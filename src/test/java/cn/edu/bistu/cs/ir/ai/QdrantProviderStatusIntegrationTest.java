package cn.edu.bistu.cs.ir.ai;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "irdemo.ai.qdrant.enabled=true",
        "irdemo.ai.qdrant.host=127.0.0.1",
        "irdemo.ai.qdrant.http-port=6333",
        "irdemo.ai.qdrant.grpc-port=6334",
        "irdemo.ai.provider-status.connect-timeout=250ms",
        "irdemo.ai.provider-status.read-timeout=500ms",
        "irdemo.dir.home=workspace/test-qdrant-it",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class QdrantProviderStatusIntegrationTest {

    @Autowired
    private ProviderStatusService providerStatusService;

    @Autowired
    private ObjectProvider<VectorStore> vectorStoreProvider;

    @Autowired
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Test
    void qdrantStatusTurnsAvailableWhenLocalContainerIsRunning() {
        Assumptions.assumeTrue(Boolean.getBoolean("irdemo.qdrant.it")
                        || Boolean.parseBoolean(System.getenv("IRDEMO_QDRANT_IT")),
                "Enable this test with -Dirdemo.qdrant.it=true after starting docker-compose.local.yml qdrant.");

        ProviderStatusSnapshot snapshot = providerStatusService.snapshot();

        Assertions.assertEquals(ProviderAvailabilityState.AVAILABLE, snapshot.qdrant().state());

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            Assertions.assertNull(vectorStoreProvider.getIfAvailable(),
                    "VectorStore should stay absent when no EmbeddingModel bean is available.");
            return;
        }

        Assertions.assertNotNull(vectorStoreProvider.getIfAvailable());
    }
}
