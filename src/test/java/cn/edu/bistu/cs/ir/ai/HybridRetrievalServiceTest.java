package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.index.IdxService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
                mock(ObjectProvider.class));

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

        HybridRetrievalService service = new HybridRetrievalService(idxService, chunkingService, providerStatusService, vectorStoreProvider) {
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
                    mock(ProviderStatusService.class),
                    mock(ObjectProvider.class));
            this.lexicalStartedAt = lexicalStartedAt;
            this.vectorStartedAt = vectorStartedAt;
        }

        @Override
        protected boolean isVectorRetrievalAvailable() {
            return true;
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
    }
}
