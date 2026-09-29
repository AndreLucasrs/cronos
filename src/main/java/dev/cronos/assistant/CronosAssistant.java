package dev.cronos.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aegis4j.api.mcp.McpContentBlock;
import dev.aegis4j.api.mcp.McpToolResult;
import dev.aegis4j.api.provider.ToolCall;
import dev.aegis4j.api.provider.ToolDefinition;
import dev.aegis4j.api.rag.Embedder;
import dev.aegis4j.api.rag.Retriever;
import dev.aegis4j.api.routing.RouteTarget;
import dev.aegis4j.api.routing.RoutingRule;
import dev.aegis4j.core.engine.Aegis4jEngine;
import dev.aegis4j.core.engine.ChatRequest;
import dev.aegis4j.core.engine.StreamedCompletion;
import dev.aegis4j.core.guard.GuardChain;
import dev.aegis4j.core.provider.ProviderRegistry;
import dev.aegis4j.core.routing.ModelRouter;
import dev.aegis4j.core.skill.SkillRegistry;
import dev.aegis4j.core.usage.InMemoryUsageTracker;
import dev.aegis4j.guardrails.builtin.JsonSchemaOutputGuard;
import dev.aegis4j.guardrails.builtin.MaxLengthGuard;
import dev.aegis4j.guardrails.builtin.PromptInjectionGuard;
import dev.aegis4j.guardrails.builtin.RegexPiiGuard;
import dev.aegis4j.guardrails.builtin.grounding.HallucinationGuard;
import dev.aegis4j.mcp.McpClient;
import dev.aegis4j.mcp.McpException;
import dev.aegis4j.mcp.transport.HttpSseMcpTransport;
import dev.aegis4j.mcp.transport.StdioMcpTransport;
import dev.aegis4j.observability.otel.OtelEngineListener;
import dev.aegis4j.provider.ollama.OllamaProvider;
import dev.aegis4j.provider.openai.OpenAiCompatibleProvider;
import dev.aegis4j.rag.mcp.McpToolArgumentMapper;
import dev.aegis4j.rag.mcp.McpToolRetriever;
import dev.aegis4j.rag.pgvector.PgVectorRetriever;
import dev.aegis4j.rag.pgvector.PgVectorRetrieverConfig;
import dev.aegis4j.rag.pgvector.ingest.Document;
import dev.aegis4j.rag.pgvector.ingest.FixedSizeChunker;
import dev.aegis4j.rag.pgvector.ingest.PgVectorIngester;
import dev.aegis4j.routing.yaml.YamlRoutingRuleLoader;
import dev.aegis4j.skills.markdown.MarkdownSkillLoader;
import dev.cronos.config.Env;
import dev.cronos.rag.CompositeRetriever;
import dev.cronos.rag.ConditionalRetriever;
import dev.cronos.rag.OllamaEmbedder;
import dev.cronos.rag.TaskIndexer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Wires the embedded {@link Aegis4jEngine} with all four aegis4j pillars
 * this showcase demonstrates: Guardrails, Skills, RAG (pgvector, over
 * completed tasks) and Model Routing (two local Ollama models). MCP is used
 * in two ways: {@link #createReminderIcs} calls the companion server
 * directly for the deterministic button flow, and {@link #reminderTool}
 * exposes the same "criar_lembrete" action as a real tool the model can
 * decide to call, via the aegis4j v0.3 tool-calling loop (only
 * {@code OpenAiCompatibleProvider} sends {@code tools} on the wire today,
 * hence the extra "ollama-openai" provider registered below).
 */
public final class CronosAssistant {

    /** Table PgVectorIngester writes project briefing chunks to, and PgVectorRetriever reads them back from. */
    private static final String PROJECT_BRIEF_TABLE = "project_brief_chunks";

    private static final String ESTIMATE_SCHEMA_PROMPT =
            "{\"minDays\": number, \"maxDays\": number, \"reasoning\": string}";

    private static final Map<String, Object> ESTIMATE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "minDays", Map.of("type", "number"),
                    "maxDays", Map.of("type", "number"),
                    "reasoning", Map.of("type", "string")),
            "required", List.of("minDays", "maxDays", "reasoning"));

    private final Aegis4jEngine engine;
    private final Aegis4jEngine structuredEngine;
    private final String structuredModel;
    private final TaskIndexer taskIndexer;
    private final McpClient calendarMcpClient;
    private final McpClient searchMcpClient;
    private final ObjectMapper toolArgsMapper = new ObjectMapper();
    private final InMemoryUsageTracker usageTracker;
    private final PgVectorIngester projectBriefIngester;

    public CronosAssistant(DataSource dataSource) {
        Embedder embedder = OllamaEmbedder.fromEnv();
        this.taskIndexer = new TaskIndexer(dataSource, embedder);
        this.projectBriefIngester = new PgVectorIngester(
                dataSource, embedder, PgVectorRetrieverConfig.defaults(PROJECT_BRIEF_TABLE),
                new FixedSizeChunker(800, 100));

        SkillRegistry skillRegistry = SkillRegistry.inMemory();
        loadSkills(skillRegistry);

        Retriever pgVectorRetriever = new PgVectorRetriever(
                dataSource, embedder, PgVectorRetrieverConfig.defaults("task_embeddings"));
        Retriever projectBriefRetriever = new PgVectorRetriever(
                dataSource, embedder, PgVectorRetrieverConfig.defaults(PROJECT_BRIEF_TABLE));

        this.searchMcpClient = connectSearchMcp();
        // Third RAG path: lexical (ILIKE) search over tasks via the
        // mcp-search/ companion server, reached over MCP's "Streamable
        // HTTP" transport instead of direct JDBC — the engine only ever
        // sees a Retriever, never knows this one is backed by a remote MCP
        // call. Its tool already names its params query/top_k, so the
        // default argument mapper needs no customization.
        Retriever mcpSearchRetriever = new McpToolRetriever(
                searchMcpClient, "buscar_tarefas", McpToolArgumentMapper.defaultMapper());
        // Gate RAG on the exact same keyword match that activates the
        // "tarefas-similares" skill — one source of truth, and no wasted
        // embedding calls (nor irrelevant context) on unrelated questions.
        // Wrapped in a CompositeRetriever because Aegis4jEngine.Builder only
        // has one .retriever(...) slot — each gated source tries in order,
        // first non-empty result wins, so a new source added here never
        // silently overwrites an existing one.
        Retriever retriever = new CompositeRetriever(List.of(
                new ConditionalRetriever(pgVectorRetriever, skillRegistry, "tarefas-similares"),
                new ConditionalRetriever(projectBriefRetriever, skillRegistry, "buscar-no-briefing"),
                new ConditionalRetriever(mcpSearchRetriever, skillRegistry, "busca-lexical-tarefas")));

        ProviderRegistry providerRegistry = new ProviderRegistry();
        providerRegistry.discover(Thread.currentThread().getContextClassLoader());
        // Only OpenAiCompatibleProvider sends `tools`/parses `tool_calls` on
        // the wire as of v0.3 — OllamaProvider silently ignores them — so
        // tool-calling turns are routed here instead (see routing.yaml).
        providerRegistry.register(OpenAiCompatibleProvider.custom(
                "ollama-openai", Env.get("CRONOS_OLLAMA_OPENAI_BASE_URL", "http://localhost:11434/v1"), ""));
        // Same wire format, remote process: a live aegis4j-server sidecar
        // (dual distribution — see routing.yaml for the route that uses it).
        // Wrapped in ToolStrippingProvider: the engine attaches the
        // reminder tool to every chat() call regardless of route, and
        // aegis4j-server v0.3.1's request DTO 500s on an unrecognized
        // "tools" field — see that class's javadoc.
        providerRegistry.register(new ToolStrippingProvider(OpenAiCompatibleProvider.custom(
                "aegis4j-sidecar", Env.get("CRONOS_AEGIS4J_SIDECAR_URL", "http://localhost:8686/v1"), "")));

        ModelRouter modelRouter = loadModelRouter();
        // Illustrative pricing only — every model Cronos actually talks to is
        // free/local, so real cost is always $0. Priced here anyway (a fictional
        // cloud-equivalent rate) purely to demonstrate InMemoryUsageTracker's
        // estimatedCost(), which is otherwise indistinguishable from "wired but
        // never actually exercised."
        this.usageTracker = new InMemoryUsageTracker(Map.of(
                "llama3.2:3b", new InMemoryUsageTracker.PricingRate(0.0001, 0.0002)));

        this.calendarMcpClient = connectCalendarMcp();

        this.engine = Aegis4jEngine.builder()
                .providerRegistry(providerRegistry)
                .guardChain(GuardChain.of(
                        MaxLengthGuard.forInput(Env.getInt("CRONOS_MAX_INPUT_CHARS", 4000)),
                        RegexPiiGuard.allPatterns(),
                        PromptInjectionGuard.defaultPatterns(),
                        buildHallucinationGuard()))
                .skillRegistry(skillRegistry)
                .retriever(retriever)
                .modelRouter(modelRouter)
                .tools(List.of(reminderTool()), this::executeReminderTool)
                .usageTracker(usageTracker)
                .listener(new OtelEngineListener(buildOpenTelemetry()))
                .build();

        // Dedicated engine for structured-output features: JsonSchemaOutputGuard
        // always BLOCKs non-conforming output (no WARN mode), so it can't share
        // the main chat GuardChain above without blocking every free-form reply.
        // No skills/retrieval/routing needed — provider+model are fixed directly
        // on each ChatRequest instead.
        ProviderRegistry structuredProviderRegistry = new ProviderRegistry();
        structuredProviderRegistry.register(OllamaProvider.create());
        this.structuredModel = Env.get("CRONOS_ESTIMATE_MODEL", "qwen2.5-coder:7b");
        this.structuredEngine = Aegis4jEngine.builder()
                .providerRegistry(structuredProviderRegistry)
                .guardChain(GuardChain.of(
                        MaxLengthGuard.forInput(Env.getInt("CRONOS_MAX_INPUT_CHARS", 4000)),
                        new JsonSchemaOutputGuard(ESTIMATE_SCHEMA)))
                .build();
    }

    /**
     * Minimal OTel SDK bootstrap for this demo: spans go straight to stdout
     * via {@link LoggingSpanExporter} — no collector to stand up. Swapping in
     * a real backend (Jaeger, Grafana, etc.) later is just swapping the
     * {@code SpanExporter} passed to {@link SimpleSpanProcessor#create}.
     * {@code SimpleSpanProcessor} (synchronous) over {@code BatchSpanProcessor}
     * is deliberate here — this is a low-traffic local demo, and exporting a
     * span right after its request makes it trivial to correlate by hand.
     */
    private static OpenTelemetry buildOpenTelemetry() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    private void loadSkills(SkillRegistry skillRegistry) {
        Path skillsDir = Path.of(Env.get("CRONOS_SKILLS_DIR", "skills"));
        new MarkdownSkillLoader().loadDirectory(skillsDir).forEach(skillRegistry::register);
    }

    // Fail-open judge on a second Ollama call — WARN mode only, since a false
    // positive from a free local judge model must never block an otherwise
    // working demo answer. No-op (zero extra latency) on turns where RAG
    // didn't retrieve anything, per HallucinationGuard's own contract.
    private HallucinationGuard buildHallucinationGuard() {
        return HallucinationGuard.warning(OllamaProvider.create(), "llama3.2:3b");
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

    /** mcp-search/ runs as its own long-lived HTTP process (not a subprocess we own), unlike mcp-calendar's stdio transport. */
    private McpClient connectSearchMcp() {
        URI endpoint = URI.create(Env.get("CRONOS_MCP_SEARCH_URL", "http://localhost:3939/mcp"));
        HttpSseMcpTransport transport = new HttpSseMcpTransport(
                endpoint, Map.of(), HttpClient.newHttpClient(), Duration.ofSeconds(30));
        McpClient client = new McpClient(transport, "cronos", "0.1.0");
        client.initialize();
        return client;
    }

    /** Schema for the "criar_lembrete" tool — mirrors mcp-calendar's own inputSchema (title/dueDate required, description optional). */
    private ToolDefinition reminderTool() {
        return new ToolDefinition(
                "criar_lembrete",
                "Cria um lembrete de calendário (.ics) para uma tarefa com prazo definido.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string", "description", "Título da tarefa / do evento"),
                                "dueDate", Map.of("type", "string", "description", "Data no formato YYYY-MM-DD"),
                                "description", Map.of("type", "string", "description", "Descrição opcional da tarefa")),
                        "required", List.of("title", "dueDate")));
    }

    /**
     * Runs the model-decided "criar_lembrete" tool call: parses the model's
     * raw JSON arguments, calls the same MCP tool {@link #createReminderIcs}
     * uses, and writes the resulting .ics under a fresh UUID (there's no
     * task id here — the model, not a task row, triggered this).
     */
    private String executeReminderTool(ToolCall call) {
        JsonNode args;
        try {
            args = toolArgsMapper.readTree(call.argumentsJson());
        } catch (IOException e) {
            throw new UncheckedIOException("Invalid criar_lembrete arguments from model", e);
        }
        String title = args.path("title").asText(null);
        String dueDate = args.path("dueDate").asText(null);
        String description = args.path("description").asText(null);
        if (title == null || dueDate == null) {
            return "Faltam campos obrigatórios (title, dueDate) para criar o lembrete.";
        }

        Optional<String> ics = createReminderIcs(title, LocalDate.parse(dueDate), description);
        if (ics.isEmpty()) {
            return "Falha ao gerar o lembrete via mcp-calendar.";
        }

        try {
            Path remindersDir = Path.of(Env.get("CRONOS_REMINDERS_DIR", "reminders"));
            Files.createDirectories(remindersDir);
            String fileName = UUID.randomUUID() + ".ics";
            Files.writeString(remindersDir.resolve(fileName), ics.get());
            return "Lembrete criado: /reminders/" + fileName;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write reminder .ics to disk", e);
        }
    }

    public ChatResult chat(String userInput) {
        ChatRequest request = ChatRequest.builder()
                .userInput(userInput)
                .build();
        var response = engine.chat(request);
        int totalTokens = response.usage() == null ? 0 : response.usage().totalTokens();
        return new ChatResult(response.content(), response.model(), totalTokens);
    }

    /**
     * Structured task-effort estimate via {@link #structuredEngine} —
     * {@link JsonSchemaOutputGuard} blocks (throws {@link
     * dev.aegis4j.core.guard.GuardBlockedException}, left to propagate, same
     * as {@link #chat} does implicitly) whenever the model's reply isn't pure
     * JSON matching {@link #ESTIMATE_SCHEMA}, so the prompt below explicitly
     * forbids prose/markdown fences around the JSON.
     */
    public String estimateStructured(String taskTitle, String taskDescription) {
        String description = taskDescription == null ? "" : taskDescription;
        // "em uma única linha, sem quebras de linha" matters in practice: qwen2.5-coder:7b
        // otherwise occasionally puts a raw newline inside the "reasoning" string value
        // (valid-looking pretty-printed JSON to the eye, but not strict JSON), which
        // JsonSchemaOutputGuard correctly rejects as malformed — see README.
        String userInput = "Estime o esforço da tarefa \"" + taskTitle + "\" (" + description + "). "
                + "Responda APENAS com um JSON válido em uma única linha, no formato exato "
                + ESTIMATE_SCHEMA_PROMPT
                + ", sem quebras de linha, sem markdown, sem crases, sem nenhum texto antes ou depois.";
        ChatRequest request = ChatRequest.builder()
                .userInput(userInput)
                .providerId(OllamaProvider.ID)
                .model(structuredModel)
                .build();
        return structuredEngine.chat(request).content();
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
                usageTracker.callCount(key.providerId(), key.model()),
                usageTracker.estimatedCost(key.providerId(), key.model()))));
        return summary;
    }

    /**
     * Streamed reply — see {@link Aegis4jEngine#chatStream} javadoc: output
     * guards, tool-calling and usage tracking do NOT run on this path.
     */
    public StreamedCompletion chatStream(String userInput) {
        ChatRequest request = ChatRequest.builder()
                .userInput(userInput)
                .build();
        return engine.chatStream(request);
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

    /**
     * Ingests a project's free-text briefing through aegis4j's real
     * document pipeline (chunk → embed → upsert into
     * {@value #PROJECT_BRIEF_TABLE}), unlike {@link TaskIndexer}'s
     * hand-rolled single-row indexing. Using the project id as the
     * {@code Document} id means re-ingesting the same project's briefing
     * naturally replaces its old chunks — upsert plus orphan cleanup for
     * chunks a shrunk re-ingestion no longer produces, both built into
     * {@code PgVectorIngester}.
     *
     * @return the number of chunks written
     */
    public int ingestProjectBrief(UUID projectId, String markdown) {
        Document document = new Document(projectId.toString(), markdown, Map.of());
        return projectBriefIngester.ingest(List.of(document));
    }

    public void close() {
        calendarMcpClient.close();
        searchMcpClient.close();
    }

    public record ChatResult(String reply, String model, int totalTokens) {
    }

    /** {@code estimatedCost} is 0 for any model with no {@link InMemoryUsageTracker.PricingRate} configured — see its construction above. */
    public record UsageSummary(
            String providerId, String model, int promptTokens, int completionTokens, int totalTokens, long calls,
            double estimatedCost) {
    }
}
