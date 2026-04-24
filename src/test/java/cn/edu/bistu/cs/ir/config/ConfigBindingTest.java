package cn.edu.bistu.cs.ir.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

class ConfigBindingTest {

    @Test
    void defaultStartupCrawlerRemainsDisabledWithoutExplicitConfig() {
        Config config = new Config();

        Assertions.assertFalse(config.isStartCrawler());
    }

    @Test
    void startCrawlerBindsFromIrDemoDirProperties() {
        Config config = bind(
                "irdemo.dir.home", "workspace/test-config-binding",
                "irdemo.dir.idx", "workspace/test-config-binding/idx",
                "irdemo.dir.crawler", "workspace/test-config-binding/crawler",
                "irdemo.dir.startCrawler", "true"
        );

        Assertions.assertTrue(config.isStartCrawler());
    }

    private Config bind(String... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put(entries[i], entries[i + 1]);
        }

        Config config = new Config();
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        binder.bind("irdemo.dir", Bindable.ofInstance(config));
        return config;
    }
}
