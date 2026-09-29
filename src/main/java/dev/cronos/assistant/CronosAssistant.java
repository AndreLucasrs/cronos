package dev.cronos.assistant;

import dev.aegis4j.api.mcp.McpContentBlock;
import dev.aegis4j.api.mcp.McpToolResult;
import dev.aegis4j.api.rag.Embedder;
import dev.aegis4j.api.rag.Retriever;
import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.routing.RoutingRule;
import dev.aegis4j.core.engine.Aegis4jEngine;
import dev.aegis4j.core.engine.ChatRequest;
import dev.aegis4j.core.guard.GuardChain;
import dev.aegis4j.core.provider.ProviderRegistry;
import dev.aegis4j.core.routing.ModelRouter;
import dev.aegis4j.core.skill.SkillRegistry;
import dev.aegis4j.core.usage.InMemoryUsageTracker;
import dev.aegis4j.guardrails.builtin.MaxLengthGuard;
import dev.aegis4j.guardrails.builtin.RegexPiiGuard;
import dev.aegis4j.mcp.McpClient;
import dev.aegis4j.mcp.McpException;
import dev.aegis4j.mcp.transport.StdioMcpTransport;
import dev.aegis4j.rag.pgvector.PgVectorRetriever;
import dev.aegis4j.rag.pgvector.PgVectorRetrieverConfig;
import dev.aegis4j.routing.yaml.YamlRoutingRuleLoader;
import dev.aegis4j.skills.markdown.MarkdownSkillLoader;
import dev.cronos.config.Env;
import dev.cronos.rag.ConditionalRetriever;
import dev.cronos.rag.OllamaEmbedder;
import dev.cronos.rag.TaskIndexer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wires the embedded {@link Aegis4jEngine} with all four aegis4j pillars
 * this showcase demonstrates: Guardrails, Skills, RAG (pgvector, over
 * completed tasks) and Model Routing (two local Ollama models). MCP is used
 * separately — {@link #createReminderIcs} calls a companion server directly,
 * as a deterministic action, not through the engine (aegis4j v0.2 has no
 * LLM-driven tool-calling loop yet).
 */
public final class CronosAssistant {

    private final Aegis4jEngine engine;
    private final TaskIndexer taskIndexer;
    private final McpClient calendarMcpClient;
    private final InMemoryUsageTracker usageTracker;

    public CronosAssistant(DataSource dataSource) {
        Embedder embedder = OllamaEmbedder.fromEnv();
        this.taskIndexer = new TaskIndexer(dataSource, embedder);

        SkillRegistry skillRegistry = SkillRegistry.inMemory();
        loadSkills(skillRegistry);

        Retriever pgVectorRetriever = new PgVectorRetriever(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("task_embeddings"));
        // Gate RAG on the exact same keyword match that activates the
        // "tarefas-similares" skill — one source of truth, and no wasted
        // embedding calls (nor irrelevant context) on unrelated questions.
        Retriever retriever = new ConditionalRetriever(pgVectorRetriever, skillRegistry, "tarefas-similares");

        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.discover(Thread.currentThread().getContextClassLoader());

        ModelRouter modelRouter = loadModelRouter();
        this.usageTracker = new InMemoryUsageTracker();

        this.engine = Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(GuardChain.of(
                        MaxLengthGuard.forInput(Env.getInt("CRONOS_MAX_INPUT_CHARS", 4000)),
                        RegexPiiGuard.allPatterns()))
                .skillRegistry(skillRegistry)
                .retriever(retriever)
                .modelRouter(modelRouter)
                .usageTracker(usageTracker)
                .build();

        this.calendarMcpClient = connectCalendarMcp();
    }

    private void loadSkills(SkillRegistry skillRegistry) {
        Path skillsDir = Path.of(Env.get("CRONOS_SKILLS_DIR", "skills"));
        new MarkdownSkillLoader().loadDirectory(skillsDir).forEach(skillRegistry::register);
    }

    private ModelRouter loadModelRouter() {
        Path routingConfig = Path.of(Env.get("CRONOS_ROUTING_CONFIG", "routing.yaml"));
        YamlRoutingRuleLoader loader = new YamlRoutingRuleLoader();
        List<RoutingRule> rules = loader.loadFile(routingConfig);
        RouteTarget defaultTarget = loader.loadDefaultTarget(routingConfig);
        return new ModelRouter(rules, defaultTarget);
    }

    private McpClient connectCalendarMcp() {
        List<String> command = List.of(
                "node", Env.get("CRONOS_MCP_CALENDAR_ENTRY", "mcp-calendar/dist/index.js"));
        StdioMcpTransport transport = new StdioMcpTransport(command, Map.of(), Duration.ofSeconds(30));
        McpClient client = new McpClient(transport, "cronos", "0.1.0");
        client.initialize();
        return client;
    }

    public ChatResult chat(String userInput) {
        ChatRequest request = ChatRequest.builder()
                .userInput(userInput)
                .build();
        var response = engine.chat(request);
        int totalTokens = response.usage() == null ? 0 : response.usage().totalTokens();
        return new ChatResult(response.content(), response.model(), totalTokens);
    }

    /** Cumulative token usage per provider+model since JVM start, for {@code GET /api/assistant/usage}. */
    public List<UsageSummary> usageSummary() {
        List<UsageSummary> summary = new ArrayList<>();
        usageTracker.snapshot().forEach((key, usage) -> summary.add(new UsageSummary(
                key.providerId(),
                key.model(),
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens(),
                usageTracker.callCount(key.providerId(), key.model()))));
        return summary;
    }

    /**
     * Deterministic MCP action, triggered by a UI button — not by the LLM.
     * Returns empty on any MCP failure so a reminder hiccup never surfaces
     * as an unhandled error to the caller.
     */
    public Optional<String> createReminderIcs(String title, LocalDate dueDate, String description) {
        try {
            McpToolResult result = calendarMcpClient.callTool("criar_lembrete", Map.of(
                    "title", title,
                    "dueDate", dueDate.toString(),
                    "description", description == null ? "" : description));
            for (McpContentBlock block : result.content()) {
                if (block instanceof McpContentBlock.Resource resource) {
                    return Optional.of(resource.text());
                }
            }
            return Optional.empty();
        } catch (McpException e) {
            return Optional.empty();
        }
    }

    public TaskIndexer taskIndexer() {
        return taskIndexer;
    }

    public void close() {
        calendarMcpClient.close();
    }

    public record ChatResult(String reply, String model, int totalTokens) {
    }

    public record UsageSummary(
            String providerId, String model, int promptTokens, int completionTokens, int totalTokens, long calls) {
    }
}
