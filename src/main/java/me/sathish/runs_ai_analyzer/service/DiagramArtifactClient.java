package me.sathish.runs_ai_analyzer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class DiagramArtifactClient {

    private final RestClient restClient;

    public DiagramArtifactClient(@Value("${diagram-maker.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public ResponseEntity<byte[]> download(String fileName) {
        ResponseEntity<byte[]> upstream = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/diagrams/{fileName}").build(fileName))
                .accept(MediaType.IMAGE_PNG, MediaType.IMAGE_JPEG, MediaType.valueOf("image/svg+xml"))
                .retrieve()
                .toEntity(byte[].class);

        HttpHeaders headers = new HttpHeaders();
        if (upstream.getHeaders().getContentType() != null) {
            headers.setContentType(upstream.getHeaders().getContentType());
        }
        if (upstream.getHeaders().getContentDisposition() != null) {
            headers.setContentDisposition(upstream.getHeaders().getContentDisposition());
        }
        if (upstream.getBody() != null) {
            headers.setContentLength(upstream.getBody().length);
        }
        return new ResponseEntity<>(upstream.getBody(), headers, upstream.getStatusCode());
    }
}
