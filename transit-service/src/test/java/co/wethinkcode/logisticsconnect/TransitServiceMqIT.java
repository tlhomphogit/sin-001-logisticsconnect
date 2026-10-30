package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.TransitDelaySubscriber;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransitServiceMqIT {

    private String testTopic;
    private Connection jmsConnection;
    private Session jmsSession;
    private MessageProducer jmsProducer;
    private Javalin mockHubService;
    private Javalin transitApp;
    private TransitDelaySubscriber subscriber;
    private TransitDelayCache cache;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        testTopic = "test-transit-it-" + UUID.randomUUID();
        String brokerUrl = TransitDelaySubscriber.resolveBrokerUrl();

        // 1. Mock Hub Service
        mockHubService = Javalin.create()
                .get("/hubs/H-501", ctx -> ctx.json(new HubRecord("H-501", "Western Cape", "Cape Town Port", true)));
        mockHubService.start(0);

        // 2. Set up JMS Producer on isolated test topic
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        jmsConnection = factory.createConnection();
        jmsConnection.start();
        jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = jmsSession.createTopic(testTopic);
        jmsProducer = jmsSession.createProducer(topic);
        jmsProducer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

        // 3. Set up subscriber and transit service
        cache = new TransitDelayCache();
        subscriber = new TransitDelaySubscriber(brokerUrl, testTopic, cache);
        subscriber.start();

        transitApp = TransitServiceApp.createApp(
                "http://localhost:" + mockHubService.port(),
                cache,
                subscriber
        );
        transitApp.start(0);
    }

    @AfterEach
    void tearDown() {
        if (transitApp != null) {
            try { transitApp.stop(); } catch (Exception ignored) {}
        }
        if (subscriber != null) {
            try { subscriber.close(); } catch (Exception ignored) {}
        }
        if (mockHubService != null) {
            try { mockHubService.stop(); } catch (Exception ignored) {}
        }
        if (jmsProducer != null) {
            try { jmsProducer.close(); } catch (Exception ignored) {}
        }
        if (jmsSession != null) {
            try { jmsSession.close(); } catch (Exception ignored) {}
        }
        if (jmsConnection != null) {
            try { jmsConnection.close(); } catch (Exception ignored) {}
        }
    }

    private String transitUrl() {
        return "http://localhost:" + transitApp.port();
    }

    @Test
    void shouldConsumeRealJmsMessageAndCalculateEtaCorrectly() throws Exception {
        // Step 1: Before any event is published, transit returns 503 unavailable
        HttpRequest reqBefore = HttpRequest.newBuilder()
                .uri(URI.create(transitUrl() + "/transit/H-501"))
                .GET()
                .build();
        HttpResponse<String> resBefore = client.send(reqBefore, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, resBefore.statusCode());

        // Step 2: Publish stage 4 event to the isolated test topic
        long now = System.currentTimeMillis();
        TextMessage msg1 = jmsSession.createTextMessage("{\"hubId\":\"H-501\",\"stage\":4,\"timestamp\":" + now + "}");
        jmsProducer.send(msg1);

        // Step 3: Wait bounded time for consumption and cache update
        boolean receivedStage4 = false;
        TransitResponse responseStage4 = null;
        for (int i = 0; i < 50; i++) {
            HttpResponse<String> res = client.send(reqBefore, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                responseStage4 = mapper.readValue(res.body(), TransitResponse.class);
                if (responseStage4.stage() == 4) {
                    receivedStage4 = true;
                    break;
                }
            }
            Thread.sleep(100);
        }
        assertTrue(receivedStage4, "Expected transit service to reflect consumed stage 4 within 5s");
        assertEquals(4, responseStage4.stage());
        assertEquals("48-56 hours", responseStage4.estimatedArrivalWindow());

        // Step 4: Publish newer event lowering stage to 2
        TextMessage msg2 = jmsSession.createTextMessage("{\"hubId\":\"H-501\",\"stage\":2,\"timestamp\":" + (now + 5000) + "}");
        jmsProducer.send(msg2);

        // Step 5: Wait bounded time for transit service to update to stage 2
        boolean receivedStage2 = false;
        TransitResponse responseStage2 = null;
        for (int i = 0; i < 50; i++) {
            HttpResponse<String> res = client.send(reqBefore, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                TransitResponse resp = mapper.readValue(res.body(), TransitResponse.class);
                if (resp.stage() == 2) {
                    responseStage2 = resp;
                    receivedStage2 = true;
                    break;
                }
            }
            Thread.sleep(100);
        }
        assertTrue(receivedStage2, "Expected transit service to reflect consumed stage 2 within 5s");
        assertEquals(2, responseStage2.stage());
        assertEquals("36-44 hours", responseStage2.estimatedArrivalWindow());
    }
}
