package cn.edu.bistu.cs.ir.crawler;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class IngestionObservabilityServiceTest {

    @Test
    void semanticFailureCountsAsFailedInsteadOfNoNewData() {
        IngestionObservabilityService service = new IngestionObservabilityService();

        service.startRun("semantic-failure", "tencent-news", 1, 1);
        service.recordRequestSuccess("semantic-failure");
        service.recordSemanticFailure("semantic-failure", "article parse failed after HTTP success");
        service.markStopped("semantic-failure");

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
}
