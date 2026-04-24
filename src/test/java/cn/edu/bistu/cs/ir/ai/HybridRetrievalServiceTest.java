package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import cn.edu.bistu.cs.ir.index.IdxService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceTest {

    @Test
    void retrieveRunsLexicalAndVectorInParallelWhenVectorPathIsAvailable() throws Exception {
        AtomicLong lexicalStartedAt = new AtomicLong();
        AtomicLong vectorStartedAt = new AtomicLong();
        HybridRetrievalService service = new TimedHybridRetrievalService(lexicalStartedAt, vectorStartedAt);

        Instant started = Instant.now();
        HybridRetrievalResult result = service.retrieve("新闻检索助手", 1, 10);
        long elapsedMillis = Duration.between(started, Instant.now()).toMillis();

        Assertions.assertAll(
                () -> Assertions.assertEquals(HybridRetrievalService.MODE_HYBRID, result.getMode()),
                () -> Assertions.assertTrue(elapsedMillis < 700, "lexical/vector retrieval should overlap in time"),
                () -> Assertions.assertTrue(Math.abs(lexicalStartedAt.get() - vectorStartedAt.get()) < 200,
                        "lexical and vector retrieval should start nearly together")
        );
    }

    @Test
    void fuseUsesRrfAndMergesCitationMetadata() {
        HybridRetrievalService service = new HybridRetrievalService(
                mock(IdxService.class),
                mock(ArticleChunkingService.class),
                mock(ProviderStatusService.class),
                mock(ObjectProvider.class),
                aiProperties(Duration.ofSeconds(120)));

        HybridChunkResult lexicalPrimary = chunk("doc-1", "chunk-1", "词法标题", null, null, 1, null);
        lexicalPrimary.setChunkText("词法片段");
        HybridChunkResult lexicalSecondary = chunk("doc-2", "chunk-2", "第二条", "https://lexical-only", null, 2, null);
        HybridChunkResult vectorPrimary = chunk("doc-1", "chunk-1", null, "https://vector", "腾讯新闻", null, 1);
        vectorPrimary.setChunkText("更长的向量片段内容");
        HybridChunkResult vectorSecondary = chunk("doc-3", "chunk-3", "第三条", "https://vector-3", "腾讯新闻", null, 2);

        List<HybridChunkResult> fused = service.fuse(
                List.of(lexicalPrimary, lexicalSecondary),
                List.of(vectorPrimary, vectorSecondary));

        Assertions.assertAll(
                () -> Assertions.assertEquals("chunk-1", fused.getFirst().getChunkId()),
                () -> Assertions.assertEquals("https://vector", fused.getFirst().getSourceUrl()),
                () -> Assertions.assertEquals("腾讯新闻", fused.getFirst().getSource()),
                () -> Assertions.assertEquals("更长的向量片段内容", fused.getFirst().getChunkText()),
                () -> Assertions.assertEquals(1, fused.getFirst().getLexicalRank()),
                () -> Assertions.assertEquals(1, fused.getFirst().getVectorRank()),
                () -> Assertions.assertTrue(fused.getFirst().getFusedScore() > fused.get(1).getFusedScore())
        );
    }

    @Test
    void retrieveFallsBackToLexicalWhenVectorStoreBeanCreationFails() throws Exception {
        IdxService idxService = mock(IdxService.class);
        ArticleChunkingService chunkingService = mock(ArticleChunkingService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);

        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY));
        when(vectorStoreProvider.getIfAvailable()).thenThrow(new IllegalStateException("vector store init boom"));

        HybridRetrievalService service = new HybridRetrievalService(idxService,
                chunkingService,
                providerStatusService,
                vectorStoreProvider,
                aiProperties(Duration.ofSeconds(120))) {
            @Override
            protected List<HybridChunkResult> lexicalRetrieve(String question) {
                return List.of(chunk("doc-1", "chunk-1", "词法标题", "https://lexical", "腾讯新闻", 1, null));
            }
        };

        HybridRetrievalResult result = service.retrieve("新闻检索助手", 1, 10);

        Assertions.assertAll(
                () -> Assertions.assertEquals(HybridRetrievalService.MODE_LEXICAL_ONLY, result.getMode()),
                () -> Assertions.assertEquals(1, result.getResults().size()),
                () -> Assertions.assertTrue(result.getDegradedReason().contains("vector store init boom"))
        );
    }

    @Test
    void retrieveFallsBackToLexicalWhenSnapshotReportsMissingQdrantCollection() throws Exception {
        IdxService idxService = mock(IdxService.class);
        ArticleChunkingService chunkingService = mock(ArticleChunkingService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);

        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.DEGRADED, true,
                        "Qdrant is reachable at http://127.0.0.1:6333, but collection 'news article chunks' does not exist."),
                true,
                AiFallbackMode.LEXICAL_ONLY));

        HybridRetrievalService service = new HybridRetrievalService(idxService,
                chunkingService,
                providerStatusService,
                vectorStoreProvider,
                aiProperties(Duration.ofSeconds(120))) {
            @Override
            protected List<HybridChunkResult> lexicalRetrieve(String question) {
                return List.of(chunk("doc-1", "chunk-1", "词法标题", "https://lexical", "腾讯新闻", 1, null));
            }
        };

        HybridRetrievalResult result = service.retrieve("新闻检索助手", 1, 10);

        Assertions.assertAll(
                () -> Assertions.assertEquals(HybridRetrievalService.MODE_LEXICAL_ONLY, result.getMode()),
                () -> Assertions.assertEquals(1, result.getResults().size()),
                () -> Assertions.assertEquals(
                        "Qdrant is reachable at http://127.0.0.1:6333, but collection 'news article chunks' does not exist.",
                        result.getDegradedReason())
        );
    }

    @Test
    void retrieveDegradesToLexicalOnlyWhenVectorRetrievalExceedsTimeoutBudget() throws Exception {
        HybridRetrievalService service = new BlockingVectorHybridRetrievalService(Duration.ZERO, Duration.ofMillis(100));

        HybridRetrievalResult result = retrieveWithinBudget(service, "新闻检索助手", Duration.ofMillis(250));

        Assertions.assertAll(
                () -> Assertions.assertEquals(HybridRetrievalService.MODE_LEXICAL_ONLY, result.getMode()),
                () -> Assertions.assertFalse(result.getResults().isEmpty()),
                () -> Assertions.assertEquals("chunk-lexical", result.getResults().getFirst().getChunkId()),
                () -> Assertions.assertEquals(Integer.valueOf(1), result.getResults().getFirst().getLexicalRank()),
                () -> Assertions.assertNull(result.getResults().getFirst().getVectorRank()),
                () -> Assertions.assertNotNull(result.getResults().getFirst().getSourceUrl()),
                () -> Assertions.assertNotNull(result.getDegradedReason()),
                () -> Assertions.assertTrue(result.getDegradedReason().contains("超时"),
                        "degraded reason should be machine-checkable for timeout handling")
        );
    }

    @Test
    void retrieveUsesOneEndToEndBudgetInsteadOfAddingFreshVectorTimeoutAfterSlowLexical() throws Exception {
        HybridRetrievalService service = new BlockingVectorHybridRetrievalService(Duration.ofMillis(150), Duration.ofMillis(200));

        Instant started = Instant.now();
        HybridRetrievalResult result = retrieveWithinBudget(service, "新闻检索助手", Duration.ofMillis(320));
        long elapsedMillis = Duration.between(started, Instant.now()).toMillis();

        Assertions.assertAll(
                () -> Assertions.assertEquals(HybridRetrievalService.MODE_LEXICAL_ONLY, result.getMode()),
                () -> Assertions.assertFalse(result.getResults().isEmpty()),
                () -> Assertions.assertEquals("chunk-lexical", result.getResults().getFirst().getChunkId()),
                () -> Assertions.assertTrue(result.getDegradedReason().contains("超时")),
                () -> Assertions.assertTrue(result.getDegradedReason().contains("200毫秒")),
                () -> Assertions.assertTrue(elapsedMillis < 320,
                        "retrieve should honor one end-to-end vector budget instead of lexical latency plus a fresh timeout")
        );
    }

    @Test
    void retrieveReturnsBeforeConfiguredTimeoutWallWhenVectorPathTimesOut() throws Exception {
        HybridRetrievalService service = new BlockingVectorHybridRetrievalService(Duration.ZERO, Duration.ofMillis(200));

        Instant started = Instant.now();
        HybridRetrievalResult result = retrieveWithinBudget(service, "新闻检索助手", Duration.ofMillis(260));
        long elapsedMillis = Duration.between(started, Instant.now()).toMillis();

        Assertions.assertAll(
                () -> Assertions.assertEquals(HybridRetrievalService.MODE_LEXICAL_ONLY, result.getMode()),
                () -> Assertions.assertTrue(result.getDegradedReason().contains("超时")),
                () -> Assertions.assertTrue(elapsedMillis < 190,
                        "retrieve should leave a small cushion before the configured timeout wall")
        );
    }

    @Test
    void retrieveUsesTwoSecondGuardBandForProductionSizedTimeouts() {
        HybridRetrievalService service = new HybridRetrievalService(
                mock(IdxService.class),
                mock(ArticleChunkingService.class),
                mock(ProviderStatusService.class),
                mock(ObjectProvider.class),
                aiProperties(Duration.ofSeconds(120)));

        Assertions.assertEquals(TimeUnit.SECONDS.toNanos(2), service.vectorTimeoutGuardBandNanos());
    }

    private HybridRetrievalResult retrieveWithinBudget(HybridRetrievalService service,
                                                       String question,
                                                       Duration timeout) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
        Future<HybridRetrievalResult> future = executor.submit(() -> service.retrieve(question, 1, 10));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            future.cancel(true);
            Assertions.fail("retrieve should degrade to lexical-only within the timeout budget instead of hanging forever", e);
            throw e;
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(cause);
        }
        finally {
            executor.shutdownNow();
        }
    }

    private static HybridChunkResult chunk(String docId,
                                           String chunkId,
                                           String title,
                                           String sourceUrl,
                                           String source,
                                           Integer lexicalRank,
                                           Integer vectorRank) {
        HybridChunkResult chunk = new HybridChunkResult();
        chunk.setDocId(docId);
        chunk.setChunkId(chunkId);
        chunk.setTitle(title);
        chunk.setSourceUrl(sourceUrl);
        chunk.setSource(source);
        chunk.setLexicalRank(lexicalRank);
        chunk.setVectorRank(vectorRank);
        return chunk;
    }

    private static class TimedHybridRetrievalService extends HybridRetrievalService {

        private final AtomicLong lexicalStartedAt;

        private final AtomicLong vectorStartedAt;

        private TimedHybridRetrievalService(AtomicLong lexicalStartedAt, AtomicLong vectorStartedAt) {
            super(mock(IdxService.class),
                    mock(ArticleChunkingService.class),
                    availableProviderStatusService(),
                    availableVectorStoreProvider(),
                    aiProperties(Duration.ofSeconds(120)));
            this.lexicalStartedAt = lexicalStartedAt;
            this.vectorStartedAt = vectorStartedAt;
        }

        @Override
        protected List<HybridChunkResult> lexicalRetrieve(String question) {
            lexicalStartedAt.set(System.currentTimeMillis());
            sleep();
            return List.of(chunk("doc-1", "chunk-1", "词法", "https://lexical", "腾讯新闻", 1, null));
        }

        @Override
        protected List<HybridChunkResult> vectorRetrieve(String question) {
            vectorStartedAt.set(System.currentTimeMillis());
            sleep();
            return List.of(chunk("doc-2", "chunk-2", "向量", "https://vector", "腾讯新闻", null, 1));
        }

        private void sleep() {
            try {
                Thread.sleep(350L);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        private static ProviderStatusService availableProviderStatusService() {
            ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
            when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                    new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                    new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                    new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                    true,
                    AiFallbackMode.AI_READY));
            return providerStatusService;
        }

        @SuppressWarnings("unchecked")
        private static ObjectProvider<VectorStore> availableVectorStoreProvider() {
            ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
            when(vectorStoreProvider.getIfAvailable()).thenReturn(mock(VectorStore.class));
            return vectorStoreProvider;
        }
    }

    private static class BlockingVectorHybridRetrievalService extends HybridRetrievalService {

        private final Duration lexicalDelay;

        private final Duration vectorTimeout;

        private BlockingVectorHybridRetrievalService(Duration lexicalDelay, Duration vectorTimeout) {
            super(mock(IdxService.class),
                    mock(ArticleChunkingService.class),
                    TimedHybridRetrievalService.availableProviderStatusService(),
                    TimedHybridRetrievalService.availableVectorStoreProvider(),
                    aiProperties(vectorTimeout));
            this.lexicalDelay = lexicalDelay;
            this.vectorTimeout = vectorTimeout;
        }

        @Override
        protected List<HybridChunkResult> lexicalRetrieve(String question) {
            sleep(lexicalDelay);
            return List.of(chunk("doc-lexical", "chunk-lexical", "词法标题", "https://lexical", "腾讯新闻", 1, null));
        }

        @Override
        protected List<HybridChunkResult> vectorRetrieve(String question) {
            sleep(Duration.ofMinutes(5));
            return List.of(chunk("doc-vector", "chunk-vector", "向量标题", "https://vector", "腾讯新闻", null, 1));
        }

        @Override
        protected long vectorRetrievalTimeoutMillis() {
            return vectorTimeout.toMillis();
        }

        private void sleep(Duration duration) {
            if (duration.isZero() || duration.isNegative()) {
                return;
            }
            try {
                Thread.sleep(duration.toMillis());
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static AiProperties aiProperties(Duration vectorTimeout) {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getRetrieval().setVectorTimeout(vectorTimeout);
        return aiProperties;
    }

    private static class DaemonThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "hybrid-retrieval-timeout-test");
            thread.setDaemon(true);
            return thread;
        }
    }
}
