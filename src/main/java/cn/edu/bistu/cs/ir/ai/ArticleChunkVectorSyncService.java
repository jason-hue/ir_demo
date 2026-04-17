package cn.edu.bistu.cs.ir.ai;

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

/**
 * 将文章分块向量以幂等方式同步到Qdrant。
 */
@Service
public class ArticleChunkVectorSyncService {

    static final String CONTENT_FIELD_NAME = "doc_content";

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

    public void syncArticle(Article article) {
        if (article == null) {
            return;
        }
        article.ensureCanonicalIdentity();
        List<ArticleChunkEmbedding> embeddings = articleEmbeddingService.generateEmbeddings(article);
        syncEmbeddings(article, embeddings);
    }

    void syncEmbeddings(Article article, List<ArticleChunkEmbedding> embeddings) {
        if (article == null || embeddings == null) {
            return;
        }
        article.ensureCanonicalIdentity();
        if (StringUtil.isEmpty(article.getDocId())) {
            throw new IllegalArgumentException("article.docId不可以为空");
        }

        QdrantClient qdrantClient = qdrantClientProvider.getIfAvailable();
        if (qdrantClient == null) {
            return;
        }

        try {
            ensureCollectionExistsForRewrite(qdrantClient, embeddings);
            UpdateResult deleteResult = qdrantClient.deleteAsync(aiProperties.getQdrant().getCollectionName(),
                    docIdFilter(article.getDocId())).get();
            if (deleteResult.getStatus() != UpdateStatus.Completed) {
                log.warn("文章[{}]旧分块向量删除未完成，状态为[{}]，跳过重写以避免残留。",
                        article.getDocId(), deleteResult.getStatus());
                return;
            }
            if (embeddings.isEmpty()) {
                return;
            }
            UpdateResult result = qdrantClient.upsertAsync(aiProperties.getQdrant().getCollectionName(),
                    toPoints(article, embeddings)).get();
            if (result.getStatus() != UpdateStatus.Completed) {
                log.warn("文章[{}]分块向量写入Qdrant未完成，状态为[{}]。", article.getDocId(), result.getStatus());
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("文章[{}]分块向量写入Qdrant被中断，保留词法路径。", article.getDocId());
        }
        catch (ExecutionException | RuntimeException e) {
            log.warn("文章[{}]分块向量写入Qdrant失败，保留词法路径: {}", article.getDocId(), e.getMessage());
        }
    }

    static String pointIdForChunk(String chunkId) {
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void ensureCollectionExistsForRewrite(QdrantClient qdrantClient, List<ArticleChunkEmbedding> embeddings)
            throws ExecutionException, InterruptedException {
        String collectionName = aiProperties.getQdrant().getCollectionName();
        if (qdrantClient.collectionExistsAsync(collectionName).get()) {
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
            if (qdrantClient.collectionExistsAsync(collectionName).get()) {
                return;
            }
            qdrantClient.createCollectionAsync(collectionName,
                    VectorParams.newBuilder()
                            .setDistance(Distance.Cosine)
                            .setSize(dimensions)
                            .build())
                    .get();
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
