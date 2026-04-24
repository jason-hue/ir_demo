package cn.edu.bistu.cs.ir.index;

import cn.edu.bistu.cs.ir.ai.ArticleChunkVectorSyncService;
import cn.edu.bistu.cs.ir.crawler.IngestionObservabilityService;
import cn.edu.bistu.cs.ir.crawler.SinaBlogCrawler;
import cn.edu.bistu.cs.ir.model.Article;
import cn.edu.bistu.cs.ir.model.Blog;
import cn.edu.bistu.cs.ir.utils.StringUtil;
import org.apache.lucene.document.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.pipeline.Pipeline;

/**
 * 基于Lucene的WebMagic Pipeline,
 * 用于将抓取的数据写入本地的Lucene索引
 * @author ruoyuchen
 */
public class LucenePipeline implements Pipeline {

    private static final Logger log = LoggerFactory.getLogger(LucenePipeline.class);

    private final IdxService idxService;

    private final ArticleChunkVectorSyncService articleChunkVectorSyncService;

    private final IngestionObservabilityService ingestionObservabilityService;

    private final String runId;

    public LucenePipeline(IdxService idxService,
                          ArticleChunkVectorSyncService articleChunkVectorSyncService){
        this(idxService, articleChunkVectorSyncService, null, null);
    }

    public LucenePipeline(IdxService idxService,
                          ArticleChunkVectorSyncService articleChunkVectorSyncService,
                          IngestionObservabilityService ingestionObservabilityService,
                          String runId){
        log.info("初始化LucenePipeline模块");
        this.idxService = idxService;
        this.articleChunkVectorSyncService = articleChunkVectorSyncService;
        this.ingestionObservabilityService = ingestionObservabilityService;
        this.runId = runId;
    }

    @Override
    public void process(ResultItems resultItems, Task task) {
        Blog blog = resultItems.get(SinaBlogCrawler.RESULT_ITEM_KEY);
        if(blog==null){
            log.error("无法从爬取的结果中提取到Blog对象");
            recordIndexFailure("无法从爬取的结果中提取到Blog对象", null);
            return;
        }
        blog.ensureCanonicalIdentity();
        String id = blog.getDocId();
        Document doc = toDoc(blog);
        boolean result = idxService.addDocument(ArticleIdxFields.ID, id, doc);
        if(!result){
            log.error("无法将ID为[{}]的博客内容写入索引", id);
            recordIndexFailure("无法将博客内容写入Lucene索引", blog);
            return;
        }
        recordIndexSuccess(blog);
        ArticleChunkVectorSyncService.SyncResult syncResult = articleChunkVectorSyncService.syncArticle(blog);
        if (!syncResult.success()) {
            recordVectorFailure(syncResult.detail(), blog);
        }
    }

    private void recordIndexSuccess(Blog blog) {
        if (ingestionObservabilityService != null && runId != null) {
            ingestionObservabilityService.recordIndexSuccess(runId, blog);
        }
    }

    private void recordIndexFailure(String detail, Blog blog) {
        if (ingestionObservabilityService != null && runId != null) {
            ingestionObservabilityService.recordIndexFailure(runId, detail, blog);
        }
    }

    private void recordVectorFailure(String detail, Blog blog) {
        if (ingestionObservabilityService != null && runId != null) {
            ingestionObservabilityService.recordVectorFailure(runId, detail, blog);
        }
    }

    static Document toDoc(Article article){
        article.ensureCanonicalIdentity();
        Document document = new Document();
        //页面ID
        document.add(new StringField(ArticleIdxFields.ID, article.getDocId(), Field.Store.YES));
        //页面标题
        document.add(new TextField(ArticleIdxFields.TITLE, article.getTitle(), Field.Store.YES));
        //页面内容全文
        if (!StringUtil.isEmpty(article.getBody())) {
            document.add(new TextField(ArticleIdxFields.CONTENT, article.getBody(), Field.Store.YES));
        }
        if (article.getPublishTime() != null) {
            long publishTime = article.getPublishTime().toEpochMilli();
            document.add(new LongPoint(ArticleIdxFields.TIME, publishTime));
            document.add(new StoredField(ArticleIdxFields.TIME, publishTime));
            document.add(new NumericDocValuesField(ArticleIdxFields.TIME, publishTime));
        }
        if (!StringUtil.isEmpty(article.getAuthor())) {
            document.add(new TextField(ArticleIdxFields.AUTHOR, article.getAuthor(), Field.Store.YES));
        }
        if (!StringUtil.isEmpty(article.getByline())) {
            document.add(new TextField(ArticleIdxFields.BYLINE, article.getByline(), Field.Store.YES));
        }
        if (!StringUtil.isEmpty(article.getSource())) {
            document.add(new StringField(ArticleIdxFields.SOURCE, article.getSource(), Field.Store.YES));
        }
        if (!StringUtil.isEmpty(article.getSourceUrl())) {
            document.add(new StringField(ArticleIdxFields.SOURCE_URL, article.getSourceUrl(), Field.Store.YES));
        }
        if (!StringUtil.isEmpty(article.getSection())) {
            document.add(new StringField(ArticleIdxFields.SECTION, article.getSection(), Field.Store.YES));
        }
        return document;
    }
}
