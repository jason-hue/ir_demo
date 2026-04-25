package cn.edu.bistu.cs.ir;

import cn.edu.bistu.cs.ir.ai.ChatAnswerResult;
import cn.edu.bistu.cs.ir.ai.ChatAnswerService;
import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.support.DemoFixtureSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "app.seed.lucene.enabled=true",
        "irdemo.dir.home=workspace/test-chat-quality-analysis",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler",
        "irdemo.ai.ollama.warmup-timeout=300s",
        "irdemo.ai.ollama.chat-timeout=300s",
        "irdemo.ai.ollama.chat-model=llama3.2:1b"
})
@ActiveProfiles({"demo"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ChatQualityAnalysisTest {

    @Autowired
    private ChatAnswerService chatAnswerService;

    @Autowired
    private IdxService idxService;

    @Autowired
    private VectorStore vectorStore;

    @BeforeAll
    void setup() throws Exception {
        // demo profile via LuceneSeedRunner already seeds automatically!
        // So we just let Spring Boot boot up.
    }

    @AfterAll
    void cleanup() throws Exception {
        cn.edu.bistu.cs.ir.utils.FileUtils.deleteSubDirs("workspace/test-chat-quality-analysis");
    }

    @Test
    void runTrickyQuestions() throws Exception {
        List<String> questions = List.of(
            "新闻检索助手是由马化腾开发的吗？",
            "新闻检索助手是否支持短视频检索？",
            "这门信息检索课的考试成绩是多少分及格？"
        );

        System.out.println("====== BATCH QA RESULTS ======");
        for (String q : questions) {
            System.out.println("Q: " + q);
            ChatAnswerResult res = chatAnswerService.ask(q);
            System.out.println("Mode: " + res.getRetrievalMode());
            System.out.println("Answer: " + res.getAnswer());
            System.out.println("Citations: " + res.getCitations().size());
            if (!res.getCitations().isEmpty()) {
                res.getCitations().forEach(c -> System.out.println("  [chunk] " + c.getChunkText().substring(0, Math.min(20, c.getChunkText().length())) + "..."));
            }
            System.out.println("------------------------------");
        }
    }
}
