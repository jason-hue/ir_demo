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

class GeminiChatClientTest {

    private HttpServer server;

    private volatile String lastApiKey;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void generateParsesTextFromGeminiResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/gemini-2.0-flash:generateContent", exchange -> {
            lastApiKey = exchange.getRequestHeaders().getFirst("x-goog-api-key");
            writeJsonResponse(exchange, 200,
                    "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Gemini答案[1]\"}]}}]}");
        });
        server.start();

        AiProperties properties = new AiProperties();
        properties.getGemini().setEnabled(true);
        properties.getGemini().setApiKey("demo-key");
        properties.getGemini().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getGemini().setModel("gemini-2.0-flash");

        GeminiChatClient client = new GeminiChatClient(properties, new ObjectMapper());
        String answer = client.generate("你好", Duration.ofSeconds(2));

        Assertions.assertAll(
                () -> Assertions.assertEquals("Gemini答案[1]", answer),
                () -> Assertions.assertEquals("demo-key", lastApiKey)
        );
    }

    @Test
    void generateFailsWhenApiKeyIsMissing() {
        AiProperties properties = new AiProperties();
        properties.getGemini().setEnabled(true);

        GeminiChatClient client = new GeminiChatClient(properties, new ObjectMapper());

        IllegalStateException error = Assertions.assertThrows(IllegalStateException.class,
                () -> client.generate("你好", Duration.ofSeconds(2)));
        Assertions.assertEquals("Gemini API key is not configured.", error.getMessage());
    }

    private void writeJsonResponse(HttpExchange exchange, int responseStatus, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
