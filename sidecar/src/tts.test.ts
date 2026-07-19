import { describe, it, expect, afterEach } from "vitest";
import { stripMarkdown, ttsCommand } from "./tts.js";
import { alertsEnabled } from "./alerts.js";

afterEach(() => {
  delete process.env.WOC_TTS;
  delete process.env.WOC_TTS_CMD;
  delete process.env.WOC_ALERT_WEBHOOK;
  delete process.env.WOC_TELEGRAM_BOT_TOKEN;
  delete process.env.WOC_TELEGRAM_CHAT_ID;
});

describe("tts", () => {
  it("strips markdown for speech", () => {
    expect(stripMarkdown("**Hi** `code` [link](http://x) | table")).toBe("Hi code link table");
  });

  it("resolves the TTS command from env", () => {
    expect(ttsCommand()).toBeNull();
    process.env.WOC_TTS = "1";
    expect(ttsCommand()).toEqual(["spd-say"]);
    process.env.WOC_TTS_CMD = "espeak-ng -s 160";
    expect(ttsCommand()).toEqual(["espeak-ng", "-s", "160"]);
  });
});

describe("alerts", () => {
  it("is disabled until env is set", () => {
    expect(alertsEnabled()).toBe(false);
    process.env.WOC_ALERT_WEBHOOK = "https://example/webhook";
    expect(alertsEnabled()).toBe(true);
  });
});
