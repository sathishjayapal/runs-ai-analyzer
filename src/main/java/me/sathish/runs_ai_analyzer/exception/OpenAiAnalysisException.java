package me.sathish.runs_ai_analyzer.exception;

public class OpenAiAnalysisException extends RuntimeException {

    public OpenAiAnalysisException(String message) {
        super(message);
    }

    public OpenAiAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
