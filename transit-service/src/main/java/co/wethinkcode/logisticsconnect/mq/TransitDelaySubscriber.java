package co.wethinkcode.logisticsconnect.mq;

import co.wethinkcode.logisticsconnect.TransitDelayCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.time.Instant;
import java.util.Locale;

public class TransitDelaySubscriber implements MessageListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TransitDelaySubscriber.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String brokerUrl;
    private final String topicName;
    private final TransitDelayCache cache;

    private Connection connection;
    private Session session;
    private MessageConsumer consumer;
    private volatile boolean running = false;

    public TransitDelaySubscriber(TransitDelayCache cache) {
        this(resolveBrokerUrl(), resolveTopicName(), cache);
    }

    public TransitDelaySubscriber(String brokerUrl, String topicName, TransitDelayCache cache) {
        this.brokerUrl = brokerUrl;
        this.topicName = topicName;
        this.cache = cache;
    }

    public static String resolveBrokerUrl() {
        String sys = System.getProperty("activemq.broker.url");
        if (sys != null && !sys.isBlank()) return sys.trim();
        String env = System.getenv("ACTIVEMQ_BROKER_URL");
        if (env != null && !env.isBlank()) return env.trim();
        return MqConfig.BROKER_URL;
    }

    public static String resolveTopicName() {
        String sys = System.getProperty("activemq.topic");
        if (sys != null && !sys.isBlank()) return sys.trim();
        String env = System.getenv("ACTIVEMQ_TOPIC");
        if (env != null && !env.isBlank()) return env.trim();
        return MqConfig.TOPIC;
    }

    public synchronized void start() throws Exception {
        if (running) return;
        log.info("Starting TransitDelaySubscriber connecting to {} on topic {}...", brokerUrl, topicName);
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        connection = factory.createConnection();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = session.createTopic(topicName);
        consumer = session.createConsumer(topic);
        consumer.setMessageListener(this);
        connection.start();
        running = true;
        log.info("TransitDelaySubscriber successfully listening on topic {}", topicName);
    }

    @Override
    public void onMessage(Message message) {
        try {
            if (!(message instanceof TextMessage textMessage)) {
                log.warn("Discarded non-TextMessage of type {}", message != null ? message.getClass().getName() : "null");
                return;
            }

            String payload = textMessage.getText();
            if (payload == null || payload.isBlank()) {
                log.warn("Discarded empty or blank message payload");
                return;
            }

            DelayStageEvent event = parseAndValidate(payload);
            if (event != null) {
                cache.updateIfNewer(event);
            }
        } catch (Throwable t) {
            log.error("Unexpected error processing message: {}", t.getMessage(), t);
        }
    }

    public static DelayStageEvent parseAndValidate(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            log.warn("Cannot parse null or blank JSON payload");
            return null;
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(jsonText);
        } catch (JsonProcessingException e) {
            log.warn("Discarded malformed JSON message: {}", e.getOriginalMessage());
            return null;
        }

        if (root == null || !root.isObject()) {
            log.warn("Discarded message: payload must be a JSON object");
            return null;
        }

        // 1. Validate hubId
        if (!root.has("hubId") || root.get("hubId").isNull() || !root.get("hubId").isTextual()) {
            log.warn("Discarded message: missing or non-textual 'hubId'");
            return null;
        }
        String rawHubId = root.get("hubId").asText();
        if (rawHubId.isBlank()) {
            log.warn("Discarded message: 'hubId' cannot be blank");
            return null;
        }
        String canonicalId = rawHubId.trim().toUpperCase(Locale.ROOT);

        // 2. Validate stage
        if (!root.has("stage") || root.get("stage").isNull() || !root.get("stage").isInt()) {
            log.warn("Discarded message for hub {}: missing or non-integer 'stage'", canonicalId);
            return null;
        }
        int stage = root.get("stage").asInt();
        if (stage < 0 || stage > 8) {
            log.warn("Discarded message for hub {}: stage {} outside valid range [0, 8]", canonicalId, stage);
            return null;
        }

        // 3. Validate timestamp
        if (!root.has("timestamp") || root.get("timestamp").isNull()) {
            log.warn("Discarded message for hub {}: missing 'timestamp'", canonicalId);
            return null;
        }
        JsonNode tsNode = root.get("timestamp");
        long timestampMillis;
        if (tsNode.isNumber()) {
            timestampMillis = tsNode.asLong();
        } else if (tsNode.isTextual()) {
            try {
                timestampMillis = Instant.parse(tsNode.asText().trim()).toEpochMilli();
            } catch (Exception e) {
                log.warn("Discarded message for hub {}: invalid ISO-8601 timestamp string '{}'", canonicalId, tsNode.asText());
                return null;
            }
        } else {
            log.warn("Discarded message for hub {}: timestamp must be numeric or ISO-8601 string", canonicalId);
            return null;
        }

        if (timestampMillis <= 0) {
            log.warn("Discarded message for hub {}: timestamp must be positive, got {}", canonicalId, timestampMillis);
            return null;
        }

        return new DelayStageEvent(canonicalId, stage, timestampMillis);
    }

    @Override
    public synchronized void close() {
        running = false;
        if (consumer != null) {
            try { consumer.close(); } catch (Exception e) { log.debug("Error closing consumer", e); }
            consumer = null;
        }
        if (session != null) {
            try { session.close(); } catch (Exception e) { log.debug("Error closing session", e); }
            session = null;
        }
        if (connection != null) {
            try { connection.close(); } catch (Exception e) { log.debug("Error closing connection", e); }
            connection = null;
        }
        log.info("TransitDelaySubscriber closed for topic {}", topicName);
    }

    public boolean isRunning() {
        return running;
    }

    public TransitDelayCache getCache() {
        return cache;
    }
}
