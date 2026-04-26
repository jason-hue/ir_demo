package cn.edu.bistu.cs.ir;

import cn.edu.bistu.cs.ir.ai.ChatAnswerResult;
import cn.edu.bistu.cs.ir.ai.AiFallbackMode;
import cn.edu.bistu.cs.ir.ai.HybridRetrievalResult;
import cn.edu.bistu.cs.ir.ai.ProviderAvailabilityState;
import cn.edu.bistu.cs.ir.ai.ProviderStatus;
import cn.edu.bistu.cs.ir.ai.ProviderStatusService;
import cn.edu.bistu.cs.ir.ai.ProviderStatusSnapshot;
import cn.edu.bistu.cs.ir.controller.dto.ChatAskRequest;
import cn.edu.bistu.cs.ir.controller.dto.HybridQueryRequest;
import cn.edu.bistu.cs.ir.utils.FileUtils;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import org.mockito.ArgumentMatchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "irdemo.ai.chat-provider=ollama",
        "irdemo.ai.ollama.base-url=http://127.0.0.1:9",
        "irdemo.ai.ollama.chat-timeout=100ms",
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

    private static final String CONFIG_DISABLED_DETAIL = "Qdrant vector support is disabled by configuration.";

    private static final String VECTOR_TIMEOUT_DETAIL = "[QDRANT_TIMEOUT] Qdrant collection probe timed out after 100毫秒 at http://127.0.0.1:6333 while checking collection 'news article chunks'.";

    @Autowired
    private TestRestTemplate restTemplate;

    @SpyBean
    private ProviderStatusService providerStatusService;

    @MockitoBean
    private ChatModel chatModel;

    @Test
    void hybridEndpointFallsBackToLexicalOnlyWhenVectorSupportIsDisabledByConfiguration() {
        HybridQueryRequest request = new HybridQueryRequest();
        request.setQuestion("腾讯新闻中的新闻检索助手讲了什么");
        request.setPageNo(1);
        request.setPageSize(5);

        doReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.DISABLED, false, CONFIG_DISABLED_DETAIL),
                false,
                AiFallbackMode.LEXICAL_ONLY)).when(providerStatusService).snapshot();

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
                () -> Assertions.assertEquals(CONFIG_DISABLED_DETAIL, response.getBody().getData().getDegradedReason()),
                () -> Assertions.assertNotNull(response.getBody().getData().getResults().getFirst().getSourceUrl()),
                () -> Assertions.assertNotNull(response.getBody().getData().getResults().getFirst().getChunkId())
        );
    }

    @Test
    void hybridEndpointReturnsMachineCheckableTimeoutDegradedReason() {
        HybridQueryRequest request = new HybridQueryRequest();
        request.setQuestion("腾讯新闻中的新闻检索助手讲了什么");
        request.setPageNo(1);
        request.setPageSize(5);

        doReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.DEGRADED, true, VECTOR_TIMEOUT_DETAIL),
                false,
                AiFallbackMode.LEXICAL_ONLY)).when(providerStatusService).snapshot();

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
                () -> Assertions.assertEquals(VECTOR_TIMEOUT_DETAIL, response.getBody().getData().getDegradedReason()),
                () -> Assertions.assertTrue(response.getBody().getData().getDegradedReason().contains("[QDRANT_TIMEOUT]")),
                () -> Assertions.assertNotEquals(CONFIG_DISABLED_DETAIL, response.getBody().getData().getDegradedReason())
        );
    }

    @Test
    void chatEndpointReturnsMachineCheckableDegradedPayload() {
        ChatAskRequest request = new ChatAskRequest();
        request.setQuestion("请总结腾讯新闻中的AI相关新闻");

        doReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.UNAVAILABLE, false, "ollama chat unavailable"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                false,
                AiFallbackMode.LEXICAL_ONLY)).when(providerStatusService).snapshot();

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

    @ParameterizedTest
    @ValueSource(strings = {
            "qwertyuiopasdfghjkl",
            "zzqvbnm12345-nohit",
            "unmatched-topic-alpha-0425",
            "nohit-corpus-sentinel-xyz"
    })
    void chatEndpointReturnsInsufficientEvidenceWhenRetrievalIsEmpty(String question) {
        ChatAskRequest request = new ChatAskRequest();
        request.setQuestion(question);

        doReturn(new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.DISABLED, false, CONFIG_DISABLED_DETAIL),
                false,
                AiFallbackMode.LEXICAL_ONLY)).when(providerStatusService).snapshot();
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
                () -> Assertions.assertEquals("证据不足", response.getBody().getData().getAnswer()),
                () -> Assertions.assertEquals("lexical_only", response.getBody().getData().getRetrievalMode()),
                () -> Assertions.assertTrue(response.getBody().getData().getCitations().isEmpty())
        );
    }

    @Test
    void chatEndpointReturnsMachineCheckableDegradedPayloadWhenChatBudgetIsExceeded() {
        ChatAskRequest request = new ChatAskRequest();
        request.setQuestion("请总结腾讯新闻中的AI相关新闻");

        ProviderStatusSnapshot snapshot = new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY);
        doReturn(snapshot).when(providerStatusService).snapshot();
        doReturn(snapshot.chat()).when(providerStatusService).activeChatStatus();
        when(chatModel.call(ArgumentMatchers.any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(1_000L);
            return new ChatResponse(java.util.List.of(new Generation(new AssistantMessage("迟到的答案[1]"))));
        });

        long startedAt = System.nanoTime();
        ResponseEntity<QueryResponse<ChatAnswerResult>> response = restTemplate.exchange(
                "/chat/ask",
                HttpMethod.POST,
                new HttpEntity<>(request),
                new ParameterizedTypeReference<>() {
                }
        );
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.OK, response.getStatusCode()),
                () -> Assertions.assertNotNull(response.getBody()),
                () -> Assertions.assertFalse(response.getBody().isSuccess()),
                () -> Assertions.assertNotNull(response.getBody().getData()),
                () -> Assertions.assertFalse(response.getBody().getData().isAnswerAvailable()),
                () -> Assertions.assertTrue(response.getBody().getData().getDegradedReason().contains("超时")),
                () -> Assertions.assertNull(response.getBody().getData().getRetrievalMode()),
                () -> Assertions.assertTrue(response.getBody().getData().getCitations().isEmpty()),
                () -> Assertions.assertTrue(elapsedMillis < 1_000L, "expected endpoint to return before the mocked chat call finished")
        );
    }

    @AfterAll
    static void cleanWorkspace() {
        FileUtils.deleteSubDirs(TEST_HOME);
    }
}
