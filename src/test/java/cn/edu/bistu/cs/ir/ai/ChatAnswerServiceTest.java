package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAnswerServiceTest {

    private static final String CONFIG_DISABLED_DETAIL = "Qdrant vector support is disabled by configuration.";

    @Test
    void askUsesTopConfiguredRetrievedChunksWhenChatModelIsAvailable() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        AtomicReference<Prompt> promptRef = new AtomicReference<>();

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                promptRef.set(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("基于片段整理的答案[1]"))));
            }
        };

        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult(4));
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("请总结腾讯新闻中的AI相关新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("【本次回答基于知识库检索结果生成】基于片段整理的答案[1]", result.getAnswer()),
                () -> Assertions.assertEquals(1, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-1", result.getCitations().getFirst().getChunkId()),
                () -> Assertions.assertTrue(promptRef.get().getContents().contains("请基于给定片段回答问题")),
                () -> Assertions.assertTrue(promptRef.get().getContents().contains("如果片段没有提供答案，只回复\"证据不足\"")),
                () -> Assertions.assertTrue(promptRef.get().getContents().contains("[4] 片段内容-4")),
                () -> Assertions.assertFalse(promptRef.get().getContents().contains("[5] 标题-5")),
                () -> Assertions.assertTrue(promptRef.get().getContents().contains("在相关句子末尾加上[1]、[2]等编号表示信息来源")),
                () -> Assertions.assertFalse(promptRef.get().getContents().contains("120字以内")),
                () -> Assertions.assertInstanceOf(OllamaOptions.class, promptRef.get().getOptions()),
                () -> Assertions.assertEquals(96, ((OllamaOptions) promptRef.get().getOptions()).getNumPredict()),
                () -> Assertions.assertEquals(2048, ((OllamaOptions) promptRef.get().getOptions()).getNumCtx()),
                () -> Assertions.assertEquals("10m", ((OllamaOptions) promptRef.get().getOptions()).getKeepAlive())
        );
        verify(hybridRetrievalService).retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4);
    }

    @Test
    void askOmitsRetrievalContextWhenChatModelIsUnavailable() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.UNAVAILABLE, false, "ollama chat unavailable"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                false,
                AiFallbackMode.LEXICAL_ONLY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.UNAVAILABLE, false, "ollama chat unavailable"));
        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult(4));
        when(chatModelProvider.getIfAvailable()).thenReturn(null);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("请总结腾讯新闻中的AI相关新闻");

        Assertions.assertAll(
                () -> Assertions.assertFalse(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("ollama chat unavailable", result.getDegradedReason()),
                () -> Assertions.assertNull(result.getRetrievalMode()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty())
        );
        verify(hybridRetrievalService).retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4);
    }

    @Test
    void askUsesGeminiClientWhenGeminiIsActiveChatProvider() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GeminiChatClient> geminiChatClientProvider = mock(ObjectProvider.class);
        GeminiChatClient geminiChatClient = mock(GeminiChatClient.class);

        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult(4));
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("gemini-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("gemini-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(geminiChatClientProvider.getIfAvailable()).thenReturn(geminiChatClient);
        when(geminiChatClient.generate(anyString(), any())).thenReturn("Gemini整理的答案[1]");

        @SuppressWarnings("unchecked")
        ObjectProvider<GlmChatClient> glmChatClientProvider = mock(ObjectProvider.class);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties("gemini"),
                chatModelProvider,
                geminiChatClientProvider,
                glmChatClientProvider);
        ChatAnswerResult result = service.ask("请总结腾讯新闻中的AI相关新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("【本次回答基于知识库检索结果生成】Gemini整理的答案[1]", result.getAnswer()),
                () -> Assertions.assertEquals(1, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-1", result.getCitations().getFirst().getChunkId())
        );
        verify(geminiChatClient).generate(anyString(), any());
        verify(chatModelProvider, never()).getIfAvailable();
    }

    @Test
    void askReturnsDegradedResultWhenGeminiIsActiveButUnavailable() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<GeminiChatClient> geminiChatClientProvider = mock(ObjectProvider.class);

        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult(4));
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("gemini-chat", ProviderAvailabilityState.UNAVAILABLE, true, "Gemini API key is not configured."),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.LEXICAL_ONLY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("gemini-chat", ProviderAvailabilityState.UNAVAILABLE, true, "Gemini API key is not configured."));

        @SuppressWarnings("unchecked")
        ObjectProvider<GlmChatClient> glmChatClientProvider = mock(ObjectProvider.class);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties("gemini"),
                chatModelProvider,
                geminiChatClientProvider,
                glmChatClientProvider);
        ChatAnswerResult result = service.ask("请总结腾讯新闻中的AI相关新闻");

        Assertions.assertAll(
                () -> Assertions.assertFalse(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("Gemini API key is not configured.", result.getDegradedReason()),
                () -> Assertions.assertNull(result.getRetrievalMode()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty())
        );
        verify(geminiChatClientProvider, never()).getIfAvailable();
        verify(chatModelProvider, never()).getIfAvailable();
    }

    @Test
    void askReturnsInsufficientEvidenceWhenRetrievalIsEmptyEvenIfChatModelIsUnavailable() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        when(hybridRetrievalService.retrieve("qwertyuiopasdfghjkl", 1, 4)).thenReturn(emptyLexicalOnlyResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.UNAVAILABLE, false, "ollama chat unavailable"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.DISABLED, false, CONFIG_DISABLED_DETAIL),
                false,
                AiFallbackMode.LEXICAL_ONLY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(null);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("qwertyuiopasdfghjkl");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("证据不足", result.getAnswer()),
                () -> Assertions.assertEquals(HybridRetrievalService.MODE_LEXICAL_ONLY, result.getRetrievalMode()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty()),
                () -> Assertions.assertNull(result.getDegradedReason())
        );
        verify(hybridRetrievalService).retrieve("qwertyuiopasdfghjkl", 1, 4);
        verify(chatModelProvider, never()).getIfAvailable();
    }

    @Test
    void askReturnsDegradedResultWhenChatModelTimesOut() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        CountDownLatch enteredCall = new CountDownLatch(1);
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                enteredCall.countDown();
                try {
                    Thread.sleep(1_000L);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("迟到的答案"))));
            }
        };

        when(hybridRetrievalService.retrieve("张雪是干啥的", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(Duration.ofMillis(100), 4, 320),
                chatModelProvider);

        long startedAt = System.nanoTime();
        ChatAnswerResult result = service.ask("张雪是干啥的");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        Assertions.assertTrue(enteredCall.await(1, TimeUnit.SECONDS));
        Assertions.assertAll(
                () -> Assertions.assertFalse(result.isAnswerAvailable()),
                () -> Assertions.assertTrue(result.getDegradedReason().contains("超时")),
                () -> Assertions.assertNull(result.getRetrievalMode()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty()),
                () -> Assertions.assertTrue(elapsedMillis < 1_000L, "expected timeout to return before the mock finished")
        );
    }

    @Test
    void askUsesRemainingBudgetAfterRetrievalBeforeCallingChatModel() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        when(hybridRetrievalService.retrieve("张雪是干啥的", 1, 4)).thenAnswer(invocation -> {
            Thread.sleep(80L);
            return hybridResult();
        });

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                try {
                    Thread.sleep(1_000L);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("迟到的答案[1]"))));
            }
        };

        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(Duration.ofMillis(100), 4, 320),
                chatModelProvider);

        long startedAt = System.nanoTime();
        ChatAnswerResult result = service.ask("张雪是干啥的");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        Assertions.assertAll(
                () -> Assertions.assertFalse(result.isAnswerAvailable()),
                () -> Assertions.assertTrue(result.getDegradedReason().contains("超时")),
                () -> Assertions.assertNull(result.getRetrievalMode()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty()),
                () -> Assertions.assertTrue(elapsedMillis < 500L, "expected remaining budget to cap total time after retrieval")
        );
    }

    @Test
    void askUsesRemainingBudgetForCitationRepair() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        AtomicInteger callCount = new AtomicInteger();

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                int call = callCount.incrementAndGet();
                try {
                    if (call == 1) {
                        Thread.sleep(60L);
                        return new ChatResponse(List.of(new Generation(new AssistantMessage("这是没有引用的混合检索答案"))));
                    }
                    Thread.sleep(1_000L);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("这是补充引用后的混合检索答案[1]"))));
            }
        };

        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult(4));
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(Duration.ofMillis(100), 4, 320),
                chatModelProvider);

        long startedAt = System.nanoTime();
        ChatAnswerResult result = service.ask("请总结腾讯新闻中的AI相关新闻");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        Assertions.assertAll(
                () -> Assertions.assertEquals(1, callCount.get()),
                () -> Assertions.assertFalse(result.isAnswerAvailable()),
                () -> Assertions.assertTrue(result.getDegradedReason().contains("超时")),
                () -> Assertions.assertNull(result.getRetrievalMode()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty()),
                () -> Assertions.assertTrue(elapsedMillis < 500L,
                        "expected citation repair to stop once the remaining budget no longer leaves a safe cushion")
        );
    }

    @Test
    void askReturnsBeforeConfiguredTimeoutWallOnSlowDegradedPath() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        when(hybridRetrievalService.retrieve("张雪是干啥的", 1, 4)).thenReturn(hybridResult());

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                try {
                    Thread.sleep(5_000L);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("迟到的答案[1]"))));
            }
        };

        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(Duration.ofMillis(200), 4, 320),
                chatModelProvider);

        long startedAt = System.nanoTime();
        ChatAnswerResult result = service.ask("张雪是干啥的");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        Assertions.assertAll(
                () -> Assertions.assertFalse(result.isAnswerAvailable()),
                () -> Assertions.assertTrue(result.getDegradedReason().contains("超时")),
                () -> Assertions.assertNull(result.getRetrievalMode()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty()),
                () -> Assertions.assertTrue(elapsedMillis < 190L,
                        "chat timeout degradation should leave a small cushion before the configured timeout wall")
        );
    }

    @Test
    void buildPromptTruncatesChunkTextWithinConfiguredBudget() {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(Duration.ofSeconds(25), 4, 10),
                chatModelProvider);
        HybridChunkResult chunk = new HybridChunkResult();
        chunk.setTitle("标题");
        chunk.setSource("腾讯新闻");
        chunk.setChunkText("12345678901234567890");

        String prompt = service.buildPrompt("问题", List.of(chunk), "hybrid", 10);

        Assertions.assertAll(
                () -> Assertions.assertTrue(prompt.contains("1234567890...")),
                () -> Assertions.assertFalse(prompt.contains("12345678901234567890"))
        );
    }

    @Test
    void askPrependsKnowledgeBaseAnswerPrefixWhenOnlyLexicallyGroundedChunksAreCited() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("这是仅词法证据支撑的答案[2]"))));
            }
        };

        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult(4));
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("请总结腾讯新闻中的AI相关新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("【本次回答基于知识库检索结果生成】这是仅词法证据支撑的答案[2]", result.getAnswer()),
                () -> Assertions.assertEquals(1, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-2", result.getCitations().getFirst().getChunkId())
        );
    }

    @Test
    void askReturnsInsufficientEvidenceWhenRetrievalReturnsNoChunks() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(hybridRetrievalService.retrieve("最近新闻资讯", 1, 4)).thenReturn(emptyLexicalOnlyResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.DISABLED, false, CONFIG_DISABLED_DETAIL),
                false,
                AiFallbackMode.LEXICAL_ONLY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("最近新闻资讯");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("证据不足", result.getAnswer()),
                () -> Assertions.assertEquals(HybridRetrievalService.MODE_LEXICAL_ONLY, result.getRetrievalMode()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty())
        );
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "最近新闻资讯",
            "qwertyuiopasdfghjkl",
            "这个系统上线情况",
            "实时全网新闻支持",
            "人工智能最新进展"
    })
    void askReturnsInsufficientEvidenceForManyEmptyRetrievalQuestions(String question) throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(hybridRetrievalService.retrieve(question, 1, 4)).thenReturn(emptyLexicalOnlyResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.DISABLED, false, CONFIG_DISABLED_DETAIL),
                false,
                AiFallbackMode.LEXICAL_ONLY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask(question);

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("证据不足", result.getAnswer()),
                () -> Assertions.assertEquals(HybridRetrievalService.MODE_LEXICAL_ONLY, result.getRetrievalMode()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty())
        );
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void askRepairsLexicalOnlyAnswerWhenFirstResponseHasNoValidCitationMarker() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("这是没有引用的词法答案")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("这是补充引用后的词法答案[2]")))));
        when(hybridRetrievalService.retrieve("最近有什么新闻", 1, 4)).thenReturn(lexicalOnlyResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.DISABLED, false, CONFIG_DISABLED_DETAIL),
                false,
                AiFallbackMode.LEXICAL_ONLY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("最近有什么新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("【本次回答基于知识库检索结果生成】这是补充引用后的词法答案[2]", result.getAnswer()),
                () -> Assertions.assertEquals(1, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-2", result.getCitations().getFirst().getChunkId())
        );
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void askRepairsHybridAnswerWhenFirstResponseHasNoValidCitationMarker() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("这是没有引用的混合检索答案")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("这是补充引用后的混合检索答案[1]")))));
        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult(4));
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("请总结腾讯新闻中的AI相关新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("【本次回答基于知识库检索结果生成】这是补充引用后的混合检索答案[1]", result.getAnswer()),
                () -> Assertions.assertEquals(1, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-1", result.getCitations().getFirst().getChunkId()),
                () -> Assertions.assertEquals(Integer.valueOf(1), result.getCitations().getFirst().getVectorRank())
        );
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void askNormalizesAnswerToInsufficientEvidenceWhenNoValidCitationMarkerExistsForNonEmptyRetrieval() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatModel chatModel = mock(ChatModel.class);

        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("这是没有有效引用标记的答案[9]")))));

        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult(4));
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("请总结腾讯新闻中的AI相关新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("证据不足", result.getAnswer()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty())
        );
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    private HybridRetrievalResult hybridResult() {
        return hybridResult(6);
    }

    private HybridRetrievalResult hybridResult(int topK) {
        HybridRetrievalResult result = new HybridRetrievalResult();
        result.setMode(HybridRetrievalService.MODE_HYBRID);
        List<HybridChunkResult> chunks = new ArrayList<>();
        int limit = Math.min(topK, 6);
        for (int i = 1; i <= limit; i++) {
            HybridChunkResult chunk = new HybridChunkResult();
            chunk.setDocId("doc-" + i);
            chunk.setChunkId("chunk-" + i);
            chunk.setTitle("标题-" + i);
            chunk.setSource("腾讯新闻");
            chunk.setSourceUrl("https://example.com/" + i);
            chunk.setChunkText("片段内容-" + i);
            chunk.setLexicalRank(i);
            if (i == 1) {
                chunk.setVectorRank(1);
            }
            chunks.add(chunk);
        }
        result.setResults(chunks);
        return result;
    }

    private HybridRetrievalResult lexicalOnlyResult() {
        HybridRetrievalResult result = new HybridRetrievalResult();
        result.setMode(HybridRetrievalService.MODE_LEXICAL_ONLY);
        List<HybridChunkResult> chunks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            HybridChunkResult chunk = new HybridChunkResult();
            chunk.setDocId("doc-" + i);
            chunk.setChunkId("chunk-" + i);
            chunk.setTitle("标题-" + i);
            chunk.setSource("腾讯新闻");
            chunk.setSourceUrl("https://example.com/" + i);
            chunk.setChunkText("片段内容-" + i);
            chunk.setLexicalRank(i);
            chunks.add(chunk);
        }
        result.setResults(chunks);
        return result;
    }

    private HybridRetrievalResult emptyLexicalOnlyResult() {
        HybridRetrievalResult result = new HybridRetrievalResult();
        result.setMode(HybridRetrievalService.MODE_LEXICAL_ONLY);
        result.setResults(List.of());
        return result;
    }

    private AiProperties aiProperties() {
        return aiProperties(Duration.ofSeconds(25), 4, 320);
    }

    private AiProperties aiProperties(String chatProvider) {
        AiProperties properties = aiProperties();
        properties.setChatProvider(chatProvider);
        properties.getGemini().setEnabled(true);
        properties.getGemini().setApiKey("test-gemini-key");
        return properties;
    }

    private AiProperties aiProperties(Duration timeout, int topK, int chunkCharLimit) {
        AiProperties properties = new AiProperties();
        properties.getOllama().setChatTimeout(timeout);
        properties.getOllama().setChatContextTopK(topK);
        properties.getOllama().setChatChunkCharLimit(chunkCharLimit);
        properties.getOllama().setChatNumPredict(96);
        properties.getOllama().setChatNumCtx(2048);
        properties.getOllama().setChatTemperature(0.1d);
        properties.getOllama().setChatTopP(0.8d);
        properties.getOllama().setKeepAlive("10m");
        return properties;
    }

    @Test
    void askResolvesSingleCitationCorrectly() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("答案是内容[1]"))));
            }
        };

        when(hybridRetrievalService.retrieve("测试问题", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("测试问题");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals(1, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-1", result.getCitations().getFirst().getChunkId())
        );
    }

    @Test
    void askResolvesMultipleCitationsInOneBracket() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("答案是内容[1, 2]"))));
            }
        };

        when(hybridRetrievalService.retrieve("测试问题", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("测试问题");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals(2, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-1", result.getCitations().get(0).getChunkId()),
                () -> Assertions.assertEquals("chunk-2", result.getCitations().get(1).getChunkId())
        );
    }

    @Test
    void askResolvesMultipleCitationsWithSpaces() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("答案是内容[1,  2,   3]"))));
            }
        };

        when(hybridRetrievalService.retrieve("测试问题", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("测试问题");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals(3, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-1", result.getCitations().get(0).getChunkId()),
                () -> Assertions.assertEquals("chunk-2", result.getCitations().get(1).getChunkId()),
                () -> Assertions.assertEquals("chunk-3", result.getCitations().get(2).getChunkId())
        );
    }

    @Test
    void askResolvesMixedSingleAndMultipleCitations() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("答案1[1], 答案2[2, 3], 答案3[4]"))));
            }
        };

        when(hybridRetrievalService.retrieve("测试问题", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("测试问题");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals(4, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-1", result.getCitations().get(0).getChunkId()),
                () -> Assertions.assertEquals("chunk-2", result.getCitations().get(1).getChunkId()),
                () -> Assertions.assertEquals("chunk-3", result.getCitations().get(2).getChunkId()),
                () -> Assertions.assertEquals("chunk-4", result.getCitations().get(3).getChunkId())
        );
    }

    @Test
    void askIgnoresOutofRangeCitations() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("答案[1, 99]"))));
            }
        };

        when(hybridRetrievalService.retrieve("测试问题", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("测试问题");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals(1, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-1", result.getCitations().getFirst().getChunkId())
        );
    }

    @Test
    void askIgnoresMalformedCitationsAndKeepsValidOnes() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("答案[1, x, 2, , 3]"))));
            }
        };

        when(hybridRetrievalService.retrieve("测试问题", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("测试问题");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals(3, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-1", result.getCitations().get(0).getChunkId()),
                () -> Assertions.assertEquals("chunk-2", result.getCitations().get(1).getChunkId()),
                () -> Assertions.assertEquals("chunk-3", result.getCitations().get(2).getChunkId())
        );
    }

    @Test
    void askHandlesEmptyCitationsGracefully() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("答案[,,]"))));
            }
        };

        when(hybridRetrievalService.retrieve("测试问题", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("测试问题");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("证据不足", result.getAnswer()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty())
        );
    }

    @Test
    void askDoesNotRetryWithNonQuestionWords() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        when(hybridRetrievalService.retrieve("这是一个普通的查询", 1, 4)).thenReturn(emptyLexicalOnlyResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("这是一个普通的查询");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("证据不足", result.getAnswer()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty())
        );
        verify(hybridRetrievalService).retrieve("这是一个普通的查询", 1, 4);
        verify(hybridRetrievalService, times(1)).retrieve(anyString(), anyInt(), anyInt());
    }

    @Test
    void extractQuestionKeywordsRemovesCommonQuestionWords() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);

        Assertions.assertAll(
                () -> Assertions.assertEquals("孙东旭卸任", service.extractQuestionKeywords("孙东旭为什么卸任")),
                () -> Assertions.assertEquals("东方甄选的主播是离职的", service.extractQuestionKeywords("东方甄选的主播是如何离职的呢")),
                () -> Assertions.assertEquals("董宇辉的薪资是", service.extractQuestionKeywords("董宇辉的薪资是多少吗")),
                () -> Assertions.assertEquals("腾讯新闻", service.extractQuestionKeywords("腾讯新闻"))
        );
    }

    @Test
    void askRetriesWithKeywordWhenFirstRoundReturnsInsufficientEvidence() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        AtomicInteger callCount = new AtomicInteger();

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                int call = callCount.incrementAndGet();
                if (call == 1) {
                    // 第一轮：返回证据不足
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("证据不足"))));
                } else {
                    // 第二轮：返回有效答案
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("孙东旭卸任CEO是因为公司调整[1]"))));
                }
            }
        };

        // 第一轮检索：有结果但不相关（导致AI返回证据不足）
        HybridRetrievalResult firstRoundRetrieval = hybridResult(4);
        // 修改内容使其不相关
        firstRoundRetrieval.getResults().get(0).setChunkText("不相关的内容");

        // 第二轮检索：关键词化后找到相关结果
        when(hybridRetrievalService.retrieve("孙东旭为什么卸任CEO", 1, 4)).thenReturn(firstRoundRetrieval);
        when(hybridRetrievalService.retrieve("孙东旭卸任CEO", 1, 2)).thenReturn(hybridResult(2));
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("孙东旭为什么卸任CEO");

        // 明确断言返回的是第二轮结果
        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertTrue(result.getAnswer().contains("孙东旭卸任CEO是因为公司调整"),
                        "应该返回第二轮答案，而不是第一轮证据不足"),
                () -> Assertions.assertEquals(1, result.getCitations().size(),
                        "应该返回第二轮的引用"),
                () -> Assertions.assertTrue(result.getRetrievalMode().contains("keyword-fallback"),
                        "应该包含keyword-fallback后缀"),
                () -> Assertions.assertEquals("孙东旭为什么卸任CEO", result.getQuestion(),
                        "应该保持原问题")
        );
        verify(hybridRetrievalService).retrieve("孙东旭为什么卸任CEO", 1, 4);
        verify(hybridRetrievalService).retrieve("孙东旭卸任CEO", 1, 2);
    }

    @Test
    void askKeepsFirstRoundWhenSecondRoundIsNotBetter() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        AtomicInteger callCount = new AtomicInteger();

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                int call = callCount.incrementAndGet();
                if (call == 1) {
                    // 第一轮：返回有效答案（无问句词，不触发重试）
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("第一轮答案[1]"))));
                } else {
                    // 第二轮：返回有效答案（但不如第一轮）
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("第二轮答案[1]"))));
                }
            }
        };

        when(hybridRetrievalService.retrieve("普通查询", 1, 4)).thenReturn(hybridResult());
        when(hybridRetrievalService.retrieve("普通查询关键词", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(providerStatusService.activeChatStatus()).thenReturn(new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("普通查询");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertTrue(result.getAnswer().contains("第一轮答案"),
                        "应该返回第一轮答案，因为第二轮不是更优"),
                () -> Assertions.assertEquals(1, result.getCitations().size()),
                () -> Assertions.assertFalse(result.getRetrievalMode().contains("keyword-fallback"))
        );
        verify(hybridRetrievalService).retrieve("普通查询", 1, 4);
        verify(hybridRetrievalService, times(1)).retrieve(anyString(), anyInt(), anyInt());
    }

    @Test
    void isSecondRoundBetterReturnsTrueWhenSecondRoundHasValidAnswerAndCitations() {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);

        ChatAnswerResult firstRound = new ChatAnswerResult();
        firstRound.setAnswer("证据不足");
        firstRound.setCitations(new ArrayList<>());

        ChatAnswerResult secondRound = new ChatAnswerResult();
        secondRound.setAnswer("【本次回答基于知识库检索结果生成】孙东旭卸任CEO是因为公司调整[1]");
        List<HybridChunkResult> citations = new ArrayList<>();
        HybridChunkResult chunk = new HybridChunkResult();
        chunk.setChunkId("chunk-1");
        citations.add(chunk);
        secondRound.setCitations(citations);

        boolean result = service.isSecondRoundBetter(secondRound, firstRound);
        Assertions.assertTrue(result, "第二轮有有效答案和引用，应该被认为是更优");
    }

    @Test
    void isSecondRoundBetterReturnsFalseWhenSecondRoundHasNoCitations() {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);

        ChatAnswerResult firstRound = new ChatAnswerResult();
        firstRound.setAnswer("证据不足");
        firstRound.setCitations(new ArrayList<>());

        ChatAnswerResult secondRound = new ChatAnswerResult();
        secondRound.setAnswer("【本次回答基于知识库检索结果生成】孙东旭卸任CEO是因为公司调整[1]");
        secondRound.setCitations(new ArrayList<>());

        boolean result = service.isSecondRoundBetter(secondRound, firstRound);
        Assertions.assertFalse(result, "第二轮没有引用，不应该被认为是更优");
    }

    @Test
    void isSecondRoundBetterReturnsFalseWhenSecondRoundIsInsufficientEvidence() {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);

        ChatAnswerResult firstRound = new ChatAnswerResult();
        firstRound.setAnswer("证据不足");
        firstRound.setCitations(new ArrayList<>());

        ChatAnswerResult secondRound = new ChatAnswerResult();
        secondRound.setAnswer("证据不足");
        List<HybridChunkResult> citations = new ArrayList<>();
        HybridChunkResult chunk = new HybridChunkResult();
        chunk.setChunkId("chunk-1");
        citations.add(chunk);
        secondRound.setCitations(citations);

        boolean result = service.isSecondRoundBetter(secondRound, firstRound);
        Assertions.assertFalse(result, "第二轮答案仍然是证据不足，不应该被认为是更优");
    }
}
