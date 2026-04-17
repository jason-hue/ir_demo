package cn.edu.bistu.cs.ir;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "irdemo.dir.home=workspace/test-app-context",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
public class IrDemoApplicationTest {
    @Test
    void contextLoads() {

    }
}
