package cn.edu.bistu.cs.ir.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@ConfigurationProperties(prefix = "irdemo.ai")
@Component
@Getter
@Setter
public class AiProperties {

    private String chatProvider = "ollama";

    private Ollama ollama = new Ollama();

    private Gemini gemini = new Gemini();

    private Glm glm = new Glm();

    private Retrieval retrieval = new Retrieval();

    private Qdrant qdrant = new Qdrant();

    private ProviderStatus providerStatus = new ProviderStatus();

    @Getter
    @Setter
    public static class Retrieval {

        private Duration vectorTimeout = Duration.ofSeconds(120);

        private double vectorSimilarityThreshold = 0.6d;
    }

    @Getter
    @Setter
    public static class Ollama {

        private boolean enabled = true;

        private String baseUrl = "http://127.0.0.1:11434";

        private String chatModel = "llama3.2:1b";

        private String embeddingModel = "nomic-embed-text";

        private Duration chatTimeout = Duration.ofSeconds(120);

        private boolean warmupEnabled = true;

        private Duration warmupTimeout = Duration.ofSeconds(45);

        private Duration keepWarmInterval = Duration.ofMinutes(4);

        private String keepAlive = "10m";

        private String warmupPrompt = "请只回复：ready";

        private int chatContextTopK = 4;

        private int chatChunkCharLimit = 320;

        private int chatNumPredict = 96;

        private int chatNumCtx = 2048;

        private double chatTemperature = 0.1d;

        private double chatTopP = 0.8d;
    }

    @Getter
    @Setter
    public static class Gemini {

        private boolean enabled = false;

        private String baseUrl = "https://generativelanguage.googleapis.com";

        private String apiKey;

        private String model = "gemini-2.5-flash";

        private Duration chatTimeout = Duration.ofSeconds(120);

        private double temperature = 0.1d;

        private double topP = 0.8d;

        private Integer maxOutputTokens = 512;

        private Integer thinkingBudget = 0;
    }

    @Getter
    @Setter
    public static class Glm {

        private boolean enabled = false;

        private String baseUrl = "https://open.bigmodel.cn";

        private String apiKey;

        private String model = "glm-4-flash";

        private Duration chatTimeout = Duration.ofSeconds(120);

        private double temperature = 0.1d;

        private double topP = 0.8d;

        private Integer maxTokens = 512;
    }

    @Getter
    @Setter
    public static class Qdrant {

        private boolean enabled = false;

        private String host = "127.0.0.1";

        private int httpPort = 6333;

        private int grpcPort = 6334;

        private String collectionName = "news_article_chunks";

        private boolean useTls = false;

        private boolean initializeSchema = false;

        private String apiKey;
    }

    @Getter
    @Setter
    public static class ProviderStatus {

        private Duration connectTimeout = Duration.ofMillis(500);

        private Duration readTimeout = Duration.ofMillis(1500);
    }
}
