package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.model.Article;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class IngestionObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(IngestionObservabilityService.class);

    private final Map<String, MutableRunStatus> activeRuns = new ConcurrentHashMap<>();

    private final Map<String, IngestionStatusSnapshot.CategoryRunStatus> lastRuns = new ConcurrentHashMap<>();

    public void startRun(String category, String source, int seedUrlCount, int maxArticles) {
        MutableRunStatus run = new MutableRunStatus(category, source, seedUrlCount, maxArticles, Instant.now());
        activeRuns.put(category, run);
        lastRuns.put(category, run.toSnapshot(IngestionStatusSnapshot.RunOutcome.RUNNING, null));
        log.info("开始记录分类[{}]的抓取运行情况，seedUrlCount=[{}]，maxArticles=[{}]，source=[{}]",
                category, seedUrlCount, maxArticles, source);
    }

    public void recordRequestSuccess(String category) {
        MutableRunStatus run = activeRuns.get(category);
        if (run != null) {
            run.requestSuccessCount.incrementAndGet();
        }
    }

    public void recordRequestFailure(String category, String detail) {
        MutableRunStatus run = activeRuns.get(category);
        if (run != null) {
            run.requestFailureCount.incrementAndGet();
            run.lastError = trim(detail);
        }
    }

    public void recordSemanticFailure(String category, String detail) {
        MutableRunStatus run = activeRuns.get(category);
        if (run != null) {
            run.requestFailureCount.incrementAndGet();
            run.lastError = trim(detail);
        }
    }

    public void recordIndexSuccess(String category, Article article) {
        MutableRunStatus run = activeRuns.get(category);
        if (run != null) {
            run.indexedDocumentCount.incrementAndGet();
            run.lastIndexedDocId = article == null ? null : trim(article.getDocId());
            run.lastIndexedSourceUrl = article == null ? null : trim(article.getSourceUrl());
        }
    }

    public void recordIndexFailure(String category, String detail, Article article) {
        MutableRunStatus run = activeRuns.get(category);
        if (run != null) {
            run.indexFailureCount.incrementAndGet();
            run.lastError = trim(detail);
            run.lastIndexedDocId = article == null ? run.lastIndexedDocId : trim(article.getDocId());
            run.lastIndexedSourceUrl = article == null ? run.lastIndexedSourceUrl : trim(article.getSourceUrl());
        }
    }

    public void markStopped(String category) {
        MutableRunStatus run = activeRuns.remove(category);
        if (run == null) {
            return;
        }
        Instant finishedAt = Instant.now();
        IngestionStatusSnapshot.RunOutcome outcome = deriveOutcome(run);
        IngestionStatusSnapshot.CategoryRunStatus snapshot = run.toSnapshot(outcome, finishedAt);
        lastRuns.put(category, snapshot);
        log.info("分类[{}]抓取结束，outcome=[{}]，indexedDocumentCount=[{}]，indexFailureCount=[{}]，requestFailureCount=[{}]",
                category,
                outcome,
                snapshot.indexedDocumentCount(),
                snapshot.indexFailureCount(),
                snapshot.requestFailureCount());
    }

    public IngestionStatusSnapshot snapshot() {
        Map<String, IngestionStatusSnapshot.CategoryRunStatus> combined = new LinkedHashMap<>(lastRuns);
        activeRuns.forEach((category, run) -> combined.put(category,
                run.toSnapshot(IngestionStatusSnapshot.RunOutcome.RUNNING, null)));
        List<IngestionStatusSnapshot.CategoryRunStatus> categories = new ArrayList<>(combined.values());
        categories.sort(Comparator.comparing(IngestionStatusSnapshot.CategoryRunStatus::category));
        return new IngestionStatusSnapshot(Instant.now(), List.copyOf(categories));
    }

    static IngestionStatusSnapshot.RunOutcome deriveOutcomeForTest(int indexedDocumentCount,
                                                                   int requestFailureCount,
                                                                   int indexFailureCount) {
        MutableRunStatus run = new MutableRunStatus("test", "test", 0, 0, Instant.now());
        run.indexedDocumentCount.set(indexedDocumentCount);
        run.requestFailureCount.set(requestFailureCount);
        run.indexFailureCount.set(indexFailureCount);
        return deriveOutcome(run);
    }

    private static IngestionStatusSnapshot.RunOutcome deriveOutcome(MutableRunStatus run) {
        boolean hasIndexedDocuments = run.indexedDocumentCount.get() > 0;
        boolean hasFailures = run.requestFailureCount.get() > 0 || run.indexFailureCount.get() > 0;
        if (hasIndexedDocuments && hasFailures) {
            return IngestionStatusSnapshot.RunOutcome.PARTIAL_SUCCESS;
        }
        if (hasFailures) {
            return IngestionStatusSnapshot.RunOutcome.FAILED;
        }
        if (hasIndexedDocuments) {
            return IngestionStatusSnapshot.RunOutcome.SUCCESS;
        }
        return IngestionStatusSnapshot.RunOutcome.NO_NEW_DATA;
    }

    private String trim(String detail) {
        if (detail == null) {
            return null;
        }
        String normalized = detail.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static final class MutableRunStatus {

        private final String category;

        private final String source;

        private final int seedUrlCount;

        private final int maxArticles;

        private final Instant startedAt;

        private final AtomicInteger requestSuccessCount = new AtomicInteger();

        private final AtomicInteger requestFailureCount = new AtomicInteger();

        private final AtomicInteger indexedDocumentCount = new AtomicInteger();

        private final AtomicInteger indexFailureCount = new AtomicInteger();

        private volatile String lastError;

        private volatile String lastIndexedDocId;

        private volatile String lastIndexedSourceUrl;

        private MutableRunStatus(String category, String source, int seedUrlCount, int maxArticles, Instant startedAt) {
            this.category = category;
            this.source = source;
            this.seedUrlCount = seedUrlCount;
            this.maxArticles = maxArticles;
            this.startedAt = startedAt;
        }

        private IngestionStatusSnapshot.CategoryRunStatus toSnapshot(IngestionStatusSnapshot.RunOutcome outcome,
                                                                     Instant finishedAt) {
            return new IngestionStatusSnapshot.CategoryRunStatus(
                    category,
                    source,
                    outcome,
                    startedAt,
                    finishedAt,
                    seedUrlCount,
                    maxArticles,
                    requestSuccessCount.get(),
                    requestFailureCount.get(),
                    indexedDocumentCount.get(),
                    indexFailureCount.get(),
                    lastError,
                    lastIndexedDocId,
                    lastIndexedSourceUrl
            );
        }
    }
}
