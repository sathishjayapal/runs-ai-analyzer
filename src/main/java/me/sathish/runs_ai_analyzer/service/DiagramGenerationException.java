package me.sathish.runs_ai_analyzer.service;

public class DiagramGenerationException extends RuntimeException {

    public DiagramGenerationException(String message) {
        super(message);
    }

    public DiagramGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
