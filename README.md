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
| **MCP client** | ✅ | Botão "Criar lembrete" por tarefa → `McpClient` (stdio) chama a tool `criar_lembrete` do servidor companheiro `mcp-calendar/` → gera um `.ics` real, baixável. **Ação determinística (botão), não o modelo decidindo** — o `Aegis4jEngine` v0.2 ainda não tem um loop de tool-calling; e é `.ics` em vez de Google Calendar real pra não depender de credencial OAuth |
| **Provider embutido (biblioteca, não sidecar)** | ✅ | `Aegis4jEngine` embutido direto no processo do Javalin |

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

### Bug encontrado e corrigido no próprio aegis4j

Testando o routing pro `deepseek-r1:14b` (modelo "thinking"), o
`aegis4j-provider-ollama` quebrava com `UnrecognizedPropertyException` — o
Ollama manda um campo `thinking` (chain-of-thought) que o `OllamaMessage` do
aegis4j não esperava. Corrigido e testado no próprio aegis4j
([`v0.2.1`](https://github.com/AndreLucasrs/aegis4j/releases/tag/v0.2.1),
com teste de regressão) — o Cronos consome essa correção desde então (hoje
na [`v0.2.2`](https://github.com/AndreLucasrs/aegis4j/releases/tag/v0.2.2)).

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
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-core:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-guardrails-builtin:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-skills:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-provider-ollama:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-rag-jdbc-pgvector:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-routing:v0.2.2")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-mcp:v0.2.2")
}
```

## Fora de escopo (roadmap)

- Ação de escrita real (o MCP hoje só gera `.ics`, não altera nenhum
  calendário de verdade).
- Integração real com Google Calendar (precisa de OAuth).
- Tool-calling do modelo decidindo sozinho quando chamar uma tool MCP
  (gap do próprio `Aegis4jEngine` v0.2).

## Licença

Apache License 2.0 — ver [LICENSE](LICENSE).
