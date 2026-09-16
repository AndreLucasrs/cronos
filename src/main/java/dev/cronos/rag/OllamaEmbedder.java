package dev.cronos.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.api.rag.Embedder;
import dev.cronos.config.Env;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * No concrete {@link Embedder} ships with aegis4j v0.2 yet (documented gap:
 * {@code OllamaEmbedder} is planned for aegis4j v0.3) — this is Cronos's own,
 * calling Ollama's {@code /api/embed} directly. {@link #dimensions()} is
 * fixed to the configured model's real output size; it must match the
 * {@code vector(N)} column in {@code task_embeddings} exactly.
 */
public final class OllamaEmbedder implements Embedder {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String model;
    private final int dimensions;

    public OllamaEmbedder(String baseUrl, String model, int dimensions) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.dimensions = dimensions;
    }

    public static OllamaEmbedder fromEnv() {
        return new OllamaEmbedder(
                Env.get("AEGIS4J_OLLAMA_BASE_URL", "http://localhost:11434"),
                Env.get("CRONOS_EMBED_MODEL", "nomic-embed-text"),
                Env.getInt("CRONOS_EMBED_DIMENSIONS", 768));
    }

    @Override
    public float[] embed(String text) {
        try {
            String body = mapper.writeValueAsString(new EmbedRequest(model, text));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embed"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new EmbeddingException("Ollama /api/embed returned HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode firstEmbedding = root.path("embeddings").path(0);
            float[] vector = new float[firstEmbedding.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) firstEmbedding.get(i).asDouble();
            }
            return vector;
        } catch (IOException e) {
            throw new EmbeddingException("Failed to call Ollama /api/embed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Interrupted while calling Ollama /api/embed", e);
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private record EmbedRequest(String model, String input) {
    }
}
