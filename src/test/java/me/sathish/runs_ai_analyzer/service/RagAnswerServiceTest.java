package me.sathish.runs_ai_analyzer.service;

import me.sathish.runs_ai_analyzer.dto.RagAnswerResponse;
import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import me.sathish.runs_ai_analyzer.exception.AiAnalysisException;
import me.sathish.runs_ai_analyzer.service.ai.AiProviderChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagAnswerServiceTest {

    private static final String QUESTION = "What should I focus on next week? Any injury risks?";

    @Mock
    private RagStorageService ragStorageService;

    @Mock
    private AiProviderChain aiProviderChain;

    private RagAnswerService service;

    @BeforeEach
    void setUp() {
        service = new RagAnswerService(ragStorageService, aiProviderChain);
    }

    @Test
    void sendsRecentAnalysisAndSimilarHitsToTheLlmAndReturnsItsAnswer() {
        RunAnalysisDocument recent = analysis(UUID.randomUUID(), "Easy week with one long run", "2026-10-07 to 2026-10-14");
        RunAnalysisDocument similar = analysis(UUID.randomUUID(), "Earlier hill block", "2026-09-20 to 2026-09-27");
        Document analysisHit = new Document("v1", "raw vector text", Map.of("documentId", similar.getDocumentId().toString()));
        Document journalHit = new Document("v2", "Run journal entry for 2026-10-12. Felt: tired. Body: sore left calf.",
                Map.of("sourceType", "run_journal_entry", "entryDate", "2026-10-12"));
        when(ragStorageService.getRecentAnalyses(RagAnswerService.RECENT_ANALYSES)).thenReturn(List.of(recent));
        when(ragStorageService.searchSimilarAnalyses(QUESTION, RagAnswerService.DEFAULT_TOP_K))
                .thenReturn(List.of(analysisHit, journalHit));
        when(ragStorageService.findByDocumentId(similar.getDocumentId())).thenReturn(Optional.of(similar));
        when(aiProviderChain.call(anyString(), anyString())).thenReturn("  Keep next week easy.\n\n**Next steps**\n- Rest.  ");

        RagAnswerResponse response = service.answer(QUESTION, null);

        assertThat(response.getAnswer()).isEqualTo("Keep next week easy.\n\n**Next steps**\n- Rest.");
        assertThat(response.getError()).isNull();
        assertThat(response.getQuery()).isEqualTo(QUESTION);
        assertThat(response.getSources()).containsExactly(
                "Analysis of 2026-10-07 to 2026-10-14 (5 runs, 16.5 mi)",
                "Analysis of 2026-09-20 to 2026-09-27 (5 runs, 16.5 mi)",
                "Journal entry 2026-10-12");

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(aiProviderChain).call(system.capture(), user.capture());
        assertThat(system.getValue()).isEqualTo(RagAnswerService.SYSTEM_PROMPT);
        assertThat(user.getValue())
                .contains(QUESTION)
                .contains("Easy week with one long run")           // summary of the recent analysis
                .contains("Keep easy days easy")                    // a stored recommendation
                .contains("Risk flags:")
                .contains("Calf tightness after long runs")         // a stored risk flag
                .contains("Pacing: Negative split (hold it)")       // a stored insight
                .contains("sore left calf");                        // the journal entry
    }

    @Test
    void aDocumentThatIsBothRecentAndSimilarIsIncludedOnlyOnce() {
        RunAnalysisDocument doc = analysis(UUID.randomUUID(), "Only analysis", "2026-10-07 to 2026-10-14");
        when(ragStorageService.getRecentAnalyses(RagAnswerService.RECENT_ANALYSES)).thenReturn(List.of(doc));
        when(ragStorageService.searchSimilarAnalyses(anyString(), anyInt()))
                .thenReturn(List.of(new Document("v1", "text", Map.of("documentId", doc.getDocumentId().toString()))));
        when(aiProviderChain.call(anyString(), anyString())).thenReturn("ok");

        RagAnswerResponse response = service.answer(QUESTION, null);

        assertThat(response.getSources()).hasSize(1);
    }

    @Test
    void withNothingStoredItDoesNotCallTheLlmAndSaysSo() {
        when(ragStorageService.getRecentAnalyses(anyInt())).thenReturn(List.of());
        when(ragStorageService.searchSimilarAnalyses(anyString(), anyInt())).thenReturn(List.of());

        RagAnswerResponse response = service.answer(QUESTION, null);

        assertThat(response.getAnswer()).isEqualTo(RagAnswerService.NO_DATA_ANSWER);
        assertThat(response.getError()).isNull();
        verify(aiProviderChain, never()).call(anyString(), anyString());
    }

    @Test
    void whenNoAiProviderIsReachableItReturnsAnErrorMessageInsteadOfThrowing() {
        RunAnalysisDocument doc = analysis(UUID.randomUUID(), "Summary", "2026-10-07 to 2026-10-14");
        when(ragStorageService.getRecentAnalyses(anyInt())).thenReturn(List.of(doc));
        when(ragStorageService.searchSimilarAnalyses(anyString(), anyInt())).thenReturn(List.of());
        when(aiProviderChain.call(anyString(), anyString()))
                .thenThrow(new AiAnalysisException("All AI providers failed: ollama=host.docker.internal: Name does not resolve"));

        RagAnswerResponse response = service.answer(QUESTION, null);

        assertThat(response.getAnswer()).isNull();
        assertThat(response.getError()).isEqualTo(RagAnswerService.UNAVAILABLE_ERROR);
        assertThat(response.getError()).doesNotContain("host.docker.internal");
        assertThat(response.getSources()).hasSize(1);
    }

    @Test
    void thePromptIsCappedSoASmallLocalModelIsNotOverrun() {
        RunAnalysisDocument huge = analysis(UUID.randomUUID(), "Summary", "2026-10-07 to 2026-10-14");
        huge.setQueryText("run ".repeat(50_000));
        RunAnalysisDocument second = analysis(UUID.randomUUID(), "Second", "2026-09-01 to 2026-09-08");
        second.setQueryText("run ".repeat(50_000));
        when(ragStorageService.getRecentAnalyses(anyInt())).thenReturn(List.of(huge, second));
        when(ragStorageService.searchSimilarAnalyses(anyString(), anyInt())).thenReturn(List.of());
        when(aiProviderChain.call(anyString(), anyString())).thenReturn("ok");

        service.answer(QUESTION, null);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(aiProviderChain).call(anyString(), user.capture());
        assertThat(user.getValue().length()).isLessThanOrEqualTo(RagAnswerService.MAX_CONTEXT_CHARS + 5);
    }

    @Test
    void topKIsClampedToASaneRange() {
        when(ragStorageService.getRecentAnalyses(anyInt())).thenReturn(List.of());
        when(ragStorageService.searchSimilarAnalyses(anyString(), anyInt())).thenReturn(List.of());

        service.answer(QUESTION, 500);
        service.answer(QUESTION, 0);

        verify(ragStorageService).searchSimilarAnalyses(QUESTION, RagAnswerService.MAX_TOP_K);
        verify(ragStorageService).searchSimilarAnalyses(QUESTION, RagAnswerService.DEFAULT_TOP_K);
    }

    @Test
    void aSecondQuestionWhileOneIsRunningIsToldToWaitInsteadOfQueueingAnotherGeneration() throws Exception {
        RunAnalysisDocument doc = analysis(UUID.randomUUID(), "Summary", "2026-10-07 to 2026-10-14");
        when(ragStorageService.getRecentAnalyses(anyInt())).thenReturn(List.of(doc));
        when(ragStorageService.searchSimilarAnalyses(anyString(), anyInt())).thenReturn(List.of());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(aiProviderChain.call(anyString(), anyString())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "first answer";
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RagAnswerResponse> first = executor.submit(() -> service.answer(QUESTION, null));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            RagAnswerResponse second = service.answer("another question", null);

            assertThat(second.getError()).isEqualTo(RagAnswerService.BUSY_ERROR);
            assertThat(second.getAnswer()).isNull();
            verify(aiProviderChain, times(1)).call(anyString(), anyString());

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getAnswer()).isEqualTo("first answer");
            // the slot is free again once the first answer is done
            assertThat(service.answer(QUESTION, null).getAnswer()).isEqualTo("first answer");
        } finally {
            executor.shutdownNow();
        }
    }

    private static RunAnalysisDocument analysis(UUID id, String summary, String dateRange) {
        return RunAnalysisDocument.builder()
                .documentId(id)
                .activityIds("A1,A2")
                .queryText("Run #1: 2026-10-07 4.0 mi, HR 150")
                .analysisContent("{}")
                .summary(summary)
                .totalRuns(5)
                .totalDistanceMiles(16.53)
                .createdAt(LocalDateTime.of(2026, 10, 18, 2, 36))
                .metadata(Map.of(
                        "dateRange", dateRange,
                        "averagePace", 12.4,
                        "averageHeartRate", 165,
                        "recommendations", List.of("Keep easy days easy", "Add one strides session"),
                        "riskFlags", List.of("Calf tightness after long runs"),
                        "insights", List.of(Map.of(
                                "category", "Pacing", "observation", "Negative split", "recommendation", "hold it"))))
                .build();
    }
}
