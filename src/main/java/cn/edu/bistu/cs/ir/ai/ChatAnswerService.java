package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class ChatAnswerService {

    private static final Logger log = LoggerFactory.getLogger(ChatAnswerService.class);

    private final HybridRetrievalService hybridRetrievalService;

    private final ProviderStatusService providerStatusService;

    private final ObjectProvider<ChatModel> chatModelProvider;

    private final Duration chatTimeout;

    private final int chatContextTopK;

    private final int chatChunkCharLimit;

    private final String chatModel;

    private final String chatKeepAlive;

    private final int chatNumPredict;

    private final int chatNumCtx;

    private final double chatTemperature;

    private final double chatTopP;

    private final ExecutorService chatCallExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatAnswerService(HybridRetrievalService hybridRetrievalService,
                             ProviderStatusService providerStatusService,
                             AiProperties aiProperties,
                             ObjectProvider<ChatModel> chatModelProvider) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.providerStatusService = providerStatusService;
        this.chatTimeout = aiProperties.getOllama().getChatTimeout();
        this.chatContextTopK = aiProperties.getOllama().getChatContextTopK();
        this.chatChunkCharLimit = aiProperties.getOllama().getChatChunkCharLimit();
        this.chatModel = aiProperties.getOllama().getChatModel();
        this.chatKeepAlive = aiProperties.getOllama().getKeepAlive();
        this.chatNumPredict = aiProperties.getOllama().getChatNumPredict();
        this.chatNumCtx = aiProperties.getOllama().getChatNumCtx();
        this.chatTemperature = aiProperties.getOllama().getChatTemperature();
        this.chatTopP = aiProperties.getOllama().getChatTopP();
        this.chatModelProvider = chatModelProvider;
    }

    public ChatAnswerResult ask(String question) throws Exception {
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isEmpty()) {
            throw new IllegalArgumentException("question不可以为空");
        }

        ChatAnswerResult result = new ChatAnswerResult();
        result.setQuestion(normalizedQuestion);

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        ProviderStatusSnapshot snapshot = providerStatusService.snapshot();
        if (chatModel == null || snapshot.ollamaChat().state() != ProviderAvailabilityState.AVAILABLE) {
            result.setAnswerAvailable(false);
            result.setDegradedReason(snapshot.ollamaChat().detail());
            return result;
        }

        HybridRetrievalResult retrieval = hybridRetrievalService.retrieve(normalizedQuestion, 1, chatContextTopK);
        result.setRetrievalMode(retrieval.getMode());
        result.setCitations(retrieval.getResults());

        try {
            Prompt prompt = new Prompt(buildPrompt(normalizedQuestion, retrieval.getResults(), retrieval.getMode()), buildChatOptions());
            ChatResponse response = callChatModelWithTimeout(chatModel, prompt);
            Generation generation = response == null ? null : response.getResult();
            AssistantMessage message = generation == null ? null : generation.getOutput();
            result.setAnswerAvailable(message != null && message.getText() != null && !message.getText().isBlank());
            result.setAnswer(message == null ? null : message.getText());
            if (!result.isAnswerAvailable()) {
                result.setDegradedReason("聊天模型未返回可用答案");
                clearRetrievalContext(result);
            }
            return result;
        }
        catch (RuntimeException e) {
            log.warn("聊天模型调用失败，返回可校验降级结果: {}", e.getMessage());
            result.setAnswerAvailable(false);
            result.setDegradedReason(e.getMessage() == null || e.getMessage().isBlank()
                    ? "聊天模型调用失败"
                    : e.getMessage());
            clearRetrievalContext(result);
            return result;
        }
    }

    @PreDestroy
    void closeExecutor() {
        chatCallExecutor.shutdownNow();
    }

    private ChatResponse callChatModelWithTimeout(ChatModel chatModel, Prompt prompt) {
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(() -> chatModel.call(prompt), chatCallExecutor);
        try {
            return future.get(chatTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("聊天模型调用超时，已在" + formatTimeout(chatTimeout) + "后返回降级结果", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("聊天模型调用被中断", e);
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("聊天模型调用失败", cause);
        }
    }

    private String formatTimeout(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis % 1000 == 0) {
            return (millis / 1000) + "秒";
        }
        return millis + "毫秒";
    }

    private void clearRetrievalContext(ChatAnswerResult result) {
        result.setRetrievalMode(null);
        result.setCitations(List.of());
    }

    private OllamaOptions buildChatOptions() {
        OllamaOptions options = new OllamaOptions();
        options.setModel(chatModel);
        options.setKeepAlive(chatKeepAlive);
        options.setNumPredict(chatNumPredict);
        options.setNumCtx(chatNumCtx);
        options.setTemperature(chatTemperature);
        options.setTopP(chatTopP);
        return options;
    }

    String buildPrompt(String question, List<HybridChunkResult> chunks, String retrievalMode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是信息检索问答助手。请严格基于给定检索片段回答问题。")
                .append("\n如果证据不足，请只明确回答“证据不足”。")
                .append("\n请使用简洁中文作答，尽量控制在120字以内，并在答案中使用[1]、[2]引用片段编号。")
                .append("\n检索模式: ").append(retrievalMode)
                .append("\n问题: ").append(question)
                .append("\n\n参考片段:\n");
        int limit = Math.min(chunks.size(), chatContextTopK);
        for (int i = 0; i < limit; i++) {
            HybridChunkResult chunk = chunks.get(i);
            prompt.append("[").append(i + 1).append("] ")
                    .append(firstNonBlank(chunk.getTitle(), "无标题"))
                    .append(" | ")
                    .append(firstNonBlank(chunk.getSource(), "未知来源"))
                    .append("\n")
                    .append(firstNonBlank(truncate(chunk.getChunkText(), chatChunkCharLimit), "无片段内容"))
                    .append("\n\n");
        }
        return prompt.toString();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.isBlank() || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
