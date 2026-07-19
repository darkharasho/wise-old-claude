import { describe, it, expect, beforeEach } from "vitest";
import { mkdtempSync } from "fs";
import { tmpdir } from "os";
import { join } from "path";
import { addGoal, completeGoal, listGoals, addJournal, recentJournal, goalsSummary } from "./memory.js";

// memory.ts resolves its path lazily from WOC_DATA_DIR, so a fresh temp dir per test isolates state.
beforeEach(() => {
  process.env.WOC_DATA_DIR = mkdtempSync(join(tmpdir(), "woc-mem-"));
});

describe("memory", () => {
  it("adds, dedupes, lists, and completes goals", () => {
    addGoal("fire cape");
    addGoal("fire cape");
    addGoal("99 fishing");
    expect(listGoals().map((g) => g.text)).toEqual(["fire cape", "99 fishing"]);
    completeGoal("fire");
    expect(listGoals().map((g) => g.text)).toEqual(["99 fishing"]);
  });

  it("persists goals to the file and summarizes them", () => {
    addGoal("quest cape");
    expect(goalsSummary()).toContain("quest cape");
  });

  it("appends and reads the journal in order", () => {
    addJournal("killed vorkath");
    addJournal("got a visage");
    expect(recentJournal().map((j) => j.text)).toEqual(["killed vorkath", "got a visage"]);
  });
});
