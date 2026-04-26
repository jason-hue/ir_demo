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
    private static final String KNOWLEDGE_BASE_ANSWER_PREFIX = "【本次回答基于知识库检索结果生成】";
    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[([^\\]]+)\\]");
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

    private final ObjectProvider<GlmChatClient> glmChatClientProvider;

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

    private final Duration glmChatTimeout;

    private final ExecutorService chatCallExecutor = Executors.newVirtualThreadPerTaskExecutor();

    ChatAnswerService(HybridRetrievalService hybridRetrievalService,
                      ProviderStatusService providerStatusService,
                      AiProperties aiProperties,
                      ObjectProvider<ChatModel> chatModelProvider) {
        this(hybridRetrievalService, providerStatusService, aiProperties, chatModelProvider, null, null);
    }

    @Autowired
    public ChatAnswerService(HybridRetrievalService hybridRetrievalService,
                             ProviderStatusService providerStatusService,
                             AiProperties aiProperties,
                             ObjectProvider<ChatModel> chatModelProvider,
                             ObjectProvider<GeminiChatClient> geminiChatClientProvider,
                             ObjectProvider<GlmChatClient> glmChatClientProvider) {
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
        this.glmChatClientProvider = glmChatClientProvider;
        this.glmChatTimeout = aiProperties.getGlm().getChatTimeout();
    }

    public ChatAnswerResult ask(String question) throws Exception {
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isEmpty()) {
            throw new IllegalArgumentException("question不可以为空");
        }
        Duration activeChatTimeout = isGlmProvider() ? glmChatTimeout : (isGeminiProvider() ? geminiChatTimeout : chatTimeout);
        long deadlineNanos = System.nanoTime() + activeChatTimeout.toNanos();

        // 第一轮：原问题检索和问答
        ChatAnswerResult firstRoundResult = performQuestionAnswering(normalizedQuestion, deadlineNanos, false);

        // 判断是否需要第二轮
        boolean needsSecondRound = shouldRetryWithKeyword(normalizedQuestion, firstRoundResult.getAnswer());
        log.info("needsSecondRound={}", needsSecondRound);

        if (needsSecondRound) {
            String keywordQuery = extractQuestionKeywords(normalizedQuestion);
            log.info("第一轮返回证据不足，尝试关键词化重试: '{}' -> '{}'", normalizedQuestion, keywordQuery);

            // 第二轮：关键词化检索和问答（使用缩减的上下文以降低延迟）
            ChatAnswerResult secondRoundResult = performQuestionAnswering(keywordQuery, deadlineNanos, true);

            log.info("第二轮结果: available={}, answer={}, citations={}",
                    secondRoundResult.isAnswerAvailable(),
                    secondRoundResult.getAnswer() != null ? secondRoundResult.getAnswer().substring(0, Math.min(50, secondRoundResult.getAnswer().length())) : "null",
                    secondRoundResult.getCitations() != null ? secondRoundResult.getCitations().size() : "null");

            // 比较两轮结果，选择更优的
            if (isSecondRoundBetter(secondRoundResult, firstRoundResult)) {
                log.info("第二轮结果更优，使用关键词化检索结果");
                secondRoundResult.setQuestion(normalizedQuestion);  // 保持原问题
                String mode = secondRoundResult.getRetrievalMode();
                secondRoundResult.setRetrievalMode(mode != null ? mode + "+keyword-fallback" : "hybrid+keyword-fallback");
                return secondRoundResult;
            } else {
                log.info("第二轮结果未优于第一轮，保留第一轮结果");
            }
        }

        return firstRoundResult;
    }

    private ChatAnswerResult performQuestionAnswering(String query, long deadlineNanos, boolean isFallback) throws Exception {
        ChatAnswerResult result = new ChatAnswerResult();
        result.setQuestion(query);

        // 在 fallback 场景下使用缩减的上下文参数
        int effectiveTopK = isFallback ? Math.min(2, chatContextTopK) : chatContextTopK;
        int effectiveCharLimit = isFallback ? Math.min(200, chatChunkCharLimit) : chatChunkCharLimit;
        log.info("performQuestionAnswering: isFallback={}, effectiveTopK={}, effectiveCharLimit={}",
                isFallback, effectiveTopK, effectiveCharLimit);

        HybridRetrievalResult retrieval = hybridRetrievalService.retrieve(query, 1, effectiveTopK);
        result.setRetrievalMode(retrieval.getMode());

        if (retrieval.getResults() == null || retrieval.getResults().isEmpty()) {
            result.setCitations(List.of());
            result.setAnswerAvailable(true);
            result.setAnswer(INSUFFICIENT_EVIDENCE_ANSWER);
            log.info("performQuestionAnswering: 检索结果为空，返回证据不足");
            return result;
        }

        ProviderStatusSnapshot snapshot = providerStatusService.snapshot();
        ProviderStatus activeChatStatus = providerStatusService.activeChatStatus();
        if (activeChatStatus.state() != ProviderAvailabilityState.AVAILABLE) {
            result.setAnswerAvailable(false);
            result.setDegradedReason(activeChatStatus.detail());
            clearRetrievalContext(result);
            return result;
        }

        result.setCitations(retrieval.getResults());

        try {
            result.setAnswer(callActiveChat(buildPrompt(query, retrieval.getResults(), retrieval.getMode(), effectiveCharLimit),
                    remainingChatBudget(deadlineNanos)));
            log.info("performQuestionAnswering: AI返回答案长度={}, 内容前50字符={}",
                    result.getAnswer() != null ? result.getAnswer().length() : 0,
                    result.getAnswer() != null ? result.getAnswer().substring(0, Math.min(50, result.getAnswer().length())) : "null");
            result.setAnswerAvailable(result.getAnswer() != null && !result.getAnswer().isBlank());
            if (result.isAnswerAvailable()) {
                attemptCitationRepair(query, retrieval, result, deadlineNanos, effectiveCharLimit);
                applyCitationProvenance(result, retrieval.getResults());
            }
            if (!result.isAnswerAvailable()) {
                result.setDegradedReason("聊天模型未返回可用答案");
                clearRetrievalContext(result);
            }
            log.info("performQuestionAnswering: 最终答案={}, citations数量={}",
                    result.getAnswer() != null ? result.getAnswer().substring(0, Math.min(50, result.getAnswer().length())) : "null",
                    result.getCitations() != null ? result.getCitations().size() : "null");
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

        if (isGlmProvider()) {
            GlmChatClient glmChatClient = glmChatClientProvider == null ? null : glmChatClientProvider.getIfAvailable();
            if (glmChatClient == null) {
                throw new IllegalStateException("GLM chat client is unavailable");
            }
            return glmChatClient.generate(promptText, timeoutBudget);
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
        Duration activeChatTimeout = isGlmProvider() ? glmChatTimeout : (isGeminiProvider() ? geminiChatTimeout : chatTimeout);
        return new IllegalStateException(CHAT_TIMEOUT_MESSAGE_PREFIX + "，已在" + formatTimeout(activeChatTimeout) + "后返回降级结果", cause);
    }

    protected long chatTimeoutGuardBandNanos() {
        Duration activeChatTimeout = isGlmProvider() ? glmChatTimeout : (isGeminiProvider() ? geminiChatTimeout : chatTimeout);
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
                                       long deadlineNanos,
                                       int effectiveCharLimit) {
        if (!shouldAttemptCitationRepair(retrieval, result.getAnswer())) {
            return;
        }
        String repairedAnswer = tryRepairCitationAnswer(question, retrieval.getResults(), result.getAnswer(), deadlineNanos, effectiveCharLimit);
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
                && !answer.trim().equals(INSUFFICIENT_EVIDENCE_ANSWER)
                && resolveCitedChunks(answer, retrieval.getResults()).isEmpty();
    }

    private String tryRepairCitationAnswer(String question,
                                           List<HybridChunkResult> retrievalResults,
                                           String originalAnswer,
                                           long deadlineNanos,
                                           int effectiveCharLimit) {
        String repairedAnswer = originalAnswer;
        for (int attempt = 0; attempt < MAX_CITATION_REPAIR_ATTEMPTS; attempt++) {
            try {
                String candidateAnswer = callActiveChat(buildCitationRepairPrompt(question, originalAnswer, retrievalResults, effectiveCharLimit),
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
        if (!result.getAnswer().startsWith(KNOWLEDGE_BASE_ANSWER_PREFIX)) {
            result.setAnswer(KNOWLEDGE_BASE_ANSWER_PREFIX + result.getAnswer());
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
            String citationGroup = matcher.group(1);
            String[] citations = citationGroup.split("\\s*,\\s*");
            for (String citation : citations) {
                try {
                    int citedIndex = Integer.parseInt(citation.trim());
                    if (citedIndex >= 1 && citedIndex <= retrievalResults.size()) {
                        citedIndexes.add(citedIndex - 1);
                    }
                }
                catch (NumberFormatException e) {
                    log.warn("无法解析引用编号: {}", citation);
                }
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

    private boolean isGlmProvider() {
        return "glm".equalsIgnoreCase(chatProvider);
    }

    String buildPrompt(String question, List<HybridChunkResult> chunks, String retrievalMode, int effectiveCharLimit) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是问答助手。请基于给定片段回答问题。")
                .append("\n如果片段没有提供答案，只回复\"证据不足\"。")
                .append("\n用自己的话总结答案，在相关句子末尾加上[1]、[2]等编号表示信息来源。")
                .append("\n\n检索模式: ").append(retrievalMode)
                .append("\n问题: ").append(question)
                .append("\n\n片段:\n");
        int limit = Math.min(chunks.size(), chatContextTopK);
        for (int i = 0; i < limit; i++) {
            HybridChunkResult chunk = chunks.get(i);
            prompt.append("[").append(i + 1).append("] ")
                    .append(firstNonBlank(truncate(chunk.getChunkText(), effectiveCharLimit), "无片段内容"))
                    .append("\n\n");
        }
        return prompt.toString();
    }

    String buildCitationRepairPrompt(String question, String answer, List<HybridChunkResult> chunks, int effectiveCharLimit) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为原始答案添加[1]、[2]这样的片段编号。如果答案与片段无关，只回复\"证据不足\"。\n")
                .append("\n问题: ").append(question)
                .append("\n原始答案: ").append(answer)
                .append("\n\n片段:\n");
        int limit = Math.min(chunks.size(), chatContextTopK);
        for (int i = 0; i < limit; i++) {
            HybridChunkResult chunk = chunks.get(i);
            prompt.append("[").append(i + 1).append("] ")
                    .append(firstNonBlank(truncate(chunk.getChunkText(), effectiveCharLimit), "无片段内容"))
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

    private boolean shouldRetryWithKeyword(String question, String answer) {
        if (answer == null || !answer.trim().equals(INSUFFICIENT_EVIDENCE_ANSWER)) {
            log.info("shouldRetryWithKeyword: answer is null or not '{}' (actual: '{}')",
                    INSUFFICIENT_EVIDENCE_ANSWER, answer);
            return false;
        }
        String lowerQuestion = question.toLowerCase();
        boolean hasQuestionWord = lowerQuestion.contains("为什么") || lowerQuestion.contains("为何")
                || lowerQuestion.contains("怎么") || lowerQuestion.contains("如何")
                || lowerQuestion.contains("多少") || lowerQuestion.contains("是否")
                || lowerQuestion.contains("吗") || lowerQuestion.contains("呢")
                || lowerQuestion.contains("什么") || lowerQuestion.contains("哪");
        log.info("shouldRetryWithKeyword: hasQuestionWord={}", hasQuestionWord);
        return hasQuestionWord;
    }

    boolean isSecondRoundBetter(ChatAnswerResult secondRound, ChatAnswerResult firstRound) {
        if (secondRound == null || firstRound == null) {
            log.info("第二轮更优判断: null check failed");
            return false;
        }
        // 第二轮更优的条件：
        // 1. 第二轮有引用（citations非空）
        // 2. 且第二轮答案不是"证据不足"（可能是纯"证据不足"或带前缀）
        boolean secondRoundHasCitations = secondRound.getCitations() != null && !secondRound.getCitations().isEmpty();
        String secondRoundAnswer = secondRound.getAnswer();
        boolean secondRoundHasAnswer = secondRoundAnswer != null
                && !secondRoundAnswer.trim().equals(INSUFFICIENT_EVIDENCE_ANSWER);
        log.info("第二轮更优判断: secondRoundHasCitations={}, secondRoundHasAnswer={}, isBetter={}",
                secondRoundHasCitations, secondRoundHasAnswer, secondRoundHasCitations && secondRoundHasAnswer);
        return secondRoundHasCitations && secondRoundHasAnswer;
    }

    String extractQuestionKeywords(String question) {
        String keywords = question
                .replaceAll("[为什么为何]", "")
                .replaceAll("[怎么如何]", "")
                .replaceAll("多少", "")
                .replaceAll("是否", "")
                .replaceAll("[吗呢]", "")
                .replaceAll("什么", "")
                .replaceAll("哪个", "")
                .replaceAll("哪些", "")
                .replaceAll("[？?]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return keywords.isEmpty() ? question : keywords;
    }
}
