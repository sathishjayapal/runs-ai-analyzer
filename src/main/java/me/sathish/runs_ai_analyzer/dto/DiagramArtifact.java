package me.sathish.runs_ai_analyzer.dto;

public record DiagramArtifact(
        String uid,
        String fileName,
        String filePath,
        String contentType,
        int sizeBytes
) {
}
