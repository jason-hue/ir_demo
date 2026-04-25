package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;

class ProviderStatusServiceDegradedTest {

    private static final String CONFIG_DISABLED_DETAIL = "Qdrant vector support is disabled by configuration.";

    private HttpServer server;

    private volatile String lastRawPath;

    private volatile String lastMethod;

    private volatile String lastApiKey;

    @Test
    void ollamaEmbeddingStatusReturnsDegradedWhenEmbedEndpointFailsAfterModelDiscoverySucceeds() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> writeJsonResponse(exchange,
                200,
                "{\"models\":[{\"model\":\"nomic-embed-text\"}]}"));
        server.createContext("/api/embed", exchange -> writeJsonResponse(exchange,
                500,
                "{\"error\":\"embed failed\"}"));
        server.start();

        ProviderStatus status = ollamaEmbeddingStatusAtPort(server.getAddress().getPort());

        Assertions.assertAll(
                () -> Assertions.assertEquals(ProviderAvailabilityState.DEGRADED, status.state()),
                () -> Assertions.assertTrue(status.enabled()),
                () -> Assertions.assertTrue(status.detail().contains("embedding probe returned HTTP 500")),
                () -> Assertions.assertEquals("/api/embed", lastRawPath),
                () -> Assertions.assertEquals("POST", lastMethod)
        );
    }

    @Test
    void qdrantStatusReturnsUnavailableWhenCollectionEndpointCannotBeReached() throws Exception {
        ProviderStatus status = probeUnavailable();

        Assertions.assertAll(
                () -> Assertions.assertEquals(ProviderAvailabilityState.UNAVAILABLE, status.state()),
                () -> Assertions.assertTrue(status.detail().contains("did not respond")),
                () -> Assertions.assertNull(lastRawPath)
        );
    }

    @Test
    void qdrantStatusPreservesInterruptedFlagWhenProbeIsInterrupted() {
        AiProperties properties = new AiProperties();
        Object qdrant = ReflectionTestUtils.getField(properties, "qdrant");
        Object providerStatus = ReflectionTestUtils.getField(properties, "providerStatus");
        ReflectionTestUtils.setField(qdrant, "enabled", true);
        ReflectionTestUtils.setField(qdrant, "host", "127.0.0.1");
        ReflectionTestUtils.setField(qdrant, "httpPort", 1);
        ReflectionTestUtils.setField(qdrant, "collectionName", "news article chunks");
        ReflectionTestUtils.setField(providerStatus, "connectTimeout", Duration.ofSeconds(2));
        ReflectionTestUtils.setField(providerStatus, "readTimeout", Duration.ofMillis(100));
        ProviderStatusService service = new ProviderStatusService(properties, new ObjectMapper());
        boolean interruptedBefore = Thread.currentThread().isInterrupted();

        try {
            Thread.currentThread().interrupt();
            ProviderStatus status = service.qdrantStatus();

            Assertions.assertAll(
                    () -> Assertions.assertEquals(ProviderAvailabilityState.UNAVAILABLE, status.state()),
                    () -> Assertions.assertTrue(Thread.currentThread().isInterrupted())
            );
        }
        finally {
            if (!interruptedBefore) {
                Thread.interrupted();
            }
        }
    }

    @Test
    void qdrantStatusReturnsDisabledWhenVectorSupportIsDisabledByConfiguration() {
        AiProperties properties = new AiProperties();

        ProviderStatus status = new ProviderStatusService(properties, new ObjectMapper()).qdrantStatus();

        Assertions.assertAll(
                () -> Assertions.assertEquals(ProviderAvailabilityState.DISABLED, status.state()),
                () -> Assertions.assertFalse(status.enabled()),
                () -> Assertions.assertEquals(CONFIG_DISABLED_DETAIL, status.detail())
        );
    }

    @Test
    void qdrantStatusReturnsAvailableWhenCollectionEndpointRespondsWith200() throws Exception {
        ProviderStatus status = probe(200);

        Assertions.assertAll(
                () -> Assertions.assertEquals(ProviderAvailabilityState.AVAILABLE, status.state()),
                () -> Assertions.assertTrue(status.detail().contains("available at")),
                () -> Assertions.assertEquals("/collections/news%20article%20chunks", lastRawPath)
        );
    }

    @Test
    void qdrantStatusReturnsDegradedWhenCollectionEndpointRespondsWith404() throws Exception {
        ProviderStatus status = probe(404);

        Assertions.assertAll(
                () -> Assertions.assertEquals(ProviderAvailabilityState.DEGRADED, status.state()),
                () -> Assertions.assertTrue(status.detail().contains("does not exist")),
                () -> Assertions.assertEquals("/collections/news%20article%20chunks", lastRawPath)
        );
    }

    @Test
    void qdrantStatusReturnsDegradedWhenCollectionEndpointRespondsWithOtherNon2xx() throws Exception {
        ProviderStatus status = probe(503);

        Assertions.assertAll(
                () -> Assertions.assertEquals(ProviderAvailabilityState.DEGRADED, status.state()),
                () -> Assertions.assertTrue(status.detail().contains("probe returned HTTP 503")),
                () -> Assertions.assertEquals("/collections/news%20article%20chunks", lastRawPath)
        );
    }

    @Test
    void qdrantStatusReturnsMachineCheckableDegradedDetailWhenCollectionProbeTimesOut() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections", exchange -> {
            lastRawPath = exchange.getRequestURI().getRawPath();
            try {
                Thread.sleep(250L);
                writeResponse(exchange, 200);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catch (IOException ignored) {
            }
        });
        server.start();

        ProviderStatus status = qdrantStatusAtPort(server.getAddress().getPort(), Duration.ofMillis(100));

        Assertions.assertAll(
                () -> Assertions.assertEquals(ProviderAvailabilityState.DEGRADED, status.state()),
                () -> Assertions.assertTrue(status.enabled()),
                () -> Assertions.assertTrue(status.detail().contains("[QDRANT_TIMEOUT]")),
                () -> Assertions.assertFalse(status.detail().equals(CONFIG_DISABLED_DETAIL)),
                () -> Assertions.assertEquals("/collections/news%20article%20chunks", lastRawPath)
        );
    }

    @Test
    void geminiChatStatusReturnsUnavailableWhenApiKeyIsMissing() {
        AiProperties properties = new AiProperties();
        properties.setChatProvider("gemini");
        properties.getGemini().setEnabled(true);
        properties.getGemini().setApiKey(null);

        ProviderStatus status = new ProviderStatusService(properties, new ObjectMapper()).geminiChatStatus();

        Assertions.assertAll(
                () -> Assertions.assertEquals(ProviderAvailabilityState.UNAVAILABLE, status.state()),
                () -> Assertions.assertTrue(status.enabled()),
                () -> Assertions.assertEquals("Gemini API key is not configured.", status.detail())
        );
    }

    @Test
    void snapshotUsesGeminiAsActiveChatProviderWhenConfigured() {
        server = createGeminiServer(200);
        server.start();

        AiProperties properties = new AiProperties();
        properties.setChatProvider("gemini");
        properties.getOllama().setEnabled(false);
        properties.getGemini().setEnabled(true);
        properties.getGemini().setApiKey("demo-key");
        properties.getGemini().setModel("gemini-2.0-flash");
        properties.getGemini().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        ProviderStatusSnapshot snapshot = new ProviderStatusService(properties, new ObjectMapper()).snapshot();

        Assertions.assertAll(
                () -> Assertions.assertEquals("gemini-chat", snapshot.ollamaChat().provider()),
                () -> Assertions.assertEquals(ProviderAvailabilityState.AVAILABLE, snapshot.ollamaChat().state()),
                () -> Assertions.assertEquals(ProviderAvailabilityState.DISABLED, snapshot.ollamaEmbedding().state()),
                () -> Assertions.assertEquals(AiFallbackMode.LEXICAL_ONLY, snapshot.fallbackMode()),
                () -> Assertions.assertEquals("/v1beta/models/gemini-2.0-flash:generateContent", lastRawPath),
                () -> Assertions.assertEquals("POST", lastMethod),
                () -> Assertions.assertEquals("demo-key", lastApiKey)
        );
    }

    @Test
    void geminiChatStatusReturnsUnavailableWhenProbeReturnsUnauthorized() throws Exception {
        server = createGeminiServer(401);
        server.start();

        ProviderStatus status = geminiChatStatusAtPort(server.getAddress().getPort(), Duration.ofSeconds(2));

        Assertions.assertAll(
                () -> Assertions.assertEquals(ProviderAvailabilityState.UNAVAILABLE, status.state()),
                () -> Assertions.assertTrue(status.enabled()),
                () -> Assertions.assertTrue(status.detail().contains("HTTP 401")),
                () -> Assertions.assertEquals("/v1beta/models/gemini-2.0-flash:generateContent", lastRawPath),
                () -> Assertions.assertEquals("POST", lastMethod),
                () -> Assertions.assertEquals("demo-key", lastApiKey)
        );
    }

    @Test
    void geminiChatStatusReturnsDegradedWhenProbeReturnsServerError() throws Exception {
        server = createGeminiServer(503);
        server.start();

        ProviderStatus status = geminiChatStatusAtPort(server.getAddress().getPort(), Duration.ofSeconds(2));

        Assertions.assertAll(
                () -> Assertions.assertEquals(ProviderAvailabilityState.DEGRADED, status.state()),
                () -> Assertions.assertTrue(status.enabled()),
                () -> Assertions.assertTrue(status.detail().contains("HTTP 503")),
                () -> Assertions.assertEquals("/v1beta/models/gemini-2.0-flash:generateContent", lastRawPath)
        );
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ProviderStatus probeUnavailable() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        int port = server.getAddress().getPort();
        server.stop(0);

        return qdrantStatusAtPort(port);
    }

    private ProviderStatus probe(int responseStatus) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections", exchange -> writeResponse(exchange, responseStatus));
        server.start();

        return qdrantStatusAtPort(server.getAddress().getPort());
    }

    private ProviderStatus qdrantStatusAtPort(int port) {
        return qdrantStatusAtPort(port, Duration.ofSeconds(2));
    }

    private ProviderStatus qdrantStatusAtPort(int port, Duration readTimeout) {
        AiProperties properties = new AiProperties();
        Object qdrant = ReflectionTestUtils.getField(properties, "qdrant");
        Object providerStatus = ReflectionTestUtils.getField(properties, "providerStatus");
        ReflectionTestUtils.setField(qdrant, "enabled", true);
        ReflectionTestUtils.setField(qdrant, "host", "127.0.0.1");
        ReflectionTestUtils.setField(qdrant, "httpPort", port);
        ReflectionTestUtils.setField(qdrant, "collectionName", "news article chunks");
        ReflectionTestUtils.setField(providerStatus, "connectTimeout", Duration.ofSeconds(2));
        ReflectionTestUtils.setField(providerStatus, "readTimeout", readTimeout);

        return new ProviderStatusService(properties, new ObjectMapper()).qdrantStatus();
    }

    private ProviderStatus ollamaEmbeddingStatusAtPort(int port) {
        AiProperties properties = new AiProperties();
        Object ollama = ReflectionTestUtils.getField(properties, "ollama");
        Object providerStatus = ReflectionTestUtils.getField(properties, "providerStatus");
        ReflectionTestUtils.setField(ollama, "enabled", true);
        ReflectionTestUtils.setField(ollama, "baseUrl", "http://127.0.0.1:" + port);
        ReflectionTestUtils.setField(ollama, "embeddingModel", "nomic-embed-text");
        ReflectionTestUtils.setField(providerStatus, "connectTimeout", Duration.ofSeconds(2));
        ReflectionTestUtils.setField(providerStatus, "readTimeout", Duration.ofSeconds(2));

        return new ProviderStatusService(properties, new ObjectMapper())
                .ollamaEmbeddingStatus(properties.getOllama().getEmbeddingModel());
    }

    private ProviderStatus geminiChatStatusAtPort(int port, Duration readTimeout) {
        AiProperties properties = new AiProperties();
        Object providerStatus = ReflectionTestUtils.getField(properties, "providerStatus");
        properties.setChatProvider("gemini");
        properties.getGemini().setEnabled(true);
        properties.getGemini().setApiKey("demo-key");
        properties.getGemini().setModel("gemini-2.0-flash");
        properties.getGemini().setBaseUrl("http://127.0.0.1:" + port);
        ReflectionTestUtils.setField(providerStatus, "connectTimeout", Duration.ofSeconds(2));
        ReflectionTestUtils.setField(providerStatus, "readTimeout", readTimeout);

        return new ProviderStatusService(properties, new ObjectMapper()).geminiChatStatus();
    }

    private HttpServer createGeminiServer(int responseStatus) {
        try {
            HttpServer geminiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            geminiServer.createContext("/v1beta/models/gemini-2.0-flash:generateContent", exchange -> {
                lastRawPath = exchange.getRequestURI().getRawPath();
                lastMethod = exchange.getRequestMethod();
                lastApiKey = exchange.getRequestHeaders().getFirst("x-goog-api-key");
                writeJsonResponse(exchange, responseStatus, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
            });
            return geminiServer;
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void writeResponse(HttpExchange exchange, int responseStatus) throws IOException {
        lastRawPath = exchange.getRequestURI().getRawPath();
        lastMethod = exchange.getRequestMethod();
        byte[] payload = new byte[0];
        exchange.sendResponseHeaders(responseStatus, payload.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        }
    }

    private void writeJsonResponse(HttpExchange exchange, int responseStatus, String payload) throws IOException {
        lastRawPath = exchange.getRequestURI().getRawPath();
        lastMethod = exchange.getRequestMethod();
        byte[] bytes = payload.getBytes();
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
