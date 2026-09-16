import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { buildIcs } from "./ics.js";

const server = new McpServer({ name: "cronos-mcp-calendar", version: "0.1.0" });

server.registerTool(
  "criar_lembrete",
  {
    description:
      "Gera um arquivo .ics (padrão de calendário, importável em qualquer app) para uma tarefa com prazo. " +
      "Não integra com nenhum serviço externo (sem Google Calendar) — devolve o conteúdo .ics pronto, " +
      "quem chama decide onde salvar/servir.",
    inputSchema: {
      title: z.string().describe("Título da tarefa / do evento"),
      dueDate: z.string().describe("Data no formato YYYY-MM-DD"),
      description: z.string().optional().describe("Descrição opcional da tarefa"),
    },
  },
  async ({ title, dueDate, description }) => {
    const { uid, ics } = buildIcs({ title, dueDate, description });
    return {
      content: [
        {
          type: "resource",
          resource: {
            uri: `reminder://${uid}`,
            mimeType: "text/calendar",
            text: ics,
          },
        },
      ],
    };
  }
);

const transport = new StdioServerTransport();
await server.connect(transport);
