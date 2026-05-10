# re-frame-pair2 — Initial Specification

**Status:** Draft 1
**Date:** 2026-05-09
**Owner:** mike.thompson@day8.com.au

---

## 1. Purpose

`re-frame-pair2` is a Claude Code Skill (and Plugin) that lets Claude act as a pair programmer for a **live, running [re-frame2](https://github.com/day8/re-frame2) application**. It attaches to the application's runtime via shadow-cljs nREPL and exposes a small set of operations that map directly onto re-frame2's primitives: frames, `app-db`, events, subscriptions, effects, interceptors, machines.

This is the re-frame2 sibling of v1 [`re-frame-pair`](https://github.com/day8/re-frame-pair). It consumes only re-frame2's own [Tool-Pair Spec](https://github.com/day8/re-frame2/blob/master/docs/specification/Tool-Pair.md) surfaces. **It has no re-frame-10x dependency.**

### Why this shape

re-frame is a reactive dataflow system — a DAG of derived values rooted in mutable state. `app-db` is the single source of truth; events are the only legal writes; subscriptions recompute as derived values; views re-render when their subs change. A coding agent that only edits `.cljs` files works against the static shape of that system and has no view of its dynamics at runtime.

re-frame-pair2 inverts this. It operates on the live browser runtime *and* on source files — but deliberately, with a protocol: REPL changes are ephemeral probes; source edits are committed changes coordinated with shadow-cljs hot-reload (§4.5). Every read and write runs through re-frame2's own vocabulary, so the data loop, the trace stream, the assembled epoch records, and the user's own instincts about the app all see the same thing Claude sees.

### Non-goals

- Not a replacement for re-frame-10x or any future v2 of it. 10x is a human-facing devtool; re-frame-pair2 is an agent-facing back-channel reading from re-frame2's public surfaces. They coexist as parallel listeners (Spec 009 §Listener ordering).
- Not a test runner, linter, or static analysis tool. Those operate on source; re-frame-pair2 operates on runtime.
- Not a production feature. Dev/debug only — `interop/debug-enabled?` gates the entire trace-and-epoch substrate.

### Assumed stack

- **re-frame2** — the subject. The reference implementation targets Reagent v2 + shadow-cljs.
- **`re-frame.interop/debug-enabled?` true** — automatic in dev builds; production elides per Spec 009 §Production builds. Without this, the trace stream and epoch history are no-ops and this skill has nothing to read.
- **Optional: re-frame2 source-coord annotation** (`(rf/configure :source-coords {:annotate-dom? true})`) — populates `data-rf2-source-coord` on rendered DOM nodes. Without this, the DOM->source bridge degrades; with it, every annotated element resolves to `{:ns :line :file :column}`.
- **Optional: re-com with debug instrumentation + `:src (at)`** at call sites — populates `data-rc-src`. Either annotation source unlocks the bridge; both can be present (re-frame2's wins).
- **shadow-cljs** as the build tool, with nREPL enabled.

re-frame-pair2 itself contributes **zero** additional host-project configuration.

### Terminology

- **Trace stream** — `(rf/register-trace-cb)` listeners + `(rf/trace-buffer)` retain-N ring. The fine-grained, per-emit stream (Spec 009).
- **Assembled epoch** — one `:rf/epoch-record` per drain-settle, with structured `:sub-runs` / `:renders` / `:effects` projections plus `:trace-events`. Consumed via `(rf/register-epoch-cb)` and `(rf/epoch-history frame-id)`.
- **Frame** — a re-frame2 isolated runtime instance (Spec 002). Most apps have one (`:rf/default`); larger apps have several.
- **Origin** — the Spec 002 §Dispatch origin tagging keyword on every dispatch (`:app`, `:pair`, `:story`, `:ui`, `:timer`, `:http`...). The skill stamps `:pair` on its own dispatches.
- **Session sentinel** — a UUID the skill interns on injection; its absence after a REPL lookup means the browser has refreshed and re-injection is needed.

---

## 2. Key concepts at a glance

- **Live runtime.** The browser JS runtime behind `shadow-cljs watch`.
- **Reactive graph.** re-frame2's subscription signal graph, with rf2-719e value-equal recompute suppression.
- **Per-frame state.** Each frame's `app-db` is reachable via `(rf/get-frame-db frame-id)` and `(rf/snapshot-of path opts)`.
- **Writes.** `dispatch` (with `:origin :pair` opt), `reg-*` re-registration, `restore-epoch`, container reset (rare).
- **Runtime introspection API.** Every Tool-Pair surface listed in [Tool-Pair §How AI tools attach](https://github.com/day8/re-frame2/blob/master/docs/specification/Tool-Pair.md#how-ai-tools-attach).
- **Connection mechanism.** nREPL -> shadow-cljs -> browser runtime.
- **Packaging.** `SKILL.md` + bash shim scripts + babashka ops dispatcher.
- **Cardinal rule.** Two modes — REPL (ephemeral) vs source edit (permanent via hot-reload). See §3.

---

## 3. Architecture

**Cardinal rule.** Two modes of changing the app, one protocol:

- **REPL changes** (hot-swap a handler, evaluate a form) are *ephemeral* — lost on full page reload. Preferred for probes and experiments.
- **Source edits** are *permanent* and pass through shadow-cljs hot-reload. After any source edit, the skill must `hot-reload/wait` before dispatching, or it risks interacting with the pre-reload code.

Source edits are not forbidden — they're the right tool for committed changes. See §4.5 for the reload-coordination protocol.

### 3.1 Connection path

shadow-cljs nREPL into the connected browser runtime. Same as v1.

### 3.2 Listening, not adapting

Where v1 reached into re-frame-10x's internal epoch buffer, v2 consumes re-frame2's own surfaces:

- `(rf/register-trace-cb :re-frame-pair2 cb)` — raw trace stream. The skill's listener id is fixed (one listener per skill per Spec 009).
- `(rf/register-epoch-cb :re-frame-pair2-epoch cb)` — assembled-epoch stream. Mirrors `register-trace-cb`'s contract.
- `(rf/trace-buffer opts)` — retain-N trace ring (default 200, configurable via `(rf/configure :trace-buffer {:depth N})`).
- `(rf/epoch-history frame-id)` — per-frame epoch ring (default 50, configurable via `(rf/configure :epoch-history {:depth N})`).
- `(rf/restore-epoch frame-id epoch-id)` — first-class time-travel with six documented failure modes (Tool-Pair §Time-travel).

No adapter layer; no internal-state introspection; no second source of truth. If a feature isn't in the Tool-Pair contract, the skill doesn't ship it (and the gap becomes a `bd` bead candidate — see "Asymmetries to monitor in the spec" in `STATUS.md`).

### 3.3 Component layout

```
re-frame-pair2/
├── .claude-plugin/
│   └── plugin.json                 # Claude Code Plugin manifest
├── SKILL.md                        # Skill body
├── README.md
├── STATUS.md
├── RELEASING.md
├── package.json
├── docs/
│   ├── initial-spec.md             # this file
│   ├── LOCAL_DEV.md
│   ├── TESTING.md
│   └── capabilities.md
├── scripts/
│   ├── discover-app.sh             # connect + verify + inject
│   ├── eval-cljs.sh                # raw CLJS eval
│   ├── inject-runtime.sh           # force re-inject runtime.cljs
│   ├── dispatch.sh                 # pair-tagged dispatch
│   ├── trace-window.sh             # last-N-ms epoch window
│   ├── watch-epochs.sh             # pull-mode live watch
│   ├── tail-build.sh               # probe-based hot-reload wait
│   ├── ops.clj                     # babashka dispatcher (every op)
│   └── runtime.cljs                # injected helper namespace
└── .github/
    └── workflows/
        ├── ci.yml
        └── release.yml
```

### 3.4 Session sentinel

Same as v1: a UUID interned on injection. Every op reads it; absence triggers re-injection. Survives hot-reloads of the running CLJS code; lost on full page refresh.

### 3.5 Watch transport

Pull-mode (same as v1, with Spec-Schemas-aware decoding). The watch loop polls `epochs-since` against the operating frame, tracks the last seen `:epoch-id`, and surfaces an `:id-aged-out?` warning when the tracking id falls off the ring. Streaming-via-`:out` is deferred.

### 3.6 Error surfaces

Structured `{:ok? false :reason ...}` — every script. Recognised reasons:

| Reason | Cause |
|---|---|
| `:nrepl-port-not-found` | shadow-cljs not running, or port file in an unexpected location |
| `:debug-disabled` | `interop/debug-enabled?` is false (production build) |
| `:ns-not-loaded` | `:missing :re-frame2` — re-frame2 isn't loaded into the runtime |
| `:no-frames-registered` | App hasn't called `(rf/init!)` yet |
| `:ambiguous-frame` | Multiple frames; mutating ops require explicit selection |
| `:eval-error`, `:cljs-eval-error` | nREPL or CLJS-eval surfaced an exception |
| `:no-epoch-recorded` | `dispatch-sync` returned but no record landed; recording disabled or frame destroyed |
| `:rf.epoch/restore-*` | One of six restore failure modes (Tool-Pair §Time-travel) |
| `:timed-out?` | Probe form didn't flip in `--wait-ms` (likely a compile error) |
| `:no-element-at-src` | `dom/fire-click-at-src` couldn't find a matching DOM node |
| `:source-coord-annotation-disabled` | Neither `:annotate-dom?` nor re-com debug is producing attributes |

### 3.7 Versioning / floors

re-frame2 itself is the only required dep. The Tool-Pair contract is additive across versions per Spec-ulation; the skill targets re-frame2 v1+ (the version that ships the contract).

---

## 4. Operation catalogue

See `SKILL.md` for the full vocabulary. Subsections at-a-glance:

- §4.1 Read — `app-db/snapshot`, `app-db/get`, `app-db/schemas`, `registrar/list`, `registrar/describe`, `subs/cache`, `subs/sample`, `machines/*`.
- §4.2 Write — `dispatch` (queued / sync / trace), `reg-*` re-registration, `app-db/reset`, `repl/eval`, `fx-overrides/with`.
- §4.3 Trace — `trace/buffer`, `trace/last-epoch`, `trace/last-pair-epoch`, `trace/epoch`, `trace/dispatch-and-collect`, `trace/recent`, `trace/find-where`, `trace/find-all-where`, `trace/cascade`.
- §4.3b DOM bridge — `dom/source-at`, `dom/find-by-src`, `dom/fire-click-at-src`, `dom/describe`. Reads `data-rf2-source-coord` first, `data-rc-src` second.
- §4.4 Watch — `watch/window`, `watch/count`, `watch/stream`, `watch/stop`. Predicates include `--origin`, `--frame`.
- §4.5 Hot-reload coordination — `tail-build.sh --probe '...'`. Recommended probe: `(rf/handler-meta kind id)` hash.
- §4.6 Time-travel — `epoch/history`, `epoch/restore`, `epoch/configure`, `undo/step-back`, `undo/to-epoch`. Six documented failure modes.
- §4.7 Recipes — see SKILL.md.

---

## 6. Phased delivery

| Phase | Deliverable | State |
|---|---|---|
| 0 | nREPL round-trip | Coded, not yet run |
| 1 | Read surface | Coded |
| 2 | Dispatch + trace | Coded |
| 3 | Live watch (pull-mode) | Coded |
| 4 | Hot-swap | Coded |
| 5 | Hot-reload coordination | Coded |
| 6 | Time-travel | Coded — first-class via re-frame2 |
| 7 | Diagnostics recipes | SKILL.md complete |
| 8 | Packaging | Coded |
| 9 | Fixture + spike | Not yet |

See `STATUS.md` for the per-phase state.

---

## 8. The §8a spike

Before graduating from pre-alpha, three things must be ground-truthed against a fixture re-frame2 app:

1. **Runtime discovery** — `discover-app.sh` connects, verifies `interop/debug-enabled?`, reports frames cleanly.
2. **CLJS-eval round-trip** — `cljs-eval-value` parses shadow's response shape correctly.
3. **`data-rf2-source-coord` format** — `parse-rf2-coord` matches whatever re-frame2's `:annotate-dom?` actually emits.

See `STATUS.md` for the full known-unknowns list.

---

## 9. Test architecture

Four surfaces — see `docs/TESTING.md`.

---

## 10. What changed from v1

- **No re-frame-10x dependency.** Every 10x reach has been replaced with a re-frame2 Tool-Pair surface. See `SKILL.md`'s "Dropped from v1" section for the exhaustive substitution table.
- **First-class time-travel.** `restore-epoch` is shipped by re-frame2; no adapter, no stubs, six documented failure modes.
- **Multi-frame.** Every op carries an operating-frame concept; reads use `:rf/default` when unambiguous; mutating ops refuse on `:ambiguous-frame`.
- **Origin tagging.** Pair dispatches carry `:origin :pair` so they can be filtered out of a trace stream that also carries `:app` / `:ui` / `:timer` / `:http` events.
- **Render projection consumed verbatim.** `:renders` and `:sub-runs` are projected by re-frame2 itself; no re-com classifier in the runtime (Spec-Schemas owns the projection shape).
- **Source-coord bridge takes re-frame2's annotation first, re-com's as a fallback.**

The skill's vocabulary is preserved end-to-end. A user familiar with v1 lands in the same place: same recipes, same op names where they make sense, same protocol around REPL vs source edits.
