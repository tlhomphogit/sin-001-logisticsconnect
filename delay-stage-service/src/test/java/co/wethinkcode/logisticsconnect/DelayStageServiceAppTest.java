package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.DelayStageEvent;
import co.wethinkcode.logisticsconnect.mq.DelayStagePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DelayStageServiceAppTest {

    private Javalin app;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private TestDelayStagePublisher testPublisher;

    static class TestDelayStagePublisher implements DelayStagePublisher {
        final List<DelayStageEvent> publishedEvents = Collections.synchronizedList(new ArrayList<>());
        boolean shouldFail = false;

        @Override
        public void publish(String hubId, int stage, long timestamp) throws Exception {
            if (shouldFail) {
                throw new RuntimeException("Simulated JMS connection failure");
            }
            publishedEvents.add(new DelayStageEvent(hubId, stage, timestamp));
        }
    }

    @BeforeEach
    void setUp() {
        testPublisher = new TestDelayStagePublisher();
        app = DelayStageServiceApp.createApp(Map.of("H-500", 2), testPublisher);
        app.start(0);
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private String baseUrl() {
        return "http://localhost:" + app.port();
    }

    @Test
    void shouldReturnOkForHealth() throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/health")).GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertEquals("OK", res.body());
    }

    @Test
    void shouldReturnDefaultStageZeroForUnrecordedHub() throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/delay-stage/H-501")).GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        DelayStageRecord record = mapper.readValue(res.body(), DelayStageRecord.class);
        assertEquals("H-501", record.hubId());
        assertEquals(0, record.stage());
    }

    @Test
    void shouldReturnPreloadedStage() throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/delay-stage/H-500")).GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        DelayStageRecord record = mapper.readValue(res.body(), DelayStageRecord.class);
        assertEquals("H-500", record.hubId());
        assertEquals(2, record.stage());
    }

    @Test
    void shouldPublishEventAndReturn200OnValidStageUpdate() throws Exception {
        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/delay-stage/H-501"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stage\": 4}"))
                .build();

        HttpResponse<String> postRes = client.send(postReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, postRes.statusCode());
        DelayStageRecord record = mapper.readValue(postRes.body(), DelayStageRecord.class);
        assertEquals("H-501", record.hubId());
        assertEquals(4, record.stage());

        assertEquals(1, testPublisher.publishedEvents.size());
        DelayStageEvent event = testPublisher.publishedEvents.get(0);
        assertEquals("H-501", event.hubId());
        assertEquals(4, event.stage());
        assertTrue(event.timestamp() > 0);

        // Subsequent GET verifies updated stage
        HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/delay-stage/H-501")).GET().build();
        HttpResponse<String> getRes = client.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getRes.statusCode());
        assertEquals(4, mapper.readValue(getRes.body(), DelayStageRecord.class).stage());
    }

    @Test
    void shouldNotPublishWhenEffectiveStageDoesNotChange() throws Exception {
        // H-500 was preloaded with stage 2. Posting stage 2 again should not publish duplicate event.
        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/delay-stage/H-500"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stage\": 2}"))
                .build();

        HttpResponse<String> postRes = client.send(postReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, postRes.statusCode());
        assertEquals(0, testPublisher.publishedEvents.size(), "Should not publish event when stage didn't change");
    }

    @Test
    void shouldFailAndPreserveStateWhenPublisherThrowsException() throws Exception {
        // Set publisher to fail
        testPublisher.shouldFail = true;

        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/delay-stage/H-500"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stage\": 5}"))
                .build();

        HttpResponse<String> postRes = client.send(postReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(500, postRes.statusCode());
        assertTrue(postRes.body().contains("Failed to publish delay stage event to broker"));

        // Confirm state remains previous stage (2), not updated to 5
        HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/delay-stage/H-500")).GET().build();
        HttpResponse<String> getRes = client.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getRes.statusCode());
        assertEquals(2, mapper.readValue(getRes.body(), DelayStageRecord.class).stage());
    }

    @Test
    void shouldRejectInvalidUpdatesAndNotPublishNorMutateState() throws Exception {
        String[] invalidBodies = {
                "{\"stage\": -1}",
                "{\"stage\": 9}",
                "{\"stage\": 4.5}",
                "{\"stage\": \"four\"}",
                "{\"stage\": null}",
                "{}",
                "{malformed_json}"
        };

        for (String invalidBody : invalidBodies) {
            HttpRequest postReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/delay-stage/H-500"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(invalidBody))
                    .build();

            HttpResponse<String> postRes = client.send(postReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, postRes.statusCode(), "Expected 400 for payload: " + invalidBody);
        }

        assertEquals(0, testPublisher.publishedEvents.size(), "No events should be published on invalid requests");

        // Verify state is still preloaded stage 2
        HttpRequest getReq = HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/delay-stage/H-500")).GET().build();
        HttpResponse<String> getRes = client.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getRes.statusCode());
        assertEquals(2, mapper.readValue(getRes.body(), DelayStageRecord.class).stage());
    }
}
