package cn.edu.bistu.cs.ir.ai;

import com.google.common.util.concurrent.ListenableFuture;
import cn.edu.bistu.cs.ir.config.AiProperties;
import cn.edu.bistu.cs.ir.model.Article;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.utils.StringUtil;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.Condition;
import io.qdrant.client.grpc.Points.FieldCondition;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.Match;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.Points.UpdateStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.EmbeddingUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 将文章分块向量以幂等方式同步到Qdrant。
 */
@Service
public class ArticleChunkVectorSyncService {

    static final String CONTENT_FIELD_NAME = "doc_content";

    static final String QDRANT_TIMEOUT_CODE = "QDRANT_TIMEOUT";

    private static final long VECTOR_SYNC_TIMEOUT_MILLIS = 120_000L;

    private static final Logger log = LoggerFactory.getLogger(ArticleChunkVectorSyncService.class);

    private final ArticleEmbeddingService articleEmbeddingService;

    private final ObjectProvider<QdrantClient> qdrantClientProvider;

    private final AiProperties aiProperties;

    private final Object collectionCreationMonitor = new Object();

    public ArticleChunkVectorSyncService(ArticleEmbeddingService articleEmbeddingService,
                                         ObjectProvider<QdrantClient> qdrantClientProvider,
                                         AiProperties aiProperties) {
        this.articleEmbeddingService = articleEmbeddingService;
        this.qdrantClientProvider = qdrantClientProvider;
        this.aiProperties = aiProperties;
    }

    public SyncResult syncArticle(Article article) {
        if (article == null) {
            return SyncResult.succeeded();
        }
        try {
            article.ensureCanonicalIdentity();
            List<ArticleChunkEmbedding> embeddings = articleEmbeddingService.generateEmbeddings(article);
            return syncEmbeddings(article, embeddings);
        }
        catch (RuntimeException e) {
            String detail = String.format("文章[%s]分块向量生成失败，保留词法路径: %s",
                    article.getDocId(), e.getMessage());
            log.warn(detail);
            return SyncResult.failed(detail);
        }
    }

    SyncResult syncEmbeddings(Article article, List<ArticleChunkEmbedding> embeddings) {
        if (article == null || embeddings == null) {
            return SyncResult.succeeded();
        }
        article.ensureCanonicalIdentity();
        if (StringUtil.isEmpty(article.getDocId())) {
            throw new IllegalArgumentException("article.docId不可以为空");
        }

        QdrantClient qdrantClient = qdrantClientProvider.getIfAvailable();
        if (qdrantClient == null) {
            return SyncResult.succeeded();
        }

        try {
            ensureCollectionExistsForRewrite(qdrantClient, embeddings);
            UpdateResult deleteResult = waitForQdrantOperation(
                    qdrantClient.deleteAsync(aiProperties.getQdrant().getCollectionName(),
                            docIdFilter(article.getDocId())),
                    "delete",
                    article.getDocId());
            if (deleteResult.getStatus() != UpdateStatus.Completed) {
                String detail = String.format("文章[%s]旧分块向量删除未完成，状态为[%s]，保留词法路径。",
                        article.getDocId(), deleteResult.getStatus());
                log.warn(detail);
                return SyncResult.failed(detail);
            }
            if (embeddings.isEmpty()) {
                return SyncResult.succeeded();
            }
            UpdateResult result = waitForQdrantOperation(
                    qdrantClient.upsertAsync(aiProperties.getQdrant().getCollectionName(),
                            toPoints(article, embeddings)),
                    "upsert",
                    article.getDocId());
            if (result.getStatus() != UpdateStatus.Completed) {
                String detail = String.format("文章[%s]分块向量写入Qdrant未完成，状态为[%s]，保留词法路径。",
                        article.getDocId(), result.getStatus());
                log.warn(detail);
                return SyncResult.failed(detail);
            }
            return SyncResult.succeeded();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String detail = String.format("文章[%s]分块向量写入Qdrant被中断，保留词法路径。", article.getDocId());
            log.warn(detail);
            return SyncResult.failed(detail);
        }
        catch (QdrantOperationTimeoutException e) {
            log.warn(e.getMessage(), e);
            return SyncResult.failed(e.getMessage());
        }
        catch (ExecutionException | RuntimeException e) {
            String detail = String.format("文章[%s]分块向量写入Qdrant失败，保留词法路径: %s",
                    article.getDocId(), e.getMessage());
            log.warn(detail);
            return SyncResult.failed(detail);
        }
    }

    public record SyncResult(boolean success, String detail) {

        public static SyncResult succeeded() {
            return new SyncResult(true, null);
        }

        public static SyncResult failed(String detail) {
            return new SyncResult(false, detail);
        }
    }

    static String pointIdForChunk(String chunkId) {
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void ensureCollectionExistsForRewrite(QdrantClient qdrantClient, List<ArticleChunkEmbedding> embeddings)
            throws ExecutionException, InterruptedException {
        String collectionName = aiProperties.getQdrant().getCollectionName();
        if (waitForQdrantOperation(qdrantClient.collectionExistsAsync(collectionName), "collection_exists", null)) {
            return;
        }
        if (embeddings.isEmpty()) {
            return;
        }

        int dimensions = embeddings.stream()
                .map(ArticleChunkEmbedding::vector)
                .filter(vector -> vector != null && vector.length > 0)
                .mapToInt(vector -> vector.length)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("向量维度不可以为空"));

        synchronized (collectionCreationMonitor) {
            if (waitForQdrantOperation(qdrantClient.collectionExistsAsync(collectionName),
                    "collection_exists",
                    null)) {
                return;
            }
            waitForQdrantOperation(qdrantClient.createCollectionAsync(collectionName,
                            VectorParams.newBuilder()
                                    .setDistance(Distance.Cosine)
                                    .setSize(dimensions)
                                    .build()),
                    "collection_create",
                    null);
        }
    }

    long vectorSyncTimeoutMillis() {
        return VECTOR_SYNC_TIMEOUT_MILLIS;
    }

    private <T> T waitForQdrantOperation(ListenableFuture<T> future, String operation, String docId)
            throws ExecutionException, InterruptedException {
        try {
            return future.get(vectorSyncTimeoutMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            future.cancel(true);
            throw new QdrantOperationTimeoutException(timeoutDetail(operation, docId), e);
        }
    }

    private String timeoutDetail(String operation, String docId) {
        String normalizedDocId = StringUtil.isEmpty(docId) ? "n/a" : docId;
        return String.format("[%s] operation=%s timeoutMs=%d docId=%s，向量同步等待Qdrant超时，保留词法路径。",
                QDRANT_TIMEOUT_CODE,
                operation,
                vectorSyncTimeoutMillis(),
                normalizedDocId);
    }

    private static final class QdrantOperationTimeoutException extends RuntimeException {

        private QdrantOperationTimeoutException(String message, TimeoutException cause) {
            super(message, cause);
        }
    }

    private Filter docIdFilter(String docId) {
        return Filter.newBuilder()
                .addMust(Condition.newBuilder()
                        .setField(FieldCondition.newBuilder()
                                .setKey("docId")
                                .setMatch(Match.newBuilder()
                                        .setKeyword(docId)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private List<PointStruct> toPoints(Article article, List<ArticleChunkEmbedding> embeddings) {
        List<PointStruct> points = new ArrayList<>(embeddings.size());
        for (ArticleChunkEmbedding embedding : embeddings) {
            ArticleChunkMetadata metadata = embedding.metadata();
            float[] vector = embedding.vector();
            if (vector == null || vector.length == 0) {
                continue;
            }
            points.add(PointStruct.newBuilder()
                    .setId(PointIdFactory.id(UUID.fromString(pointIdForChunk(metadata.getChunkId()))))
                    .setVectors(VectorsFactory.vectors(EmbeddingUtils.toList(vector)))
                    .putAllPayload(toPayload(article, metadata))
                    .build());
        }
        return List.copyOf(points);
    }

    private Map<String, Value> toPayload(Article article, ArticleChunkMetadata metadata) {
        Map<String, Value> payload = new LinkedHashMap<>();
        putString(payload, "docId", metadata.getDocId());
        putString(payload, "chunkId", metadata.getChunkId());
        putString(payload, "sourceUrl", article.getSourceUrl());
        putString(payload, "title", article.getTitle());
        putInstant(payload, "publishTime", article.getPublishTime());
        putString(payload, "source", article.getSource());
        putString(payload, "section", article.getSection());
        payload.put("chunkIndex", ValueFactory.value(metadata.getChunkIndex()));
        putString(payload, "embeddingModel", metadata.getEmbeddingModel());
        payload.put("charCount", ValueFactory.value(metadata.getCharCount()));
        putString(payload, CONTENT_FIELD_NAME, metadata.getChunkText());
        return payload;
    }

    private void putString(Map<String, Value> payload, String key, String value) {
        if (value != null) {
            payload.put(key, ValueFactory.value(value));
        }
    }

    private void putInstant(Map<String, Value> payload, String key, Instant value) {
        if (value != null) {
            payload.put(key, ValueFactory.value(value.toString()));
        }
    }
}
