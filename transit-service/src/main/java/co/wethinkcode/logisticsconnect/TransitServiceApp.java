package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.DelayStageEvent;
import co.wethinkcode.logisticsconnect.mq.TransitDelaySubscriber;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class TransitServiceApp {

    private static final Logger log = LoggerFactory.getLogger(TransitServiceApp.class);
    public static final int DEFAULT_PORT = 7053;
    public static final String DEFAULT_HUB_SERVICE_URL = "http://localhost:7051";

    public static final int BASE_TRANSIT_HOURS = 24;
    public static final int DELAY_HOURS_PER_STAGE = 6;
    public static final int WINDOW_BUFFER_HOURS = 8;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static String resolveHubServiceUrl() {
        String sys = System.getProperty("hub.service.base.url");
        if (sys != null && !sys.isBlank()) return sys.trim();
        String env = System.getenv("HUB_SERVICE_BASE_URL");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_HUB_SERVICE_URL;
    }

    public static Javalin createApp(String hubServiceUrl, TransitDelayCache cache) {
        return createApp(hubServiceUrl, cache, null);
    }

    public static Javalin createApp(String hubServiceUrl, TransitDelayCache cache, AutoCloseable subscriber) {
        String cleanHubUrl = hubServiceUrl.replaceAll("/+$", "");
        TransitDelayCache delayCache = cache != null ? cache : new TransitDelayCache();

        Javalin app = Javalin.create();

        if (subscriber != null) {
            app.events(event -> event.serverStopped(() -> {
                try {
                    subscriber.close();
                } catch (Exception e) {
                    log.warn("Error closing subscriber on server stop: {}", e.getMessage());
                }
            }));
        }

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/transit/{hubId}", ctx -> {
            String rawId = ctx.pathParam("hubId");
            if (rawId == null || rawId.isBlank()) {
                ctx.status(400).json(Map.of("error", "Hub ID cannot be blank"));
                return;
            }
            String canonicalId = rawId.trim().toUpperCase(Locale.ROOT);

            // 1. Fetch Hub Information from Hub Service
            String hubReqUrl = cleanHubUrl + "/hubs/" + canonicalId;
            HubRecord hub;
            try {
                HttpRequest hubReq = HttpRequest.newBuilder()
                        .uri(URI.create(hubReqUrl))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> hubRes = HTTP_CLIENT.send(hubReq, HttpResponse.BodyHandlers.ofString());

                if (hubRes.statusCode() == 404) {
                    ctx.status(404).json(Map.of(
                        "error", "Hub not found",
                        "hubId", canonicalId
                    ));
                    return;
                }
                if (hubRes.statusCode() != 200) {
                    ctx.status(502).json(Map.of(
                        "error", "Upstream hub-service error",
                        "status", hubRes.statusCode(),
                        "details", hubRes.body()
                    ));
                    return;
                }
                hub = OBJECT_MAPPER.readValue(hubRes.body(), HubRecord.class);
            } catch (Exception e) {
                log.error("Failed to query hub-service for {}: {}", canonicalId, e.getMessage());
                ctx.status(502).json(Map.of(
                    "error", "Failed to connect to upstream hub-service",
                    "details", e.getMessage()
                ));
                return;
            }

            // 2. Fetch Delay Stage from local ActiveMQ subscriber cache (no HTTP to delay-stage-service)
            Optional<DelayStageEvent> cachedOpt = delayCache.get(canonicalId);
            if (cachedOpt.isEmpty()) {
                log.warn("Delay stage not established in local cache for hub {}", canonicalId);
                ctx.status(503).json(Map.of(
                    "error", "Delay stage unavailable",
                    "message", "No delay stage event received for hub: " + canonicalId,
                    "hubId", canonicalId
                ));
                return;
            }

            int stage = cachedOpt.get().stage();

            // 3. Compute ETA Arrival Window
            int delayHours = stage * DELAY_HOURS_PER_STAGE;
            int minHours = BASE_TRANSIT_HOURS + delayHours;
            int maxHours = minHours + WINDOW_BUFFER_HOURS;
            String arrivalWindow = minHours + "-" + maxHours + " hours";

            TransitResponse response = new TransitResponse(
                canonicalId,
                hub.province(),
                hub.sortingCenter(),
                hub.active(),
                stage,
                BASE_TRANSIT_HOURS,
                delayHours,
                minHours,
                maxHours,
                arrivalWindow
            );

            ctx.status(200).json(response);
        });

        return app;
    }

    public static void main(String[] args) {
        String hubUrl = resolveHubServiceUrl();
        log.info("Starting TransitServiceApp on port {} (HubService: {})...", DEFAULT_PORT, hubUrl);

        TransitDelayCache cache = new TransitDelayCache();
        TransitDelaySubscriber subscriber = new TransitDelaySubscriber(cache);

        try {
            subscriber.start();
        } catch (Exception e) {
            log.error("Failed to connect ActiveMQ subscriber on startup: {}", e.getMessage(), e);
        }

        Javalin app = createApp(hubUrl, cache, subscriber);
        Runtime.getRuntime().addShutdownHook(new Thread(subscriber::close));
        app.start(DEFAULT_PORT);
    }
}
