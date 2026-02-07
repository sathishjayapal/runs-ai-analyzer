package me.sathish.runs_ai_analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;

@SpringBootApplication(exclude = {OllamaChatAutoConfiguration.class})
public class RunsAiAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RunsAiAnalyzerApplication.class, args);
    }
}
