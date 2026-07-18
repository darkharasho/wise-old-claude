export type Hello = { type: "hello"; token: string };
export type Chat = { type: "chat"; id: string; text: string };
export type ToolResponse = {
  type: "tool_response";
  requestId: string;
  data?: Record<string, unknown>;
  error?: string;
};
export type PluginToSidecar = Hello | Chat | ToolResponse;

export type HelloOk = { type: "hello_ok" };
export type HelloReject = { type: "hello_reject"; reason: string };
export type AssistantDelta = { type: "assistant_delta"; id: string; text: string };
export type AssistantDone = { type: "assistant_done"; id: string };
export type ToolRequest = {
  type: "tool_request";
  requestId: string;
  tool: string;
  args: Record<string, unknown>;
};
export type ErrorMsg = { type: "error"; id: string | null; message: string };
export type SidecarToPlugin =
  | HelloOk
  | HelloReject
  | AssistantDelta
  | AssistantDone
  | ToolRequest
  | ErrorMsg;

const INBOUND = new Set(["hello", "chat", "tool_response"]);

export function parseMessage(raw: string): PluginToSidecar {
  const obj = JSON.parse(raw) as { type?: string };
  if (!obj || typeof obj.type !== "string" || !INBOUND.has(obj.type)) {
    throw new Error(`unknown inbound message type: ${obj?.type}`);
  }
  return obj as PluginToSidecar;
}

export function serialize(msg: SidecarToPlugin): string {
  return JSON.stringify(msg);
}
