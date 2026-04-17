package cn.edu.bistu.cs.ir.config;

import cn.edu.bistu.cs.ir.ai.AiFallbackMode;
import cn.edu.bistu.cs.ir.ai.ArticleChunkingService;
import cn.edu.bistu.cs.ir.ai.ProviderAvailabilityState;
import cn.edu.bistu.cs.ir.ai.ProviderStatus;
import cn.edu.bistu.cs.ir.ai.ProviderStatusService;
import cn.edu.bistu.cs.ir.ai.ProviderStatusSnapshot;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.model.Blog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
@Profile("demo-happy")
public class DemoHappyPathConfiguration {

    private static final String RUNTIME_SEED_RESOURCE = "classpath:fixtures/tencent/news/runtime-seed-articles.json";

    @Bean
    @Primary
    public ProviderStatusService demoHappyProviderStatusService(AiProperties aiProperties, ObjectMapper objectMapper) {
        return new ProviderStatusService(aiProperties, objectMapper) {
            @Override
            public ProviderStatusSnapshot snapshot() {
                return new ProviderStatusSnapshot(
                        new ProviderStatus("ollama-chat", ProviderAvailabilityState.AVAILABLE, true,
                                "demo-happy seeded chat model is active"),
                        new ProviderStatus("ollama-embedding", ProviderAvailabilityState.AVAILABLE, true,
                                "demo-happy seeded embedding path is active"),
                        new ProviderStatus("qdrant", ProviderAvailabilityState.AVAILABLE, true,
                                "demo-happy seeded vector store is active"),
                        true,
                        AiFallbackMode.AI_READY);
            }
        };
    }

    @Bean
    @Primary
    public ChatModel demoHappyChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "基于固定种子数据的检索问答案例答案[1]：腾讯新闻中的AI相关新闻重点介绍了北京信息科技大学在信息检索课程中试点新闻检索助手，并持续完善本地优先的教学实验环境。"))));
            }
        };
    }

    @Bean
    @Primary
    public VectorStore demoHappyVectorStore(ResourceLoader resourceLoader,
                                            ObjectMapper objectMapper,
                                            ArticleChunkingService articleChunkingService) throws Exception {
        return new SeededDemoVectorStore(loadSeedDocuments(resourceLoader, objectMapper, articleChunkingService));
    }

    private List<org.springframework.ai.document.Document> loadSeedDocuments(ResourceLoader resourceLoader,
                                                                             ObjectMapper objectMapper,
                                                                             ArticleChunkingService articleChunkingService)
            throws Exception {
        Resource resource = resourceLoader.getResource(RUNTIME_SEED_RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException("无法找到demo-happy种子资源: " + RUNTIME_SEED_RESOURCE);
        }

        try (InputStream inputStream = resource.getInputStream()) {
            List<Blog> articles = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            List<org.springframework.ai.document.Document> documents = new ArrayList<>();
            for (Blog article : articles) {
                article.ensureDocId();
                List<ArticleChunkMetadata> chunks = articleChunkingService.chunk(article);
                if (chunks.isEmpty()) {
                    continue;
                }
                documents.add(toVectorDocument(article, chunks.getFirst()));
            }
            return List.copyOf(documents);
        }
    }

    private org.springframework.ai.document.Document toVectorDocument(Blog article, ArticleChunkMetadata chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("docId", article.getDocId());
        metadata.put("chunkId", chunk.getChunkId());
        metadata.put("title", article.getTitle());
        metadata.put("source", article.getSource());
        metadata.put("sourceUrl", article.getSourceUrl());
        metadata.put("publishTime", article.getPublishTime() == null ? null : article.getPublishTime().toString());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("charCount", chunk.getCharCount());
        return new org.springframework.ai.document.Document(chunk.getChunkText(), chunk.getChunkId(), metadata);
    }

    private static final class SeededDemoVectorStore implements VectorStore {

        private final List<org.springframework.ai.document.Document> documents;

        private SeededDemoVectorStore(List<org.springframework.ai.document.Document> documents) {
            this.documents = List.copyOf(documents);
        }

        @Override
        public void add(List<org.springframework.ai.document.Document> documents) {
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
        }

        @Override
        public List<org.springframework.ai.document.Document> similaritySearch(SearchRequest request) {
            String query = request == null ? null : request.getQuery();
            int topK = request == null ? documents.size() : Math.max(request.getTopK(), 1);
            return documents.stream()
                    .sorted(Comparator.comparingInt(document -> -score(document, query)))
                    .limit(topK)
                    .toList();
        }

        private int score(org.springframework.ai.document.Document document, String query) {
            if (query == null || query.isBlank()) {
                return 0;
            }
            String normalizedQuery = query.toLowerCase();
            String title = String.valueOf(document.getMetadata().getOrDefault("title", "")).toLowerCase();
            String text = document.getText() == null ? "" : document.getText().toLowerCase();
            int score = containsScore(title, normalizedQuery) * 3 + containsScore(text, normalizedQuery);
            if (score > 0) {
                return score;
            }
            Set<String> queryTerms = new LinkedHashSet<>();
            normalizedQuery.codePoints()
                    .mapToObj(codePoint -> new String(Character.toChars(codePoint)).trim())
                    .filter(term -> !term.isEmpty())
                    .forEach(queryTerms::add);
            for (String term : queryTerms) {
                score += containsScore(title, term) * 3;
                score += containsScore(text, term);
            }
            return score;
        }

        private int containsScore(String text, String term) {
            if (text == null || text.isBlank() || term == null || term.isBlank()) {
                return 0;
            }
            int score = 0;
            int index = 0;
            while ((index = text.indexOf(term, index)) >= 0) {
                score++;
                index += term.length();
            }
            return score;
        }
    }
}
