package me.sathish.runs_ai_analyzer.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.sathish.runs_ai_analyzer.entity.RunJournalEntry.Feel;

import java.time.LocalDate;

/**
 * Payload for creating or updating a {@code RunJournalEntry}.
 *
 * <p>Only {@code entryDate} is required -- the philosophy is low friction, so an
 * entry of just a date and "ROUGH, calves tight" is still valid and useful.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunJournalEntryRequest {

    /**
     * Optional Garmin activity id; omit for a standalone entry.
     */
    private String activityId;

    @NotNull(message = "Entry date is required")
    private LocalDate entryDate;

    @Min(value = 1, message = "Perceived effort must be between 1 and 10")
    @Max(value = 10, message = "Perceived effort must be between 1 and 10")
    private Short perceivedEffort;

    private Feel feel;

    @Size(max = 500, message = "Body notes must be 500 characters or fewer")
    private String bodyNotes;

    @Size(max = 500, message = "Context notes must be 500 characters or fewer")
    private String contextNotes;

    private String narrative;
}
