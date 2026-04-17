package cn.edu.bistu.cs.ir.ai;

import cn.edu.bistu.cs.ir.model.Article;
import cn.edu.bistu.cs.ir.model.ArticleChunkMetadata;
import cn.edu.bistu.cs.ir.utils.StringUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按段落/句子优先的方式对中文文章正文进行确定性分块。
 */
@Service
public class ArticleChunkingService {

    public static final int MAX_CHUNK_CHARS = 500;

    public static final int CHUNK_OVERLAP_CHARS = 100;

    private static final int MAX_NEW_CONTENT_CHARS = MAX_CHUNK_CHARS - CHUNK_OVERLAP_CHARS;

    private static final Pattern PARAGRAPH_SPLIT_PATTERN = Pattern.compile("(?:\\r?\\n){2,}");

    private static final Pattern SENTENCE_PATTERN = Pattern.compile("[^。！？!?；;]+[。！？!?；;]*");

    public List<ArticleChunkMetadata> chunk(Article article) {
        if (article == null) {
            throw new IllegalArgumentException("article不可以为空");
        }
        if (StringUtil.isEmpty(article.getBody())) {
            return List.of();
        }

        ensureDocId(article);

        String body = article.getBody().replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> units = splitIntoSemanticUnits(body);
        List<ArticleChunkMetadata> chunks = new ArrayList<>();
        String overlapPrefix = "";

        for (String unit : units) {
            if (chunks.isEmpty()) {
                appendChunk(chunks, article.getDocId(), unit);
                overlapPrefix = suffixByCodePoints(unit, CHUNK_OVERLAP_CHARS);
                continue;
            }

            String candidate = concatChunkParts(overlapPrefix, unit);
            if (candidate.codePointCount(0, candidate.length()) <= MAX_CHUNK_CHARS) {
                appendChunk(chunks, article.getDocId(), candidate);
                overlapPrefix = suffixByCodePoints(candidate, CHUNK_OVERLAP_CHARS);
                continue;
            }

            String merged = concatChunkParts(chunks.getLast().getChunkText(), unit);
            if (merged.codePointCount(0, merged.length()) <= MAX_CHUNK_CHARS) {
                replaceLastChunk(chunks, article.getDocId(), merged);
                overlapPrefix = suffixByCodePoints(merged, CHUNK_OVERLAP_CHARS);
                continue;
            }

            appendChunk(chunks, article.getDocId(), candidate);
            overlapPrefix = suffixByCodePoints(candidate, CHUNK_OVERLAP_CHARS);
        }

        return List.copyOf(chunks);
    }

    private List<String> splitIntoSemanticUnits(String body) {
        if (body.isEmpty()) {
            return List.of();
        }
        List<String> units = new ArrayList<>();
        int cursor = 0;
        Matcher paragraphMatcher = PARAGRAPH_SPLIT_PATTERN.matcher(body);
        while (paragraphMatcher.find()) {
            addParagraphUnits(units, body.substring(cursor, paragraphMatcher.start()));
            cursor = paragraphMatcher.end();
        }
        addParagraphUnits(units, body.substring(cursor));
        return List.copyOf(units);
    }

    private void addParagraphUnits(List<String> units, String paragraph) {
        String trimmedParagraph = paragraph.strip();
        if (trimmedParagraph.isEmpty()) {
            return;
        }
        if (trimmedParagraph.codePointCount(0, trimmedParagraph.length()) <= MAX_NEW_CONTENT_CHARS) {
            units.add(trimmedParagraph);
            return;
        }

        Matcher sentenceMatcher = SENTENCE_PATTERN.matcher(trimmedParagraph);
        boolean matched = false;
        while (sentenceMatcher.find()) {
            matched = true;
            addSentenceUnits(units, sentenceMatcher.group().strip());
        }
        if (!matched) {
            addSentenceUnits(units, trimmedParagraph);
        }
    }

    private void addSentenceUnits(List<String> units, String sentence) {
        if (sentence.isEmpty()) {
            return;
        }
        if (sentence.codePointCount(0, sentence.length()) <= MAX_NEW_CONTENT_CHARS) {
            units.add(sentence);
            return;
        }

        int totalChars = sentence.codePointCount(0, sentence.length());
        for (int start = 0; start < totalChars; start += MAX_NEW_CONTENT_CHARS) {
            int end = Math.min(start + MAX_NEW_CONTENT_CHARS, totalChars);
            units.add(substringByCodePoints(sentence, start, end));
        }
    }

    private void appendChunk(List<ArticleChunkMetadata> chunks, String docId, String chunkText) {
        chunks.add(buildMetadata(docId, chunks.size(), chunkText));
    }

    private void replaceLastChunk(List<ArticleChunkMetadata> chunks, String docId, String chunkText) {
        int lastIndex = chunks.size() - 1;
        chunks.set(lastIndex, buildMetadata(docId, lastIndex, chunkText));
    }

    private ArticleChunkMetadata buildMetadata(String docId, int chunkIndex, String chunkText) {
        ArticleChunkMetadata metadata = new ArticleChunkMetadata();
        metadata.setDocId(docId);
        metadata.setChunkIndex(chunkIndex);
        metadata.setChunkText(chunkText);
        metadata.setCharCount(chunkText.codePointCount(0, chunkText.length()));
        metadata.deriveChunkId();
        return metadata;
    }

    private String concatChunkParts(String first, String second) {
        if (StringUtil.isEmpty(first)) {
            return second;
        }
        if (StringUtil.isEmpty(second)) {
            return first;
        }
        return first + second;
    }

    static String substringByCodePoints(String text, int start, int end) {
        int startOffset = text.offsetByCodePoints(0, start);
        int endOffset = text.offsetByCodePoints(0, end);
        return text.substring(startOffset, endOffset);
    }

    static String suffixByCodePoints(String text, int maxChars) {
        int charCount = text.codePointCount(0, text.length());
        if (charCount <= maxChars) {
            return text;
        }
        return substringByCodePoints(text, charCount - maxChars, charCount);
    }

    private void ensureDocId(Article article) {
        if (StringUtil.isEmpty(article.getDocId())) {
            article.ensureDocId();
        }
        if (StringUtil.isEmpty(article.getDocId())) {
            throw new IllegalArgumentException("article.docId不可以为空");
        }
    }
}
