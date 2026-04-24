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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

class OllamaWarmupServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void warmUpNowMarksChatReadyWhenGenerateSucceeds() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/generate", exchange -> writeJson(exchange, 200, "{\"response\":\"ready\",\"done\":true}"));
        server.start();

        OllamaWarmupService service = new OllamaWarmupService(aiProperties(server.getAddress().getPort(), Duration.ofSeconds(2)),
                new ObjectMapper());

        boolean warmed = service.warmUpNow("test");

        Assertions.assertAll(
                () -> Assertions.assertTrue(warmed),
                () -> Assertions.assertTrue(service.isChatReady()),
                () -> Assertions.assertTrue(service.chatReadinessDetail().contains("已预热"))
        );
    }

    @Test
    void warmUpNowKeepsChatUnreadyWhenGenerateFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/generate", exchange -> writeJson(exchange, 500, "{\"error\":\"boom\"}"));
        server.start();

        OllamaWarmupService service = new OllamaWarmupService(aiProperties(server.getAddress().getPort(), Duration.ofSeconds(2)),
                new ObjectMapper());

        boolean warmed = service.warmUpNow("test");

        Assertions.assertAll(
                () -> Assertions.assertFalse(warmed),
                () -> Assertions.assertFalse(service.isChatReady()),
                () -> Assertions.assertTrue(service.chatReadinessDetail().contains("预热失败"))
        );
    }

    @Test
    void keepWarmFailureDoesNotClearReadyStateAfterSuccessfulWarmup() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/generate", exchange -> {
            if (attempts.incrementAndGet() == 1) {
                writeJson(exchange, 200, "{\"response\":\"ready\",\"done\":true}");
                return;
            }
            writeJson(exchange, 500, "{\"error\":\"boom\"}");
        });
        server.start();

        OllamaWarmupService service = new OllamaWarmupService(aiProperties(server.getAddress().getPort(), Duration.ofSeconds(2)),
                new ObjectMapper());

        boolean firstWarmup = service.warmUpNow("startup");
        boolean keepWarm = service.warmUpNow("keep-warm");

        Assertions.assertAll(
                () -> Assertions.assertTrue(firstWarmup),
                () -> Assertions.assertFalse(keepWarm),
                () -> Assertions.assertTrue(service.isChatReady()),
                () -> Assertions.assertTrue(service.chatReadinessDetail().contains("已预热"))
        );
    }

    private AiProperties aiProperties(int port, Duration timeout) {
        AiProperties properties = new AiProperties();
        properties.getOllama().setBaseUrl("http://127.0.0.1:" + port);
        properties.getOllama().setChatModel("llama3.2:latest");
        properties.getOllama().setWarmupEnabled(true);
        properties.getOllama().setWarmupTimeout(timeout);
        properties.getOllama().setKeepAlive("10m");
        return properties;
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
