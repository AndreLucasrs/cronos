# Cronos

Gestão de cronograma de projetos — e vitrine funcional do
[aegis4j](https://github.com/AndreLucasrs/aegis4j), uma lib JVM de
guardrails, skills, RAG, roteamento de modelo e cliente MCP para colocar na
frente de LLMs.

O domínio (cronograma: projetos, tarefas, prazos, dependências) não é o
ponto — o ponto é mostrar, com um app real rodando, como cada pilar do
aegis4j se encaixa no dia a dia sem parecer forçado. **v0.2: os cinco
pilares estão todos ativos.**

## Como rodar

Pré-requisitos: JDK 17+, Node 20+, Docker, e um [Ollama](https://ollama.com)
local rodando com três modelos:

```bash
ollama pull llama3.2:3b       # modelo rápido — routing: perguntas simples
ollama pull deepseek-r1:14b   # modelo maior — routing: estimativa/replanejamento
ollama pull nomic-embed-text  # embedding — RAG sobre tarefas concluídas

docker compose up -d          # Postgres com pgvector

cd mcp-calendar && npm install && npm run build && cd ..   # servidor MCP do calendário
cd mcp-search && npm install && npm run build && cd ..     # servidor MCP de busca lexical (rodar antes: node mcp-search/dist/index.js &)

./gradlew run
```

Abra `http://localhost:7070`. Crie um projeto, adicione tarefas com prazo,
converse com o assistente na lateral, marque uma tarefa como concluída
(fica indexada pro RAG), e crie um lembrete `.ics` numa tarefa com prazo.

Opcional: pra ver a rota `aegis4j-sidecar` funcionando (mensagens com
"sidecar"/"via http"/"modo servidor"), suba também o `aegis4j-server` a
partir do repo do aegis4j (`AEGIS4J_PROVIDER_ID=ollama ./gradlew
:aegis4j-server:run`, porta `8686`). O Cronos não inicia esse processo
sozinho — sem ele rodando, só essas mensagens específicas falham com erro
de conexão; o resto do app funciona normalmente.

Variáveis de ambiente (todas opcionais, com default de dev local):

| Variável | Default | Uso |
|---|---|---|
| `CRONOS_PORT` | `7070` | Porta HTTP |
| `CRONOS_DB_URL` / `CRONOS_DB_USER` / `CRONOS_DB_PASSWORD` | aponta pro `docker-compose.yml` local | Postgres |
| `AEGIS4J_OLLAMA_BASE_URL` | `http://localhost:11434` | Endereço do Ollama |
| `CRONOS_MAX_INPUT_CHARS` | `4000` | Limite do `MaxLengthGuard` |
| `CRONOS_SKILLS_DIR` | `skills` | Diretório de skills markdown |
| `CRONOS_EMBED_MODEL` / `CRONOS_EMBED_DIMENSIONS` | `nomic-embed-text` / `768` | Modelo e dimensão do embedding (RAG) |
| `CRONOS_ROUTING_CONFIG` | `routing.yaml` | Regras de model routing |
| `CRONOS_MCP_CALENDAR_ENTRY` | `mcp-calendar/dist/index.js` | Comando do servidor MCP do calendário |
| `CRONOS_MCP_SEARCH_URL` | `http://localhost:3939/mcp` | Endpoint HTTP do servidor MCP de busca lexical (`mcp-search/`) |
| `CRONOS_REMINDERS_DIR` | `reminders` | Onde os `.ics` gerados ficam salvos/servidos |
| `CRONOS_AEGIS4J_SIDECAR_URL` | `http://localhost:8686/v1` | Endpoint do `aegis4j-server` (sidecar), processo separado — ver rota `aegis4j-sidecar` em `routing.yaml` |

**`CRONOS_PROVIDER_ID`/`CRONOS_OLLAMA_MODEL` não existem mais** — desde a
v0.2 o `ModelRouter` decide provider/model em toda mensagem (ver `routing.yaml`).

## Build e testes

```bash
./gradlew test
```

Primeira suíte automatizada do cronos: `ConditionalRetriever` e
`CompositeRetriever` (os dois compositores de RAG), o guard chain
(`MaxLengthGuard`, `RegexPiiGuard`, `HallucinationGuard`) e o `ModelRouter`
lendo um fixture no formato do `routing.yaml`. Usa o `aegis4j-testkit`
(`FakeProvider`/`FakeRetriever`) — nada de Ollama, Postgres ou MCP real
rodando para os testes passarem.

## O que está aceso (v0.2 — todos os pilares ativos)

| Pilar do aegis4j | Nesta v0.2 | Onde |
|---|---|---|
| **Guardrails** | ✅ | `MaxLengthGuard` bloqueia prompt gigante; `RegexPiiGuard` redige e-mail/telefone/cartão antes do modelo; `PromptInjectionGuard.defaultPatterns()` agora roda em toda a `GuardChain` principal (bilíngue pt/en, só no input) — `CronosAssistant` |
| **Skills (progressive disclosure)** | ✅ | `skills/estimativa-esforco.md` (heurística sem histórico) + `skills/tarefas-similares.md` (usa o RAG) |
| **RAG (pgvector)** | ✅ | `OllamaEmbedder` (chama `/api/embed` do Ollama — nenhum módulo do aegis4j ainda publica um `Embedder` concreto) + `PgVectorRetriever` sobre `task_embeddings`, indexada automaticamente quando uma tarefa vira `done` (`TaskIndexer`). Só é consultado quando a pergunta bate com a mesma keyword que ativa a skill `tarefas-similares` (`ConditionalRetriever`) — ver limitações |
| **RAG (MCP)** | ✅ | Segundo caminho de retrieval, lado a lado com o pgvector via `CompositeRetriever`: `McpToolRetriever` (`aegis4j-rag-mcp`) chama a tool `buscar_tarefas` do servidor companheiro `mcp-search/` (Node, `pg` direto) sobre `HttpSseMcpTransport` — MCP "Streamable HTTP", não stdio. É busca **lexical** (`ILIKE` em título/descrição), deliberadamente diferente da busca semântica do pgvector; gatilhada pela skill `busca-lexical-tarefas` |
| **Model routing** | ✅ | `routing.yaml` + `ModelRouter` — pergunta simples cai no `llama3.2:3b`, "estimativa"/"prazo"/"replanejar" cai no `deepseek-r1:14b`. Resposta do chat inclui `model` usado, pra ficar visível qual rota foi tomada. Rotear pra Anthropic/OpenAI de verdade é só trocar `provider`/`model` no YAML e ter a chave configurada |
| **Usage tracking (v0.3)** | ✅ | `InMemoryUsageTracker` plugado no `Aegis4jEngine`. Cada resposta do chat mostra os tokens daquela mensagem na legenda; o acumulado por modelo desde o start da JVM fica em `GET /api/assistant/usage` e no painel "Uso de tokens" ao lado do chat |
| **MCP client** | ✅ | Dois caminhos lado a lado pra tool `criar_lembrete` do servidor companheiro `mcp-calendar/`: o botão "Criar lembrete" (`POST /api/tasks/{id}/reminder`) segue **determinístico**, chamando `McpClient` (stdio) direto; e agora o assistente também cria lembrete **decidindo sozinho**, via o loop de tool-calling do `Aegis4jEngine` v0.3 (`CronosAssistant.reminderTool`/`executeReminderTool`), roteado por `routing.yaml` pro `OpenAiCompatibleProvider` (`ollama-openai` / `llama3.1:8b` local) — só esse provider manda `tools`/parseia `tool_calls` hoje. Ambos geram `.ics` em vez de Google Calendar real pra não depender de credencial OAuth |
| **Dual distribution (embutido + sidecar)** | ✅ | O motor principal continua o `Aegis4jEngine` embutido direto no processo do Javalin — mas mensagens que batem com a rota `sidecar`/`via http`/`modo servidor` de `routing.yaml` são roteadas pro provider `aegis4j-sidecar`, um `OpenAiCompatibleProvider` apontando pra uma instância **separada** do `aegis4j-server` (`CRONOS_AEGIS4J_SIDECAR_URL`, default `http://localhost:8686/v1`) rodando como processo HTTP à parte (repo do aegis4j, não iniciado pelo Cronos) — a mesma engine, nos dois modos de distribuição, interoperando. `ToolStrippingProvider` remove `tools` da requisição antes de mandar pro sidecar — `aegis4j-server` v0.3.1 ainda não aceita esse campo (planejado v0.4+, ver limitações) |
| **Observabilidade (EngineListener/OTel)** | ✅ | `OtelEngineListener` (`aegis4j-observability-otel`) plugado via `.listener(...)` no builder — um span `aegis4j.chat` por chamada, com atributos de provider/model/duração/tokens. Pra essa demo os spans só vão pro log (`LoggingSpanExporter`, `SimpleSpanProcessor`); trocar por um backend real (Jaeger, Grafana, etc.) é só trocar o `SpanExporter`, nada mais muda |

Desde o bump pra v0.3, o `GuardChain` também tem um `HallucinationGuard` (modo
`WARN`) no final — julga se a resposta está de fato apoiada no que o RAG
recuperou, usando o `llama3.2:3b` como juiz numa segunda chamada. Só entra em
ação quando `retrievedChunks` não está vazio (ou seja, só no caminho da
`tarefas-similares`); nas demais mensagens é um no-op sem custo extra.

### Streaming (v0.3)

`POST /api/assistant/chat/stream` usa `Aegis4jEngine.chatStream` (SSE,
`data: {"delta":"..."}` por token, `data: [DONE]` no final) — tem um toggle
"streaming" no chat da UI, **desligado por padrão**. Não é upgrade estrito
do `/api/assistant/chat`: pelo próprio javadoc do `chatStream` no aegis4j,
guards de saída (`RegexPiiGuard`), tool-calling e tracking de usage **não
rodam** nesse caminho — só no `chat()` não-streamado. A UI marca a mensagem
em streaming visualmente pra deixar essa troca visível, não escondida.

### Briefing de projeto via pipeline de ingestão de documentos (v0.3)

`PUT /api/projects/{id}/brief` (`{"markdown": "..."}`) ingere um texto livre
(o "briefing" do projeto — escopo, contexto, restrições) pelo pipeline de
**documentos** do aegis4j (`aegis4j-rag-jdbc-pgvector`'s `ingest`), diferente
do `TaskIndexer`, que indexa tarefas linha a linha sem passar por esse
pipeline: `FixedSizeChunker(800, 100)` quebra o markdown em pedaços
sobrepostos e `PgVectorIngester` embeda e grava cada um na nova tabela
`project_brief_chunks` (mesmo shape de `task_embeddings`). Usar o id do
próprio projeto como id do `Document` faz um novo `PUT` do mesmo projeto
substituir os chunks antigos automaticamente (upsert + limpeza de órfãos, já
embutidos no `PgVectorIngester`). Retorna `{"chunksIndexed": N}`. A skill
`skills/buscar-no-briefing.md` (gatilhos: `briefing`, `escopo do projeto`,
`o que diz o briefing`, `codinome`) ativa um `PgVectorRetriever` sobre essa
tabela, adicionado à mesma `CompositeRetriever` que já servia
`tarefas-similares` — cada fonte de RAG continua isolada por skill, sem uma
sobrescrever a outra.

### Estimativa estruturada de esforço (v0.3)

`POST /api/tasks/{id}/estimate` pede ao modelo um JSON estrito
(`{"minDays", "maxDays", "reasoning"}`) para a tarefa indicada, validado por
`JsonSchemaOutputGuard`. Esse guard **sempre bloqueia** saída que não seja
JSON válido no schema (não tem modo `WARN`) — colocá-lo na `GuardChain`
principal do chat bloquearia toda resposta em prosa livre, então ele vive
num **segundo `Aegis4jEngine`** (`CronosAssistant.structuredEngine`), com
`ProviderRegistry`/`GuardChain` próprios (só `MaxLengthGuard` +
`JsonSchemaOutputGuard`) e provider/model fixos direto no `ChatRequest`, sem
skills/RAG/routing. Testado com `qwen2.5-coder:7b` (bom em seguir formato
estruturado) — `deepseek-r1:14b` foi descartado de propósito por ser um
modelo "thinking" que antepõe raciocínio em prosa antes do JSON, quebrando a
saída pura. Mesmo o `qwen2.5-coder:7b` falhava ocasionalmente (~1 em 3) por
colocar uma quebra de linha crua dentro do valor de `reasoning` — JSON
tecnicamente inválido, mas bonito aos olhos; pedir explicitamente "em uma
única linha, sem quebras de linha" no prompt eliminou o problema nos testes
manuais (8/8 e depois 5/5 chamadas válidas). Um bloqueio genuíno vira
`GuardBlockedException` → `422 {"error":"blocked","reasonCode":...}`, igual
ao chat principal.

### Limitações honestas, não escondidas

- O `Retriever` do `Aegis4jEngine` v0.2 em si é **incondicional** — não tem
  nenhuma abstração de "só busca se for relevante" (`RetrievalTriggerStrategy`
  não existe ainda). O Cronos contorna isso na aplicação: `ConditionalRetriever`
  só chama o `PgVectorRetriever` de verdade quando a pergunta bate com a
  mesma keyword que ativaria a skill `tarefas-similares` — mesma fonte de
  verdade das duas coisas, sem lista duplicada. O gap de engine continua
  existindo (é uma limitação do próprio aegis4j), só não afeta mais o
  Cronos na prática.
- Modelos locais pequenos **nem sempre seguem as instruções da skill à
  risca** — o `llama3.2:3b` já respondeu citando o mecanismo interno de
  "Context" e contradizendo o próprio Context que tinha acabado de listar.
  Mitigado (não eliminado) com um exemplo explícito de resposta errada vs.
  certa na skill (`tarefas-similares.md`) — ajuda modelo pequeno seguir
  regra melhor, mas não substitui um modelo maior/mais bem instruído.
- `Aegis4jEngine.chat()` anexa as `tools` registradas (o `criar_lembrete`) em
  **toda** chamada, não só nas rotas que sabem lidar com tool-calling — o
  `ChatCompletionRequestDto` do `aegis4j-server` v0.3.1 não tem campo `tools`
  ainda (planejado v0.4+, ver roadmap do aegis4j) e rejeitava a requisição
  com `UnrecognizedPropertyException` sempre que uma mensagem caía na rota do
  sidecar. Contornado no lado do Cronos com `ToolStrippingProvider`, que
  remove `tools` antes de repassar pro `aegis4j-sidecar` — a rota
  `ollama-openai` não precisa disso porque o endpoint OpenAI-compatible do
  próprio Ollama já tolera o campo.

### Não é uma limitação (mas parecia)

Editar uma tarefa depois de concluída deixaria o embedding indexado
desatualizado — mas o Cronos **não tem endpoint de edição de tarefa**
(só criar e mudar status), então esse cenário não existe hoje.

### Bugs encontrados e corrigidos no próprio aegis4j

Testando o routing pro `deepseek-r1:14b` (modelo "thinking"), o
`aegis4j-provider-ollama` quebrava com `UnrecognizedPropertyException` — o
Ollama manda um campo `thinking` (chain-of-thought) que o `OllamaMessage` do
aegis4j não esperava. Corrigido e testado no próprio aegis4j
([`v0.2.1`](https://github.com/AndreLucasrs/aegis4j/releases/tag/v0.2.1),
com teste de regressão) — o Cronos consome essa correção desde então (hoje
na [`v0.3.1`](https://github.com/AndreLucasrs/aegis4j/releases/tag/v0.3.1)).

Testando o usage tracking (acima), o `OllamaProvider.complete()` sempre
devolvia `Usage.UNKNOWN` — `OllamaChatResponseChunk` nem desserializava os
campos `prompt_eval_count`/`eval_count` que o próprio Ollama já manda na
resposta. Corrigido no aegis4j
([`v0.3.1`](https://github.com/AndreLucasrs/aegis4j/releases/tag/v0.3.1),
com teste de regressão) — antes do fix, o painel de uso do Cronos mostrava
`0 tokens` pra qualquer resposta roteada pro Ollama nativo (só o
`OpenAiCompatibleProvider`, usado no tool-calling, reportava tokens reais).

## Arquitetura

```
Frontend (web/, HTML/JS puro)
        │ fetch /api/*
        ▼
Javalin (CronosApp) ──► Postgres+pgvector (projects, tasks, task_embeddings)
        │
        ▼
CronosAssistant ──► Aegis4jEngine (embutido, não sidecar)
        │                 │
        │                 ├─ GuardChain (MaxLength + PII)
        │                 ├─ SkillRegistry (skills/*.md)
        │                 ├─ Retriever = PgVectorRetriever + OllamaEmbedder
        │                 ├─ ModelRouter (routing.yaml → 2 modelos Ollama locais)
        │                 └─ ProviderRegistry ──► Ollama local
        │
        └─ McpClient (stdio, direto — não passa pelo engine) ──► mcp-calendar/ (Node) ──► .ics
```

## Dependência do aegis4j

Consumido via [JitPack](https://jitpack.io/#AndreLucasrs/aegis4j), o mesmo
caminho documentado para qualquer terceiro:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
dependencies {
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-core:v0.3.1")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-guardrails-builtin:v0.3.1")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-skills:v0.3.1")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-provider-ollama:v0.3.1")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-provider-openai:v0.3.1")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-rag-jdbc-pgvector:v0.3.1")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-routing:v0.3.1")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-mcp:v0.3.1")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-observability-otel:v0.3.1")
}
```

## Fora de escopo (roadmap)

- Ação de escrita real (o MCP hoje só gera `.ics`, não altera nenhum
  calendário de verdade).
- Integração real com Google Calendar (precisa de OAuth).

## Licença

Apache License 2.0 — ver [LICENSE](LICENSE).
