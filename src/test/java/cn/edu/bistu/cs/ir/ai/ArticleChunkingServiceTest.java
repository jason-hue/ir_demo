package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.model.Blog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import us.codecraft.webmagic.selector.Html;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

public class ArticleChunkingServiceTest {

    private static final String ARTICLE_FIXTURE = "fixtures/tencent/news/article-page.html";

    private final ArticleChunkingService articleChunkingService = new ArticleChunkingService();

    @Test
    void chunkingStaysDeterministicWithParagraphSentenceAwareBoundariesAndOverlap() throws Exception {
        Blog article = buildLongFixtureArticle();

        List<ArticleChunkMetadata> first = articleChunkingService.chunk(article);
        List<ArticleChunkMetadata> second = articleChunkingService.chunk(article);

        Assertions.assertTrue(first.size() >= 2);
        Assertions.assertEquals(first.size(), second.size());

        for (int i = 0; i < first.size(); i++) {
            int expectedIndex = i;
            ArticleChunkMetadata current = first.get(i);
            ArticleChunkMetadata repeated = second.get(i);

            Assertions.assertAll(
                    () -> Assertions.assertEquals(article.getDocId() + "#" + expectedIndex, current.getChunkId()),
                    () -> Assertions.assertEquals(expectedIndex, current.getChunkIndex()),
                    () -> Assertions.assertTrue(current.getCharCount() <= ArticleChunkingService.MAX_CHUNK_CHARS),
                    () -> Assertions.assertEquals(current.getChunkId(), repeated.getChunkId()),
                    () -> Assertions.assertEquals(current.getChunkText(), repeated.getChunkText()),
                    () -> Assertions.assertEquals(current.getCharCount(), repeated.getCharCount()),
                    () -> Assertions.assertFalse(current.getChunkText().isBlank()),
                    () -> Assertions.assertFalse(current.getChunkText().startsWith("\n"))
            );

            if (i > 0) {
                String previous = first.get(i - 1).getChunkText();
                int overlapChars = Math.min(
                        ArticleChunkingService.CHUNK_OVERLAP_CHARS,
                        Math.min(current.getCharCount(), first.get(i - 1).getCharCount()));
                String overlapPrefix = ArticleChunkingService.substringByCodePoints(current.getChunkText(), 0, overlapChars);
                String overlapSuffix = ArticleChunkingService.substringByCodePoints(
                        previous,
                        Math.max(0, first.get(i - 1).getCharCount() - overlapChars),
                        first.get(i - 1).getCharCount());
                Assertions.assertEquals(overlapSuffix, overlapPrefix);
            }
        }

    }

    private Blog buildLongFixtureArticle() throws Exception {
        String rawHtml = Files.readString(new ClassPathResource(ARTICLE_FIXTURE).getFile().toPath());
        Html html = new Html(rawHtml);
        String baseBody = String.join("\n", html.xpath("//div[contains(@class,'content-article')]//p/allText()").all());

        Blog parsed = new Blog();
        parsed.setSource("tencent-news");
        parsed.setSourceUrl("https://news.qq.com/rain/a/20240318A01AB000");
        parsed.setTitle("腾讯新闻推出新闻检索助手试点");
        String longBody = (baseBody + "\n").repeat(30);
        parsed.setBody(longBody);
        parsed.setPublishTime(Instant.parse("2024-03-18T09:30:00Z"));
        parsed.setCrawlTime(Instant.parse("2024-03-18T10:00:00Z"));
        parsed.setSection("tech");
        parsed.setAuthor("腾讯教育");
        parsed.setByline("腾讯教育");
        parsed.ensureDocId();
        return parsed;
    }
}
