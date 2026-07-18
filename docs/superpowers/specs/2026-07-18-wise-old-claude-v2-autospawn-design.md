# Wise Old Claude v2 — Auto-spawn the Sidecar (Sub-project B)

**Status:** Draft for review
**Date:** 2026-07-18
**Builds on:** v1 + v2 sub-project A (both merged to `main`).

## Summary

Let the RuneLite plugin launch the Node sidecar as a child process on startup and
kill it on shutdown, so the user no longer starts it by hand in a terminal.
Opt-in (default off) so existing manual-start users are unaffected. The plugin
does **not** hold or pass the Claude OAuth credential — the spawned sidecar
resolves it itself from the user's environment / `claude` login, preserving the
v1 boundary (no raw token in RuneLite config). An optional env-file path gives an
escape hatch when GUI-launched RuneLite doesn't inherit the needed env var.

## Goals

- A config toggle that, when on, spawns the sidecar on plugin startup and stops
  it on shutdown.
- **No OAuth token in the plugin.** The plugin passes the child only the
  non-secret run vars it already holds (`WOC_TOKEN` handshake secret, `WOC_PORT`).
  It does **not** pass `WOC_MODEL` (the plugin has no model config; the sidecar's
  own default or an env-file entry provides it). The child inherits RuneLite's
  environment for the credential.
- An optional env-file path the plugin merges into the child's environment (the
  escape hatch for the credential when the inherited env lacks it).
- **Don't spawn a duplicate:** if a sidecar is already reachable on the configured
  host:port, attach to it instead of spawning.
- Child stdout/stderr surfaced to the RuneLite log so failures are diagnosable.
- Clean lifecycle: the child (and its `claude` grandchild) is killed on shutdown.

## Non-goals

- Bundling the sidecar as a single executable (pkg/esbuild). The prerequisite is
  Node installed + a one-time `npm run build`. (Possible future work.)
- Auto-installing Node or running `npm install` for the user.
- Auto-restart/health-supervision beyond the existing reconnect loop (a crashed
  child is surfaced as "Disconnected"; restarting it is a manual re-enable). A
  supervised auto-restart is possible later; not in this cut.
- Changing the manual-start path — it remains fully supported (toggle off).

## Architecture

Adds one plugin-side component; the sidecar is unchanged. When `manageSidecar`
is on:

```
WiseOldClaudePlugin.startUp
   │
   ▼
SidecarProcess.start()
   │  probe host:port
   ├─ reachable ──────────────▶ do NOT spawn (attach to existing)
   └─ not reachable ─▶ ProcessBuilder(nodePath, "dist/main.js")
                          cwd = sidecarDir
                          env = RuneLite env
                              + WOC_TOKEN / WOC_PORT (from config)
                              + sidecarEnvFile entries (if set)
                          stdout+stderr → RuneLite log
   │
   ▼
(existing) ReconnectingConnection.start()  → connects to the sidecar
```

The credential is never handled by the plugin: the child process either inherits
`CLAUDE_CODE_OAUTH_TOKEN` from RuneLite's environment, or reads the stored
`claude` login the Agent SDK uses, or picks it up from `sidecarEnvFile` if the
user points at one.

## Components

### `SidecarProcess` (new, `com.wiseoldclaude.SidecarProcess`)

- Constructor takes the values it needs (nodePath, sidecarDir, host, port, token,
  model, envFilePath) — or a small config-snapshot object — plus two injected
  seams for testability: a **port probe** (`BiPredicate<String,Integer>` or a
  `PortProbe` functional interface returning "is something listening?") and a
  **process launcher** (a functional interface wrapping `ProcessBuilder.start()`),
  so tests exercise the decision + env assembly without launching a real process.
- `void start()`:
  1. If the port probe reports the sidecar is already reachable, log "attaching to
     existing sidecar" and return without spawning.
  2. Otherwise assemble the command (`nodePath dist/main.js`), working dir
     (`sidecarDir`), and environment (inherited + `WOC_TOKEN`/`WOC_PORT`
     + parsed `sidecarEnvFile` lines), launch via the injected
     launcher, and retain the `Process` handle. Pipe the merged output stream to
     the RuneLite log on a daemon reader thread.
  3. On launch failure (e.g. node not found, bad dir), log a clear error and
     return — the plugin still attempts to connect (in case one is already up).
- `void stop()`: if a child was spawned, `destroyForcibly()` it; best-effort kill
  of the process tree (`Process.descendants()` where available, then the root).
  A child we merely *attached to* (didn't spawn) is left running.
- Static/pure helpers, unit-testable in isolation:
  - `Map<String,String> buildChildEnv(base, token, port, model, envFileLines)` —
    merges the run vars and env-file entries over the inherited base.
  - `List<String> parseEnvFile(List<String> lines)` → `KEY=VALUE` pairs, ignoring
    blanks and `#` comments.

### `WiseOldClaudeConfig` additions

| Key | Type | Default | Notes |
|---|---|---|---|
| `manageSidecar` | boolean | `false` | Master opt-in toggle |
| `nodePath` | String | `"node"` | Resolved via PATH unless absolute |
| `sidecarDir` | String | `""` | Path to the `sidecar/` folder (contains `dist/main.js`) |
| `sidecarEnvFile` | String | `""` | Optional `KEY=VALUE` file merged into the child env |

### `WiseOldClaudePlugin` wiring

- `startUp`: if `config.manageSidecar()`, construct a `SidecarProcess` (with a real
  port probe and a real `ProcessBuilder`-backed launcher) and call `start()`
  **before** the existing `reconnect.start()`.
- `shutDown`: call `sidecarProcess.stop()` (null-guarded) alongside the existing
  cleanup.

## Data flow

Startup with `manageSidecar` on and nothing already listening: plugin assembles
the command + env → launches `node dist/main.js` in `sidecarDir` → the child reads
its OAuth credential from the inherited env / stored login → binds `127.0.0.1:port`
→ the plugin's reconnect loop connects and the panel shows "Connected". Child
logs stream into RuneLite's log.

## Error handling

- **Node missing / bad `sidecarDir`:** launcher throws → logged clearly; plugin
  proceeds to attempt a connection anyway.
- **Port already in use (something listening):** treated as "already running" →
  attach, don't spawn.
- **Child exits early (e.g. missing credential):** its stderr is in the RuneLite
  log; the reconnect loop shows "Disconnected". User fixes the env / env-file and
  re-enables the plugin.
- **`sidecarEnvFile` unreadable:** logged; the child is still launched with the
  inherited env (the file is a best-effort augmentation).
- **`manageSidecar` off:** `SidecarProcess` is never constructed; behavior is
  exactly v1/A (manual start).

## Testing strategy

- **`SidecarProcess` (JUnit + Mockito / injected seams):**
  - `parseEnvFile` — parses `KEY=VALUE`, skips blanks/`#`, tolerates `=` in values.
  - `buildChildEnv` — WOC_TOKEN/PORT/MODEL present; env-file entries merged;
    inherited base preserved; env-file overrides win (or documented precedence).
  - `start()` with an injected probe reporting "reachable" → the launcher is
    **not** invoked (no spawn).
  - `start()` with probe "not reachable" → the launcher **is** invoked with the
    expected command (`nodePath`, `dist/main.js`), cwd, and env.
  - `start()` when the launcher throws → no exception escapes; error path taken.
  - `stop()` after a spawn → destroys the retained process (fake process);
    `stop()` after an attach (no spawn) → does nothing.
- The real end-to-end spawn (actually launching Node) is verified manually.

## Config defaults summary

`manageSidecar=false`, `nodePath="node"`, `sidecarDir=""`, `sidecarEnvFile=""`.

## Milestones (suggested build order)

1. `SidecarProcess` pure helpers (`parseEnvFile`, `buildChildEnv`) — TDD.
2. `SidecarProcess.start()`/`stop()` with injected probe + launcher seams — TDD
   (spawn-vs-attach decision, command/env assembly, stop behavior).
3. `WiseOldClaudeConfig` keys.
4. `WiseOldClaudePlugin` wiring (construct with real probe/launcher; start before
   reconnect; stop on shutdown) — compile + full build.
5. README: prerequisites (Node + `npm run build`), the toggle, and the env-file
   escape hatch.

## Open questions / assumptions to confirm

1. `manageSidecar` defaults **off** (opt-in) — good, or default on now that it's a
   convenience feature?
2. Env-file precedence: entries from `sidecarEnvFile` **override** inherited env
   (so the file is authoritative) — acceptable, or should inherited env win?
