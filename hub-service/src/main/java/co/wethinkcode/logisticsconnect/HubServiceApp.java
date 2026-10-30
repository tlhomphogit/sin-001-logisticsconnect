package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HubServiceApp {

    private static final Logger log = LoggerFactory.getLogger(HubServiceApp.class);
    public static final int DEFAULT_PORT = 7051;
    public static final String DEFAULT_INGESTION_URL = "http://localhost:7050";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static String resolveIngestionUrl() {
        String sysProp = System.getProperty("ingestion.base.url");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp.trim();
        }
        String envVar = System.getenv("INGESTION_BASE_URL");
        if (envVar != null && !envVar.isBlank()) {
            return envVar.trim();
        }
        return DEFAULT_INGESTION_URL;
    }

    public static Map<String, HubRecord> fetchHubsFromIngestion(String baseUrl) throws IOException, InterruptedException {
        String targetUrl = baseUrl.replaceAll("/+$", "") + "/hubs";
        log.info("Fetching cleaned hub records from {}", targetUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to load hubs from ingestion service. HTTP status: " + response.statusCode());
        }

        List<HubRecord> records = OBJECT_MAPPER.readValue(response.body(), new TypeReference<List<HubRecord>>() {});
        if (records == null || records.isEmpty()) {
            throw new IOException("Ingestion service returned an empty or invalid dataset");
        }

        Map<String, HubRecord> map = new ConcurrentHashMap<>();
        for (HubRecord r : records) {
            if (r.hubId() != null) {
                map.put(r.hubId().toUpperCase(Locale.ROOT), r);
            }
        }
        return map;
    }

    public static Javalin createApp(String ingestionBaseUrl, Map<String, HubRecord> preloadedHubs) {
        Map<String, HubRecord> hubCache = new ConcurrentHashMap<>();
        if (preloadedHubs != null && !preloadedHubs.isEmpty()) {
            hubCache.putAll(preloadedHubs);
        }

        Javalin app = Javalin.create();

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/hubs/{hubId}", ctx -> {
            String rawId = ctx.pathParam("hubId");
            if (rawId == null || rawId.isBlank()) {
                ctx.status(400).json(Map.of("error", "Hub ID cannot be blank"));
                return;
            }

            // Attempt on-demand load if cache is empty
            if (hubCache.isEmpty()) {
                synchronized (hubCache) {
                    if (hubCache.isEmpty()) {
                        try {
                            Map<String, HubRecord> fetched = fetchHubsFromIngestion(ingestionBaseUrl);
                            hubCache.putAll(fetched);
                            log.info("Loaded {} hubs into cache on demand", hubCache.size());
                        } catch (Exception e) {
                            log.error("Failed on-demand ingestion load: {}", e.getMessage());
                            ctx.status(503).json(Map.of(
                                "error", "Ingestion service unavailable",
                                "message", "Unable to load hub data from ingestion-service: " + e.getMessage()
                            ));
                            return;
                        }
                    }
                }
            }

            String canonicalId = rawId.trim().toUpperCase(Locale.ROOT);
            HubRecord record = hubCache.get(canonicalId);
            if (record != null) {
                ctx.status(200).json(record);
            } else {
                ctx.status(404).json(Map.of(
                    "error", "Hub not found",
                    "hubId", canonicalId
                ));
            }
        });

        return app;
    }

    public static void main(String[] args) {
        String ingestionUrl = resolveIngestionUrl();
        log.info("Starting HubServiceApp on port {} (Ingestion URL: {})...", DEFAULT_PORT, ingestionUrl);

        Map<String, HubRecord> initialHubs = null;
        try {
            initialHubs = fetchHubsFromIngestion(ingestionUrl);
            log.info("Preloaded {} hubs on startup", initialHubs.size());
        } catch (Exception e) {
            log.warn("Ingestion preloading deferred ({}): {}", ingestionUrl, e.getMessage());
        }

        Javalin app = createApp(ingestionUrl, initialHubs);
        app.start(DEFAULT_PORT);
    }
}
