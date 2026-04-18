package cn.edu.bistu.cs.ir.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ConnectException;
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
        ProviderStatus ollamaChat = ollamaModelStatus("ollama-chat", aiProperties.getOllama().getChatModel());
        ollamaChat = applyWarmupReadiness(ollamaChat);
        ProviderStatus ollamaEmbedding = ollamaModelStatus("ollama-embedding", aiProperties.getOllama().getEmbeddingModel());
        ProviderStatus qdrant = qdrantStatus();
        AiFallbackMode fallbackMode = isAiReady(ollamaChat, ollamaEmbedding, qdrant)
                ? AiFallbackMode.AI_READY
                : AiFallbackMode.LEXICAL_ONLY;
        return new ProviderStatusSnapshot(ollamaChat, ollamaEmbedding, qdrant, true, fallbackMode);
    }

    public ProviderStatus ollamaModelStatus(String providerName, String configuredModel) {
        if (!aiProperties.getOllama().isEnabled()) {
            return new ProviderStatus(providerName, ProviderAvailabilityState.DISABLED, false,
                    "Ollama support is disabled by configuration.");
        }

        HttpResponse<String> response = httpGet(normalizeBaseUrl(aiProperties.getOllama().getBaseUrl()) + "/api/tags");
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

    public ProviderStatus qdrantStatus() {
        if (!aiProperties.getQdrant().isEnabled()) {
            return new ProviderStatus("qdrant", ProviderAvailabilityState.DISABLED, false,
                    "Qdrant vector support is disabled by configuration.");
        }

        String baseUrl = (aiProperties.getQdrant().isUseTls() ? "https" : "http") + "://"
                + aiProperties.getQdrant().getHost() + ":" + aiProperties.getQdrant().getHttpPort();
        String collectionName = aiProperties.getQdrant().getCollectionName();
        HttpResponse<String> response = httpGet(baseUrl + "/collections/" + encodePathSegment(collectionName));
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

    private HttpResponse<String> httpGet(String url) {
        try {
            Duration connectTimeout = aiProperties.getProviderStatus().getConnectTimeout();
            Duration readTimeout = aiProperties.getProviderStatus().getReadTimeout();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(readTimeout)
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (ConnectException e) {
            log.debug("Provider probe connection refused for {}", url);
            return null;
        }
        catch (Exception e) {
            log.debug("Provider probe failed for {}", url, e);
            return null;
        }
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
}
