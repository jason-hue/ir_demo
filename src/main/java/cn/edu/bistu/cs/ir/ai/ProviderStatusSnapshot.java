package cn.edu.bistu.cs.ir.ai;

public record ProviderStatusSnapshot(
        ProviderStatus chat,
        ProviderStatus ollamaEmbedding,
        ProviderStatus qdrant,
        boolean lexicalAvailable,
        AiFallbackMode fallbackMode
) {
}
