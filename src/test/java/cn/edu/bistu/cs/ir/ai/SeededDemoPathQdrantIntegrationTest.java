package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import cn.edu.bistu.cs.ir.index.ArticleIdxFields;
import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.index.LuceneSeedRunner;
import cn.edu.bistu.cs.ir.model.Article;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.support.DemoFixtureSupport;
import cn.edu.bistu.cs.ir.utils.FileUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.file.Path;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "app.seed.lucene.enabled=true",
        "app.seed.lucene.resource=classpath:fixtures/tencent/news/runtime-seed-articles.json",
        "irdemo.ai.ollama.enabled=false",
        "irdemo.ai.qdrant.enabled=true",
        "irdemo.ai.qdrant.host=127.0.0.1",
        "irdemo.ai.qdrant.http-port=6333",
        "irdemo.ai.qdrant.grpc-port=6334",
        "irdemo.ai.qdrant.collection-name=news_article_chunks",
        "irdemo.ai.provider-status.connect-timeout=250ms",
        "irdemo.ai.provider-status.read-timeout=500ms",
        "irdemo.dir.home=workspace/test-seeded-demo-qdrant",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class SeededDemoPathQdrantIntegrationTest {

    private static final String TEST_HOME = "workspace/test-seeded-demo-qdrant";

    @Autowired
    private LuceneSeedRunner luceneSeedRunner;

    @Autowired
    private QdrantClient qdrantClient;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private IdxService idxService;

    @Test
    void repeatedSeedRunsStayIdempotentForLuceneAndQdrant() throws Exception {
        Assumptions.assumeTrue(qdrantIntegrationEnabled(),
                "Enable this test with -Dirdemo.qdrant.it=true after starting Qdrant, for example via sg docker -c 'docker compose -f docker-compose.local.yml up -d qdrant'.");

        String collectionName = aiProperties.getQdrant().getCollectionName();
        Assertions.assertTrue(qdrantClient.collectionExistsAsync(collectionName).get());
        int luceneDocCountBefore = countIndexedDocs();
        long qdrantPointCountBefore = qdrantClient.countAsync(collectionName).get();

        luceneSeedRunner.run();
        luceneSeedRunner.run();

        Assertions.assertAll(
                () -> Assertions.assertEquals(3, luceneDocCountBefore),
                () -> Assertions.assertEquals(luceneDocCountBefore, countIndexedDocs()),
                () -> Assertions.assertEquals(qdrantPointCountBefore, qdrantClient.countAsync(collectionName).get()),
                () -> Assertions.assertFalse(idxService.queryByKw("新闻检索助手", 1, 10).isEmpty()),
                () -> Assertions.assertNotNull(idxService.queryByKw("新闻检索助手", 1, 10)
                        .getFirst().get(ArticleIdxFields.ID))
        );
    }

    @AfterEach
    void deleteCollection() throws Exception {
        if (!qdrantIntegrationEnabled()) {
            return;
        }
        String collectionName = aiProperties.getQdrant().getCollectionName();
        if (qdrantClient.collectionExistsAsync(collectionName).get()) {
            qdrantClient.deleteCollectionAsync(collectionName).get();
        }
    }

    @AfterAll
    static void cleanWorkspace() {
        FileUtils.deleteSubDirs(TEST_HOME);
    }

    private boolean qdrantIntegrationEnabled() {
        return Boolean.getBoolean("irdemo.qdrant.it")
                || Boolean.parseBoolean(System.getenv("IRDEMO_QDRANT_IT"));
    }

    private int countIndexedDocs() throws Exception {
        try (FSDirectory directory = FSDirectory.open(Path.of(TEST_HOME, "idx"));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }

    @TestConfiguration
    static class DeterministicSeedVectorConfiguration {

        @Bean
        @Primary
        ProviderStatusService providerStatusService(AiProperties aiProperties, ObjectMapper objectMapper) {
            return new ProviderStatusService(aiProperties, objectMapper) {
                @Override
                public ProviderStatusSnapshot snapshot() {
                    return DemoFixtureSupport.availableSnapshot();
                }
            };
        }

        @Bean
        @Primary
        ArticleEmbeddingService articleEmbeddingService(ArticleChunkingService articleChunkingService,
                                                       ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModelProvider,
                                                       AiFallbackService aiFallbackService,
                                                       AiProperties aiProperties) {
            return new ArticleEmbeddingService(articleChunkingService, embeddingModelProvider, aiFallbackService, aiProperties) {
                @Override
                public List<ArticleChunkEmbedding> generateEmbeddings(Article article) {
                    return articleChunkingService.chunk(article).stream()
                            .map(this::toEmbedding)
                            .toList();
                }

                private ArticleChunkEmbedding toEmbedding(ArticleChunkMetadata metadata) {
                    metadata.setEmbeddingModel("seed-demo-test");
                    float[] vector = new float[]{
                            metadata.getChunkIndex() + 1.0f,
                            metadata.getCharCount() / 100.0f,
                            (Math.abs(metadata.getChunkId().hashCode()) % 1000) / 100.0f
                    };
                    return new ArticleChunkEmbedding(metadata, vector);
                }
            };
        }
    }
}
