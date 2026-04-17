package cn.edu.bistu.cs.ir;

import cn.edu.bistu.cs.ir.ai.ChatAnswerResult;
import cn.edu.bistu.cs.ir.ai.HybridRetrievalResult;
import cn.edu.bistu.cs.ir.controller.dto.ChatAskRequest;
import cn.edu.bistu.cs.ir.controller.dto.HybridQueryRequest;
import cn.edu.bistu.cs.ir.utils.FileUtils;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "irdemo.ai.ollama.base-url=http://127.0.0.1:9",
        "irdemo.ai.qdrant.enabled=true",
        "irdemo.ai.qdrant.host=127.0.0.1",
        "irdemo.ai.qdrant.http-port=1",
        "irdemo.ai.qdrant.grpc-port=1",
        "irdemo.ai.provider-status.connect-timeout=100ms",
        "irdemo.ai.provider-status.read-timeout=100ms",
        "app.seed.lucene.enabled=true",
        "app.seed.lucene.resource=classpath:fixtures/tencent/news/runtime-seed-articles.json",
        "irdemo.dir.home=workspace/test-task9-endpoints",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class HybridChatEndpointDegradedTest {

    private static final String TEST_HOME = "workspace/test-task9-endpoints";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void hybridEndpointFallsBackToLexicalOnlyWithoutVectorPath() {
        HybridQueryRequest request = new HybridQueryRequest();
        request.setQuestion("腾讯新闻中的新闻检索助手讲了什么");
        request.setPageNo(1);
        request.setPageSize(5);

        ResponseEntity<QueryResponse<HybridRetrievalResult>> response = restTemplate.exchange(
                "/query/hybrid",
                HttpMethod.POST,
                new HttpEntity<>(request),
                new ParameterizedTypeReference<>() {
                }
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.OK, response.getStatusCode()),
                () -> Assertions.assertNotNull(response.getBody()),
                () -> Assertions.assertTrue(response.getBody().isSuccess()),
                () -> Assertions.assertNotNull(response.getBody().getData()),
                () -> Assertions.assertEquals("lexical_only", response.getBody().getData().getMode()),
                () -> Assertions.assertFalse(response.getBody().getData().getResults().isEmpty()),
                () -> Assertions.assertNotNull(response.getBody().getData().getDegradedReason()),
                () -> Assertions.assertNotNull(response.getBody().getData().getResults().getFirst().getSourceUrl()),
                () -> Assertions.assertNotNull(response.getBody().getData().getResults().getFirst().getChunkId())
        );
    }

    @Test
    void chatEndpointReturnsMachineCheckableDegradedPayload() {
        ChatAskRequest request = new ChatAskRequest();
        request.setQuestion("请总结腾讯新闻中的AI相关新闻");

        ResponseEntity<QueryResponse<ChatAnswerResult>> response = restTemplate.exchange(
                "/chat/ask",
                HttpMethod.POST,
                new HttpEntity<>(request),
                new ParameterizedTypeReference<>() {
                }
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.OK, response.getStatusCode()),
                () -> Assertions.assertNotNull(response.getBody()),
                () -> Assertions.assertFalse(response.getBody().isSuccess()),
                () -> Assertions.assertNotNull(response.getBody().getData()),
                () -> Assertions.assertFalse(response.getBody().getData().isAnswerAvailable()),
                () -> Assertions.assertNotNull(response.getBody().getData().getDegradedReason()),
                () -> Assertions.assertNull(response.getBody().getData().getRetrievalMode()),
                () -> Assertions.assertTrue(response.getBody().getData().getCitations().isEmpty())
        );
    }

    @AfterAll
    static void cleanWorkspace() {
        FileUtils.deleteSubDirs(TEST_HOME);
    }
}
