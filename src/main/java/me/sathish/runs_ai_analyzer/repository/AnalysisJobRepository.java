package me.sathish.runs_ai_analyzer.repository;

import me.sathish.runs_ai_analyzer.entity.AnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {
}
