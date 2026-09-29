package dev.cronos.rag;

import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.api.rag.Retriever;

import java.util.List;

/**
 * Aegis4jEngine.Builder only accepts one {@code Retriever} — a second
 * {@code .retriever(...)} call silently overwrites the first. This lets
 * several gated sources (each already a {@link ConditionalRetriever} or
 * similar, deciding on its own whether it's relevant to a given query) share
 * that single slot: tries each delegate in order and returns the first one
 * that actually has something to say, instead of one silently shadowing
 * another.
 */
public final class CompositeRetriever implements Retriever {

    private final List<Retriever> delegates;

    public CompositeRetriever(List<Retriever> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int topK) {
        for (Retriever delegate : delegates) {
            List<RetrievedChunk> chunks = delegate.retrieve(query, topK);
            if (!chunks.isEmpty()) {
                return chunks;
            }
        }
        return List.of();
    }
}
