package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.ActiveMqDelayStagePublisher;
import co.wethinkcode.logisticsconnect.mq.MqConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DelayStageServiceMqIT {

    private String testTopic;
    private Connection jmsConnection;
    private Session jmsSession;
    private MessageConsumer jmsConsumer;
    private ActiveMqDelayStagePublisher publisher;
    private Javalin app;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        testTopic = "test-delay-stage-it-" + UUID.randomUUID();
        String brokerUrl = ActiveMqDelayStagePublisher.resolveBrokerUrl();

        // 1. Set up JMS Consumer on isolated test topic
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        jmsConnection = factory.createConnection();
        jmsConnection.start();
        jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = jmsSession.createTopic(testTopic);
        jmsConsumer = jmsSession.createConsumer(topic);

        // 2. Set up publisher using the isolated test topic
        publisher = new ActiveMqDelayStagePublisher(brokerUrl, testTopic);

        // 3. Start app
        app = DelayStageServiceApp.createApp(null, publisher);
        app.start(0);
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            try { app.stop(); } catch (Exception ignored) {}
        }
        if (publisher != null) {
            try { publisher.close(); } catch (Exception ignored) {}
        }
        if (jmsConsumer != null) {
            try { jmsConsumer.close(); } catch (Exception ignored) {}
        }
        if (jmsSession != null) {
            try { jmsSession.close(); } catch (Exception ignored) {}
        }
        if (jmsConnection != null) {
            try { jmsConnection.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    void shouldPublishRealJmsMessageToBrokerOnStageChange() throws Exception {
        String baseUrl = "http://localhost:" + app.port();

        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/delay-stage/H-501"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stage\": 4}"))
                .build();

        HttpResponse<String> postRes = client.send(postReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, postRes.statusCode());

        // Await delivery on isolated test consumer
        Message received = jmsConsumer.receive(5000);
        assertNotNull(received, "Expected JMS message on topic " + testTopic + " within 5000ms");
        assertTrue(received instanceof TextMessage, "Message must be TextMessage");

        String text = ((TextMessage) received).getText();
        assertNotNull(text);
        JsonNode json = mapper.readTree(text);
        assertEquals("H-501", json.get("hubId").asText());
        assertEquals(4, json.get("stage").asInt());
        assertTrue(json.get("timestamp").asLong() > 0, "Timestamp must be positive epoch millis");
    }

    private String baseUrl() {
        return "http://localhost:" + app.port();
    }
}
