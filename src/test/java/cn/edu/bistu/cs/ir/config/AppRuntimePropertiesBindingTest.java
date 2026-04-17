package cn.edu.bistu.cs.ir.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class AppRuntimePropertiesBindingTest {

    @Test
    void bindsMultipleTencentCategories() {
        AppRuntimeProperties properties = bind(
                "app.crawler.tencent.enabled", "true",
                "app.crawler.tencent.categories[0].name", "ai",
                "app.crawler.tencent.categories[0].enabled", "true",
                "app.crawler.tencent.categories[0].list-urls[0]", "https://news.qq.com/rain/a/20260415A05BCB00",
                "app.crawler.tencent.categories[0].per-run-limit", "2",
                "app.crawler.tencent.categories[0].source-label", "tencent-news-ai",
                "app.crawler.tencent.categories[1].name", "finance",
                "app.crawler.tencent.categories[1].enabled", "true",
                "app.crawler.tencent.categories[1].list-urls[0]", "https://news.qq.com/rain/a/20260415A03FIN00",
                "app.crawler.tencent.categories[1].per-run-limit", "4",
                "app.crawler.tencent.categories[1].source-label", "tencent-news-finance"
        );

        List<AppRuntimeProperties.TencentCategoryProperties> categories = properties.getCrawler().getTencent().getCategories();

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, categories.size()),
                () -> Assertions.assertEquals("ai", categories.get(0).getName()),
                () -> Assertions.assertEquals(List.of("https://news.qq.com/rain/a/20260415A05BCB00"), categories.get(0).getListUrls()),
                () -> Assertions.assertEquals("finance", categories.get(1).getName()),
                () -> Assertions.assertEquals("tencent-news-finance", categories.get(1).getSourceLabel())
        );
    }

    @Test
    void disabledCategoryStaysDisabledAndIsIgnoredByCompatibilityAccessors() {
        AppRuntimeProperties properties = bind(
                "app.crawler.tencent.enabled", "true",
                "app.crawler.tencent.categories[0].name", "disabled-ai",
                "app.crawler.tencent.categories[0].enabled", "false",
                "app.crawler.tencent.categories[0].list-urls[0]", "https://news.qq.com/rain/a/20260415A05BCB00",
                "app.crawler.tencent.categories[0].per-run-limit", "2",
                "app.crawler.tencent.categories[1].name", "finance",
                "app.crawler.tencent.categories[1].enabled", "true",
                "app.crawler.tencent.categories[1].list-urls[0]", "https://news.qq.com/rain/a/20260415A03FIN00",
                "app.crawler.tencent.categories[1].per-run-limit", "3",
                "app.crawler.tencent.categories[1].source-label", "tencent-news-finance"
        );

        AppRuntimeProperties.TencentProperties tencent = properties.getCrawler().getTencent();

        Assertions.assertAll(
                () -> Assertions.assertFalse(tencent.getCategories().get(0).isEnabled()),
                () -> Assertions.assertEquals(1, tencent.getEnabledCategories().size()),
                () -> Assertions.assertEquals("finance", tencent.getEnabledCategories().get(0).getName()),
                () -> Assertions.assertEquals(List.of("https://news.qq.com/rain/a/20260415A03FIN00"), tencent.getSeedUrls())
        );
    }

    @Test
    void perRunLimitBindsCorrectly() {
        AppRuntimeProperties properties = bind(
                "app.crawler.tencent.enabled", "true",
                "app.crawler.tencent.categories[0].name", "ai",
                "app.crawler.tencent.categories[0].enabled", "true",
                "app.crawler.tencent.categories[0].list-urls[0]", "https://news.qq.com/rain/a/20260415A05BCB00",
                "app.crawler.tencent.categories[0].per-run-limit", "7",
                "app.crawler.tencent.categories[0].source-label", "tencent-news-ai"
        );

        AppRuntimeProperties.TencentProperties tencent = properties.getCrawler().getTencent();

        Assertions.assertAll(
                () -> Assertions.assertEquals(7, tencent.getCategories().get(0).getPerRunLimit()),
                () -> Assertions.assertEquals(7, tencent.getMaxArticles()),
                () -> Assertions.assertEquals("tencent-news-ai", tencent.getSource())
        );
    }

    private AppRuntimeProperties bind(String... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put(entries[i], entries[i + 1]);
        }

        AppRuntimeProperties properties = new AppRuntimeProperties();
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        binder.bind("app", Bindable.ofInstance(properties));
        return properties;
    }
}
