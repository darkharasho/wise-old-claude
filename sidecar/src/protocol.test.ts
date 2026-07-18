import { describe, it, expect } from "vitest";
import { parseMessage, serialize } from "./protocol.js";

describe("protocol", () => {
  it("parses a chat message", () => {
    const msg = parseMessage(JSON.stringify({ type: "chat", id: "1", text: "hi" }));
    expect(msg).toEqual({ type: "chat", id: "1", text: "hi" });
  });

  it("parses a tool_response with data", () => {
    const raw = JSON.stringify({ type: "tool_response", requestId: "r1", data: { hp: 99 } });
    expect(parseMessage(raw)).toEqual({ type: "tool_response", requestId: "r1", data: { hp: 99 } });
  });

  it("rejects an unknown type", () => {
    expect(() => parseMessage(JSON.stringify({ type: "nope" }))).toThrow();
  });

  it("serializes an assistant_delta", () => {
    const s = serialize({ type: "assistant_delta", id: "1", text: "he" });
    expect(JSON.parse(s)).toEqual({ type: "assistant_delta", id: "1", text: "he" });
  });

  it("parses an event message", () => {
    const raw = JSON.stringify({ type: "event", id: "e1", kind: "level_up", detail: { skill: "Attack", level: 70 } });
    expect(parseMessage(raw)).toEqual({ type: "event", id: "e1", kind: "level_up", detail: { skill: "Attack", level: 70 } });
  });
});
