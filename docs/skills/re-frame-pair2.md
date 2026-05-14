# re-frame-pair2

> Pair-program with a live, running re-frame2 application. Attach via nREPL, inspect any frame's `app-db`, dispatch events, hot-swap handlers, walk epochs, and read the trace stream — through re-frame2's own Tool-Pair contract, no `re-frame-10x` dependency.

## What it does

The `re-frame-pair2` skill is the AI pair-programming companion for a running re-frame2 app. The app is up behind `shadow-cljs watch`; the skill attaches to its nREPL session in `:cljs` mode and operates on the live runtime — not just static source files.

Three primitives carry the skill's agency, all part of re-frame2's [Tool-Pair Spec](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md):

1. **The REPL** — ClojureScript forms evaluated against the real app, usually through helpers in the injected `re-frame-pair2.runtime` namespace.
2. **The trace stream** — `(rf/register-trace-cb id cb)` for live trace events; `(rf/trace-buffer opts)` for the retain-N ring of recent events.
3. **The epoch history** — `(rf/epoch-history frame-id)` returns the per-frame ring of `:rf/epoch-record` values, each carrying `:db-before`, `:db-after`, `:trace-events`, and the assembled `:sub-runs` / `:renders` / `:effects` projections.

The skill is **multi-frame aware** (Spec 002 — most apps run with one frame, larger apps run several). It registers exactly **one** trace listener (`:re-frame-pair2`) and one epoch listener (`:re-frame-pair2-epoch`) so it coexists with other tools (e.g. `re-frame-10x` v2) on the same bus. Mutating ops refuse with `:ambiguous-frame` when the operating frame is unclear.

The cardinal rule: **REPL changes are ephemeral, source edits are permanent.** After any source edit, the skill waits on the hot-reload protocol before dispatching or tracing — otherwise you interact with the pre-reload code.

## When to reach for it

Load this skill when the user mentions a **running** re-frame2 app, or any of: `re-frame2`, `app-db`, `dispatch`, `subscribe`, `reg-event`, `reg-sub`, `reg-fx`, `reg-machine`, frame, epoch, interceptor, sub-cache, trace-buffer, `register-trace-cb!`, `register-epoch-cb!`, `restore-epoch`, re-com, shadow-cljs — *and the question is about the live runtime*, not about writing new code.

Do **not** use this skill for:

- Writing new application code → use [re-frame2](re-frame2.md).
- Greenfield setup → use [re-frame2-setup](re-frame2-setup.md).
- Migrating a v1 project → use [re-frame-migration](re-frame-migration.md).

## Kickoff

Every session starts with `discover-app` — call the MCP tool when the
`re-frame-pair2-mcp` server is installed (preferred — see
[Transport](#transport) below), or the legacy bash shim
`scripts/discover-app.sh` otherwise:

```
discover-app
# or, if the MCP server isn't configured for this agent host:
scripts/discover-app.sh
```

This locates the shadow-cljs nREPL port, connects, switches to `:cljs` mode for the running build, verifies re-frame2 is loaded with `interop/debug-enabled?` true, and injects the runtime namespace. Failures return a structured edn shape like `{:ok? false :missing :re-frame2}` which the skill reports verbatim and routes to the matching recovery in [`references/errors.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-pair2/references/errors.md).

## Transport

Two transports ship with the skill:

- **MCP server** (preferred) — `@day8/re-frame-pair2-mcp`, an
  npm-installable stdio JSON-RPC server holding one persistent
  nREPL connection per session. Per-op latency ~5–50ms. Install via
  `npm install -g @day8/re-frame-pair2-mcp` and add to your agent
  host's MCP config. Source: [`tools/pair2-mcp/`](https://github.com/day8/re-frame2/tree/main/tools/pair2-mcp).
- **Bash shims** (deprecated, kept for back-compat) — one bash
  script per op, each spawning bash → babashka → fresh nREPL connect
  per call. Per-op latency ~700ms. Use only when the MCP server
  isn't available in the current agent host.

The op vocabulary is identical across transports; pick whichever your
session has wired up.

To force-load in Claude Code:

```
/skill re-frame-pair2
```

After connect, work in structured ops (read, write, trace, DOM bridge, watch, hot-reload, time-travel) rather than ad-hoc `repl/eval` — the escape hatch is available for probes that don't fit the catalogue.

## Where the skill lives

- Source: [`skills/re-frame-pair2/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair2)
- `SKILL.md`: [`skills/re-frame-pair2/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-pair2/SKILL.md)
- Reference leaves: [`skills/re-frame-pair2/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair2/references) — `ops.md` (structured ops catalogue), `recipes.md` (named procedures: *"why didn't my view update?"*, post-mortem, experiment loop), `errors.md` (structured-error → plain-English recovery), `hot-reload-protocol.md`, `migration-from-v1.md`.
- Tool-Pair contract: [`spec/Tool-Pair.md`](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md).
- Narrative companion: [Causa](../causa/index.md).
- Retrospective companion skill: [`re-frame-pair-retro2`](re-frame-pair-retro2.md).
