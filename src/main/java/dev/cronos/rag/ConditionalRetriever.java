package dev.cronos.rag;

import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.api.rag.Retriever;
import dev.aegis4j.core.skill.SkillRegistry;

import java.util.List;

/**
 * Aegis4jEngine v0.2's {@code Retriever} is unconditional — it fires on
 * every message, not just ones actually about past tasks. There is no
 * {@code RetrievalTriggerStrategy} abstraction yet to gate it at the engine
 * level, so this gates it at the application level instead: reuses the
 * exact same keyword match that activates {@code gateSkillName}'s skill, so
 * RAG only runs when that skill would also activate — one source of truth
 * for "is this question about similar tasks", not two lists to keep in sync.
 */
public final class ConditionalRetriever implements Retriever {

    private final Retriever delegate;
    private final SkillRegistry skillRegistry;
    private final String gateSkillName;

    public ConditionalRetriever(Retriever delegate, SkillRegistry skillRegistry, String gateSkillName) {
        this.delegate = delegate;
        this.skillRegistry = skillRegistry;
        this.gateSkillName = gateSkillName;
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int topK) {
        boolean relevant = skillRegistry.matchByKeywords(query).stream()
                .anyMatch(skill -> skill.descriptor().name().equals(gateSkillName));
        return relevant ? delegate.retrieve(query, topK) : List.of();
    }
}
