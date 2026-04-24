package me.sathish.runs_ai_analyzer.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_processing_log", 
       indexes = {
           @Index(name = "idx_activity_id", columnList = "activityId"),
           @Index(name = "idx_processing_status", columnList = "processingStatus"),
           @Index(name = "idx_created_at", columnList = "createdAt")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisProcessingLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String activityId;

    @Column(nullable = false)
    private Long databaseId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ProcessingStatus processingStatus;

    @Column
    private String documentId;

    @Column
    private Integer retryCount;

    @Column(columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime processedAt;

    @Column
    private LocalDateTime lastRetryAt;

    public enum ProcessingStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        SKIPPED
    }
}
