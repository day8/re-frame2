# re-frame2-pair

> Pair-program with a live, running re-frame2 application. Attach via nREPL, inspect any frame's `app-db`, dispatch events, hot-swap handlers, walk epochs, and read the trace stream — through re-frame2's own Tool-Pair contract, no `re-frame-10x` dependency.

## What it does

The `re-frame2-pair` skill is the AI pair-programming companion for a running re-frame2 app. The app is up behind `shadow-cljs watch`; the skill attaches to its nREPL session in `:cljs` mode and operates on the live runtime — not just static source files.

Three primitives carry the skill's agency, all part of re-frame2's [Tool-Pair Spec](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md):

1. **The REPL** — ClojureScript forms evaluated against the real app, usually through helpers in the preloaded `re-frame2-pair.runtime` namespace.
2. **The trace stream** — `(rf/register-listener! :trace id listener)` for live trace events (the verb is stream-parameterised across the two raw dev streams, `:trace` and `:epoch` — a closed vocabulary with no bare two-argument default); `(rf/trace-buffer frame-id)` for the retain-N ring of recent events (frame-id is the first positional arg, `(rf/trace-buffer frame-id opts)` filters, and the fn exists on both platforms).
3. **The epoch history** — `(rf/epoch-history frame-id)` returns the per-frame ring of `:rf/epoch-record` values, each carrying `:db-before`, `:db-after`, `:trace-events`, and the assembled `:sub-runs` / `:renders` / `:effects` projections.

The skill is **multi-frame aware** (Spec 002 — most apps run with one frame, larger apps run several). It registers exactly **one** trace listener (`:re-frame2-pair`) and one epoch listener (`:re-frame2-pair-epoch`) so it coexists with other tools (e.g. `re-frame-10x` v2) on the same bus. Mutating ops refuse with `:ambiguous-frame` when the operating frame is unclear.

The cardinal rule: **REPL changes are ephemeral, source edits are permanent.** After any source edit, the skill waits on the hot-reload protocol before dispatching or tracing — otherwise you interact with the pre-reload code.

## When to reach for it

Load this skill when the user mentions a **running** re-frame2 app, or any of: `re-frame2`, `app-db`, `dispatch`, `subscribe`, `reg-event`, `reg-sub`, `reg-fx`, `reg-machine`, frame, epoch, interceptor, sub-cache, trace-buffer, `register-listener!`, `restore-epoch`, re-com, shadow-cljs — *and the question is about the live runtime*, not about writing new code.

Do **not** use this skill for:

- Writing new application code → use [re-frame2](re-frame2.md).
- Greenfield setup → use [re-frame2-setup](re-frame2-setup.md).
- Migrating a v1 project → use [re-frame-migration](re-frame-migration.md).

## Kickoff

Three steps, the first two one-time setup on the app you are pairing with:

1. **Build the MCP server from a clone.** It is not published to npm yet:
   `cd tools/re-frame2-pair-mcp && npm install && npm run build`, then point your
   agent host's `mcpServers` entry at the compiled `out/server.js`. (Once
   published: `npm install -g @day8/re-frame2-pair-mcp`.)
2. **Add the preload to the app.** The `re-frame2-pair.runtime` namespace ships in
   the skill's own `preload/` directory — the MCP server does *not* carry it — so
   put that directory on the app's build **classpath** and add
   `re-frame2-pair.runtime` to its `:devtools :preloads`. The `:preloads` half is
   always a `shadow-cljs.edn` line; the classpath half goes wherever the app's
   classpath is actually owned — an activated alias's `:extra-paths` in `deps.edn`
   for a `:deps` app, `:source-paths` in `project.clj` for a `:lein` app, and
   `:source-paths` in `shadow-cljs.edn` only for a standalone shadow app. (Under
   `:deps` and `:lein`, shadow *ignores* a `shadow-cljs.edn` `:source-paths` key
   and warns that it did, so putting it there yields a preload that never loads.)
   No package install, dev builds only. **The preload is required; there is no
   per-session inject fallback.** See [`SKILL.md` §Setup](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/SKILL.md)
   for the branch and the snippets.
3. **Run `discover-app`**, via the `re-frame2-pair-mcp` server — the only
   skill-facing transport (see [Transport](#transport) below). The bash/babashka
   `scripts/` shims that once fronted these ops have been removed; the live
   connect/dispatch/trace/hot-reload coverage now drives the MCP server over
   stdio from `tools/re-frame2-pair-mcp/test/live-e2e-fixture.cjs`.

```
discover-app
```

This locates the shadow-cljs nREPL port, connects, switches to `:cljs` mode for the running build, verifies re-frame2 is loaded with `interop/debug-enabled?` true, and confirms the preloaded runtime namespace landed. Failures return a structured edn shape — `{:ok? false :reason :runtime-loaded-but-preload-missing}` is the normal missing-preload verdict, alongside the other ladder rungs `:build-not-running` / `:no-runtime-connected` / `:nrepl-unreachable` — which the skill reports verbatim and routes to the matching recovery in [`references/errors.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/errors.md).

## Transport

The skill is **MCP-only**: a single skill-facing transport.

- **MCP server** — `@day8/re-frame2-pair-mcp`, a stdio JSON-RPC server holding
  one persistent nREPL connection per session. Per-op latency ~5–50ms. It is
  **not yet published to npm**: build it from a re-frame2 clone
  (`cd tools/re-frame2-pair-mcp && npm install && npm run build`) and point your
  agent host's MCP config at the compiled `out/server.js`. (Once published:
  `npm install -g @day8/re-frame2-pair-mcp`.) Source: [`tools/re-frame2-pair-mcp/`](https://github.com/day8/re-frame2/tree/main/tools/re-frame2-pair-mcp).

The MCP server is the one implementation of every operation. The
bash/babashka transport that originally fronted these ops
(`scripts/ops.clj` + shell wrappers) has been removed; the live
connect/dispatch/trace/hot-reload coverage now drives the MCP server over
stdio from `tools/re-frame2-pair-mcp/test/live-e2e-fixture.cjs`.

To force-load in Claude Code:

```
/skill re-frame2-pair
```

After connect, prefer a structured op (read, write, trace, DOM bridge, watch, hot-reload, time-travel) whenever one fits the gesture — and reach for `eval-cljs` as a first-class workhorse for the long tail no typed tool covers (epoch forensics, arbitrary-selector DOM reads, cross-referencing, recovery), not as a last resort.

## Where the skill lives

- Source: [`skills/re-frame2-pair/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair)
- `SKILL.md`: [`skills/re-frame2-pair/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/SKILL.md)
- Reference leaves: [`skills/re-frame2-pair/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair/references) — [`SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/SKILL.md) §Where the depth lives — loading map names the leaf per question.
- Tool-Pair contract: [`spec/Tool-Pair.md`](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md).
- Narrative companion: [Xray](../xray/index.md).
- Retrospective companion skill: [`re-frame2-pair-retro`](re-frame2-pair-retro.md).
