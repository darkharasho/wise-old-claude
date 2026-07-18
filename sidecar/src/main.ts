import { query, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";
import { SidecarServer } from "./server.js";
import { runChat } from "./agent.js";

const port = Number(process.env.WOC_PORT ?? "8137");
const token = process.env.WOC_TOKEN ?? "";
const model = process.env.WOC_MODEL ?? "claude-sonnet-4-6";

if (!token) {
  console.error("WOC_TOKEN must be set");
  process.exit(1);
}
if (!process.env.CLAUDE_CODE_OAUTH_TOKEN) {
  console.error("CLAUDE_CODE_OAUTH_TOKEN must be set");
  process.exit(1);
}

const server = new SidecarServer({
  port,
  token,
  onChat: (id, text, ctx) => {
    // Tools added in Task 12; empty MCP server for now.
    const mcpServer = createSdkMcpServer({ name: "gielinor", version: "0.1.0", tools: [] });
    void runChat({ queryFn: query as any, mcpServer, model }, id, text, ctx);
  },
});

await server.start();
console.log(`Wise Old Claude sidecar listening on 127.0.0.1:${server.port}`);
