package cn.edu.bistu.cs.ir.ai;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HybridChunkResult {

    private String docId;

    private String chunkId;

    private String title;

    private String source;

    private String sourceUrl;

    private String publishTime;

    private String section;

    private String author;

    private String byline;

    private Integer chunkIndex;

    private Integer charCount;

    private String chunkText;

    private Integer lexicalRank;

    private Integer vectorRank;

    private Double fusedScore;
}
