# Operations catalogue

The op vocabulary the skill operates through. Every op runs over the **MCP transport** (the only one this skill exposes) — see [`mcp-transport.md`](mcp-transport.md). The Invocation column shows the MCP form.

Most ops wrap a call into `re-frame2-pair.runtime`; for those the MCP form is `eval-cljs {form: "<runtime call>"}`. Dedicated MCP tools (`orient`, `dispatch`, `dispatch-dry-run`, `snapshot`, `get-path`, `read-sub`, `read-ui`, `read-dom`, `list-handlers`, `handler-meta`, `trace-window`, `watch-epochs`, `tail-build`, `subscribe`, `unsubscribe`, the operating-frame trio, the time-travel writes) cover the broader concerns.

**Op-selection rule: prefer a structured op WHEN ONE FITS the gesture; for the long tail and recovery, `eval-cljs` is first-class, not a last resort.** A structured op gives a validated, elided, single-round-trip answer for the gesture it owns; the long tail with no dedicated shape (epoch forensics, arbitrary-selector DOM reads, cross-referencing, recovery) is `eval-cljs` work — see [recipes.md §eval-cljs is the workhorse](recipes.md#eval-cljs-is-the-workhorse).

> **Privacy carve-out (applies to every raw-`eval-cljs` row below).** The structured read tools (`snapshot`, `get-path`, `read-sub`, `trace-window`, `watch-epochs`, `subscribe`, and — for its `:db-state-after-simulation` + `:would-fire-effects[*].args` slots — `dispatch-dry-run`) apply the wire-boundary elision walker by default (sensitive slots → `:rf/redacted`, large slots → `:rf.size/large-elided`) under the `--allow-sensitive-reads` gate (OFF by default). The **raw `eval-cljs` forms in this catalogue** (`(re-frame2-pair.runtime/snapshot)`, `(…/app-db-at …)`, `(…/sub-cache)`, `(…/subs-sample …)`, `(re-frame.trace.tooling/trace-buffer :rf/default)`, `(rf/epoch-history …)`, the time-travel / `app-db-reset!` write forms) return their value **un-elided** and are **not** governed by that gate — `eval-cljs` is default-ON (gated only by `--no-eval`). So when the data is a privacy-sensitive app-db path, sub value, trace event, or epoch payload, prefer the structured elided tool; reach for the raw eval form for that data only on explicit user/operator request. See [SKILL.md §Style guidance privacy bullet](../SKILL.md) and [`vocabulary.md` §The raw-eval carve-out](vocabulary.md#the-raw-eval-carve-out--eval-cljs-is-outside-the-structured-guarantee).

## Contents

- [Read](#read)
- [Frames](#frames)
- [Write](#write)
- [Trace](#trace) — trace stream + epoch history
- [DOM source bridge](#dom-source-bridge)
- [Reading what's on screen — two planes (`read-dom` vs `read-ui`)](#reading-whats-on-screen--two-planes-read-dom-vs-read-ui)
  - [View → rendered content + producing entity (`ui/read`)](#view--rendered-content--producing-entity-uiread)
  - [`read-dom` — raw DOM content by explicit CSS selector](#read-dom--raw-dom-content-by-explicit-css-selector)
- [Live watch (push-mode)](#live-watch-push-mode)
- [Signal recording + blocking waits](#signal-recording--blocking-waits)
- [Hot-reload coordination](#hot-reload-coordination)
- [Time-travel (epoch restore)](#time-travel-epoch-restore)
- [Dropped from v1 (re-frame-pair) — surfaces with no v2 equivalent](#dropped-from-v1-re-frame-pair--surfaces-with-no-v2-equivalent)

## Read

| Op | Invocation | Returns |
|---|---|---|
| `app/orient` | `mcp__re-frame2-pair__orient {}` | **App-shape orientation summary in one round-trip** — your first read on an unfamiliar app (see SKILL.md §Orient before you drill for the rule + drill table). Returns `{:ok? true :liveness {...} :frames {:all :app :operating} :app-db-top-keys {...} :registry {:counts :events :subs :fx} :machines [...]}` — compact by construction (counts + ids + per-frame top-keys, not the full app-db; reserved `:rf/*` tool frames excluded). *(runtime fn: `re-frame2-pair.runtime/orient`.)* |
| `app-db/snapshot` | `mcp__re-frame2-pair__snapshot {}` | Current app-db value for the operating frame (via `rf/app-db-value`). **A drill-in, not an orientation tool — `orient` (top of this table) is your first read; reach for `snapshot` only to narrow into a known sub-tree.** Defaults to **`:summary` mode** (top-level shape only) + supports `:path`-slicing — see [`mcp-transport.md` §:app-db slice modes](mcp-transport.md#when-to-use-snapshot-vs-the-per-op-reads). `path: "[]"` (full, unsliced) is a **last resort** (large on any real app frame) and the server REFUSES it against a reserved `:rf/*` tool frame with `{:ok? false :reason :wholesale-read-of-reserved-frame ...}` (a *sliced* read of a tool frame is unaffected) — see SKILL.md §Orient before you drill for why. The underlying runtime form is `(re-frame2-pair.runtime/snapshot)`. |
| `app-db/get` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/app-db-at [:path :to :value])"}` | Path-scoped value (via `rf/snapshot-of`). For targeted reads, prefer the `get-path` tool below — single round-trip, structured `{:exists?}` answer, shared `:path` vocabulary with `snapshot`. |
| `app-db/get-path` | `mcp__re-frame2-pair__get-path {path: "[:cart :items 0 :sku]"}` | Targeted read at `path`. `{:ok? true :exists? true :value <subtree>}` on hit; `{:ok? false :reason :path-not-found :deepest-valid-prefix [...]}` on miss. `:exists?` distinguishes a path that points at `nil` from a missing path. A root `path: "[]"` against a reserved `:rf/*` tool frame (e.g. `frame ":rf/xray"`) is REFUSED by the same server backstop — slice it instead. |
| `app-db/schemas` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/schemas)"}` | Map of `path → schema` from `re-frame.schemas/app-schemas` (not re-exported on `rf/` — owned-ns surface) |
| `registrar/list` | `mcp__re-frame2-pair__list-handlers {kind: "event"}` | The **discovery** surface — every id registered under one kind. `{:ok? true :kind :event :ids [...] :count n}`, ids sorted (stable across calls). Supported kinds: `event` / `sub` / `fx` / `cofx` / `interceptor` / `view` / `frame` / `route` / `flow` / `head` / `error-projector` / `resource` / `mutation` / `resource-scope` / `machine` (the closed registrar set — the `resource` / `mutation` / `resource-scope` kinds are the EP-0016 resources-artefact registrars and appear only on an app that loaded `day8/re-frame2-resources`; app-db schemas are not a registrar kind — use `app-db/schemas` for schemas). `machine` lists handlers flagged `:rf/machine? true`; `interceptor` (EP-0022) enumerates the ids registered with `reg-interceptor` (the descriptor a `:before` / `:after` / `:factory` interceptor was filed under, that event/frame `:interceptors` chains reference); `resource-scope` enumerates the named `reg-resource-scope` resolvers. Prefer this over a `registrar-list` eval — no wide-authority eval round-trip. *(eval fallback: `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/registrar-list :event)"}`.)* |
| `registrar/describe` | `mcp__re-frame2-pair__handler-meta {kind: "event", id: ":cart/apply-coupon"}` | The **drill** surface — registration metadata for one id: source-coord (`:ns` / `:line` / `:column` / `:file`), `:doc`, `:tags`, plus an `:rf.source/uri` the host renders as a clickable jump-to-editor link. `{:ok? true :kind k :id i ...}` on a hit; `{:ok? false :reason :not-registered :kind k :id i}` on a miss. Pass composite-key sub ids as the vector-string form (`id: "[:rf/composite :x]"`). The `machine` kind routes through `re-frame.machines/machine-meta` (not re-exported on `rf/`); `resource` / `mutation` / `resource-scope` surface the resources-artefact registration meta (a `resource` / `mutation` reports its scope policy + tags; a `resource-scope` reports its declared `:inputs`, the `:whole-db?` flag for the whole-db-sugar form, and the resolved scope metadata — nested resolver/request functions are stripped from the returned meta so the payload stays data); others through `rf/handler-meta`. Prefer this over a `registrar-describe` eval — targeted read, no eval authority. *(eval fallback: `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/registrar-describe :event :cart/apply-coupon)"}` — also surfaces the retained source form when present.)* |

> **EP-0016 resources registrars (`resource` / `mutation` / `resource-scope`).** On a resources app, discover the server-state surface with `list-handlers`, then drill with `handler-meta`:
>
> - `list-handlers {kind: "resource-scope"}` → `{:ok? true :kind :resource-scope :ids [:realworld/session] :count 1}` — the named scope resolvers.
> - `handler-meta {kind: "resource-scope", id: ":realworld/session"}` → the resolver's declared `:inputs` (names ↦ source descriptors), the `:whole-db?` cost flag, and resolved scope metadata (nested functions stripped) — so you can see *which db facts decide the scope* without reading source.
> - `list-handlers {kind: "resource"}` / `{kind: "mutation"}` enumerate the registered reads / writes; `handler-meta` on one reports its scope policy + tag producer.
| `subs/cache` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/sub-cache)"}` | wraps `re-frame.subs.tooling/sub-cache-snapshot` — `{query-v {:value v :ref-count n}}` for every materialised subscription (CLJS-only) |
| `subs/read` | `mcp__re-frame2-pair__read-sub {sub: "[:cart/total]"}` | The **validated one-shot subscription read** — PREFER this over a raw `eval-cljs` `@(rf/subscribe ...)`. The `sub` arg is parsed as EDN once and MUST be a vector; the sub-id is **validated against the live `:sub` registrar** (unknown → `:reason :unknown-id` + `:nearest`, NOT subscribed — never a silent nil), the value is subscribed + deref'd once and **elided** server-side (declared-sensitive → `:rf/redacted`, declared-large → `:rf.size/large-elided`). `{:ok? true :query-v [...] :frame <id> :value <v> :elision true}` on a hit; structured `:isError` (`:not-a-sub-vector` / `:invalid-sub-edn` / `:unknown-id` / `:ambiguous-frame` / `:sub-error`) otherwise. No-silent-swallow parity with `dispatch`. *(runtime fn: `re-frame2-pair.runtime/read-sub!`.)* |
| `subs/sample` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/subs-sample [:cart/total])"}` | One-shot value via `rf/compute-sub` (no cache mutation) or `@(rf/subscribe ...)`. Unvalidated + un-elided — prefer `subs/read` above. |
| `machines/list` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/machines-list)"}` | Machine ids (`re-frame.machines/machines`) |
| `machines/describe` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/machine-describe :auth)"}` | The registered spec map (`re-frame.machines/machine-meta`) |
| `machines/state` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/machine-state :auth)"}` | Current `:rf/machine-snapshot` from `(get-in (rf/runtime-db-value frame-id) [:rf.runtime/machines :snapshots :auth])` — snapshots live in the **runtime-db** partition, read via `rf/runtime-db-value`. The snapshot shape is `{:state :data :tags? :meta?}` (Spec 005 §Snapshot shape): `:state` is the FSM-keyword / compound-path / parallel-region map; `:data` the machine's private memory; `:tags` the runtime-projected union of active states' `:tags` (optional); `:meta` carries `:rf/snapshot-version` (Spec-Schemas §`:rf/machine-snapshot`). The runtime also stamps closed `:rf/*` slots (e.g. `:rf/spawn-counter` at the root) — framework-owned, read-only. |

## Frames

Set and inspect the operating frame (SKILL.md §Multi-frame model). Every read/write op resolves an operating frame: explicit per-call `frame: ":foo"` wins, else the session pin, else the sole registered **app frame**, else `:ambiguous-frame`. **Both reads and writes refuse** rather than guess — the read helpers (`subs-sample` / `read-sub!` / `sub-cache-info`) return `:reason :ambiguous-frame` rather than silently reading `:rf/default`.

**The public address is the frame.** You target a **frame** id in a single process-local frame-id space (full EP-0023 model in SKILL.md §Multi-frame model). `set-operating-frame` pins a frame; with exactly one app frame, tier-3 auto-selects it. No realm/container coordinate.

**Reserved tool frames are excluded from the ambiguity count.** `:rf/*` reserved tool frames — Xray's `:rf/xray`, an SSR slot, … (per [Conventions.md §Reserved namespaces](../../../spec/Conventions.md)) — are devtool surfaces, not the app you pair against, so they're removed before counting. A single-app session also carrying an `:rf/xray` frame (the common Xray case) has exactly one app frame and resolves to it automatically — **no `frames/select` needed**. Only two-plus genuine *app* frames are ambiguous. Carve-out: `:rf/default` shares the `:rf/*` root but is an **ordinary app frame id with no framework privilege** (EP-0002 / Conventions §Reserved namespaces — not auto-created, not a fallback, a legal id an app may explicitly register), so it always counts as an app frame, never a tool frame.

**Prefer the dedicated operating-frame tools** to set/read the session pin — `set-operating-frame {frame: ":foo"}` / `reset-operating-frame {}` / `get-operating-frame {}` — the wire-level surfacing of the pin and the escape from the tier-4 `:ambiguous-frame` refusal (see SKILL.md §Multi-frame model). The eval-based helpers below wrap the same preload functions (`select-frame!` / `current-frame` / `frame-meta`); reach for them only for `frames/meta` (no dedicated tool) or when you want the raw runtime return shape.

| Op | Invocation | Returns |
|---|---|---|
| `frames/list` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/frames-list)"}` | `{:ok? true :frames [...] :app-frames [...] :selected <pinned-or-nil> :operating <resolved-or-nil>}` — `:frames` is every registered, non-destroyed frame (`rf/frame-ids`); `:app-frames` is the reserved-frame-aware view (`:frames` minus `:rf/*` tool frames like `:rf/xray`). When `:app-frames` holds one id while `:frames` holds more, `:operating` auto-resolved to that sole app frame. |
| `frames/select` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/select-frame! :stories)"}` | Pin the session's default operating frame; subsequent ops use it unless they pass an explicit `frame` arg. `{:ok? true :frame :stories}`. |
| `frames/meta` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/frames-meta :stories)"}` | Flat metadata map for one frame (`rf/frame-meta`): `:id`, `:created-at`, the preset-expansion keys (`:preset`, `:fx-overrides`, `:drain-depth`, …) and lifecycle fields (`:destroyed?`, `:listeners`) at the top level. `{:ok? false :reason :no-such-frame :frame-id id}` when unregistered. See `:rf/frame-meta` in Spec-Schemas. |
| `frames/describe-image` | `mcp__re-frame2-pair__describe-image {frame: ":app/main"}` | **What behaviour does THIS frame run, and where did each piece come from?** — the EP-0023 forward read (Use-Case 7) over the public `(rf/frame-generation frame)` (API §Registration). A frame runs a composed **image** (selected registrations from one-or-more namespaces / inline sections + framework standards), and the *same* `(kind, id)` can resolve differently per frame, so this shows the **selected universe the frame actually runs** — not the process-wide registrar union `list-handlers` reports. `{:ok? true :frame <id> :images [...] :kinds [...] :counts {<kind> N…}}`; pass `include-ns: true` to also get `:registrations {[kind id] {:source … :ns …}…}` — every selected `(kind, id)` with the provenance/standard coordinate that **won** the resolution (cross-image shadows, which source supplied it). Frame resolution mirrors every read op — omit `frame` for the operating frame; a multi-frame session with no pin returns `:reason :ambiguous-frame`. Drill one `(kind, id)` with the **frame-targeted** `handler-meta {frame: ":app/main", kind: "event", id: ":user/login"}` (API §Public registrar query API — the map-shaped arity resolves through the frame's sealed image generation, surfacing per-frame provenance). Runtime threw → `:reason :describe-image-failed` (carries `:rf.error/frame-no-generation` when an explicit `frame` names no live frame carrying a generation). |

`describe-image` is the per-frame **image** read; `list-handlers` is the process-wide **registrar** read. Reach for `describe-image` when you need to know *which* registrations a specific frame resolved (the common Story / multi-image case where two frames run different handlers for the same id), and `list-handlers` when you just want every id registered process-wide. `orient` gives registry **counts** across the app; `describe-image` gives the selected set + provenance for **one** frame.

To target one op at a non-operating frame without pinning the session, pass the per-call `frame` arg on the dedicated tools (`mcp__re-frame2-pair__dispatch {event: "[:foo]", frame: ":stories"}`, `mcp__re-frame2-pair__snapshot {frames: [":stories"]}`, `mcp__re-frame2-pair__get-path {path: "[...]", frame: ":stories"}`).

## Write

| Op | Invocation | Notes |
|---|---|---|
| `dispatch` | `mcp__re-frame2-pair__dispatch {event: "[:cart/apply-coupon \"SPRING25\"]"}` | **Returns the consequence by default**: `{:epoch-id :db-changed? :changed-paths :effects-fired :no-op? :cascade-summary}` — `dispatch → verify` in one call; a no-op visibly returns `:db-changed? false :no-op? true`. The event-id is **validated** against the `:event` registrar (unknown → `:reason :unknown-id` + `:nearest`, NOT dispatched — never a silent no-op). Skill-issued dispatches carry `:origin :pair` (Spec 002 §Dispatch origin tagging). Modes: `sync: true` (force `dispatch-sync`), `queued: true` (async transport-ack), `trace: true` (full epoch), `await-render: true` (resolve after the substrate flushed + next paint scheduled), `settle: true` (the most complete shape — synchronously flush renders + return the full epoch incl. `:render-events`). **Reproducible dispatch:** `cofx: "{:rf/time-ms 1781078400123}"` (EDN map string, EP-0010 recording / EP-0017 authoring) pins the scripted recordable coeffects — it threads verbatim to the dispatch envelope's flat `:rf.cofx` map (the router fills only a missing `:rf/time-ms`, never overwrites a supplied value; `:rf/time-ms` must be an integer, a malformed map returns `:reason :invalid-cofx` without dispatching), so every durable wall-clock read (`:created-at`, resource `:loaded-at`, machine snapshot times) reads the scripted value and the event replays to the SAME output run-to-run. Use it to assert a durable write deterministically. Add owner-qualified app facts the same way — e.g. `"{:rf/time-ms 1781078400123 :counter/delta 4}"` — to script any recordable coeffect a handler declares. Composes with `sync` / `trace` / `settle` / `await-render` / `queued` and with `frame` / `fx-overrides`. |
| `dispatch --frame` | `mcp__re-frame2-pair__dispatch {event: "[:foo]", frame: ":stories"}` | Targets a specific frame via the `:frame` opt on `rf/dispatch`. |
| `dispatch-dry-run` | `mcp__re-frame2-pair__dispatch-dry-run {event: "[:cart/checkout]"}` | **Simulate a cascade WITHOUT committing** — "experiment without consequences". Full reducer + interceptor + schema validation + machine transitions + sub-runs + renders all run; **NO fx execute** (each registered fx is redirected to a recording stub) and the framework rolls back the app-db via `restore-epoch`. Returns the same `:cascade-summary` shape as `dispatch` plus `:rolled-back? true` and `:would-fire-effects [{:fx-id :args}...]` enumerating every fx that WOULD have fired (with args) so you reason about real-world impact without paying it. Composes with `:fx-overrides` (user overrides win, e.g. a canned http stub) without losing the rollback guarantee. **NOT** `--allow-writes`-gated — its contract IS "no observable effect". **Privacy:** dry-run is an AI-facing READ surface — its `:db-state-after-simulation` + each `:would-fire-effects[*].args` slot elide server-side under the same `--allow-sensitive-reads` posture as `snapshot` / `get-path` (the `:cascade-summary` projection rides through unwalked); see the §Privacy carve-out above and [vocabulary.md §Privacy posture](vocabulary.md#privacy-posture--sensitive-and-the-raw-eval-carve-out). |
| `reg-event` / `reg-sub` / `reg-fx` | `mcp__re-frame2-pair__eval-cljs {form: "<full reg-* form>"}` | Re-registration replaces; emits `:rf.registry/handler-replaced` trace (Spec 001 §Hot-reload semantics). Ephemeral. **Foot-gun — a `reg-event` handler returning `{:db <bare-map>}` REPLACES app-db wholesale (does NOT merge)**, so a throwaway probe driven by a live `dispatch` nukes the frame's app-db. Prefer `dispatch-dry-run` (rolls back) or `{:db (assoc db …)}`, never a bare map — full treatment in [recipes.md §Experiment loop](recipes.md#experiment-loop). |
| `app-db/reset` | `mcp__re-frame2-pair__replace-app-db {db: "{...}"}` | **The canonical state-injection write** (Tool-Pair §Pair-tool writes) — replaces app-db with an arbitrary EDN value the runtime never recorded, records a synthetic `:rf.epoch/db-replaced` epoch so the injection itself is undoable, validates against schema, refuses during a drain (`:reason :reset-rejected`), and logs via `tap>`. Returns a structured `:cascade-summary`. `--allow-writes`-gated (default OFF — see §Time-travel for the gate + the `eval-cljs (re-frame2-pair.runtime/app-db-reset! ...)` backstop). Use sparingly. |
| `repl/eval` | `mcp__re-frame2-pair__eval-cljs {form: "<arbitrary form>"}` | The exploratory **workhorse**, not merely an escape hatch — prefer a structured op when one fits the gesture, but reach for `eval-cljs` first-class for the long tail (epoch forensics, arbitrary-selector DOM reads, cross-referencing reads) and for recovery (re-run a blank/errored structured read here to confirm the runtime answers). Takes the same `frame: ":foo"` arg as every other op (see *Frame-scoping an eval form* below): the server wraps the form in `(re-frame.core/with-frame :foo <form>)` so `(rf/subscribe ...)` / `(rf/dispatch ...)` inside it resolve against `:foo` — without it a frame-scoped op has no carried frame and raises `:rf.error/no-frame-context` (EP-0002 — there is no ambient default to resolve to). |
| `repl/eval-await` | `mcp__re-frame2-pair__eval-cljs {form: "(-> (.layout instance input) (.then transform))", await: true, timeout-ms: 5000}` | Like `repl/eval` but the form may return a Promise — the server awaits it and returns the resolved value as `:value`. Use for `.layout()`, `fetch`, async fns, anything thenable. Rejections surface as `{:ok? false :reason :rf.error/eval-cljs-rejected :rejection "..."}`; timeouts as `{:ok? false :reason :rf.error/eval-cljs-timeout :timeout-ms n}`. Default `:timeout-ms` 5000. Replaces the `js/window.__probe__` mailbox dance. Composes with `frame:` — but see the async caveat below. |
| `fx-overrides/with` | `mcp__re-frame2-pair__dispatch {event: "[:cart/checkout]", fx-overrides: {":http": ":stub-http"}}` | Per-call `:fx-overrides` (Spec 002 §Per-frame and per-call overrides) — redirect a registered fx to a stub for one experiment, restore on completion. |

### Frame-scoping an eval form

`eval-cljs` takes the same `frame: ":foo"` arg the other frame-aware ops do. Supply it for any form that subscribes or dispatches: without a frame scope, a `(rf/subscribe ...)` / `(rf/dispatch ...)` inside the form has no frame to resolve — the most common eval-probe footgun. The server wraps the form in `(re-frame.core/with-frame :foo <form>)`, binding `*current-frame*` for the form's dynamic extent; the success envelope echoes `:frame :foo`. A frame-scoped op with no carried frame raises `:rf.error/no-frame-context` — the runtime never synthesises `:rf/default`. Prefer `frame:` over hand-wrapping with `with-frame`.

**Async caveat.** `with-frame`'s binding lasts only for the form's **synchronous** evaluation. Once a Promise resolves on a later tick the binding is gone (Spec 002 §with-frame), so a `(rf/dispatch ...)` inside a `.then` callback has no carried frame and raises `:rf.error/no-frame-context` (EP-0002 — it does not fall back to a default). Long-running async forms that need to dispatch in a callback must capture the frame explicitly — grab a frame api (`rf/capture-frame`) in scope and call its `:dispatch` op. Most ad-hoc probes finish synchronously and never hit this.

## Trace

Read-only from the trace stream + epoch history.

| Op | Invocation | Returns |
|---|---|---|
| `trace/buffer` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame.trace.tooling/trace-buffer :rf/default)"}` | Recent retained cascade bundles from the named frame's retain-N ring (Spec 009 §Retain-N trace ring buffer). **Frame-id is the first positional arg** — a missing/destroyed frame silently returns `[]`, so never pass an opts map as the sole arg. For raw events use `{:flat true}`; the `:operation` / `:op-type` / `:since` / `:severity` filter keys are **`:flat-only`** (e.g. `(trace-buffer :rf/default {:flat true :op-type :error})`). **CLJS callers must use the `re-frame.trace.tooling` ns** — `rf/trace-buffer` is a JVM-only alias and silently returns nil in the browser runtime this skill drives. |
| `trace/last-epoch` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/last-epoch)"}` | Most recent `:rf/epoch-record` for the operating frame |
| `trace/last-pair-epoch` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/last-pair-epoch)"}` | Most recent epoch whose `:trigger-event`'s top-level dispatch carried `:origin :pair` (i.e. *this skill* fired it) |
| `trace/epoch` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/epoch-by-id <id>)"}` | The named epoch from the frame's history |
| `trace/dispatch-and-collect` | `mcp__re-frame2-pair__dispatch {event: "[:foo ...]", trace: true}` | Fire + wait for drain-settle + return the resulting `:rf/epoch-record` |
| `trace/recent` | `mcp__re-frame2-pair__trace-window {ms: <ms>}` | Epochs whose `:committed-at` falls inside the last N ms |
| `trace/find-where` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/find-where <pred>)"}` | Most recent epoch matching a predicate — primary forensic op for "when did X happen?" post-mortems |
| `trace/find-all-where` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/find-all-where <pred>)"}` | Every matching epoch, newest first — for trajectories rather than single transitions |
| `trace/cascade` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/cascade-of <dispatch-id>)"}` | Walk `:dispatch-id` / `:parent-dispatch-id` (Spec 009 §Dispatch correlation) to reconstruct the full cascade tree from a root dispatch |
| `trace/configure-privacy` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/configure-privacy! {:include-sensitive? true})"}` | Set the privacy posture for the streaming subscription surface. Default: `{:include-sensitive? false}` — drops `:sensitive? true` trace events before they reach the LLM-facing queue, per [Spec 009 §Privacy](../../../spec/009-Instrumentation.md). Resets on page reload. See [references/vocabulary.md §Privacy posture](vocabulary.md#privacy-posture--sensitive-and-the-raw-eval-carve-out). |

## DOM source bridge

**Why this family matters — read first.** In a debug build, re-frame2 injects a `data-rf2-source-coord` attribute on every **registered view's** root DOM element pointing back to the registration that produced it (mandatory per Tool-Pair §Source-mapping / Spec 006 §Source-coord annotation, gated on `interop/debug-enabled?` — **no** `configure!` knob, not user-enabled). `re-frame2-pair.runtime/parse-rf2-coord` parses that attribute into `{:ns :handler-id :line :col}` (the registration's source coords, auto-captured by `reg-*` macros per Spec 001 §Source-coordinate capture) — a direct two-way bridge between a live DOM element and the exact source line that rendered it. (`:file` is not on the raw attribute; it arrives only when the coord is enriched through `handler-meta`, as `read-ui`'s `:source-coord` does.)

**Two attribute formats are recognised:**

- `data-rf2-source-coord` — re-frame2's own annotation, present on registered-view roots in debug builds. Stable, preferred.
- `data-rc-src` — re-com's debug-instrumentation attribute. The runtime parses both; if both are present on a node, `data-rf2-source-coord` wins.

**Prerequisites — at least one of:**

- a debug build with the element produced by a **registered view** (`reg-view`) on a DOM-capable adapter (annotation is mandatory there — no opt-in needed), *or*
- re-com debug instrumentation enabled and the call site passed `:src (at)`.

**Degradation is per-element.** Neither attribute present on an element → `{:src nil :reason :no-coord-at-this-element}` (e.g. an anonymous Reagent fn, not a registered view). No source-coord attributes reaching the DOM app-wide (production build, or no registered-view / re-com coverage) → every element returns `{:src nil :reason :source-coord-annotation-disabled}` — diagnose by checking registered-view coverage, DOM-capable adapter, debug build (`goog.DEBUG`), or a re-com `:src (at)` fallback. Tell the user which case they're hitting.

| Op | Invocation | Returns |
|---|---|---|
| `dom/source-at` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-source-at \"#save-button\")"}` (or `(... :last-clicked)`) | `{:ok? true :src <coord>}` for a CSS selector (or the most recently clicked element) — `:src` is `{:ns :handler-id :line :col}` when the re-frame2 attribute matched, `{:file :line :column}` on the re-com `data-rc-src` fallback |
| `dom/find-by-src` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-find-by-src \"view.cljs\" 84)"}` | Live DOM elements rendered by that source line |
| `dom/fire-click-at-src` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-fire-click \"view.cljs\" 84)"}` | Synthesise a click on the element rendered by that line |
| `dom/describe` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-describe \"#save-button\")"}` | Tag, classes, both source-coord attributes, and any registration metadata they resolve to |

## Reading what's on screen — two planes (`read-dom` vs `read-ui`)

Two rendered-state reads at **different layers** — NOT duplicates:

- **`read-dom` = the raw DOM plane.** A CSS selector → matched nodes `{:tag :text :attrs}`. Multi-node, exact, **no re-frame2 awareness**. *"What does this exact node SAY?"*
- **`read-ui` = the re-frame2 view plane.** Rides the `data-rf-view` map → content **PLUS the producing entity** (view-id, source-coord, subs-read, render-key). *"What is this view, and what produced it?"*

**When to use which:** `read-dom` when you already have a CSS selector, want raw content across N matched nodes, and don't need provenance; `read-ui` when you want a view's content **and** its re-frame2 entity (which subs feed it, where it's defined) in one round-trip — or when you only have a view-id / screen point rather than a selector. Both apply per-node text caps at the source and emit the same `:rf.size/large-elided` marker for over-cap text. Both call the same preloaded runtime ns (`re-frame2-pair.runtime/dom-read` / `…/ui-read`) and share one per-node projection, so content shapes stay aligned.

### View → rendered content + producing entity (`ui/read`)

**The most common UI-pairing question, first-classed.** The DOM source bridge above maps a gesture/selector → *source coord*; `read-dom` returns raw *content* by explicit CSS selector. `ui/read` (MCP tool `read-ui`) does what neither does: given a **view-id** (or point / CSS selector), return the **rendered subtree** as structured, elided data **PLUS the re-frame2 entity that produced it** — view-id, source-coord, render-key, and the frame's live `subs-read` — in one round-trip. It rides the **same view-id↔DOM map** the Xray pink hover-highlight uses (every registered view's root carries `data-rf-view="<id>"`, per [Spec 006 §View tagging contract](../../../spec/006-ReactiveSubstrate.md#view-tagging-contract-fallback)), so it works on **any** re-frame2 app with **zero testids** — no guessing selectors then mapping the node back to a view by hand.

Pass **exactly one** entry point (precedence `view-id` > `point` > `selector`). The returned `:text` routes through `re-frame.core/elide-wire-value` (the elision `snapshot` / `get-path` use), so large/sensitive content collapses to `:rf.size/large-elided` rather than shipping raw user DOM text. Read-only by construction.

| Op | Invocation | Returns |
|---|---|---|
| `ui/read` by view-id | `mcp__re-frame2-pair__read-ui {view-id: ":my.app/counter"}` | `{:ok? true :via :view-id :entity {:view-id … :source-coord {:ns … :line … :file …} :render-key … :subs-read [[:count] …]} :content {:tag "div" :text "Count: 3" :attrs {…}}}` |
| `ui/read` by point | `mcp__re-frame2-pair__read-ui {point: {x: 120, y: 240}}` | The view under viewport point (120,240): `elementFromPoint` → nearest `[data-rf-view]` ancestor → entity + content |
| `ui/read` by selector | `mcp__re-frame2-pair__read-ui {selector: "#save"}` | `querySelector` → walk up to the producing view → entity + content |

The accepted args (`:additionalProperties false`): `view-id`, `point`, `selector`, `max-text` (per-node char cap, default 2000), `frame`, `build`. Failure modes: `:no-target-arg` (no entry point), `:no-element` (entry point matched nothing), `:rf.error/ui-read-bad-selector` (malformed CSS). A portal / fragment leaf with no tagged view ancestor still returns `:content`, with `:entity {:view-id nil :reason :no-tagged-view-root}`.

### `read-dom` — raw DOM content by explicit CSS selector

`read-dom` is the **raw DOM plane** read by explicit CSS selector — the answer to *"did the UI update?"* / *"what does the rendered node SAY?"* when you have a selector and don't need entity provenance (for that, use `read-ui` above). The data-plane reads (`snapshot` / `get-path` / `read-sub` / `trace-window`) tell you what's in `app-db` and the trace; `read-dom` tells you what the app actually **put on screen**. Read-only by construction (only `querySelectorAll` + `textContent` / attribute strings). Pairs with `dispatch :await-render` / `:settle` for a deterministic *dispatch → settle → read-dom* observe.

| Op | Invocation | Returns |
|---|---|---|
| `read-dom` | `mcp__re-frame2-pair__read-dom {selector: "#app .counter"}` | `{:ok? true :selector … :count N :truncated? bool :nodes [{:tag "div" :text "Count: 3" :attrs {"class" "counter" "data-count" "3"}}]}` — `:count` is the full pre-`:limit` tally |
| `read-dom` scoped | `mcp__re-frame2-pair__read-dom {selector: ".card", sub-selector: ".title"}` | `:sub-selector` runs RELATIVE to each matched node to narrow a coarse match (a card) to its inner parts |
| `read-dom` specific attrs | `mcp__re-frame2-pair__read-dom {selector: "input[name=email]", attrs: ["value", "data-valid"]}` | Omit `:attrs` and a curated default set rides PLUS a `data-*` / `aria-*` sweep (the re-frame2 view-plane idiom for surfacing rendered state) |

Caps are applied **at the source** (browser-side) so only bounded EDN crosses the wire: `:max-text` (per-node text cap, default 2000 — over-cap → `{:rf.size/large-elided {:type :dom-text :chars N :preview "…"}}`) and `:limit` (matched-node cap, default 50; `:truncated? true` when more matched than returned). Failure modes: `:rf.error/read-dom-bad-selector` (malformed CSS); a no-match returns `{:ok? true :count 0 :nodes []}`.

## Live watch (push-mode)

Two modes — `subscribe` (push) and `watch-epochs` (poll) — over the same underlying assembled-epoch / trace stream.

**MCP streaming subscriptions (preferred for push-mode).** True server-pushed events delivered via `notifications/progress`, correlated by the call's `progressToken`. See [streaming-subscriptions.md](streaming-subscriptions.md) for topics, filters, termination, and the recipes that prefer this path.

| Op | MCP tool | Behaviour |
|---|---|---|
| `trace/subscribe` | `mcp__re-frame2-pair__subscribe` | Open a streaming subscription on the `:trace`, `:epoch`, `:fx`, `:error`, or `:frameless` bus. Returns a `sub-id`; each batch arrives as a `notifications/progress` tick until termination. `:frameless` emits flat event batches (not cascade bundles) and is the right topic for **registration, reload, REPL, and other unjoined lifecycle events** that carry no `:rf.trace/dispatch-id` — the only live channel for them. |
| `trace/unsubscribe` | `mcp__re-frame2-pair__unsubscribe` | Close a subscription by `sub-id`. Idempotent — unknown ids return `:existed? false`. |

**Pull-mode poll (fallback).** The `watch-epochs` MCP tool is poll-only: each call returns the matching epochs that landed *after* `since-id`. To live-watch, call it repeatedly, passing the previous response's `:head-id` as the next `since-id`. Use this when the agent host doesn't surface `notifications/progress` to the model, or when you want a finite, controlled drain rather than a push stream.

The tool's accepted args (`:additionalProperties false` — anything else is rejected): `since-id`, `pred`, `frame`, `limit`, `cursor`, `epochs-mode`, `dedup`, `include-sensitive`, `build`. There is **no** `window-ms`/`count`/`stream`/`stop` arg — the MCP `watch-epochs` tool is poll-only (`since-id`/`cursor`); "run for N matches" / "stream until disconnect" are loops the agent runs, not tool args.

| Op | Invocation | Behaviour |
|---|---|---|
| `watch/first-poll` | `mcp__re-frame2-pair__watch-epochs {pred: {"event-id-prefix": ":checkout/"}}` | Drains matching epochs already in the ring; response carries `:matches`, `:count`, and `:head-id` |
| `watch/resume` | `mcp__re-frame2-pair__watch-epochs {since-id: "<last-head-id>", pred: {...}}` | Returns only matches that landed after `since-id`; repeat to live-watch |
| `watch/paginate` | `mcp__re-frame2-pair__watch-epochs {pred: {...}, limit: 20, cursor: "<next-cursor>"}` | When a poll's matches exceed `:limit` (default 50), `:next-cursor`/`:has-more?` let you page the rest |

"Run for N matches" and "stream until disconnect" are *loops the agent runs*, not tool args: call `watch-epochs` repeatedly, advancing `since-id`, until you've seen enough matches or the user stops you.

Predicate keys (any combination, inside `pred`): `event-id`, `event-id-prefix`, `effects`, `timing-ms` (e.g. `">100"`), `touches-path`, `sub-ran`, `render`, `origin` (`:app|:pair|:story|:test`), `frame`.

Each call tracks the last seen `:epoch-id` in the operating frame's history via `since-id` and returns everything matching since.

## Signal recording + blocking waits

The push/poll watch ops above stream *epochs* — the cascade unit. The `record` / `read-recording` / `watch-until` family observes arbitrary **signals** (an app-db path, sub value, DOM node's text/attribute, the focus slot) across a window of **real human interaction** — the canonical move for catching intermittent / human-in-the-loop bugs (render-timing races reproducible only under real mouse input); the runtime solves the rAF-sampling / dedup / teardown footguns once. All three are **read-only**: never dispatch, mutate app-db, or write the DOM.

**SIGNAL shapes** (each a map naming one observable, shared by `record` + `watch-until`):

| Signal | Reads |
|---|---|
| `{:app-db [path]}` | `(get-in app-db path)` |
| `{:sub [query-v]}` | a subscription deref |
| `{:dom "sel"}` / `{:dom "sel" :attr "name"}` | a node's `textContent` or attribute string |
| `{:focus true}` | a stable descriptor of `document.activeElement` (the focus slot) |

**PRED shapes** (a DATA predicate map over the positional sample map `{<signal-index> <value>}` — compiled to a pure value-comparison fn, no host source crosses the wire): `{:signal 0 :equals <v>}` / `{:signal 0 :changed true}` / `{:signal 0 :path [...] :equals <v>}` / `{:signal 0 :contains <substr>}` / `{:signal 0}` (any non-nil).

| Op | Invocation | Behaviour |
|---|---|---|
| `record` | `mcp__re-frame2-pair__record {signals: "[{:focus true} {:dom \"#count\"}]", stop: {:ms 15000}}` | Returns IMMEDIATELY with a `:recording-id`; the recording runs in the background, samples each signal once per animation frame, records each CHANGE (with a `:t` wall-clock ms + `:frame` rAF-counter), dedups (a steady signal → ONE baseline entry), and tears down at the STOP condition. STOP (first to trip wins; defaults to `{:ms 30000}`): `{:ms N}` / `{:changes N}` / `{:pred {...}}`. |
| `read-recording` | `mcp__re-frame2-pair__read-recording {recording-id: "rec-abc"}` | Read the change-log: `{:ok? true :recording-id :status :recording\|:stopped :stopped-reason :frames-sampled :count :entries [{:i :signal :value :t :frame}...]}`. Pass `drain: true` for the live-watch idiom (consume buffered entries, recording keeps running) or `stop: true` to read-and-close. Unknown id → `:reason :no-such-recording`. |
| `watch-until` | `mcp__re-frame2-pair__watch-until {signals: "[{:app-db [:upload :status]}]", pred: {:signal 0 :equals :done}}` | **Blocks** until the predicate holds — the synchronous counterpart to `record`. Like `tail-build`, the server polls a cheap runtime read (~100ms cadence) until the condition trips or `timeout-ms` (default 30000) elapses. `{:ok? true :held? true :elapsed-ms :sample :t}` on success; `{:ok? false :reason :watch-timeout :timed-out? true :last-sample {...}}` on timeout (`:last-sample` shows how close it got). Missing `:pred` → `:reason :missing-pred`. |

Use `record` + `read-recording` when you want to **capture** what happened across an open-ended interaction window (then read it back); use `watch-until` when you want to **block** until a specific condition lands before the next op (e.g. "wait until focus moves into the dialog, then read-dom it").

## Hot-reload coordination

Editing source is legitimate and often correct. The protocol is strict — after any source edit, before the next `dispatch` / `trace/*`:

1. Make the edit with `Edit` / `Write`.
2. Call `mcp__re-frame2-pair__tail-build` with a `probe` that verifies the browser has the new code.
3. Only after the probe succeeds (`{:ok? true …}`, the probe value flipped) do you proceed to `dispatch`, `trace/*`, etc.
4. **`tail-build` does not tail the shadow-cljs server log** (the name is historical) — it polls your `probe` form and hands back *diagnostics*. So **branch on the result, don't treat every non-success as a compile error**:
   - `{:ok? false :reason :timed-out :probe-values {:initial … :final …} :note …}` — the probe was reachable but its value never changed in `wait-ms`. The `:note` lists the candidate causes; use `:probe-values` to disambiguate. **If `:initial` and `:final` are equal, the reload may have landed but your probe doesn't discriminate it** — pick a better probe (one whose value provably changes on reload, e.g. a `handler-meta` hash) or ask the user to refresh the browser, then re-probe. Do *not* report a compile error on this evidence alone.
   - `{:ok? false :reason :probe-errored :probe-error …}` — the probe form raised on every iteration. Treat this as a **malformed probe** (typo, dotted host-interop against a missing var), not a compile error, unless separate shadow-cljs / browser output proves an actual build failure. Fix the probe and re-run.
   - A genuine compile error is confirmed from **actual shadow-cljs / browser console output**, not inferred from a `tail-build` timeout. When the runtime is reachable, the `subscribe`/`watch-epochs` `:frameless` topic (below) also surfaces reload / registration / compile lifecycle events.

```
mcp__re-frame2-pair__tail-build {wait-ms: 5000, probe: "(some/probe-form)"}
```

`probe` is a CLJS form chosen to change when the edited code reloads. Good probes for re-frame2:

- After editing a `reg-*` handler: `(re-frame2-pair.runtime/registrar-handler-ref :event <id>)` — compares a hash over `handler-meta`. The underlying `(rf/handler-meta :event :foo)` `:line` / `:column` / `:handler-fn` change after re-registration; capture the meta map's hash before the edit, compare after.
- After editing a `reg-machine`: same shape against `:event` (machines register under `:event` per Spec 005); `(re-frame.machines/machine-meta :auth)` is the equivalent direct read (`machine-meta` lives on `re-frame.machines`, not re-exported from `re-frame.core`).
- After editing a view or helper: pick a CLJS form that derefs the view's namespace var (e.g. `(some-ns/my-view)` or `(meta #'some-ns/my-view)`).
- If you don't know a good probe, omit `probe` and the tool falls back to a 300ms timer; the result includes `:soft? true` so you know it's timer-based.

A successful probe-flip also coincides with a `:rf.registry/handler-replaced` trace event arriving in the buffer, so an alternative confirmation is `(filter #(= :rf.registry/handler-replaced (:operation %)) (re-frame.trace.tooling/trace-buffer :rf/default {:flat true :since <pre-edit-id>}))` (raw per-event mode — `:since` is a `:flat-only` filter). Use whichever fits — they're not exclusive.

## Time-travel (epoch restore)

re-frame2 ships first-class time-travel as part of the Tool-Pair contract — no adapter, no internal poking. These ops are **fully implemented** over public surfaces only.

> **Prefer the dedicated tools; the eval forms are the backstop.** Time-travel undo and app-db injection are *named, auditable* writes, so they have dedicated MCP tools — `restore-epoch` and `replace-app-db` — and both are **allow-listed by this skill**. They are the **canonical path**: they validate inputs, append a synthetic `:rf.epoch/*` epoch so the rewrite itself is undoable, log via `tap>`, and return a structured `:cascade-summary`. Both are gated behind the server's `--allow-writes` launch flag (default **OFF**); against a gate-OFF server (the published default) they refuse with `{:ok? false :reason :rf.error/writes-disabled}` without touching the runtime — the server's gate, not the skill allow-list, is the write-authority boundary. The **eval forms below** (`(rf/restore-epoch! …)` / `app-db-reset!`) are the **backstop, not the default**: `eval-cljs` is default-ON and is NOT covered by `--allow-writes`, so it can express the same rewrites *outside* the gate + the structured envelope. Reach for the eval form only when the dedicated tool is unavailable (a deliberately gate-OFF server) and the operator has told you to proceed via eval — and say so when you do. See [`mcp-transport.md`](mcp-transport.md#mcp-tool-reference-args) §MCP tool reference.

| Op | Invocation | Purpose |
|---|---|---|
| `epoch/history` | `mcp__re-frame2-pair__eval-cljs {form: "(rf/epoch-history :rf/default)"}` | The full ring of `:rf/epoch-record` values for the frame, oldest-first (read-only — no dedicated tool; walk it with `trace-window` / `snapshot {include: ["epochs"]}` for an elided view) |
| `epoch/restore` | `mcp__re-frame2-pair__restore-epoch {epoch-id: "<id>"}` | **The canonical undo.** Rewind the frame's whole **frame-state** to the named epoch's `:frame-state-after` — both partitions (app-db AND runtime-db), reinstalled atomically via `replace-frame-state!`, so machine snapshots / route slice / elision / SSR metadata revive alongside app-db. (`:db-after` is the app-db *projection* of that frame-state.) `--allow-writes`-gated; returns `{:ok? true :restored? true :cascade-summary {…} :unreplayable-effects [...]}` on success, `{:ok? false :reason :restore-rejected}` (or `:rf.error/writes-disabled` on a gate-OFF server) on any documented failure mode (see below). **Backstop only** (gate-OFF server + operator says proceed via eval): `eval-cljs {form: "(rf/restore-epoch! :rf/default <epoch-id>)"}` — returns bare `true`/`false`, outside the structured envelope + the `--allow-writes` audit gate. |
| `epoch/configure` | `mcp__re-frame2-pair__eval-cljs {form: "(rf/configure! {:epoch-history {:depth 200}})"}` | Bump the ring depth (default 50). Pure config, not a write — no dedicated tool. |
| `undo/step-back` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/undo-step-back)"}` | Sugar: restore the previous epoch in the operating frame. No dedicated tool — resolve the previous epoch-id from the ring and pass it to `restore-epoch` for the gated/audited path; this eval form is the backstop. |
| `undo/to-epoch` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/undo-to-epoch <id>)"}` | Sugar over the eval restore for the operating frame — prefer `restore-epoch {epoch-id: "<id>"}` (gated/audited); this is the backstop. |

**Documented failure modes** (Tool-Pair §Time-travel — restore is a no-op on failure). Seven in total: six fire under the reserved `:rf.epoch/*` namespace, plus **Unknown frame** which rides the framework-wide `:rf.error/no-such-handler` op-type:

| Failure | Trace operation | When |
|---|---|---|
| Unknown frame | `:rf.error/no-such-handler` (kind `:frame`) | `frame-id` not registered |
| Unknown epoch | `:rf.epoch/restore-unknown-epoch` | `epoch-id` not in current history (aged out or never recorded) |
| Schema mismatch | `:rf.epoch/restore-schema-mismatch` | `:db-after` no longer validates against currently-registered schemas (a schema was tightened since the snapshot) |
| Missing handler | `:rf.epoch/restore-missing-handler` | DB references a registration id no longer in the registrar (e.g. a machine snapshot whose machine was unregistered) |
| Version mismatch | `:rf.epoch/restore-version-mismatch` | Recorded `:meta :rf/snapshot-version` of an active machine is incompatible with the currently-loaded definition (hot-reload bumped it) |
| Concurrent drain | `:rf.epoch/restore-during-drain` | Called while the frame's run-to-completion drain is in flight |
| Halted-cascade target | `:rf.epoch/restore-non-ok-record` | The named epoch's `:outcome` is not `:ok` — the record was committed for a halted cascade (`:halted-depth`, `:halted-destroy`, …); halted records carry partial state for devtools introspection and are not valid restore targets. Tags `:rf.epoch/outcome` + `:halt-reason`. |

When `restore-epoch` returns `false`, read the matching trace event from `(re-frame.trace.tooling/trace-buffer :rf/default {:flat true :op-type :error})` to get the structured `:tags`, then report to the user (`:op-type` is a `:flat-only` filter, so pass `:flat true` and the frame-id first).

**Caveat (always tell the user before restoring):** restore rewinds durable **frame-state** — both the app-db and runtime-db partitions (so machine snapshots, the route slice, and elision declarations *are* rewound too). What it does **not** undo: side effects that already fired (HTTP requests sent, navigation pushed, localStorage written, `:dispatch-later` already landed) and transient host state outside the durable partitions (in-flight HTTP handles, trace rings).

## Dropped from v1 (re-frame-pair) — surfaces with no v2 equivalent

If you're coming from the v1 `re-frame-pair` skill, a few of its surfaces have no direct re-frame2 equivalent. What to reach for instead:

- **`subs/live` (10x's "currently subscribed query vectors" view)** — there is none; use `subs/cache` (`re-frame.subs.tooling/sub-cache-snapshot`), the public Tool-Pair-pinned shape `{query-v {:value v :ref-count n}}`.
- **A 10x-style internal epoch-buffer accessor + ring-rollover detection** — there is none; use `(rf/epoch-history frame-id)`, which is bounded and self-describing (size = `(count history)`, depth = `(:depth (epoch/current-config))`).
- **A 10x-style internal undo / step-back navigation** — there is none; use first-class `(rf/restore-epoch! frame-id epoch-id)` with seven documented failure modes (see [Time-travel](#time-travel-epoch-restore)).
- **`re-com-debug-disabled` heuristic** — the source-coord story leads with re-frame2's own mandatory registered-view annotation (debug builds, no opt-in); re-com's `data-rc-src` is a fallback source-coord source, not the only path.
- **`trace-enabled?` discovery check** — use `interop/debug-enabled?` (the `goog.DEBUG` mirror per Spec 009 §Production builds).
- **Version-floor enforcement against re-frame-10x / re-com / re-frame** — there is none (no re-frame-10x dependency; re-com is optional; re-frame2's version is implicit in the loaded ns).

If during real-world use a surface re-frame2 lacks would unblock a recipe, raise it against the re-frame2 spec rather than working around it in this skill.
