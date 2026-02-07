package me.sathish.runs_ai_analyzer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchRequest {

    @NotBlank(message = "Query is required")
    private String query;

    private Integer topK;
}
