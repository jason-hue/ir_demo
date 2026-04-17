package cn.edu.bistu.cs.ir.index;

/**
 * Lucene field names for canonical article documents.
 */
public final class ArticleIdxFields {

    public static final String ID = "ID";
    public static final String TITLE = "TITLE";
    public static final String CONTENT = "CONTENT";
    public static final String TIME = "TIME";
    public static final String AUTHOR = "AUTHOR";
    public static final String BYLINE = "BYLINE";
    public static final String SOURCE = "SOURCE";
    public static final String SOURCE_URL = "SOURCE_URL";
    public static final String SECTION = "SECTION";

    public static final String[] SEARCH_FIELDS = {
            TITLE,
            CONTENT,
            AUTHOR,
            BYLINE,
            SOURCE,
            SECTION
    };

    private ArticleIdxFields() {
    }
}
