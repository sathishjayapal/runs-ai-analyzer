package me.sathish.runs_ai_analyzer.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RootController {

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> root() {
        return ResponseEntity.ok(Map.of(
            "service", "runs-ai-analyzer",
            "status", "running",
            "endpoints", Map.of(
                "health", "/actuator/health",
                "api-docs", "/v3/api-docs",
                "swagger-ui", "/swagger-ui.html",
                "analysis", "/api/v1/analysis",
                "rag", "/api/v1/rag"
            )
        ));
    }
}
