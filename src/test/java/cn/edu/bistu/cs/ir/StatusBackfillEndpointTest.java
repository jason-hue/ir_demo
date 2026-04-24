package cn.edu.bistu.cs.ir;

import cn.edu.bistu.cs.ir.ai.LuceneVectorBackfillService;
import cn.edu.bistu.cs.ir.controller.dto.VectorBackfillResult;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "irdemo.ai.ollama.enabled=false",
        "irdemo.ai.qdrant.enabled=false"
})
class StatusBackfillEndpointTest {

    private static final Path TEST_HOME = createTestHome();

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private LuceneVectorBackfillService luceneVectorBackfillService;

    @DynamicPropertySource
    static void registerWorkDirs(DynamicPropertyRegistry registry) {
        registry.add("irdemo.dir.home", () -> TEST_HOME.toString());
        registry.add("irdemo.dir.idx", () -> TEST_HOME.resolve("idx").toString());
        registry.add("irdemo.dir.crawler", () -> TEST_HOME.resolve("crawler").toString());
    }

    @Test
    void backfillEndpointReturnsSummaryPayload() {
        when(luceneVectorBackfillService.backfillExistingArticles()).thenReturn(new VectorBackfillResult(
                Instant.parse("2026-04-25T00:00:00Z"),
                Instant.parse("2026-04-25T00:00:01Z"),
                4,
                4,
                3,
                1,
                List.of("doc-4 failed")));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<QueryResponse<VectorBackfillResult>> response = restTemplate.exchange(
                "/status/production/backfill-vectors",
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                new ParameterizedTypeReference<>() {
                });

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        QueryResponse<VectorBackfillResult> body = response.getBody();
        Assertions.assertNotNull(body);
        Assertions.assertTrue(body.isSuccess());
        Assertions.assertNotNull(body.getData());
        Assertions.assertEquals(4, body.getData().scanned());
        Assertions.assertEquals(1, body.getData().failed());
    }

    private static Path createTestHome() {
        try {
            return Files.createTempDirectory("ir-demo-status-backfill-");
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanWorkspace() throws IOException {
        if (!Files.exists(TEST_HOME)) {
            return;
        }
        try (var paths = Files.walk(TEST_HOME)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        }
                        catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }
}
