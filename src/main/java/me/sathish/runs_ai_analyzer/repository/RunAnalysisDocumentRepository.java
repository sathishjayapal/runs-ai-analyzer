package me.sathish.runs_ai_analyzer.repository;

import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RunAnalysisDocumentRepository extends JpaRepository<RunAnalysisDocument, Long> {

    Optional<RunAnalysisDocument> findByDocumentId(UUID documentId);

    List<RunAnalysisDocument> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime after);

    List<RunAnalysisDocument> findTop10ByOrderByCreatedAtDesc();

    @Query("SELECT r FROM RunAnalysisDocument r WHERE r.activityIds LIKE %:activityId%")
    List<RunAnalysisDocument> findByActivityIdContaining(@Param("activityId") String activityId);

    @Query("SELECT r FROM RunAnalysisDocument r WHERE r.totalDistanceKm >= :minDistance ORDER BY r.createdAt DESC")
    List<RunAnalysisDocument> findByMinimumDistance(@Param("minDistance") Double minDistance);

    @Query("SELECT r FROM RunAnalysisDocument r WHERE r.totalRuns >= :minRuns ORDER BY r.createdAt DESC")
    List<RunAnalysisDocument> findByMinimumRuns(@Param("minRuns") Integer minRuns);
}
