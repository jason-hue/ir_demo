package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.model.Article;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IngestionObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(IngestionObservabilityService.class);

    private static final int COMPLETED_RUN_RETENTION_CAP = 20;

    private static final Comparator<CompletedRunStatus> COMPLETED_RUN_EVICTION_ORDER =
            Comparator.comparing(CompletedRunStatus::finishedAt)
                    .thenComparing(CompletedRunStatus::runId);

    private static final Comparator<IngestionStatusSnapshot.CategoryRunStatus> SNAPSHOT_ENTRY_ORDER =
            Comparator.comparing(IngestionObservabilityService::isNotRunning)
                    .thenComparing(IngestionStatusSnapshot.CategoryRunStatus::startedAt, Comparator.reverseOrder())
                    .thenComparing(IngestionStatusSnapshot.CategoryRunStatus::finishedAt,
                            Comparator.nullsFirst(Comparator.reverseOrder()))
                    .thenComparing(IngestionStatusSnapshot.CategoryRunStatus::runId);

    private final Clock clock;

    private final Supplier<String> runIdGenerator;

    private final Map<String, MutableRunStatus> activeRunsByRunId = new LinkedHashMap<>();

    private final Map<String, CompletedRunStatus> completedRunsByRunId = new LinkedHashMap<>();

    private final Map<String, LinkedHashSet<String>> activeRunIdsByCategory = new HashMap<>();

    public IngestionObservabilityService() {
        this(Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    IngestionObservabilityService(Clock clock, Supplier<String> runIdGenerator) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runIdGenerator = Objects.requireNonNull(runIdGenerator, "runIdGenerator");
    }

    public synchronized String startRun(String category, String source, int seedUrlCount, int maxArticles) {
        String runId = normalizeRunId(runIdGenerator.get());
        MutableRunStatus run = new MutableRunStatus(runId, category, source, seedUrlCount, maxArticles, Instant.now(clock));
        activeRunsByRunId.put(runId, run);
        activeRunIdsByCategory.computeIfAbsent(category, ignored -> new LinkedHashSet<>()).add(runId);
        log.info("开始记录分类[{}]的抓取运行情况，runId=[{}]，seedUrlCount=[{}]，maxArticles=[{}]，source=[{}]",
                category, runId, seedUrlCount, maxArticles, source);
        return runId;
    }

    public synchronized void recordRequestSuccess(String runIdOrCategory) {
        MutableRunStatus run = resolveActiveRun(runIdOrCategory);
        if (run != null) {
            run.requestSuccessCount++;
        }
    }

    public synchronized void recordRequestFailure(String runIdOrCategory, String detail) {
        MutableRunStatus run = resolveActiveRun(runIdOrCategory);
        if (run != null) {
            run.requestFailureCount++;
            run.lastError = trim(detail);
        }
    }

    public synchronized void recordSemanticFailure(String runIdOrCategory, String detail) {
        MutableRunStatus run = resolveActiveRun(runIdOrCategory);
        if (run != null) {
            run.requestFailureCount++;
            run.lastError = trim(detail);
        }
    }

    public synchronized void recordIndexSuccess(String runIdOrCategory, Article article) {
        MutableRunStatus run = resolveActiveRun(runIdOrCategory);
        if (run != null) {
            run.indexedDocumentCount++;
            run.lastIndexedDocId = articleValue(article, "docId");
            run.lastIndexedSourceUrl = articleValue(article, "sourceUrl");
        }
    }

    public synchronized void recordIndexFailure(String runIdOrCategory, String detail, Article article) {
        MutableRunStatus run = resolveActiveRun(runIdOrCategory);
        if (run != null) {
            run.indexFailureCount++;
            run.lastError = trim(detail);
            run.lastIndexedDocId = article == null ? run.lastIndexedDocId : articleValue(article, "docId");
            run.lastIndexedSourceUrl = article == null ? run.lastIndexedSourceUrl : articleValue(article, "sourceUrl");
        }
    }

    public synchronized void markStopped(String runIdOrCategory) {
        String runId = resolveActiveRunId(runIdOrCategory);
        if (runId == null) {
            return;
        }
        MutableRunStatus run = activeRunsByRunId.remove(runId);
        if (run == null) {
            return;
        }
        removeActiveRunId(run.category, runId);

        Instant finishedAt = Instant.now(clock);
        IngestionStatusSnapshot.RunOutcome outcome = deriveOutcome(run);
        IngestionStatusSnapshot.CategoryRunStatus snapshot = run.toSnapshot(outcome, finishedAt);
        completedRunsByRunId.put(runId, new CompletedRunStatus(runId, snapshot));
        evictCompletedRunsIfNeeded();

        log.info("分类[{}]抓取结束，runId=[{}]，outcome=[{}]，indexedDocumentCount=[{}]，indexFailureCount=[{}]，requestFailureCount=[{}]",
                run.category,
                runId,
                outcome,
                snapshot.indexedDocumentCount(),
                snapshot.indexFailureCount(),
                snapshot.requestFailureCount());
    }

    public synchronized IngestionStatusSnapshot snapshot() {
        List<IngestionStatusSnapshot.CategoryRunStatus> categories = new ArrayList<>();
        for (CompletedRunStatus completedRun : completedRunsByRunId.values()) {
            categories.add(completedRun.snapshot());
        }
        for (MutableRunStatus run : activeRunsByRunId.values()) {
            categories.add(run.toSnapshot(IngestionStatusSnapshot.RunOutcome.RUNNING, null));
        }
        categories.sort(SNAPSHOT_ENTRY_ORDER);
        return new IngestionStatusSnapshot(Instant.now(clock), List.copyOf(categories));
    }

    static IngestionStatusSnapshot.RunOutcome deriveOutcomeForTest(int indexedDocumentCount,
                                                                   int requestFailureCount,
                                                                   int indexFailureCount) {
        MutableRunStatus run = new MutableRunStatus("test-run-id", "test", "test", 0, 0, Instant.now());
        run.indexedDocumentCount = indexedDocumentCount;
        run.requestFailureCount = requestFailureCount;
        run.indexFailureCount = indexFailureCount;
        return deriveOutcome(run);
    }

    static int completedRunRetentionCapForTest() {
        return COMPLETED_RUN_RETENTION_CAP;
    }

    synchronized boolean hasActiveRunIdForTest(String runId) {
        return activeRunsByRunId.containsKey(runId);
    }

    synchronized boolean hasCompletedRunIdForTest(String runId) {
        return completedRunsByRunId.containsKey(runId);
    }

    private void evictCompletedRunsIfNeeded() {
        while (completedRunsByRunId.size() > COMPLETED_RUN_RETENTION_CAP) {
            String runIdToEvict = completedRunsByRunId.values().stream()
                    .min(COMPLETED_RUN_EVICTION_ORDER)
                    .map(CompletedRunStatus::runId)
                    .orElse(null);
            if (runIdToEvict == null) {
                return;
            }
            completedRunsByRunId.remove(runIdToEvict);
        }
    }

    private MutableRunStatus resolveActiveRun(String runIdOrCategory) {
        String runId = resolveActiveRunId(runIdOrCategory);
        return runId == null ? null : activeRunsByRunId.get(runId);
    }

    private String resolveActiveRunId(String runIdOrCategory) {
        if (runIdOrCategory == null) {
            return null;
        }
        if (activeRunsByRunId.containsKey(runIdOrCategory)) {
            return runIdOrCategory;
        }
        LinkedHashSet<String> runIds = activeRunIdsByCategory.get(runIdOrCategory);
        if (runIds == null || runIds.isEmpty()) {
            return null;
        }
        String lastRunId = null;
        for (String runId : runIds) {
            lastRunId = runId;
        }
        return lastRunId;
    }

    private void removeActiveRunId(String category, String runId) {
        LinkedHashSet<String> runIds = activeRunIdsByCategory.get(category);
        if (runIds == null) {
            return;
        }
        runIds.remove(runId);
        if (runIds.isEmpty()) {
            activeRunIdsByCategory.remove(category);
        }
    }

    private static String normalizeRunId(String candidate) {
        String runId = trim(candidate);
        if (runId == null) {
            throw new IllegalStateException("runIdGenerator produced a blank runId");
        }
        return runId;
    }

    private static IngestionStatusSnapshot.RunOutcome deriveOutcome(MutableRunStatus run) {
        boolean hasIndexedDocuments = run.indexedDocumentCount > 0;
        boolean hasFailures = run.requestFailureCount > 0 || run.indexFailureCount > 0;
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

    private static boolean isNotRunning(IngestionStatusSnapshot.CategoryRunStatus status) {
        return status.outcome() != IngestionStatusSnapshot.RunOutcome.RUNNING;
    }

    private static String articleValue(Article article, String fieldName) {
        if (article == null) {
            return null;
        }
        try {
            Field field = Article.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return trim((String) field.get(article));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to read Article." + fieldName, ex);
        }
    }

    private static String trim(String detail) {
        if (detail == null) {
            return null;
        }
        String normalized = detail.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record CompletedRunStatus(String runId, IngestionStatusSnapshot.CategoryRunStatus snapshot) {

        private Instant finishedAt() {
            return snapshot.finishedAt();
        }
    }

    private static final class MutableRunStatus {

        private final String runId;

        private final String category;

        private final String source;

        private final int seedUrlCount;

        private final int maxArticles;

        private final Instant startedAt;

        private int requestSuccessCount;

        private int requestFailureCount;

        private int indexedDocumentCount;

        private int indexFailureCount;

        private String lastError;

        private String lastIndexedDocId;

        private String lastIndexedSourceUrl;

        private MutableRunStatus(String runId,
                                 String category,
                                 String source,
                                 int seedUrlCount,
                                 int maxArticles,
                                 Instant startedAt) {
            this.runId = runId;
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
                    runId,
                    outcome,
                    startedAt,
                    finishedAt,
                    seedUrlCount,
                    maxArticles,
                    requestSuccessCount,
                    requestFailureCount,
                    indexedDocumentCount,
                    indexFailureCount,
                    lastError,
                    lastIndexedDocId,
                    lastIndexedSourceUrl
            );
        }
    }
}
