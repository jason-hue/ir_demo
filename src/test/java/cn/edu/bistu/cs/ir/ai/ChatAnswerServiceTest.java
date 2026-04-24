package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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

    private static final String NO_DATA_HINT = "\n\n提示：知识库中未检索到相关数据，以上回答由模型基于通用知识生成，未基于库内证据。";

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

        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("请总结腾讯新闻中的AI相关新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("基于片段整理的答案[1]", result.getAnswer()),
                () -> Assertions.assertEquals(1, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-1", result.getCitations().getFirst().getChunkId()),
                () -> Assertions.assertTrue(promptRef.get().getContents().contains("请严格基于给定检索片段回答问题")),
                () -> Assertions.assertTrue(promptRef.get().getContents().contains("如果证据不足，请只明确回答“证据不足”")),
                () -> Assertions.assertTrue(promptRef.get().getContents().contains("[4] 标题-4")),
                () -> Assertions.assertFalse(promptRef.get().getContents().contains("[5] 标题-5")),
                () -> Assertions.assertTrue(promptRef.get().getContents().contains("在答案中使用[1]、[2]引用片段编号")),
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
        verify(hybridRetrievalService, never()).retrieve(anyString(), anyInt(), anyInt());
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

        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
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

        String prompt = service.buildPrompt("问题", List.of(chunk), "hybrid");

        Assertions.assertAll(
                () -> Assertions.assertTrue(prompt.contains("1234567890...")),
                () -> Assertions.assertFalse(prompt.contains("12345678901234567890"))
        );
    }

    @Test
    void askPrependsNonVectorEvidencePrefixWhenOnlyLexicallyGroundedChunksAreCited() throws Exception {
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

        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("请总结腾讯新闻中的AI相关新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("【非向量证据回答】这是仅词法证据支撑的答案[2]", result.getAnswer()),
                () -> Assertions.assertEquals(1, result.getCitations().size()),
                () -> Assertions.assertEquals("chunk-2", result.getCitations().getFirst().getChunkId())
        );
    }

    @Test
    void askReturnsModelAnswerWithFixedHintWhenRetrievalReturnsNoChunks() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);

        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("这是模型基于通用知识生成的回答"))));
            }
        };

        when(hybridRetrievalService.retrieve("最近有什么新闻", 1, 4)).thenReturn(emptyLexicalOnlyResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.DISABLED, false, CONFIG_DISABLED_DETAIL),
                false,
                AiFallbackMode.LEXICAL_ONLY));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("最近有什么新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("这是模型基于通用知识生成的回答" + NO_DATA_HINT, result.getAnswer()),
                () -> Assertions.assertEquals(HybridRetrievalService.MODE_LEXICAL_ONLY, result.getRetrievalMode()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty())
        );
    }

    @Test
    void askKeepsEmptyRetrievalAnswerUntouchedWhenModelEmitsCitationLikeText() throws Exception {
        HybridRetrievalService hybridRetrievalService = mock(HybridRetrievalService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        ChatModel chatModel = mock(ChatModel.class);

        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("这是模型按通用知识生成的回答[1]")))));
        when(hybridRetrievalService.retrieve("最近有什么新闻", 1, 4)).thenReturn(emptyLexicalOnlyResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.DISABLED, false, CONFIG_DISABLED_DETAIL),
                false,
                AiFallbackMode.LEXICAL_ONLY));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("最近有什么新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("这是模型按通用知识生成的回答[1]" + NO_DATA_HINT, result.getAnswer()),
                () -> Assertions.assertEquals(HybridRetrievalService.MODE_LEXICAL_ONLY, result.getRetrievalMode()),
                () -> Assertions.assertTrue(result.getCitations().isEmpty())
        );
        verify(chatModel, times(1)).call(any(Prompt.class));
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
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("最近有什么新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("【非向量证据回答】这是补充引用后的词法答案[2]", result.getAnswer()),
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
        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(chatModelProvider.getIfAvailable()).thenReturn(chatModel);

        ChatAnswerService service = new ChatAnswerService(hybridRetrievalService,
                providerStatusService,
                aiProperties(),
                chatModelProvider);
        ChatAnswerResult result = service.ask("请总结腾讯新闻中的AI相关新闻");

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.isAnswerAvailable()),
                () -> Assertions.assertEquals("这是补充引用后的混合检索答案[1]", result.getAnswer()),
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

        when(hybridRetrievalService.retrieve("请总结腾讯新闻中的AI相关新闻", 1, 4)).thenReturn(hybridResult());
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
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
        HybridRetrievalResult result = new HybridRetrievalResult();
        result.setMode(HybridRetrievalService.MODE_HYBRID);
        List<HybridChunkResult> chunks = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
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
}
