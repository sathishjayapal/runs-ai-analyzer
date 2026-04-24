package me.sathish.runs_ai_analyzer.repository;

import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog;
import me.sathish.runs_ai_analyzer.entity.AnalysisProcessingLog.ProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnalysisProcessingLogRepository extends JpaRepository<AnalysisProcessingLog, Long> {

    Optional<AnalysisProcessingLog> findByActivityIdAndDatabaseId(String activityId, Long databaseId);

    List<AnalysisProcessingLog> findByProcessingStatusOrderByCreatedAtAsc(ProcessingStatus status);

    @Query("SELECT a FROM AnalysisProcessingLog a WHERE a.processingStatus = :status " +
           "AND a.retryCount < :maxRetries " +
           "AND (a.lastRetryAt IS NULL OR a.lastRetryAt < :retryThreshold) " +
           "ORDER BY a.createdAt ASC")
    List<AnalysisProcessingLog> findRetryableFailed(
            @Param("status") ProcessingStatus status,
            @Param("maxRetries") int maxRetries,
            @Param("retryThreshold") LocalDateTime retryThreshold);

    @Query("SELECT COUNT(a) FROM AnalysisProcessingLog a WHERE a.processingStatus = :status")
    long countByStatus(@Param("status") ProcessingStatus status);

    List<AnalysisProcessingLog> findByActivityIdIn(List<String> activityIds);
}
