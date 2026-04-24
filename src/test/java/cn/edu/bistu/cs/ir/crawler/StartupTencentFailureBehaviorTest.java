package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.controller.dto.ProductionStatusSnapshot;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "irdemo.dir.startCrawler=true",
        "app.ai.enabled=false",
        "app.vector.enabled=false",
        "irdemo.ai.ollama.enabled=false",
        "irdemo.ai.ollama.warmup-enabled=false",
        "irdemo.ai.qdrant.enabled=false",
        "app.crawler.tencent.enabled=true",
        "app.crawler.tencent.categories[0].name=startup-failure",
        "app.crawler.tencent.categories[0].enabled=true",
        "app.crawler.tencent.categories[0].per-run-limit=1",
        "app.crawler.tencent.categories[0].source-label=startup-failure"
})
class StartupTencentFailureBehaviorTest {

    private static final String MALFORMED_FIXTURE = "fixtures/tencent/news/malformed-article-page.html";

    private static final Path TEST_HOME = createTestHome();

    private static final HttpServer SERVER = startServer();

    @Autowired
    private CrawlerService crawlerService;

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("irdemo.dir.home", () -> TEST_HOME.toString());
        registry.add("irdemo.dir.idx", () -> TEST_HOME.resolve("idx").toString());
        registry.add("irdemo.dir.crawler", () -> TEST_HOME.resolve("crawler").toString());
        registry.add("app.crawler.tencent.categories[0].list-urls[0]",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/broken-article");
    }

    @Test
    void startupTencentFailureDoesNotCrashServiceAndIsReportedAsFailed() throws Exception {
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

        IngestionStatusSnapshot.CategoryRunStatus status = awaitCompletedStatus();
        Assertions.assertNotNull(status);

        Assertions.assertAll(
                () -> Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.FAILED, status.outcome()),
                () -> Assertions.assertTrue(status.requestSuccessCount() > 0),
                () -> Assertions.assertTrue(status.requestFailureCount() > 0),
                () -> Assertions.assertEquals(0, status.indexedDocumentCount()),
                () -> Assertions.assertTrue(status.lastError().contains("article parse failed")),
                () -> Assertions.assertEquals(0, countIndexedDocs())
        );
    }

    @AfterAll
    static void cleanUp() throws IOException {
        SERVER.stop(0);
        deleteRecursively(TEST_HOME);
    }

    private IngestionStatusSnapshot.CategoryRunStatus awaitCompletedStatus() throws Exception {
        IngestionStatusSnapshot.CategoryRunStatus status = null;
        for (int i = 0; i < 80; i++) {
            status = crawlerService.getIngestionStatusSnapshot().categories().stream()
                    .findFirst()
                    .orElse(null);
            if (status != null && status.outcome() != IngestionStatusSnapshot.RunOutcome.RUNNING) {
                return status;
            }
            Thread.sleep(100L);
        }
        return status;
    }

    private int countIndexedDocs() throws Exception {
        try (FSDirectory directory = FSDirectory.open(TEST_HOME.resolve("idx"));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        } catch (IndexNotFoundException ignored) {
            return 0;
        }
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/broken-article", exchange -> writeResponse(exchange, readFixture(MALFORMED_FIXTURE)));
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeResponse(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (var outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        }
    }

    private static String readFixture(String path) {
        try {
            return Files.readString(Path.of("src/test/resources", path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path createTestHome() {
        try {
            return Files.createTempDirectory("ir-demo-startup-tencent-failure-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
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
}
