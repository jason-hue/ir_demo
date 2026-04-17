package cn.edu.bistu.cs.ir.model;

import cn.edu.bistu.cs.ir.utils.StringUtil;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文章与分块ID生成工具。
 */
public final class ArticleIds {

    private static final String CHUNK_SEPARATOR = "#";

    private static final Pattern TENCENT_ARTICLE_PATH_PATTERN = Pattern.compile("^/rain/a/([^/]+)$", Pattern.CASE_INSENSITIVE);

    private static final String TENCENT_CANONICAL_SCHEME = "https";

    private static final String TENCENT_CANONICAL_HOST = "news.qq.com";

    private ArticleIds() {
    }

    public static String generateDocId(String sourceUrl,
                                       String source,
                                       String title,
                                       Instant publishTime) {
        String normalizedUrl = canonicalIdentityUrl(sourceUrl);
        if (!StringUtil.isEmpty(normalizedUrl)) {
            return sha256(normalizedUrl);
        }
        String publishTimeValue = publishTime == null ? "" : publishTime.toString();
        return sha256(String.join("\n",
                normalizeText(source),
                normalizeText(title),
                publishTimeValue));
    }

    public static String generateChunkId(String docId, int chunkIndex) {
        if (StringUtil.isEmpty(docId)) {
            throw new IllegalArgumentException("docId不可以为空");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex不可以小于0");
        }
        return docId + CHUNK_SEPARATOR + chunkIndex;
    }

    public static String normalizeSourceUrl(String sourceUrl) {
        if (StringUtil.isEmpty(sourceUrl)) {
            return null;
        }
        try {
            URI uri = new URI(sourceUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!StringUtil.isEmpty(scheme) && !StringUtil.isEmpty(host)) {
                String path = uri.getPath();
                if (StringUtil.isEmpty(path)) {
                    path = "/";
                }
                while (path.length() > 1 && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                return new URI(scheme.toLowerCase(Locale.ROOT), uri.getUserInfo(), host.toLowerCase(Locale.ROOT), uri.getPort(), path, null, null)
                        .toString();
            }
        } catch (URISyntaxException ignore) {
        }
        String normalized = sourceUrl.trim().toLowerCase(Locale.ROOT);
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        int fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 文章内容身份规则：腾讯文章优先使用归一化后的 canonical URL 作为主身份，
     * 其它来源沿用通用URL归一化规则。
     */
    public static String canonicalIdentityUrl(String sourceUrl) {
        String normalizedTencentUrl = normalizeTencentArticleUrl(sourceUrl);
        if (!StringUtil.isEmpty(normalizedTencentUrl)) {
            return normalizedTencentUrl;
        }
        return normalizeSourceUrl(sourceUrl);
    }

    /**
     * 腾讯文章canonical URL规则：
     * 1. 只识别`/rain/a/{articleId}`文章路径；
     * 2. 强制收敛到`https://news.qq.com`；
     * 3. 去除query、fragment与尾部斜杠。
     */
    public static String normalizeTencentArticleUrl(String sourceUrl) {
        if (StringUtil.isEmpty(sourceUrl)) {
            return null;
        }
        String normalized = normalizeSourceUrl(sourceUrl);
        if (StringUtil.isEmpty(normalized)) {
            return null;
        }
        try {
            URI uri = new URI(normalized);
            String host = uri.getHost();
            if (StringUtil.isEmpty(host) || !isTencentArticleHost(host)) {
                return null;
            }
            String path = uri.getPath();
            if (StringUtil.isEmpty(path)) {
                return null;
            }
            Matcher matcher = TENCENT_ARTICLE_PATH_PATTERN.matcher(path);
            if (!matcher.matches()) {
                return null;
            }
            return new URI(TENCENT_CANONICAL_SCHEME,
                    null,
                    TENCENT_CANONICAL_HOST,
                    -1,
                    "/rain/a/" + matcher.group(1).toUpperCase(Locale.ROOT),
                    null,
                    null)
                    .toString();
        } catch (URISyntaxException ignore) {
            return null;
        }
    }

    private static boolean isTencentArticleHost(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return TENCENT_CANONICAL_HOST.equals(normalizedHost) || "new.qq.com".equals(normalizedHost);
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JVM不支持SHA-256", e);
        }
    }
}
