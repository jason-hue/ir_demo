package cn.edu.bistu.cs.ir.support;

import cn.edu.bistu.cs.ir.ai.AiFallbackMode;
import cn.edu.bistu.cs.ir.ai.ArticleChunkingService;
import cn.edu.bistu.cs.ir.ai.ProviderAvailabilityState;
import cn.edu.bistu.cs.ir.ai.ProviderStatus;
import cn.edu.bistu.cs.ir.ai.ProviderStatusSnapshot;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.model.Blog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public final class DemoFixtureSupport {

    public static final String RUNTIME_SEED_RESOURCE = "fixtures/tencent/news/runtime-seed-articles.json";

    private DemoFixtureSupport() {
    }

    public static List<Blog> loadRuntimeSeedArticles(ObjectMapper objectMapper) throws IOException {
        try (InputStream inputStream = new ClassPathResource(RUNTIME_SEED_RESOURCE).getInputStream()) {
            List<Blog> articles = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            articles.forEach(Blog::ensureDocId);
            return List.copyOf(articles);
        }
    }

    public static Blog firstRuntimeSeedArticle(ObjectMapper objectMapper) throws IOException {
        return loadRuntimeSeedArticles(objectMapper).getFirst();
    }

    public static ArticleChunkMetadata firstChunk(ArticleChunkingService articleChunkingService,
                                                  Blog article) {
        return articleChunkingService.chunk(article).getFirst();
    }

    public static ProviderStatusSnapshot availableSnapshot() {
        return new ProviderStatusSnapshot(
                new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true, "ok"),
                true,
                AiFallbackMode.AI_READY);
    }
}
