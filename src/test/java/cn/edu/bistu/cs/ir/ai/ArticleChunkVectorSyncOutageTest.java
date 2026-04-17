package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.index.ArticleIdxFields;
import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.model.Blog;
import cn.edu.bistu.cs.ir.utils.FileUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import us.codecraft.webmagic.selector.Html;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "irdemo.ai.ollama.enabled=false",
        "irdemo.ai.qdrant.enabled=true",
        "irdemo.ai.qdrant.host=127.0.0.1",
        "irdemo.ai.qdrant.http-port=1",
        "irdemo.ai.qdrant.grpc-port=1",
        "irdemo.ai.qdrant.collection-name=news_article_chunks",
        "irdemo.ai.provider-status.connect-timeout=100ms",
        "irdemo.ai.provider-status.read-timeout=100ms",
        "irdemo.dir.home=workspace/test-article-chunk-vector-outage",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class ArticleChunkVectorSyncOutageTest {

    private static final String ARTICLE_FIXTURE = "fixtures/tencent/news/article-page.html";

    private static final String TEST_HOME = "workspace/test-article-chunk-vector-outage";

    @Autowired
    private ArticleChunkVectorSyncService articleChunkVectorSyncService;

    @Autowired
    private ArticleChunkingService articleChunkingService;

    @Autowired
    private IdxService idxService;

    @Test
    void qdrantOutageDoesNotBreakLexicalIndexing() throws Exception {
        Blog article = buildLongFixtureArticle();
        List<ArticleChunkEmbedding> embeddings = buildEmbeddings(article);

        Document document = new Document();
        document.add(new StringField(ArticleIdxFields.ID, article.getDocId(), Field.Store.YES));
        document.add(new TextField(ArticleIdxFields.TITLE, article.getTitle(), Field.Store.YES));
        document.add(new TextField(ArticleIdxFields.CONTENT, article.getBody(), Field.Store.YES));
        boolean indexed = idxService.addDocument(ArticleIdxFields.ID, article.getDocId(), document);

        Assertions.assertDoesNotThrow(() -> articleChunkVectorSyncService.syncEmbeddings(article, embeddings));

        List<Document> hits = idxService.queryByKw("新闻检索助手");
        Assertions.assertAll(
                () -> Assertions.assertTrue(indexed),
                () -> Assertions.assertFalse(hits.isEmpty()),
                () -> Assertions.assertEquals(article.getDocId(), hits.getFirst().get(ArticleIdxFields.ID))
        );
    }

    @AfterAll
    static void cleanWorkspace() {
        FileUtils.deleteSubDirs(TEST_HOME);
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
        String rawHtml = Files.readString(new ClassPathResource(ARTICLE_FIXTURE).getFile().toPath());
        Html html = new Html(rawHtml);
        String baseBody = String.join("\n", html.xpath("//div[contains(@class,'content-article')]//p/allText()").all());

        Blog article = new Blog();
        article.setSource("tencent-news");
        article.setSourceUrl("https://news.qq.com/rain/a/20240318A01AB000");
        article.setTitle("腾讯新闻推出新闻检索助手试点");
        article.setBody((baseBody + "\n").repeat(20));
        article.setPublishTime(Instant.parse("2024-03-18T09:30:00Z"));
        article.setCrawlTime(Instant.parse("2024-03-18T10:00:00Z"));
        article.setSection("tech");
        article.setAuthor("腾讯教育");
        article.setByline("腾讯教育");
        article.ensureDocId();
        return article;
    }
}
