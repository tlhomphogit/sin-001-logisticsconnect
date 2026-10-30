package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HubServiceAppTest {

    private Javalin app;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void shouldReturnOkForHealthEndpoint() throws Exception {
        app = HubServiceApp.createApp("http://localhost:7050", Map.of());
        app.start(0);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("OK", response.body());
    }

    @Test
    void shouldReturnHubDetailsAndPreserveNullableFields() throws Exception {
        Map<String, HubRecord> mockHubs = Map.of(
                "H-501", new HubRecord("H-501", "Western Cape", "Cape Town Port", true),
                "H-508", new HubRecord("H-508", null, "Pretoria North", true)
        );

        app = HubServiceApp.createApp("http://localhost:7050", mockHubs);
        app.start(0);

        // 1. Query H-501
        HttpRequest request1 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/hubs/H-501"))
                .GET()
                .build();
        HttpResponse<String> response1 = client.send(request1, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response1.statusCode());
        HubRecord record1 = mapper.readValue(response1.body(), HubRecord.class);
        assertEquals("H-501", record1.hubId());
        assertEquals("Western Cape", record1.province());
        assertEquals("Cape Town Port", record1.sortingCenter());
        assertEquals(Boolean.TRUE, record1.active());

        // 2. Case insensitive lookup: h-501
        HttpRequest requestCase = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/hubs/h-501"))
                .GET()
                .build();
        HttpResponse<String> responseCase = client.send(requestCase, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, responseCase.statusCode());

        // 3. Query H-508: province must be null
        HttpRequest request2 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/hubs/H-508"))
                .GET()
                .build();
        HttpResponse<String> response2 = client.send(request2, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response2.statusCode());
        HubRecord record2 = mapper.readValue(response2.body(), HubRecord.class);
        assertEquals("H-508", record2.hubId());
        assertNull(record2.province(), "Province must remain null");
        assertEquals("Pretoria North", record2.sortingCenter());
    }

    @Test
    void shouldReturn404ForUnknownHub() throws Exception {
        Map<String, HubRecord> mockHubs = Map.of(
                "H-501", new HubRecord("H-501", "Western Cape", "Cape Town Port", true)
        );

        app = HubServiceApp.createApp("http://localhost:7050", mockHubs);
        app.start(0);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/hubs/H-999"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Hub not found"));
    }

    @Test
    void shouldReturn503WhenIngestionFailsAndCacheIsEmpty() throws Exception {
        // Point to an invalid unreachable port to simulate ingestion failure
        app = HubServiceApp.createApp("http://localhost:59999", Map.of());
        app.start(0);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + app.port() + "/hubs/H-501"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("Ingestion service unavailable"));
    }
}
