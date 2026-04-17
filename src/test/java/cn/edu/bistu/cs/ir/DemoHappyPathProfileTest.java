package cn.edu.bistu.cs.ir;

import cn.edu.bistu.cs.ir.ai.ChatAnswerResult;
import cn.edu.bistu.cs.ir.controller.dto.ChatAskRequest;
import cn.edu.bistu.cs.ir.utils.FileUtils;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "irdemo.dir.home=workspace/test-demo-happy-profile",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
@ActiveProfiles({"demo", "demo-happy"})
class DemoHappyPathProfileTest {

    private static final String TEST_HOME = "workspace/test-demo-happy-profile";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void chatEndpointReturnsSeededHappyPathAnswerForDemoProfile() {
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
                () -> Assertions.assertTrue(response.getBody().isSuccess()),
                () -> Assertions.assertNotNull(response.getBody().getData()),
                () -> Assertions.assertTrue(response.getBody().getData().isAnswerAvailable()),
                () -> Assertions.assertEquals("hybrid", response.getBody().getData().getRetrievalMode()),
                () -> Assertions.assertFalse(response.getBody().getData().getCitations().isEmpty()),
                () -> Assertions.assertTrue(response.getBody().getData().getAnswer().contains("检索问答案例答案"))
        );
    }

    @AfterAll
    static void cleanWorkspace() {
        FileUtils.deleteSubDirs(TEST_HOME);
    }
}
