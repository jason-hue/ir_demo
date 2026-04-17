package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.controller.QueryController;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "irdemo.ai.ollama.base-url=http://127.0.0.1:9",
        "irdemo.ai.ollama.warmup-enabled=false",
        "irdemo.ai.qdrant.enabled=true",
        "irdemo.ai.qdrant.host=127.0.0.1",
        "irdemo.ai.qdrant.http-port=1",
        "irdemo.ai.qdrant.grpc-port=1",
        "irdemo.ai.provider-status.connect-timeout=100ms",
        "irdemo.ai.provider-status.read-timeout=100ms",
        "irdemo.dir.home=workspace/test-provider-status",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class ProviderStatusServiceDegradedTest {

    @Autowired
    private ProviderStatusService providerStatusService;

    @Autowired
    private AiFallbackService aiFallbackService;

    @Autowired
    private QueryController queryController;

    @Test
    void startupStaysLexicalWhenAiProvidersAreUnavailable() {
        ProviderStatusSnapshot snapshot = providerStatusService.snapshot();

        Assertions.assertEquals(ProviderAvailabilityState.UNAVAILABLE, snapshot.ollamaChat().state());
        Assertions.assertEquals(ProviderAvailabilityState.UNAVAILABLE, snapshot.ollamaEmbedding().state());
        Assertions.assertEquals(ProviderAvailabilityState.UNAVAILABLE, snapshot.qdrant().state());
        Assertions.assertEquals(AiFallbackMode.LEXICAL_ONLY, snapshot.fallbackMode());
        Assertions.assertTrue(snapshot.lexicalAvailable());
        Assertions.assertTrue(aiFallbackService.isLexicalOnlyMode());
        Assertions.assertNotNull(queryController);
    }
}
