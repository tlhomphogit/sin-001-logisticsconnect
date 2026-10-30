package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.DelayStageEvent;
import co.wethinkcode.logisticsconnect.mq.TransitDelaySubscriber;
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

class TransitServiceAppTest {

    private Javalin mockHubService;
    private Javalin transitApp;
    private TransitDelayCache cache;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Mock Hub Service (no mock delay service required in Stage 3)
        mockHubService = Javalin.create()
                .get("/hubs/H-501", ctx -> ctx.json(new HubRecord("H-501", "Western Cape", "Cape Town Port", true)))
                .get("/hubs/H-508", ctx -> ctx.json(new HubRecord("H-508", null, "Pretoria North", true)))
                .get("/hubs/H-NOSTAGE", ctx -> ctx.json(new HubRecord("H-NOSTAGE", "Gauteng", "Centurion Hub", true)))
                .get("/hubs/H-999", ctx -> ctx.status(404).json(Map.of("error", "Hub not found")))
                .get("/hubs/H-ERR", ctx -> ctx.status(500).result("Internal error"));
        mockHubService.start(0);

        // Preload local delay cache with stage 4 for H-501, stage 0 for H-508
        cache = new TransitDelayCache();
        cache.put(new DelayStageEvent("H-501", 4, 1000L));
        cache.put(new DelayStageEvent("H-508", 0, 1000L));

        // Transit Service App under test (uses cached state, no delay-stage HTTP dependency)
        transitApp = TransitServiceApp.createApp(
                "http://localhost:" + mockHubService.port(),
                cache
        );
        transitApp.start(0);
    }

    @AfterEach
    void tearDown() {
        if (transitApp != null) transitApp.stop();
        if (mockHubService != null) mockHubService.stop();
    }

    private String transitUrl() {
        return "http://localhost:" + transitApp.port();
    }

    @Test
    void shouldReturnOkForHealthEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(transitUrl() + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("OK", response.body());
    }

    @Test
    void shouldCalculateEtaCorrectlyFromCachedStage() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(transitUrl() + "/transit/H-501"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        TransitResponse transit = mapper.readValue(response.body(), TransitResponse.class);
        assertEquals("H-501", transit.hubId());
        assertEquals("Western Cape", transit.province());
        assertEquals("Cape Town Port", transit.sortingCenter());
        assertEquals(Boolean.TRUE, transit.active());
        assertEquals(4, transit.stage());
        assertEquals(24, transit.baseTransitHours());
        assertEquals(24, transit.delayHours(), "Delay hours must be 4 * 6 = 24");
        assertEquals(48, transit.minEstimatedHours(), "Min hours must be 24 + 24 = 48");
        assertEquals(56, transit.maxEstimatedHours(), "Max hours must be 48 + 8 = 56");
        assertEquals("48-56 hours", transit.estimatedArrivalWindow());
    }

    @Test
    void shouldPreserveNullProvinceForH508InTransitResponse() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(transitUrl() + "/transit/H-508"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        TransitResponse transit = mapper.readValue(response.body(), TransitResponse.class);
        assertEquals("H-508", transit.hubId());
        assertNull(transit.province(), "Province must remain null");
        assertEquals("Pretoria North", transit.sortingCenter());
        assertEquals(0, transit.stage());
        assertEquals(24, transit.minEstimatedHours());
        assertEquals(32, transit.maxEstimatedHours());
        assertEquals("24-32 hours", transit.estimatedArrivalWindow());
    }

    @Test
    void shouldReturn404WhenHubServiceReturns404() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(transitUrl() + "/transit/H-999"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Hub not found"));
    }

    @Test
    void shouldReturn502WhenHubServiceFails() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(transitUrl() + "/transit/H-ERR"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(502, response.statusCode(), "Failed upstream dependency must not fabricate successful ETA");
    }

    @Test
    void shouldReturn503WhenDelayStageIsNotEstablishedInCache() throws Exception {
        // H-NOSTAGE exists in hub-service but has not received any delay event
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(transitUrl() + "/transit/H-NOSTAGE"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode(), "Missing event must return 503 unavailable");
        assertTrue(response.body().contains("Delay stage unavailable"));
    }

    @Test
    void shouldUpdateCacheAndEtaOnValidNewerEventIncludingDecreasingStage() throws Exception {
        // Initially H-501 is stage 4 at ts 1000
        assertEquals(4, cache.get("H-501").orElseThrow().stage());

        // Update with newer event lowering stage to 1 at ts 2000
        boolean updated = cache.updateIfNewer(new DelayStageEvent("H-501", 1, 2000L));
        assertTrue(updated);
        assertEquals(1, cache.get("H-501").orElseThrow().stage());

        // Transit query now returns lower ETA window
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(transitUrl() + "/transit/H-501"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        TransitResponse transit = mapper.readValue(response.body(), TransitResponse.class);
        assertEquals(1, transit.stage());
        assertEquals(6, transit.delayHours());
        assertEquals(30, transit.minEstimatedHours());
        assertEquals(38, transit.maxEstimatedHours());
        assertEquals("30-38 hours", transit.estimatedArrivalWindow());
    }

    @Test
    void shouldRejectStaleAndDuplicateEventsInCache() {
        // Current state for H-501 is ts 1000, stage 4
        assertFalse(cache.updateIfNewer(new DelayStageEvent("H-501", 6, 500L)), "Stale event must be rejected");
        assertEquals(4, cache.get("H-501").orElseThrow().stage(), "Stage must remain 4");

        assertFalse(cache.updateIfNewer(new DelayStageEvent("H-501", 6, 1000L)), "Duplicate event must be rejected");
        assertEquals(4, cache.get("H-501").orElseThrow().stage(), "Stage must remain 4");
    }

    @Test
    void shouldValidateAndParseEventPayloadsCorrectly() {
        // Valid epoch millis
        DelayStageEvent ev1 = TransitDelaySubscriber.parseAndValidate("{\"hubId\":\"H-501\",\"stage\":3,\"timestamp\":1730000000000}");
        assertNotNull(ev1);
        assertEquals("H-501", ev1.hubId());
        assertEquals(3, ev1.stage());
        assertEquals(1730000000000L, ev1.timestamp());

        // Valid ISO-8601 timestamp string
        DelayStageEvent ev2 = TransitDelaySubscriber.parseAndValidate("{\"hubId\":\"h-502\",\"stage\":0,\"timestamp\":\"2026-07-18T10:15:00Z\"}");
        assertNotNull(ev2);
        assertEquals("H-502", ev2.hubId());
        assertEquals(0, ev2.stage());
        assertTrue(ev2.timestamp() > 0);

        // Invalid: missing stage
        assertNull(TransitDelaySubscriber.parseAndValidate("{\"hubId\":\"H-501\",\"timestamp\":1000}"));

        // Invalid: non-integer stage
        assertNull(TransitDelaySubscriber.parseAndValidate("{\"hubId\":\"H-501\",\"stage\":4.5,\"timestamp\":1000}"));
        assertNull(TransitDelaySubscriber.parseAndValidate("{\"hubId\":\"H-501\",\"stage\":\"four\",\"timestamp\":1000}"));

        // Invalid: stage out of range
        assertNull(TransitDelaySubscriber.parseAndValidate("{\"hubId\":\"H-501\",\"stage\":-1,\"timestamp\":1000}"));
        assertNull(TransitDelaySubscriber.parseAndValidate("{\"hubId\":\"H-501\",\"stage\":9,\"timestamp\":1000}"));

        // Invalid: missing/blank hubId
        assertNull(TransitDelaySubscriber.parseAndValidate("{\"stage\":3,\"timestamp\":1000}"));
        assertNull(TransitDelaySubscriber.parseAndValidate("{\"hubId\":\"   \",\"stage\":3,\"timestamp\":1000}"));

        // Invalid: missing or negative timestamp
        assertNull(TransitDelaySubscriber.parseAndValidate("{\"hubId\":\"H-501\",\"stage\":3}"));
        assertNull(TransitDelaySubscriber.parseAndValidate("{\"hubId\":\"H-501\",\"stage\":3,\"timestamp\":-5}"));
        assertNull(TransitDelaySubscriber.parseAndValidate("{\"hubId\":\"H-501\",\"stage\":3,\"timestamp\":\"invalid-date\"}"));

        // Invalid: malformed JSON
        assertNull(TransitDelaySubscriber.parseAndValidate("{not-json}"));
    }
}
