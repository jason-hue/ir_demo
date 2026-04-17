package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.model.ArticleIds;
import cn.edu.bistu.cs.ir.model.Blog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import us.codecraft.webmagic.Site;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public class TencentNewsCrawlerFixtureTest {

    private static final String LIST_FIXTURE = "fixtures/tencent/news/list-page.html";
    private static final String ARTICLE_FIXTURE = "fixtures/tencent/news/article-page.html";
    private static final String MALFORMED_FIXTURE = "fixtures/tencent/news/malformed-article-page.html";

    private final TencentNewsCrawler crawler = new TencentNewsCrawler(Site.me(), "tencent-news", 2);

    @Test
    void listFixtureExtractsDistinctNormalizedArticleUrls() throws Exception {
        List<String> urls = crawler.extractArticleUrls(readFixture(LIST_FIXTURE));

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, urls.size()),
                () -> Assertions.assertEquals("https://news.qq.com/rain/a/20240318A01AB000", urls.get(0)),
                () -> Assertions.assertEquals("https://news.qq.com/rain/a/20240318A01CD000", urls.get(1))
        );
    }

    @Test
    void articleFixtureBuildsCanonicalArticleFields() throws Exception {
        Instant crawlTime = Instant.parse("2024-03-18T10:00:00Z");
        String rawHtml = readFixture(ARTICLE_FIXTURE);

        Blog first = crawler.parseArticle("http://new.qq.com/rain/a/20240318a01ab000/?from=fixture", rawHtml, crawlTime);
        Blog second = crawler.parseArticle("https://news.qq.com/rain/a/20240318A01AB000#fragment", rawHtml, crawlTime);

        Assertions.assertNotNull(first);
        Assertions.assertNotNull(second);
        Assertions.assertAll(
                () -> Assertions.assertEquals("腾讯新闻推出新闻检索助手试点", first.getTitle()),
                () -> Assertions.assertTrue(first.getBody().contains("北京信息科技大学")),
                () -> Assertions.assertTrue(first.getBody().contains("信息检索课程")),
                () -> Assertions.assertEquals("https://news.qq.com/rain/a/20240318A01AB000", first.getSourceUrl()),
                () -> Assertions.assertEquals(first.getSourceUrl(), second.getSourceUrl()),
                () -> Assertions.assertEquals(first.getDocId(), second.getDocId()),
                () -> Assertions.assertEquals(ArticleIds.generateDocId(first.getSourceUrl(), first.getSource(), first.getTitle(), first.getPublishTime()), first.getDocId()),
                () -> Assertions.assertEquals("tech", first.getSection()),
                () -> Assertions.assertEquals("腾讯教育", first.getByline()),
                () -> Assertions.assertEquals(crawlTime, first.getCrawlTime())
        );
    }

    @Test
    void feedResponseExtractsCanonicalArticleUrls() {
        String feedResponse = """
                {
                  "data": [
                    {
                      "url": "https://new.qq.com/rain/a/20260415A08BI800?adChannelId=finance"
                    },
                    {
                      "sub_item": [
                        {
                          "url": "https://view.inews.qq.com/a/UTR2026041410047000"
                        },
                        {
                          "url": "https://new.qq.com/rain/a/20260416A068RU00#to-comment"
                        }
                      ]
                    }
                  ]
                }
                """;

        List<String> urls = crawler.extractArticleUrlsFromFeedResponse(feedResponse);

        Assertions.assertEquals(List.of(
                "https://news.qq.com/rain/a/20260415A08BI800",
                "https://news.qq.com/rain/a/20260416A068RU00"
        ), urls);
    }

    @Test
    void malformedFixtureIsRejectedGracefully() throws Exception {
        Blog malformed = crawler.parseArticle("https://news.qq.com/rain/a/20240318A09ZZ000", readFixture(MALFORMED_FIXTURE), Instant.parse("2024-03-18T10:00:00Z"));

        Assertions.assertNull(malformed);
    }

    private String readFixture(String path) throws Exception {
        return Files.readString(Path.of("src/test/resources", path), StandardCharsets.UTF_8);
    }
}
