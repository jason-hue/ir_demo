package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

class ProviderStatusServiceQdrantProbeTest {

    private HttpServer server;

    private volatile String lastRawPath;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
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

    private ProviderStatus probe(int responseStatus) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/collections", exchange -> writeResponse(exchange, responseStatus));
        server.start();

        AiProperties properties = new AiProperties();
        properties.getQdrant().setEnabled(true);
        properties.getQdrant().setHost("127.0.0.1");
        properties.getQdrant().setHttpPort(server.getAddress().getPort());
        properties.getQdrant().setCollectionName("news article chunks");
        properties.getProviderStatus().setConnectTimeout(java.time.Duration.ofSeconds(2));
        properties.getProviderStatus().setReadTimeout(java.time.Duration.ofSeconds(2));

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
