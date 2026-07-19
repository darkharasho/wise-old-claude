import { spawn } from "child_process";

// Speak Claude's answers aloud via a local TTS command. Off unless enabled:
// WOC_TTS=1 uses `spd-say`; WOC_TTS_CMD="espeak-ng" (or any command) overrides.
// The text is passed as the final argument (spawn without a shell — no injection).

export function ttsCommand(): string[] | null {
  if (process.env.WOC_TTS_CMD) return process.env.WOC_TTS_CMD.trim().split(/\s+/);
  if (process.env.WOC_TTS === "1") return ["spd-say"];
  return null;
}

// Strip markdown so the readout isn't full of asterisks, pipes, and URLs.
export function stripMarkdown(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/`([^`]*)`/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    .replace(/https?:\/\/\S+/g, "")
    .replace(/[*_#>|]/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

export function speak(text: string): void {
  const cmd = ttsCommand();
  if (!cmd) return;
  const clean = stripMarkdown(text).slice(0, 600);
  if (!clean) return;
  try {
    const child = spawn(cmd[0], [...cmd.slice(1), clean], { stdio: "ignore", detached: true });
    child.on("error", () => {});
    child.unref();
  } catch {
    // best-effort
  }
}
