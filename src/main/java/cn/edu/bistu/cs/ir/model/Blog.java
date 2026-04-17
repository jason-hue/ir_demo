package cn.edu.bistu.cs.ir.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 面向新浪博客的兼容模型类。
 * @author chenruoyu
 */
@Getter
@Setter
public class Blog extends Article {

    /**
     * 动态获取的博文信息
     */
    private BlogStats blogStats;

    public String getId() {
        return getDocId();
    }

    public void setId(String id) {
        setDocId(id);
    }

    public long getDate() {
        Instant publishTime = getPublishTime();
        return publishTime == null ? 0 : publishTime.toEpochMilli();
    }

    public void setDate(long date) {
        setPublishTime(date <= 0 ? null : Instant.ofEpochMilli(date));
    }

    public String getContent() {
        return getBody();
    }

    public void setContent(String content) {
        setBody(content);
    }

    @Override
    public void setAuthor(String author) {
        super.setAuthor(author);
        if (getByline() == null) {
            setByline(author);
        }
    }
}
