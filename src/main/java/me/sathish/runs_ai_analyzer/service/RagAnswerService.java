package me.sathish.runs_ai_analyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.dto.RagAnswerResponse;
import me.sathish.runs_ai_analyzer.entity.RunAnalysisDocument;
import me.sathish.runs_ai_analyzer.service.ai.AiProviderChain;
import me.sathish.runs_ai_analyzer.service.ai.AiResult;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Turns a free-form question into a coaching answer: gathers the runner's recent analyses plus the stored
 * analyses and journal entries most similar to the question, then has the LLM answer from that material.
 *
 * <p>The vector search alone only returns raw stored text. This is the missing "generation" half of RAG.
 * Context is kept small on purpose: a local Ollama model has a short context window and silently truncates
 * what does not fit, so each item and the whole prompt are capped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagAnswerService {

    // Budgets are small on purpose. On the home VM (a 2013 Xeon with no AVX2) a 3B model reads about 7 prompt
    // tokens/s and writes about 3 tokens/s, so a ~2,000-token prompt plus a long answer took almost 7 minutes.
    // Roughly 700 tokens of context and a short answer keep a CPU-only answer to a few minutes.
    static final int DEFAULT_TOP_K = 2;
    static final int MAX_TOP_K = 10;
    /** Always included, newest first, because "what next" depends on what the runner did most recently. */
    static final int RECENT_ANALYSES = 1;
    static final int MAX_ITEM_CHARS = 900;
    static final int MAX_CONTEXT_CHARS = 2800;
    static final int MAX_RUN_DATA_CHARS = 350;
    static final int MAX_INSIGHTS = 3;
    static final int MAX_RECOMMENDATIONS = 4;
    static final String JOURNAL_SOURCE_TYPE = "run_journal_entry";

    static final String SYSTEM_PROMPT = """
            You are a supportive, practical running coach. The runner asked a question. You are given \
            their own recent run analyses and journal notes.

            How to answer:
            - Start with a direct answer to the question in two or three sentences.
            - Then give "**Next steps**": three to five concrete actions for the coming week \
            (distances or effort, easy versus hard days, rest, what to watch), based on the data.
            - Mention injury or overtraining risks only when the data supports them, and say what in the \
            data suggests it. If nothing stands out, say so.
            - Refer to specific runs or dates when that helps.
            - Use only the supplied data. If something you would need is missing, say what is missing \
            instead of guessing.
            - Keep the whole answer under about 200 words.
            - Plain text with short paragraphs and lines starting with "-". You may use **bold** for headings. \
            No JSON and no code fences.
            - You are not a doctor: for pain that persists or worsens, suggest seeing a clinician.
            """;

    static final String NO_DATA_ANSWER =
            "I don't have any analyzed runs or journal entries to base an answer on yet. "
                    + "Import your runs and run an analysis (or add a journal entry), then ask again.";

    static final String BUSY_ERROR =
            "The AI coach is still working on another question. Please wait a few minutes and ask again.";

    static final String UNAVAILABLE_ERROR =
            "The AI coach could not answer right now because no AI model was reachable. Please try again shortly.";

    private final RagStorageService ragStorageService;
    private final AiProviderChain aiProviderChain;

    /**
     * One answer at a time. A CPU-only local model handles one request at a time anyway, and a page that
     * times out and retries would otherwise queue a second multi-minute generation behind the first (and the
     * abandoned one keeps running), so a request that arrives meanwhile is told to wait.
     */
    private final Semaphore inFlight = new Semaphore(1);

    public RagAnswerResponse answer(String question, Integer topK) {
        if (!inFlight.tryAcquire()) {
            log.info("AI coach busy, rejecting '{}'", question);
            return RagAnswerResponse.builder().query(question).error(BUSY_ERROR).sources(List.of()).build();
        }
        try {
            return answerNow(question, topK);
        } finally {
            inFlight.release();
        }
    }

    private RagAnswerResponse answerNow(String question, Integer topK) {
        int k = topK == null || topK < 1 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
        List<ContextItem> items = gatherContext(question, k);

        if (items.isEmpty()) {
            return RagAnswerResponse.builder()
                    .query(question)
                    .answer(NO_DATA_ANSWER)
                    .sources(List.of())
                    .build();
        }

        List<String> sources = items.stream().map(ContextItem::source).toList();
        String userPrompt = buildUserPrompt(question, items);
        try {
            AiResult result = aiProviderChain.callWithDetails(SYSTEM_PROMPT, userPrompt);
            return RagAnswerResponse.builder()
                    .query(question)
                    .answer(result.text().strip())
                    .sources(sources)
                    .provider(label(result.provider()))
                    .notice(notice(result))
                    .build();
        } catch (RuntimeException e) {
            // Answered with 200 + error text on purpose: the page shows it in the answer card instead of
            // navigating to its generic error screen. The detail (provider URLs, causes) stays in the log.
            log.warn("AI coach could not answer '{}': {}", question, e.getMessage());
            return RagAnswerResponse.builder().query(question).error(UNAVAILABLE_ERROR).sources(sources).build();
        }
    }

    /** User-facing name of a tier, as the page shows it. */
    static String label(String provider) {
        return switch (provider) {
            case "openrouter" -> "OpenRouter";
            case "anthropic" -> "Anthropic";
            case "acg-ollama" -> "the remote Ollama";
            case "local-ollama" -> "local Qwen on the VM";
            default -> provider;
        };
    }

    /**
     * Explains why a faster tier did not answer, or null when the first enabled tier did. Uses only the short,
     * user-safe reasons from the chain, never raw error text.
     */
    static String notice(AiResult result) {
        if (result.unavailable().isEmpty()) {
            return null;
        }
        String skipped = result.unavailable().stream()
                .map(s -> label(s.provider()) + " (" + s.reason() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        String answeredBy = label(result.provider());
        boolean slow = "local-ollama".equals(result.provider());
        return skipped + " was unavailable, so this was answered by " + answeredBy
                + (slow ? ", which is slower." : ".");
    }

    private List<ContextItem> gatherContext(String question, int topK) {
        List<ContextItem> items = new ArrayList<>();
        Set<String> seenAnalysisIds = new HashSet<>();

        for (RunAnalysisDocument recent : ragStorageService.getRecentAnalyses(RECENT_ANALYSES)) {
            if (recent.getDocumentId() != null && seenAnalysisIds.add(recent.getDocumentId().toString())) {
                items.add(analysisItem(recent));
            }
        }

        for (Document hit : ragStorageService.searchSimilarAnalyses(question, topK)) {
            Map<String, Object> metadata = hit.getMetadata() == null ? Map.of() : hit.getMetadata();
            if (JOURNAL_SOURCE_TYPE.equals(metadata.get("sourceType"))) {
                items.add(journalItem(hit, metadata));
                continue;
            }
            UUID documentId = parseUuid(metadata.get("documentId"));
            if (documentId == null || !seenAnalysisIds.add(documentId.toString())) {
                continue;
            }
            items.add(ragStorageService.findByDocumentId(documentId)
                    .map(this::analysisItem)
                    .orElseGet(() -> new ContextItem("Stored analysis", truncate(hit.getText(), MAX_ITEM_CHARS))));
        }
        return items;
    }

    private ContextItem analysisItem(RunAnalysisDocument doc) {
        Map<String, Object> meta = doc.getMetadata() == null ? Map.of() : doc.getMetadata();
        String period = meta.get("dateRange") != null
                ? String.valueOf(meta.get("dateRange"))
                : doc.getCreatedAt() != null ? doc.getCreatedAt().toLocalDate().toString() : "unknown date";
        String distance = doc.getTotalDistanceMiles() == null ? "?" : String.format("%.1f", doc.getTotalDistanceMiles());
        String source = "Analysis of " + period + " (" + doc.getTotalRuns() + " runs, " + distance + " mi)";

        StringBuilder text = new StringBuilder(source).append('\n');
        appendLine(text, "Summary", doc.getSummary());
        appendLine(text, "Average pace (min/mi)", meta.get("averagePace"));
        appendLine(text, "Average heart rate", meta.get("averageHeartRate"));
        appendList(text, "Insights", insightLines(meta.get("insights")).stream().limit(MAX_INSIGHTS).toList());
        appendList(text, "Recommendations",
                stringList(meta.get("recommendations")).stream().limit(MAX_RECOMMENDATIONS).toList());
        List<String> riskFlags = stringList(meta.get("riskFlags"));
        appendList(text, "Risk flags", riskFlags.isEmpty() ? List.of("none flagged") : riskFlags);
        appendLine(text, "Runs analyzed", truncate(doc.getQueryText(), MAX_RUN_DATA_CHARS));
        return new ContextItem(source, truncate(text.toString().strip(), MAX_ITEM_CHARS));
    }

    private ContextItem journalItem(Document hit, Map<String, Object> metadata) {
        Object date = metadata.get("entryDate");
        String source = "Journal entry" + (date != null ? " " + date : "");
        return new ContextItem(source, truncate(source + ": " + hit.getText(), MAX_ITEM_CHARS));
    }

    private String buildUserPrompt(String question, List<ContextItem> items) {
        StringBuilder prompt = new StringBuilder("Runner's question:\n")
                .append(question.strip())
                .append("\n\nData from the runner's own history (most recent analyses first):\n");
        for (ContextItem item : items) {
            String block = "\n--- " + item.source() + " ---\n" + item.text() + "\n";
            if (prompt.length() + block.length() > MAX_CONTEXT_CHARS) {
                int room = MAX_CONTEXT_CHARS - prompt.length();
                if (room > 200) {
                    prompt.append(block, 0, room).append("…\n");
                }
                break;
            }
            prompt.append(block);
        }
        return prompt.toString();
    }

    private static void appendLine(StringBuilder sb, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            sb.append(label).append(": ").append(String.valueOf(value).strip()).append('\n');
        }
    }

    private static void appendList(StringBuilder sb, String label, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append(label).append(":\n");
        lines.forEach(line -> sb.append("- ").append(line).append('\n'));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(e -> e != null && !String.valueOf(e).isBlank()).map(e -> String.valueOf(e).strip()).toList();
    }

    private static List<String> insightLines(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Map<?, ?> insight) {
                String category = String.valueOf(insight.get("category"));
                String observation = String.valueOf(insight.get("observation"));
                String recommendation = String.valueOf(insight.get("recommendation"));
                lines.add(category + ": " + observation + " (" + recommendation + ")");
            }
        }
        return lines;
    }

    private static UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private record ContextItem(String source, String text) {
    }
}
