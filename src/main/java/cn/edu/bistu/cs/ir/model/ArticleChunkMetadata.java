package cn.edu.bistu.cs.ir.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 文章分块元数据。
 */
@Getter
@Setter
public class ArticleChunkMetadata {

    private String chunkId;

    private String docId;

    private int chunkIndex;

    private String chunkText;

    private int charCount;

    private String embeddingModel;

    public void deriveChunkId() {
        this.chunkId = ArticleIds.generateChunkId(docId, chunkIndex);
    }
}
