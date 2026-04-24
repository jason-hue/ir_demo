package cn.edu.bistu.cs.ir;

import cn.edu.bistu.cs.ir.controller.dto.ProductionStatusSnapshot;
import cn.edu.bistu.cs.ir.config.AppRuntimeProperties;
import cn.edu.bistu.cs.ir.crawler.CrawlerService;
import cn.edu.bistu.cs.ir.crawler.IngestionStatusSnapshot;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "irdemo.ai.ollama.base-url=http://127.0.0.1:9",
        "irdemo.ai.qdrant.enabled=true",
        "irdemo.ai.qdrant.host=127.0.0.1",
        "irdemo.ai.qdrant.http-port=1",
        "irdemo.ai.qdrant.grpc-port=1",
        "irdemo.ai.provider-status.connect-timeout=100ms",
        "irdemo.ai.provider-status.read-timeout=100ms"
})
class ProductionStatusEndpointTest {

    private static final String ARTICLE_FIXTURE = "fixtures/tencent/news/article-page.html";

    private static final String MALFORMED_FIXTURE = "fixtures/tencent/news/malformed-article-page.html";

    private static final Path TEST_HOME = createTestHome();

    @Autowired
    private CrawlerService crawlerService;

    @Autowired
    private AppRuntimeProperties appRuntimeProperties;

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void registerWorkDirs(DynamicPropertyRegistry registry) {
        registry.add("irdemo.dir.home", () -> TEST_HOME.toString());
        registry.add("irdemo.dir.idx", () -> TEST_HOME.resolve("idx").toString());
        registry.add("irdemo.dir.crawler", () -> TEST_HOME.resolve("crawler").toString());
    }

    @Test
    void productionStatusExposesLuceneProviderAndIngestSnapshots() {
        ResponseEntity<QueryResponse<ProductionStatusSnapshot>> response = restTemplate.exchange(
                "/status/production",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {
                });

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        QueryResponse<ProductionStatusSnapshot> body = response.getBody();
        Assertions.assertNotNull(body);
        Assertions.assertTrue(body.isSuccess());
        Assertions.assertTrue(appRuntimeProperties.getCrawler().getTencent().isEnabled());
        Assertions.assertNotNull(body.getData());
        Assertions.assertNotNull(body.getData().lucene());
        Assertions.assertTrue(body.getData().lucene().available());
        Assertions.assertNotNull(body.getData().providers());
        Assertions.assertNotNull(body.getData().providers().qdrant());
        Assertions.assertNotNull(body.getData().ingest());
        Assertions.assertNotNull(body.getData().ingest().categories());
    }

    @Test
    void productionStatusMarksForcedDownloadFailureAsFailedInsteadOfNoNewData() throws Exception {
        String firstSource = "broken-source-a";
        String secondSource = "broken-source-b";
        crawlerService.startTencentNewsCrawler(
                java.util.List.of("http://127.0.0.1:9/boom"),
                firstSource,
                1);
        Thread.sleep(25L);
        crawlerService.startTencentNewsCrawler(
                java.util.List.of("http://127.0.0.1:9/boom"),
                secondSource,
                1);

        List<IngestionStatusSnapshot.CategoryRunStatus> brokenStatuses = awaitCategoryStatuses(
                "adhoc",
                List.of(firstSource, secondSource));

        Assertions.assertEquals(2, brokenStatuses.size());
        Assertions.assertEquals(
                List.of(secondSource, firstSource),
                brokenStatuses.stream().map(IngestionStatusSnapshot.CategoryRunStatus::source).toList());
        Assertions.assertNotEquals(brokenStatuses.get(0).runId(), brokenStatuses.get(1).runId());
        for (IngestionStatusSnapshot.CategoryRunStatus brokenStatus : brokenStatuses) {
            Assertions.assertEquals("adhoc", brokenStatus.category());
            Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.FAILED, brokenStatus.outcome());
            Assertions.assertTrue(brokenStatus.requestFailureCount() > 0);
            Assertions.assertEquals(0, brokenStatus.indexedDocumentCount());
            Assertions.assertNotNull(brokenStatus.runId());
            Assertions.assertFalse(brokenStatus.runId().isBlank());
        }
    }

    @Test
    void productionStatusMarksSemanticArticleParseFailureAsFailedAfterHttpSuccess() throws Exception {
        String malformedArticleHtml = readFixture(MALFORMED_FIXTURE);
        try (HttpServerHandle server = startServer(Map.of("/broken-article", malformedArticleHtml))) {
            crawlerService.startTencentNewsCrawler(
                    java.util.List.of(server.url("/broken-article")),
                    "semantic-article-failure",
                    1);

            IngestionStatusSnapshot.CategoryRunStatus brokenStatus = awaitCategoryStatus("adhoc", "semantic-article-failure");

            Assertions.assertNotNull(brokenStatus);
            Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.FAILED, brokenStatus.outcome());
            Assertions.assertTrue(brokenStatus.requestSuccessCount() > 0);
            Assertions.assertTrue(brokenStatus.requestFailureCount() > 0);
            Assertions.assertEquals(0, brokenStatus.indexedDocumentCount());
            Assertions.assertTrue(brokenStatus.lastError().contains("article parse failed"));
        }
    }

    @Test
    void productionStatusMarksSemanticCategoryExtractionFailureAsFailedAfterHttpSuccess() throws Exception {
        try (HttpServerHandle server = startServer(Map.of("/empty-list", "<html><body><div>empty list</div></body></html>"))) {
            crawlerService.startTencentNewsCrawler(
                    java.util.List.of(server.url("/empty-list")),
                    "semantic-list-failure",
                    1);

            IngestionStatusSnapshot.CategoryRunStatus brokenStatus = awaitCategoryStatus("adhoc", "semantic-list-failure");

            Assertions.assertNotNull(brokenStatus);
            Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.FAILED, brokenStatus.outcome());
            Assertions.assertTrue(brokenStatus.requestSuccessCount() > 0);
            Assertions.assertTrue(brokenStatus.requestFailureCount() > 0);
            Assertions.assertEquals(0, brokenStatus.indexedDocumentCount());
            Assertions.assertTrue(brokenStatus.lastError().contains("yielded no article URLs"));
        }
    }

    @Test
    void productionStatusMarksLuceneSuccessPlusVectorFailureAsPartialSuccess() throws Exception {
        String articleHtml = readFixture(ARTICLE_FIXTURE);
        try (HttpServerHandle server = startServer(Map.of("/rain/a/20240318A01AB000", articleHtml))) {
            crawlerService.startTencentNewsCrawler(
                    java.util.List.of(server.url("/rain/a/20240318A01AB000")),
                    "vector-sync-outage",
                    1);

            IngestionStatusSnapshot.CategoryRunStatus status = awaitCategoryStatus("adhoc", "vector-sync-outage");

            Assertions.assertNotNull(status);
            Assertions.assertAll(
                    () -> Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.PARTIAL_SUCCESS, status.outcome()),
                    () -> Assertions.assertTrue(status.requestSuccessCount() > 0),
                    () -> Assertions.assertEquals(1, status.indexedDocumentCount()),
                    () -> Assertions.assertEquals(0, status.indexFailureCount()),
                    () -> Assertions.assertNotNull(status.lastIndexedDocId()),
                    () -> Assertions.assertTrue(status.lastIndexedSourceUrl().contains("20240318A01AB000")),
                    () -> Assertions.assertTrue(status.lastError().contains("分块向量"))
            );
        }
    }

    @AfterAll
    static void cleanWorkspace() throws IOException {
        if (!Files.exists(TEST_HOME)) {
            return;
        }
        try (var paths = Files.walk(TEST_HOME)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    private static Path createTestHome() {
        try {
            return Files.createTempDirectory("ir-demo-production-status-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private IngestionStatusSnapshot.CategoryRunStatus awaitCategoryStatus(String categoryName, String source) throws Exception {
        List<IngestionStatusSnapshot.CategoryRunStatus> statuses = awaitCategoryStatuses(categoryName, List.of(source));
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    private List<IngestionStatusSnapshot.CategoryRunStatus> awaitCategoryStatuses(String categoryName,
                                                                                  List<String> sources) throws Exception {
        List<IngestionStatusSnapshot.CategoryRunStatus> statuses = List.of();
        for (int i = 0; i < 80; i++) {
            ResponseEntity<QueryResponse<ProductionStatusSnapshot>> response = restTemplate.exchange(
                    "/status/production",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {
                    });
            Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
            QueryResponse<ProductionStatusSnapshot> body = response.getBody();
            Assertions.assertNotNull(body);
            Assertions.assertNotNull(body.getData());
            statuses = body.getData().ingest().categories().stream()
                    .filter(candidate -> categoryName.equals(candidate.category()) && sources.contains(candidate.source()))
                    .toList();
            if (statuses.size() == sources.size()
                    && statuses.stream().allMatch(status -> status.outcome() != IngestionStatusSnapshot.RunOutcome.RUNNING)) {
                return statuses;
            }
            Thread.sleep(100L);
        }
        return statuses;
    }

    private String readFixture(String path) throws IOException {
        return Files.readString(Path.of("src/test/resources", path), StandardCharsets.UTF_8);
    }

    private HttpServerHandle startServer(Map<String, String> responses) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        for (Map.Entry<String, String> entry : responses.entrySet()) {
            server.createContext(entry.getKey(), exchange -> writeResponse(exchange, entry.getValue()));
        }
        server.start();
        return new HttpServerHandle(server);
    }

    private void writeResponse(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (var outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        }
    }

    private record HttpServerHandle(HttpServer server) implements AutoCloseable {

        private String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
