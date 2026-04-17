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

import java.util.ArrayList;
import java.util.List;

/**
 * 使用Spring AI EmbeddingModel为文章分块生成向量。
 */
@Service
public class ArticleEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(ArticleEmbeddingService.class);

    private final ArticleChunkingService articleChunkingService;

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final AiFallbackService aiFallbackService;

    private final AiProperties aiProperties;

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
        List<ArticleChunkMetadata> chunks = articleChunkingService.chunk(article);
        if (chunks.isEmpty() || aiFallbackService.isLexicalOnlyMode()) {
            return List.of();
        }

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            return List.of();
        }

        try {
            EmbeddingResponse response = embeddingModel.embedForResponse(
                    chunks.stream().map(ArticleChunkMetadata::getChunkText).toList());
            List<Embedding> results = response.getResults();
            if (results.size() != chunks.size()) {
                log.warn("文章[{}]分块向量数量[{}]与分块数量[{}]不一致，跳过向量结果。",
                        article.getDocId(), results.size(), chunks.size());
                return List.of();
            }

            String embeddingModelName = resolveEmbeddingModelName(response);
            List<ArticleChunkEmbedding> embeddings = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                ArticleChunkMetadata metadata = copyMetadata(chunks.get(i), embeddingModelName);
                embeddings.add(new ArticleChunkEmbedding(metadata, results.get(i).getOutput()));
            }
            return List.copyOf(embeddings);
        }
        catch (RuntimeException e) {
            log.warn("文章[{}]生成向量失败，保留词法路径并跳过向量流程: {}", article.getDocId(), e.getMessage());
            return List.of();
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
