package cn.edu.bistu.cs.ir.controller.dto;

import cn.edu.bistu.cs.ir.ai.ProviderStatusSnapshot;
import cn.edu.bistu.cs.ir.crawler.IngestionStatusSnapshot;

import java.time.Instant;

public record ProductionStatusSnapshot(
        Instant observedAt,
        LuceneStatus lucene,
        ProviderStatusSnapshot providers,
        IngestionStatusSnapshot ingest
) {

    public record LuceneStatus(
            boolean available,
            String indexPath,
            int documentCount
    ) {
    }
}
