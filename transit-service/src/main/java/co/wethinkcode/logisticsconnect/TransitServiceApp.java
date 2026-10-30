package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Locale;
import java.util.Map;

public class TransitServiceApp {

    private static final Logger log = LoggerFactory.getLogger(TransitServiceApp.class);
    public static final int DEFAULT_PORT = 7053;
    public static final String DEFAULT_HUB_SERVICE_URL = "http://localhost:7051";
    public static final String DEFAULT_DELAY_STAGE_SERVICE_URL = "http://localhost:7052";

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

    public static String resolveDelayStageServiceUrl() {
        String sys = System.getProperty("delay.stage.service.base.url");
        if (sys != null && !sys.isBlank()) return sys.trim();
        String env = System.getenv("DELAY_STAGE_SERVICE_BASE_URL");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_DELAY_STAGE_SERVICE_URL;
    }

    public static Javalin createApp(String hubServiceUrl, String delayStageServiceUrl) {
        String cleanHubUrl = hubServiceUrl.replaceAll("/+$", "");
        String cleanDelayUrl = delayStageServiceUrl.replaceAll("/+$", "");

        Javalin app = Javalin.create();

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

            // 2. Fetch Delay Stage from Delay Stage Service
            String stageReqUrl = cleanDelayUrl + "/delay-stage/" + canonicalId;
            int stage;
            try {
                HttpRequest stageReq = HttpRequest.newBuilder()
                        .uri(URI.create(stageReqUrl))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> stageRes = HTTP_CLIENT.send(stageReq, HttpResponse.BodyHandlers.ofString());

                if (stageRes.statusCode() != 200) {
                    ctx.status(502).json(Map.of(
                        "error", "Upstream delay-stage-service error",
                        "status", stageRes.statusCode(),
                        "details", stageRes.body()
                    ));
                    return;
                }
                JsonNode stageJson = OBJECT_MAPPER.readTree(stageRes.body());
                if (!stageJson.has("stage") || !stageJson.get("stage").isInt()) {
                    ctx.status(502).json(Map.of(
                        "error", "Malformed response from delay-stage-service",
                        "details", "Missing or non-integer 'stage' property"
                    ));
                    return;
                }
                stage = stageJson.get("stage").asInt();
            } catch (Exception e) {
                log.error("Failed to query delay-stage-service for {}: {}", canonicalId, e.getMessage());
                ctx.status(502).json(Map.of(
                    "error", "Failed to connect to upstream delay-stage-service",
                    "details", e.getMessage()
                ));
                return;
            }

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
        String delayUrl = resolveDelayStageServiceUrl();
        log.info("Starting TransitServiceApp on port {} (HubService: {}, DelayStageService: {})...",
            DEFAULT_PORT, hubUrl, delayUrl);

        Javalin app = createApp(hubUrl, delayUrl);
        app.start(DEFAULT_PORT);
    }
}
