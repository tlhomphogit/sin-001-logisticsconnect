package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DelayStageServiceAppTest {

    private Javalin app;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        app = DelayStageServiceApp.createApp(Map.of("H-500", 2));
        app.start(0);
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void shouldReturnOkForHealthEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("OK", response.body());
    }

    @Test
    void shouldReturnInitialDefaultStageForUnknownHub() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/delay-stage/H-501"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        DelayStageRecord record = mapper.readValue(response.body(), DelayStageRecord.class);
        assertEquals("H-501", record.hubId());
        assertEquals(0, record.stage(), "Default initial delay stage must be 0");
    }

    @Test
    void shouldReturnPreloadedStage() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/delay-stage/H-500"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        DelayStageRecord record = mapper.readValue(response.body(), DelayStageRecord.class);
        assertEquals("H-500", record.hubId());
        assertEquals(2, record.stage());
    }

    @Test
    void shouldUpdateDelayStageSuccessfully() throws Exception {
        HttpRequest post = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/delay-stage/H-501"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stage\": 4}"))
                .build();

        HttpResponse<String> postRes = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, postRes.statusCode());

        DelayStageRecord postRecord = mapper.readValue(postRes.body(), DelayStageRecord.class);
        assertEquals("H-501", postRecord.hubId());
        assertEquals(4, postRecord.stage());

        // Verify state persistence on subsequent GET
        HttpRequest get = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/delay-stage/H-501"))
                .GET()
                .build();
        HttpResponse<String> getRes = client.send(get, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getRes.statusCode());
        assertEquals(4, mapper.readValue(getRes.body(), DelayStageRecord.class).stage());
    }

    @Test
    void shouldAcceptBoundaryStagesZeroAndEight() throws Exception {
        // Stage 0
        HttpResponse<String> res0 = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/delay-stage/H-501"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stage\": 0}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res0.statusCode());
        assertEquals(0, mapper.readValue(res0.body(), DelayStageRecord.class).stage());

        // Stage 8
        HttpResponse<String> res8 = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/delay-stage/H-501"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stage\": 8}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res8.statusCode());
        assertEquals(8, mapper.readValue(res8.body(), DelayStageRecord.class).stage());
    }

    @Test
    void shouldRejectInvalidUpdatesAndLeaveExistingStateUnchanged() throws Exception {
        // Initialize stage to 3
        client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/delay-stage/H-501"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stage\": 3}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        // 1. Out of range negative: -1
        assertBadRequest("{\"stage\": -1}");

        // 2. Out of range positive: 9
        assertBadRequest("{\"stage\": 9}");

        // 3. Fractional value: 4.5
        assertBadRequest("{\"stage\": 4.5}");

        // 4. Non-numeric string: "high"
        assertBadRequest("{\"stage\": \"high\"}");

        // 5. Missing stage field: {}
        assertBadRequest("{}");

        // 6. Null stage field: {"stage": null}
        assertBadRequest("{\"stage\": null}");

        // 7. Malformed JSON
        assertBadRequest("{stage: 3");

        // Assert previous valid stage (3) is completely preserved
        HttpResponse<String> check = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/delay-stage/H-501"))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, check.statusCode());
        assertEquals(3, mapper.readValue(check.body(), DelayStageRecord.class).stage());
    }

    private void assertBadRequest(String invalidJson) throws Exception {
        HttpResponse<String> res = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/delay-stage/H-501"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(invalidJson))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, res.statusCode(), "Expected 400 for payload: " + invalidJson);
    }
}
