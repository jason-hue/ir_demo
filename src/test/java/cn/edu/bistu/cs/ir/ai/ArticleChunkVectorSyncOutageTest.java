package cn.edu.bistu.cs.ir.ai;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import cn.edu.bistu.cs.ir.index.ArticleIdxFields;
import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.model.Blog;
import cn.edu.bistu.cs.ir.utils.FileUtils;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.Points.UpdateStatus;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import cn.edu.bistu.cs.ir.config.AiProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import us.codecraft.webmagic.selector.Html;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "irdemo.ai.ollama.enabled=false",
        "irdemo.ai.qdrant.enabled=true",
        "irdemo.ai.qdrant.host=127.0.0.1",
        "irdemo.ai.qdrant.http-port=1",
        "irdemo.ai.qdrant.grpc-port=1",
        "irdemo.ai.qdrant.collection-name=news_article_chunks",
        "irdemo.ai.provider-status.connect-timeout=100ms",
        "irdemo.ai.provider-status.read-timeout=100ms",
        "irdemo.dir.home=workspace/test-article-chunk-vector-outage",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class ArticleChunkVectorSyncOutageTest {

    private static final String ARTICLE_FIXTURE = "fixtures/tencent/news/article-page.html";

    private static final String TEST_HOME = "workspace/test-article-chunk-vector-outage";

    @Autowired
    private ArticleChunkVectorSyncService articleChunkVectorSyncService;

    @Autowired
    private ArticleChunkingService articleChunkingService;

    @Autowired
    private IdxService idxService;

    @Test
    void qdrantOutageDoesNotBreakLexicalIndexing() throws Exception {
        Blog article = buildLongFixtureArticle();
        List<ArticleChunkEmbedding> embeddings = buildEmbeddings(article);

        Document document = new Document();
        document.add(new StringField(ArticleIdxFields.ID, article.getDocId(), Field.Store.YES));
        document.add(new TextField(ArticleIdxFields.TITLE, article.getTitle(), Field.Store.YES));
        document.add(new TextField(ArticleIdxFields.CONTENT, article.getBody(), Field.Store.YES));
        boolean indexed = idxService.addDocument(ArticleIdxFields.ID, article.getDocId(), document);

        Assertions.assertDoesNotThrow(() -> articleChunkVectorSyncService.syncEmbeddings(article, embeddings));

        List<Document> hits = idxService.queryByKw("新闻检索助手");
        Assertions.assertAll(
                () -> Assertions.assertTrue(indexed),
                () -> Assertions.assertFalse(hits.isEmpty()),
                () -> Assertions.assertEquals(article.getDocId(), hits.getFirst().get(ArticleIdxFields.ID))
        );
    }

    @Test
    void collectionExistsTimeoutReturnsBoundedFailureDetail() throws Exception {
        Blog article = buildLongFixtureArticle();
        List<ArticleChunkEmbedding> embeddings = buildEmbeddings(article);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        SettableFuture<Boolean> collectionExistsFuture = neverCompletingFuture();
        when(qdrantClient.collectionExistsAsync("news_article_chunks")).thenReturn(collectionExistsFuture);

        ArticleChunkVectorSyncService service = buildTimeoutScopedService(qdrantClient, 25L);

        ArticleChunkVectorSyncService.SyncResult result = Assertions.assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> service.syncEmbeddings(article, embeddings));

        Assertions.assertAll(
                () -> Assertions.assertFalse(result.success()),
                () -> Assertions.assertTrue(result.detail().contains(ArticleChunkVectorSyncService.QDRANT_TIMEOUT_CODE)),
                () -> Assertions.assertTrue(result.detail().contains("operation=collection_exists")),
                () -> Assertions.assertTrue(collectionExistsFuture.isCancelled())
        );
    }

    @Test
    void collectionCreateTimeoutReturnsBoundedFailureDetail() throws Exception {
        Blog article = buildLongFixtureArticle();
        List<ArticleChunkEmbedding> embeddings = buildEmbeddings(article);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        SettableFuture<CollectionOperationResponse> collectionCreateFuture = neverCompletingFuture();
        when(qdrantClient.collectionExistsAsync("news_article_chunks"))
                .thenReturn(Futures.immediateFuture(false), Futures.immediateFuture(false));
        when(qdrantClient.createCollectionAsync(anyString(), any(VectorParams.class))).thenReturn(collectionCreateFuture);

        ArticleChunkVectorSyncService service = buildTimeoutScopedService(qdrantClient, 25L);

        ArticleChunkVectorSyncService.SyncResult result = Assertions.assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> service.syncEmbeddings(article, embeddings));

        Assertions.assertAll(
                () -> Assertions.assertFalse(result.success()),
                () -> Assertions.assertTrue(result.detail().contains(ArticleChunkVectorSyncService.QDRANT_TIMEOUT_CODE)),
                () -> Assertions.assertTrue(result.detail().contains("operation=collection_create")),
                () -> Assertions.assertTrue(collectionCreateFuture.isCancelled())
        );
    }

    @Test
    void deleteTimeoutReturnsBoundedFailureDetail() throws Exception {
        Blog article = buildLongFixtureArticle();
        List<ArticleChunkEmbedding> embeddings = buildEmbeddings(article);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        SettableFuture<UpdateResult> deleteFuture = neverCompletingFuture();
        when(qdrantClient.collectionExistsAsync("news_article_chunks")).thenReturn(Futures.immediateFuture(true));
        when(qdrantClient.deleteAsync(anyString(), any(Filter.class))).thenReturn(deleteFuture);

        ArticleChunkVectorSyncService service = buildTimeoutScopedService(qdrantClient, 25L);

        ArticleChunkVectorSyncService.SyncResult result = Assertions.assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> service.syncEmbeddings(article, embeddings));

        Assertions.assertAll(
                () -> Assertions.assertFalse(result.success()),
                () -> Assertions.assertTrue(result.detail().contains(ArticleChunkVectorSyncService.QDRANT_TIMEOUT_CODE)),
                () -> Assertions.assertTrue(result.detail().contains("operation=delete")),
                () -> Assertions.assertTrue(result.detail().contains("docId=" + article.getDocId())),
                () -> Assertions.assertTrue(deleteFuture.isCancelled())
        );
    }

    @Test
    void upsertTimeoutReturnsBoundedFailureDetail() throws Exception {
        Blog article = buildLongFixtureArticle();
        List<ArticleChunkEmbedding> embeddings = buildEmbeddings(article);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        SettableFuture<UpdateResult> upsertFuture = neverCompletingFuture();
        when(qdrantClient.collectionExistsAsync("news_article_chunks")).thenReturn(Futures.immediateFuture(true));
        when(qdrantClient.deleteAsync(anyString(), any(Filter.class))).thenReturn(Futures.immediateFuture(completedUpdateResult()));
        when(qdrantClient.upsertAsync(anyString(), anyList())).thenReturn(upsertFuture);

        ArticleChunkVectorSyncService service = buildTimeoutScopedService(qdrantClient, 25L);

        ArticleChunkVectorSyncService.SyncResult result = Assertions.assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> service.syncEmbeddings(article, embeddings));

        Assertions.assertAll(
                () -> Assertions.assertFalse(result.success()),
                () -> Assertions.assertTrue(result.detail().contains(ArticleChunkVectorSyncService.QDRANT_TIMEOUT_CODE)),
                () -> Assertions.assertTrue(result.detail().contains("operation=upsert")),
                () -> Assertions.assertTrue(result.detail().contains("docId=" + article.getDocId())),
                () -> Assertions.assertTrue(upsertFuture.isCancelled())
        );
    }

    @Test
    void embeddingGenerationTimeoutReturnsBoundedFailureDetail() throws Exception {
        Blog article = buildLongFixtureArticle();
        List<ArticleChunkMetadata> chunks = articleChunkingService.chunk(article);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embedForResponse(anyList())).thenAnswer(invocation -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("embedding timeout test interrupted", e);
            }
            throw new AssertionError("Embedding timeout test should have completed before model returned");
        });

        ArticleChunkVectorSyncService service = buildEmbeddingTimeoutScopedService(article, chunks, embeddingModel, 25L);

        ArticleChunkVectorSyncService.SyncResult result = Assertions.assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> service.syncArticle(article));

        Assertions.assertAll(
                () -> Assertions.assertFalse(result.success()),
                () -> Assertions.assertTrue(result.detail().contains(ArticleEmbeddingService.EMBEDDING_TIMEOUT_CODE)),
                () -> Assertions.assertTrue(result.detail().contains("operation=embed")),
                () -> Assertions.assertTrue(result.detail().contains("docId=" + article.getDocId()))
        );
    }

    @AfterAll
    static void cleanWorkspace() {
        FileUtils.deleteSubDirs(TEST_HOME);
    }

    private List<ArticleChunkEmbedding> buildEmbeddings(Blog article) {
        return articleChunkingService.chunk(article).stream()
                .map(this::toEmbedding)
                .toList();
    }

    private ArticleChunkVectorSyncService buildTimeoutScopedService(QdrantClient qdrantClient, long timeoutMillis) {
        @SuppressWarnings("unchecked")
        ObjectProvider<QdrantClient> qdrantClientProvider = mock(ObjectProvider.class);
        when(qdrantClientProvider.getIfAvailable()).thenReturn(qdrantClient);

        AiProperties aiProperties = new AiProperties();
        aiProperties.getQdrant().setCollectionName("news_article_chunks");

        return new ArticleChunkVectorSyncService(mock(ArticleEmbeddingService.class), qdrantClientProvider, aiProperties) {
            @Override
            long vectorSyncTimeoutMillis() {
                return timeoutMillis;
            }
        };
    }

    private ArticleChunkVectorSyncService buildEmbeddingTimeoutScopedService(Blog article,
                                                                             List<ArticleChunkMetadata> chunks,
                                                                             EmbeddingModel embeddingModel,
                                                                             long timeoutMillis) {
        ArticleChunkingService chunkingService = mock(ArticleChunkingService.class);
        when(chunkingService.chunk(article)).thenReturn(chunks);

        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> embeddingModelProvider = mock(ObjectProvider.class);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);

        AiFallbackService aiFallbackService = mock(AiFallbackService.class);
        when(aiFallbackService.isLexicalOnlyMode()).thenReturn(false);

        AiProperties aiProperties = new AiProperties();
        aiProperties.getOllama().setEmbeddingModel("test-embed");

        ArticleEmbeddingService articleEmbeddingService = new ArticleEmbeddingService(
                chunkingService,
                embeddingModelProvider,
                aiFallbackService,
                aiProperties) {
            @Override
            long embeddingTimeoutMillis() {
                return timeoutMillis;
            }
        };

        @SuppressWarnings("unchecked")
        ObjectProvider<QdrantClient> qdrantClientProvider = mock(ObjectProvider.class);
        when(qdrantClientProvider.getIfAvailable()).thenReturn(null);

        return new ArticleChunkVectorSyncService(articleEmbeddingService, qdrantClientProvider, aiProperties);
    }

    private UpdateResult completedUpdateResult() {
        return UpdateResult.newBuilder()
                .setStatus(UpdateStatus.Completed)
                .build();
    }

    private <T> SettableFuture<T> neverCompletingFuture() {
        return SettableFuture.create();
    }

    private ArticleChunkEmbedding toEmbedding(ArticleChunkMetadata metadata) {
        metadata.setEmbeddingModel("test-embed");
        float[] vector = new float[]{
                metadata.getChunkIndex() + 1.0f,
                metadata.getCharCount() / 100.0f,
                (metadata.getChunkIndex() + metadata.getCharCount()) / 10.0f
        };
        return new ArticleChunkEmbedding(metadata, vector);
    }

    private Blog buildLongFixtureArticle() throws Exception {
        String rawHtml = Files.readString(new ClassPathResource(ARTICLE_FIXTURE).getFile().toPath());
        Html html = new Html(rawHtml);
        String baseBody = String.join("\n", html.xpath("//div[contains(@class,'content-article')]//p/allText()").all());

        Blog article = new Blog();
        article.setSource("tencent-news");
        article.setSourceUrl("https://news.qq.com/rain/a/20240318A01AB000");
        article.setTitle("腾讯新闻推出新闻检索助手试点");
        article.setBody((baseBody + "\n").repeat(20));
        article.setPublishTime(Instant.parse("2024-03-18T09:30:00Z"));
        article.setCrawlTime(Instant.parse("2024-03-18T10:00:00Z"));
        article.setSection("tech");
        article.setAuthor("腾讯教育");
        article.setByline("腾讯教育");
        article.ensureDocId();
        return article;
    }
}
