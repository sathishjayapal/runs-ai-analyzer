package me.sathish.runs_ai_analyzer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.dto.RunJournalEntryRequest;
import me.sathish.runs_ai_analyzer.entity.RunJournalEntry;
import me.sathish.runs_ai_analyzer.repository.RunJournalEntryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD for subjective runner journal entries.
 *
 * <p>Creating or editing an entry leaves it with {@code embedded = false}; the
 * scheduled {@code JournalEmbeddingService} sweep picks it up and writes its text
 * to the vector store. Editing an already-embedded entry resets the flag so the
 * sweep re-embeds it (replacing the stale vector).
 */
@RestController
@RequestMapping("/api/v1/journal")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Run Journal", description = "Subjective runner journal entries that feed RAG analysis")
public class RunJournalController {

    private final RunJournalEntryRepository journalRepository;

    @PostMapping
    @Operation(summary = "Create a journal entry",
            description = "Records a subjective entry. Embedded into the vector store by the next sweep.")
    @ApiResponse(responseCode = "200", description = "Entry created")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    public ResponseEntity<RunJournalEntry> create(@Valid @RequestBody RunJournalEntryRequest request) {
        RunJournalEntry entry = RunJournalEntry.builder()
                .activityId(request.getActivityId())
                .entryDate(request.getEntryDate())
                .perceivedEffort(request.getPerceivedEffort())
                .feel(request.getFeel())
                .bodyNotes(request.getBodyNotes())
                .contextNotes(request.getContextNotes())
                .narrative(request.getNarrative())
                .embedded(false)
                .build();
        RunJournalEntry saved = journalRepository.save(entry);
        log.info("Created journal entry id={}, activityId={}, date={}",
                saved.getId(), saved.getActivityId(), saved.getEntryDate());
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a journal entry",
            description = "Updates an entry and resets it for re-embedding by the next sweep.")
    @ApiResponse(responseCode = "200", description = "Entry updated")
    @ApiResponse(responseCode = "404", description = "Entry not found")
    public ResponseEntity<RunJournalEntry> update(@PathVariable Long id,
                                                  @Valid @RequestBody RunJournalEntryRequest request) {
        return journalRepository.findById(id)
                .map(entry -> {
                    entry.setActivityId(request.getActivityId());
                    entry.setEntryDate(request.getEntryDate());
                    entry.setPerceivedEffort(request.getPerceivedEffort());
                    entry.setFeel(request.getFeel());
                    entry.setBodyNotes(request.getBodyNotes());
                    entry.setContextNotes(request.getContextNotes());
                    entry.setNarrative(request.getNarrative());
                    // Reset so the sweep re-embeds; the stale vector is replaced.
                    entry.setEmbedded(false);
                    RunJournalEntry saved = journalRepository.save(entry);
                    log.info("Updated journal entry id={}, reset for re-embedding", id);
                    return ResponseEntity.ok(saved);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a journal entry by id")
    @ApiResponse(responseCode = "200", description = "Entry found")
    @ApiResponse(responseCode = "404", description = "Entry not found")
    public ResponseEntity<RunJournalEntry> getById(@PathVariable Long id) {
        return journalRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/recent")
    @Operation(summary = "Get recent journal entries", description = "Most recent entries, newest first.")
    @ApiResponse(responseCode = "200", description = "Entries retrieved")
    public ResponseEntity<List<RunJournalEntry>> getRecent() {
        return ResponseEntity.ok(journalRepository.findTop20ByOrderByEntryDateDesc());
    }

    @GetMapping("/activity/{activityId}")
    @Operation(summary = "Get journal entries for a run",
            description = "All entries attached to a given Garmin activity id.")
    @ApiResponse(responseCode = "200", description = "Entries retrieved")
    public ResponseEntity<List<RunJournalEntry>> getByActivity(@PathVariable String activityId) {
        return ResponseEntity.ok(journalRepository.findByActivityIdOrderByEntryDateDesc(activityId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a journal entry")
    @ApiResponse(responseCode = "204", description = "Entry deleted")
    @ApiResponse(responseCode = "404", description = "Entry not found")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!journalRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        journalRepository.deleteById(id);
        log.info("Deleted journal entry id={}", id);
        return ResponseEntity.noContent().build();
    }
}
