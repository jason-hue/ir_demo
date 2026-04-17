package cn.edu.bistu.cs.ir.index;

import cn.edu.bistu.cs.ir.config.AppRuntimeProperties;
import cn.edu.bistu.cs.ir.ai.ArticleChunkVectorSyncService;
import cn.edu.bistu.cs.ir.model.Blog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 可选的Lucene启动种子加载器，用于在隔离工作目录中为词法检索烟测预置文章文档。
 */
@Component
public class LuceneSeedRunner {

    private static final Logger log = LoggerFactory.getLogger(LuceneSeedRunner.class);

    private final AppRuntimeProperties appRuntimeProperties;

    private final ResourceLoader resourceLoader;

    private final ObjectMapper objectMapper;

    private final IdxService idxService;

    private final ArticleChunkVectorSyncService articleChunkVectorSyncService;

    public LuceneSeedRunner(AppRuntimeProperties appRuntimeProperties,
                            ResourceLoader resourceLoader,
                            ObjectMapper objectMapper,
                            IdxService idxService,
                            ArticleChunkVectorSyncService articleChunkVectorSyncService) {
        this.appRuntimeProperties = appRuntimeProperties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.idxService = idxService;
        this.articleChunkVectorSyncService = articleChunkVectorSyncService;
    }

    @PostConstruct
    public void run() throws Exception {
        AppRuntimeProperties.LuceneSeedProperties properties = appRuntimeProperties.getSeed().getLucene();
        if (!properties.isEnabled()) {
            return;
        }

        Resource resource = resourceLoader.getResource(properties.getResource());
        if (!resource.exists()) {
            throw new IllegalStateException("无法找到Lucene启动种子资源: " + properties.getResource());
        }

        try (InputStream inputStream = resource.getInputStream()) {
            List<Blog> articles = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            int seeded = 0;
            for (Blog article : articles) {
                article.ensureDocId();
                if (idxService.addDocument(ArticleIdxFields.ID, article.getDocId(), LucenePipeline.toDoc(article))) {
                    seeded++;
                    articleChunkVectorSyncService.syncArticle(article);
                }
            }
            log.info("已从资源[{}]完成Lucene启动种子写入，共[{}]篇文章", properties.getResource(), seeded);
        }
    }
}
