package me.sathish.runs_ai_analyzer.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A subjective journal entry authored by the runner.
 *
 * <p>This is deliberately separate from run data. Run records are owned by runs-app
 * and arrive here only as {@code GarminRunDataDTO} over REST. A journal entry is
 * authored content -- it must never be at risk of being overwritten by a sync --
 * so it references a run loosely by {@code activityId} rather than a foreign key.
 *
 * <p>{@code activityId} is nullable: an entry may stand alone (a rest day, soreness
 * felt two days after a run) with no run attached.
 *
 * <p>The {@code embedded} flag drives the scheduled embedding sweep -- new and edited
 * entries are marked {@code false} and picked up by {@code JournalEmbeddingService}.
 */
@Entity
@Table(name = "run_journal_entry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunJournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "primary_sequence")
    @SequenceGenerator(name = "primary_sequence", sequenceName = "primary_sequence", allocationSize = 1)
    private Long id;

    /**
     * Garmin activity id this entry reflects on; null for standalone entries.
     */
    @Column(name = "activity_id")
    private String activityId;

    /**
     * The day being reflected on.
     */
    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    /**
     * Rate of perceived exertion, 1-10. Optional -- keep the form low-friction.
     */
    @Column(name = "perceived_effort")
    private Short perceivedEffort;

    @Column(name = "feel", length = 20)
    @Enumerated(EnumType.STRING)
    private Feel feel;

    /**
     * Quick structured notes on the body: "calves tight, left knee twinge".
     */
    @Column(name = "body_notes", length = 500)
    private String bodyNotes;

    /**
     * Quick structured notes on context: "slept 5 hrs, stressful week, hot day".
     */
    @Column(name = "context_notes", length = 500)
    private String contextNotes;

    /**
     * Free-form reflection -- the richest RAG fuel, but always optional.
     */
    @Column(name = "narrative", columnDefinition = "text")
    private String narrative;

    /**
     * True once this entry's text has been written to the vector store.
     */
    @Column(name = "embedded", nullable = false)
    private boolean embedded;

    @Column(name = "embedded_at")
    private LocalDateTime embeddedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum Feel {
        GREAT, GOOD, OK, ROUGH, BAD
    }
}
