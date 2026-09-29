import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { createMcpExpressApp } from "@modelcontextprotocol/sdk/server/express.js";
import type { Request, Response } from "express";
import { z } from "zod";
import { Pool } from "pg";
import { parseJdbcUrl } from "./db.js";
import { tokenize } from "./tokenize.js";

const pool = new Pool(
  parseJdbcUrl(
    process.env.CRONOS_DB_URL ?? "jdbc:postgresql://localhost:5433/cronos",
    process.env.CRONOS_DB_USER ?? "cronos",
    process.env.CRONOS_DB_PASSWORD ?? "cronos"
  )
);

/**
 * Deliberately lexical (SQL ILIKE), not semantic — the other RAG path
 * (PgVectorRetriever, "tarefas-similares") already covers "find something
 * similar in meaning". This one is for "find the exact task I'm thinking
 * of", where substring matching on title/description is the right tool.
 */
function getServer(): McpServer {
  const server = new McpServer({ name: "cronos-mcp-search", version: "0.1.0" });

  server.registerTool(
    "buscar_tarefas",
    {
      description:
        "Busca LEXICAL (ILIKE, não semântica) por tarefas cujo título ou descrição contenham o termo buscado. " +
        "Não é RAG por similaridade — é correspondência exata de texto. Retorna vazio se nada bater.",
      inputSchema: {
        query: z.string().describe("Termo a buscar em título/descrição da tarefa"),
        top_k: z.number().int().positive().optional().describe("Número máximo de tarefas retornadas"),
      },
    },
    async ({ query, top_k }) => {
      const limit = top_k ?? 5;
      const terms = tokenize(query);
      const patterns = terms.map((term) => `%${term}%`);
      console.log(`[mcp-search] buscar_tarefas query=${JSON.stringify(query)} terms=${JSON.stringify(terms)} top_k=${limit}`);
      const result = await pool.query(
        `SELECT id, title, description FROM tasks
         WHERE title ILIKE ANY($1::text[]) OR description ILIKE ANY($1::text[])
         ORDER BY created_at DESC
         LIMIT $2`,
        [patterns, limit]
      );
      console.log(`[mcp-search] found ${result.rowCount} task(s) for query=${JSON.stringify(query)}`);
      return {
        content: result.rows.map((row) => ({
          type: "resource" as const,
          resource: {
            uri: `task://${row.id}`,
            mimeType: "text/plain",
            text: row.description ? `${row.title}\n${row.description}` : String(row.title),
          },
        })),
      };
    }
  );

  return server;
}

const app = createMcpExpressApp();

// Streamable HTTP, POST-only — matches what HttpSseMcpTransport (Java side)
// sends: no GET/DELETE session lifecycle since this runs stateless
// (sessionIdGenerator: undefined), one MCP server instance per request.
app.post("/mcp", async (req: Request, res: Response) => {
  const server = getServer();
  try {
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined });
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
    res.on("close", () => {
      transport.close();
      server.close();
    });
  } catch (error) {
    console.error("[mcp-search] Error handling MCP request:", error);
    if (!res.headersSent) {
      res.status(500).json({
        jsonrpc: "2.0",
        error: { code: -32603, message: "Internal server error" },
        id: null,
      });
    }
  }
});

app.get("/mcp", (_req: Request, res: Response) => {
  res.writeHead(405).end(
    JSON.stringify({
      jsonrpc: "2.0",
      error: { code: -32000, message: "Method not allowed." },
      id: null,
    })
  );
});

app.delete("/mcp", (_req: Request, res: Response) => {
  res.writeHead(405).end(
    JSON.stringify({
      jsonrpc: "2.0",
      error: { code: -32000, message: "Method not allowed." },
      id: null,
    })
  );
});

const PORT = Number(process.env.MCP_SEARCH_PORT ?? 3939);
app.listen(PORT, () => {
  console.log(`[mcp-search] listening on port ${PORT}`);
});

process.on("SIGINT", () => process.exit(0));
process.on("SIGTERM", () => process.exit(0));
