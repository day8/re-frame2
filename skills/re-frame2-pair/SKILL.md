---
name: re-frame2-pair
description: >
  Pair-program against a **running** re-frame2 application via its
  Tool-Pair contract — attach to a live shadow-cljs nREPL, inspect a
  frame's app-db, dispatch events, hot-swap handlers, read the trace
  stream and per-frame epoch history, and time-travel with
  `restore-epoch`. Use when the user is operating on (or wants to
  operate on) a live runtime they have running locally. **Do not use**
  for static spec reading, architecture questions, design discussion,
  or ordinary source edits when no runtime is involved — those belong
  to `skills/re-frame2/` (authoring) or direct spec reading. See
  `references/vocabulary.md` for the surface glossary; vocabulary
  matches alone do not justify activation.
allowed-tools:
  # MCP transport — single persistent nREPL connection per session.
  # The canonical path; install via `npm install -g @day8/re-frame2-pair-mcp`.
  - mcp__re-frame2-pair__discover-app
  - mcp__re-frame2-pair__eval-cljs
  - mcp__re-frame2-pair__dispatch
  - mcp__re-frame2-pair__trace-window
  - mcp__re-frame2-pair__watch-epochs
  - mcp__re-frame2-pair__tail-build
  - mcp__re-frame2-pair__snapshot
  - mcp__re-frame2-pair__get-path
  - mcp__re-frame2-pair__subscribe
  - mcp__re-frame2-pair__unsubscribe
  - mcp__re-frame2-pair__list-subscriptions
  - mcp__re-frame2-pair__handler-meta
  - mcp__re-frame2-pair__list-handlers
  - mcp__re-frame2-pair__get-re-frame2-pair-instructions
  # story-mcp — live-session tools only (HYBRID split). The
  # authoring-side surface (register-variant, get-variant,
  # preview-variant, list-stories, …) is allow-listed by the
  # `re-frame2` skill. These entries cover running a variant against
  # the live runtime, inspecting failures, and capturing the cascade
  # back into a `:play-script` snippet from within a pair-session.
  - mcp__re-frame2-story-mcp__run-variant
  - mcp__re-frame2-story-mcp__read-failures
  - mcp__re-frame2-story-mcp__snapshot-identity
  - mcp__re-frame2-story-mcp__run-a11y
  - mcp__re-frame2-story-mcp__record-as-variant
  # Read-only enumerations + agent-paste markdown surface a pair
  # session may reach into when navigating an unfamiliar Story
  # registry — peer surfaces to the authoring skill's allow-list.
  - mcp__re-frame2-story-mcp__list-decorators
  - mcp__re-frame2-story-mcp__get-docs-markdown
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame2-pair

You are pair-programming with a developer on a **live, running re-frame2 application**. The app is running in a browser tab behind `shadow-cljs watch`. Your job is to help the developer understand, debug, and modify the app by *operating on the live runtime* — not just by reading source files.

This is a **router skill**. The trigger-time guard rails live below; the operational depth (op catalogue, recipes, error handling, hot-reload protocol, v1-migration notes) lives in `references/` and is loaded on demand.

## The three primitives

Your agency runs through three coupled primitives, all part of re-frame2's own [Tool-Pair contract](https://github.com/day8/re-frame2/blob/master/docs/specification/Tool-Pair.md):

1. **The REPL** — a shadow-cljs nREPL session connected to the browser runtime, where ClojureScript forms evaluate against the real app.
2. **The trace stream** — `(rf/register-trace-listener id cb)` for live trace events; `(rf/trace-buffer opts)` for the retain-N ring of recent events. This skill registers exactly *one* trace listener (under id `:re-frame2-pair`) so multiple tools can coexist.
3. **The epoch history** — `(rf/epoch-history frame-id)` returns the per-frame ring of `:rf/epoch-record` values, each carrying the cascade's `:db-before`, `:db-after`, `:trace-events`, and the structured `:sub-runs` / `:renders` / `:effects` projections. `(rf/register-epoch-listener! id cb)` is the assembled-stream listener.

Every operation eventually becomes a short ClojureScript form evaluated through the REPL, usually against a helper function in the `re-frame2-pair.runtime` namespace that the consumer app preloads (see §Setup below).

---

## Setup — preload `re-frame2-pair.runtime`

The skill's helper namespace ships into the app via shadow-cljs's standard `:devtools :preloads` mechanism. This is re-frame2-pair's runtime-helper requirement, separate from Causa's devtools preload and true-inline `[data-rf-causa-host]` panel contract. **The re-frame2-pair preload is required**; there is no per-session cljs-eval inject fallback. `discover-app` refuses with `:reason :runtime-not-preloaded` when it can't find the marker.

Two-line setup. In `shadow-cljs.edn`:

```clojure
{:source-paths ["src"
                "node_modules/@day8/re-frame2-pair/preload"]  ;; add this
 :builds
 {:app {:devtools {:preloads [re-frame2-pair.runtime]}}}}     ;; …and this
```

Where the runtime lives:

- **Source of truth**: `skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs` in this repo. The path layout matches the CLJS namespace `re-frame2-pair.runtime` so shadow-cljs picks it up on `:source-paths`.
- **npm consumers**: the `@day8/re-frame2-pair` package ships the `preload/` directory; the source-path entry above points there.
- **Local-dev / linked checkouts**: substitute the absolute path to `skills/re-frame2-pair/preload/` for the `node_modules/...` entry.

Verification — run `discover-app` (the MCP tool `mcp__re-frame2-pair__discover-app`). The success result includes `:ok? true :session-id "..." :build-id :app`. If the preload is missing you get back:

```edn
{:ok? false :reason :runtime-not-preloaded
 :hint "re-frame2-pair.runtime is not loaded into this build. Add the preload entry to your shadow-cljs.edn: ..."}
```

Report the hint to the user verbatim — they can fix it in seconds without re-cloning anything.

---

## Cardinal rule — two modes of changing the app

- **REPL changes** (hot-swap a handler, evaluate a form, reset a frame's `app-db`) are **ephemeral**. They survive hot-reloads of unaffected namespaces, but are lost on full page reload. Use them for **probes, experiments, and throwaway fixes**.
- **Source edits** (using `Edit` / `Write`) are **permanent**. After any source edit, you *must* call `hot-reload/wait` before dispatching or tracing. Otherwise you'll interact with the pre-reload code and get misleading results.

Know which mode you're in and why. For the strict source-edit protocol, see [references/ops.md §Hot-reload coordination](references/ops.md#hot-reload-coordination).

---

## Connect first, every session

Before any other op, run:

```
discover-app
```

(MCP tool call — formal name `mcp__re-frame2-pair__discover-app`.)

This locates the shadow-cljs nREPL port, connects, switches the session to `:cljs` mode for the running build, verifies re-frame2 is loaded with `interop/debug-enabled?` true, and confirms the `re-frame2-pair.runtime` namespace was loaded by the consumer's `:devtools :preloads` (see §Setup above).

If any precondition fails, the script returns a structured edn error like `{:ok? false :reason :runtime-not-preloaded}`. Report the failing check to the user verbatim; do *not* guess at workarounds. See [references/errors.md](references/errors.md) for the common error reasons and the recovery each one calls for.

Between user turns, the nREPL session persists. A full page refresh in the browser drops the runtime, but the preload re-installs it on the next bundle load — no manual reconnect step is needed. Every op checks the load-time marker (`js/globalThis.__re_frame2_pair_runtime`) before proceeding; if it's missing the op refuses with the structured `:runtime-not-preloaded` hint above.

If you want a refresher on the MCP surface before the first real op, optionally call `get-re-frame2-pair-instructions` (formal name `mcp__re-frame2-pair__get-re-frame2-pair-instructions`) — it returns inline onboarding text (tool catalogue, EDN posture, tagged-mutation conventions, streaming-subscribe semantics, the wire pipeline) with no nREPL round-trip.

---

## Multi-frame model — set the operating frame

re-frame2 supports multiple, named frames (Spec 002). Most apps run with one frame (`:rf/default`); larger apps may run several (a stories build, an SSR slot, a sub-app island). Every read/write op takes an implicit operating frame; on the canonical MCP transport you override per-call with the `frame` arg, e.g. `{frame: ":foo"}` (the legacy bash-shim flag form `--frame :foo` is the back-compat appendix's equivalent — see [references/ops.md §Frames](references/ops.md#frames)).

- `frames/list` — `(rf/frame-ids)` — set of registered, non-destroyed frame ids.
- `frames/select` — set the session's default operating frame (the runtime caches it).
- `frames/meta` — `(rf/frame-meta id)` — flat metadata map for one frame: `:id`, `:created-at`, the preset-expansion keys (`:preset`, `:fx-overrides`, `:drain-depth`, …), and lifecycle fields (`:destroyed?`, `:listeners`) all at the top level. See `:rf/frame-meta` in Spec-Schemas.

When the operating frame is ambiguous (more than one is registered and the session hasn't selected one), **mutating ops refuse with `:ambiguous-frame`** and read ops proceed against `:rf/default` after warning. This mirrors the Spec 002 §Frame presets / lifecycle convention.

---

## Where the depth lives — loading map

Read the leaf that matches the task. Each reference file is ≤250 lines.

| Task shape | Reference |
|---|---|
| Pick a structured op (read, write, trace, DOM bridge, watch, hot-reload, time-travel) | [references/ops.md](references/ops.md) |
| Run a named procedure the user asked for ("why didn't my view update?", post-mortem, experiment loop, etc.) | [references/recipes.md](references/recipes.md) |
| Drive a Story variant from a pair-session — variant-id ↔ frame-id identity, per-variant isolation, the four-phase lifecycle | [references/variant-as-frame.md](references/variant-as-frame.md) |
| Open a push-mode subscription on the trace or epoch bus (topics, filters, termination) | [references/streaming-subscriptions.md](references/streaming-subscriptions.md) |
| Decode a deduped wire payload (`:rf.mcp/dedup-table`) or pick the right size-conscious arg (`max-tokens`, `path`, `mode`, `dedup`, `elision`, `limit`/`cursor`, `cache`, `max-buffered-*`) | [references/wire-size-budget.md](references/wire-size-budget.md) |
| Translate a structured `{:ok? false :reason ...}` to plain English; suggest the recovery | [references/errors.md](references/errors.md) |
| Inspect, propose, or hot-swap a frame's `:on-error` policy — the closed return-map contract | [references/on-error.md](references/on-error.md) |
| Edit source, then wait for the browser to pick up the new code | [references/ops.md §Hot-reload coordination](references/ops.md#hot-reload-coordination) |
| Map a v1 (`re-frame-pair`) surface to its v2 equivalent (or know that it was dropped) | [references/ops.md §Dropped from v1](references/ops.md#dropped-from-v1-re-frame-pair--surfaces-with-no-v2-equivalent) |
| Install/configure the persistent-connection MCP server | [references/mcp-transport.md](references/mcp-transport.md) |
| Drive a Story variant from a re-frame2-pair session — the variant *is* a frame; per-variant isolation, gotchas, discovery | [references/variant-as-frame.md](references/variant-as-frame.md) |
| Use story-mcp tools (`run-variant`, `read-failures`, `snapshot-identity`, `run-a11y`, `record-as-variant`) during a live pair-session — composition with watch-epochs and dispatch-from-pair | [references/stories.md](references/stories.md) |

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
- **One trace listener per skill.** This skill registers exactly one listener (`:re-frame2-pair`) and one epoch listener (`:re-frame2-pair-epoch`). Multi-tool coexistence is the expected default — don't worry about other listeners; per Spec 009 §Listener ordering, ordering is not contract.
- **Sensitive data does not cross the LLM boundary by default.** Per [Spec 009 §Privacy](../../spec/009-Instrumentation.md), re-frame2-pair-mcp ships with a `--allow-sensitive-reads` boot gate that is **OFF by default**; the CLI flag name is aligned across MCP servers. When OFF:
  - `snapshot`, `get-path`, `trace-window`, `watch-epochs`, and `subscribe` always force `:include-sensitive? false` and `:elision true` regardless of the per-call MCP arg. Sensitive slots in `:app-db` / `:sub-cache` reads return `:rf/redacted`; declared-large slots return the `:rf.size/large-elided` marker.
  - The preload's `app-db-reset!` taps default-elide both `:previous` and `:next` payloads through `re-frame.core/elide-wire-value` before any registered tap consumer sees them.
  - The streaming subscription dispatch additionally drops `:sensitive? true` trace events at source (the preload's `streaming-drop?` filter).

  Operators who need raw state for offline debug pass `--allow-sensitive-reads` at server launch — then the per-call MCP args win again (`:include-sensitive? true` and `:elision false` ride through). The retain-N ring buffer reached via `(rf/trace-buffer)` is a separate, explicit read surface — direct CLJS callers see everything regardless of the gate. Same architecture as the `--allow-eval` gate on `eval-cljs` and the canonically-named `--allow-sensitive-reads` gate on story-mcp. See [references/vocabulary.md §Privacy posture](references/vocabulary.md#privacy-posture--sensitive-and-the-streaming-surface).

---

## When to also open Causa

A re-frame2-pair session and a running Causa panel are **complementary** surfaces over the same trace bus + epoch history. Pair2 owns the *driving* (dispatch, hot-swap, restore-epoch); Causa owns the *seeing* (the visual reading of what just happened across its Dynamic event-spine tabs and Static registry-browse tabs — `skills/re-frame2-causa/` is the canonical source for Causa facts). Reach for Causa alongside re-frame2-pair when:

| Pair2 just did | Open Causa to … |
|---|---|
| Rewound to an earlier epoch via `restore-epoch` | Scrub the bottom-rail time-travel scrubber to inspect adjacent epochs visually; pin slices in the App-DB Diff panel. |
| Dispatched into a cascade you don't fully understand | The Event Detail panel lands on the latest cascade and shows the dispatch-id tree. |
| Hot-swapped a sub or reg-event handler | Watch the Subscriptions panel's invalidation-chain affordance recompute (`:cart/total` ← `:cart/items` ← `[:cart :items]`). |
| Stepped into a machine transition | Open the Machine Inspector for the state-chart view with transition history. |
| Triggered a schema violation | The Schema Violation Timeline surfaces it with recovery mode + source coord. |

The authoring-side guidance for getting Causa mounted (preload, layout host, suppress-auto-open knob, popout, host-CSS-variable resize) lives at [`skills/re-frame2/references/tooling/causa.md`](../re-frame2/references/tooling/causa.md). When you're advising a user mid-session on which panel to look at, route them there for the mount-side detail; this skill stays focused on the *driving* side.

---

*Deep-dive content (full API reference, EP design rationale, spec corpus, migration guide) routes through [`SKILL-REDIRECT.md`](../../SKILL-REDIRECT.md) at the repo root. Full skill-disambiguation matrix (when to use which skill) lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*
