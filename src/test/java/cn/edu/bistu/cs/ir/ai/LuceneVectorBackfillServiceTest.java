package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.controller.dto.VectorBackfillResult;
import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.model.Article;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LuceneVectorBackfillServiceTest {

    @Test
    void backfillFailsFastWhenEmbeddingProviderIsNotAvailable() {
        IdxService idxService = mock(IdxService.class);
        ArticleChunkVectorSyncService syncService = mock(ArticleChunkVectorSyncService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "chat ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.DEGRADED, true, "embed probe failed"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "qdrant ok"),
                true,
                AiFallbackMode.LEXICAL_ONLY));

        LuceneVectorBackfillService service = new LuceneVectorBackfillService(idxService, syncService, providerStatusService);

        IllegalStateException error = Assertions.assertThrows(IllegalStateException.class, service::backfillExistingArticles);
        Assertions.assertTrue(error.getMessage().contains("embed probe failed"));
    }

    @Test
    void backfillCountsSuccessesAndFailuresAcrossStoredLuceneArticles() {
        IdxService idxService = mock(IdxService.class);
        ArticleChunkVectorSyncService syncService = mock(ArticleChunkVectorSyncService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "chat ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "embed ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "qdrant ok"),
                true,
                AiFallbackMode.AI_READY));
        Article first = article("doc-1");
        Article second = article("doc-2");
        when(idxService.listStoredArticles()).thenReturn(List.of(first, second));
        when(syncService.syncArticleForBackfill(first)).thenReturn(ArticleChunkVectorSyncService.SyncResult.succeeded());
        when(syncService.syncArticleForBackfill(second)).thenReturn(ArticleChunkVectorSyncService.SyncResult.failed("doc-2 failed"));

        LuceneVectorBackfillService service = new LuceneVectorBackfillService(idxService, syncService, providerStatusService);

        VectorBackfillResult result = service.backfillExistingArticles();
        Assertions.assertAll(
                () -> Assertions.assertEquals(2, result.scanned()),
                () -> Assertions.assertEquals(2, result.attempted()),
                () -> Assertions.assertEquals(1, result.succeeded()),
                () -> Assertions.assertEquals(1, result.failed()),
                () -> Assertions.assertEquals(List.of("doc-2 failed"), result.errorSamples()),
                () -> Assertions.assertFalse(result.finishedAt().isBefore(result.startedAt()))
        );
    }

    @Test
    void backfillPropagatesLuceneReadFailureInsteadOfReportingEmptySuccess() {
        IdxService idxService = mock(IdxService.class);
        ArticleChunkVectorSyncService syncService = mock(ArticleChunkVectorSyncService.class);
        ProviderStatusService providerStatusService = mock(ProviderStatusService.class);
        when(providerStatusService.snapshot()).thenReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "chat ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "embed ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "qdrant ok"),
                true,
                AiFallbackMode.AI_READY));
        when(idxService.listStoredArticles()).thenThrow(new IllegalStateException("无法读取Lucene索引中的存储文章，无法执行向量回填。"));

        LuceneVectorBackfillService service = new LuceneVectorBackfillService(idxService, syncService, providerStatusService);

        IllegalStateException error = Assertions.assertThrows(IllegalStateException.class, service::backfillExistingArticles);
        Assertions.assertTrue(error.getMessage().contains("无法读取Lucene索引中的存储文章"));
    }

    private static Article article(String docId) {
        Article article = mock(Article.class);
        doReturn(docId).when(article).getDocId();
        return article;
    }
}
