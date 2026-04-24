package cn.edu.bistu.cs.ir.controller.dto;

import java.time.Instant;
import java.util.List;

public record VectorBackfillResult(
        Instant startedAt,
        Instant finishedAt,
        int scanned,
        int attempted,
        int succeeded,
        int failed,
        List<String> errorSamples) {
}
