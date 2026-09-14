# Cronos

Gestão de cronograma de projetos — e vitrine funcional do
[aegis4j](https://github.com/AndreLucasrs/aegis4j), uma lib JVM de
guardrails, skills, RAG, roteamento de modelo e cliente MCP para colocar na
frente de LLMs.

O domínio (cronograma: projetos, tarefas, prazos, dependências) não é o
ponto — o ponto é mostrar, com um app real rodando, como cada pilar do
aegis4j se encaixa no dia a dia sem parecer forçado.

## Como rodar

Pré-requisitos: JDK 17+, Docker, e um [Ollama](https://ollama.com) local
rodando (o assistente usa o `aegis4j-provider-ollama`, sem custo e sem
chave de API).

```bash
docker compose up -d
ollama pull llama3.1:8b

./gradlew run
```

Abra `http://localhost:7070`. Crie um projeto, adicione tarefas, e converse
com o assistente na lateral (ex: "quanto tempo leva a tarefa X?").

Variáveis de ambiente (todas opcionais, com default de dev local):

| Variável | Default | Uso |
|---|---|---|
| `CRONOS_PORT` | `7070` | Porta HTTP |
| `CRONOS_DB_URL` / `CRONOS_DB_USER` / `CRONOS_DB_PASSWORD` | aponta pro `docker-compose.yml` local | Postgres |
| `CRONOS_PROVIDER_ID` | `ollama` | Provider do aegis4j usado pelo assistente |
| `CRONOS_OLLAMA_MODEL` | `llama3.1:8b` | Modelo Ollama usado no chat |
| `AEGIS4J_OLLAMA_BASE_URL` | `http://localhost:11434` | Endereço do Ollama (env var do próprio módulo aegis4j) |
| `CRONOS_MAX_INPUT_CHARS` | `4000` | Limite do `MaxLengthGuard` |
| `CRONOS_SKILLS_DIR` | `skills` | Diretório de skills markdown |

## O que está aceso nesta v0.1 (e o que falta)

| Pilar do aegis4j | Nesta v0.1 | Onde |
|---|---|---|
| **Guardrails** | ✅ Ativo | `MaxLengthGuard` bloqueia prompt gigante; `RegexPiiGuard` redige e-mail/telefone/cartão antes do modelo (substitui por placeholder, não bloqueia) — `CronosAssistant` |
| **Skills (progressive disclosure)** | ✅ Ativo | `skills/estimativa-esforco.md` — descriptor sempre no prompt, corpo só carrega quando o usuário pergunta sobre prazo/estimativa |
| **Provider embutido (biblioteca, não sidecar)** | ✅ Ativo | `Aegis4jEngine` embutido direto no processo do Javalin, provider Ollama local |
| **RAG (pgvector)** | ⏳ v0.2 | Precisa de um `Embedder` concreto — o aegis4j ainda não publica um (`OllamaEmbedder` é gap documentado do próprio aegis4j v0.2) |
| **Model routing** | ⏳ v0.2 | Só faz sentido com 2+ providers reais configurados (ex: Ollama + Anthropic) |
| **MCP client** | ⏳ v0.2 | Precisa de um servidor MCP externo real (ex: calendário) pra não ser um mock vazio |

## Arquitetura

```
Frontend (web/, HTML/JS puro)
        │  fetch /api/*
        ▼
Javalin (CronosApp) ──► Postgres (projetos, tarefas, dependências)
        │
        ▼
CronosAssistant ──► Aegis4jEngine (embutido, não sidecar)
        │                 │
        │                 ├─ GuardChain (MaxLength + PII)
        │                 ├─ SkillRegistry (skills/*.md)
        │                 └─ ProviderRegistry ──► Ollama local
        ▼
   /api/assistant/chat
```

## Dependência do aegis4j

Consumido via [JitPack](https://jitpack.io/#AndreLucasrs/aegis4j), o mesmo
caminho documentado para qualquer terceiro:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
dependencies {
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-core:v0.2.0")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-guardrails-builtin:v0.2.0")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-skills:v0.2.0")
    implementation("com.github.AndreLucasrs.aegis4j:aegis4j-provider-ollama:v0.2.0")
}
```

## Licença

Apache License 2.0 — ver [LICENSE](LICENSE).
