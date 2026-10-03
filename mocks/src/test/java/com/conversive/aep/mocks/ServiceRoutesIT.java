package com.conversive.aep.mocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceRoutesIT extends MockIT {

    private static final Map<String, Object> CONTACT =
            Map.of("external_ref", "lead-1", "name", "Ada", "email", "ada@x.test", "label", "hot");

    @Test
    void crmCreatesDuplicatesLooksUpAndDeletes() throws Exception {
        String first = post("/crm/contacts", CONTACT).body().get("contact_id").asText();
        String second = post("/crm/contacts", CONTACT).body().get("contact_id").asText();

        assertThat(second).isNotEqualTo(first);
        JsonNode found = get("/crm/contacts?external_ref=lead-1").body().get("contacts");
        assertThat(found).hasSize(2);
        assertThat(found.get(0).get("label").asText()).isEqualTo("hot");
        assertThat(get("/crm/contacts?external_ref=other").body().get("contacts")).isEmpty();

        assertThat(send("DELETE", "/crm/contacts/" + first, null, Map.of()).status()).isEqualTo(200);
        assertThat(get("/crm/contacts?external_ref=lead-1").body().get("contacts")).hasSize(1);
        assertThat(send("DELETE", "/crm/contacts/" + first, null, Map.of()).status()).isEqualTo(404);
        assertThat(post("/crm/contacts", Map.of("name", "x")).status()).isEqualTo(400);
    }

    @Test
    void leadsAreDeterministicWithDefaultAndMaxLimit() throws Exception {
        JsonNode five = get("/leads").body().get("leads");
        assertThat(five).hasSize(5);
        assertThat(five.get(0).fieldNames()).toIterable().containsExactly("id", "name", "email", "company");
        assertThat(get("/leads?limit=3").body().get("leads").get(2)).isEqualTo(five.get(2));
        assertThat(get("/leads?limit=1000").body().get("leads")).hasSize(200);
    }

    @Test
    void messagingReturnsFreshIdEachTime() throws Exception {
        Map<String, Object> msg = Map.of("to", "a@b.test", "body", "hi");

        String first = post("/messaging/send", msg).body().get("message_id").asText();
        String second = post("/messaging/send", msg).body().get("message_id").asText();

        assertThat(first).isNotEqualTo(second);
        assertThat(post("/messaging/send", Map.of("body", "x")).status()).isEqualTo(400);
    }
}
