package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.ai.AiFallbackService;
import cn.edu.bistu.cs.ir.ai.ArticleChunkEmbedding;
import cn.edu.bistu.cs.ir.ai.ArticleChunkingService;
import cn.edu.bistu.cs.ir.ai.ArticleEmbeddingService;
import cn.edu.bistu.cs.ir.ai.ProviderStatusService;
import cn.edu.bistu.cs.ir.ai.ProviderStatusSnapshot;
import cn.edu.bistu.cs.ir.config.AiProperties;
import cn.edu.bistu.cs.ir.index.ArticleIdxFields;
import cn.edu.bistu.cs.ir.model.Article;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.support.DemoFixtureSupport;
import cn.edu.bistu.cs.ir.utils.FileUtils;
import cn.edu.bistu.cs.ir.utils.HttpUtils;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag("live-network")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TencentNewsCrawlerLiveNetworkTest {

    private static final int QDRANT_SCROLL_LIMIT = 2048;

    private static final String CATEGORY_NAME = "finance";

    private static final String CATEGORY_URL = "https://news.qq.com/ch/finance/";

    private static final String SOURCE_LABEL = "tencent-news-finance";

    private static final List<CategoryConfig> TASK8_CATEGORY_CONFIGS = List.of(
            new CategoryConfig(CATEGORY_NAME, CATEGORY_URL, SOURCE_LABEL),
            new CategoryConfig("tech", "https://news.qq.com/ch/tech/", "tencent-news-tech"),
            new CategoryConfig("edu", "https://news.qq.com/ch/edu/", "tencent-news-edu")
    );

    private static final String TEST_HOME = "workspace/test-tencent-live-" + System.nanoTime();

    private static final String TEST_COLLECTION = "news_article_chunks_task7_" + System.nanoTime();

    private static final HttpUtils HTTP_UTILS = new HttpUtils();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CrawlerService crawlerService;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("irdemo.dir.home", () -> TEST_HOME);
        registry.add("irdemo.dir.idx", () -> TEST_HOME + "/idx");
        registry.add("irdemo.dir.crawler", () -> TEST_HOME + "/crawler");
        registry.add("irdemo.dir.startCrawler", () -> true);
        registry.add("app.seed.lucene.enabled", () -> false);
        registry.add("app.crawler.tencent.enabled", () -> true);
        registry.add("app.crawler.tencent.categories[0].name", () -> CATEGORY_NAME);
        registry.add("app.crawler.tencent.categories[0].enabled", () -> true);
        registry.add("app.crawler.tencent.categories[0].list-urls[0]", () -> CATEGORY_URL);
        registry.add("app.crawler.tencent.categories[0].per-run-limit", () -> 1);
        registry.add("app.crawler.tencent.categories[0].source-label", () -> SOURCE_LABEL);
        registry.add("irdemo.ai.ollama.enabled", () -> false);
        registry.add("irdemo.ai.qdrant.enabled", () -> true);
        registry.add("irdemo.ai.qdrant.collection-name", () -> TEST_COLLECTION);
    }

    @Test
    @Order(1)
    void startupFinanceCategoryIngestProducesCanonicalArticleThroughLuceneAndQdrant() throws Exception {
        assertConfiguredCategoryPageReachable();

        List<Document> docs = awaitIndexedDocuments();
        Document first = docs.getFirst();
        String docId = first.get(ArticleIdxFields.ID);
        String title = first.get(ArticleIdxFields.TITLE);
        String content = first.get(ArticleIdxFields.CONTENT);
        String sourceUrl = first.get(ArticleIdxFields.SOURCE_URL);
        List<Map<String, String>> qdrantPayloads = awaitQdrantPayloadsAtLeast(1);

        Assertions.assertAll(
                () -> Assertions.assertEquals(1, docs.size()),
                () -> Assertions.assertEquals(SOURCE_LABEL, first.get(ArticleIdxFields.SOURCE)),
                () -> Assertions.assertNotNull(docId),
                () -> Assertions.assertFalse(docId.isBlank()),
                () -> Assertions.assertNotNull(title),
                () -> Assertions.assertFalse(title.isBlank()),
                () -> Assertions.assertNotNull(content),
                () -> Assertions.assertFalse(content.isBlank()),
                () -> Assertions.assertNotNull(sourceUrl),
                () -> Assertions.assertTrue(sourceUrl.matches("https://news\\.qq\\.com/rain/a/[A-Z0-9]+")),
                () -> Assertions.assertTrue(awaitQdrantPointCountAtLeast(1) > 0),
                () -> Assertions.assertTrue(qdrantPayloads.stream().anyMatch(payload ->
                        docId.equals(payload.get("docId"))
                                && SOURCE_LABEL.equals(payload.get("source"))
                                && sourceUrl.equals(payload.get("sourceUrl")))),
                () -> assertRealTencentArticle(sourceUrl)
        );
    }

    @Test
    @Order(2)
    void queryEndpointReturnsTheIngestedFinanceArticle() throws Exception {
        List<Document> docs = awaitIndexedDocuments();
        Document first = docs.getFirst();
        String title = first.get(ArticleIdxFields.TITLE);
        String sourceUrl = first.get(ArticleIdxFields.SOURCE_URL);

        ResponseEntity<QueryResponse<List<Map<String, String>>>> response = awaitQueryResponse(title, sourceUrl);
        QueryResponse<List<Map<String, String>>> body = response.getBody();

        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.OK, response.getStatusCode()),
                () -> Assertions.assertNotNull(body),
                () -> Assertions.assertTrue(body.isSuccess()),
                () -> Assertions.assertNotNull(body.getData()),
                () -> Assertions.assertFalse(body.getData().isEmpty()),
                () -> Assertions.assertTrue(body.getData().stream().anyMatch(item ->
                        sourceUrl.equals(item.get(ArticleIdxFields.SOURCE_URL))
                                && SOURCE_LABEL.equals(item.get(ArticleIdxFields.SOURCE))))
        );
    }

    @Test
    @Order(3)
    void categoryExpansionAddsDiversityAndRepeatIngestKeepsLuceneAndQdrantCountsStable() throws Exception {
        assertConfiguredCategoryPagesReachable(TASK8_CATEGORY_CONFIGS.subList(1, TASK8_CATEGORY_CONFIGS.size()));

        for (CategoryConfig category : TASK8_CATEGORY_CONFIGS.subList(1, TASK8_CATEGORY_CONFIGS.size())) {
            crawlerService.startTencentNewsCrawler(List.of(category.categoryUrl()), category.sourceLabel(), 1);
        }

        List<Document> docs = awaitIndexedDocuments(TASK8_CATEGORY_CONFIGS.size());
        Set<String> expectedSources = TASK8_CATEGORY_CONFIGS.stream()
                .map(CategoryConfig::sourceLabel)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> docSources = docs.stream()
                .map(doc -> doc.get(ArticleIdxFields.SOURCE))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> docSections = docs.stream()
                .map(doc -> doc.get(ArticleIdxFields.SECTION))
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, String>> qdrantPayloads = awaitQdrantPayloadsForSources(expectedSources);
        Set<String> qdrantSources = qdrantPayloads.stream()
                .map(payload -> payload.get("source"))
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> qdrantSections = qdrantPayloads.stream()
                .map(payload -> payload.get("section"))
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> docIdsBeforeRepeat = docs.stream()
                .map(doc -> doc.get(ArticleIdxFields.ID))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        long qdrantPointCountBeforeRepeat = awaitQdrantPointCountAtLeast(1);

        Assertions.assertAll(
                () -> Assertions.assertEquals(TASK8_CATEGORY_CONFIGS.size(), docs.size()),
                () -> Assertions.assertEquals(expectedSources, docSources),
                () -> Assertions.assertEquals(expectedSources, qdrantSources),
                () -> Assertions.assertTrue(docSections.size() >= 2),
                () -> Assertions.assertTrue(qdrantSections.size() >= 2),
                () -> Assertions.assertTrue(qdrantPayloads.stream().allMatch(payload -> hasText(payload.get("section")))),
                () -> Assertions.assertTrue(qdrantPayloads.stream().allMatch(payload -> hasText(payload.get("sourceUrl"))))
        );

        for (Document doc : docs) {
            String sourceLabel = doc.get(ArticleIdxFields.SOURCE);
            String sourceUrl = doc.get(ArticleIdxFields.SOURCE_URL);
            Assertions.assertAll(
                    () -> Assertions.assertNotNull(sourceLabel),
                    () -> Assertions.assertNotNull(sourceUrl),
                    () -> Assertions.assertTrue(expectedSources.contains(sourceLabel)),
                    () -> Assertions.assertTrue(sourceUrl.matches("https://news\\.qq\\.com/rain/a/[A-Z0-9]+")),
                    () -> assertRealTencentArticle(sourceUrl, categoryBySourceLabel(sourceLabel).categoryUrl())
            );
        }

        for (Document doc : docs) {
            crawlerService.startTencentNewsCrawler(
                    List.of(doc.get(ArticleIdxFields.SOURCE_URL)),
                    doc.get(ArticleIdxFields.SOURCE),
                    1
            );
        }

        Thread.sleep(3000);

        List<Document> docsAfterRepeat = awaitIndexedDocuments(TASK8_CATEGORY_CONFIGS.size());
        Set<String> docIdsAfterRepeat = docsAfterRepeat.stream()
                .map(doc -> doc.get(ArticleIdxFields.ID))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        long qdrantPointCountAfterRepeat = awaitQdrantPointCountAtLeast(qdrantPointCountBeforeRepeat);

        Assertions.assertAll(
                () -> Assertions.assertEquals(docs.size(), docsAfterRepeat.size()),
                () -> Assertions.assertEquals(docIdsBeforeRepeat, docIdsAfterRepeat),
                () -> Assertions.assertEquals(qdrantPointCountBeforeRepeat, qdrantPointCountAfterRepeat)
        );
    }

    @AfterAll
    static void cleanWorkspaceAndCollection() throws Exception {
        FileUtils.deleteSubDirs(TEST_HOME);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:6333/collections/" + TEST_COLLECTION))
                .timeout(Duration.ofSeconds(5))
                .DELETE()
                .build();
        client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private List<Document> awaitIndexedDocuments() throws Exception {
        return awaitIndexedDocuments(1);
    }

    private List<Document> awaitIndexedDocuments(int expectedDocCount) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            List<Document> docs = readIndexedDocuments();
            if (docs.size() >= expectedDocCount) {
                return docs;
            }
            Thread.sleep(1000);
        }
        Assertions.fail("Timed out waiting for Tencent crawl to index documents");
        return List.of();
    }

    private List<Document> readIndexedDocuments() throws Exception {
        Path idxPath = Path.of(TEST_HOME, "idx");
        if (!java.nio.file.Files.exists(idxPath)) {
            return List.of();
        }
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(idxPath))) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), 10);
            List<Document> docs = new ArrayList<>(topDocs.scoreDocs.length);
            for (var scoreDoc : topDocs.scoreDocs) {
                docs.add(searcher.doc(scoreDoc.doc));
            }
            return docs;
        }
        catch (org.apache.lucene.index.IndexNotFoundException e) {
            return List.of();
        }
    }

    private void assertConfiguredCategoryPageReachable() {
        String page = HTTP_UTILS.getPage(CATEGORY_URL, null);
        Assertions.assertAll(
                () -> Assertions.assertNotNull(page),
                () -> Assertions.assertFalse(page.isBlank()),
                () -> Assertions.assertFalse(page.contains("页面找不到")),
                () -> Assertions.assertFalse(page.contains("页面不存在")),
                () -> Assertions.assertFalse(page.contains("<title>404")),
                () -> Assertions.assertTrue(page.contains("news.qq.com") || page.contains("channelInfo") || page.contains("window.__INITIAL_STATE__"),
                        () -> "Expected a live Tencent category page for " + CATEGORY_URL)
        );
    }

    private void assertConfiguredCategoryPagesReachable(List<CategoryConfig> categories) {
        for (CategoryConfig category : categories) {
            String page = HTTP_UTILS.getPage(category.categoryUrl(), null);
            Assertions.assertAll(
                    () -> Assertions.assertNotNull(page),
                    () -> Assertions.assertFalse(page.isBlank()),
                    () -> Assertions.assertFalse(page.contains("页面找不到")),
                    () -> Assertions.assertFalse(page.contains("页面不存在")),
                    () -> Assertions.assertFalse(page.contains("<title>404")),
                    () -> Assertions.assertTrue(page.contains("news.qq.com") || page.contains("channelInfo") || page.contains("window.__INITIAL_STATE__"),
                            () -> "Expected a live Tencent category page for " + category.categoryUrl())
            );
        }
    }

    private void assertRealTencentArticle(String sourceUrl) {
        assertRealTencentArticle(sourceUrl, CATEGORY_URL);
    }

    private void assertRealTencentArticle(String sourceUrl, String refererUrl) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", refererUrl);
        String page = HTTP_UTILS.getPage(sourceUrl, headers);
        Assertions.assertAll(
                () -> Assertions.assertNotNull(page),
                () -> Assertions.assertTrue(page.contains("window.DATA")),
                () -> Assertions.assertFalse(page.contains("页面找不到")),
                () -> Assertions.assertFalse(page.contains("页面不存在")),
                () -> Assertions.assertFalse(page.contains("<title>404"))
        );
    }

    private long awaitQdrantPointCountAtLeast(long minimumPointCount) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            long pointCount = readQdrantPointCount();
            if (pointCount >= minimumPointCount) {
                return pointCount;
            }
            Thread.sleep(1000);
        }
        return 0;
    }

    private long readQdrantPointCount() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:6333/collections/" + TEST_COLLECTION))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return 0;
        }
        JsonNode root = objectMapper.readTree(response.body());
        return root.path("result").path("points_count").asLong(0);
    }

    private List<Map<String, String>> readQdrantPayloads() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:6333/collections/" + TEST_COLLECTION + "/points/scroll"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"limit\":" + QDRANT_SCROLL_LIMIT + ",\"with_payload\":true,\"with_vector\":false}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode points = root.path("result").path("points");
        List<Map<String, String>> payloads = new ArrayList<>();
        for (JsonNode point : points) {
            JsonNode payloadNode = point.path("payload");
            Map<String, String> payload = new LinkedHashMap<>();
            payloadNode.fields().forEachRemaining(entry -> payload.put(entry.getKey(), entry.getValue().asText(null)));
            payloads.add(payload);
        }
        return payloads;
    }

    private List<Map<String, String>> awaitQdrantPayloadsAtLeast(int minimumPayloadCount) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            List<Map<String, String>> payloads = readQdrantPayloads();
            if (payloads.size() >= minimumPayloadCount) {
                return payloads;
            }
            Thread.sleep(1000);
        }
        return List.of();
    }

    private List<Map<String, String>> awaitQdrantPayloadsForSources(Set<String> expectedSources) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            List<Map<String, String>> payloads = readQdrantPayloads();
            Set<String> actualSources = payloads.stream()
                    .map(payload -> payload.get("source"))
                    .filter(this::hasText)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (actualSources.containsAll(expectedSources)) {
                return payloads;
            }
            Thread.sleep(1000);
        }
        return List.of();
    }

    private ResponseEntity<QueryResponse<List<Map<String, String>>>> awaitQueryResponse(String title, String sourceUrl)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            ResponseEntity<QueryResponse<List<Map<String, String>>>> response = restTemplate.exchange(
                    "/query/kw?kw={kw}&pageNo=1&pageSize=5",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {
                    },
                    title
            );
            QueryResponse<List<Map<String, String>>> body = response.getBody();
            if (response.getStatusCode() == HttpStatus.OK
                    && body != null
                    && body.isSuccess()
                    && body.getData() != null
                    && body.getData().stream().anyMatch(item -> sourceUrl.equals(item.get(ArticleIdxFields.SOURCE_URL)))) {
                return response;
            }
            Thread.sleep(1000);
        }
        Assertions.fail("Timed out waiting for /query/kw to return the ingested Tencent finance article");
        return null;
    }

    private CategoryConfig categoryBySourceLabel(String sourceLabel) {
        return TASK8_CATEGORY_CONFIGS.stream()
                .filter(category -> category.sourceLabel().equals(sourceLabel))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Tencent source label: " + sourceLabel));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record CategoryConfig(String name, String categoryUrl, String sourceLabel) {
    }

    @TestConfiguration
    static class DeterministicLiveVectorConfiguration {

        @Bean
        @Primary
        ProviderStatusService providerStatusService(AiProperties aiProperties, ObjectMapper objectMapper) {
            return new ProviderStatusService(aiProperties, objectMapper) {
                @Override
                public ProviderStatusSnapshot snapshot() {
                    return DemoFixtureSupport.availableSnapshot();
                }
            };
        }

        @Bean
        @Primary
        ArticleEmbeddingService articleEmbeddingService(ArticleChunkingService articleChunkingService,
                                                       ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModelProvider,
                                                       AiFallbackService aiFallbackService,
                                                       AiProperties aiProperties) {
            return new ArticleEmbeddingService(articleChunkingService, embeddingModelProvider, aiFallbackService, aiProperties) {
                @Override
                public List<ArticleChunkEmbedding> generateEmbeddings(Article article) {
                    return articleChunkingService.chunk(article).stream()
                            .map(this::toEmbedding)
                            .toList();
                }

                private ArticleChunkEmbedding toEmbedding(ArticleChunkMetadata metadata) {
                    metadata.setEmbeddingModel("live-category-test");
                    float[] vector = new float[]{
                            metadata.getChunkIndex() + 1.0f,
                            metadata.getCharCount() / 100.0f,
                            (Math.abs(metadata.getChunkId().hashCode()) % 1000) / 100.0f
                    };
                    return new ArticleChunkEmbedding(metadata, vector);
                }
            };
        }
    }
}
