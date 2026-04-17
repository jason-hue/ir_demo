package cn.edu.bistu.cs.ir;

import cn.edu.bistu.cs.ir.utils.QueryResponse;
import cn.edu.bistu.cs.ir.utils.FileUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.ai.enabled=false",
                "app.vector.enabled=false",
                "app.crawler.tencent.enabled=false",
                "app.seed.lucene.enabled=true",
                "app.seed.lucene.resource=classpath:fixtures/tencent/news/runtime-seed-articles.json",
                "irdemo.dir.home=workspace/test-lexical-only",
                "irdemo.dir.idx=${irdemo.dir.home}/idx",
                "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
        }
)
public class LexicalOnlyStartupTest {

    private static final String TEST_HOME = "workspace/test-lexical-only";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void queryEndpointStillRespondsWhenAiAndVectorAreDisabled() {
        ResponseEntity<QueryResponse<List<Map<String, String>>>> firstPage = restTemplate.exchange(
                "/query/kw?kw=新闻检索助手&pageNo=1&pageSize=2",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
        ResponseEntity<QueryResponse<List<Map<String, String>>>> secondPage = restTemplate.exchange(
                "/query/kw?kw=新闻检索助手&pageNo=2&pageSize=2",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                }
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.OK, firstPage.getStatusCode()),
                () -> Assertions.assertEquals(HttpStatus.OK, secondPage.getStatusCode()),
                () -> Assertions.assertNotNull(firstPage.getBody()),
                () -> Assertions.assertNotNull(secondPage.getBody()),
                () -> Assertions.assertTrue(firstPage.getBody().isSuccess()),
                () -> Assertions.assertTrue(secondPage.getBody().isSuccess()),
                () -> Assertions.assertEquals(2, firstPage.getBody().getData().size()),
                () -> Assertions.assertEquals(1, secondPage.getBody().getData().size()),
                () -> Assertions.assertNotEquals(
                        firstPage.getBody().getData().get(0).get("ID"),
                        secondPage.getBody().getData().get(0).get("ID")
                )
        );
    }

    @AfterAll
    static void cleanWorkspace() {
        FileUtils.deleteSubDirs(TEST_HOME);
    }
}
