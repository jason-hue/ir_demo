package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.config.AiProperties;
import cn.edu.bistu.cs.ir.index.ArticleIdxFields;
import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.model.Article;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import com.hankcs.lucene.HanLPAnalyzer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class HybridRetrievalService {

    static final String MODE_HYBRID = "hybrid";

    static final String MODE_LEXICAL_ONLY = "lexical_only";

    static final int RRF_K = 60;

    static final int LEXICAL_TOP_K = 20;

    static final int VECTOR_TOP_K = 20;

    static final long REQUEST_TIMEOUT_GUARD_BAND_MILLIS_FLOOR = 50L;

    static final long REQUEST_TIMEOUT_GUARD_BAND_MILLIS_CEILING = 2_000L;

    static final long REQUEST_TIMEOUT_GUARD_BAND_DIVISOR = 60L;

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final IdxService idxService;

    private final ArticleChunkingService articleChunkingService;

    private final ProviderStatusService providerStatusService;

    private final ObjectProvider<VectorStore> vectorStoreProvider;

    private final Duration vectorRetrievalTimeout;

    private final double vectorSimilarityThreshold;

    public HybridRetrievalService(IdxService idxService,
                                  ArticleChunkingService articleChunkingService,
                                  ProviderStatusService providerStatusService,
                                  ObjectProvider<VectorStore> vectorStoreProvider,
                                  AiProperties aiProperties) {
        this.idxService = idxService;
        this.articleChunkingService = articleChunkingService;
        this.providerStatusService = providerStatusService;
        this.vectorStoreProvider = vectorStoreProvider;
        this.vectorRetrievalTimeout = aiProperties.getRetrieval().getVectorTimeout();
        this.vectorSimilarityThreshold = aiProperties.getRetrieval().getVectorSimilarityThreshold();
    }

    public HybridRetrievalResult retrieve(String question, int pageNo, int pageSize) throws Exception {
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isEmpty()) {
            throw new IllegalArgumentException("question不可以为空");
        }

        int normalizedPageNo = Math.max(pageNo, 1);
        int normalizedPageSize = Math.max(pageSize, 1);
        List<HybridChunkResult> lexicalResults = List.of();
        List<HybridChunkResult> vectorResults = List.of();
        String mode = MODE_LEXICAL_ONLY;
        String degradedReason = null;
        VectorRetrievalAvailability vectorAvailability = vectorRetrievalAvailability();
        if (vectorAvailability.available()) {
            CompletableFuture<List<HybridChunkResult>> lexicalFuture = CompletableFuture.supplyAsync(
                    () -> lexicalRetrieve(normalizedQuestion));
            CompletableFuture<List<HybridChunkResult>> vectorFuture = CompletableFuture.supplyAsync(
                    () -> vectorRetrieve(normalizedQuestion));

            try {
                lexicalResults = lexicalFuture.get();
                long vectorWaitBudgetNanos = vectorRetrievalTimeout.toNanos() - vectorTimeoutGuardBandNanos();
                if (vectorWaitBudgetNanos <= 0L) {
                    throw new TimeoutException("vector retrieval deadline exhausted");
                }
                vectorResults = vectorFuture.get(vectorWaitBudgetNanos, TimeUnit.NANOSECONDS);
                if (!vectorResults.isEmpty()) {
                    mode = MODE_HYBRID;
                }
            }
            catch (TimeoutException e) {
                vectorFuture.cancel(true);
                degradedReason = "向量检索执行超时，已在" + formatTimeout(vectorRetrievalTimeoutMillis()) + "后退化为词法检索";
                log.warn(degradedReason, e);
            }
            catch (InterruptedException e) {
                vectorFuture.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("混合检索等待向量结果时被中断", e);
            }
            catch (ExecutionException e) {
                degradedReason = resolveFailureMessage(e, "向量检索执行失败，已退化为词法检索");
                log.warn(degradedReason, e);
            }
            catch (CompletionException e) {
                degradedReason = resolveFailureMessage(e, "向量检索执行失败，已退化为词法检索");
                log.warn(degradedReason, e);
            }
        }
        else {
            lexicalResults = lexicalRetrieve(normalizedQuestion);
            degradedReason = vectorAvailability.reason();
        }

        List<HybridChunkResult> fusedResults = fuse(lexicalResults, vectorResults);
        if (!vectorResults.isEmpty()) {
            mode = MODE_HYBRID;
            degradedReason = null;
        }

        HybridRetrievalResult response = new HybridRetrievalResult();
        response.setQuestion(normalizedQuestion);
        response.setMode(mode);
        response.setPageNo(normalizedPageNo);
        response.setPageSize(normalizedPageSize);
        response.setTotalResults(fusedResults.size());
        response.setDegradedReason(degradedReason);
        response.setResults(paginate(fusedResults, normalizedPageNo, normalizedPageSize));
        return response;
    }

    protected boolean isVectorRetrievalAvailable() {
        return vectorRetrievalAvailability().available();
    }

    protected String vectorUnavailableReason() {
        return vectorRetrievalAvailability().reason();
    }

    protected long vectorRetrievalTimeoutMillis() {
        return vectorRetrievalTimeout.toMillis();
    }

    protected long vectorTimeoutGuardBandNanos() {
        return requestTimeoutGuardBandNanos(vectorRetrievalTimeout);
    }

    private long requestTimeoutGuardBandNanos(Duration timeout) {
        long timeoutMillis = timeout.toMillis();
        long guardBandMillis = Math.max(REQUEST_TIMEOUT_GUARD_BAND_MILLIS_FLOOR,
                Math.min(REQUEST_TIMEOUT_GUARD_BAND_MILLIS_CEILING, timeoutMillis / REQUEST_TIMEOUT_GUARD_BAND_DIVISOR));
        return TimeUnit.MILLISECONDS.toNanos(guardBandMillis);
    }

    private VectorRetrievalAvailability vectorRetrievalAvailability() {
        ProviderStatusSnapshot snapshot = providerStatusService.snapshot();
        if (snapshot.ollamaEmbedding().state() != ProviderAvailabilityState.AVAILABLE) {
            return VectorRetrievalAvailability.unavailableBecause(snapshot.ollamaEmbedding().detail());
        }
        if (snapshot.qdrant().state() != ProviderAvailabilityState.AVAILABLE) {
            return VectorRetrievalAvailability.unavailableBecause(snapshot.qdrant().detail());
        }
        VectorStore vectorStore;
        try {
            vectorStore = vectorStoreProvider.getIfAvailable();
        }
        catch (RuntimeException e) {
            return VectorRetrievalAvailability.unavailableBecause(
                    resolveFailureMessage(e, "向量检索初始化失败，已退化为词法检索"));
        }
        if (vectorStore == null) {
            return VectorRetrievalAvailability.unavailableBecause("向量检索未启用或EmbeddingModel/Qdrant VectorStore不可用");
        }
        return VectorRetrievalAvailability.availableNow();
    }

    protected List<HybridChunkResult> lexicalRetrieve(String question) {
        try {
            List<Document> docs = idxService.queryByKw(question, 1, LEXICAL_TOP_K);
            List<String> queryTerms = tokenize(question);
            List<LexicalChunkCandidate> candidates = new ArrayList<>();
            for (int articleRank = 0; articleRank < docs.size(); articleRank++) {
                Article article = toArticle(docs.get(articleRank));
                List<ArticleChunkMetadata> chunks = articleChunkingService.chunk(article);
                if (chunks.isEmpty()) {
                    chunks = fallbackChunks(article);
                }
                for (ArticleChunkMetadata chunk : chunks) {
                    candidates.add(new LexicalChunkCandidate(article,
                            chunk,
                            articleRank + 1,
                            lexicalChunkScore(queryTerms, article, chunk)));
                }
            }
            candidates.sort(Comparator
                    .comparingInt(LexicalChunkCandidate::score).reversed()
                    .thenComparingInt(LexicalChunkCandidate::articleRank)
                    .thenComparing(candidate -> candidate.chunk().getChunkIndex())
                    .thenComparing(candidate -> candidate.chunk().getChunkId()));

            List<HybridChunkResult> results = new ArrayList<>();
            int limit = Math.min(LEXICAL_TOP_K, candidates.size());
            for (int i = 0; i < limit; i++) {
                results.add(toChunkResult(candidates.get(i).article(), candidates.get(i).chunk(), i + 1, null));
            }
            return List.copyOf(results);
        }
        catch (Exception e) {
            throw new IllegalStateException("词法检索执行失败", e);
        }
    }

    protected List<HybridChunkResult> vectorRetrieve(String question) {
        List<String> queryTerms = tokenize(question);
        VectorStore vectorStore;
        try {
            vectorStore = Objects.requireNonNull(vectorStoreProvider.getIfAvailable(), "VectorStore不可用");
        }
        catch (RuntimeException e) {
            throw new IllegalStateException(resolveFailureMessage(e, "向量检索初始化失败，已退化为词法检索"), e);
        }
        List<org.springframework.ai.document.Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(VECTOR_TOP_K)
                        .similarityThreshold(vectorSimilarityThreshold)
                        .build());
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }

        List<HybridChunkResult> results = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            org.springframework.ai.document.Document document = docs.get(i);
            if (!hasVectorLexicalEvidence(queryTerms, document)) {
                continue;
            }
            results.add(fromVectorDocument(document, results.size() + 1));
        }
        return List.copyOf(results);
    }

    private boolean hasVectorLexicalEvidence(List<String> queryTerms, org.springframework.ai.document.Document document) {
        if (queryTerms.isEmpty()) {
            return true;
        }
        Map<String, Object> metadata = document.getMetadata();
        String title = normalize(stringMetadata(metadata, "title"));
        String chunkText = normalize(firstNonBlank(document.getText(),
                stringMetadata(metadata, ArticleChunkVectorSyncService.CONTENT_FIELD_NAME)));
        for (String term : queryTerms) {
            if (countOccurrences(title, term) > 0 || countOccurrences(chunkText, term) > 0) {
                return true;
            }
        }
        return false;
    }

    List<HybridChunkResult> fuse(List<HybridChunkResult> lexicalResults, List<HybridChunkResult> vectorResults) {
        Map<String, FusionAccumulator> merged = new LinkedHashMap<>();
        addContribution(merged, lexicalResults, true);
        addContribution(merged, vectorResults, false);

        List<HybridChunkResult> fused = new ArrayList<>();
        for (FusionAccumulator value : merged.values()) {
            HybridChunkResult chunk = value.result();
            chunk.setFusedScore(value.fusedScore());
            fused.add(chunk);
        }
        fused.sort(Comparator
                .comparing(HybridChunkResult::getFusedScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(this::bestRank)
                .thenComparing(result -> nullSafe(result.getChunkId()))
                .thenComparing(result -> nullSafe(result.getDocId())));
        return List.copyOf(fused);
    }

    private void addContribution(Map<String, FusionAccumulator> merged,
                                 List<HybridChunkResult> results,
                                 boolean lexical) {
        for (int i = 0; i < results.size(); i++) {
            HybridChunkResult incoming = copyOf(results.get(i));
            String key = chunkIdentity(incoming);
            FusionAccumulator accumulator = merged.computeIfAbsent(key, ignored -> new FusionAccumulator(incoming));
            accumulator.merge(incoming);
            accumulator.addScore(1.0d / (RRF_K + i + 1));
            if (lexical) {
                accumulator.result().setLexicalRank(i + 1);
            }
            else {
                accumulator.result().setVectorRank(i + 1);
            }
        }
    }

    private Article toArticle(Document doc) {
        Article article = new Article();
        article.setDocId(doc.get(ArticleIdxFields.ID));
        article.setTitle(doc.get(ArticleIdxFields.TITLE));
        article.setBody(doc.get(ArticleIdxFields.CONTENT));
        article.setSource(doc.get(ArticleIdxFields.SOURCE));
        article.setSourceUrl(doc.get(ArticleIdxFields.SOURCE_URL));
        article.setSection(doc.get(ArticleIdxFields.SECTION));
        article.setAuthor(doc.get(ArticleIdxFields.AUTHOR));
        article.setByline(doc.get(ArticleIdxFields.BYLINE));
        String publishTime = doc.get(ArticleIdxFields.TIME);
        if (publishTime != null && !publishTime.isBlank()) {
            article.setPublishTime(Instant.ofEpochMilli(Long.parseLong(publishTime)));
        }
        return article;
    }

    private List<ArticleChunkMetadata> fallbackChunks(Article article) {
        String fallbackText = firstNonBlank(article.getBody(), article.getTitle());
        if (fallbackText == null || fallbackText.isBlank()) {
            return List.of();
        }
        Article fallback = new Article();
        fallback.setDocId(article.getDocId());
        fallback.setBody(fallbackText);
        return articleChunkingService.chunk(fallback);
    }

    private int lexicalChunkScore(List<String> queryTerms, Article article, ArticleChunkMetadata chunk) {
        String title = normalize(article.getTitle());
        String chunkText = normalize(chunk.getChunkText());
        int score = 0;
        for (String term : queryTerms) {
            score += countOccurrences(title, term) * 3;
            score += countOccurrences(chunkText, term);
        }
        return score;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        try (Analyzer analyzer = new HanLPAnalyzer();
             TokenStream tokenStream = analyzer.tokenStream("", text)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                String term = termAttribute.toString().trim().toLowerCase();
                if (!term.isEmpty()) {
                    tokens.add(term);
                }
            }
            tokenStream.end();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (tokens.isEmpty()) {
            tokens.add(text.trim().toLowerCase());
        }
        return List.copyOf(tokens);
    }

    private HybridChunkResult toChunkResult(Article article,
                                            ArticleChunkMetadata chunk,
                                            Integer lexicalRank,
                                            Integer vectorRank) {
        HybridChunkResult result = new HybridChunkResult();
        result.setDocId(article.getDocId());
        result.setChunkId(chunk.getChunkId());
        result.setTitle(article.getTitle());
        result.setSource(article.getSource());
        result.setSourceUrl(article.getSourceUrl());
        result.setSection(article.getSection());
        result.setAuthor(article.getAuthor());
        result.setByline(article.getByline());
        result.setPublishTime(article.getPublishTime() == null ? null : article.getPublishTime().toString());
        result.setChunkIndex(chunk.getChunkIndex());
        result.setCharCount(chunk.getCharCount());
        result.setChunkText(chunk.getChunkText());
        result.setLexicalRank(lexicalRank);
        result.setVectorRank(vectorRank);
        return result;
    }

    private HybridChunkResult fromVectorDocument(org.springframework.ai.document.Document document, int vectorRank) {
        Map<String, Object> metadata = document.getMetadata();
        HybridChunkResult result = new HybridChunkResult();
        result.setDocId(stringMetadata(metadata, "docId"));
        result.setChunkId(firstNonBlank(stringMetadata(metadata, "chunkId"), document.getId()));
        result.setTitle(stringMetadata(metadata, "title"));
        result.setSource(stringMetadata(metadata, "source"));
        result.setSourceUrl(stringMetadata(metadata, "sourceUrl"));
        result.setPublishTime(stringMetadata(metadata, "publishTime"));
        result.setChunkIndex(integerMetadata(metadata, "chunkIndex"));
        result.setCharCount(integerMetadata(metadata, "charCount"));
        result.setChunkText(firstNonBlank(document.getText(), stringMetadata(metadata, ArticleChunkVectorSyncService.CONTENT_FIELD_NAME)));
        result.setVectorRank(vectorRank);
        return result;
    }

    private List<HybridChunkResult> paginate(List<HybridChunkResult> results, int pageNo, int pageSize) {
        int start = (pageNo - 1) * pageSize;
        if (start >= results.size()) {
            return List.of();
        }
        int end = Math.min(start + pageSize, results.size());
        return List.copyOf(results.subList(start, end));
    }

    private HybridChunkResult copyOf(HybridChunkResult source) {
        HybridChunkResult target = new HybridChunkResult();
        target.setDocId(source.getDocId());
        target.setChunkId(source.getChunkId());
        target.setTitle(source.getTitle());
        target.setSource(source.getSource());
        target.setSourceUrl(source.getSourceUrl());
        target.setPublishTime(source.getPublishTime());
        target.setSection(source.getSection());
        target.setAuthor(source.getAuthor());
        target.setByline(source.getByline());
        target.setChunkIndex(source.getChunkIndex());
        target.setCharCount(source.getCharCount());
        target.setChunkText(source.getChunkText());
        target.setLexicalRank(source.getLexicalRank());
        target.setVectorRank(source.getVectorRank());
        target.setFusedScore(source.getFusedScore());
        return target;
    }

    private String chunkIdentity(HybridChunkResult chunk) {
        if (chunk.getChunkId() != null && !chunk.getChunkId().isBlank()) {
            return chunk.getChunkId();
        }
        return nullSafe(chunk.getDocId()) + "#" + (chunk.getChunkIndex() == null ? -1 : chunk.getChunkIndex());
    }

    private int bestRank(HybridChunkResult result) {
        int lexicalRank = result.getLexicalRank() == null ? Integer.MAX_VALUE : result.getLexicalRank();
        int vectorRank = result.getVectorRank() == null ? Integer.MAX_VALUE : result.getVectorRank();
        return Math.min(lexicalRank, vectorRank);
    }

    private String formatTimeout(long timeoutMillis) {
        if (timeoutMillis % 1000 == 0) {
            return (timeoutMillis / 1000) + "秒";
        }
        return timeoutMillis + "毫秒";
    }

    private String resolveFailureMessage(Throwable throwable, String fallback) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null || root.getMessage().isBlank() ? fallback : root.getMessage();
    }

    private String stringMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase();
    }

    private int countOccurrences(String text, String term) {
        if (text == null || text.isBlank() || term == null || term.isBlank()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(term, index)) >= 0) {
            count++;
            index += term.length();
        }
        return count;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record VectorRetrievalAvailability(boolean available, String reason) {

        private static VectorRetrievalAvailability availableNow() {
            return new VectorRetrievalAvailability(true, null);
        }

        private static VectorRetrievalAvailability unavailableBecause(String reason) {
            return new VectorRetrievalAvailability(false, reason);
        }
    }

    private record LexicalChunkCandidate(Article article,
                                         ArticleChunkMetadata chunk,
                                         int articleRank,
                                         int score) {
    }

    private static final class FusionAccumulator {

        private final HybridChunkResult result;

        private double fusedScore;

        private FusionAccumulator(HybridChunkResult result) {
            this.result = result;
        }

        private HybridChunkResult result() {
            return result;
        }

        private double fusedScore() {
            return fusedScore;
        }

        private void addScore(double score) {
            fusedScore += score;
        }

        private void merge(HybridChunkResult incoming) {
            result.setDocId(prefer(result.getDocId(), incoming.getDocId()));
            result.setChunkId(prefer(result.getChunkId(), incoming.getChunkId()));
            result.setTitle(prefer(result.getTitle(), incoming.getTitle()));
            result.setSource(prefer(result.getSource(), incoming.getSource()));
            result.setSourceUrl(prefer(result.getSourceUrl(), incoming.getSourceUrl()));
            result.setPublishTime(prefer(result.getPublishTime(), incoming.getPublishTime()));
            result.setSection(prefer(result.getSection(), incoming.getSection()));
            result.setAuthor(prefer(result.getAuthor(), incoming.getAuthor()));
            result.setByline(prefer(result.getByline(), incoming.getByline()));
            result.setChunkIndex(prefer(result.getChunkIndex(), incoming.getChunkIndex()));
            result.setCharCount(prefer(result.getCharCount(), incoming.getCharCount()));
            result.setChunkText(preferContent(result.getChunkText(), incoming.getChunkText()));
            result.setLexicalRank(prefer(result.getLexicalRank(), incoming.getLexicalRank()));
            result.setVectorRank(prefer(result.getVectorRank(), incoming.getVectorRank()));
        }

        private String prefer(String current, String incoming) {
            return current != null && !current.isBlank() ? current : incoming;
        }

        private Integer prefer(Integer current, Integer incoming) {
            return current != null ? current : incoming;
        }

        private String preferContent(String current, String incoming) {
            if (incoming == null || incoming.isBlank()) {
                return current;
            }
            if (current == null || current.isBlank()) {
                return incoming;
            }
            return incoming.length() > current.length() ? incoming : current;
        }
    }
}
