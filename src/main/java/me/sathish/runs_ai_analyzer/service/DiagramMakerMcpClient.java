package me.sathish.runs_ai_analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.sathish.runs_ai_analyzer.dto.DiagramArtifact;
import me.sathish.runs_ai_analyzer.dto.DiagramGenerationRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiagramMakerMcpClient {

    private static final String TOOL_NAME = "generate_analysis_diagram";

    private final List<McpSyncClient> mcpClients;
    private final ObjectMapper objectMapper;

    public DiagramArtifact generateAnalysisDiagram(
            UUID documentId,
            DiagramGenerationRequest request) {
        McpSyncClient client = getClient();
        Map<String, Object> arguments = Map.of(
                "documentId", documentId.toString(),
                "diagramType", normalize(request.getDiagramType(), "SUMMARY"),
                "format", normalize(request.getFormat(), "PNG")
        );

        log.info("Invoking MCP tool {} for analysis document {}", TOOL_NAME, documentId);
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(TOOL_NAME, arguments, null));

        if (Boolean.TRUE.equals(result.isError())) {
            throw new DiagramGenerationException(readError(result));
        }
        if (result.structuredContent() != null) {
            return objectMapper.convertValue(result.structuredContent(), DiagramArtifact.class);
        }

        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .map(this::deserializeArtifact)
                .orElseThrow(() -> new DiagramGenerationException("Diagram MCP tool returned no artifact"));
    }

    private McpSyncClient getClient() {
        return mcpClients.stream()
                .findFirst()
                .orElseThrow(() -> new DiagramGenerationException("Diagram Maker MCP client is not configured"));
    }

    private DiagramArtifact deserializeArtifact(String json) {
        try {
            return objectMapper.readValue(json, DiagramArtifact.class);
        } catch (Exception e) {
            throw new DiagramGenerationException("Invalid artifact response from Diagram Maker", e);
        }
    }

    private String readError(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("Diagram Maker MCP tool failed");
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase();
    }
}
