package cn.edu.bistu.cs.ir.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "irdemo.ai.qdrant.enabled=true",
        "irdemo.ai.qdrant.host=127.0.0.1",
        "irdemo.ai.qdrant.http-port=6333",
        "irdemo.ai.qdrant.grpc-port=6334",
        "irdemo.ai.qdrant.collection-name=provider_status_it_collection",
        "irdemo.ai.provider-status.connect-timeout=250ms",
        "irdemo.ai.provider-status.read-timeout=500ms",
        "irdemo.dir.home=workspace/test-qdrant-it",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class QdrantProviderStatusIntegrationTest {

    private static final String COLLECTION_NAME = "provider_status_it_collection";

    @Autowired
    private ProviderStatusService providerStatusService;

    @Autowired
    private QdrantClient qdrantClient;

    @Test
    void qdrantStatusReflectsMissingAndExistingCollectionLifecycle() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("irdemo.qdrant.it")
                        || Boolean.parseBoolean(System.getenv("IRDEMO_QDRANT_IT")),
                "Enable this test with -Dirdemo.qdrant.it=true after starting docker-compose.local.yml qdrant.");

        deleteCollectionIfPresent();

        try {
            ProviderStatus degraded = providerStatusService.qdrantStatus();
            Assertions.assertAll(
                    () -> Assertions.assertEquals(ProviderAvailabilityState.DEGRADED, degraded.state()),
                    () -> Assertions.assertTrue(degraded.detail().contains("does not exist"))
            );

            qdrantClient.createCollectionAsync(COLLECTION_NAME,
                    VectorParams.newBuilder()
                            .setDistance(Distance.Cosine)
                            .setSize(3)
                            .build()).get();

            ProviderStatus available = providerStatusService.qdrantStatus();
            Assertions.assertAll(
                    () -> Assertions.assertEquals(ProviderAvailabilityState.AVAILABLE, available.state()),
                    () -> Assertions.assertTrue(available.detail().contains(COLLECTION_NAME))
            );
        }
        finally {
            deleteCollectionIfPresent();
        }
    }

    @AfterEach
    void cleanupCollection() throws Exception {
        if (!qdrantIntegrationEnabled()) {
            return;
        }
        deleteCollectionIfPresent();
    }

    private void deleteCollectionIfPresent() throws Exception {
        if (qdrantClient.collectionExistsAsync(COLLECTION_NAME).get()) {
            qdrantClient.deleteCollectionAsync(COLLECTION_NAME).get();
        }
    }

    private boolean qdrantIntegrationEnabled() {
        return Boolean.getBoolean("irdemo.qdrant.it")
                || Boolean.parseBoolean(System.getenv("IRDEMO_QDRANT_IT"));
    }
}
