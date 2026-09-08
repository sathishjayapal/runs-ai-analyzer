package me.sathish.runs_ai_analyzer.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiConfig {

    @Bean
    @Primary
    @Qualifier("anthropicChatClient")
    public ChatClient anthropicChatClient(AnthropicChatModel anthropicChatModel) {
        return ChatClient.builder(anthropicChatModel).build();
    }

    @Bean
    @Qualifier("ollamaChatClient")
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel).build();
    }

    /**
     * Explicit OllamaApi with a hard-coded socket read timeout. The autoconfigured
     * RestClient.Builder was not honoring spring.http.clients.read-timeout in practice
     * (calls were failing after ~9s instead of OLLAMA_CHAT_TIMEOUT), so this builds the
     * HttpComponents request factory directly to guarantee the timeout applies.
     */
    @Bean
    public OllamaApi ollamaApi(
            @Value("${OLLAMA_BASE_URL}") String baseUrl,
            @DurationUnit(ChronoUnit.SECONDS) @Value("${OLLAMA_CHAT_TIMEOUT:600}") Duration readTimeout) {
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.httpComponents()
                .build(HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(10), readTimeout));
        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
    }
}
