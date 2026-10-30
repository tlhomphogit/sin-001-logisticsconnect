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

class TransitServiceAppTest {

    private Javalin mockHubService;
    private Javalin mockDelayService;
    private Javalin transitApp;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Mock Hub Service
        mockHubService = Javalin.create()
                .get("/hubs/H-501", ctx -> ctx.json(new HubRecord("H-501", "Western Cape", "Cape Town Port", true)))
                .get("/hubs/H-508", ctx -> ctx.json(new HubRecord("H-508", null, "Pretoria North", true)))
                .get("/hubs/H-BAD", ctx -> ctx.json(new HubRecord("H-BAD", "Gauteng", "Pretoria North", true)))
                .get("/hubs/H-999", ctx -> ctx.status(404).json(Map.of("error", "Hub not found")))
                .get("/hubs/H-ERR", ctx -> ctx.status(500).result("Internal error"));
        mockHubService.start(0);

        // Mock Delay Stage Service
        mockDelayService = Javalin.create()
                .get("/delay-stage/H-501", ctx -> ctx.json(Map.of("hubId", "H-501", "stage", 4)))
                .get("/delay-stage/H-508", ctx -> ctx.json(Map.of("hubId", "H-508", "stage", 0)))
                .get("/delay-stage/H-BAD", ctx -> ctx.result("not json"));
        mockDelayService.start(0);

        // Transit Service App under test
        transitApp = TransitServiceApp.createApp(
                "http://localhost:" + mockHubService.port(),
                "http://localhost:" + mockDelayService.port()
        );
        transitApp.start(0);
    }

    @AfterEach
    void tearDown() {
        if (transitApp != null) transitApp.stop();
        if (mockHubService != null) mockHubService.stop();
        if (mockDelayService != null) mockDelayService.stop();
    }

    @Test
    void shouldReturnOkForHealthEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + transitApp.port() + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("OK", response.body());
    }

    @Test
    void shouldCalculateEtaCorrectlyForKnownHub() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + transitApp.port() + "/transit/H-501"))
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
                .uri(URI.create("http://localhost:" + transitApp.port() + "/transit/H-508"))
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
                .uri(URI.create("http://localhost:" + transitApp.port() + "/transit/H-999"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Hub not found"));
    }

    @Test
    void shouldReturn502WhenHubServiceFails() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + transitApp.port() + "/transit/H-ERR"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(502, response.statusCode(), "Failed upstream dependency must not fabricate successful ETA");
    }

    @Test
    void shouldReturn502WhenDelayStageServiceReturnsMalformedData() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + transitApp.port() + "/transit/H-BAD"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(502, response.statusCode(), "Malformed dependency payload must return 502");
    }
}
