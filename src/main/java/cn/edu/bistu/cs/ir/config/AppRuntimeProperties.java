package cn.edu.bistu.cs.ir.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 应用运行期扩展配置。
 */
@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppRuntimeProperties {

    private final AiProperties ai = new AiProperties();

    private final VectorProperties vector = new VectorProperties();

    private final CrawlerProperties crawler = new CrawlerProperties();

    private final SeedProperties seed = new SeedProperties();

    public CrawlerProperties getCrawler() {
        return crawler;
    }

    @Getter
    @Setter
    public static class AiProperties {

        private boolean enabled = false;

        private String provider = "ollama";

        private final OllamaProperties ollama = new OllamaProperties();
    }

    @Getter
    @Setter
    public static class OllamaProperties {

        private String baseUrl = "http://127.0.0.1:11434";

        private String chatModel = "qwen2.5:7b";

        private String embeddingModel = "nomic-embed-text";
    }

    @Getter
    @Setter
    public static class VectorProperties {

        private boolean enabled = false;

        private String host = "127.0.0.1";

        private int port = 6334;

        private boolean tls = false;

        private String collection = "articles";

        private String apiKey;
    }

    @Getter
    @Setter
    public static class CrawlerProperties {

        private final TencentProperties tencent = new TencentProperties();

        public TencentProperties getTencent() {
            return tencent;
        }
    }

    @Getter
    @Setter
    public static class SeedProperties {

        private final LuceneSeedProperties lucene = new LuceneSeedProperties();
    }

    @Getter
    @Setter
    public static class LuceneSeedProperties {

        private boolean enabled = false;

        private String resource = "classpath:fixtures/tencent/news/runtime-seed-articles.json";
    }

    @Getter
    @Setter
    public static class TencentProperties {

        private boolean enabled = false;

        private final List<TencentCategoryProperties> categories = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<TencentCategoryProperties> getCategories() {
            return categories;
        }

        public void setCategories(List<TencentCategoryProperties> categories) {
            this.categories.clear();
            if (categories != null) {
                this.categories.addAll(categories);
            }
        }

        public List<TencentCategoryProperties> getEnabledCategories() {
            return categories.stream()
                    .filter(Objects::nonNull)
                    .filter(TencentCategoryProperties::isEnabled)
                    .toList();
        }

        public TencentCategoryProperties getPrimaryEnabledCategory() {
            return getEnabledCategories().stream().findFirst().orElse(null);
        }

        public String getSource() {
            TencentCategoryProperties category = getPrimaryEnabledCategory();
            if (category != null && hasText(category.getSourceLabel())) {
                return category.getSourceLabel();
            }
            return "tencent-news";
        }

        public void setSource(String source) {
            getOrCreateCompatibilityCategory().setSourceLabel(source);
        }

        public int getMaxArticles() {
            TencentCategoryProperties category = getPrimaryEnabledCategory();
            return category != null ? category.getPerRunLimit() : 2;
        }

        public void setMaxArticles(int maxArticles) {
            getOrCreateCompatibilityCategory().setPerRunLimit(maxArticles);
        }

        public List<String> getSeedUrls() {
            TencentCategoryProperties category = getPrimaryEnabledCategory();
            return category != null ? category.getListUrls() : List.of();
        }

        public void setSeedUrls(List<String> seedUrls) {
            getOrCreateCompatibilityCategory().setListUrls(seedUrls);
        }

        private TencentCategoryProperties getOrCreateCompatibilityCategory() {
            TencentCategoryProperties existing = getPrimaryEnabledCategory();
            if (existing != null) {
                return existing;
            }
            TencentCategoryProperties category = new TencentCategoryProperties();
            category.setName("default");
            category.setEnabled(true);
            categories.add(category);
            return category;
        }

        private boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    @Getter
    @Setter
    public static class TencentCategoryProperties {

        private String name;

        private boolean enabled = true;

        private List<String> listUrls = new ArrayList<>();

        private int perRunLimit = 2;

        private String sourceLabel;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getListUrls() {
            return listUrls;
        }

        public void setListUrls(List<String> listUrls) {
            this.listUrls = listUrls == null ? new ArrayList<>() : new ArrayList<>(listUrls);
        }

        public int getPerRunLimit() {
            return perRunLimit;
        }

        public void setPerRunLimit(int perRunLimit) {
            this.perRunLimit = perRunLimit;
        }

        public String getSourceLabel() {
            return sourceLabel;
        }

        public void setSourceLabel(String sourceLabel) {
            this.sourceLabel = sourceLabel;
        }
    }
}
