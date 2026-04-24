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
    private static final String EMPTY_RETRIEVAL_HINT_SUFFIX = "\n\n提示：知识库中未检索到相关数据，以上回答由模型基于通用知识生成，未基于库内证据。";
    private static final Pattern CITATION_MARKER_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final int MAX_CITATION_REPAIR_ATTEMPTS = 1;
    private static final String CHAT_TIMEOUT_MESSAGE_PREFIX = "聊天模型调用超时";
    static final long REQUEST_TIMEOUT_GUARD_BAND_MILLIS_FLOOR = 50L;
    static final long REQUEST_TIMEOUT_GUARD_BAND_MILLIS_CEILING = 1_000L;

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
        long deadlineNanos = System.nanoTime() + chatTimeout.toNanos();

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

        if (retrieval.getResults() == null || retrieval.getResults().isEmpty()) {
            result.setCitations(List.of());
            try {
                Prompt prompt = new Prompt(buildEmptyRetrievalPrompt(normalizedQuestion, retrieval.getMode()), buildChatOptions());
                ChatResponse response = callChatModelWithTimeout(chatModel, prompt, remainingChatBudget(deadlineNanos));
                Generation generation = response == null ? null : response.getResult();
                AssistantMessage message = generation == null ? null : generation.getOutput();
                String answer = message == null ? null : message.getText();
                result.setAnswerAvailable(answer != null && !answer.isBlank());
                result.setAnswer(result.isAnswerAvailable() ? answer + EMPTY_RETRIEVAL_HINT_SUFFIX : answer);
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

        result.setCitations(retrieval.getResults());

        try {
            Prompt prompt = new Prompt(buildPrompt(normalizedQuestion, retrieval.getResults(), retrieval.getMode()), buildChatOptions());
            ChatResponse response = callChatModelWithTimeout(chatModel, prompt, remainingChatBudget(deadlineNanos));
            Generation generation = response == null ? null : response.getResult();
            AssistantMessage message = generation == null ? null : generation.getOutput();
            result.setAnswerAvailable(message != null && message.getText() != null && !message.getText().isBlank());
            result.setAnswer(message == null ? null : message.getText());
            if (result.isAnswerAvailable()) {
                attemptCitationRepair(chatModel, normalizedQuestion, retrieval, result, deadlineNanos);
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

    private Duration remainingChatBudget(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime() - chatTimeoutGuardBandNanos();
        if (remainingNanos <= 0L) {
            throw exhaustedChatBudgetException(null);
        }
        return Duration.ofNanos(remainingNanos);
    }

    private IllegalStateException exhaustedChatBudgetException(Throwable cause) {
        return new IllegalStateException(CHAT_TIMEOUT_MESSAGE_PREFIX + "，已在" + formatTimeout(chatTimeout) + "后返回降级结果", cause);
    }

    protected long chatTimeoutGuardBandNanos() {
        return requestTimeoutGuardBandNanos(chatTimeout);
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

    private void attemptCitationRepair(ChatModel chatModel,
                                       String question,
                                       HybridRetrievalResult retrieval,
                                       ChatAnswerResult result,
                                       long deadlineNanos) {
        if (!shouldAttemptCitationRepair(retrieval, result.getAnswer())) {
            return;
        }
        String repairedAnswer = tryRepairCitationAnswer(chatModel, question, retrieval.getResults(), result.getAnswer(), deadlineNanos);
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

    private String tryRepairCitationAnswer(ChatModel chatModel,
                                           String question,
                                           List<HybridChunkResult> retrievalResults,
                                           String originalAnswer,
                                           long deadlineNanos) {
        String repairedAnswer = originalAnswer;
        for (int attempt = 0; attempt < MAX_CITATION_REPAIR_ATTEMPTS; attempt++) {
            try {
                Prompt repairPrompt = new Prompt(buildCitationRepairPrompt(question, originalAnswer, retrievalResults), buildChatOptions());
                ChatResponse repairResponse = callChatModelWithTimeout(chatModel, repairPrompt, remainingChatBudget(deadlineNanos));
                Generation repairGeneration = repairResponse == null ? null : repairResponse.getResult();
                AssistantMessage repairMessage = repairGeneration == null ? null : repairGeneration.getOutput();
                String candidateAnswer = repairMessage == null ? null : repairMessage.getText();
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

    String buildPrompt(String question, List<HybridChunkResult> chunks, String retrievalMode) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是信息检索问答助手。请严格基于给定检索片段回答问题。")
                .append("\n如果证据不足，请只明确回答“证据不足”。")
                .append("\n请使用简洁中文作答，并在答案中使用[1]、[2]引用片段编号。")
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

    String buildEmptyRetrievalPrompt(String question, String retrievalMode) {
        return new StringBuilder()
                .append("你是信息检索问答助手。当前知识库未检索到与问题直接相关的片段。")
                .append("\n请基于你的通用知识直接回答用户问题，不要声称答案来自知识库，不要编造“已检索到资料”或伪造引用编号。")
                .append("\n请使用简洁中文作答。")
                .append("\n问题: ").append(question)
                .toString();
    }

    String buildCitationRepairPrompt(String question, String answer, List<HybridChunkResult> chunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是信息检索问答助手。下面给定了问题、原始答案和编号参考片段。")
                .append("\n请保留原始答案的事实内容，仅补充或修正有效的片段引用编号。")
                .append("\n只允许使用方括号数字引用，例如[1]、[2]，且编号必须来自给定参考片段。")
                .append("\n如果无法为答案补充有效引用，请只回答“证据不足”。")
                .append("\n问题: ").append(question)
                .append("\n原始答案: ").append(answer)
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
