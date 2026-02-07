package me.sathish.runs_ai_analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchResponse {

    private String query;
    private List<SearchResult> results;
    private int totalResults;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResult {
        private String documentId;
        private String content;
        private Map<String, Object> metadata;
        private Double score;
    }
}
