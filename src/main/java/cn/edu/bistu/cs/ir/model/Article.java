package cn.edu.bistu.cs.ir.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一文章模型，供爬虫、索引、分块与后续向量流程共享。
 */
@Getter
@Setter
public class Article {

    private String docId;

    private String source;

    private String sourceUrl;

    private String title;

    private String body;

    private Instant publishTime;

    private Instant crawlTime;

    private String section;

    private String author;

    private String byline;

    private List<String> tags = new ArrayList<>();

    /**
     * 将文章身份收敛到共享规则：canonical sourceUrl + stable docId。
     */
    public void ensureCanonicalIdentity() {
        this.sourceUrl = ArticleIds.canonicalIdentityUrl(sourceUrl);
        this.docId = ArticleIds.generateDocId(this.sourceUrl, source, title, publishTime);
    }

    public void ensureDocId() {
        ensureCanonicalIdentity();
    }
}
