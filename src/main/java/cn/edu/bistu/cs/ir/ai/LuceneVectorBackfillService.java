package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.controller.dto.VectorBackfillResult;
import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.model.Article;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class LuceneVectorBackfillService {

    private static final int MAX_ERROR_SAMPLES = 5;

    private final IdxService idxService;

    private final ArticleChunkVectorSyncService articleChunkVectorSyncService;

    private final ProviderStatusService providerStatusService;

    public LuceneVectorBackfillService(IdxService idxService,
                                       ArticleChunkVectorSyncService articleChunkVectorSyncService,
                                       ProviderStatusService providerStatusService) {
        this.idxService = idxService;
        this.articleChunkVectorSyncService = articleChunkVectorSyncService;
        this.providerStatusService = providerStatusService;
    }

    public VectorBackfillResult backfillExistingArticles() {
        ProviderStatusSnapshot snapshot = providerStatusService.snapshot();
        if (snapshot.ollamaEmbedding().state() != ProviderAvailabilityState.AVAILABLE) {
            throw new IllegalStateException(snapshot.ollamaEmbedding().detail());
        }
        if (snapshot.qdrant().state() != ProviderAvailabilityState.AVAILABLE) {
            throw new IllegalStateException(snapshot.qdrant().detail());
        }

        Instant startedAt = Instant.now();
        List<Article> articles = idxService.listStoredArticles();
        List<String> errorSamples = new ArrayList<>();
        int attempted = 0;
        int succeeded = 0;

        for (Article article : articles) {
            attempted++;
            ArticleChunkVectorSyncService.SyncResult result = articleChunkVectorSyncService.syncArticleForBackfill(article);
            if (result.success()) {
                succeeded++;
                continue;
            }
            if (errorSamples.size() < MAX_ERROR_SAMPLES && result.detail() != null && !result.detail().isBlank()) {
                errorSamples.add(result.detail());
            }
        }

        return new VectorBackfillResult(
                startedAt,
                Instant.now(),
                articles.size(),
                attempted,
                succeeded,
                attempted - succeeded,
                List.copyOf(errorSamples));
    }
}
