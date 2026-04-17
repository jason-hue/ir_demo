package cn.edu.bistu.cs.ir.controller;

import cn.edu.bistu.cs.ir.ai.ProviderStatusService;
import cn.edu.bistu.cs.ir.config.Config;
import cn.edu.bistu.cs.ir.controller.dto.ProductionStatusSnapshot;
import cn.edu.bistu.cs.ir.crawler.CrawlerService;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import cn.edu.bistu.cs.ir.index.IdxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/status")
public class StatusController {

    private final IdxService idxService;

    private final Config config;

    private final ProviderStatusService providerStatusService;

    private final CrawlerService crawlerService;

    public StatusController(@Autowired IdxService idxService,
                            @Autowired Config config,
                            @Autowired ProviderStatusService providerStatusService,
                            @Autowired CrawlerService crawlerService) {
        this.idxService = idxService;
        this.config = config;
        this.providerStatusService = providerStatusService;
        this.crawlerService = crawlerService;
    }

    @GetMapping(value = "/production", produces = "application/json;charset=UTF-8")
    public QueryResponse<ProductionStatusSnapshot> productionStatus() {
        ProductionStatusSnapshot snapshot = new ProductionStatusSnapshot(
                Instant.now(),
                new ProductionStatusSnapshot.LuceneStatus(
                        idxService.isAvailable(),
                        config.getIdx(),
                        idxService.documentCount()),
                providerStatusService.snapshot(),
                crawlerService.getIngestionStatusSnapshot()
        );
        return QueryResponse.genSucc("生产观测状态已获取", snapshot);
    }
}
