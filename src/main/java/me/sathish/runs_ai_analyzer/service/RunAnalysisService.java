package me.sathish.runs_ai_analyzer.service;

import me.sathish.runs_ai_analyzer.dto.GarminRunDataDTO;
import me.sathish.runs_ai_analyzer.dto.RunAnalysisResponse;

import java.util.List;

public interface RunAnalysisService {

    RunAnalysisResponse analyzeRuns(List<GarminRunDataDTO> runs);

    boolean containsRunData(List<GarminRunDataDTO> runs);
}
