package dev.cronos.assistant;

import dev.aegis4j.api.provider.CompletionChunk;
import dev.aegis4j.api.provider.CompletionRequest;
import dev.aegis4j.api.provider.CompletionResponse;
import dev.aegis4j.api.provider.ModelInfo;
import dev.aegis4j.api.provider.Provider;

import java.util.List;
import java.util.stream.Stream;

/**
 * Strips {@code tools} before delegating. {@code Aegis4jEngine.chat()}
 * attaches the engine-wide tool list (the {@code criar_lembrete} reminder
 * tool, see {@code CronosAssistant}) to every request regardless of route,
 * and {@code OpenAiCompatibleProvider} forwards it on the wire whenever it's
 * non-empty. {@code aegis4j-server} v0.3.1's own {@code
 * ChatCompletionRequestDto} has no {@code tools} field and its Jackson
 * mapper rejects unknown properties, so a sidecar-routed message would 500
 * without this. Ollama's own OpenAI-compatible endpoint (the
 * "ollama-openai" provider) tolerates the same payload, so only the
 * sidecar-facing provider needs wrapping.
 */
final class ToolStrippingProvider implements Provider {

    private final Provider delegate;

    ToolStrippingProvider(Provider delegate) {
        this.delegate = delegate;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        return delegate.complete(withoutTools(request));
    }

    @Override
    public Stream<CompletionChunk> stream(CompletionRequest request) {
        return delegate.stream(withoutTools(request));
    }

    @Override
    public List<ModelInfo> listModels() {
        return delegate.listModels();
    }

    private CompletionRequest withoutTools(CompletionRequest request) {
        return CompletionRequest.builder()
                .model(request.model())
                .messages(request.messages())
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .tools(List.of())
                .responseFormat(request.responseFormat())
                .providerOptions(request.providerOptions())
                .build();
    }
}
