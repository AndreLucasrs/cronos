CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL DEFAULT 'todo',
    due_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE task_dependencies (
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    depends_on_task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, depends_on_task_id)
);

-- RAG: embeddings de tarefas concluídas, indexadas quando o status vira
-- "done" (ver TaskIndexer). Schema e nomes de coluna seguem exatamente o
-- que PgVectorRetrieverConfig.defaults(table) do aegis4j espera.
-- 768 = dimensão do modelo de embedding "nomic-embed-text" (Ollama).
CREATE TABLE task_embeddings (
    id text PRIMARY KEY,
    content text NOT NULL,
    embedding vector(768)
);

-- RAG: chunks de "briefing" de projeto, ingeridos via o pipeline de
-- documentos do aegis4j (DocumentLoader/Chunker/PgVectorIngester) —
-- diferente de task_embeddings, que é indexado linha a linha por
-- TaskIndexer sem passar por esse pipeline.
CREATE TABLE project_brief_chunks (
    id text PRIMARY KEY,
    content text NOT NULL,
    embedding vector(768)
);
