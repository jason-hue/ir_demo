package cn.edu.bistu.cs.ir.crawler;

import cn.edu.bistu.cs.ir.model.ArticleIds;
import cn.edu.bistu.cs.ir.model.Blog;
import cn.edu.bistu.cs.ir.utils.HttpUtils;
import cn.edu.bistu.cs.ir.utils.StringUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.processor.PageProcessor;
import us.codecraft.webmagic.selector.Html;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向腾讯新闻文章页的爬虫与解析器。
 */
public class TencentNewsCrawler implements PageProcessor {

    private static final Logger log = LoggerFactory.getLogger(TencentNewsCrawler.class);

    public static final String RESULT_ITEM_KEY = "BLOG_INFO";

    private static final Pattern ARTICLE_URL_PATTERN = Pattern.compile("https?://(?:news|new)\\.qq\\.com/rain/a/[^?#]+", Pattern.CASE_INSENSITIVE);

    private static final Pattern CHANNEL_URL_PATTERN = Pattern.compile("https?://news\\.qq\\.com/ch/([a-z0-9_-]+)/?", Pattern.CASE_INSENSITIVE);

    private static final Pattern CHANNEL_KEY_PATTERN = Pattern.compile("window\\.channelInfo\\s*=\\s*\\{.*?\"channelKey\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern WINDOW_DATA_PATTERN = Pattern.compile("window\\.DATA\\s*=\\s*(\\{.*?});", Pattern.DOTALL);

    private static final String CATEGORY_FEED_URL = "https://i.news.qq.com/web_feed/getPCList";

    private static final int MIN_CATEGORY_FEED_ITEMS = 12;

    private static final Set<String> FEED_URL_KEYS = Set.of("url", "open_url", "article_url", "link");

    private static final DateTimeFormatter[] DATE_TIME_FORMATTERS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    };

    private final Site site;

    private final String source;

    private final int maxArticleTargets;

    private final HttpUtils httpUtils;

    private final IngestionObservabilityService ingestionObservabilityService;

    private final String categoryName;

    private final Set<String> scheduledArticleUrls = ConcurrentHashMap.newKeySet();

    public TencentNewsCrawler(Site site, String source, int maxArticleTargets) {
        this(site, source, maxArticleTargets, null, null);
    }

    public TencentNewsCrawler(Site site,
                              String source,
                              int maxArticleTargets,
                              IngestionObservabilityService ingestionObservabilityService,
                              String categoryName) {
        this.site = site;
        this.source = StringUtil.isEmpty(source) ? "tencent-news" : source.trim();
        this.maxArticleTargets = maxArticleTargets <= 0 ? 1 : maxArticleTargets;
        this.httpUtils = new HttpUtils();
        this.ingestionObservabilityService = ingestionObservabilityService;
        this.categoryName = categoryName;
    }

    @Override
    public void process(Page page) {
        String requestUrl = page.getRequest().getUrl();
        String rawHtml = page.getRawText();
        if (isArticleUrl(requestUrl) || containsWindowData(rawHtml)) {
            Blog article = parseArticle(requestUrl, rawHtml, Instant.now());
            if (article == null) {
                log.warn("腾讯新闻页面[{}]缺少必要字段，跳过入库", requestUrl);
                recordSemanticFailure(String.format("tencent article parse failed for [%s] after HTTP success", requestUrl));
                page.setSkip(true);
                return;
            }
            page.putField(RESULT_ITEM_KEY, article);
            return;
        }

        List<String> articleUrls = extractArticleUrls(rawHtml);
        if (articleUrls.isEmpty()) {
            articleUrls = extractArticleUrlsFromCategoryFeed(requestUrl, rawHtml);
        }
        if (articleUrls.isEmpty()) {
            log.info("腾讯新闻列表页[{}]未提取到可抓取文章链接", requestUrl);
            recordSemanticFailure(String.format("tencent category extraction yielded no article URLs for [%s] after HTTP success", requestUrl));
            page.setSkip(true);
            return;
        }
        page.addTargetRequests(limitArticleUrls(articleUrls));
        page.setSkip(true);
    }

    List<String> extractArticleUrls(String rawHtml) {
        if (StringUtil.isEmpty(rawHtml)) {
            return List.of();
        }
        List<String> links = new Html(rawHtml).links().all();
        LinkedHashSet<String> articleUrls = new LinkedHashSet<>();
        for (String link : links) {
            String normalized = normalizeTencentUrl(link);
            if (isArticleUrl(normalized)) {
                articleUrls.add(normalized);
            }
        }
        return new ArrayList<>(articleUrls);
    }

    List<String> extractArticleUrlsFromCategoryFeed(String requestUrl, String rawHtml) {
        String responseBody = resolveCategoryFeedResponse(requestUrl, rawHtml);
        if (StringUtil.isEmpty(responseBody)) {
            return List.of();
        }
        return extractArticleUrlsFromFeedResponse(responseBody);
    }

    List<String> extractArticleUrlsFromFeedResponse(String responseBody) {
        if (StringUtil.isEmpty(responseBody)) {
            return List.of();
        }
        Object payload;
        try {
            payload = JSONObject.parse(responseBody);
        } catch (Exception e) {
            log.warn("无法解析腾讯新闻分类Feed响应: {}", e.getMessage());
            return List.of();
        }
        LinkedHashSet<String> articleUrls = new LinkedHashSet<>();
        collectArticleUrls(payload, articleUrls);
        return new ArrayList<>(articleUrls);
    }

    Blog parseArticle(String requestUrl, String rawHtml, Instant crawlTime) {
        if (StringUtil.isEmpty(rawHtml)) {
            return null;
        }
        Html html = new Html(rawHtml);
        JSONObject data = extractWindowData(rawHtml);

        String title = cleanTitle(firstNonBlank(
                getString(data, "title"),
                extractMetaContent(html, "property", "og:title"),
                html.xpath("//h1/text()").get(),
                html.xpath("//title/text()").get()
        ));
        String sourceUrl = normalizeTencentUrl(firstNonBlank(
                extractMetaContent(html, "property", "og:url"),
                requestUrl
        ));
        String byline = firstNonBlank(
                getString(data, "media"),
                extractMetaContent(html, "property", "article:author"),
                html.xpath("//a[contains(@href,'/omn/author/')]/allText()").get()
        );
        String section = firstNonBlank(
                getString(data, "catalog1"),
                extractMetaContent(html, "property", "category")
        );
        Instant publishTime = parsePublishTime(firstNonBlank(
                getString(data, "pubtime"),
                extractMetaContent(html, "property", "article:published_time")
        ));
        String body = firstNonBlank(
                extractBodyFromWindowData(data),
                extractBodyFromHtml(html)
        );

        if (StringUtil.isEmpty(title) || StringUtil.isEmpty(body) || StringUtil.isEmpty(sourceUrl)) {
            return null;
        }

        Blog blog = new Blog();
        blog.setSource(source);
        blog.setSourceUrl(sourceUrl);
        blog.setTitle(title);
        blog.setBody(body);
        blog.setPublishTime(publishTime);
        blog.setCrawlTime(crawlTime == null ? Instant.now() : crawlTime);
        blog.setSection(section);
        blog.setAuthor(byline);
        blog.setByline(byline);
        blog.ensureCanonicalIdentity();
        return blog;
    }

    static String normalizeTencentUrl(String url) {
        String canonicalUrl = ArticleIds.normalizeTencentArticleUrl(url);
        if (!StringUtil.isEmpty(canonicalUrl)) {
            return canonicalUrl;
        }
        return ArticleIds.normalizeSourceUrl(url);
    }

    private String resolveCategoryFeedResponse(String requestUrl, String rawHtml) {
        if (looksLikeCategoryFeedResponse(rawHtml)) {
            return rawHtml;
        }
        String channelKey = extractChannelKey(requestUrl, rawHtml);
        if (StringUtil.isEmpty(channelKey)) {
            return null;
        }
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("base_req", Map.of("from", "pc"));
        requestBody.put("forward", "2");
        requestBody.put("flush_num", 1);
        requestBody.put("channel_id", "news_news_" + channelKey);
        requestBody.put("item_count", Math.max(maxArticleTargets * 6, MIN_CATEGORY_FEED_ITEMS));
        requestBody.put("is_local_chlid", "0");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Referer", StringUtil.isEmpty(requestUrl) ? "https://news.qq.com/" : requestUrl);
        return httpUtils.postJson(CATEGORY_FEED_URL, JSONObject.toJSONString(requestBody), headers);
    }

    private boolean looksLikeCategoryFeedResponse(String rawHtml) {
        if (StringUtil.isEmpty(rawHtml)) {
            return false;
        }
        String trimmed = rawHtml.trim();
        return trimmed.startsWith("{") && trimmed.contains("\"data\"");
    }

    private String extractChannelKey(String requestUrl, String rawHtml) {
        String channelKeyFromUrl = extractChannelKeyFromUrl(requestUrl);
        if (!StringUtil.isEmpty(channelKeyFromUrl)) {
            return channelKeyFromUrl;
        }
        if (StringUtil.isEmpty(rawHtml)) {
            return null;
        }
        Matcher matcher = CHANNEL_KEY_PATTERN.matcher(rawHtml);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String extractChannelKeyFromUrl(String requestUrl) {
        if (StringUtil.isEmpty(requestUrl)) {
            return null;
        }
        Matcher matcher = CHANNEL_URL_PATTERN.matcher(requestUrl);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private void collectArticleUrls(Object payload, Set<String> articleUrls) {
        if (payload == null || articleUrls == null) {
            return;
        }
        if (payload instanceof JSONObject jsonObject) {
            for (Map.Entry<String, Object> entry : jsonObject.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String stringValue && FEED_URL_KEYS.contains(entry.getKey())) {
                    String normalized = normalizeTencentUrl(stringValue);
                    if (isArticleUrl(normalized)) {
                        articleUrls.add(normalized);
                    }
                    continue;
                }
                collectArticleUrls(value, articleUrls);
            }
            return;
        }
        if (payload instanceof JSONArray jsonArray) {
            for (Object item : jsonArray) {
                collectArticleUrls(item, articleUrls);
            }
        }
    }

    private List<String> limitArticleUrls(List<String> articleUrls) {
        List<String> limited = new ArrayList<>();
        for (String articleUrl : articleUrls) {
            if (scheduledArticleUrls.size() >= maxArticleTargets) {
                break;
            }
            if (scheduledArticleUrls.add(articleUrl)) {
                limited.add(articleUrl);
            }
        }
        return limited;
    }

    private boolean isArticleUrl(String url) {
        return !StringUtil.isEmpty(url) && ARTICLE_URL_PATTERN.matcher(url).matches();
    }

    private boolean containsWindowData(String rawHtml) {
        return !StringUtil.isEmpty(rawHtml) && rawHtml.contains("window.DATA");
    }

    private JSONObject extractWindowData(String rawHtml) {
        if (StringUtil.isEmpty(rawHtml)) {
            return null;
        }
        Matcher matcher = WINDOW_DATA_PATTERN.matcher(rawHtml);
        if (!matcher.find()) {
            return null;
        }
        try {
            return JSONObject.parseObject(matcher.group(1));
        } catch (Exception e) {
            log.warn("无法解析腾讯新闻window.DATA数据: {}", e.getMessage());
            return null;
        }
    }

    private String extractBodyFromWindowData(JSONObject data) {
        if (data == null) {
            return null;
        }
        JSONObject originContent = data.getJSONObject("originContent");
        if (originContent == null) {
            return null;
        }
        String htmlFragment = originContent.getString("text");
        if (StringUtil.isEmpty(htmlFragment)) {
            return null;
        }
        Html contentHtml = new Html(htmlFragment);
        return joinParagraphs(contentHtml.xpath("//div[contains(@class,'rich_media_content')]//p/allText()").all());
    }

    private String extractBodyFromHtml(Html html) {
        return firstNonBlank(
                joinParagraphs(html.xpath("//div[contains(@class,'content-article')]//p/allText()").all()),
                joinParagraphs(html.xpath("//div[contains(@class,'article-content')]//p/allText()").all()),
                joinParagraphs(html.xpath("//article//p/allText()").all())
        );
    }

    private String joinParagraphs(List<String> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String normalized = normalizeBodyLine(paragraph);
            if (!StringUtil.isEmpty(normalized)) {
                cleaned.add(normalized);
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        return String.join("\n", cleaned);
    }

    private String normalizeBodyLine(String value) {
        if (StringUtil.isEmpty(value)) {
            return null;
        }
        String normalized = value.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.matches("IMG_\\d+") || normalized.startsWith("免责声明：")) {
            return null;
        }
        return normalized;
    }

    private String extractMetaContent(Html html, String attrName, String attrValue) {
        return html.xpath(String.format("//meta[@%s='%s']/@content", attrName, attrValue)).get();
    }

    private String cleanTitle(String title) {
        if (StringUtil.isEmpty(title)) {
            return null;
        }
        return title.replace("_腾讯新闻", "").trim();
    }

    private String getString(JSONObject jsonObject, String key) {
        return jsonObject == null ? null : jsonObject.getString(key);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!StringUtil.isEmpty(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private Instant parsePublishTime(String value) {
        if (StringUtil.isEmpty(value)) {
            return null;
        }
        String candidate = value.trim();
        try {
            return Instant.parse(candidate);
        } catch (DateTimeParseException ignore) {
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(candidate, formatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant();
            } catch (DateTimeParseException ignore) {
            }
        }
        log.warn("无法识别腾讯新闻发布时间格式: {}", candidate);
        return null;
    }

    private void recordSemanticFailure(String detail) {
        if (ingestionObservabilityService == null || StringUtil.isEmpty(categoryName)) {
            return;
        }
        ingestionObservabilityService.recordSemanticFailure(categoryName, detail);
    }

    @Override
    public Site getSite() {
        return site;
    }
}
