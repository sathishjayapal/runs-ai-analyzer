package me.sathish.runs_ai_analyzer.repository;

import me.sathish.runs_ai_analyzer.entity.RunJournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RunJournalEntryRepository extends JpaRepository<RunJournalEntry, Long> {

    /**
     * Pending work for the embedding sweep -- entries not yet in the vector store.
     */
    List<RunJournalEntry> findByEmbeddedFalseOrderByEntryDateAsc();

    /**
     * All entries attached to a given run, most recent first.
     */
    List<RunJournalEntry> findByActivityIdOrderByEntryDateDesc(String activityId);

    /**
     * Recent entries for a list view.
     */
    List<RunJournalEntry> findTop20ByOrderByEntryDateDesc();
}
