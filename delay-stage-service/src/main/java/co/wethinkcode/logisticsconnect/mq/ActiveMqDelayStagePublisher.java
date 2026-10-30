package co.wethinkcode.logisticsconnect.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;

public class ActiveMqDelayStagePublisher implements DelayStagePublisher {

    private static final Logger log = LoggerFactory.getLogger(ActiveMqDelayStagePublisher.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String brokerUrl;
    private final String topicName;

    private Connection connection;
    private Session session;
    private MessageProducer producer;

    public ActiveMqDelayStagePublisher() {
        this(resolveBrokerUrl(), resolveTopicName());
    }

    public ActiveMqDelayStagePublisher(String brokerUrl, String topicName) {
        this.brokerUrl = brokerUrl;
        this.topicName = topicName;
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

    private synchronized void ensureConnected() throws Exception {
        if (connection == null) {
            log.info("Connecting ActiveMQ producer to {} on topic {}...", brokerUrl, topicName);
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
            connection = factory.createConnection();
            connection.start();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(topicName);
            producer = session.createProducer(topic);
            producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
            log.info("ActiveMQ producer connected successfully to {}", topicName);
        }
    }

    @Override
    public synchronized void publish(String hubId, int stage, long timestamp) throws Exception {
        ensureConnected();
        DelayStageEvent event = new DelayStageEvent(hubId, stage, timestamp);
        String payload = OBJECT_MAPPER.writeValueAsString(event);
        TextMessage textMessage = session.createTextMessage(payload);
        producer.send(textMessage);
        log.info("Published DelayStageEvent to {}: {}", topicName, payload);
    }

    @Override
    public synchronized void close() {
        if (producer != null) {
            try { producer.close(); } catch (Exception e) { log.debug("Error closing producer", e); }
            producer = null;
        }
        if (session != null) {
            try { session.close(); } catch (Exception e) { log.debug("Error closing session", e); }
            session = null;
        }
        if (connection != null) {
            try { connection.close(); } catch (Exception e) { log.debug("Error closing connection", e); }
            connection = null;
        }
        log.info("ActiveMQ producer closed for topic {}", topicName);
    }

    public String getBrokerUrl() {
        return brokerUrl;
    }

    public String getTopicName() {
        return topicName;
    }
}
