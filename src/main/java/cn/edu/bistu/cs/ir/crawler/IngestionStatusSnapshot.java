package cn.edu.bistu.cs.ir.crawler;

import java.time.Instant;
import java.util.List;

public record IngestionStatusSnapshot(
        Instant observedAt,
        List<CategoryRunStatus> categories
) {

    public enum RunOutcome {
        RUNNING,
        SUCCESS,
        NO_NEW_DATA,
        FAILED,
        PARTIAL_SUCCESS
    }

    public record CategoryRunStatus(
            String category,
            String source,
            RunOutcome outcome,
            Instant startedAt,
            Instant finishedAt,
            int seedUrlCount,
            int maxArticles,
            int requestSuccessCount,
            int requestFailureCount,
            int indexedDocumentCount,
            int indexFailureCount,
            String lastError,
            String lastIndexedDocId,
            String lastIndexedSourceUrl
    ) {
    }
}
