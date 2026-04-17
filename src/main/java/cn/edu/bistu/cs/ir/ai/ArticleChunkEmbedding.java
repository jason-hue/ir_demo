package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;

import java.util.Objects;

/**
 * 单个文章分块及其可选向量结果。
 */
public record ArticleChunkEmbedding(ArticleChunkMetadata metadata, float[] vector) {

    public ArticleChunkEmbedding {
        Objects.requireNonNull(metadata, "metadata不可以为空");
        vector = vector == null ? null : vector.clone();
    }

    @Override
    public float[] vector() {
        return vector == null ? null : vector.clone();
    }
}
