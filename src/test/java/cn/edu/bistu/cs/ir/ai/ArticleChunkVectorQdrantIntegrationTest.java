package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.model.Blog;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import us.codecraft.webmagic.selector.Html;

import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "irdemo.ai.ollama.enabled=false",
        "irdemo.ai.qdrant.enabled=true",
        "irdemo.ai.qdrant.host=127.0.0.1",
        "irdemo.ai.qdrant.http-port=6333",
        "irdemo.ai.qdrant.grpc-port=6334",
        "irdemo.ai.qdrant.collection-name=news_article_chunks",
        "irdemo.ai.provider-status.connect-timeout=250ms",
        "irdemo.ai.provider-status.read-timeout=500ms",
        "irdemo.dir.home=workspace/test-article-chunk-qdrant-it",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class ArticleChunkVectorQdrantIntegrationTest {

    private static final String ARTICLE_FIXTURE = "fixtures/tencent/news/article-page.html";

    @Autowired
    private ArticleChunkVectorSyncService articleChunkVectorSyncService;

    @Autowired
    private ArticleChunkingService articleChunkingService;

    @Autowired
    private QdrantClient qdrantClient;

    @Autowired
    private AiProperties aiProperties;

    @BeforeEach
    void resetCollectionBeforeTest() throws Exception {
        if (!qdrantIntegrationEnabled()) {
            return;
        }
        String collectionName = aiProperties.getQdrant().getCollectionName();
        if (qdrantClient.collectionExistsAsync(collectionName).get()) {
            qdrantClient.deleteCollectionAsync(collectionName).get();
        }
    }

    @Test
    void syncUsesSingleCollectionAndStaysIdempotentForDuplicateIngest() throws Exception {
        Assumptions.assumeTrue(qdrantIntegrationEnabled(),
                "Enable this test with -Dirdemo.qdrant.it=true after starting docker-compose.local.yml qdrant.");

        Blog article = buildLongFixtureArticle();
        List<ArticleChunkEmbedding> embeddings = buildEmbeddings(article);

        articleChunkVectorSyncService.syncEmbeddings(article, embeddings);
        articleChunkVectorSyncService.syncEmbeddings(article, embeddings);

        long pointCount = qdrantClient.countAsync(aiProperties.getQdrant().getCollectionName()).get();
        ScrollResponse scroll = qdrantClient.scrollAsync(ScrollPoints.newBuilder()
                .setCollectionName(aiProperties.getQdrant().getCollectionName())
                .setLimit(embeddings.size() + 2)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .build()).get();

        Assertions.assertEquals("news_article_chunks", aiProperties.getQdrant().getCollectionName());
        Assertions.assertEquals(embeddings.size(), pointCount);
        Assertions.assertFalse(scroll.getResultList().isEmpty());

        RetrievedPoint point = scroll.getResultList().getFirst();
        Set<String> payloadKeys = point.getPayloadMap().keySet();
        Assertions.assertTrue(payloadKeys.containsAll(Set.of(
                "docId",
                "chunkId",
                "sourceUrl",
                "title",
                "publishTime",
                "source",
                "section",
                "chunkIndex"
        )));
        Assertions.assertEquals("tech", stringPayload(point, "section"));
    }

    @Test
    void changedContentRewriteDeletesOnlyOldChunksForSameDocId() throws Exception {
        Assumptions.assumeTrue(qdrantIntegrationEnabled(),
                "Enable this test with -Dirdemo.qdrant.it=true after starting docker-compose.local.yml qdrant.");

        Blog firstVersion = buildFixtureArticle("https://news.qq.com/rain/a/20240318A01AB000",
                "腾讯新闻推出新闻检索助手试点",
                Instant.parse("2024-03-18T09:30:00Z"),
                20,
                "\n第一版内容。",
                Instant.parse("2024-03-18T10:00:00Z"));
        Blog rewrittenVersion = buildFixtureArticle("https://news.qq.com/rain/a/20240318A01AB000?from=feed",
                "腾讯新闻推出新闻检索助手试点",
                Instant.parse("2024-03-18T09:30:00Z"),
                5,
                "\n改写后的短版本，只保留摘要。",
                Instant.parse("2024-03-18T10:30:00Z"));
        Blog siblingArticle = buildFixtureArticle("https://news.qq.com/rain/a/20240319A02CD000",
                "腾讯新闻发布新闻检索助手第二批试点",
                Instant.parse("2024-03-19T09:30:00Z"),
                8,
                "\n第二篇文章内容。",
                Instant.parse("2024-03-19T10:00:00Z"));

        List<ArticleChunkEmbedding> firstEmbeddings = buildEmbeddings(firstVersion);
        List<ArticleChunkEmbedding> rewrittenEmbeddings = buildEmbeddings(rewrittenVersion);
        List<ArticleChunkEmbedding> siblingEmbeddings = buildEmbeddings(siblingArticle);

        Assertions.assertTrue(firstEmbeddings.size() > rewrittenEmbeddings.size(),
                "测试前提失败：改写后的文章必须产生更少的分块。");

        articleChunkVectorSyncService.syncEmbeddings(firstVersion, firstEmbeddings);
        articleChunkVectorSyncService.syncEmbeddings(siblingArticle, siblingEmbeddings);
        articleChunkVectorSyncService.syncEmbeddings(rewrittenVersion, rewrittenEmbeddings);

        ScrollResponse scroll = qdrantClient.scrollAsync(ScrollPoints.newBuilder()
                .setCollectionName(aiProperties.getQdrant().getCollectionName())
                .setLimit(firstEmbeddings.size() + rewrittenEmbeddings.size() + siblingEmbeddings.size() + 5)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .build()).get();

        Map<String, Set<String>> chunkIdsByDocId = new HashMap<>();
        for (RetrievedPoint point : scroll.getResultList()) {
            String docId = stringPayload(point, "docId");
            String chunkId = stringPayload(point, "chunkId");
            chunkIdsByDocId.computeIfAbsent(docId, ignored -> new java.util.LinkedHashSet<>()).add(chunkId);
        }

        Set<String> rewrittenChunkIds = rewrittenEmbeddings.stream()
                .map(embedding -> embedding.metadata().getChunkId())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> firstVersionChunkIds = firstEmbeddings.stream()
                .map(embedding -> embedding.metadata().getChunkId())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> siblingChunkIds = siblingEmbeddings.stream()
                .map(embedding -> embedding.metadata().getChunkId())
                .collect(java.util.stream.Collectors.toSet());

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, chunkIdsByDocId.size()),
                () -> Assertions.assertEquals(rewrittenVersion.getDocId(), firstVersion.getDocId()),
                () -> Assertions.assertEquals(rewrittenChunkIds, chunkIdsByDocId.get(rewrittenVersion.getDocId())),
                () -> Assertions.assertNotEquals(firstVersionChunkIds, rewrittenChunkIds),
                () -> Assertions.assertEquals(siblingChunkIds, chunkIdsByDocId.get(siblingArticle.getDocId())),
                () -> Assertions.assertEquals(rewrittenEmbeddings.size() + siblingEmbeddings.size(),
                        scroll.getResultList().size())
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

    private boolean qdrantIntegrationEnabled() {
        return Boolean.getBoolean("irdemo.qdrant.it")
                || Boolean.parseBoolean(System.getenv("IRDEMO_QDRANT_IT"));
    }

    private List<ArticleChunkEmbedding> buildEmbeddings(Blog article) {
        return articleChunkingService.chunk(article).stream()
                .map(this::toEmbedding)
                .toList();
    }

    private ArticleChunkEmbedding toEmbedding(ArticleChunkMetadata metadata) {
        metadata.setEmbeddingModel("test-embed");
        float[] vector = new float[]{
                metadata.getChunkIndex() + 1.0f,
                metadata.getCharCount() / 100.0f,
                (metadata.getChunkIndex() + metadata.getCharCount()) / 10.0f
        };
        return new ArticleChunkEmbedding(metadata, vector);
    }

    private Blog buildLongFixtureArticle() throws Exception {
        return buildFixtureArticle("https://news.qq.com/rain/a/20240318A01AB000",
                "腾讯新闻推出新闻检索助手试点",
                Instant.parse("2024-03-18T09:30:00Z"),
                20,
                "",
                Instant.parse("2024-03-18T10:00:00Z"));
    }

    private Blog buildFixtureArticle(String sourceUrl,
                                     String title,
                                     Instant publishTime,
                                     int repeatCount,
                                     String suffix,
                                     Instant crawlTime) throws Exception {
        String rawHtml = Files.readString(new ClassPathResource(ARTICLE_FIXTURE).getFile().toPath());
        Html html = new Html(rawHtml);
        String baseBody = String.join("\n", html.xpath("//div[contains(@class,'content-article')]//p/allText()").all());

        Blog article = new Blog();
        article.setSource("tencent-news");
        article.setSourceUrl(sourceUrl);
        article.setTitle(title);
        article.setBody((baseBody + "\n").repeat(repeatCount) + suffix);
        article.setPublishTime(publishTime);
        article.setCrawlTime(crawlTime);
        article.setSection("tech");
        article.setAuthor("腾讯教育");
        article.setByline("腾讯教育");
        article.ensureDocId();
        return article;
    }

    private String stringPayload(RetrievedPoint point, String key) {
        Value value = point.getPayloadMap().get(key);
        return value == null ? null : value.getStringValue();
    }
}
