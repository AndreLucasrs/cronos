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

./gradlew run
```

Abra `http://localhost:7070`. Crie um projeto, adicione tarefas com prazo,
converse com o assistente na lateral, marque uma tarefa como concluída
(fica indexada pro RAG), e crie um lembrete `.ics` numa tarefa com prazo.

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
| `CRONOS_REMINDERS_DIR` | `reminders` | Onde os `.ics` gerados ficam salvos/servidos |

**`CRONOS_PROVIDER_ID`/`CRONOS_OLLAMA_MODEL` não existem mais** — desde a
v0.2 o `ModelRouter` decide provider/model em toda mensagem (ver `routing.yaml`).

## O que está aceso (v0.2 — todos os pilares ativos)

| Pilar do aegis4j | Nesta v0.2 | Onde |
|---|---|---|
| **Guardrails** | ✅ | `MaxLengthGuard` bloqueia prompt gigante; `RegexPiiGuard` redige e-mail/telefone/cartão antes do modelo — `CronosAssistant` |
| **Skills (progressive disclosure)** | ✅ | `skills/estimativa-esforco.md` (heurística sem histórico) + `skills/tarefas-similares.md` (usa o RAG) |
| **RAG (pgvector)** | ✅ | `OllamaEmbedder` (chama `/api/embed` do Ollama — nenhum módulo do aegis4j ainda publica um `Embedder` concreto) + `PgVectorRetriever` sobre `task_embeddings`, indexada automaticamente quando uma tarefa vira `done` (`TaskIndexer`). Só é consultado quando a pergunta bate com a mesma keyword que ativa a skill `tarefas-similares` (`ConditionalRetriever`) — ver limitações |
| **Model routing** | ✅ | `routing.yaml` + `ModelRouter` — pergunta simples cai no `llama3.2:3b`, "estimativa"/"prazo"/"replanejar" cai no `deepseek-r1:14b`. Resposta do chat inclui `model` usado, pra ficar visível qual rota foi tomada. Rotear pra Anthropic/OpenAI de verdade é só trocar `provider`/`model` no YAML e ter a chave configurada |
| **Usage tracking (v0.3)** | ✅ | `InMemoryUsageTracker` plugado no `Aegis4jEngine`. Cada resposta do chat mostra os tokens daquela mensagem na legenda; o acumulado por modelo desde o start da JVM fica em `GET /api/assistant/usage` e no painel "Uso de tokens" ao lado do chat |
| **MCP client** | ✅ | Dois caminhos lado a lado pra tool `criar_lembrete` do servidor companheiro `mcp-calendar/`: o botão "Criar lembrete" (`POST /api/tasks/{id}/reminder`) segue **determinístico**, chamando `McpClient` (stdio) direto; e agora o assistente também cria lembrete **decidindo sozinho**, via o loop de tool-calling do `Aegis4jEngine` v0.3 (`CronosAssistant.reminderTool`/`executeReminderTool`), roteado por `routing.yaml` pro `OpenAiCompatibleProvider` (`ollama-openai` / `llama3.1:8b` local) — só esse provider manda `tools`/parseia `tool_calls` hoje. Ambos geram `.ics` em vez de Google Calendar real pra não depender de credencial OAuth |
| **Provider embutido (biblioteca, não sidecar)** | ✅ | `Aegis4jEngine` embutido direto no processo do Javalin |
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
