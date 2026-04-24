package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.config.AppRuntimeProperties;
import cn.edu.bistu.cs.ir.config.Config;
import cn.edu.bistu.cs.ir.model.Blog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import us.codecraft.webmagic.Spider;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;

class CrawlerServiceStartupTest {

    @Test
    void readinessHookDoesNothingWhenStartupCrawlerIsDisabled() {
        Config config = new Config();
        config.setStartCrawler(false);

        RecordingCrawlerService service = new RecordingCrawlerService(config, new AppRuntimeProperties());

        service.startTencentCrawlerAfterApplicationReady();

        Assertions.assertAll(
                () -> Assertions.assertTrue(service.tencentStarts.isEmpty()),
                () -> Assertions.assertFalse(service.cnBlogsStarted)
        );
    }

    @Test
    void readinessHookDoesNothingWhenTencentStartupIsNotExplicitlyEnabled() {
        Config config = new Config();
        config.setStartCrawler(true);
        AppRuntimeProperties properties = new AppRuntimeProperties();
        properties.getCrawler().getTencent().setEnabled(false);
        properties.getCrawler().getTencent().setSeedUrls(List.of("https://news.qq.com/rain/a/20260415A05BCB00"));

        RecordingCrawlerService service = new RecordingCrawlerService(config, properties);

        service.startTencentCrawlerAfterApplicationReady();

        Assertions.assertAll(
                () -> Assertions.assertTrue(service.tencentStarts.isEmpty()),
                () -> Assertions.assertFalse(service.cnBlogsStarted)
        );
    }

    @Test
    void readinessHookStartsOnlyTheSingleEnabledTencentCategory() {
        Config config = new Config();
        config.setStartCrawler(true);
        AppRuntimeProperties properties = new AppRuntimeProperties();
        properties.getCrawler().getTencent().setEnabled(true);
        properties.getCrawler().getTencent().setCategories(List.of(
                category("technology", true, List.of(
                        "https://news.qq.com/technology",
                        "https://news.qq.com/technology/ai"
                ), 3, "tencent-technology"),
                category("finance", false, List.of("https://news.qq.com/finance"), 5, "tencent-finance")
        ));

        RecordingCrawlerService service = new RecordingCrawlerService(config, properties);

        service.startTencentCrawlerAfterApplicationReady();

        Assertions.assertAll("single enabled category should start independently",
                () -> Assertions.assertEquals(1, service.tencentStarts.size()),
                () -> Assertions.assertEquals("technology", service.tencentStarts.getFirst().categoryName),
                () -> Assertions.assertEquals(List.of(
                        "https://news.qq.com/technology",
                        "https://news.qq.com/technology/ai"
                ), service.tencentStarts.getFirst().seedUrls),
                () -> Assertions.assertEquals("tencent-technology", service.tencentStarts.getFirst().source),
                () -> Assertions.assertEquals(3, service.tencentStarts.getFirst().maxArticles),
                () -> Assertions.assertFalse(service.cnBlogsStarted)
        );
    }

    @Test
    void readinessHookSchedulesMultipleTencentCategoriesSeparately() {
        Config config = new Config();
        config.setStartCrawler(true);
        AppRuntimeProperties properties = new AppRuntimeProperties();
        properties.getCrawler().getTencent().setEnabled(true);
        properties.getCrawler().getTencent().setCategories(List.of(
                category("technology", true, List.of(
                        "https://news.qq.com/technology",
                        "https://news.qq.com/technology/ai"
                ), 2, "tencent-technology"),
                category("finance", true, List.of(
                        "https://news.qq.com/finance",
                        "https://news.qq.com/stock"
                ), 4, "tencent-finance")
        ));

        RecordingCrawlerService service = new RecordingCrawlerService(config, properties);

        service.startTencentCrawlerAfterApplicationReady();

        Assertions.assertAll("multiple enabled categories should remain isolated",
                () -> Assertions.assertEquals(2, service.tencentStarts.size()),
                () -> Assertions.assertEquals("technology", service.tencentStarts.get(0).categoryName),
                () -> Assertions.assertEquals(List.of(
                        "https://news.qq.com/technology",
                        "https://news.qq.com/technology/ai"
                ), service.tencentStarts.get(0).seedUrls),
                () -> Assertions.assertEquals("finance", service.tencentStarts.get(1).categoryName),
                () -> Assertions.assertEquals(List.of(
                        "https://news.qq.com/finance",
                        "https://news.qq.com/stock"
                ), service.tencentStarts.get(1).seedUrls),
                () -> Assertions.assertNotEquals(service.tencentStarts.get(0).seedUrls, service.tencentStarts.get(1).seedUrls),
                () -> Assertions.assertEquals("tencent-technology", service.tencentStarts.get(0).source),
                () -> Assertions.assertEquals("tencent-finance", service.tencentStarts.get(1).source),
                () -> Assertions.assertEquals(2, service.tencentStarts.get(0).maxArticles),
                () -> Assertions.assertEquals(4, service.tencentStarts.get(1).maxArticles),
                () -> Assertions.assertFalse(service.cnBlogsStarted)
        );
    }

    @Test
    void readinessHookRejectsSecondTriggerAfterFirstHookReturnsWhileStartupBootstrapStaysActive() {
        Config config = new Config();
        config.setStartCrawler(true);
        AppRuntimeProperties properties = new AppRuntimeProperties();
        properties.getCrawler().getTencent().setEnabled(true);
        properties.getCrawler().getTencent().setCategories(List.of(
                category("technology", true, List.of("https://news.qq.com/technology"), 2, "tencent-technology")
        ));

        ActiveStartupRecordingCrawlerService service = new ActiveStartupRecordingCrawlerService(config, properties);

        service.startTencentCrawlerAfterApplicationReady();
        Assertions.assertEquals(1, service.tencentStartCount(), "first startup trigger should launch one Tencent bootstrap");
        Assertions.assertTrue(service.hasSimulatedActiveStartupBootstrap(), "test seam should keep startup bootstrap active after hook returns");

        service.startTencentCrawlerAfterApplicationReady();

        Assertions.assertEquals(1, service.tencentStartCount(), "second readiness trigger must be ignored while startup Tencent runs are still active after hook return");

        service.markStartupBootstrapFinished();

        service.startTencentCrawlerAfterApplicationReady();

        Assertions.assertAll("overlap is forbidden, replay after the in-flight bootstrap finishes remains possible",
                () -> Assertions.assertEquals(2, service.tencentStartCount()),
                () -> Assertions.assertEquals("technology", service.tencentStartAt(0).categoryName),
                () -> Assertions.assertEquals("technology", service.tencentStartAt(1).categoryName),
                () -> Assertions.assertFalse(service.cnBlogsStarted())
        );
    }

    @Test
    void completedTencentSpiderLifecycleRetiresRunWithoutRefreshPolling() {
        Config config = new Config();
        AppRuntimeProperties properties = new AppRuntimeProperties();
        IngestionObservabilityService observabilityService = new IngestionObservabilityService();
        LifecycleTestingCrawlerService service = new LifecycleTestingCrawlerService(config, properties, observabilityService);

        String runId = service.startRunObservation("technology", "tencent-technology", 1, 1);
        service.trackTencentSpiderRun(runId, mock(Spider.class));
        Blog indexedArticle = articleWithIdentity("doc-1", "https://news.qq.com/rain/a/doc-1");
        observabilityService.recordIndexSuccess(runId, indexedArticle);

        service.onTencentSpiderCompleted(runId);

        Map<String, IngestionStatusSnapshot.CategoryRunStatus> statusesByRunId = observabilityService.snapshot().categories().stream()
                .collect(java.util.stream.Collectors.toMap(IngestionStatusSnapshot.CategoryRunStatus::runId, status -> status));
        IngestionStatusSnapshot.CategoryRunStatus status = statusesByRunId.get(runId);

        Assertions.assertAll(
                () -> Assertions.assertNotNull(status),
                () -> Assertions.assertEquals(IngestionStatusSnapshot.RunOutcome.SUCCESS, status.outcome()),
                () -> Assertions.assertEquals(1, status.indexedDocumentCount()),
                () -> Assertions.assertNotNull(status.finishedAt()),
                () -> Assertions.assertFalse(observabilityService.hasActiveRunIdForTest(runId)),
                () -> Assertions.assertTrue(observabilityService.hasCompletedRunIdForTest(runId))
        );
    }

    private static AppRuntimeProperties.TencentCategoryProperties category(String name,
                                                                           boolean enabled,
                                                                           List<String> listUrls,
                                                                           int perRunLimit,
                                                                           String sourceLabel) {
        AppRuntimeProperties.TencentCategoryProperties category = new AppRuntimeProperties.TencentCategoryProperties();
        category.setName(name);
        category.setEnabled(enabled);
        category.setListUrls(listUrls);
        category.setPerRunLimit(perRunLimit);
        category.setSourceLabel(sourceLabel);
        return category;
    }

    private static Blog articleWithIdentity(String docId, String sourceUrl) {
        Blog article = new Blog();
        setArticleField(article, "docId", docId);
        setArticleField(article, "sourceUrl", sourceUrl);
        return article;
    }

    private static void setArticleField(Blog article, String fieldName, String value) {
        try {
            Field field = article.getClass().getSuperclass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(article, value);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set Article." + fieldName, e);
        }
    }

    static class RecordingCrawlerService extends CrawlerService {

        private boolean cnBlogsStarted;
        private final List<TencentStart> tencentStarts = Collections.synchronizedList(new ArrayList<>());

        private RecordingCrawlerService(Config config, AppRuntimeProperties appRuntimeProperties) {
            super(config, appRuntimeProperties, null, null, null);
        }

        @Override
        public void startCnBlogCrawler(String blogger) {
            cnBlogsStarted = true;
        }

        @Override
        protected boolean startTencentNewsCrawlerCategory(String categoryName, List<String> seedUrls, String source, int maxArticles) {
            tencentStarts.add(new TencentStart(categoryName, List.copyOf(seedUrls), source, maxArticles));
            return true;
        }

        protected int tencentStartCount() {
            return tencentStarts.size();
        }

        protected TencentStart tencentStartAt(int index) {
            return tencentStarts.get(index);
        }

        protected boolean cnBlogsStarted() {
            return cnBlogsStarted;
        }

        @Override
        protected boolean hasActiveTencentStartupBootstrap() {
            return false;
        }
    }

    static final class ActiveStartupRecordingCrawlerService extends RecordingCrawlerService {

        private final AtomicBoolean startupBootstrapActive = new AtomicBoolean(false);

        private ActiveStartupRecordingCrawlerService(Config config, AppRuntimeProperties appRuntimeProperties) {
            super(config, appRuntimeProperties);
        }

        @Override
        protected boolean startTencentNewsCrawlerCategory(String categoryName, List<String> seedUrls, String source, int maxArticles) {
            startupBootstrapActive.set(true);
            return super.startTencentNewsCrawlerCategory(categoryName, seedUrls, source, maxArticles);
        }

        @Override
        protected boolean hasActiveTencentStartupBootstrap() {
            return startupBootstrapActive.get();
        }

        private boolean hasSimulatedActiveStartupBootstrap() {
            return startupBootstrapActive.get();
        }

        private void markStartupBootstrapFinished() {
            startupBootstrapActive.set(false);
        }
    }

    static final class LifecycleTestingCrawlerService extends CrawlerService {

        private LifecycleTestingCrawlerService(Config config,
                                               AppRuntimeProperties appRuntimeProperties,
                                               IngestionObservabilityService observabilityService) {
            super(config, appRuntimeProperties, null, null, observabilityService);
        }
    }

    private record TencentStart(String categoryName, List<String> seedUrls, String source, int maxArticles) {
    }
}
