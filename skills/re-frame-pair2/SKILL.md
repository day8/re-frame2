---
name: re-frame-pair2
description: >
  Pair-program with a live re-frame2 application. Attach to a running
  shadow-cljs build via nREPL, inspect any frame's app-db, dispatch
  events, hot-swap handlers, trace the six dominoes, and read the
  per-frame epoch history — all through re-frame2's own runtime
  contract (Tool-Pair Spec). No re-frame-10x dependency. Use this
  skill whenever the user asks about their running re-frame2 app or
  uses any of: re-frame2, app-db, dispatch, subscribe, reg-event,
  reg-sub, reg-fx, reg-machine, frame, epoch, interceptor, sub-cache,
  trace-buffer, register-trace-cb, register-epoch-cb, restore-epoch,
  re-com, shadow-cljs.
allowed-tools:
  # MCP transport (preferred — single persistent nREPL connection per session)
  - mcp__re-frame-pair2__discover-app
  - mcp__re-frame-pair2__eval-cljs
  - mcp__re-frame-pair2__inject-runtime
  - mcp__re-frame-pair2__dispatch
  - mcp__re-frame-pair2__trace-window
  - mcp__re-frame-pair2__watch-epochs
  - mcp__re-frame-pair2__tail-build
  # Bash-shim transport (deprecated — kept for back-compat sessions
  # where the MCP server isn't installed yet)
  - Bash(scripts/discover-app.sh *)
  - Bash(scripts/eval-cljs.sh *)
  - Bash(scripts/inject-runtime.sh *)
  - Bash(scripts/dispatch.sh *)
  - Bash(scripts/trace-window.sh *)
  - Bash(scripts/watch-epochs.sh *)
  - Bash(scripts/tail-build.sh *)
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame-pair2

You are pair-programming with a developer on a **live, running re-frame2 application**. The app is running in a browser tab behind `shadow-cljs watch`. Your job is to help the developer understand, debug, and modify the app by *operating on the live runtime* — not just by reading source files.

This is a **router skill**. The trigger-time guard rails live below; the operational depth (op catalogue, recipes, error handling, hot-reload protocol, v1-migration notes) lives in `references/` and is loaded on demand.

## The three primitives

Your agency runs through three coupled primitives, all part of re-frame2's own [Tool-Pair contract](https://github.com/day8/re-frame2/blob/master/docs/specification/Tool-Pair.md):

1. **The REPL** — a shadow-cljs nREPL session connected to the browser runtime, where ClojureScript forms evaluate against the real app.
2. **The trace stream** — `(rf/register-trace-cb id cb)` for live trace events; `(rf/trace-buffer opts)` for the retain-N ring of recent events. This skill registers exactly *one* trace listener (under id `:re-frame-pair2`) so multiple tools can coexist.
3. **The epoch history** — `(rf/epoch-history frame-id)` returns the per-frame ring of `:rf/epoch-record` values, each carrying the cascade's `:db-before`, `:db-after`, `:trace-events`, and the structured `:sub-runs` / `:renders` / `:effects` projections. `(rf/register-epoch-cb id cb)` is the assembled-stream listener.

Every operation eventually becomes a short ClojureScript form evaluated through the REPL, usually against a helper function in the `re-frame-pair2.runtime` namespace that the skill injects on connect.

---

## Cardinal rule — two modes of changing the app

- **REPL changes** (hot-swap a handler, evaluate a form, reset a frame's `app-db`) are **ephemeral**. They survive hot-reloads of unaffected namespaces, but are lost on full page reload. Use them for **probes, experiments, and throwaway fixes**.
- **Source edits** (using `Edit` / `Write`) are **permanent**. After any source edit, you *must* call `hot-reload/wait` before dispatching or tracing. Otherwise you'll interact with the pre-reload code and get misleading results.

Know which mode you're in and why. For the strict source-edit protocol, see [references/hot-reload-protocol.md](references/hot-reload-protocol.md).

---

## Connect first, every session

Before any other op, run:

```
discover-app
```

(MCP tool call — formal name `mcp__re-frame-pair2__discover-app`.
When the MCP server isn't configured for this session, fall back to
the legacy bash shim: `scripts/discover-app.sh`.)

This locates the shadow-cljs nREPL port, connects, switches the session to `:cljs` mode for the running build, verifies re-frame2 is loaded with `interop/debug-enabled?` true, and injects the runtime namespace.

If any precondition fails, the script returns a structured edn error like `{:ok? false :missing :re-frame2}`. Report the failing check to the user verbatim; do *not* guess at workarounds. See [references/errors.md](references/errors.md) for the common error reasons and the recovery each one calls for.

Between user turns, the nREPL session persists, but a full page refresh in the browser drops the injected namespace. Every op checks the **session sentinel** (`re-frame-pair2.runtime/session-id`) and re-injects if it's gone. You don't usually need to do this by hand.

---

## Multi-frame model — set the operating frame

re-frame2 supports multiple, named frames (Spec 002). Most apps run with one frame (`:rf/default`); larger apps may run several (a stories build, an SSR slot, a sub-app island). Every read/write op takes an implicit operating frame; you can override per-call with `--frame :foo`.

- `frames/list` — `(rf/frame-ids)` — set of registered, non-destroyed frame ids.
- `frames/select` — set the session's default operating frame (the runtime caches it).
- `frames/meta` — `(rf/frame-meta id)` — config + lifecycle for one frame.

When the operating frame is ambiguous (more than one is registered and the session hasn't selected one), **mutating ops refuse with `:ambiguous-frame`** and read ops proceed against `:rf/default` after warning. This mirrors the Spec 002 §Frame presets / lifecycle convention.

---

## Where the depth lives — loading map

Read the leaf that matches the task. Each reference file is ≤250 lines.

| Task shape | Reference |
|---|---|
| Pick a structured op (read, write, trace, DOM bridge, watch, hot-reload, time-travel) | [references/ops.md](references/ops.md) |
| Run a named procedure the user asked for ("why didn't my view update?", post-mortem, experiment loop, etc.) | [references/recipes.md](references/recipes.md) |
| Translate a structured `{:ok? false :reason ...}` to plain English; suggest the recovery | [references/errors.md](references/errors.md) |
| Edit source, then wait for the browser to pick up the new code | [references/hot-reload-protocol.md](references/hot-reload-protocol.md) |
| Map a v1 (`re-frame-pair`) surface to its v2 equivalent (or know that it was dropped) | [references/migration-from-v1.md](references/migration-from-v1.md) |
| Install/configure the persistent-connection MCP server, or map a bash shim to its MCP tool name | [references/mcp-transport.md](references/mcp-transport.md) |

Load at most two references for a single task. If you find yourself wanting three, the request likely spans concerns and should be broken up.

---

## Style guidance

- **Read before you write.** Use `app-db/snapshot` or `trace/last-epoch` to ground a hypothesis before proposing a change.
- **Prefer structured ops over `repl/eval`.** The escape hatch is available; use it for probes that don't fit the catalogue.
- **Keep it in re-frame2's vocabulary.** Dispatch, reg-event-fx, reg-sub, reg-machine, frame, epoch — speak the same language the app speaks. Avoid `reset!` of a frame's app-db except when surgically needed, and say so when you do.
- **Experiment, don't speculate.** When an answer isn't obvious, probe at the REPL against live data.
- **Validate before proposing.** When a hot-swap or suggestion is on the table, compose the form and run it against current state first.
- **Narrow detail as you go.** Summaries first; drill into a specific epoch, diff, sub-run, or render entry when the user asks.
- **Always resolve UI references to source first.** When the user mentions a button, view, panel, or "the thing I clicked", run `dom/source-at` *before* speculating about behaviour. Reporting `re-com/button at app/cart/view.cljs:84` grounds the conversation in a file the user can open; reporting *"probably the Save button somewhere in the profile view"* doesn't.
- **Surface restore limits.** Before any time-travel experiment, walk the cascade's effects and tell the user which effects already fired and cannot be reversed.
- **Use the assembled epoch stream by default; reach for the raw trace stream when you need detail the projection drops.** `:sub-runs`, `:renders`, `:effects` are the routing surface; `:trace-events` is the escape hatch when the projection is incomplete (e.g. successful-fx attribution).
- **One trace listener per skill.** This skill registers exactly one listener (`:re-frame-pair2`) and one epoch listener (`:re-frame-pair2-epoch`). Multi-tool coexistence is the expected default — don't worry about other listeners; per Spec 009 §Listener ordering, ordering is not contract.

---

*Deep-dive content (full API reference, EP design rationale, spec corpus, migration guide) routes through [`SKILL-REDIRECT.md`](../../SKILL-REDIRECT.md) at the repo root. For authoring re-frame2 code (rather than inspecting a live app), see `skills/re-frame2/`. For greenfield bootstrap, see `skills/re-frame2-setup/`.*
