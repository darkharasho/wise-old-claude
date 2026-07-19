import { readFileSync, writeFileSync, mkdirSync } from "fs";
import { homedir } from "os";
import { dirname, join } from "path";

// File-backed player memory that survives reconnects and sidecar restarts, unlike the
// per-connection conversation session. Path resolved lazily so tests can set WOC_DATA_DIR.

export type Goal = { text: string; created: string; done?: boolean };
export type JournalEntry = { text: string; ts: string };
type Memory = { goals: Goal[]; journal: JournalEntry[] };

function file(): string {
  const dir = process.env.WOC_DATA_DIR ?? join(homedir(), ".wise-old-claude");
  return join(dir, "memory.json");
}

function load(): Memory {
  try {
    const m = JSON.parse(readFileSync(file(), "utf8")) as Partial<Memory>;
    return { goals: m.goals ?? [], journal: m.journal ?? [] };
  } catch {
    return { goals: [], journal: [] };
  }
}

function save(m: Memory): void {
  try {
    mkdirSync(dirname(file()), { recursive: true });
    writeFileSync(file(), JSON.stringify(m, null, 2));
  } catch {
    // best-effort; memory is a convenience, not critical path
  }
}

function activeGoals(m: Memory): Goal[] {
  return m.goals.filter((g) => !g.done);
}

export function addGoal(text: string): Goal[] {
  const m = load();
  if (!m.goals.some((g) => !g.done && g.text.toLowerCase() === text.toLowerCase())) {
    m.goals.push({ text, created: new Date().toISOString() });
    save(m);
  }
  return activeGoals(m);
}

export function completeGoal(text: string): Goal[] {
  const m = load();
  const q = text.toLowerCase();
  for (const g of m.goals) {
    if (!g.done && (g.text.toLowerCase() === q || g.text.toLowerCase().includes(q))) g.done = true;
  }
  save(m);
  return activeGoals(m);
}

export function listGoals(): Goal[] {
  return activeGoals(load());
}

export function addJournal(text: string): void {
  const m = load();
  m.journal.push({ text, ts: new Date().toISOString() });
  if (m.journal.length > 200) m.journal = m.journal.slice(-200);
  save(m);
}

export function recentJournal(n = 20): JournalEntry[] {
  return load().journal.slice(-n);
}

// Compact one-line summary injected into the chat system prompt so goals persist.
export function goalsSummary(): string {
  const goals = listGoals();
  if (goals.length === 0) return "";
  return "The player's current goals (from memory): " + goals.map((g) => g.text).join("; ") + ".";
}
