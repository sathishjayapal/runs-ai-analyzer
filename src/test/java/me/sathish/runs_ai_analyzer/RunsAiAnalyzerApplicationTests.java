package me.sathish.runs_ai_analyzer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunsAiAnalyzerApplicationTests {

    @Test
    void applicationClassExists() {
        assertThat(RunsAiAnalyzerApplication.class).isNotNull();
    }

    @Test
    void mainMethodExists() throws NoSuchMethodException {
        assertThat(RunsAiAnalyzerApplication.class.getMethod("main", String[].class)).isNotNull();
    }
}
