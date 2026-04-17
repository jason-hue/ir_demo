package cn.edu.bistu.cs.ir;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest(properties = {
        "irdemo.dir.home=workspace/test-default-runtime-isolation",
        "irdemo.dir.idx=${irdemo.dir.home}/idx",
        "irdemo.dir.crawler=${irdemo.dir.home}/crawler"
})
class DefaultRuntimeIsolationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Test
    void defaultRuntimeDoesNotEnableDemoSeedOrDemoHappyBeans() {
        Assertions.assertAll(
                () -> Assertions.assertEquals("false", environment.getProperty("app.seed.lucene.enabled", "false")),
                () -> Assertions.assertArrayEquals(new String[0], environment.getActiveProfiles()),
                () -> Assertions.assertFalse(applicationContext.containsBean("demoHappyProviderStatusService")),
                () -> Assertions.assertFalse(applicationContext.containsBean("demoHappyChatModel")),
                () -> Assertions.assertFalse(applicationContext.containsBean("demoHappyVectorStore"))
        );
    }
}
