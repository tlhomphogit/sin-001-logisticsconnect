package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class IngestionServiceAppTest {

    private Javalin app;
    private int port;
    private HttpClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        HubCsvCleaner.IngestionResult result = HubCsvCleaner.loadFromClasspath();
        app = IngestionServiceApp.createApp(result.cleanedRecords());
        app.start(0); // Bind to random available port
        port = app.port();
        client = HttpClient.newHttpClient();
        objectMapper = new ObjectMapper();
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
                .uri(URI.create("http://localhost:" + port + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("OK", response.body());
    }

    @Test
    void shouldReturnCleanedHubsAsJsonArray() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/hubs"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));

        List<HubRecord> hubs = objectMapper.readValue(response.body(), new TypeReference<List<HubRecord>>() {});

        assertEquals(11, hubs.size(), "Should return exactly 11 cleaned hub records");

        // Verify all hub IDs are unique
        Set<String> uniqueIds = hubs.stream().map(HubRecord::hubId).collect(Collectors.toSet());
        assertEquals(11, uniqueIds.size(), "All hub IDs must be unique");

        // Verify H-508 has null province
        HubRecord h508 = hubs.stream().filter(h -> "H-508".equals(h.hubId())).findFirst().orElseThrow();
        assertNull(h508.province(), "H-508 province must be null");
        assertEquals("Pretoria North", h508.sortingCenter());
        assertEquals(Boolean.TRUE, h508.active());

        // Verify H-511 and H-517 have null active
        HubRecord h511 = hubs.stream().filter(h -> "H-511".equals(h.hubId())).findFirst().orElseThrow();
        assertNull(h511.active(), "H-511 active must be null");

        HubRecord h517 = hubs.stream().filter(h -> "H-517".equals(h.hubId())).findFirst().orElseThrow();
        assertNull(h517.active(), "H-517 active must be null");
    }
}
