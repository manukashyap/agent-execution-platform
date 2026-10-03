package com.conversive.aep.mocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Base for integration tests: real HTTP against the random-port app via the JDK HttpClient. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class MockIT {

    static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void resetMocks() throws Exception {
        send("POST", "/admin/reset", null, Map.of());
    }

    record Reply(int status, JsonNode body, HttpResponse<String> raw) {
        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    Reply send(String method, String path, Object body, Map<String, String> headers) throws Exception {
        return send(client, Duration.ofSeconds(30), method, path, body, headers);
    }

    Reply send(HttpClient httpClient, Duration timeout, String method, String path, Object body,
               Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        HttpResponse<String> response = httpClient.send(builder.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), parse(response.body()), response);
    }

    Reply get(String path) throws Exception {
        return send("GET", path, null, Map.of());
    }

    Reply post(String path, Object body) throws Exception {
        return send("POST", path, body, Map.of());
    }

    Reply post(String path, Object body, Map<String, String> headers) throws Exception {
        return send("POST", path, body, headers);
    }

    private static JsonNode parse(String text) throws IOException {
        return text == null || text.isBlank() ? null : JSON.readTree(text);
    }
}
