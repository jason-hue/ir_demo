package cn.edu.bistu.cs.ir.index;

import cn.edu.bistu.cs.ir.utils.FileUtils;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "app.seed.lucene.enabled=true",
        "app.seed.lucene.resource=classpath:fixtures/tencent/news/runtime-seed-articles.json",
        "irdemo.ai.ollama.enabled=false",
        "irdemo.ai.qdrant.enabled=false",
        "irdemo.dir.home=workspace/test-lucene-seed-idempotency",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class LuceneSeedRunnerIdempotencyTest {

    private static final String TEST_HOME = "workspace/test-lucene-seed-idempotency";

    @Autowired
    private LuceneSeedRunner luceneSeedRunner;

    @Test
    void repeatedSeedRunsKeepSingleCopyOfEachFixtureArticle() throws Exception {
        Assertions.assertEquals(3, countIndexedDocs());

        luceneSeedRunner.run();
        luceneSeedRunner.run();

        Assertions.assertAll(
                () -> Assertions.assertEquals(3, countIndexedDocs()),
                () -> Assertions.assertEquals(3, countUniqueDocIds())
        );
    }

    @AfterAll
    static void cleanWorkspace() {
        FileUtils.deleteSubDirs(TEST_HOME);
    }

    private int countIndexedDocs() throws Exception {
        try (FSDirectory directory = FSDirectory.open(Path.of(TEST_HOME, "idx"));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }

    private int countUniqueDocIds() throws Exception {
        try (FSDirectory directory = FSDirectory.open(Path.of(TEST_HOME, "idx"));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            Set<String> docIds = new HashSet<>();
            for (int i = 0; i < reader.maxDoc(); i++) {
                IndexableField field = reader.document(i).getField(ArticleIdxFields.ID);
                if (field != null) {
                    docIds.add(field.stringValue());
                }
            }
            return docIds.size();
        }
    }
}
