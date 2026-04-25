package cn.edu.bistu.cs.ir.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

@Service
public class ProviderStatusService {

    private static final Logger log = LoggerFactory.getLogger(ProviderStatusService.class);

    static final String VECTOR_TIMEOUT_DETAIL_PREFIX = "[" + ArticleChunkVectorSyncService.QDRANT_TIMEOUT_CODE + "]";

    private final cn.edu.bistu.cs.ir.config.AiProperties aiProperties;

    private final ObjectMapper objectMapper;

    private final ObjectProvider<OllamaWarmupService> ollamaWarmupServiceProvider;

    public ProviderStatusService(cn.edu.bistu.cs.ir.config.AiProperties aiProperties, ObjectMapper objectMapper) {
        this(aiProperties, objectMapper, null);
    }

    @Autowired
    public ProviderStatusService(cn.edu.bistu.cs.ir.config.AiProperties aiProperties,
                                  ObjectMapper objectMapper,
                                  ObjectProvider<OllamaWarmupService> ollamaWarmupServiceProvider) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.ollamaWarmupServiceProvider = ollamaWarmupServiceProvider;
    }

    public ProviderStatusSnapshot snapshot() {
        ProviderStatus ollamaChat = activeChatStatus();
        ProviderStatus ollamaEmbedding = ollamaEmbeddingStatus(aiProperties.getOllama().getEmbeddingModel());
        ProviderStatus qdrant = qdrantStatus();
        AiFallbackMode fallbackMode = isAiReady(ollamaChat, ollamaEmbedding, qdrant)
                ? AiFallbackMode.AI_READY
                : AiFallbackMode.LEXICAL_ONLY;
        return new ProviderStatusSnapshot(ollamaChat, ollamaEmbedding, qdrant, true, fallbackMode);
    }

    public ProviderStatus activeChatStatus() {
        String provider = aiProperties.getChatProvider();
        if (provider == null || provider.isBlank() || "ollama".equalsIgnoreCase(provider)) {
            ProviderStatus ollamaChat = ollamaModelStatus("ollama-chat", aiProperties.getOllama().getChatModel());
            return applyWarmupReadiness(ollamaChat);
        }
        if ("gemini".equalsIgnoreCase(provider)) {
            return geminiChatStatus();
        }
        return new ProviderStatus(provider + "-chat", ProviderAvailabilityState.DEGRADED, true,
                "Unsupported chat provider '" + provider + "'.");
    }

    public ProviderStatus geminiChatStatus() {
        if (!aiProperties.getGemini().isEnabled()) {
            return new ProviderStatus("gemini-chat", ProviderAvailabilityState.DISABLED, false,
                    "Gemini support is disabled by configuration.");
        }
        if (!StringUtils.hasText(aiProperties.getGemini().getApiKey())) {
            return new ProviderStatus("gemini-chat", ProviderAvailabilityState.UNAVAILABLE, true,
                    "Gemini API key is not configured.");
        }
        if (!StringUtils.hasText(aiProperties.getGemini().getModel())) {
            return new ProviderStatus("gemini-chat", ProviderAvailabilityState.DEGRADED, true,
                    "Gemini model is not configured.");
        }

        String probeUrl = geminiGenerateContentUrl();
        ProbeResult probe = httpPost(probeUrl,
                geminiProbePayload(aiProperties.getGemini().getModel()),
                aiProperties.getGemini().getApiKey());
        if (probe.timedOut()) {
            return new ProviderStatus("gemini-chat", ProviderAvailabilityState.UNAVAILABLE, true,
                    "Gemini probe timed out after " + formatTimeout(aiProperties.getProviderStatus().getReadTimeout())
                            + " at " + probeUrl + ".");
        }

        HttpResponse<String> response = probe.response();
        if (response == null) {
            return new ProviderStatus("gemini-chat", ProviderAvailabilityState.UNAVAILABLE, true,
                    "Gemini did not respond at " + probeUrl + ".");
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return new ProviderStatus("gemini-chat", ProviderAvailabilityState.AVAILABLE, true,
                    "Gemini chat model '" + aiProperties.getGemini().getModel() + "' is available.");
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return new ProviderStatus("gemini-chat", ProviderAvailabilityState.UNAVAILABLE, true,
                    "Gemini probe returned HTTP " + response.statusCode() + ".");
        }
        return new ProviderStatus("gemini-chat", ProviderAvailabilityState.DEGRADED, true,
                "Gemini probe returned HTTP " + response.statusCode() + ".");
    }

    public ProviderStatus ollamaModelStatus(String providerName, String configuredModel) {
        if (!aiProperties.getOllama().isEnabled()) {
            return new ProviderStatus(providerName, ProviderAvailabilityState.DISABLED, false,
                    "Ollama support is disabled by configuration.");
        }

        ProbeResult probe = httpGet(normalizeBaseUrl(aiProperties.getOllama().getBaseUrl()) + "/api/tags");
        HttpResponse<String> response = probe.response();
        if (response == null) {
            return new ProviderStatus(providerName, ProviderAvailabilityState.UNAVAILABLE, true,
                    "Ollama did not respond at " + aiProperties.getOllama().getBaseUrl());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new ProviderStatus(providerName, ProviderAvailabilityState.DEGRADED, true,
                    "Ollama probe returned HTTP " + response.statusCode());
        }

        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode models = root.path("models");
            if (!models.isArray()) {
                return new ProviderStatus(providerName, ProviderAvailabilityState.DEGRADED, true,
                        "Ollama probe response did not include a model list.");
            }

            if (containsConfiguredModel(models, configuredModel)) {
                return new ProviderStatus(providerName, ProviderAvailabilityState.AVAILABLE, true,
                        "Model '" + configuredModel + "' is available locally.");
            }

            return new ProviderStatus(providerName, ProviderAvailabilityState.DEGRADED, true,
                    "Ollama is reachable, but model '" + configuredModel + "' is not available locally.");
        }
        catch (IOException e) {
            log.debug("Failed to parse Ollama probe response", e);
            return new ProviderStatus(providerName, ProviderAvailabilityState.DEGRADED, true,
                    "Ollama is reachable, but the probe response could not be parsed.");
        }
    }

    public ProviderStatus ollamaEmbeddingStatus(String configuredModel) {
        ProviderStatus modelStatus = ollamaModelStatus("ollama-embedding", configuredModel);
        if (modelStatus.state() != ProviderAvailabilityState.AVAILABLE) {
            return modelStatus;
        }

        ProbeResult probe = httpPost(normalizeBaseUrl(aiProperties.getOllama().getBaseUrl()) + "/api/embed",
                embeddingProbePayload(configuredModel));
        if (probe.timedOut()) {
            return new ProviderStatus("ollama-embedding", ProviderAvailabilityState.DEGRADED, true,
                    "Ollama embedding probe timed out after "
                            + formatTimeout(aiProperties.getProviderStatus().getReadTimeout())
                            + " for model '" + configuredModel + "'.");
        }

        HttpResponse<String> response = probe.response();
        if (response == null) {
            return new ProviderStatus("ollama-embedding", ProviderAvailabilityState.DEGRADED, true,
                    "Ollama embedding probe did not complete for model '" + configuredModel + "'.");
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new ProviderStatus("ollama-embedding", ProviderAvailabilityState.DEGRADED, true,
                    "Ollama embedding probe returned HTTP " + response.statusCode()
                            + " for model '" + configuredModel + "'.");
        }
        return modelStatus;
    }

    public ProviderStatus qdrantStatus() {
        if (!aiProperties.getQdrant().isEnabled()) {
            return new ProviderStatus("qdrant", ProviderAvailabilityState.DISABLED, false,
                    "Qdrant vector support is disabled by configuration.");
        }

        String baseUrl = (aiProperties.getQdrant().isUseTls() ? "https" : "http") + "://"
                + aiProperties.getQdrant().getHost() + ":" + aiProperties.getQdrant().getHttpPort();
        String collectionName = aiProperties.getQdrant().getCollectionName();
        ProbeResult probe = httpGet(baseUrl + "/collections/" + encodePathSegment(collectionName));
        if (probe.timedOut()) {
            return new ProviderStatus("qdrant", ProviderAvailabilityState.DEGRADED, true,
                    VECTOR_TIMEOUT_DETAIL_PREFIX + " Qdrant collection probe timed out after "
                            + formatTimeout(aiProperties.getProviderStatus().getReadTimeout())
                            + " at " + baseUrl + " while checking collection '" + collectionName + "'.");
        }

        HttpResponse<String> response = probe.response();
        if (response == null) {
            return new ProviderStatus("qdrant", ProviderAvailabilityState.UNAVAILABLE, true,
                    "Qdrant did not respond at " + baseUrl + " while checking collection '" + collectionName + "'.");
        }

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true,
                    "Qdrant collection '" + collectionName + "' is available at " + baseUrl + ".");
        }

        if (response.statusCode() == 404) {
            return new ProviderStatus("qdrant", ProviderAvailabilityState.DEGRADED, true,
                    "Qdrant is reachable at " + baseUrl + ", but collection '" + collectionName + "' does not exist.");
        }

        return new ProviderStatus("qdrant", ProviderAvailabilityState.DEGRADED, true,
                "Qdrant is reachable at " + baseUrl + ", but collection '" + collectionName + "' probe returned HTTP " + response.statusCode() + ".");
    }

    private boolean isAiReady(ProviderStatus ollamaChat, ProviderStatus ollamaEmbedding, ProviderStatus qdrant) {
        return ollamaChat.state() == ProviderAvailabilityState.AVAILABLE
                && ollamaEmbedding.state() == ProviderAvailabilityState.AVAILABLE
                && (!qdrant.enabled() || qdrant.state() == ProviderAvailabilityState.AVAILABLE);
    }

    private ProbeResult httpGet(String url) {
        return httpRequest(HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(aiProperties.getProviderStatus().getReadTimeout())
                .build(), url);
    }

    private ProbeResult httpPost(String url, String payload) {
        return httpRequest(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(aiProperties.getProviderStatus().getReadTimeout())
                .build(), url);
    }

    private ProbeResult httpPost(String url, String payload, String apiKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(aiProperties.getProviderStatus().getReadTimeout());
        if (StringUtils.hasText(apiKey)) {
            builder.header("x-goog-api-key", apiKey);
        }
        return httpRequest(builder.build(), url);
    }

    private ProbeResult httpRequest(HttpRequest request, String url) {
        try {
            Duration connectTimeout = aiProperties.getProviderStatus().getConnectTimeout();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .build();
            return ProbeResult.fromResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
        }
        catch (ConnectException e) {
            log.debug("Provider probe connection refused for {}", url);
            return ProbeResult.unavailableProbe();
        }
        catch (HttpTimeoutException e) {
            log.debug("Provider probe timed out for {}", url);
            return ProbeResult.timeoutProbe();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Provider probe interrupted for {}", url);
            return ProbeResult.unavailableProbe();
        }
        catch (Exception e) {
            log.debug("Provider probe failed for {}", url, e);
            return ProbeResult.unavailableProbe();
        }
    }

    private String embeddingProbePayload(String configuredModel) {
        return objectMapper.createObjectNode()
                .put("model", configuredModel)
                .put("input", "health-check")
                .toString();
    }

    private String geminiProbePayload(String configuredModel) {
        return objectMapper.createObjectNode()
                .putArray("contents")
                .addObject()
                .putArray("parts")
                .addObject()
                .put("text", "health-check")
                .toPrettyString();
    }

    private String geminiGenerateContentUrl() {
        return normalizeBaseUrl(aiProperties.getGemini().getBaseUrl())
                + "/v1beta/models/"
                + encodePathSegment(aiProperties.getGemini().getModel())
                + ":generateContent";
    }

    private String formatTimeout(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis % 1000 == 0) {
            return (millis / 1000) + "秒";
        }
        return millis + "毫秒";
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private ProviderStatus applyWarmupReadiness(ProviderStatus ollamaChat) {
        if (!aiProperties.getOllama().isWarmupEnabled() || ollamaChat.state() != ProviderAvailabilityState.AVAILABLE) {
            return ollamaChat;
        }
        if (ollamaWarmupServiceProvider == null) {
            return ollamaChat;
        }
        OllamaWarmupService warmupService = ollamaWarmupServiceProvider.getIfAvailable();
        if (warmupService == null || warmupService.isChatReady()) {
            return ollamaChat;
        }
        return new ProviderStatus(ollamaChat.provider(),
                ProviderAvailabilityState.DEGRADED,
                ollamaChat.enabled(),
                warmupService.chatReadinessDetail());
    }

    private boolean containsConfiguredModel(JsonNode models, String configuredModel) {
        Iterator<JsonNode> iterator = models.iterator();
        while (iterator.hasNext()) {
            JsonNode node = iterator.next();
            String discoveredModel = node.path("model").asText();
            if (configuredModel.equals(discoveredModel)) {
                return true;
            }
            if (discoveredModel.startsWith(configuredModel + ":")) {
                return true;
            }
        }
        return false;
    }

    private record ProbeResult(HttpResponse<String> response, boolean timedOut) {

        private static ProbeResult fromResponse(HttpResponse<String> response) {
            return new ProbeResult(response, false);
        }

        private static ProbeResult unavailableProbe() {
            return new ProbeResult(null, false);
        }

        private static ProbeResult timeoutProbe() {
            return new ProbeResult(null, true);
        }
    }
}
