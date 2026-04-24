package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import cn.edu.bistu.cs.ir.model.Article;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleEmbeddingServiceBackfillTest {

    @Test
    void backfillEmbedsEachChunkSeparatelyAndPreservesOrder() {
        Article article = buildArticle();

        ArticleChunkMetadata firstChunk = chunk("doc-1#0", 0, "第一段新闻检索助手内容");
        ArticleChunkMetadata secondChunk = chunk("doc-1#1", 1, "第二段AI新闻推荐内容");

        ArticleChunkingService chunkingService = mock(ArticleChunkingService.class);
        when(chunkingService.chunk(article)).thenReturn(List.of(firstChunk, secondChunk));

        AtomicInteger callCount = new AtomicInteger();
        List<List<String>> requestedInputs = new ArrayList<>();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        doAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(0);
            requestedInputs.add(List.copyOf(inputs));
            if (inputs.size() != 1) {
                throw new AssertionError("expected one chunk per embedding request");
            }
            int callIndex = callCount.getAndIncrement();
            return embeddingResponse(
                    List.of(embedding(new float[] {(float) callIndex + 0.1f, (float) callIndex + 0.2f})),
                    "nomic-embed-text:latest");
        }).when(embeddingModel).embedForResponse(anyList());

        ArticleEmbeddingService service = buildService(chunkingService, embeddingModel, false);

        List<ArticleChunkEmbedding> embeddings = service.generateEmbeddingsForBackfill(article);

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, embeddings.size()),
                () -> Assertions.assertEquals("doc-1#0", embeddings.get(0).metadata().getChunkId()),
                () -> Assertions.assertEquals("doc-1#1", embeddings.get(1).metadata().getChunkId()),
                () -> Assertions.assertArrayEquals(new float[] {0.1f, 0.2f}, embeddings.get(0).vector()),
                () -> Assertions.assertArrayEquals(new float[] {1.1f, 1.2f}, embeddings.get(1).vector()),
                () -> Assertions.assertEquals(List.of(List.of("第一段新闻检索助手内容"), List.of("第二段AI新闻推荐内容")), requestedInputs)
        );
        verify(embeddingModel, times(2)).embedForResponse(anyList());
    }

    @Test
    void backfillReturnsEmptyWhenSingleChunkBatchReturnsWrongEmbeddingCount() {
        Article article = buildArticle();
        ArticleChunkMetadata firstChunk = chunk("doc-1#0", 0, "第一段新闻检索助手内容");
        ArticleChunkMetadata secondChunk = chunk("doc-1#1", 1, "第二段AI新闻推荐内容");

        ArticleChunkingService chunkingService = mock(ArticleChunkingService.class);
        when(chunkingService.chunk(article)).thenReturn(List.of(firstChunk, secondChunk));

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embedForResponse(anyList())).thenReturn(
                embeddingResponse(List.of(embedding(new float[] {0.1f, 0.2f}), embedding(new float[] {0.3f, 0.4f})),
                        "nomic-embed-text:latest"));

        ArticleEmbeddingService service = buildService(chunkingService, embeddingModel, false);

        Assertions.assertTrue(service.generateEmbeddingsForBackfill(article).isEmpty());
    }

    @Test
    void backfillEmbeddingIgnoresLexicalOnlyGateAndStillCallsEmbeddingModel() {
        Article article = buildArticle();
        ArticleChunkMetadata chunk = chunk("doc-1#0", 0, "新闻检索助手测试分块");

        ArticleChunkingService chunkingService = mock(ArticleChunkingService.class);
        when(chunkingService.chunk(article)).thenReturn(List.of(chunk));

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embedForResponse(anyList())).thenReturn(
                embeddingResponse(List.of(embedding(new float[] {0.1f, 0.2f, 0.3f})), null));

        ArticleEmbeddingService service = buildService(chunkingService, embeddingModel, true);

        Assertions.assertAll(
                () -> Assertions.assertTrue(service.generateEmbeddings(article).isEmpty()),
                () -> Assertions.assertEquals(1, service.generateEmbeddingsForBackfill(article).size()),
                () -> Assertions.assertArrayEquals(
                        new float[] {0.1f, 0.2f, 0.3f},
                        service.generateEmbeddingsForBackfill(article).getFirst().vector())
        );
    }

    private static Article buildArticle() {
        Article article = new Article();
        article.setDocId("doc-1");
        article.setSource("tencent-news");
        article.setSourceUrl("https://news.qq.com/rain/a/20240318A01AB000");
        article.setTitle("腾讯新闻推出新闻检索助手试点");
        article.setBody("北京信息科技大学在课堂上试点新闻检索助手，帮助学生整理新闻语料。".repeat(4));
        article.setPublishTime(Instant.parse("2024-03-18T09:30:00Z"));
        return article;
    }

    private static ArticleChunkMetadata chunk(String chunkId, int chunkIndex, String chunkText) {
        ArticleChunkMetadata chunk = new ArticleChunkMetadata();
        chunk.setChunkId(chunkId);
        chunk.setDocId("doc-1");
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkText(chunkText);
        chunk.setCharCount(chunkText.length());
        return chunk;
    }

    @SuppressWarnings("unchecked")
    private static ArticleEmbeddingService buildService(ArticleChunkingService chunkingService,
                                                        EmbeddingModel embeddingModel,
                                                        boolean lexicalOnlyMode) {
        ObjectProvider<EmbeddingModel> embeddingModelProvider = mock(ObjectProvider.class);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);

        AiFallbackService aiFallbackService = mock(AiFallbackService.class);
        when(aiFallbackService.isLexicalOnlyMode()).thenReturn(lexicalOnlyMode);

        return new ArticleEmbeddingService(
                chunkingService,
                embeddingModelProvider,
                aiFallbackService,
                new AiProperties());
    }

    private static Embedding embedding(float[] vector) {
        return new Embedding(vector, 0);
    }

    private static EmbeddingResponse embeddingResponse(List<Embedding> embeddings, String modelName) {
        EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
        metadata.setModel(modelName);
        return new EmbeddingResponse(embeddings, metadata);
    }
}
