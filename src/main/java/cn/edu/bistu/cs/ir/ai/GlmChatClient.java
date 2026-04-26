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

@Service
public class GlmChatClient {

    private final AiProperties aiProperties;

    private final ObjectMapper objectMapper;

    public GlmChatClient(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    public String generate(String prompt, Duration timeout) {
        AiProperties.Glm glm = aiProperties.getGlm();
        if (!glm.isEnabled()) {
            throw new IllegalStateException("GLM support is disabled by configuration.");
        }
        if (!StringUtils.hasText(glm.getApiKey())) {
            throw new IllegalStateException("GLM API key is not configured.");
        }
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("prompt不可以为空");
        }
        try {
            Duration requestTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? glm.getChatTimeout()
                    : timeout;
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(requestTimeout)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(buildGenerateUri(glm))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + glm.getApiKey())
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(prompt, glm)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("GLM chat returned HTTP " + response.statusCode());
            }
            return extractText(response.body());
        }
        catch (IllegalStateException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException("GLM chat invocation failed", e);
        }
    }

    private URI buildGenerateUri(AiProperties.Glm glm) {
        String normalizedBaseUrl = glm.getBaseUrl();
        if (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }
        return URI.create(normalizedBaseUrl + "/api/paas/v4/chat/completions");
    }

    private String buildPayload(String prompt, AiProperties.Glm glm) throws Exception {
        var root = objectMapper.createObjectNode();
        root.put("model", glm.getModel());
        var messages = root.putArray("messages");
        var message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);

        var generationConfig = root.putObject("generation_config");
        generationConfig.put("temperature", glm.getTemperature());
        generationConfig.put("top_p", glm.getTopP());
        if (glm.getMaxTokens() != null) {
            generationConfig.put("max_tokens", glm.getMaxTokens());
        }

        return objectMapper.writeValueAsString(root);
    }

    private String extractText(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode message = choices.get(0).path("message");
        if (message == null || message.isMissingNode()) {
            return null;
        }
        return message.path("content").asText();
    }
}
