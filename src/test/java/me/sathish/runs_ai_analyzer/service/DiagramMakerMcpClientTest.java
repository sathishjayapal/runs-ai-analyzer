package me.sathish.runs_ai_analyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import me.sathish.runs_ai_analyzer.dto.DiagramArtifact;
import me.sathish.runs_ai_analyzer.dto.DiagramGenerationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiagramMakerMcpClientTest {

    @Mock
    private McpSyncClient mcpClient;

    private DiagramMakerMcpClient client;

    @BeforeEach
    void setUp() {
        client = new DiagramMakerMcpClient(List.of(mcpClient), new ObjectMapper());
    }

    @Test
    void shouldInvokeDocumentDiagramToolAndReadStructuredArtifact() {
        UUID documentId = UUID.randomUUID();
        Map<String, Object> structuredContent = Map.of(
                "uid", "artifact-1",
                "fileName", "analysis.svg",
                "filePath", "/app/diagrams/analysis.svg",
                "contentType", "image/svg+xml",
                "sizeBytes", 321
        );
        when(mcpClient.callTool(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new McpSchema.CallToolResult(List.of(), false, structuredContent, null));

        DiagramArtifact artifact = client.generateAnalysisDiagram(
                documentId,
                DiagramGenerationRequest.builder().diagramType("all_metrics").format("svg").build());

        assertThat(artifact.uid()).isEqualTo("artifact-1");
        assertThat(artifact.contentType()).isEqualTo("image/svg+xml");
        ArgumentCaptor<McpSchema.CallToolRequest> requestCaptor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(mcpClient).callTool(requestCaptor.capture());
        assertThat(requestCaptor.getValue().name()).isEqualTo("generate_analysis_diagram");
        assertThat(requestCaptor.getValue().arguments()).containsEntry("documentId", documentId.toString());
        assertThat(requestCaptor.getValue().arguments()).containsEntry("diagramType", "ALL_METRICS");
        assertThat(requestCaptor.getValue().arguments()).containsEntry("format", "SVG");
    }

    @Test
    void shouldReadTextArtifactAndApplyDefaults() {
        UUID documentId = UUID.randomUUID();
        String artifactJson = "{\"uid\":\"artifact-2\",\"fileName\":\"analysis.png\",\"filePath\":\"/app/diagrams/analysis.png\",\"contentType\":\"image/png\",\"sizeBytes\":123}";
        McpSchema.TextContent content = new McpSchema.TextContent(null, artifactJson, null);
        when(mcpClient.callTool(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new McpSchema.CallToolResult(List.of(content), false, null, null));

        DiagramArtifact artifact = client.generateAnalysisDiagram(
                documentId, DiagramGenerationRequest.builder().diagramType(" ").format(null).build());

        assertThat(artifact.fileName()).isEqualTo("analysis.png");
        ArgumentCaptor<McpSchema.CallToolRequest> requestCaptor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(mcpClient).callTool(requestCaptor.capture());
        assertThat(requestCaptor.getValue().arguments()).containsEntry("diagramType", "SUMMARY");
        assertThat(requestCaptor.getValue().arguments()).containsEntry("format", "PNG");
    }

    @Test
    void shouldRaiseToolError() {
        McpSchema.TextContent content = new McpSchema.TextContent(null, "Analysis not found", null);
        when(mcpClient.callTool(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new McpSchema.CallToolResult(List.of(content), true, null, null));

        assertThatThrownBy(() -> client.generateAnalysisDiagram(
                UUID.randomUUID(), DiagramGenerationRequest.builder().build()))
                .isInstanceOf(DiagramGenerationException.class)
                .hasMessage("Analysis not found");
    }

    @Test
    void shouldFailWhenNoMcpClientIsConfigured() {
        DiagramMakerMcpClient unconfiguredClient = new DiagramMakerMcpClient(List.of(), new ObjectMapper());

        assertThatThrownBy(() -> unconfiguredClient.generateAnalysisDiagram(
                UUID.randomUUID(), DiagramGenerationRequest.builder().build()))
                .isInstanceOf(DiagramGenerationException.class)
                .hasMessage("Diagram Maker MCP client is not configured");
    }
}
