package me.sathish.runs_ai_analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class RunsAiAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RunsAiAnalyzerApplication.class, args);
    }
}
