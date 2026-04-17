package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.config.AppRuntimeProperties;
import cn.edu.bistu.cs.ir.config.Config;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class CrawlerServiceStartupTest {

    @Test
    void initDoesNothingWhenStartupCrawlerIsDisabled() {
        Config config = new Config();
        config.setStartCrawler(false);

        RecordingCrawlerService service = new RecordingCrawlerService(config, new AppRuntimeProperties());

        service.init();

        Assertions.assertAll(
                () -> Assertions.assertTrue(service.tencentStarts.isEmpty()),
                () -> Assertions.assertFalse(service.cnBlogsStarted)
        );
    }

    @Test
    void initDoesNothingWhenTencentStartupIsNotExplicitlyEnabled() {
        Config config = new Config();
        config.setStartCrawler(true);
        AppRuntimeProperties properties = new AppRuntimeProperties();
        properties.getCrawler().getTencent().setEnabled(false);
        properties.getCrawler().getTencent().setSeedUrls(List.of("https://news.qq.com/rain/a/20260415A05BCB00"));

        RecordingCrawlerService service = new RecordingCrawlerService(config, properties);

        service.init();

        Assertions.assertAll(
                () -> Assertions.assertTrue(service.tencentStarts.isEmpty()),
                () -> Assertions.assertFalse(service.cnBlogsStarted)
        );
    }

    @Test
    void initStartsOnlyTheSingleEnabledTencentCategory() {
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

        service.init();

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
    void initSchedulesMultipleTencentCategoriesSeparately() {
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

        service.init();

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

    private static final class RecordingCrawlerService extends CrawlerService {

        private boolean cnBlogsStarted;
        private final List<TencentStart> tencentStarts = new ArrayList<>();

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
    }

    private record TencentStart(String categoryName, List<String> seedUrls, String source, int maxArticles) {
    }
}
