package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.ai.ArticleChunkVectorSyncService;
import cn.edu.bistu.cs.ir.config.Config;
import cn.edu.bistu.cs.ir.config.AppRuntimeProperties;
import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.index.LucenePipeline;
import cn.edu.bistu.cs.ir.utils.StringUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.downloader.Downloader;
import us.codecraft.webmagic.downloader.HttpClientDownloader;
import us.codecraft.webmagic.pipeline.JsonFilePipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static us.codecraft.webmagic.Spider.Status.Stopped;

/**
 * 面向爬虫的服务类
 * @author chenruoyu
 */
@Component
public class CrawlerService{

    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);

    private final Config config;

    private final AppRuntimeProperties appRuntimeProperties;

    private final IdxService idxService;

    private final ArticleChunkVectorSyncService articleChunkVectorSyncService;

    private final IngestionObservabilityService ingestionObservabilityService;

    public CrawlerService(@Autowired Config config,
                          @Autowired AppRuntimeProperties appRuntimeProperties,
                          @Autowired IdxService idxService,
                          @Autowired ArticleChunkVectorSyncService articleChunkVectorSyncService,
                          @Autowired(required = false) IngestionObservabilityService ingestionObservabilityService){
        this.config = config;
        this.appRuntimeProperties = appRuntimeProperties;
        this.idxService = idxService;
        this.articleChunkVectorSyncService = articleChunkVectorSyncService;
        this.ingestionObservabilityService = ingestionObservabilityService;
    }

    private Spider spider = null;

    private String cnBlogRunId;

    private final Map<String, TencentSpiderRun> tencentSpiders = new ConcurrentHashMap<>();


    /**
     * 启动面向博客园的爬虫
     * @param blogger 待爬取的博主ID
     */
    public void startCnBlogCrawler(String blogger) {
        if(StringUtil.isEmpty(blogger)){
            log.error("博主的唯一ID不可以为空");
            return;
        }
        String startPage = String.format("https://www.cnblogs.com/%s/default.html?page=2", blogger);
        if(hasAnyRunningCrawler()){
            //如果当前有正在运行的爬虫，则不可以启动新的爬虫
            log.error("当前有正在运行的爬虫对象，不可以创建新的爬虫");
            return;
        }
        Site site = Site
                .me()
                .setRetryTimes(config.getRetryTimes())
                .setSleepTime(config.getSleepTime())
                .setUserAgent(config.getAgent());
        String categoryName = "cnblogs:" + blogger.trim();
        String runId = startRunObservation(categoryName, blogger, 1, 0);
        this.spider = Spider.create(new CnBlogsCrawler(site, blogger));
        spider.setSpiderListeners(List.of(new CategorySpiderListener(runId, categoryName)));
        spider.setDownloader(new ObservedDownloader(runId));
        spider.addPipeline(new LucenePipeline(idxService, articleChunkVectorSyncService, ingestionObservabilityService, runId));
        spider.addPipeline(new JsonFilePipeline(config.getCrawler()));
        spider.thread(1);
        spider.addUrl(startPage);
        cnBlogRunId = runId;
        spider.runAsync();
        log.info("启动面向博客园的爬虫，抓取博主ID为[{}]的作者的文章", blogger);
    }

    public void startTencentNewsCrawler() {
        AppRuntimeProperties.TencentProperties tencent = appRuntimeProperties.getCrawler().getTencent();
        List<AppRuntimeProperties.TencentCategoryProperties> enabledCategories = tencent.getEnabledCategories();
        if (enabledCategories.isEmpty()) {
            log.warn("腾讯新闻爬虫未配置启用的分类，跳过启动");
            return;
        }
        List<String> startedCategories = new ArrayList<>();
        for (AppRuntimeProperties.TencentCategoryProperties category : enabledCategories) {
            if (startTencentNewsCrawlerCategory(category)) {
                startedCategories.add(resolveTencentCategoryName(category));
            }
        }
        if (startedCategories.isEmpty()) {
            log.warn("腾讯新闻爬虫未找到可启动的分类种子URL，跳过启动");
            return;
        }
        log.info("按分类启动腾讯新闻爬虫，分类列表为{}", startedCategories);
    }

    public void startTencentNewsCrawler(List<String> seedUrls, String source, int maxArticles) {
        startTencentNewsCrawlerCategory("adhoc", seedUrls, source, maxArticles);
    }

    protected boolean startTencentNewsCrawlerCategory(AppRuntimeProperties.TencentCategoryProperties category) {
        if (category == null) {
            log.warn("腾讯新闻爬虫分类配置为空，跳过启动");
            return false;
        }
        return startTencentNewsCrawlerCategory(
                resolveTencentCategoryName(category),
                category.getListUrls(),
                resolveTencentSource(category),
                category.getPerRunLimit()
        );
    }

    protected boolean startTencentNewsCrawlerCategory(String categoryName, List<String> seedUrls, String source, int maxArticles) {
        if (seedUrls == null || seedUrls.isEmpty()) {
            log.warn("腾讯新闻分类[{}]的种子URL列表不可以为空，跳过启动", categoryName);
            return false;
        }
        if (hasRunningCnBlogCrawler()) {
            log.error("当前有正在运行的爬虫对象，不可以创建新的爬虫");
            return false;
        }
        Site site = Site
                .me()
                .setRetryTimes(config.getRetryTimes())
                .setSleepTime(config.getSleepTime())
                .setUserAgent(config.getAgent());
        String runId = startRunObservation(categoryName, source, seedUrls.size(), maxArticles);
        Spider tencentSpider = Spider.create(new TencentNewsCrawler(site, source, maxArticles, ingestionObservabilityService, runId));
        tencentSpider.setSpiderListeners(List.of(new CategorySpiderListener(runId, categoryName)));
        tencentSpider.setDownloader(new ObservedDownloader(runId));
        tencentSpider.addPipeline(new LucenePipeline(idxService, articleChunkVectorSyncService, ingestionObservabilityService, runId));
        tencentSpider.addPipeline(new JsonFilePipeline(config.getCrawler()));
        tencentSpider.thread(1);
        tencentSpider.addUrl(seedUrls.toArray(new String[0]));
        synchronized (tencentSpiders) {
            tencentSpiders.put(runId, new TencentSpiderRun(tencentSpider));
        }
        tencentSpider.runAsync();
        log.info("启动面向腾讯新闻分类[{}]的爬虫，种子地址数量为[{}]，最大抓取文章数为[{}]", categoryName, seedUrls.size(), maxArticles);
        return true;
    }

    @PostConstruct
    public void init(){
        if(!config.isStartCrawler()){
            return;
        }

        AppRuntimeProperties.TencentProperties tencent = appRuntimeProperties.getCrawler().getTencent();
        if (!tencent.isEnabled()) {
            log.info("系统配置信息中[startCrawler]配置项为true，但腾讯新闻启动爬虫开关未开启，跳过启动抓取");
            return;
        }
        if (tencent.getEnabledCategories().isEmpty()) {
            log.warn("系统配置信息中[startCrawler]配置项为true，但未配置启用的腾讯新闻分类，跳过启动抓取");
            return;
        }

        log.info("系统配置信息中[startCrawler]配置项为true，按腾讯新闻配置启动爬虫");
        startTencentNewsCrawler();
    }

    private boolean hasAnyRunningCrawler() {
        return hasRunningCnBlogCrawler() || hasRunningTencentCrawler();
    }

    private boolean hasRunningCnBlogCrawler() {
        refreshCnBlogCrawlerStatus();
        return this.spider != null && !Stopped.equals(this.spider.getStatus());
    }

    private boolean hasRunningTencentCrawler() {
        refreshTencentCrawlerStatuses();
        synchronized (tencentSpiders) {
            return !tencentSpiders.isEmpty();
        }
    }

    public IngestionStatusSnapshot getIngestionStatusSnapshot() {
        refreshCnBlogCrawlerStatus();
        refreshTencentCrawlerStatuses();
        if (ingestionObservabilityService == null) {
            return new IngestionStatusSnapshot(java.time.Instant.now(), List.of());
        }
        return ingestionObservabilityService.snapshot();
    }

    private String resolveTencentCategoryName(AppRuntimeProperties.TencentCategoryProperties category) {
        if (category == null || StringUtil.isEmpty(category.getName())) {
            return "unnamed";
        }
        return category.getName().trim();
    }

    private String resolveTencentSource(AppRuntimeProperties.TencentCategoryProperties category) {
        if (category != null && !StringUtil.isEmpty(category.getSourceLabel())) {
            return category.getSourceLabel().trim();
        }
        return "tencent-news";
    }

    private String startRunObservation(String categoryName, String source, int seedUrlCount, int maxArticles) {
        if (ingestionObservabilityService != null) {
            return ingestionObservabilityService.startRun(categoryName, source, seedUrlCount, maxArticles);
        }
        return UUID.randomUUID().toString();
    }

    private void refreshCnBlogCrawlerStatus() {
        if (this.spider != null && Stopped.equals(this.spider.getStatus())) {
            if (cnBlogRunId != null && ingestionObservabilityService != null) {
                ingestionObservabilityService.markStopped(cnBlogRunId);
            }
            this.spider = null;
            this.cnBlogRunId = null;
        }
    }

    private void refreshTencentCrawlerStatuses() {
        List<String> stoppedRunIds = new ArrayList<>();
        synchronized (tencentSpiders) {
            for (Map.Entry<String, TencentSpiderRun> entry : tencentSpiders.entrySet()) {
                if (Stopped.equals(entry.getValue().spider().getStatus())) {
                    stoppedRunIds.add(entry.getKey());
                }
            }
            for (String stoppedRunId : stoppedRunIds) {
                tencentSpiders.remove(stoppedRunId);
            }
        }
        if (ingestionObservabilityService != null) {
            for (String stoppedRunId : stoppedRunIds) {
                ingestionObservabilityService.markStopped(stoppedRunId);
            }
        }
    }

    private final class CategorySpiderListener implements us.codecraft.webmagic.SpiderListener {

        private final String runId;

        private final String categoryName;

        private CategorySpiderListener(String runId, String categoryName) {
            this.runId = runId;
            this.categoryName = categoryName;
        }

        @Override
        public void onSuccess(us.codecraft.webmagic.Request request) {
        }

        @Override
        public void onError(us.codecraft.webmagic.Request request, Exception e) {
            if (ingestionObservabilityService != null) {
                String detail = e == null ? "crawl request failed" : e.getMessage();
                ingestionObservabilityService.recordRequestFailure(runId, detail);
            }
            log.warn("分类[{}]抓取请求失败，url=[{}]", categoryName, request == null ? null : request.getUrl(), e);
        }
    }

    private final class ObservedDownloader implements Downloader {

        private final HttpClientDownloader delegate = new HttpClientDownloader();

        private final String runId;

        private ObservedDownloader(String runId) {
            this.runId = runId;
        }

        @Override
        public Page download(Request request, Task task) {
            Page page;
            try {
                page = delegate.download(request, task);
            }
            catch (RuntimeException e) {
                recordDownloadFailure(request, e.getMessage());
                return Page.fail();
            }
            if (page != null && page.isDownloadSuccess()) {
                if (ingestionObservabilityService != null) {
                    ingestionObservabilityService.recordRequestSuccess(runId);
                }
                return page;
            }
            recordDownloadFailure(request, "crawl download failed");
            return page == null ? Page.fail() : page;
        }

        @Override
        public void setThread(int threadNum) {
            delegate.setThread(threadNum);
        }

        private void recordDownloadFailure(Request request, String detail) {
            if (ingestionObservabilityService == null) {
                return;
            }
            String url = request == null ? null : request.getUrl();
            String message = detail == null || detail.isBlank()
                    ? String.format("crawl download failed for [%s]", url)
                    : String.format("crawl download failed for [%s]: %s", url, detail);
            ingestionObservabilityService.recordRequestFailure(runId, message);
        }
    }

    private record TencentSpiderRun(Spider spider) {
    }
}
