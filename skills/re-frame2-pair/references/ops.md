# Operations catalogue

The op vocabulary the skill operates through. Every op runs over the **MCP transport** (the only transport this skill exposes) — see [`mcp-transport.md`](mcp-transport.md). The MCP form is shown in the Invocation column. The bash shims under `scripts/` are retired from the skill's tool surface; their shell counterparts are catalogued in the [Bash-shim appendix](#bash-shim-appendix-not-reachable-from-this-skill) for the project's own e2e harness only.

Most ops wrap a call into `re-frame2-pair.runtime`; for those, the MCP form is `eval-cljs {form: "<runtime call>"}`. Dedicated MCP tools (`dispatch`, `snapshot`, `get-path`, `trace-window`, `watch-epochs`, `tail-build`, `subscribe`, `unsubscribe`) cover the broader concerns. Prefer the **structured ops** over `repl/eval` whenever a structured op fits. See [`mcp-transport.md`](mcp-transport.md) for transport details.

## Contents

- [Read](#read)
- [Frames](#frames)
- [Write](#write)
- [Trace](#trace) — trace stream + epoch history
- [DOM source bridge](#dom-source-bridge)
- [Live watch (push-mode)](#live-watch-push-mode)
- [Hot-reload coordination](#hot-reload-coordination)
- [Time-travel (epoch restore)](#time-travel-epoch-restore)
- [Bash-shim appendix (not reachable from this skill)](#bash-shim-appendix-not-reachable-from-this-skill)
- [Dropped from v1 (re-frame-pair) — surfaces with no v2 equivalent](#dropped-from-v1-re-frame-pair--surfaces-with-no-v2-equivalent)

## Read

| Op | Invocation | Returns |
|---|---|---|
| `app-db/snapshot` | `mcp__re-frame2-pair__snapshot {}` | Current app-db value for the operating frame (via `rf/app-db-value`). The MCP `snapshot` tool defaults to **`:summary` mode** (top-level shape only) + supports `:path`-slicing at the wire boundary — see [`mcp-transport.md` §:app-db slice modes](mcp-transport.md#when-to-use-snapshot-vs-the-per-op-reads). Pass `path: "[]"` for the full unsliced value. The underlying runtime form is `(re-frame2-pair.runtime/snapshot)`. |
| `app-db/get` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/app-db-at [:path :to :value])"}` | Path-scoped value (via `rf/snapshot-of`). For targeted reads, prefer the `get-path` tool below — single round-trip, structured `{:exists?}` answer, shared `:path` vocabulary with `snapshot`. |
| `app-db/get-path` | `mcp__re-frame2-pair__get-path {path: "[:cart :items 0 :sku]"}` | Targeted read at `path`. `{:ok? true :exists? true :value <subtree>}` on hit; `{:ok? false :reason :path-not-found :deepest-valid-prefix [...]}` on miss. `:exists?` distinguishes a path that points at `nil` from a missing path. |
| `app-db/schemas` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/schemas)"}` | Map of `path → schema` from `rf/app-schemas` |
| `registrar/list` | `mcp__re-frame2-pair__list-handlers {kind: "event"}` | The **discovery** surface — every id registered under one kind. `{:ok? true :kind :event :ids [...] :count n}`, ids sorted (stable across calls). Supported kinds: `event` / `sub` / `fx` / `cofx` / `view` / `frame` / `route` / `flow` / `head` / `error-projector` / `machine` (the closed v1 registrar set; per rf2-cq1ak app-db schemas are not a registrar kind — use `app-db/schemas` for schemas). `machine` lists handlers flagged `:rf/machine? true`. Prefer this over a `registrar-list` eval — no wide-authority eval round-trip. *(eval fallback: `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/registrar-list :event)"}`.)* |
| `registrar/describe` | `mcp__re-frame2-pair__handler-meta {kind: "event", id: ":cart/apply-coupon"}` | The **drill** surface — registration metadata for one id: source-coord (`:ns` / `:line` / `:column` / `:file`), `:doc`, `:tags`, plus an `:rf.source/uri` the host renders as a clickable jump-to-editor link. `{:ok? true :kind k :id i ...}` on a hit; `{:ok? false :reason :not-registered :kind k :id i}` on a miss. Pass composite-key sub ids as the vector-string form (`id: "[:rf/composite :x]"`). The `machine` kind routes through `rf/machine-meta`; others through `rf/handler-meta`. Prefer this over a `registrar-describe` eval — targeted read, no eval authority. *(eval fallback: `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/registrar-describe :event :cart/apply-coupon)"}` — also surfaces the retained source form when present.)* |
| `subs/cache` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/sub-cache)"}` | `rf/sub-cache` — `{query-v {:value v :ref-count n}}` for every materialised subscription (CLJS-only) |
| `subs/sample` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/subs-sample [:cart/total])"}` | One-shot value via `rf/compute-sub` (no cache mutation) or `@(rf/subscribe ...)` |
| `machines/list` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/machines-list)"}` | Machine ids (`rf/machines`) |
| `machines/describe` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/machine-describe :auth)"}` | The registered spec map (`rf/machine-meta`) |
| `machines/state` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/machine-state :auth)"}` | Current snapshot from `(rf/snapshot-of [:rf/runtime :machines :snapshots :auth])` |

## Frames

Set and inspect the operating frame (SKILL.md §Multi-frame model). Every read/write op resolves an operating frame: an explicit per-call `frame: ":foo"` arg wins, else the session pin, else the sole registered **app frame**, else `:ambiguous-frame` for mutating ops.

**Reserved tool frames are excluded from the ambiguity count (rf2-3bu3d.4).** `:rf/*` reserved tool frames — Xray's `:rf/xray`, an SSR slot, … (per [Conventions.md §Reserved namespaces](../../../spec/Conventions.md)) — are devtool surfaces the tooling mounted, not the app you are pairing against, so they are removed before counting. A single-app session that *also* carries an `:rf/xray` frame (the common Xray-instrumented case) has exactly one app frame and resolves to it automatically — **no `frames/select` is needed**. Only a session with two-plus genuine *app* frames is ambiguous. The carve-out is `:rf/default`: it shares the `:rf/*` root but IS the universal default app frame, so it is always counted as an app frame.

**Prefer the dedicated operating-frame tools (rf2-zomfq)** to set and read the session pin — `set-operating-frame {frame: ":foo"}` / `reset-operating-frame {}` / `get-operating-frame {}`. They are the wire-level surfacing of the pin and the escape from the tier-4 `:ambiguous-frame` refusal (see SKILL.md §Multi-frame model). The eval-based helpers below wrap the same preload functions (`select-frame!` / `current-frame` / `frame-meta`); reach for them only for `frames/meta`, which has no dedicated tool, or when you want the raw runtime return shape.

| Op | Invocation | Returns |
|---|---|---|
| `frames/list` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/frames-list)"}` | `{:ok? true :frames [...] :app-frames [...] :selected <pinned-or-nil> :operating <resolved-or-nil>}` — `:frames` is every registered, non-destroyed frame (`rf/frame-ids`); `:app-frames` is the reserved-frame-aware view (`:frames` minus `:rf/*` tool frames like `:rf/xray`). When `:app-frames` holds one id while `:frames` holds more, `:operating` auto-resolved to that sole app frame (rf2-3bu3d.4). |
| `frames/select` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/select-frame! :stories)"}` | Pin the session's default operating frame; subsequent ops use it unless they pass an explicit `frame` arg. `{:ok? true :frame :stories}`. |
| `frames/meta` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/frames-meta :stories)"}` | Flat metadata map for one frame (`rf/frame-meta`): `:id`, `:created-at`, the preset-expansion keys (`:preset`, `:fx-overrides`, `:drain-depth`, …) and lifecycle fields (`:destroyed?`, `:listeners`) at the top level. `{:ok? false :reason :no-such-frame :frame-id id}` when unregistered. See `:rf/frame-meta` in Spec-Schemas. |

To target one op at a non-operating frame without pinning the session, pass the per-call `frame` arg on the dedicated tools (`mcp__re-frame2-pair__dispatch {event: "[:foo]", frame: ":stories"}`, `mcp__re-frame2-pair__snapshot {frames: [":stories"]}`, `mcp__re-frame2-pair__get-path {path: "[...]", frame: ":stories"}`).

## Write

| Op | Invocation | Notes |
|---|---|---|
| `dispatch` | `mcp__re-frame2-pair__dispatch {event: "[:cart/apply-coupon \"SPRING25\"]"}` | Queued by default; pass `sync: true` to force `dispatch-sync`. Skill-issued dispatches carry `:origin :pair` (Spec 002 §Dispatch origin tagging) so `:rf.event/dispatched` traces can be filtered by who fired them. |
| `dispatch --frame` | `mcp__re-frame2-pair__dispatch {event: "[:foo]", frame: ":stories"}` | Targets a specific frame via the `:frame` opt on `rf/dispatch`. |
| `reg-event` / `reg-sub` / `reg-fx` | `mcp__re-frame2-pair__eval-cljs {form: "<full reg-* form>"}` | Re-registration replaces; emits `:rf.registry/handler-replaced` trace (Spec 001 §Hot-reload semantics). Ephemeral. |
| `app-db/reset` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/app-db-reset! ...)"}` | Delegates to `rf/reset-frame-db!` (Tool-Pair §Pair-tool writes) — replaces app-db, records a synthetic `:rf.epoch/db-replaced` epoch, validates against schema, refuses during a drain. Logged explicitly via `tap>` so the user sees what the agent changed. Use sparingly. |
| `repl/eval` | `mcp__re-frame2-pair__eval-cljs {form: "<arbitrary form>"}` | Escape hatch. Prefer structured ops first. Takes the same `frame: ":foo"` arg as every other op (rf2-ntuzf — see *Frame-scoping an eval form* below): the server wraps the form in `(re-frame.core/with-frame :foo <form>)` so `(rf/subscribe ...)` / `(rf/dispatch ...)` inside it resolve against `:foo` rather than the ambient `:rf/default`. |
| `repl/eval-await` | `mcp__re-frame2-pair__eval-cljs {form: "(-> (.layout instance input) (.then transform))", await: true, timeout-ms: 5000}` | Like `repl/eval` but the form may return a Promise — the server awaits it and returns the resolved value as `:value`. Use for `.layout()`, `fetch`, async fns, anything thenable. Rejections surface as `{:ok? false :reason :rf.error/eval-cljs-rejected :rejection "..."}`; timeouts as `{:ok? false :reason :rf.error/eval-cljs-timeout :timeout-ms n}`. Default `:timeout-ms` 5000. Replaces the `js/window.__probe__` mailbox dance (rf2-xn4f9). Composes with `frame:` — but see the async caveat below. |
| `fx-overrides/with` | `mcp__re-frame2-pair__dispatch {event: "[:cart/checkout]", fx-overrides: {":http": ":stub-http"}}` | Per-call `:fx-overrides` (Spec 002 §Per-frame and per-call overrides) — redirect a registered fx to a stub for one experiment, restore on completion. |

### Frame-scoping an eval form (rf2-ntuzf)

`eval-cljs` takes the same `frame: ":foo"` arg the other frame-aware ops do. Pre-rf2-ntuzf it didn't — a supplied form ran against the server's ambient frame context (`:rf/default`), so a `(rf/subscribe ...)` / `(rf/dispatch ...)` inside it silently targeted `:rf/default` even in a multi-frame app, the most common eval-probe footgun. Now the server wraps the form in `(re-frame.core/with-frame :foo <form>)`, binding `*current-frame*` for the form's dynamic extent; the success envelope echoes `:frame :foo`. Prefer `frame:` over hand-wrapping with `with-frame`.

**Async caveat.** `with-frame`'s binding lasts only for the form's **synchronous** evaluation. Once a Promise resolves on a later tick the binding is gone (Spec 002 §with-frame), so a `(rf/dispatch ...)` inside a `.then` callback resolves against the *default* frame, not `:foo`. Long-running async forms that need to dispatch in a callback must capture the frame explicitly — grab a `frame-handle` or wrap the callback via `frame-bound-fn` / `frame-bound-fn*`. Most ad-hoc probes finish synchronously and never hit this.

## Trace

Read-only from the trace stream + epoch history.

| Op | Invocation | Returns |
|---|---|---|
| `trace/buffer` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame.trace.tooling/trace-buffer)"}` | Recent N trace events from the retain-N ring (Spec 009 §Retain-N trace ring buffer). Optional `{:operation _ :op-type _ :since _ :frame _}` filter. **CLJS callers must use the `re-frame.trace.tooling` ns** — `rf/trace-buffer` is a JVM-only alias and silently returns nil in the browser runtime this skill drives. |
| `trace/last-epoch` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/last-epoch)"}` | Most recent `:rf/epoch-record` for the operating frame |
| `trace/last-pair-epoch` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/last-pair-epoch)"}` | Most recent epoch whose `:trigger-event`'s top-level dispatch carried `:origin :pair` (i.e. *this skill* fired it) |
| `trace/epoch` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/epoch-by-id <id>)"}` | The named epoch from the frame's history |
| `trace/dispatch-and-collect` | `mcp__re-frame2-pair__dispatch {event: "[:foo ...]", trace: true}` | Fire + wait for drain-settle + return the resulting `:rf/epoch-record` |
| `trace/recent` | `mcp__re-frame2-pair__trace-window {ms: <ms>}` | Epochs whose `:committed-at` falls inside the last N ms |
| `trace/find-where` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/find-where <pred>)"}` | Most recent epoch matching a predicate — primary forensic op for "when did X happen?" post-mortems |
| `trace/find-all-where` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/find-all-where <pred>)"}` | Every matching epoch, newest first — for trajectories rather than single transitions |
| `trace/cascade` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/cascade-of <dispatch-id>)"}` | Walk `:dispatch-id` / `:parent-dispatch-id` (Spec 009 §Dispatch correlation) to reconstruct the full cascade tree from a root dispatch |
| `trace/configure-privacy` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/configure-privacy! {:include-sensitive? true})"}` | Set the privacy posture for the streaming subscription surface. Default: `{:include-sensitive? false}` — drops `:sensitive? true` trace events before they reach the LLM-facing queue, per [Spec 009 §Privacy](../../../spec/009-Instrumentation.md). Resets on page reload. See [references/vocabulary.md §Privacy posture](vocabulary.md#privacy-posture--sensitive-and-the-streaming-surface). |

## DOM source bridge

**Why this family matters — read first.** When the runtime is configured to annotate rendered DOM (`(rf/configure! :source-coords {:annotate-dom? true})` per Tool-Pair §Source-mapping), every rendered DOM node carries a `data-rf2-source-coord` attribute pointing back to the registration that produced it. The attribute's value resolves via `re-frame2-pair.runtime/parse-rf2-coord` to a structured `{:ns ... :line ... :file ...}` map keyed off the registration's source coords (auto-captured by `reg-*` macros, per Spec 001 §Source-coordinate capture). This gives you a direct, two-way bridge between a live DOM element and the exact line of source code that rendered it.

**Two attribute formats are recognised:**

- `data-rf2-source-coord` — re-frame2's own annotation when `:annotate-dom?` is on. Stable, preferred.
- `data-rc-src` — re-com's debug-instrumentation attribute. The runtime parses both; if both are present on a node, `data-rf2-source-coord` wins.

**Prerequisites — at least one of:**

- re-frame2 source-coord annotation enabled (`(rf/configure! :source-coords {:annotate-dom? true})` at startup), *or*
- re-com debug instrumentation enabled and the call site passed `:src (at)`.

**Degradation is per-element.** When neither is present on a given element, the bridge returns `{:src nil :reason :no-coord-at-this-element}`. When neither annotation is enabled app-wide, every element returns `{:src nil :reason :source-coord-annotation-disabled}`. Tell the user which case they're hitting.

| Op | Invocation | Returns |
|---|---|---|
| `dom/source-at` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-source-at \"#save-button\")"}` (or `(... :last-clicked)`) | `{:ns :line :file}` for a CSS selector, or for the most recently clicked element |
| `dom/find-by-src` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-find-by-src \"view.cljs\" 84)"}` | Live DOM elements rendered by that source line |
| `dom/fire-click-at-src` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-fire-click \"view.cljs\" 84)"}` | Synthesise a click on the element rendered by that line |
| `dom/describe` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-describe \"#save-button\")"}` | Tag, classes, both source-coord attributes, and any registration metadata they resolve to |

## View → rendered content + producing entity (`ui/read`)

**The most common UI-pairing question, first-classed.** The DOM source bridge above maps a gesture/selector → *source coord*; `read-dom` returns *content* by an explicit CSS selector. `ui/read` (MCP tool `read-ui`, rf2-3bu3d.1) does what neither does: given a **view-id** (or a point / CSS selector), return the **rendered subtree** as structured, elided data **PLUS the re-frame2 entity that produced it** — view-id, source-coord, render-key, and the frame's live `subs-read` — in one round-trip. It rides the **same view-id↔DOM map** the Xray pink hover-highlight uses (every registered view's root carries `data-rf-view="<id>"`, per [Spec 006 §View tagging contract](../../../spec/006-ReactiveSubstrate.md#view-tagging-contract-fallback)), so it works on **any** re-frame2 app with **zero testids** — no more guessing selectors then mapping the node back to a view by hand.

Pass **exactly one** entry point (precedence `view-id` > `point` > `selector`). The returned `:text` is routed through `re-frame.core/elide-wire-value` (the same elision `snapshot` / `get-path` use), so large/sensitive content collapses to a `:rf.size/large-elided` marker rather than shipping raw user DOM text. Read-only by construction.

| Op | Invocation | Returns |
|---|---|---|
| `ui/read` by view-id | `mcp__re-frame2-pair__read-ui {view-id: ":my.app/counter"}` | `{:ok? true :via :view-id :entity {:view-id … :source-coord {:ns … :line … :file …} :render-key … :subs-read [[:count] …]} :content {:tag "div" :text "Count: 3" :attrs {…}}}` |
| `ui/read` by point | `mcp__re-frame2-pair__read-ui {point: {x: 120, y: 240}}` | The view under viewport point (120,240): `elementFromPoint` → nearest `[data-rf-view]` ancestor → entity + content |
| `ui/read` by selector | `mcp__re-frame2-pair__read-ui {selector: "#save"}` | `querySelector` → walk up to the producing view → entity + content |

The accepted args (`:additionalProperties false`): `view-id`, `point`, `selector`, `max-text` (per-node char cap, default 2000), `frame`, `build`. Failure modes: `:no-target-arg` (no entry point), `:no-element` (entry point matched nothing), `:rf.error/ui-read-bad-selector` (malformed CSS). A portal / fragment leaf with no tagged view ancestor still returns `:content`, with `:entity {:view-id nil :reason :no-tagged-view-root}`.

## Live watch (push-mode)

Two modes — `subscribe` (push) and `watch-epochs` (poll) — over the same underlying assembled-epoch / trace stream.

**MCP streaming subscriptions (preferred for push-mode).** True server-pushed events delivered via `notifications/progress`, correlated by the call's `progressToken`. See [streaming-subscriptions.md](streaming-subscriptions.md) for topics, filters, termination, and the recipes that prefer this path.

| Op | MCP tool | Behaviour |
|---|---|---|
| `trace/subscribe` | `mcp__re-frame2-pair__subscribe` | Open a streaming subscription on the `:trace`, `:epoch`, `:fx`, or `:error` bus. Returns a `sub-id`; each batch arrives as a `notifications/progress` tick until termination. |
| `trace/unsubscribe` | `mcp__re-frame2-pair__unsubscribe` | Close a subscription by `sub-id`. Idempotent — unknown ids return `:existed? false`. |

**Pull-mode poll (fallback).** The `watch-epochs` MCP tool is poll-only: each call returns the matching epochs that landed *after* `since-id`. To live-watch, call it repeatedly, passing the previous response's `:head-id` as the next `since-id`. Use this when the agent host doesn't surface `notifications/progress` to the model, or when you want a finite, controlled drain rather than a push stream.

The tool's accepted args (`:additionalProperties false` — anything else is rejected): `since-id`, `pred`, `frame`, `limit`, `cursor`, `epochs-mode`, `dedup`, `include-sensitive`, `build`. There is **no** `window-ms`/`count`/`stream`/`stop` arg — those are bash-shim flags only (see the bash-shim appendix).

| Op | Invocation | Behaviour |
|---|---|---|
| `watch/first-poll` | `mcp__re-frame2-pair__watch-epochs {pred: {"event-id-prefix": ":checkout/"}}` | Drains matching epochs already in the ring; response carries `:matches`, `:count`, and `:head-id` |
| `watch/resume` | `mcp__re-frame2-pair__watch-epochs {since-id: "<last-head-id>", pred: {...}}` | Returns only matches that landed after `since-id`; repeat to live-watch |
| `watch/paginate` | `mcp__re-frame2-pair__watch-epochs {pred: {...}, limit: 20, cursor: "<next-cursor>"}` | When a poll's matches exceed `:limit` (default 50), `:next-cursor`/`:has-more?` let you page the rest |

"Run for N matches" and "stream until disconnect" are *loops the agent runs*, not tool args: call `watch-epochs` repeatedly, advancing `since-id`, until you've seen enough matches or the user stops you.

Predicate keys (any combination, inside `pred`): `event-id`, `event-id-prefix`, `effects`, `timing-ms` (e.g. `">100"`), `touches-path`, `sub-ran`, `render`, `origin` (`:app|:pair|:story|:test`), `frame`.

Each call tracks the last seen `:epoch-id` in the operating frame's history via `since-id` and returns everything matching since. See `docs/initial-spec.md` §4.4.

## Hot-reload coordination

Editing source is legitimate and often correct. The protocol is strict — after any source edit, before the next `dispatch` / `trace/*`:

1. Make the edit with `Edit` / `Write`.
2. Call `mcp__re-frame2-pair__tail-build` with a `probe` that verifies the browser has the new code.
3. Only after the probe succeeds do you proceed to `dispatch`, `trace/*`, etc.
4. If the probe times out, treat that as a compile error in the user's code — read the tail output, report it to the user, do *not* retry dispatching.

```
mcp__re-frame2-pair__tail-build {wait-ms: 5000, probe: "(some/probe-form)"}
```

`probe` is a CLJS form chosen to change when the edited code reloads. Good probes for re-frame2:

- After editing a `reg-*` handler: `(re-frame2-pair.runtime/registrar-handler-ref :event <id>)` — compares a hash over `handler-meta`. The underlying `(rf/handler-meta :event :foo)` `:line` / `:column` / `:handler-fn` change after re-registration; capture the meta map's hash before the edit, compare after.
- After editing a `reg-machine`: same shape against `:event` (machines register under `:event` per Spec 005); `(rf/machine-meta :auth)` is the equivalent direct read.
- After editing a view or helper: pick a CLJS form that derefs the view's namespace var (e.g. `(some-ns/my-view)` or `(meta #'some-ns/my-view)`).
- If you don't know a good probe, omit `probe` and the tool falls back to a 300ms timer; the result includes `:soft? true` so you know it's timer-based.

A successful probe-flip also coincides with a `:rf.registry/handler-replaced` trace event arriving in the buffer, so an alternative confirmation is `(filter #(= :rf.registry/handler-replaced (:operation %)) (re-frame.trace.tooling/trace-buffer {:since <pre-edit-id>}))`. Use whichever fits — they're not exclusive.

## Time-travel (epoch restore)

re-frame2 ships first-class time-travel as part of the Tool-Pair contract — no adapter, no internal poking. These ops are **fully implemented** and use only public surfaces.

| Op | Invocation | Purpose |
|---|---|---|
| `epoch/history` | `mcp__re-frame2-pair__eval-cljs {form: "(rf/epoch-history :rf/default)"}` | The full ring of `:rf/epoch-record` values for the frame, oldest-first |
| `epoch/restore` | `mcp__re-frame2-pair__eval-cljs {form: "(rf/restore-epoch :rf/default <epoch-id>)"}` | Rewind the frame's `app-db` to the named epoch's `:db-after`. Returns `true` on success, `false` on any documented failure mode (see below). |
| `epoch/configure` | `mcp__re-frame2-pair__eval-cljs {form: "(rf/configure! :epoch-history {:depth 200})"}` | Bump the ring depth (default 50). |
| `undo/step-back` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/undo-step-back)"}` | Sugar: restore the previous epoch in the operating frame |
| `undo/to-epoch` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/undo-to-epoch <id>)"}` | Sugar over `restore-epoch` for the operating frame |

**Documented failure modes** (Tool-Pair §Time-travel — restore is a no-op on failure):

| Failure | Trace operation | When |
|---|---|---|
| Unknown frame | `:rf.error/no-such-handler` (kind `:frame`) | `frame-id` not registered |
| Unknown epoch | `:rf.epoch/restore-unknown-epoch` | `epoch-id` not in current history (aged out or never recorded) |
| Schema mismatch | `:rf.epoch/restore-schema-mismatch` | `:db-after` no longer validates against currently-registered schemas (a schema was tightened since the snapshot) |
| Missing handler | `:rf.epoch/restore-missing-handler` | DB references a registration id no longer in the registrar (e.g. a machine snapshot whose machine was unregistered) |
| Version mismatch | `:rf.epoch/restore-version-mismatch` | Recorded `:rf/snapshot-version` of an active machine is incompatible with the currently-loaded definition (hot-reload bumped it) |
| Concurrent drain | `:rf.epoch/restore-during-drain` | Called while the frame's run-to-completion drain is in flight |

When `restore-epoch` returns `false`, read the matching trace event from `(re-frame.trace.tooling/trace-buffer {:op-type :error})` to get the structured `:tags`, then report to the user.

**Caveat (always tell the user before restoring):** restore rewinds `app-db` only. Side effects that already fired (HTTP requests sent, navigation pushed, localStorage written, `:dispatch-later` already landed) are *not* undone.

## Bash-shim appendix (not reachable from this skill)

The bash shims under `scripts/` are **retired from this skill's tool surface** — the `allowed-tools:` frontmatter carries no shell tool, so you cannot run them. They remain on disk only for the project's own e2e test harness and ad-hoc shell use outside the skill. Every op above is an MCP tool. This appendix documents the legacy mapping for the harness only; do not reach for it as a "fallback transport".

For the harness, each MCP tool has a behavioural shell counterpart. Note the watch shim carries flags (`--window-ms`, `--count`, `--stream`, `--stop`) that have **no MCP equivalent** — the MCP `watch-epochs` tool is poll-only (`since-id`/`cursor`); those flags exist on the shim alone.

| MCP tool | Shell counterpart (harness only) |
|---|---|
| `eval-cljs {form: "..."}` | `scripts/eval-cljs.sh '<form>'` |
| `dispatch {event: "[:foo]"}` | `scripts/dispatch.sh '[:foo]'` |
| `dispatch {event: "...", sync: true}` | `scripts/dispatch.sh '...' --sync` |
| `dispatch {event: "...", frame: ":foo"}` | `scripts/dispatch.sh '...' --frame :foo` |
| `dispatch {event: "...", trace: true}` | `scripts/dispatch.sh '...' --trace` |
| `dispatch {event: "...", fx-overrides: {...}}` | `scripts/dispatch.sh '...' --fx-override :http=:stub-http` |
| `trace-window {ms: N}` | `scripts/trace-window.sh N` |
| `watch-epochs {pred: {...}, since-id: "..."}` (poll loop) | `scripts/watch-epochs.sh --window-ms ... --event-id-prefix ...` (shim-only `--window-ms`/`--count`/`--stream`/`--stop` modes) |
| `tail-build {wait-ms: ..., probe: "..."}` | `scripts/tail-build.sh --wait-ms ... --probe '...'` |
| `discover-app {}` | `scripts/discover-app.sh` |
| `snapshot {...}` | _MCP-only_ (no shell counterpart; chain individual `eval-cljs.sh` calls for `snapshot`-style mega-reads) |
| `get-path {path: "..."}` | _MCP-only_ (use `eval-cljs.sh '(re-frame2-pair.runtime/app-db-at [...])'` for a coarse equivalent) |
| `subscribe` / `unsubscribe` | _MCP-only_ (push-mode requires `notifications/progress`; the shim approximates pull-mode with `scripts/watch-epochs.sh --stream`) |

For full transport mechanics and the `:app-db` slice modes that only the MCP `snapshot` tool exposes, see [`mcp-transport.md`](mcp-transport.md).

## Dropped from v1 (re-frame-pair) — surfaces with no v2 equivalent

The v1 `re-frame-pair` skill carried a few surfaces that have no direct re-frame2 equivalent today. They have been **dropped** rather than ported:

- **`subs/live` (10x's "currently subscribed query vectors" view)** — replaced by `subs/cache` (`rf/sub-cache`), which is the public Tool-Pair-pinned shape `{query-v {:value v :ref-count n}}`. Same need, different surface.
- **10x's internal epoch-buffer accessor + ring-rollover detection** — gone; replaced by `(rf/epoch-history frame-id)` which is bounded and self-describing (size = `(count history)`, depth = `(:depth (epoch/current-config))`).
- **10x's internal undo / step-back navigation** — gone; replaced by first-class `(rf/restore-epoch frame-id epoch-id)` with six documented failure modes (see [Time-travel](#time-travel-epoch-restore)).
- **`re-com-debug-disabled` heuristic** — kept (re-com is still a valid source-coord source), but the source-coord story now leads with re-frame2's own `:annotate-dom?` annotation; re-com's `data-rc-src` is a fallback rather than the only path.
- **`trace-enabled?` discovery check** — replaced by `interop/debug-enabled?` (the `goog.DEBUG` mirror per Spec 009 §Production builds). Same gate, framework-canonical name.
- **Version-floor enforcement against re-frame-10x / re-com / re-frame** — gone (no re-frame-10x dependency; re-com is optional; re-frame2's version is implicit in the loaded ns).

If during real-world use a surface re-frame2 currently lacks would unblock a recipe (e.g. successful-fx attribution in `:effects` projection, or a stable `:render-key` shape), file a `bd` bead against the spec rather than working around in this skill.
