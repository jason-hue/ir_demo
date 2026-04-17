package cn.edu.bistu.cs.ir.crawler;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import us.codecraft.webmagic.selector.Html;

import java.nio.file.Files;
import java.util.List;

public class CnBlogsCrawlerFixtureTest {

    private static final String LIST_FIXTURE = "fixtures/cnblogs/tencent-cloud-native/list-page.html";
    private static final String DETAIL_FIXTURE = "fixtures/cnblogs/tencent-cloud-native/detail-page.html";

    @Test
    void cnblogsFixturesMatchCurrentCrawlerSelectors() throws Exception {
        Html listHtml = new Html(Files.readString(new ClassPathResource(LIST_FIXTURE).getFile().toPath()));
        Html detailHtml = new Html(Files.readString(new ClassPathResource(DETAIL_FIXTURE).getFile().toPath()));

        List<String> blogs = listHtml.xpath("//div[@class='forFlow']//div[@class='postTitle']/a/@href").all();
        String title = detailHtml.xpath("//div[@class='post']/h1[@class='postTitle']//span/text()").get();
        String time = detailHtml.xpath("//div[@class='postDesc']/span[@id='post-date']/text()").get();
        String content = detailHtml.xpath("//div[@class='post']//div[@id='cnblogs_post_body']/allText()").get();

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, blogs.size()),
                () -> Assertions.assertEquals("https://www.cnblogs.com/tencent-cloud-native/p/18700001.html", blogs.get(0)),
                () -> Assertions.assertEquals("https://www.cnblogs.com/tencent-cloud-native/p/18700002.html", blogs.get(1)),
                () -> Assertions.assertEquals("腾讯云原生｜Service Mesh 演进实践", title),
                () -> Assertions.assertEquals("2024-03-18 09:30", time),
                () -> Assertions.assertTrue(content.contains("腾讯云原生团队")),
                () -> Assertions.assertTrue(content.contains("服务网格")),
                () -> Assertions.assertTrue(content.contains("可观测性"))
        );
    }
}
