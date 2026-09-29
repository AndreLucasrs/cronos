package dev.cronos.assistant;

import dev.aegis4j.core.engine.Aegis4jEngine;
import dev.aegis4j.core.engine.ChatRequest;
import dev.aegis4j.core.guard.GuardBlockedException;
import dev.aegis4j.core.guard.GuardChain;
import dev.aegis4j.core.provider.ProviderRegistry;
import dev.aegis4j.core.routing.ModelRouter;
import dev.aegis4j.guardrails.builtin.MaxLengthGuard;
import dev.aegis4j.guardrails.builtin.RegexPiiGuard;
import dev.aegis4j.guardrails.builtin.grounding.HallucinationGuard;
import dev.aegis4j.routing.yaml.YamlRoutingRuleLoader;
import dev.aegis4j.testkit.FakeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real configuration cronos ships — the same guard chain shape
 * (in the same order) as {@link CronosAssistant}'s constructor, and a
 * routing.yaml-shaped fixture loaded through the real {@link YamlRoutingRuleLoader}
 * — against a hand-built {@link Aegis4jEngine} wired with {@link FakeProvider}
 * instead of the live Ollama stack {@code CronosAssistant} builds internally.
 *
 * <p>{@code CronosAssistant} itself is not exercised here: its constructor
 * builds a real embedder, a real MCP client connection and real Ollama
 * providers eagerly, with no seam to substitute fakes, and refactoring it to
 * be fully dependency-injectable is out of scope for this test suite.
 */
class CronosEngineWiringTest {

    private static final String ROUTING_FIXTURE = """
            rules:
              - match: {keyword: [lembrete, lembrar, agenda]}
                provider: fake-tools
                model: fake-tools-model
              - match:
                  keyword: [quanto tempo, estimativa, estimar, prazo, quantos dias, replanejar, replanejamento]
                provider: fake-estimator
                model: fake-estimator-model
            default:
              provider: fake-default
              model: fake-default-model
            """;

    private static ModelRouter loadRoutingFixture(Path tempDir) throws IOException {
        Path routingFile = tempDir.resolve("routing-fixture.yaml");
        Files.writeString(routingFile, ROUTING_FIXTURE);
        YamlRoutingRuleLoader loader = new YamlRoutingRuleLoader();
        return new ModelRouter(loader.loadFile(routingFile), loader.loadDefaultTarget(routingFile));
    }

    /**
     * Same guards, in the same order, as {@code CronosAssistant}'s constructor
     * (see its {@code guardChain(GuardChain.of(...))} call) — MaxLengthGuard,
     * then RegexPiiGuard, then HallucinationGuard. The judge provider is a
     * {@link FakeProvider} rather than real Ollama, but it is never actually
     * invoked in these tests since no {@code Retriever} is configured:
     * {@code HallucinationGuard} is a documented no-op with no retrieved
     * chunks, so it's safe (and faithful to the real shape) to include here.
     */
    private static GuardChain cronosGuardChain(int maxInputChars) {
        FakeProvider judge = FakeProvider.withId("fake-judge").respondingWith("VERDICT: GROUNDED\nCONFIDENCE: 1.0\nREASON: n/a");
        return GuardChain.of(
                MaxLengthGuard.forInput(maxInputChars),
                RegexPiiGuard.allPatterns(),
                HallucinationGuard.warning(judge, "judge-model"));
    }

    @Test
    void resolvesTheExpectedModelForAMessageMatchingTheEstimativaRoutingKeyword(@TempDir Path tempDir) throws IOException {
        ModelRouter router = loadRoutingFixture(tempDir);

        FakeProvider estimator = FakeProvider.withId("fake-estimator").respondingWith("estimativa: 2 a 4 dias");
        FakeProvider defaultProvider = FakeProvider.withId("fake-default").respondingWith("ok");
        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.register(estimator);
        providerRegistry.register(defaultProvider);

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(cronosGuardChain(4000))
                .modelRouter(router)
                .build();

        engine.chat(ChatRequest.builder()
                .userInput("Preciso de uma estimativa para essa tarefa")
                .build());

        assertThat(estimator.lastRequest().model()).isEqualTo("fake-estimator-model");
        assertThat(defaultProvider.receivedRequests()).isEmpty();
    }

    @Test
    void regexPiiGuardRedactsAnEmailAndPhoneNumberRoundTripThroughFakeProvider(@TempDir Path tempDir) throws IOException {
        ModelRouter router = loadRoutingFixture(tempDir);

        // Echoes the (already input-redacted) last user message back, simulating
        // a model that might otherwise leak PII straight into its own answer —
        // this is what makes the round trip prove BOTH the input and output guard.
        FakeProvider defaultProvider = FakeProvider.withId("fake-default")
                .respondingWith(request -> "Resumo: " + request.messages().get(request.messages().size() - 1).content());
        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.register(defaultProvider);

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(cronosGuardChain(4000))
                .modelRouter(router)
                .build();

        var response = engine.chat(ChatRequest.builder()
                .userInput("Meu email é andre@example.com e meu telefone é (11) 98765-4321")
                .build());

        assertThat(defaultProvider.lastRequest().messages().toString())
                .doesNotContain("andre@example.com")
                .contains("[EMAIL_REDACTED]");
        assertThat(response.content())
                .doesNotContain("andre@example.com")
                .contains("[EMAIL_REDACTED]");
    }

    @Test
    void maxLengthGuardBlocksAnOversizedInputBeforeItEverReachesTheProvider(@TempDir Path tempDir) throws IOException {
        ModelRouter router = loadRoutingFixture(tempDir);

        FakeProvider defaultProvider = FakeProvider.withId("fake-default").respondingWith("ok");
        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.register(defaultProvider);

        Aegis4jEngine engine = Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(cronosGuardChain(20))
                .modelRouter(router)
                .build();

        String oversizedInput = "x".repeat(21);

        assertThatThrownBy(() -> engine.chat(ChatRequest.builder().userInput(oversizedInput).build()))
                .isInstanceOf(GuardBlockedException.class);
        assertThat(defaultProvider.receivedRequests()).isEmpty();
    }
}
