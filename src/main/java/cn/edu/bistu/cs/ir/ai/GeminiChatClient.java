package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;

@Service
public class GeminiChatClient {

    private final AiProperties aiProperties;

    private final ObjectMapper objectMapper;

    public GeminiChatClient(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    public String generate(String prompt, Duration timeout) {
        AiProperties.Gemini gemini = aiProperties.getGemini();
        if (!gemini.isEnabled()) {
            throw new IllegalStateException("Gemini support is disabled by configuration.");
        }
        if (!StringUtils.hasText(gemini.getApiKey())) {
            throw new IllegalStateException("Gemini API key is not configured.");
        }
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("prompt不可以为空");
        }
        try {
            Duration requestTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? gemini.getChatTimeout()
                    : timeout;
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(requestTimeout)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(buildGenerateUri(gemini))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", gemini.getApiKey())
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(prompt, gemini)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Gemini chat returned HTTP " + response.statusCode());
            }
            return extractText(response.body());
        }
        catch (IllegalStateException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException("Gemini chat invocation failed", e);
        }
    }

    private URI buildGenerateUri(AiProperties.Gemini gemini) {
        String normalizedBaseUrl = gemini.getBaseUrl();
        if (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }
        return URI.create(normalizedBaseUrl
                + "/v1beta/models/"
                + URLEncoder.encode(gemini.getModel(), StandardCharsets.UTF_8)
                + ":generateContent");
    }

    private String buildPayload(String prompt, AiProperties.Gemini gemini) throws Exception {
        var root = objectMapper.createObjectNode();
        var contents = root.putArray("contents");
        var content = contents.addObject();
        var parts = content.putArray("parts");
        parts.addObject().put("text", prompt);
        var generationConfig = root.putObject("generationConfig");
        generationConfig.put("temperature", gemini.getTemperature());
        generationConfig.put("topP", gemini.getTopP());
        if (gemini.getMaxOutputTokens() != null) {
            generationConfig.put("maxOutputTokens", gemini.getMaxOutputTokens());
        }
        return objectMapper.writeValueAsString(root);
    }

    private String extractText(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return null;
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        Iterator<JsonNode> iterator = parts.iterator();
        while (iterator.hasNext()) {
            String partText = iterator.next().path("text").asText();
            if (!partText.isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(partText);
            }
        }
        return text.isEmpty() ? null : text.toString();
    }
}
