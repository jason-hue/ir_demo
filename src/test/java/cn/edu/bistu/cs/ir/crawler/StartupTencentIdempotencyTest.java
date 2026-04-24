package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.config.Config;
import cn.edu.bistu.cs.ir.index.ArticleIdxFields;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "irdemo.dir.startCrawler=false",
        "app.ai.enabled=false",
        "app.vector.enabled=false",
        "irdemo.ai.ollama.enabled=false",
        "irdemo.ai.ollama.warmup-enabled=false",
        "irdemo.ai.qdrant.enabled=false",
        "app.crawler.tencent.enabled=true",
        "app.crawler.tencent.categories[0].name=startup-idempotent",
        "app.crawler.tencent.categories[0].enabled=true",
        "app.crawler.tencent.categories[0].per-run-limit=1",
        "app.crawler.tencent.categories[0].source-label=startup-idempotent"
})
class StartupTencentIdempotencyTest {

    private static final String ARTICLE_FIXTURE = "fixtures/tencent/news/article-page.html";

    private static final Path TEST_HOME = createTestHome();

    private static final HttpServer SERVER = startServer();

    @Autowired
    private CrawlerService crawlerService;

    @Autowired
    private Config config;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("irdemo.dir.home", () -> TEST_HOME.toString());
        registry.add("irdemo.dir.idx", () -> TEST_HOME.resolve("idx").toString());
        registry.add("irdemo.dir.crawler", () -> TEST_HOME.resolve("crawler").toString());
        registry.add("app.crawler.tencent.categories[0].list-urls[0]",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/rain/a/20240318A01AB000");
    }

    @Test
    void repeatedStartupTriggersKeepSingleLuceneDocumentForSameTencentArticleIdentity() throws Exception {
        config.setStartCrawler(true);

        crawlerService.startTencentCrawlerAfterApplicationReady();
        List<IngestionStatusSnapshot.CategoryRunStatus> firstRunStatuses = awaitCompletedStatuses(1);

        int firstDocCount = countIndexedDocs();
        int firstUniqueDocIdCount = countUniqueDocIds();

        crawlerService.startTencentCrawlerAfterApplicationReady();
        List<IngestionStatusSnapshot.CategoryRunStatus> secondRunStatuses = awaitCompletedStatuses(2);

        Assertions.assertAll(
                () -> Assertions.assertEquals(1, firstRunStatuses.size()),
                () -> Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.SUCCESS, firstRunStatuses.getFirst().outcome()),
                () -> Assertions.assertEquals(1, firstDocCount),
                () -> Assertions.assertEquals(1, firstUniqueDocIdCount),
                () -> Assertions.assertEquals(2, secondRunStatuses.size()),
                () -> Assertions.assertTrue(secondRunStatuses.stream().allMatch(status -> status.outcome() == IngestionStatusSnapshot.RunOutcome.SUCCESS)),
                () -> Assertions.assertEquals(1, countIndexedDocs()),
                () -> Assertions.assertEquals(1, countUniqueDocIds()),
                () -> Assertions.assertEquals(secondRunStatuses.get(0).lastIndexedDocId(), secondRunStatuses.get(1).lastIndexedDocId()),
                () -> Assertions.assertEquals("https://news.qq.com/rain/a/20240318A01AB000", secondRunStatuses.get(0).lastIndexedSourceUrl()),
                () -> Assertions.assertEquals("https://news.qq.com/rain/a/20240318A01AB000", secondRunStatuses.get(1).lastIndexedSourceUrl())
        );
    }

    @AfterAll
    static void cleanUp() throws IOException {
        SERVER.stop(0);
        deleteRecursively(TEST_HOME);
    }

    private List<IngestionStatusSnapshot.CategoryRunStatus> awaitCompletedStatuses(int expectedCount) throws Exception {
        List<IngestionStatusSnapshot.CategoryRunStatus> statuses = List.of();
        for (int i = 0; i < 80; i++) {
            statuses = crawlerService.getIngestionStatusSnapshot().categories().stream()
                    .filter(candidate -> candidate.outcome() != IngestionStatusSnapshot.RunOutcome.RUNNING)
                    .toList();
            if (statuses.size() == expectedCount
                    && statuses.stream().allMatch(status -> status.outcome() != IngestionStatusSnapshot.RunOutcome.RUNNING)) {
                return new ArrayList<>(statuses);
            }
            Thread.sleep(100L);
        }
        return new ArrayList<>(statuses);
    }

    private int countIndexedDocs() throws Exception {
        try (FSDirectory directory = FSDirectory.open(TEST_HOME.resolve("idx"));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        } catch (IndexNotFoundException ignored) {
            return 0;
        }
    }

    private int countUniqueDocIds() throws Exception {
        try (FSDirectory directory = FSDirectory.open(TEST_HOME.resolve("idx"));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            Set<String> docIds = new HashSet<>();
            for (int i = 0; i < reader.maxDoc(); i++) {
                IndexableField field = reader.document(i).getField(ArticleIdxFields.ID);
                if (field != null) {
                    docIds.add(field.stringValue());
                }
            }
            return docIds.size();
        } catch (IndexNotFoundException ignored) {
            return 0;
        }
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/rain/a/20240318A01AB000", exchange -> writeResponse(exchange, readFixture(ARTICLE_FIXTURE)));
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
            return Files.createTempDirectory("ir-demo-startup-tencent-idempotent-");
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
