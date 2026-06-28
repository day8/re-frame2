# re-frame2-pair — Initial Specification

**Status:** Draft 1 (historical design record)
**Date:** 2026-05-09
**Owner:** mike.thompson@day8.com.au

> **Transport superseded.** This is the original design record. The
> live, skill-facing transport is now the **MCP server**
> (`tools/re-frame2-pair-mcp/`) — **30** tools, **all 30**
> allow-listed; the two write-authority tools (`restore-epoch`,
> `replace-app-db`) are the canonical named-write path and are gated
> behind the server's default-OFF `--allow-writes` flag — the server's
> gate, not the allow-list, is the write boundary (see `STATUS.md` +
> `references/mcp-transport.md`).
> The `scripts/*.sh` + babashka `ops.clj` shims and the `cljs-eval`
> injection step described below are **retired** from the skill
> surface — shims on disk for the e2e harness only, and there is no
> `inject-runtime` tool (gone; the runtime ships via shadow-cljs
> `:devtools :preloads`). The §3.3 component layout and §4 op-catalogue slash-op
> names below are a historical snapshot; the current op surface is the
> flat `mcp__re-frame2-pair__*` tools catalogued in
> `references/ops.md` + `references/mcp-transport.md`. A re-authoring
> pass MUST target the current MCP-only contract, not the shapes in
> this draft.

---

## 1. Purpose

`re-frame2-pair` is a Claude Code Skill (and Plugin) that lets Claude act as a pair programmer for a **live, running [re-frame2](https://github.com/day8/re-frame2) application**. It attaches to the application's runtime via shadow-cljs nREPL and exposes a small set of operations that map directly onto re-frame2's primitives: frames, `app-db`, events, subscriptions, effects, interceptors, machines.

This is the re-frame2 sibling of v1 [`re-frame-pair`](https://github.com/day8/re-frame-pair). It consumes only re-frame2's own [Tool-Pair Spec](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md) surfaces. **It has no re-frame-10x dependency.**

### Why this shape

re-frame is a reactive dataflow system — a DAG of derived values rooted in mutable state. `app-db` is the single source of truth; events are the only legal writes; subscriptions recompute as derived values; views re-render when their subs change. A coding agent that only edits `.cljs` files works against the static shape of that system and has no view of its dynamics at runtime.

re-frame2-pair inverts this. It operates on the live browser runtime *and* on source files — but deliberately, with a protocol: REPL changes are ephemeral probes; source edits are committed changes coordinated with shadow-cljs hot-reload (§4.5). Every read and write runs through re-frame2's own vocabulary, so the data loop, the trace stream, the assembled epoch records, and the user's own instincts about the app all see the same thing Claude sees.

### Non-goals

- Not a replacement for Xray, and not a revival of re-frame-10x. Xray is the human-facing devtool, defaulting to an app-provided `[data-rf-xray-host]` true-inline panel; re-frame2-pair is an agent-facing back-channel reading from re-frame2's public surfaces. They coexist as parallel listeners (Spec 009 §Listener ordering).
- Not a test runner, linter, or static analysis tool. Those operate on source; re-frame2-pair operates on runtime.
- Not a production feature. Dev/debug only — `interop/debug-enabled?` gates the entire trace-and-epoch substrate.

### Assumed stack

- **re-frame2** — the subject. The reference implementation targets Reagent v2 + shadow-cljs.
- **`re-frame.interop/debug-enabled?` true** — automatic in dev builds; production elides per Spec 009 §Production builds. Without this, the trace stream and epoch history are no-ops and this skill has nothing to read.
- **re-frame2 source-coord annotation** — mandatory in debug builds (gated on `interop/debug-enabled?`, **not** user-enabled via `configure!`): re-frame2 populates `data-rf2-source-coord` on every **registered view's** root DOM node. Each annotated element resolves to `{:ns :line :file :column}`. The DOM→source bridge degrades for anonymous (non-registered) views, non-DOM adapters, and production builds; re-com `:src (at)` is the fallback.
- **Optional: re-com with debug instrumentation + `:src (at)`** at call sites — populates `data-rc-src`. Either annotation source unlocks the bridge; both can be present (re-frame2's wins).
- **shadow-cljs** as the build tool, with nREPL enabled.

re-frame2-pair itself contributes **zero** additional host-project configuration.

### Terminology

- **Trace stream** — `(re-frame.trace.tooling/register-listener!)` listeners + `(re-frame.trace.tooling/trace-buffer frame-id)` retain-N ring. The fine-grained, per-emit stream (Spec 009). (`register-listener!` is also on `rf/`; `trace-buffer` is a JVM-only `rf/` alias, so CLJS callers use the `re-frame.trace.tooling` form. Frame-id is the first positional arg — a missing frame returns `[]`.)
- **Assembled epoch** — one `:rf/epoch-record` per drain-settle, with structured `:sub-runs` / `:renders` / `:effects` projections plus `:trace-events`. Consumed via `(rf/register-epoch-listener!)` and `(rf/epoch-history frame-id)`.
- **Frame** — a re-frame2 isolated runtime instance (Spec 002). Most apps have one (`:rf/default`); larger apps have several.
- **Origin** — the Spec 002 §Dispatch origin tagging keyword on every dispatch (`:app`, `:pair`, `:story`, `:ui`, `:timer`, `:http`...). The skill stamps `:pair` on its own dispatches.
- **Session sentinel** — a UUID interned at preload-load time. The MCP server probes the load-time mirror at `js/globalThis.__re_frame2_pair_runtime`; its absence means the preload isn't configured (or the page refreshed and the next bundle load hasn't run the preload yet).

---

## 2. Key concepts at a glance

- **Live runtime.** The browser JS runtime behind `shadow-cljs watch`.
- **Reactive graph.** re-frame2's subscription signal graph, with value-equal recompute suppression.
- **Per-frame state.** Each frame's `app-db` is reachable via `(rf/app-db-value frame-id)` and `(rf/snapshot-of path opts)`.
- **Writes.** `dispatch` (with `:origin :pair` opt), `reg-*` re-registration, `restore-epoch`, container reset (rare).
- **Runtime introspection API.** Every Tool-Pair surface listed in [Tool-Pair §How AI tools attach](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md#how-ai-tools-attach).
- **Connection mechanism.** nREPL -> shadow-cljs -> browser runtime.
- **Packaging.** `SKILL.md` + `references/` + the `re-frame2-pair.runtime` preload, driven over the **MCP server** (`tools/re-frame2-pair-mcp/`) — the only skill-facing transport. The `scripts/*.sh` + `ops.clj` (babashka) shims predate the MCP server and are retired from the skill surface; they remain on disk for the project's own e2e harness only.
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

- `(re-frame.trace.tooling/register-listener! :re-frame2-pair cb)` — raw trace stream (also re-exported on `rf/`). The skill's listener id is fixed (one listener per skill per Spec 009).
- `(rf/register-epoch-listener! :re-frame2-pair-epoch cb)` — assembled-epoch stream. Mirrors `register-listener!`'s contract.
- `(re-frame.trace.tooling/trace-buffer frame-id)` / `(re-frame.trace.tooling/trace-buffer frame-id opts)` — retain-N trace ring (default 200, configurable via `(rf/configure! {:trace-buffer {:cascades-retained N}})`). Frame-id is the first positional arg (a missing frame returns `[]`); the default shape is cascade bundles, and `:operation` / `:op-type` / `:since` / `:severity` are `:flat-only` filters (pass `{:flat true ...}`). CLJS callers must use the `re-frame.trace.tooling` ns — `rf/trace-buffer` is a JVM-only alias and returns nil in the browser runtime.
- `(rf/epoch-history frame-id)` — per-frame epoch ring (default 50, configurable via `(rf/configure! {:epoch-history {:depth N}})`).
- `(rf/restore-epoch! frame-id epoch-id)` — first-class time-travel with seven documented failure modes (Tool-Pair §Time-travel).

No adapter layer; no internal-state introspection; no second source of truth. If a feature isn't in the Tool-Pair contract, the skill doesn't ship it (and the gap becomes a `bd` bead candidate — see "Asymmetries to monitor in the spec" in `STATUS.md`).

### 3.3 Component layout

```
re-frame2-pair/
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
│   ├── discover-app.sh             # connect + verify + probe preload
│   ├── eval-cljs.sh                # raw CLJS eval
│   ├── dispatch.sh                 # pair-tagged dispatch
│   ├── trace-window.sh             # last-N-ms epoch window
│   ├── watch-epochs.sh             # pull-mode live watch
│   ├── tail-build.sh               # probe-based hot-reload wait
│   └── ops.clj                     # babashka dispatcher (every op)
├── preload/
│   └── re_frame2_pair/
│       └── runtime.cljs            # shadow-cljs :preloads target
└── .github/
    └── workflows/
        ├── ci.yml
        └── release.yml
```

### 3.4 Session sentinel

A UUID set once at preload-load time, mirrored to `js/globalThis.__re_frame2_pair_runtime`. The MCP server's `discover-app` probes the mirror; absence means the preload isn't configured and the op refuses with `:reason :runtime-loaded-but-preload-missing` (the normal missing-preload verdict; `:runtime-not-preloaded` is the degradation fallback the ladder returns only when it errors mid-diagnosis). A full page refresh wipes both the var and the mirror, but the next bundle load re-runs the preload — no manual reconnect step.

(Earlier iterations injected the runtime via `cljs-eval` on first connect each session; that path was cut along with the cljs-eval fallback for pre-alpha simplicity — the runtime now ships via shadow-cljs `:preloads`.)

### 3.5 Watch transport

Pull-mode (same as v1, with Spec-Schemas-aware decoding). The watch loop polls `epochs-since` against the operating frame, tracks the last seen `:epoch-id`, and surfaces an `:id-aged-out?` warning when the tracking id falls off the ring. (Historical: this draft deferred push-streaming-via-`:out`; push-mode `subscribe` / `unsubscribe` has since landed — see `STATUS.md` §Live watch and `references/streaming-subscriptions.md`.)

### 3.6 Error surfaces

Structured `{:ok? false :reason ...}` — every script. Recognised reasons:

| Reason | Cause |
|---|---|
| `:nrepl-port-not-found` | shadow-cljs not running, or port file in an unexpected location |
| `:debug-disabled` | `interop/debug-enabled?` is false (production build) |
| `:ns-not-loaded` | `:missing :re-frame2` — re-frame2 isn't loaded into the runtime |
| `:no-frames-registered` | App hasn't established its app frame yet (`init!` installs only the adapter — it creates no frame under EP-0002) |
| `:ambiguous-frame` | Multiple app frames, none selected; both reads and writes refuse rather than default to `:rf/default` — pin one or pass `frame` |
| `:eval-error`, `:cljs-eval-error` | nREPL or CLJS-eval surfaced an exception |
| `:no-epoch-recorded` | `dispatch-sync` returned but no record landed; recording disabled or frame destroyed |
| `:rf.epoch/restore-*` | One of the restore failure modes (Tool-Pair §Time-travel lists seven; six fire under `:rf.epoch/*`, plus **Unknown frame** under `:rf.error/no-such-handler`) |
| `:timed-out?` | Probe form didn't flip in `--wait-ms` (likely a compile error) |
| `:no-element-at-src` | `dom/fire-click-at-src` couldn't find a matching DOM node |
| `:source-coord-annotation-disabled` | No source-coord attributes reaching the DOM — production build, no registered-view coverage, non-DOM adapter, or no re-com `:src (at)` |

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
- §4.6 Time-travel — `epoch/history`, `epoch/restore`, `epoch/configure`, `undo/step-back`, `undo/to-epoch`. Seven documented failure modes.
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
3. **`data-rf2-source-coord` format** — `parse-rf2-coord` matches whatever re-frame2's registered-view DOM annotation actually emits.

See `STATUS.md` for the full known-unknowns list.

---

## 9. Test architecture

Four surfaces — see `docs/TESTING.md`.

---

## 10. What changed from v1

- **No re-frame-10x dependency.** Every 10x reach has been replaced with a re-frame2 Tool-Pair surface. See `SKILL.md`'s "Dropped from v1" section for the exhaustive substitution table.
- **First-class time-travel.** `restore-epoch` is shipped by re-frame2; no adapter, no stubs, seven documented failure modes.
- **Multi-frame.** Every op carries an operating-frame concept; the operating frame resolves to the sole registered app frame when unambiguous; both reads and writes refuse on `:ambiguous-frame` (reads return `:reason :ambiguous-frame` rather than silently reading `:rf/default`).
- **Origin tagging.** Pair dispatches carry `:origin :pair` so they can be filtered out of a trace stream that also carries `:app` / `:ui` / `:timer` / `:http` events.
- **Render projection consumed verbatim.** `:renders` and `:sub-runs` are projected by re-frame2 itself; no re-com classifier in the runtime (Spec-Schemas owns the projection shape).
- **Source-coord bridge takes re-frame2's annotation first, re-com's as a fallback.**

The skill's vocabulary is preserved end-to-end. A user familiar with v1 lands in the same place: same recipes, same op names where they make sense, same protocol around REPL vs source edits.
