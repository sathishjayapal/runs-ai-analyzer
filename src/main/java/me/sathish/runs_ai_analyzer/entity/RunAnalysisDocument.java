package me.sathish.runs_ai_analyzer.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "run_analysis_document")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunAnalysisDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "primary_sequence")
    @SequenceGenerator(name = "primary_sequence", sequenceName = "primary_sequence", allocationSize = 1)
    private Long id;

    @Column(name = "document_id", nullable = false, unique = true)
    private UUID documentId;

    @Column(name = "activity_ids", nullable = false, columnDefinition = "TEXT")
    private String activityIds;

    @Column(name = "query_text", nullable = false, columnDefinition = "TEXT")
    private String queryText;

    @Column(name = "analysis_content", nullable = false, columnDefinition = "TEXT")
    private String analysisContent;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "total_runs")
    private Integer totalRuns;

    @Column(name = "total_distance_km")
    private Double totalDistanceKm;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (documentId == null) {
            documentId = UUID.randomUUID();
        }
    }
}
