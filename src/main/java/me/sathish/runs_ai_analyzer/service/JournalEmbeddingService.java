package me.sathish.runs_ai_analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.entity.RunJournalEntry;
import me.sathish.runs_ai_analyzer.repository.RunJournalEntryRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Embeds subjective journal entries into the pgvector store so they can be
 * retrieved by RAG search alongside {@code RunAnalysisDocument} vectors.
 *
 * <p>Runs as a scheduled sweep rather than embedding on save: this keeps the
 * controller fast, and a transient Ollama or pgvector failure simply leaves the
 * entry {@code embedded = false} to be retried next sweep -- the user's save
 * never fails because of an embedding hiccup. This mirrors the scheduled-job
 * pattern already used by RunAnalysisBatchService and ReconciliationService.
 *
 * <p>The vector store is the same {@code VectorStore} bean used by
 * RagStorageServiceImpl, so journal vectors and analysis vectors share one space.
 * Each vector carries {@code journalEntryId} in its metadata so an edited entry's
 * stale vector can be deleted before the new one is inserted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JournalEmbeddingService {

    private static final String SOURCE_TYPE = "run_journal_entry";

    private final RunJournalEntryRepository journalRepository;
    private final VectorStore vectorStore;

    @Scheduled(fixedDelayString = "${journal.embedding.interval-ms:60000}")
    @Transactional
    public void embedPendingEntries() {
        List<RunJournalEntry> pending = journalRepository.findByEmbeddedFalseOrderByEntryDateAsc();
        if (pending.isEmpty()) {
            return;
        }
        log.info("Journal embedding sweep: {} entries pending", pending.size());

        for (RunJournalEntry entry : pending) {
            try {
                embedEntry(entry);
                entry.setEmbedded(true);
                entry.setEmbeddedAt(LocalDateTime.now());
                journalRepository.save(entry);
                log.debug("Embedded journal entry id={}", entry.getId());
            } catch (Exception e) {
                // Leave embedded=false; the next sweep retries this entry.
                log.error("Failed to embed journal entry id={}: {}", entry.getId(), e.getMessage(), e);
            }
        }
    }

    private void embedEntry(RunJournalEntry entry) {
        // Edited entries already have a vector -- remove it before re-inserting so
        // the store never holds two conflicting vectors for one entry.
        deleteExistingVector(entry);

        String vectorId = vectorIdFor(entry);
        Document document = new Document(vectorId, composeText(entry), buildMetadata(entry));
        vectorStore.add(List.of(document));
    }

    private void deleteExistingVector(RunJournalEntry entry) {
        try {
            vectorStore.delete(List.of(vectorIdFor(entry)));
        } catch (Exception e) {
            // First-time embedding has nothing to delete; not an error worth failing on.
            log.debug("No existing vector to delete for journal entry id={}", entry.getId());
        }
    }

    private String vectorIdFor(RunJournalEntry entry) {
        return UUID.nameUUIDFromBytes((SOURCE_TYPE + ":" + entry.getId()).getBytes()).toString();
    }

    /**
     * Stitches the structured fields and the free-form narrative into one
     * document. Embedding the whole blob -- not just the narrative -- means
     * effort, feel, and body state are all searchable.
     */
    private String composeText(RunJournalEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Run journal entry for ").append(entry.getEntryDate()).append(".");
        if (entry.getActivityId() != null) {
            sb.append(" Activity: ").append(entry.getActivityId()).append(".");
        }
        if (entry.getPerceivedEffort() != null) {
            sb.append(" Perceived effort: ").append(entry.getPerceivedEffort()).append("/10.");
        }
        if (entry.getFeel() != null) {
            sb.append(" Felt: ").append(entry.getFeel()).append(".");
        }
        if (entry.getBodyNotes() != null && !entry.getBodyNotes().isBlank()) {
            sb.append(" Body: ").append(entry.getBodyNotes().strip()).append(".");
        }
        if (entry.getContextNotes() != null && !entry.getContextNotes().isBlank()) {
            sb.append(" Context: ").append(entry.getContextNotes().strip()).append(".");
        }
        if (entry.getNarrative() != null && !entry.getNarrative().isBlank()) {
            sb.append(" Notes: ").append(entry.getNarrative().strip());
        }
        return sb.toString();
    }

    /**
     * Metadata for filtered retrieval -- e.g. combining vector similarity with a
     * date range or a feel value. {@code journalEntryId} lets edits find and
     * delete the prior vector.
     */
    private Map<String, Object> buildMetadata(RunJournalEntry entry) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceType", SOURCE_TYPE);
        metadata.put("journalEntryId", entry.getId());
        metadata.put("entryDate", entry.getEntryDate().toString());
        if (entry.getActivityId() != null) {
            metadata.put("activityId", entry.getActivityId());
        }
        if (entry.getPerceivedEffort() != null) {
            metadata.put("perceivedEffort", entry.getPerceivedEffort());
        }
        if (entry.getFeel() != null) {
            metadata.put("feel", entry.getFeel().name());
        }
        return metadata;
    }
}
