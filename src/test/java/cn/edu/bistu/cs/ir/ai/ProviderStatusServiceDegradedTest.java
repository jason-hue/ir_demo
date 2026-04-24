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

    private void writeResponse(HttpExchange exchange, int responseStatus) throws IOException {
        lastRawPath = exchange.getRequestURI().getRawPath();
        byte[] payload = new byte[0];
        exchange.sendResponseHeaders(responseStatus, payload.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        }
    }
}
