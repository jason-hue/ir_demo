package cn.edu.bistu.cs.ir.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

@Configuration
public class AiProviderConfiguration {

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "irdemo.ai.qdrant", name = "enabled", havingValue = "true")
    public QdrantClient qdrantClient(AiProperties aiProperties) {
        AiProperties.Qdrant qdrant = aiProperties.getQdrant();
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(
                qdrant.getHost(),
                qdrant.getGrpcPort(),
                qdrant.isUseTls());
        if (StringUtils.hasText(qdrant.getApiKey())) {
            builder.withApiKey(qdrant.getApiKey());
        }
        return new QdrantClient(builder.build());
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(prefix = "irdemo.ai.qdrant", name = "enabled", havingValue = "true")
    public VectorStore qdrantVectorStore(QdrantClient qdrantClient,
                                         ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                         AiProperties aiProperties) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            return null;
        }
        AiProperties.Qdrant qdrant = aiProperties.getQdrant();
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(qdrant.getCollectionName())
                .initializeSchema(qdrant.isInitializeSchema())
                .build();
    }
}
