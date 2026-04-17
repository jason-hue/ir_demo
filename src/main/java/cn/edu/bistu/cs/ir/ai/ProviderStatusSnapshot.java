package cn.edu.bistu.cs.ir.ai;

public record ProviderStatusSnapshot(
        ProviderStatus ollamaChat,
        ProviderStatus ollamaEmbedding,
        ProviderStatus qdrant,
        boolean lexicalAvailable,
        AiFallbackMode fallbackMode
) {
}
