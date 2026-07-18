import { describe, it, expect } from "vitest";
import { runChat } from "./agent.js";

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

  it("emits error when query throws", async () => {
    const { events, ctx } = recordingCtx();
    const throwing = () => { throw new Error("boom"); };
    await runChat({ queryFn: throwing as any, mcpServer: {} as any, model: "m" }, "1", "hi", ctx);
    expect(events).toEqual(["error:boom"]);
  });
});
