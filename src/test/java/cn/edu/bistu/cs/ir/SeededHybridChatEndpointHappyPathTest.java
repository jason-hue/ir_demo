package cn.edu.bistu.cs.ir;

import cn.edu.bistu.cs.ir.ai.ChatAnswerResult;
import cn.edu.bistu.cs.ir.ai.HybridRetrievalResult;
import cn.edu.bistu.cs.ir.ai.ProviderStatusService;
import cn.edu.bistu.cs.ir.controller.dto.ChatAskRequest;
import cn.edu.bistu.cs.ir.controller.dto.HybridQueryRequest;
import cn.edu.bistu.cs.ir.index.ArticleIdxFields;
import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.model.Blog;
import cn.edu.bistu.cs.ir.support.DemoFixtureSupport;
import cn.edu.bistu.cs.ir.utils.StringUtil;
import cn.edu.bistu.cs.ir.utils.FileUtils;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "app.seed.lucene.enabled=false",
        "irdemo.ai.ollama.enabled=false",
        "irdemo.ai.ollama.warmup-enabled=false",
        "irdemo.ai.qdrant.enabled=false",
        "irdemo.ai.ollama.chat-timeout=100ms",
        "irdemo.dir.home=workspace/test-seeded-hybrid-chat-happy",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class SeededHybridChatEndpointHappyPathTest {

    private static final String TEST_HOME = "workspace/test-seeded-hybrid-chat-happy";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private cn.edu.bistu.cs.ir.ai.ArticleChunkingService articleChunkingService;

    @Autowired
    private IdxService idxService;

    @MockitoBean
    private ProviderStatusService providerStatusService;

    @MockitoBean
    private VectorStore vectorStore;

    @MockitoBean
    private ChatModel chatModel;

    @BeforeEach
    void setUpProviders() throws Exception {
        Blog article = DemoFixtureSupport.firstRuntimeSeedArticle(objectMapper);
        ArticleChunkMetadata chunk = DemoFixtureSupport.firstChunk(articleChunkingService, article);

        when(providerStatusService.snapshot()).thenReturn(DemoFixtureSupport.availableSnapshot());
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorDocument(article, chunk)));
        when(chatModel.call(ArgumentMatchers.any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("基于固定种子数据的检索问答案例答案[1]")))));

        for (Blog seedArticle : DemoFixtureSupport.loadRuntimeSeedArticles(objectMapper)) {
            idxService.addDocument(ArticleIdxFields.ID, seedArticle.getDocId(), toDoc(seedArticle));
        }
    }

    @Test
    void hybridEndpointUsesSeededLuceneDataAndAvailableVectorPath() {
        HybridQueryRequest request = new HybridQueryRequest();
        request.setQuestion("新闻检索助手");
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
                () -> Assertions.assertEquals("hybrid", response.getBody().getData().getMode()),
                () -> Assertions.assertFalse(response.getBody().getData().getResults().isEmpty()),
                () -> Assertions.assertNull(response.getBody().getData().getDegradedReason()),
                () -> Assertions.assertEquals("腾讯新闻推出新闻检索助手试点",
                        response.getBody().getData().getResults().getFirst().getTitle())
        );
    }

    @Test
    void chatEndpointReturnsAnswerAgainstSeededDemoFixtures() {
        ChatAskRequest request = new ChatAskRequest();
        request.setQuestion("请总结新闻检索助手的相关新闻");

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

    @Test
    void chatEndpointReturnsBoundedDegradedPayloadWhenChatModelStalls() {
        doAnswer(invocation -> {
            TimeUnit.MILLISECONDS.sleep(250L);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("迟到的检索问答案例答案[1]"))));
        }).when(chatModel).call(any(Prompt.class));

        ChatAskRequest request = new ChatAskRequest();
        request.setQuestion("张雪是干啥的");

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
                () -> Assertions.assertTrue(elapsedMillis < 2_000L, "expected bounded endpoint response")
        );
    }

    @AfterAll
    static void cleanWorkspace() {
        FileUtils.deleteSubDirs(TEST_HOME);
    }

    private org.springframework.ai.document.Document vectorDocument(Blog article, ArticleChunkMetadata chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("docId", article.getDocId());
        metadata.put("chunkId", chunk.getChunkId());
        metadata.put("title", article.getTitle());
        metadata.put("source", article.getSource());
        metadata.put("sourceUrl", article.getSourceUrl());
        metadata.put("publishTime", article.getPublishTime().toString());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("charCount", chunk.getCharCount());
        return new org.springframework.ai.document.Document(chunk.getChunkText(), chunk.getChunkId(), metadata);
    }

    private Document toDoc(Blog article) {
        Document document = new Document();
        document.add(new StringField(ArticleIdxFields.ID, article.getDocId(), Field.Store.YES));
        document.add(new TextField(ArticleIdxFields.TITLE, article.getTitle(), Field.Store.YES));
        if (!StringUtil.isEmpty(article.getBody())) {
            document.add(new TextField(ArticleIdxFields.CONTENT, article.getBody(), Field.Store.YES));
        }
        if (article.getPublishTime() != null) {
            long publishTime = article.getPublishTime().toEpochMilli();
            document.add(new LongPoint(ArticleIdxFields.TIME, publishTime));
            document.add(new StoredField(ArticleIdxFields.TIME, publishTime));
            document.add(new NumericDocValuesField(ArticleIdxFields.TIME, publishTime));
        }
        if (!StringUtil.isEmpty(article.getAuthor())) {
            document.add(new TextField(ArticleIdxFields.AUTHOR, article.getAuthor(), Field.Store.YES));
        }
        if (!StringUtil.isEmpty(article.getByline())) {
            document.add(new TextField(ArticleIdxFields.BYLINE, article.getByline(), Field.Store.YES));
        }
        if (!StringUtil.isEmpty(article.getSource())) {
            document.add(new StringField(ArticleIdxFields.SOURCE, article.getSource(), Field.Store.YES));
        }
        if (!StringUtil.isEmpty(article.getSourceUrl())) {
            document.add(new StringField(ArticleIdxFields.SOURCE_URL, article.getSourceUrl(), Field.Store.YES));
        }
        if (!StringUtil.isEmpty(article.getSection())) {
            document.add(new StringField(ArticleIdxFields.SECTION, article.getSection(), Field.Store.YES));
        }
        return document;
    }
}
