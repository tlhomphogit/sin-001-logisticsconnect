package co.wethinkcode.logisticsconnect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.wethinkcode.logisticsconnect.mq.ActiveMqDelayStagePublisher;
import co.wethinkcode.logisticsconnect.mq.DelayStagePublisher;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DelayStageServiceApp {

    private static final Logger log = LoggerFactory.getLogger(DelayStageServiceApp.class);
    public static final int DEFAULT_PORT = 7052;
    public static final int DEFAULT_INITIAL_STAGE = 0;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Object UPDATE_LOCK = new Object();

    public static Javalin createApp(Map<String, Integer> initialStages) {
        return createApp(initialStages, new ActiveMqDelayStagePublisher());
    }

    public static Javalin createApp(Map<String, Integer> initialStages, DelayStagePublisher publisher) {
        Map<String, Integer> stageStore = new ConcurrentHashMap<>();
        if (initialStages != null) {
            stageStore.putAll(initialStages);
        }

        Javalin app = Javalin.create();

        if (publisher != null) {
            app.events(event -> event.serverStopped(() -> {
                try {
                    publisher.close();
                } catch (Exception e) {
                    log.warn("Error closing publisher on server stop: {}", e.getMessage());
                }
            }));
        }

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/delay-stage/{hubId}", ctx -> {
            String rawId = ctx.pathParam("hubId");
            if (rawId == null || rawId.isBlank()) {
                ctx.status(400).json(Map.of("error", "Hub ID cannot be blank"));
                return;
            }
            String canonicalId = rawId.trim().toUpperCase(Locale.ROOT);
            int stage = stageStore.getOrDefault(canonicalId, DEFAULT_INITIAL_STAGE);
            ctx.status(200).json(new DelayStageRecord(canonicalId, stage));
        });

        app.post("/delay-stage/{hubId}", ctx -> {
            String rawId = ctx.pathParam("hubId");
            if (rawId == null || rawId.isBlank()) {
                ctx.status(400).json(Map.of("error", "Hub ID cannot be blank"));
                return;
            }
            String canonicalId = rawId.trim().toUpperCase(Locale.ROOT);

            String body = ctx.body();
            if (body == null || body.isBlank()) {
                ctx.status(400).json(Map.of("error", "Request body cannot be empty"));
                return;
            }

            JsonNode root;
            try {
                root = OBJECT_MAPPER.readTree(body);
            } catch (JsonProcessingException e) {
                ctx.status(400).json(Map.of("error", "Malformed JSON payload: " + e.getOriginalMessage()));
                return;
            }

            if (root == null || !root.isObject()) {
                ctx.status(400).json(Map.of("error", "Request body must be a JSON object"));
                return;
            }

            if (!root.has("stage") || root.get("stage").isNull()) {
                ctx.status(400).json(Map.of("error", "Missing required field 'stage'"));
                return;
            }

            JsonNode stageNode = root.get("stage");
            if (!stageNode.isInt()) {
                ctx.status(400).json(Map.of("error", "Field 'stage' must be an integer between 0 and 8"));
                return;
            }

            int newStage = stageNode.asInt();
            if (newStage < 0 || newStage > 8) {
                ctx.status(400).json(Map.of(
                    "error", "Stage must be between 0 and 8 inclusive",
                    "received", newStage
                ));
                return;
            }

            synchronized (UPDATE_LOCK) {
                int currentStage = stageStore.getOrDefault(canonicalId, DEFAULT_INITIAL_STAGE);
                if (currentStage == newStage && stageStore.containsKey(canonicalId)) {
                    // No effective stage change
                    ctx.status(200).json(new DelayStageRecord(canonicalId, currentStage));
                    return;
                }

                long timestamp = System.currentTimeMillis();
                if (publisher != null) {
                    try {
                        publisher.publish(canonicalId, newStage, timestamp);
                    } catch (Exception e) {
                        log.error("Failed to publish stage update for hub {} to broker: {}", canonicalId, e.getMessage());
                        ctx.status(500).json(Map.of(
                            "error", "Failed to publish delay stage event to broker",
                            "details", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                        ));
                        return;
                    }
                }

                stageStore.put(canonicalId, newStage);
                log.info("Updated delay stage for hub {} to {} (ts={})", canonicalId, newStage, timestamp);
                ctx.status(200).json(new DelayStageRecord(canonicalId, newStage));
            }
        });

        return app;
    }

    public static void main(String[] args) {
        log.info("Starting DelayStageServiceApp on port {}...", DEFAULT_PORT);
        ActiveMqDelayStagePublisher publisher = new ActiveMqDelayStagePublisher();
        Javalin app = createApp(null, publisher);
        Runtime.getRuntime().addShutdownHook(new Thread(publisher::close));
        app.start(DEFAULT_PORT);
    }
}
