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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatAnswerService {

    private static final Logger log = LoggerFactory.getLogger(ChatAnswerService.class);
    private static final String INSUFFICIENT_EVIDENCE_ANSWER = "证据不足";
    private static final String NON_VECTOR_EVIDENCE_PREFIX = "【非向量证据回答】";
    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final Pattern HALLUCINATION_DISCLAIMER_PATTERN = Pattern.compile(
            "(片段|参考|资料|文献|信息|文本|文章).*?(没有|未)(能)?(明确)?(提到|提及|提供|给出|包含|说明|表明|发现|找到)|" +
            "(没有|未)(能)?(明确)?(提到|提及|提供|给出|包含|说明|表明|发现|找到).*?(片段|参考|资料|文献|信息|文本|文章)|" +
            "(没有|未)(任何|有)?(明确)?(证据|信息)(表明|证明|显示|说明)|" +
            "证据不足|无法判断|无法从|无法直接|没有相关信息|不知|无法回答"
    );
    private static final int MAX_CITATION_REPAIR_ATTEMPTS = 1;
    private static final String CHAT_TIMEOUT_MESSAGE_PREFIX = "聊天模型调用超时";
    static final long REQUEST_TIMEOUT_GUARD_BAND_MILLIS_FLOOR = 50L;
    static final long REQUEST_TIMEOUT_GUARD_BAND_MILLIS_CEILING = 1_000L;

    private final HybridRetrievalService hybridRetrievalService;

    private final ProviderStatusService providerStatusService;

    private final ObjectProvider<ChatModel> chatModelProvider;

    private final ObjectProvider<GeminiChatClient> geminiChatClientProvider;

    private final String chatProvider;

    private final Duration chatTimeout;

    private final int chatContextTopK;

    private final int chatChunkCharLimit;

    private final String chatModel;

    private final String chatKeepAlive;

    private final int chatNumPredict;

    private final int chatNumCtx;

    private final double chatTemperature;

    private final double chatTopP;

    private final Duration geminiChatTimeout;

    private final ExecutorService chatCallExecutor = Executors.newVirtualThreadPerTaskExecutor();

    ChatAnswerService(HybridRetrievalService hybridRetrievalService,
                      ProviderStatusService providerStatusService,
                      AiProperties aiProperties,
                      ObjectProvider<ChatModel> chatModelProvider) {
        this(hybridRetrievalService, providerStatusService, aiProperties, chatModelProvider, null);
    }

    @Autowired
    public ChatAnswerService(HybridRetrievalService hybridRetrievalService,
                             ProviderStatusService providerStatusService,
                             AiProperties aiProperties,
                             ObjectProvider<ChatModel> chatModelProvider,
                             ObjectProvider<GeminiChatClient> geminiChatClientProvider) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.providerStatusService = providerStatusService;
        this.chatProvider = aiProperties.getChatProvider();
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
        this.geminiChatClientProvider = geminiChatClientProvider;
        this.geminiChatTimeout = aiProperties.getGemini().getChatTimeout();
    }

    public ChatAnswerResult ask(String question) throws Exception {
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isEmpty()) {
            throw new IllegalArgumentException("question不可以为空");
        }
        Duration activeChatTimeout = isGeminiProvider() ? geminiChatTimeout : chatTimeout;
        long deadlineNanos = System.nanoTime() + activeChatTimeout.toNanos();

        ChatAnswerResult result = new ChatAnswerResult();
        result.setQuestion(normalizedQuestion);

        HybridRetrievalResult retrieval = hybridRetrievalService.retrieve(normalizedQuestion, 1, chatContextTopK);
        result.setRetrievalMode(retrieval.getMode());

        if (retrieval.getResults() == null || retrieval.getResults().isEmpty()) {
            result.setCitations(List.of());
            result.setAnswerAvailable(true);
            result.setAnswer(INSUFFICIENT_EVIDENCE_ANSWER);
            return result;
        }

        ProviderStatusSnapshot snapshot = providerStatusService.snapshot();
        if (snapshot.ollamaChat().state() != ProviderAvailabilityState.AVAILABLE) {
            result.setAnswerAvailable(false);
            result.setDegradedReason(snapshot.ollamaChat().detail());
            clearRetrievalContext(result);
            return result;
        }

        result.setCitations(retrieval.getResults());

        try {
            result.setAnswer(callActiveChat(buildPrompt(normalizedQuestion, retrieval.getResults(), retrieval.getMode()),
                    remainingChatBudget(deadlineNanos)));
            result.setAnswerAvailable(result.getAnswer() != null && !result.getAnswer().isBlank());
            if (result.isAnswerAvailable()) {
                attemptCitationRepair(normalizedQuestion, retrieval, result, deadlineNanos);
                applyCitationProvenance(result, retrieval.getResults());
            }
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

    private ChatResponse callChatModelWithTimeout(ChatModel chatModel, Prompt prompt, Duration timeoutBudget) {
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(() -> chatModel.call(prompt), chatCallExecutor);
        try {
            return future.get(timeoutBudget.toNanos(), TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException e) {
            future.cancel(true);
            throw exhaustedChatBudgetException(e);
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

    private String callActiveChat(String promptText, Duration timeoutBudget) {
        if (isGeminiProvider()) {
            GeminiChatClient geminiChatClient = geminiChatClientProvider == null ? null : geminiChatClientProvider.getIfAvailable();
            if (geminiChatClient == null) {
                throw new IllegalStateException("Gemini chat client is unavailable");
            }
            return geminiChatClient.generate(promptText, timeoutBudget);
        }

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("聊天模型不可用");
        }
        Prompt prompt = new Prompt(promptText, buildChatOptions());
        ChatResponse response = callChatModelWithTimeout(chatModel, prompt, timeoutBudget);
        Generation generation = response == null ? null : response.getResult();
        AssistantMessage message = generation == null ? null : generation.getOutput();
        return message == null ? null : message.getText();
    }

    private Duration remainingChatBudget(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime() - chatTimeoutGuardBandNanos();
        if (remainingNanos <= 0L) {
            throw exhaustedChatBudgetException(null);
        }
        return Duration.ofNanos(remainingNanos);
    }

    private IllegalStateException exhaustedChatBudgetException(Throwable cause) {
        Duration activeChatTimeout = isGeminiProvider() ? geminiChatTimeout : chatTimeout;
        return new IllegalStateException(CHAT_TIMEOUT_MESSAGE_PREFIX + "，已在" + formatTimeout(activeChatTimeout) + "后返回降级结果", cause);
    }

    protected long chatTimeoutGuardBandNanos() {
        Duration activeChatTimeout = isGeminiProvider() ? geminiChatTimeout : chatTimeout;
        return requestTimeoutGuardBandNanos(activeChatTimeout);
    }

    private long requestTimeoutGuardBandNanos(Duration timeout) {
        long timeoutMillis = timeout.toMillis();
        long guardBandMillis = Math.max(REQUEST_TIMEOUT_GUARD_BAND_MILLIS_FLOOR,
                Math.min(REQUEST_TIMEOUT_GUARD_BAND_MILLIS_CEILING, timeoutMillis / 100));
        return TimeUnit.MILLISECONDS.toNanos(guardBandMillis);
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

    private void attemptCitationRepair(String question,
                                       HybridRetrievalResult retrieval,
                                       ChatAnswerResult result,
                                       long deadlineNanos) {
        if (!shouldAttemptCitationRepair(retrieval, result.getAnswer())) {
            return;
        }
        String repairedAnswer = tryRepairCitationAnswer(question, retrieval.getResults(), result.getAnswer(), deadlineNanos);
        if (repairedAnswer != null && !repairedAnswer.isBlank()) {
            result.setAnswer(repairedAnswer);
        }
    }

    private boolean shouldAttemptCitationRepair(HybridRetrievalResult retrieval, String answer) {
        return retrieval != null
                && retrieval.getResults() != null
                && !retrieval.getResults().isEmpty()
                && answer != null
                && !answer.isBlank()
                && resolveCitedChunks(answer, retrieval.getResults()).isEmpty();
    }

    private String tryRepairCitationAnswer(String question,
                                           List<HybridChunkResult> retrievalResults,
                                           String originalAnswer,
                                           long deadlineNanos) {
        String repairedAnswer = originalAnswer;
        for (int attempt = 0; attempt < MAX_CITATION_REPAIR_ATTEMPTS; attempt++) {
            try {
                String candidateAnswer = callActiveChat(buildCitationRepairPrompt(question, originalAnswer, retrievalResults),
                        remainingChatBudget(deadlineNanos));
                if (candidateAnswer == null || candidateAnswer.isBlank()) {
                    return repairedAnswer;
                }
                repairedAnswer = candidateAnswer;
                if (!resolveCitedChunks(repairedAnswer, retrievalResults).isEmpty()) {
                    return repairedAnswer;
                }
            }
            catch (RuntimeException e) {
                if (isChatTimeoutFailure(e)) {
                    throw e;
                }
                log.warn("答案引用修复失败，保留原始可校验结果: {}", e.getMessage());
                return repairedAnswer;
            }
        }
        return repairedAnswer;
    }

    private boolean isChatTimeoutFailure(RuntimeException e) {
        return e.getMessage() != null && e.getMessage().startsWith(CHAT_TIMEOUT_MESSAGE_PREFIX);
    }

    private void applyCitationProvenance(ChatAnswerResult result, List<HybridChunkResult> retrievalResults) {
        List<HybridChunkResult> citedChunks = resolveCitedChunks(result.getAnswer(), retrievalResults);
        result.setCitations(citedChunks);
        if (citedChunks.isEmpty()) {
            result.setAnswer(INSUFFICIENT_EVIDENCE_ANSWER);
            return;
        }
        boolean vectorGrounded = citedChunks.stream().anyMatch(chunk -> chunk.getVectorRank() != null);
        if (!vectorGrounded && !result.getAnswer().startsWith(NON_VECTOR_EVIDENCE_PREFIX)) {
            result.setAnswer(NON_VECTOR_EVIDENCE_PREFIX + result.getAnswer());
        }
    }

    private List<HybridChunkResult> resolveCitedChunks(String answer, List<HybridChunkResult> retrievalResults) {
        if (answer == null || answer.isBlank() || retrievalResults == null || retrievalResults.isEmpty()) {
            return List.of();
        }
        if (HALLUCINATION_DISCLAIMER_PATTERN.matcher(answer).find()) {
            return List.of();
        }
        Matcher matcher = CITATION_MARKER_PATTERN.matcher(answer);
        Set<Integer> citedIndexes = new LinkedHashSet<>();
        while (matcher.find()) {
            int citedIndex = Integer.parseInt(matcher.group(1));
            if (citedIndex >= 1 && citedIndex <= retrievalResults.size()) {
                citedIndexes.add(citedIndex - 1);
            }
        }
        if (citedIndexes.isEmpty()) {
            return List.of();
        }
        List<HybridChunkResult> citedChunks = new ArrayList<>();
        for (Integer citedIndex : citedIndexes) {
            citedChunks.add(retrievalResults.get(citedIndex));
        }
        return List.copyOf(citedChunks);
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

    private boolean isGeminiProvider() {
        return "gemini".equalsIgnoreCase(chatProvider);
    }

    String buildPrompt(String question, List<HybridChunkResult> chunks, String retrievalMode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是问答助手。请基于给定片段回答问题。")
                .append("\n如果片段没有提供答案，只回复“证据不足”。")
                .append("\n用自己的话总结答案，在相关句子末尾加上[1]、[2]等编号表示信息来源。")
                .append("\n\n检索模式: ").append(retrievalMode)
                .append("\n问题: ").append(question)
                .append("\n\n片段:\n");
        int limit = Math.min(chunks.size(), chatContextTopK);
        for (int i = 0; i < limit; i++) {
            HybridChunkResult chunk = chunks.get(i);
            prompt.append("[").append(i + 1).append("] ")
                    .append(firstNonBlank(truncate(chunk.getChunkText(), chatChunkCharLimit), "无片段内容"))
                    .append("\n\n");
        }
        return prompt.toString();
    }

    String buildCitationRepairPrompt(String question, String answer, List<HybridChunkResult> chunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为原始答案添加[1]、[2]这样的片段编号。如果答案与片段无关，只回复“证据不足”。\n")
                .append("\n问题: ").append(question)
                .append("\n原始答案: ").append(answer)
                .append("\n\n片段:\n");
        int limit = Math.min(chunks.size(), chatContextTopK);
        for (int i = 0; i < limit; i++) {
            HybridChunkResult chunk = chunks.get(i);
            prompt.append("[").append(i + 1).append("] ")
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
