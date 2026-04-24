package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import cn.edu.bistu.cs.ir.model.Article;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.utils.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 使用Spring AI EmbeddingModel为文章分块生成向量。
 */
@Service
public class ArticleEmbeddingService {

    static final String EMBEDDING_TIMEOUT_CODE = "EMBEDDING_TIMEOUT";

    private static final long EMBEDDING_TIMEOUT_MILLIS = 120_000L;

    private static final Logger log = LoggerFactory.getLogger(ArticleEmbeddingService.class);

    private final ArticleChunkingService articleChunkingService;

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final AiFallbackService aiFallbackService;

    private final AiProperties aiProperties;

    private final ExecutorService embeddingCallExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ArticleEmbeddingService(ArticleChunkingService articleChunkingService,
                                   ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                   AiFallbackService aiFallbackService,
                                   AiProperties aiProperties) {
        this.articleChunkingService = articleChunkingService;
        this.embeddingModelProvider = embeddingModelProvider;
        this.aiFallbackService = aiFallbackService;
        this.aiProperties = aiProperties;
    }

    public List<ArticleChunkEmbedding> generateEmbeddings(Article article) {
        return generateEmbeddings(article, false);
    }

    public List<ArticleChunkEmbedding> generateEmbeddingsForBackfill(Article article) {
        return generateEmbeddings(article, true);
    }

    private List<ArticleChunkEmbedding> generateEmbeddings(Article article, boolean ignoreLexicalOnlyMode) {
        List<ArticleChunkMetadata> chunks = articleChunkingService.chunk(article);
        if (chunks.isEmpty()) {
            return List.of();
        }
        if (!ignoreLexicalOnlyMode && aiFallbackService.isLexicalOnlyMode()) {
            return List.of();
        }

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            return List.of();
        }

        try {
            return embedChunksSequentially(article, embeddingModel, chunks);
        }
        catch (EmbeddingGenerationTimeoutException e) {
            throw e;
        }
        catch (RuntimeException e) {
            log.warn("文章[{}]生成向量失败，保留词法路径并跳过向量流程: {}", article.getDocId(), e.getMessage());
            return List.of();
        }
    }

    @PreDestroy
    void closeExecutor() {
        embeddingCallExecutor.shutdownNow();
    }

    long embeddingTimeoutMillis() {
        return EMBEDDING_TIMEOUT_MILLIS;
    }

    private List<ArticleChunkEmbedding> embedChunksSequentially(Article article,
                                                                EmbeddingModel embeddingModel,
                                                                List<ArticleChunkMetadata> chunks) {
        List<ArticleChunkEmbedding> embeddings = new ArrayList<>(chunks.size());
        String embeddingModelName = null;
        for (ArticleChunkMetadata chunk : chunks) {
            EmbeddingResponse response = embedForResponseWithTimeout(article, embeddingModel, List.of(chunk.getChunkText()));
            List<Embedding> results = response.getResults();
            if (results.size() != 1) {
                log.warn("文章[{}]分块向量数量[{}]与单次分块请求数量[1]不一致，跳过向量结果。",
                        article.getDocId(), results.size());
                return List.of();
            }

            if (embeddingModelName == null) {
                embeddingModelName = resolveEmbeddingModelName(response);
            }
            else {
                String batchModelName = resolveEmbeddingModelName(response);
                if (!Objects.equals(embeddingModelName, batchModelName)) {
                    log.warn("文章[{}]分块向量模型名称前后不一致[{} -> {}]，跳过向量结果。",
                            article.getDocId(), embeddingModelName, batchModelName);
                    return List.of();
                }
            }

            ArticleChunkMetadata metadata = copyMetadata(chunk, embeddingModelName);
            embeddings.add(new ArticleChunkEmbedding(metadata, results.getFirst().getOutput()));
        }
        return List.copyOf(embeddings);
    }

    private EmbeddingResponse embedForResponseWithTimeout(Article article,
                                                          EmbeddingModel embeddingModel,
                                                          List<String> texts) {
        Future<EmbeddingResponse> future = embeddingCallExecutor.submit(() -> embeddingModel.embedForResponse(texts));
        try {
            return future.get(embeddingTimeoutMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            future.cancel(true);
            throw new EmbeddingGenerationTimeoutException(timeoutDetail(article), e);
        }
        catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文章[" + article.getDocId() + "]分块向量生成被中断，保留词法路径。", e);
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("文章[" + article.getDocId() + "]分块向量生成失败", cause);
        }
    }

    private String timeoutDetail(Article article) {
        return String.format("[%s] operation=embed timeoutMs=%d docId=%s，文章分块向量生成超时，保留词法路径。",
                EMBEDDING_TIMEOUT_CODE,
                embeddingTimeoutMillis(),
                article.getDocId());
    }

    private static final class EmbeddingGenerationTimeoutException extends IllegalStateException {

        private EmbeddingGenerationTimeoutException(String message, TimeoutException cause) {
            super(message, cause);
        }
    }

    private String resolveEmbeddingModelName(EmbeddingResponse response) {
        if (response.getMetadata() != null && !StringUtil.isEmpty(response.getMetadata().getModel())) {
            return response.getMetadata().getModel();
        }
        return aiProperties.getOllama().getEmbeddingModel();
    }

    private ArticleChunkMetadata copyMetadata(ArticleChunkMetadata source, String embeddingModelName) {
        ArticleChunkMetadata target = new ArticleChunkMetadata();
        target.setChunkId(source.getChunkId());
        target.setDocId(source.getDocId());
        target.setChunkIndex(source.getChunkIndex());
        target.setChunkText(source.getChunkText());
        target.setCharCount(source.getCharCount());
        target.setEmbeddingModel(embeddingModelName);
        return target;
    }
}
