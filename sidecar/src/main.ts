import { query, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";
import { SidecarServer } from "./server.js";
import { runChat, runProactive } from "./agent.js";
import { buildTools } from "./tools.js";

const port = Number(process.env.WOC_PORT ?? "8137");
const token = process.env.WOC_TOKEN ?? "";
const model = process.env.WOC_MODEL ?? "claude-sonnet-4-6";

// The server binds to 127.0.0.1 only, so an empty token means "accept any local
// client" — which matches the plugin's empty-by-default token. That's a fine
// zero-config personal setup; set WOC_TOKEN (and the matching plugin config field)
// if you want to require a shared secret on the handshake.
if (!token) {
  console.warn(
    "WOC_TOKEN is not set: accepting any localhost client (empty-token handshake). " +
      "Set WOC_TOKEN + the plugin's token field to require a shared secret.",
  );
}
// The Agent SDK resolves credentials from the environment or the local `claude`
// login (~/.claude). CLAUDE_CODE_OAUTH_TOKEN is one way to supply them, not the
// only way — so warn instead of refusing to start. If no credentials resolve, the
// failure surfaces per-chat as an error bubble rather than a dead sidecar.
if (!process.env.CLAUDE_CODE_OAUTH_TOKEN) {
  console.warn(
    "CLAUDE_CODE_OAUTH_TOKEN is not set: the Agent SDK will fall back to your " +
      "local `claude` login (~/.claude). Run `claude setup-token` to set an explicit token.",
  );
}

const server = new SidecarServer({
  port,
  token,
  onChat: (id, text, ctx) => {
    const mcpServer = createSdkMcpServer({
      name: "gielinor",
      version: "0.1.0",
      tools: buildTools(ctx.bridge),
    });
    void runChat({ queryFn: query as any, mcpServer, model }, id, text, ctx);
  },
  onEvent: (id, kind, detail, ctx) => {
    const mcpServer = createSdkMcpServer({
      name: "gielinor",
      version: "0.1.0",
      tools: buildTools(ctx.bridge),
    });
    void runProactive({ queryFn: query as any, mcpServer, model }, id, kind, detail, ctx);
  },
});

await server.start();
console.log(`Wise Old Claude sidecar listening on 127.0.0.1:${server.port}`);
