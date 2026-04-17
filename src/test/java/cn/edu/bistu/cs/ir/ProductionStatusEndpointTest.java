package cn.edu.bistu.cs.ir;

import cn.edu.bistu.cs.ir.controller.dto.ProductionStatusSnapshot;
import cn.edu.bistu.cs.ir.config.AppRuntimeProperties;
import cn.edu.bistu.cs.ir.crawler.CrawlerService;
import cn.edu.bistu.cs.ir.crawler.IngestionStatusSnapshot;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "irdemo.ai.ollama.base-url=http://127.0.0.1:9",
        "irdemo.ai.qdrant.enabled=true",
        "irdemo.ai.qdrant.host=127.0.0.1",
        "irdemo.ai.qdrant.http-port=1",
        "irdemo.ai.qdrant.grpc-port=1",
        "irdemo.ai.provider-status.connect-timeout=100ms",
        "irdemo.ai.provider-status.read-timeout=100ms",
        "irdemo.dir.home=workspace/test-production-status",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class ProductionStatusEndpointTest {

    private static final String MALFORMED_FIXTURE = "fixtures/tencent/news/malformed-article-page.html";

    @Autowired
    private CrawlerService crawlerService;

    @Autowired
    private AppRuntimeProperties appRuntimeProperties;

    @Autowired
    private TestRestTemplate restTemplate;

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
        crawlerService.startTencentNewsCrawler(
                java.util.List.of("http://127.0.0.1:9/boom"),
                "broken-source",
                1);

        IngestionStatusSnapshot.CategoryRunStatus brokenStatus = null;
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
            brokenStatus = body.getData().ingest().categories().stream()
                    .filter(category -> "adhoc".equals(category.category()))
                    .findFirst()
                    .orElse(null);
            if (brokenStatus != null && brokenStatus.outcome() != IngestionStatusSnapshot.RunOutcome.RUNNING) {
                break;
            }
            Thread.sleep(100L);
        }

        Assertions.assertNotNull(brokenStatus);
        Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.FAILED, brokenStatus.outcome());
        Assertions.assertTrue(brokenStatus.requestFailureCount() > 0);
        Assertions.assertEquals(0, brokenStatus.indexedDocumentCount());
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

    private IngestionStatusSnapshot.CategoryRunStatus awaitCategoryStatus(String categoryName, String source) throws Exception {
        IngestionStatusSnapshot.CategoryRunStatus status = null;
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
            status = body.getData().ingest().categories().stream()
                    .filter(candidate -> categoryName.equals(candidate.category()) && source.equals(candidate.source()))
                    .findFirst()
                    .orElse(null);
            if (status != null && status.outcome() != IngestionStatusSnapshot.RunOutcome.RUNNING) {
                return status;
            }
            Thread.sleep(100L);
        }
        return status;
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
