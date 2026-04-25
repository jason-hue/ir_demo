package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class OllamaWarmupService {

    private static final Logger log = LoggerFactory.getLogger(OllamaWarmupService.class);

    private final AiProperties aiProperties;

    private final ObjectMapper objectMapper;

    private final AtomicBoolean chatReady = new AtomicBoolean(false);

    private final AtomicReference<String> readinessDetail = new AtomicReference<>("聊天模型尚未完成预热");

    public OllamaWarmupService(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        if (!"ollama".equalsIgnoreCase(aiProperties.getChatProvider())) {
            chatReady.set(true);
            readinessDetail.set("当前活动聊天提供方不是Ollama，跳过预热");
            return;
        }
        if (!aiProperties.getOllama().isWarmupEnabled()) {
            chatReady.set(true);
            readinessDetail.set("聊天模型预热已禁用");
            return;
        }
        warmUpNow("startup");
    }

    @Scheduled(fixedDelayString = "${irdemo.ai.ollama.keep-warm-interval:PT4M}",
            initialDelayString = "${irdemo.ai.ollama.keep-warm-interval:PT4M}")
    public void keepWarm() {
        if (!"ollama".equalsIgnoreCase(aiProperties.getChatProvider())) {
            return;
        }
        if (!aiProperties.getOllama().isWarmupEnabled()) {
            return;
        }
        warmUpNow("keep-warm");
    }

    public boolean isChatReady() {
        return chatReady.get();
    }

    public String chatReadinessDetail() {
        return readinessDetail.get();
    }

    public boolean warmUpNow(String reason) {
        if (!aiProperties.getOllama().isEnabled()) {
            chatReady.set(false);
            readinessDetail.set("Ollama support is disabled by configuration.");
            return false;
        }

        Duration timeout = aiProperties.getOllama().getWarmupTimeout();
        String baseUrl = normalizeBaseUrl(aiProperties.getOllama().getBaseUrl());
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", aiProperties.getOllama().getChatModel());
            payload.put("prompt", aiProperties.getOllama().getWarmupPrompt());
            payload.put("stream", false);
            payload.put("keep_alive", aiProperties.getOllama().getKeepAlive());

            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return markFailure("聊天模型预热失败，HTTP " + response.statusCode() + " (" + reason + ")", reason);
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("response").asText().isBlank() && !root.path("done").asBoolean(false)) {
                return markFailure("聊天模型预热未返回有效内容 (" + reason + ")", reason);
            }

            chatReady.set(true);
            readinessDetail.set("聊天模型已预热，可答状态更新时间：" + Instant.now());
            log.info("聊天模型预热成功，reason=[{}], model=[{}]", reason, aiProperties.getOllama().getChatModel());
            return true;
        }
        catch (Exception e) {
            return markFailure("聊天模型预热失败: " + e.getMessage() + " (" + reason + ")", reason);
        }
    }

    private boolean markFailure(String detail, String reason) {
        if (chatReady.get() && !Objects.equals("startup", reason)) {
            log.warn("{}，保留最近成功预热状态", detail);
            return false;
        }
        chatReady.set(false);
        readinessDetail.set(detail);
        log.warn(detail);
        return false;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
