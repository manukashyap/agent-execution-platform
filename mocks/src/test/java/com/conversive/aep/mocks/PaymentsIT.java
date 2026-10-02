package com.conversive.aep.mocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentsIT extends MockIT {

    private static final Map<String, Object> CHARGE = Map.of("customer_id", "c-1", "amount_cents", 1999, "currency", "USD");

    private static Map<String, String> key(String k) {
        return Map.of("Idempotency-Key", k);
    }

    @Test
    void chargeHappyPathAndLookup() throws Exception {
        Reply reply = post("/payments/charge", CHARGE, key("k-1"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("status").asText()).isEqualTo("succeeded");
        assertThat(reply.body().get("amount_cents").asInt()).isEqualTo(1999);
        String chargeId = reply.body().get("charge_id").asText();

        assertThat(get("/payments/charges/" + chargeId).body().get("customer_id").asText()).isEqualTo("c-1");
        JsonNode byKey = get("/payments/charges?idempotency_key=k-1").body().get("charges");
        assertThat(byKey).hasSize(1);
        assertThat(byKey.get(0).get("charge_id").asText()).isEqualTo(chargeId);
        assertThat(get("/payments/charges/ch_missing").status()).isEqualTo(404);
    }

    @Test
    void missingKeyIs400() throws Exception {
        assertThat(post("/payments/charge", CHARGE).status()).isEqualTo(400);
        assertThat(post("/payments/refund", Map.of("charge_id", "x")).status()).isEqualTo(400);
    }

    @Test
    void sameKeyAfterCompletionReturnsStoredResponseWithoutNewCharge() throws Exception {
        Reply first = post("/payments/charge", CHARGE, key("k-2"));
        Reply second = post("/payments/charge", CHARGE, key("k-2"));

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(executedCharges()).hasSize(1);
    }

    @Test
    void refundIsIdempotentAndRequiresKnownCharge() throws Exception {
        String chargeId = post("/payments/charge", CHARGE, key("k-3")).body().get("charge_id").asText();

        Reply first = post("/payments/refund", Map.of("charge_id", chargeId), key("r-1"));
        Reply second = post("/payments/refund", Map.of("charge_id", chargeId), key("r-1"));

        assertThat(first.body().get("status").asText()).isEqualTo("refunded");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(get("/admin/calls/payments").body().get("refunds")).hasSize(1);
        assertThat(post("/payments/refund", Map.of("charge_id", "ch_nope"), key("r-2")).status()).isEqualTo(404);
    }

    @Test
    void clientTimeoutThenRetryYieldsExactlyOneChargeCompletedServerSide() throws Exception {
        post("/admin/latency", Map.of("route", "payments.charge", "ms", 1500));
        HttpClient shortClient = HttpClient.newHttpClient();

        assertThatThrownBy(() -> send(shortClient, Duration.ofSeconds(1), "POST", "/payments/charge", CHARGE, key("k-4")))
                .isInstanceOf(HttpTimeoutException.class);

        Reply inFlight = post("/payments/charge", CHARGE, key("k-4"));
        assertThat(inFlight.status()).isEqualTo(409);
        assertThat(inFlight.body().get("error").asText()).isEqualTo("in_progress");
        assertThat(executedCharges()).isEmpty();

        awaitCharges(1);
        Reply retry = post("/payments/charge", CHARGE, key("k-4"));

        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().get("charge_id").asText()).isEqualTo(executedCharges().get(0).get("charge_id").asText());
        assertThat(executedCharges()).hasSize(1);
        assertThat(get("/admin/calls").body().get("payments.charge").asLong()).isEqualTo(3);
    }

    private JsonNode executedCharges() throws Exception {
        return get("/admin/calls/payments").body().get("charges");
    }

    private void awaitCharges(int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (executedCharges().size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(executedCharges()).hasSize(expected);
    }
}
