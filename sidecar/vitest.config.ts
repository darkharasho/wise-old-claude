import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    pool: "forks",
    // maxForks: 2 is the machine memory cap (see CLAUDE.md). minForks: 1 is
    // required — vitest 2.x throws RangeError when maxForks is below its
    // computed default minForks; do not remove it.
    poolOptions: { forks: { maxForks: 2, minForks: 1 } },
  },
});
