package me.sathish.runs_ai_analyzer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagramGenerationRequest {

    @Builder.Default
    private String diagramType = "SUMMARY";

    @Builder.Default
    private String format = "PNG";
}
