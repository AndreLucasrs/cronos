package dev.cronos.rag;

import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.api.skill.ProgrammaticSkill;
import dev.aegis4j.api.skill.SkillDescriptor;
import dev.aegis4j.core.skill.SkillRegistry;
import dev.aegis4j.testkit.FakeRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionalRetrieverTest {

    private static final String GATE_SKILL_NAME = "tarefas-similares";

    // Same trigger keywords as the real skills/tarefas-similares.md ships with.
    private static SkillRegistry tarefasSimilaresRegistry() {
        SkillRegistry registry = SkillRegistry.inMemory();
        registry.register(new ProgrammaticSkill(new SkillDescriptor(
                GATE_SKILL_NAME,
                "Responde perguntas sobre tarefas parecidas já concluídas",
                List.of("parecido", "similar", "já fizemos", "antes", "histórico", "quanto levou"))) {
            @Override
            public String body() {
                return "unused in this test";
            }
        });
        return registry;
    }

    @Test
    void returnsDelegateChunksWhenQueryMatchesGateSkillKeyword() {
        SkillRegistry skillRegistry = tarefasSimilaresRegistry();
        List<RetrievedChunk> chunks = List.of(new RetrievedChunk("tarefa concluída X", "task-1", 0.8, Map.of()));
        FakeRetriever delegate = FakeRetriever.withChunks(chunks);
        ConditionalRetriever retriever = new ConditionalRetriever(delegate, skillRegistry, GATE_SKILL_NAME);

        List<RetrievedChunk> result = retriever.retrieve("já fizemos algo parecido antes?", 5);

        assertThat(result).isEqualTo(chunks);
        assertThat(delegate.receivedQueries()).containsExactly("já fizemos algo parecido antes?");
    }

    @Test
    void returnsEmptyAndNeverCallsDelegateWhenQueryDoesNotMatchGateSkillKeyword() {
        SkillRegistry skillRegistry = tarefasSimilaresRegistry();
        List<RetrievedChunk> chunks = List.of(new RetrievedChunk("tarefa concluída X", "task-1", 0.8, Map.of()));
        FakeRetriever delegate = FakeRetriever.withChunks(chunks);
        ConditionalRetriever retriever = new ConditionalRetriever(delegate, skillRegistry, GATE_SKILL_NAME);

        List<RetrievedChunk> result = retriever.retrieve("qual é a capital da frança?", 5);

        assertThat(result).isEmpty();
        // Proves the cheap no-op path: the delegate (a real pgvector retriever
        // in production) is never even invoked for an unrelated question.
        assertThat(delegate.receivedQueries()).isEmpty();
    }
}
