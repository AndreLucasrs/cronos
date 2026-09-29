package dev.cronos.rag;

import dev.aegis4j.api.rag.RetrievedChunk;
import dev.aegis4j.testkit.FakeRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeRetrieverTest {

    @Test
    void fallsThroughToTheNextDelegateWhenTheFirstOneReturnsNothing() {
        FakeRetriever empty = FakeRetriever.withChunks(List.of());
        List<RetrievedChunk> secondChunks = List.of(new RetrievedChunk("relevant fact", "doc-2", 0.7, Map.of()));
        FakeRetriever second = FakeRetriever.withChunks(secondChunks);
        CompositeRetriever composite = new CompositeRetriever(List.of(empty, second));

        List<RetrievedChunk> result = composite.retrieve("some query", 5);

        assertThat(result).isEqualTo(secondChunks);
    }

    @Test
    void shortCircuitsAndNeverCallsTheSecondDelegateOnceTheFirstOneMatches() {
        List<RetrievedChunk> firstChunks = List.of(new RetrievedChunk("first fact", "doc-1", 0.9, Map.of()));
        FakeRetriever first = FakeRetriever.withChunks(firstChunks);
        FakeRetriever second = FakeRetriever.withChunks(List.of(new RetrievedChunk("second fact", "doc-2", 0.5, Map.of())));
        CompositeRetriever composite = new CompositeRetriever(List.of(first, second));

        List<RetrievedChunk> result = composite.retrieve("some query", 5);

        assertThat(result).isEqualTo(firstChunks);
        assertThat(second.receivedQueries()).isEmpty();
    }
}
