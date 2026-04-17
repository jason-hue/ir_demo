package cn.edu.bistu.cs.ir.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import us.codecraft.webmagic.selector.Html;

import java.nio.file.Files;
import java.time.Instant;

public class ArticleModelTest {

    private static final String DETAIL_FIXTURE = "fixtures/cnblogs/tencent-cloud-native/detail-page.html";
    private static final String FIXTURE_URL = "https://www.cnblogs.com/tencent-cloud-native/p/18700001.html";

    @Test
    void deterministicDocIdAndChunkIdStayStableForSameFixture() throws Exception {
        Html detailHtml = new Html(Files.readString(new ClassPathResource(DETAIL_FIXTURE).getFile().toPath()));
        String title = detailHtml.xpath("//div[@class='post']/h1[@class='postTitle']//span/text()").get();
        String publishTime = detailHtml.xpath("//div[@class='postDesc']/span[@id='post-date']/text()").get();
        String body = detailHtml.xpath("//div[@class='post']//div[@id='cnblogs_post_body']/allText()").get();

        Article first = buildFixtureArticle(title, publishTime, body);
        Article second = buildFixtureArticle(title, publishTime, body);

        ArticleChunkMetadata firstChunk = new ArticleChunkMetadata();
        firstChunk.setDocId(first.getDocId());
        firstChunk.setChunkIndex(0);
        firstChunk.setChunkText(body);
        firstChunk.setCharCount(body.length());
        firstChunk.setEmbeddingModel("nomic-embed-text");
        firstChunk.deriveChunkId();

        ArticleChunkMetadata secondChunk = new ArticleChunkMetadata();
        secondChunk.setDocId(second.getDocId());
        secondChunk.setChunkIndex(0);
        secondChunk.setChunkText(body);
        secondChunk.setCharCount(body.length());
        secondChunk.setEmbeddingModel("nomic-embed-text");
        secondChunk.deriveChunkId();

        Assertions.assertAll(
                () -> Assertions.assertEquals(first.getDocId(), second.getDocId()),
                () -> Assertions.assertEquals(firstChunk.getChunkId(), secondChunk.getChunkId()),
                () -> Assertions.assertEquals(first.getDocId() + "#0", firstChunk.getChunkId())
        );
    }

    @Test
    void tencentCanonicalUrlDefinesStableIdentityAcrossUrlVariants() {
        Article first = buildTencentArticle("http://new.qq.com/rain/a/20240318a01ab000/?from=fixture");
        Article second = buildTencentArticle("https://news.qq.com/rain/a/20240318A01AB000#fragment");

        Assertions.assertAll(
                () -> Assertions.assertEquals("https://news.qq.com/rain/a/20240318A01AB000", first.getSourceUrl()),
                () -> Assertions.assertEquals(first.getSourceUrl(), second.getSourceUrl()),
                () -> Assertions.assertEquals(first.getDocId(), second.getDocId()),
                () -> Assertions.assertEquals(ArticleIds.generateDocId(first.getSourceUrl(), first.getSource(), first.getTitle(), first.getPublishTime()), first.getDocId())
        );
    }

    private Article buildFixtureArticle(String title, String publishTime, String body) {
        Article article = new Article();
        article.setSource("cnblogs");
        article.setSourceUrl(FIXTURE_URL);
        article.setTitle(title);
        article.setBody(body);
        article.setPublishTime(Instant.parse("2024-03-18T09:30:00Z"));
        article.setCrawlTime(Instant.parse("2024-03-18T10:00:00Z"));
        article.setSection("tencent-cloud-native");
        article.setAuthor("tencent-cloud-native");
        article.setByline("tencent-cloud-native");
        article.ensureDocId();
        Assertions.assertEquals("2024-03-18 09:30", publishTime);
        return article;
    }

    private Article buildTencentArticle(String sourceUrl) {
        Article article = new Article();
        article.setSource("tencent-news");
        article.setSourceUrl(sourceUrl);
        article.setTitle("腾讯新闻推出新闻检索助手试点");
        article.setBody("北京信息科技大学在课堂上试点新闻检索助手。");
        article.setPublishTime(Instant.parse("2024-03-18T09:30:00Z"));
        article.ensureCanonicalIdentity();
        return article;
    }
}
