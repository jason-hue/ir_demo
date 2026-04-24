package cn.edu.bistu.cs.ir.index;

import cn.edu.bistu.cs.ir.config.Config;
import cn.edu.bistu.cs.ir.model.Article;
import cn.edu.bistu.cs.ir.utils.StringUtil;
import com.hankcs.lucene.HanLPAnalyzer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 面向<a href="https://lucene.apache.org/">Lucene</a>
 * 索引读、写的服务类
 * @author chenruoyu
 */
@Component
public class IdxService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IdxService.class);

    private IndexWriter writer;

    public IdxService(@Autowired Config config) throws Exception {
        Analyzer analyzer = createAnalyzer();
        Directory index;
        try {
            index = FSDirectory.open(Paths.get(config.getIdx()));
            IndexWriterConfig writerConfig = new IndexWriterConfig(analyzer);
            writer = new IndexWriter(index, writerConfig);
            log.info("索引初始化完成，索引目录为:[{}]", config.getIdx());
        } catch (IOException e) {
            e.printStackTrace();
            log.error("无法初始化索引，请检查提供的索引目录是否可用:[{}]", config.getIdx());
            writer = null;
        }
    }

    private static Analyzer createAnalyzer() {
        return new HanLPAnalyzer();
    }

    public boolean addDocument(String idFld, String id, Document doc){
        if(writer==null||doc==null){
            log.error("Writer对象或文档对象为空，无法添加文档到索引中");
            return false;
        }
        if(StringUtil.isEmpty(idFld)||StringUtil.isEmpty(id)){
            log.error("ID字段名或ID字段值为空，无法添加文档到索引中");
            return false;
        }
        try {
            writer.updateDocument(new Term(idFld, id), doc);
            writer.commit();
            log.info("成功将ID为[{}]的文档加入索引", id);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            log.error("构建索引失败");
            return false;
        }
    }

    public boolean isAvailable() {
        return writer != null;
    }

    public int documentCount() {
        if (writer == null) {
            return 0;
        }
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            return reader.numDocs();
        }
        catch (IOException e) {
            log.warn("无法统计Lucene索引文档数", e);
            return -1;
        }
    }

    public List<Article> listStoredArticles() {
        if (writer == null) {
            throw new IllegalStateException("Lucene索引不可用，无法回填存量文章向量。");
        }
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs docs = searcher.search(new MatchAllDocsQuery(), reader.numDocs());
            List<Article> results = new ArrayList<>(docs.scoreDocs.length);
            for (ScoreDoc hit : docs.scoreDocs) {
                results.add(toArticle(searcher.doc(hit.doc)));
            }
            results.sort(Comparator.comparing(Article::getDocId, Comparator.nullsLast(String::compareTo)));
            return List.copyOf(results);
        }
        catch (IOException e) {
            throw new IllegalStateException("无法读取Lucene索引中的存储文章，无法执行向量回填。", e);
        }
    }

    /**
     * 根据关键词对索引内容进行检索，并将检索结果返回
     * @param kw 待检索的关键词
     * @return 检索得到的文档列表
     */
    public List<Document> queryByKw(String kw) throws Exception{
        return queryByKw(kw, 1, 10);
    }

    public List<Document> queryByKw(String kw, int pageNo, int pageSize) throws Exception{
        if (StringUtil.isEmpty(kw) || writer == null) {
            return List.of();
        }
        int normalizedPageNo = Math.max(pageNo, 1);
        int normalizedPageSize = Math.max(pageSize, 1);
        int start = (normalizedPageNo - 1) * normalizedPageSize;
        int end = normalizedPageNo * normalizedPageSize;
        try (DirectoryReader reader = DirectoryReader.open(writer);
             Analyzer analyzer = createAnalyzer()) {
            IndexSearcher searcher = new IndexSearcher(reader);
            QueryParser parser = new MultiFieldQueryParser(ArticleIdxFields.SEARCH_FIELDS, analyzer);
            parser.setDefaultOperator(QueryParser.Operator.OR);
            Query query = parser.parse(QueryParser.escape(kw.trim()));
            TopDocs docs = searcher.search(query, end);
            ScoreDoc[] hits = docs.scoreDocs;
            List<Document> results = new ArrayList<>();
            for (int i = start; i < end && i < hits.length; i++) {
                results.add(searcher.doc(hits[i].doc));
            }
            return results;
        }
    }

    private Article toArticle(Document doc) {
        Article article = new Article();
        article.setDocId(doc.get(ArticleIdxFields.ID));
        article.setTitle(doc.get(ArticleIdxFields.TITLE));
        article.setBody(doc.get(ArticleIdxFields.CONTENT));
        article.setSource(doc.get(ArticleIdxFields.SOURCE));
        article.setSourceUrl(doc.get(ArticleIdxFields.SOURCE_URL));
        article.setSection(doc.get(ArticleIdxFields.SECTION));
        article.setAuthor(doc.get(ArticleIdxFields.AUTHOR));
        article.setByline(doc.get(ArticleIdxFields.BYLINE));
        String publishTime = doc.get(ArticleIdxFields.TIME);
        if (!StringUtil.isEmpty(publishTime)) {
            article.setPublishTime(Instant.ofEpochMilli(Long.parseLong(publishTime)));
        }
        return article;
    }

    //TODO 请大家在这里添加更多的检索函数，如针对发表时间的范围检索等，
    // 添加了检索函数后，还需要相应地在Controller中添加接口


    @Override
    public void destroy(){
        if(this.writer==null){
            return;
        }
        try {
            log.info("索引关闭");
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
            log.info("尝试关闭索引失败");
        }
    }
}
