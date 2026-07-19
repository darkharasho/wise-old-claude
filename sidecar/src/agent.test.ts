import { describe, it, expect } from "vitest";
import { runChat, proactivePrompt, runProactive } from "./agent.js";

// Fake query() yielding SDK-shaped streaming messages.
async function* fakeQuery() {
  yield { type: "assistant", message: { content: [{ type: "text", text: "Hel" }] } };
  yield { type: "assistant", message: { content: [{ type: "text", text: "lo" }] } };
  yield { type: "result", subtype: "success" };
}

function recordingCtx() {
  const events: string[] = [];
  return {
    events,
    ctx: {
      sendDelta: (_id: string, t: string) => events.push("delta:" + t),
      sendThinking: (_id: string, t: string) => events.push("thinking:" + t),
      sendDone: () => events.push("done"),
      sendError: (_id: string | null, m: string) => events.push("error:" + m),
      bridge: {} as any,
    },
  };
}

describe("runChat", () => {
  it("streams text deltas then done", async () => {
    const { events, ctx } = recordingCtx();
    await runChat({ queryFn: fakeQuery as any, mcpServer: {} as any, model: "m" }, "1", "hi", ctx);
    expect(events).toEqual(["delta:Hel", "delta:lo", "done"]);
  });

  it("emits thinking blocks before text", async () => {
    async function* withThinking() {
      yield { type: "assistant", message: { content: [{ type: "thinking", thinking: "hmm" }] } };
      yield { type: "assistant", message: { content: [{ type: "text", text: "answer" }] } };
      yield { type: "result", subtype: "success" };
    }
    const { events, ctx } = recordingCtx();
    await runChat({ queryFn: withThinking as any, mcpServer: {} as any, model: "m" }, "1", "hi", ctx);
    expect(events).toEqual(["thinking:hmm", "delta:answer", "done"]);
  });

  it("resumes the prior chat session and records the new session id", async () => {
    let seenOptions: any;
    async function* q(args: any) {
      seenOptions = args.options;
      yield { type: "system", session_id: "sess-123" };
      yield { type: "assistant", message: { content: [{ type: "text", text: "ok" }] } };
      yield { type: "result", subtype: "success", session_id: "sess-123" };
    }
    let recorded: string | undefined;
    const ctx: any = {
      sendDelta: () => {}, sendThinking: () => {}, sendDone: () => {}, sendError: () => {},
      bridge: {}, resumeSessionId: "prev-999", onSessionId: (s: string) => { recorded = s; },
    };
    await runChat({ queryFn: q as any, mcpServer: {} as any, model: "m" }, "1", "hi", ctx);
    expect(seenOptions.resume).toBe("prev-999");
    expect(recorded).toBe("sess-123");
  });

  it("emits error when query throws", async () => {
    const { events, ctx } = recordingCtx();
    const throwing = () => { throw new Error("boom"); };
    await runChat({ queryFn: throwing as any, mcpServer: {} as any, model: "m" }, "1", "hi", ctx);
    expect(events).toEqual(["error:boom"]);
  });
});

describe("proactive", () => {
  async function* fakeQuery() {
    yield { type: "assistant", message: { content: [{ type: "text", text: "GZ!" }] } };
    yield { type: "result", subtype: "success" };
  }
  function recordingCtx() {
    const events: string[] = [];
    return { events, ctx: {
      sendDelta: (_id: string, t: string) => events.push("delta:" + t),
      sendThinking: (_id: string, t: string) => events.push("thinking:" + t),
      sendDone: () => events.push("done"),
      sendError: (_id: string | null, m: string) => events.push("error:" + m),
      bridge: {} as any,
    } };
  }

  it("builds a level_up prompt mentioning the skill and level", () => {
    const p = proactivePrompt("level_up", { skill: "Attack", level: 70 });
    expect(p).toContain("70");
    expect(p).toContain("Attack");
  });

  it("streams a proactive comment then done", async () => {
    const { events, ctx } = recordingCtx();
    await runProactive({ queryFn: fakeQuery as any, mcpServer: {} as any, model: "m" },
      "e1", "death", {}, ctx);
    expect(events).toEqual(["delta:GZ!", "done"]);
  });
});
