package me.sathish.runs_ai_analyzer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=test-key"
})
class RunsAiAnalyzerApplicationTests {

    @Test
    void contextLoads() {
    }
}
