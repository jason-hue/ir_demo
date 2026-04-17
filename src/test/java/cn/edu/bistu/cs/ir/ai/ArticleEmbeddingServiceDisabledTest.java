package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.index.ArticleIdxFields;
import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.model.Blog;
import cn.edu.bistu.cs.ir.utils.FileUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "irdemo.ai.ollama.enabled=false",
        "irdemo.ai.qdrant.enabled=false",
        "irdemo.ai.provider-status.connect-timeout=100ms",
        "irdemo.ai.provider-status.read-timeout=100ms",
        "irdemo.dir.home=workspace/test-article-embedding-disabled",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class ArticleEmbeddingServiceDisabledTest {

    private static final String TEST_HOME = "workspace/test-article-embedding-disabled";

    @Autowired
    private ArticleEmbeddingService articleEmbeddingService;

    @Autowired
    private IdxService idxService;

    @Autowired
    private AtomicInteger embeddingCalls;

    @Test
    void lexicalIndexingStillWorksWhenEmbeddingGenerationIsDisabled() throws Exception {
        Blog article = new Blog();
        article.setSource("tencent-news");
        article.setSourceUrl("https://news.qq.com/rain/a/20240318A01AB000");
        article.setTitle("腾讯新闻推出新闻检索助手试点");
        article.setBody("北京信息科技大学在课堂上试点新闻检索助手，帮助学生整理新闻语料。".repeat(20));
        article.setPublishTime(Instant.parse("2024-03-18T09:30:00Z"));
        article.setCrawlTime(Instant.parse("2024-03-18T10:00:00Z"));
        article.setSection("tech");
        article.setAuthor("腾讯教育");
        article.setByline("腾讯教育");
        article.ensureDocId();

        List<ArticleChunkEmbedding> embeddings = articleEmbeddingService.generateEmbeddings(article);
        Document document = new Document();
        document.add(new StringField(ArticleIdxFields.ID, article.getDocId(), Field.Store.YES));
        document.add(new TextField(ArticleIdxFields.TITLE, article.getTitle(), Field.Store.YES));
        document.add(new TextField(ArticleIdxFields.CONTENT, article.getBody(), Field.Store.YES));

        boolean indexed = idxService.addDocument(ArticleIdxFields.ID, article.getDocId(), document);
        List<Document> hits = idxService.queryByKw("新闻检索助手");

        Assertions.assertAll(
                () -> Assertions.assertTrue(embeddings.isEmpty()),
                () -> Assertions.assertEquals(0, embeddingCalls.get()),
                () -> Assertions.assertTrue(indexed),
                () -> Assertions.assertFalse(hits.isEmpty()),
                () -> Assertions.assertEquals(article.getDocId(), hits.getFirst().get(ArticleIdxFields.ID))
        );
    }

    @AfterAll
    static void cleanWorkspace() {
        FileUtils.deleteSubDirs(TEST_HOME);
    }

    @TestConfiguration
    static class DisabledEmbeddingTestConfiguration {

        @Bean
        AtomicInteger embeddingCalls() {
            return new AtomicInteger();
        }

        @Bean
        EmbeddingModel embeddingModel(AtomicInteger embeddingCalls) {
            return new EmbeddingModel() {
                @Override
                public EmbeddingResponse call(EmbeddingRequest request) {
                    embeddingCalls.incrementAndGet();
                    throw new AssertionError("EmbeddingModel should not be called in lexical-only mode");
                }

                @Override
                public float[] embed(org.springframework.ai.document.Document document) {
                    embeddingCalls.incrementAndGet();
                    throw new AssertionError("EmbeddingModel should not be called in lexical-only mode");
                }
            };
        }
    }
}
