package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.model.Article;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

class IngestionObservabilityServiceTest {

    @Test
    void semanticFailureCountsAsFailedInsteadOfNoNewData() {
        IngestionObservabilityService service = new IngestionObservabilityService();

        String runId = service.startRun("semantic-failure", "tencent-news", 1, 1);
        service.recordRequestSuccess(runId);
        service.recordSemanticFailure(runId, "article parse failed after HTTP success");
        service.markStopped(runId);

        IngestionStatusSnapshot.CategoryRunStatus status = service.snapshot().categories().stream()
                .filter(category -> "semantic-failure".equals(category.category()))
                .findFirst()
                .orElseThrow();

        Assertions.assertAll(
                () -> Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.FAILED, status.outcome()),
                () -> Assertions.assertEquals(1, status.requestSuccessCount()),
                () -> Assertions.assertEquals(1, status.requestFailureCount()),
                () -> Assertions.assertEquals(0, status.indexedDocumentCount()),
                () -> Assertions.assertTrue(status.lastError().contains("article parse failed"))
        );
    }

    @Test
    void twoSameCategoryRunsWithDistinctSourcesKeepIsolatedCountersAndOutcomes() {
        IngestionObservabilityService service = new IngestionObservabilityService();

        String firstRunId = service.startRun("adhoc", "source-a", 1, 10);
        String secondRunId = service.startRun("adhoc", "source-b", 2, 20);

        service.recordRequestSuccess(firstRunId);
        service.recordIndexSuccess(firstRunId, article("doc-a", "https://example.com/a"));
        service.markStopped(firstRunId);

        service.recordRequestFailure(secondRunId, "source-b failed");
        service.markStopped(secondRunId);

        Map<String, IngestionStatusSnapshot.CategoryRunStatus> statusesBySource = statusesBySource(
                service.snapshot(),
                "adhoc"
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, statusesBySource.size()),
                () -> Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.SUCCESS,
                        statusesBySource.get("source-a").outcome()),
                () -> Assertions.assertEquals(1, statusesBySource.get("source-a").requestSuccessCount()),
                () -> Assertions.assertEquals(1, statusesBySource.get("source-a").indexedDocumentCount()),
                () -> Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.FAILED,
                        statusesBySource.get("source-b").outcome()),
                () -> Assertions.assertEquals(1, statusesBySource.get("source-b").requestFailureCount()),
                () -> Assertions.assertEquals(0, statusesBySource.get("source-b").indexedDocumentCount())
        );
    }

    @Test
    void finishingOneSameCategoryRunKeepsTheOtherRunning() {
        IngestionObservabilityService service = new IngestionObservabilityService();

        String runningRunId = service.startRun("adhoc", "source-a", 1, 10);
        String finishedRunId = service.startRun("adhoc", "source-b", 1, 10);

        service.recordRequestSuccess(runningRunId);
        service.markStopped(finishedRunId);

        Map<String, IngestionStatusSnapshot.CategoryRunStatus> statusesBySource = statusesBySource(
                service.snapshot(),
                "adhoc"
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, statusesBySource.size()),
                () -> Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.RUNNING,
                        statusesBySource.get("source-a").outcome()),
                () -> Assertions.assertNull(statusesBySource.get("source-a").finishedAt()),
                () -> Assertions.assertEquals(1, statusesBySource.get("source-a").requestSuccessCount()),
                () -> Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.NO_NEW_DATA,
                        statusesBySource.get("source-b").outcome()),
                () -> Assertions.assertNotNull(statusesBySource.get("source-b").finishedAt()),
                () -> Assertions.assertTrue(service.hasActiveRunIdForTest(runningRunId)),
                () -> Assertions.assertTrue(service.hasCompletedRunIdForTest(finishedRunId))
        );
    }

    @Test
    void stableRunIdMovesFromRunningToCompletedForSingleLifecycle() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-18T00:00:00Z"));
        IngestionObservabilityService service = new IngestionObservabilityService(clock, sequenceSupplier("run-001"));

        String runId = service.startRun("adhoc", "source-a", 1, 10);
        service.recordRequestSuccess(runId);

        Assertions.assertAll(
                () -> Assertions.assertEquals("run-001", runId),
                () -> Assertions.assertTrue(service.hasActiveRunIdForTest(runId)),
                () -> Assertions.assertFalse(service.hasCompletedRunIdForTest(runId)),
                () -> Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.RUNNING,
                        statusesBySource(service.snapshot(), "adhoc").get("source-a").outcome())
        );

        clock.setInstant(Instant.parse("2026-04-18T00:05:00Z"));
        service.recordIndexSuccess(runId, article("doc-1", "https://example.com/doc-1"));
        service.markStopped(runId);

        IngestionStatusSnapshot.CategoryRunStatus finishedStatus = statusesBySource(service.snapshot(), "adhoc").get("source-a");

        Assertions.assertAll(
                () -> Assertions.assertFalse(service.hasActiveRunIdForTest(runId)),
                () -> Assertions.assertTrue(service.hasCompletedRunIdForTest(runId)),
                () -> Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.SUCCESS, finishedStatus.outcome()),
                () -> Assertions.assertEquals(Instant.parse("2026-04-18T00:00:00Z"), finishedStatus.startedAt()),
                () -> Assertions.assertEquals(Instant.parse("2026-04-18T00:05:00Z"), finishedStatus.finishedAt()),
                () -> Assertions.assertEquals(1, finishedStatus.requestSuccessCount()),
                () -> Assertions.assertEquals(1, finishedStatus.indexedDocumentCount())
        );
    }

    @Test
    void completedRunRetentionEvictsOldestFinishedRunThenRunIdTieBreak() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-18T00:00:00Z"));
        int retentionCap = IngestionObservabilityService.completedRunRetentionCapForTest();
        List<String> runIds = retentionRunIds(retentionCap + 1);
        IngestionObservabilityService service = new IngestionObservabilityService(clock, sequenceSupplier(runIds));

        for (String runId : runIds) {
            String startedRunId = service.startRun("adhoc", runId, 1, 1);
            Assertions.assertEquals(runId, startedRunId);
            service.markStopped(runId);
        }

        Map<String, IngestionStatusSnapshot.CategoryRunStatus> statusesBySource = statusesBySource(service.snapshot(), "adhoc");

        Assertions.assertAll(
                () -> Assertions.assertEquals(retentionCap, statusesBySource.size()),
                () -> Assertions.assertFalse(service.hasCompletedRunIdForTest("run-a")),
                () -> Assertions.assertTrue(service.hasCompletedRunIdForTest("run-z")),
                () -> Assertions.assertFalse(statusesBySource.containsKey("run-a")),
                () -> Assertions.assertTrue(statusesBySource.containsKey("run-z"))
        );
    }

    @Test
    void deriveOutcomeMarksNoNewDataWhenRunStopsWithoutFailuresOrIndexedDocs() {
        Assertions.assertEquals(
                IngestionStatusSnapshot.RunOutcome.NO_NEW_DATA,
                IngestionObservabilityService.deriveOutcomeForTest(0, 0, 0));
    }

    @Test
    void deriveOutcomeMarksFailedWhenCrawlerSawFailuresAndNoIndexedDocs() {
        Assertions.assertEquals(
                IngestionStatusSnapshot.RunOutcome.FAILED,
                IngestionObservabilityService.deriveOutcomeForTest(0, 1, 0));
    }

    @Test
    void deriveOutcomeMarksPartialSuccessWhenSomeDocsWereIndexedBeforeFailures() {
        Assertions.assertEquals(
                IngestionStatusSnapshot.RunOutcome.PARTIAL_SUCCESS,
                IngestionObservabilityService.deriveOutcomeForTest(2, 0, 1));
    }

    private static Article article(String docId, String sourceUrl) {
        Article article = new Article();
        setArticleField(article, "docId", docId);
        setArticleField(article, "sourceUrl", sourceUrl);
        return article;
    }

    private static void setArticleField(Article article, String fieldName, String value) {
        try {
            Field field = Article.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(article, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to set Article." + fieldName, ex);
        }
    }

    private static Map<String, IngestionStatusSnapshot.CategoryRunStatus> statusesBySource(
            IngestionStatusSnapshot snapshot,
            String category
    ) {
        Map<String, IngestionStatusSnapshot.CategoryRunStatus> statuses = new HashMap<>();
        snapshot.categories().stream()
                .filter(status -> category.equals(status.category()))
                .forEach(status -> statuses.put(status.source(), status));
        return statuses;
    }

    private static Supplier<String> sequenceSupplier(String... runIds) {
        return sequenceSupplier(List.of(runIds));
    }

    private static Supplier<String> sequenceSupplier(List<String> runIds) {
        AtomicInteger index = new AtomicInteger();
        return () -> {
            int currentIndex = index.getAndIncrement();
            if (currentIndex >= runIds.size()) {
                throw new IllegalStateException("No runId prepared for index " + currentIndex);
            }
            return runIds.get(currentIndex);
        };
    }

    private static List<String> retentionRunIds(int count) {
        List<String> runIds = new ArrayList<>();
        runIds.add("run-z");
        if (count > 1) {
            runIds.add("run-a");
        }
        int nextLetter = 'b';
        while (runIds.size() < count && nextLetter <= 'y') {
            runIds.add("run-" + (char) nextLetter++);
        }
        int suffix = 0;
        while (runIds.size() < count) {
            runIds.add("run-extra-" + suffix++);
        }
        return runIds;
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }
    }
}
