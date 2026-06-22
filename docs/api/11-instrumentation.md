# 11 — Instrumentation

The instrumentation surface is two surfaces stacked. The first is **dev-only**: a trace bus that emits one richly-tagged record per noteworthy event (dispatch, sub recompute, fx walk, render, machine transition, schema validation, error), buffered into a ring, fanned out to registered listeners synchronously, and elided entirely under `:advanced` + `goog.DEBUG=false`. The second is **always-on**: a pair of tight, production-survivable substrates (event-emit, error-emit) that deliver one record per processed event and one record per `:rf.error/*` event. Together they let the same app feed Xray in dev and Sentry / Datadog / Honeybadger in production from the same registration.

This is the load-bearing surface for the pair-shape architecture — every tool that watches a running re-frame2 app composes against one of these surfaces. Xray subscribes to the dev trace bus. The MCP servers do the same. The event-emit substrate is what hosted observability shippers consume. The error-emit substrate is what hosted error monitors consume. Same registrations, three audiences.

This chapter covers the event-emit listener surface, the error-emit listener surface, the dev-only tracing surface, the epoch buffer (time-travel), the performance instrumentation gate, the source-coord annotation contract, the wire-boundary elision walker, and the error contract.

## Event-emit (always-on, production-survivable)

A minimal always-on listener surface that survives `:advanced` + `goog.DEBUG=false` and delivers one tight record per processed event. The intended consumers are hosted observability back-ends (Datadog, Honeycomb, Sentry, …). **Parallel to (not a fallback for) the dev-only trace surface; per-event only — no per-sub, per-fx, or per-`:rf.event/db-changed` records.**

Record shape: `{:event :event-id :frame :time :outcome :elapsed-ms}`. The `:event` slot is passed through `elide-wire-value` (see below) once before fan-out, so schema-marked `:sensitive?` paths land as `:rf/redacted` and `:large?` paths land as `:rf.size/large-elided`.

### `register-event-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-event-listener! id listener-fn)
  ```
- **Description**: Receive one event-record per processed event. Re-registering the same `id` replaces. Returns `id`. **Always-on**: survives CLJS `:advanced` + `goog.DEBUG=false`.

### `unregister-event-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unregister-event-listener! id) → nil
  ```
- **Description**: The inverse.

## Error-emit (always-on, production-survivable)

Sibling of the event-emit surface above. Runs through the always-on error-emit substrate. Survives `:advanced` + `goog.DEBUG=false`. Intended consumers are hosted error monitors (Sentry, Honeybadger, Rollbar).

This corpus-wide listener delivers one record per **every catalogued production-reachable runtime `:rf.error/*`** — handler / interceptor / cofx exceptions, flow exceptions, fx / reserved-fx exceptions, reactive- and `compute-sub`-resolution exceptions, the invalid-operation categories `:rf.error/frame-destroyed`, `:rf.error/no-such-handler`, `:rf.error/no-such-sub`, the bounded `:rf.error/frame-teardown-failed` report, **and** the six EP-0008-promoted SSR non-event categories (the frameless-tolerant `dispatch-error-record!` path). (Registration-time / dev-only-validation categories stay dev-trace-only by design — see [009 §Error event catalogue](../../spec/009-Instrumentation.md#error-event-catalogue).) It is the single error-observability surface; recovery is the framework's typed per-category default, not app-steerable (the per-frame `:on-error` recovery policy was removed per rf2-hiqtk8).

The listener payload is a **union of three record shapes** — the per-event record, plus two non-event records that ride the general `dispatch-error-record!` helper (the non-event sibling of `dispatch-on-error!`):

1. **Per-event error record** — `{:error :event :event-id :frame :time :exception :elapsed-ms}` (plus `:source-coord` when the failing handler was registered via the public macro path). One per production-reachable per-event `:rf.error/*` (handler / interceptor / cofx / sub / fx / flow failures). Fanned out by `dispatch-on-error!`. The `:event` slot is passed through `elide-wire-value` once before fan-out (same redaction posture as event-emit).
2. **Frame-teardown report** — `{:error :rf.error/frame-teardown-failed :frame :hook-failures :reason :recovery :time}`. One bounded record per frame destroy whose best-effort cleanup hooks threw (EP-0008 promotion criterion; see [009 §Observability channels and the promotion criterion](../../spec/009-Instrumentation.md#observability-channels-and-the-promotion-criterion)). It is frame-keyed and carries a `:hook-failures` vector **instead of** the per-event `:event` / `:event-id` / `:exception` / `:elapsed-ms` slots.
3. **Promoted SSR non-event records** — the six SSR categories EP-0008 (rf2-hhutya) promoted onto the always-on axis: `:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload` (incl. the pre-frame **frameless** parse sub-path, which carries `:frame nil`), `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, and `:rf.error/ssr-ring-error-view-failed`. Each is a flat union record `{:error :frame :time …category keys…}` carrying its own slots (`:exception` / `:phase` / `:reason` / `:projector-id` / `:ex-class` / …) and either a server `:frame` or `:frame nil` (the frameless hydration-parse path). Production-reachable on a long-lived JVM SSR host where the dev trace is `-Dre-frame.debug=false`-elided. The recoverable-degradation and post-commit members are **non-projecting** — promotion changes what off-box shippers see, never the wire outcome. See [009 §What IS available in production](../../spec/009-Instrumentation.md#what-is-available-in-production) for the full enumeration and the projecting/non-projecting caveats.

Listener bodies MUST branch on `(:error record)` (or otherwise tolerate a record with no top-level `:event` / `:event-id` / `:exception`) rather than assuming the per-event shape — a generic shipper that maps `(:error record)` to the alert name and forwards the rest handles every arm. Per-listener exceptions are isolated — a buggy listener cannot block siblings or the cascade.

### `register-error-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-error-listener! id listener-fn)
  ```
- **Description**: Receive one error-record per catalogued production-reachable runtime `:rf.error/*` event (the broad surface — including frame-destroyed / no-such-handler / no-such-sub / sub-exception, not just handler exceptions). Re-registering the same `id` replaces. Returns `id`. **Always-on**: survives CLJS `:advanced` + `goog.DEBUG=false`.

### `unregister-error-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unregister-error-listener! id) → nil
  ```
- **Description**: The inverse.

## Tracing (dev-only)

The rich-detail trace surface. **Dev-only — elided in production via Closure DCE under `:advanced` + `goog.DEBUG=false`.** See [009 §Tracing](../../spec/009-Instrumentation.md) for emit semantics and synchronous listener delivery.

### `register-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-listener! key callback-fn)
  ```
- **Description**: "Receive every trace event the runtime emits." Synchronous delivery; the callback returns before the next trace event is processed.

### `unregister-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unregister-listener! key) → nil
  ```
- **Description**: The inverse.

### `emit-trace-event!`

- **Kind**: function
- **Signature**:
  ```clojure
  (emit-trace-event! op-type operation tags) → nil
  ```
- **Description**: "Emit a custom trace event." Use sparingly — the framework emits the load-bearing events; custom emission is for app-specific cross-cutting concerns the framework can't know about.

### `re-frame.interop/debug-enabled?`

- **Kind**: Var (`^boolean`)
- **Description**: **CLJS:** alias of `goog.DEBUG` — constant-folded by Closure under `:advanced`, so `:advanced` + `goog.DEBUG=false` builds DCE every `(when interop/debug-enabled? ...)` branch. **JVM:** a `def` read ONCE at ns-load from the Java system property `-Dre-frame.debug` (winning on conflict) or the environment variable `RE_FRAME_DEBUG`; defaults `true` (dev parity). Accepts the conventional false-y vocabulary case-insensitively (`false`, `0`, `no`, `off`, empty string) with whitespace trimmed; anything else leaves the flag at `true`. SSR / webhook receivers / long-running JVMs facing untrusted input MUST set the gate `false` explicitly.

### `re-frame.performance/enabled?`

- **Kind**: Var (`^boolean`)
- **Description**: `goog-define`d (CLJS) / `^:const false` (JVM). Set via `:closure-defines {re-frame.performance/enabled? true}` to bracket event dispatch / sub recompute / fx walk / view render in `performance.mark` + `performance.measure` calls (User-Timing entries `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`). **Compile-time only** — not a `(rf/configure! ...)` knob; runtime mutation has no effect. Default `false`; under `:advanced` + default the bracket DCEs and shipped binaries carry zero User-Timing instrumentation. CLJS-only — JVM is a no-op.

### `trace-buffer`

- **Kind**: function
- **Signature**:
  ```clojure
  (trace-buffer) → vector of trace events, oldest-first
  (trace-buffer opts) → vector of trace events, oldest-first
  ```
- **Description**: "What's in the ring right now?" Reads the buffer non-destructively. Pair tools and Xray use this for post-mortem inspection.

### `clear-trace-buffer!`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-trace-buffer!) → nil
  ```
- **Description**: Empty the ring.

### `(rf/configure! {:trace-buffer …})`

- **Kind**: config key
- **Signature**:
  ```clojure
  (rf/configure! {:trace-buffer {:cascades-retained N}})
  ```
- **Description**: Per-frame ring cascade-slot count knob (0 disables retention). See [01 — Core §Configure keys](01-core.md#runtime-configuration-configure).

### `group-cascades`

- **Kind**: function
- **Signature**:
  ```clojure
  (group-cascades events) → vector of cascade records
  ```
- **Description**: Pure data projection of a list of trace events into per-cascade records `{:dispatch-id :event :handler :fx :effects :subs :renders :other}`, sorted by emission order. JVM-runnable. Re-exported from `re-frame.trace.projection`.

### `domino-bucket`

- **Kind**: function
- **Signature**:
  ```clojure
  (domino-bucket trace-event) → #{:event :handler :fx :effect :sub :render :other}
  ```
- **Description**: Classify a raw trace event into the six-domino slot used by `group-cascades`. Pure.

### Trace-emission opt-out

Event-handler registration accepts a `:rf.trace/no-emit? true` metadata flag. When set, the runtime suppresses **every** trace emission and event-emit record within the handler's scope — the handler runs invisibly to the trace surface, the event-emit substrate, and (transitively) the epoch buffer.

| Metadata key | Where | Value | Default | Effect |
|---|---|---|---|---|
| `:rf.trace/no-emit?` | `reg-event` metadata map | boolean | `false` | When `true`, suppresses all trace + event-emit emissions inside the handler's scope. |

Used by framework-internal bookkeeping handlers (Xray, Story, re-frame2-pair-mcp, story-mcp) that would otherwise saturate the trace stream. The `:rf.trace/*` namespace is framework-owned (per [Conventions §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned)).

## Epoch history (Tool-Pair)

Per-frame epoch snapshots, recorded on each drain-completion in dev builds. Used by pair-shaped tools for time-travel and post-mortem analysis. **Production builds elide entirely.**

### `epoch-history`

- **Kind**: function
- **Signature**:
  ```clojure
  (epoch-history frame-id) → vector of epoch records
  ```
- **Description**: Returns `[]` for an unknown / destroyed frame.

### `restore-epoch!`

- **Kind**: function
- **Signature**:
  ```clojure
  (restore-epoch! frame-id epoch-id) → boolean
  ```
- **Description**: Restore the frame's whole **frame-state** — both the app-db and runtime-db partitions — to the named epoch's `:frame-state-after`, reinstalled in one atomic write (so machine snapshots, the route slice, and other runtime-db material rewind alongside app-db, not just the application slice). Returns `true` on success; `false` for an unknown / destroyed frame (and emits `:rf.error/no-such-handler` of kind `:frame`).

### `replace-app-db!`

- **Kind**: function
- **Signature**:
  ```clojure
  (replace-app-db! frame-id new-db) → boolean
  ```
- **Description**: Pair-tool write surface (state injection). Direct write to `app-db` — bypasses the cascade. Returns `true` on success.

### `register-epoch-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-epoch-listener! key callback-fn)
  ```
- **Description**: Process-global assembled-epoch listener. A callback whose previously-observed frame is destroyed receives a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace.

### `unregister-epoch-listener!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unregister-epoch-listener! key)
  ```
- **Description**: The inverse.

### `(rf/configure! {:epoch-history …})`

- **Kind**: config key
- **Signature**:
  ```clojure
  (rf/configure! {:epoch-history {:depth N :trace-events-keep N :redact-fn fn}})
  ```
- **Description**: Buffer-depth and redactor knobs. See [01 — Core §Configure keys](01-core.md#runtime-configuration-configure).

### Trace events emitted by epoch-history machinery

| `:operation` | Tags |
|---|---|
| `:rf.epoch/snapshotted` | `:frame`, `:epoch-id`, `:event-id` |
| `:rf.epoch/restored` | `:frame`, `:epoch-id` |
| `:rf.epoch/db-replaced` | `:frame`, `:epoch-id` |
| `:rf.epoch/restore-unknown-epoch` | `:frame`, `:epoch-id`, `:history-size` |
| `:rf.epoch/restore-schema-mismatch` | `:frame`, `:epoch-id`, `:schema-digest-recorded`, `:schema-digest-current`, `:failing-paths` |
| `:rf.epoch/restore-missing-handler` | `:frame`, `:epoch-id`, `:missing` |
| `:rf.epoch/restore-version-mismatch` | `:frame`, `:epoch-id`, `:machine-id`, `:version-recorded`, `:version-current` |
| `:rf.epoch/restore-during-drain` | `:frame`, `:epoch-id` |
| `:rf.epoch/restore-non-ok-record` | `:frame`, `:epoch-id`, `:outcome`, `:halt-reason` |
| `:rf.epoch/replace-during-drain` | `:frame` |
| `:rf.epoch/replace-schema-mismatch` | `:frame`, `:failing-paths` |
| `:rf.epoch.cb/silenced-on-frame-destroy` | `:frame`, `:cb-id` |

## The wire-boundary walker

`elide-wire-value` is the framework primitive that walks tree-shaped values at the wire boundary and substitutes elision markers for sensitive or large slots. **This walker is the single normative emission site for the `:rf/redacted` sensitive sentinel and the `:rf.size/large-elided` size marker.** Per-tool reimplementation is prohibited.

### `elide-wire-value`

- **Kind**: function
- **Signature**:
  ```clojure
  (elide-wire-value v opts) → v or an elision-marker substitution
  ```
- **Description**: Walk `v` consulting `[:rf.runtime/elision :sensitive-declarations]` and `[:rf.runtime/elision :declarations]` of the named frame's **runtime-db** — the per-frame registry written by the commit-plane classification effects (EP-0025). Redaction is strictly **path-based**: substitute `:rf/redacted` at each declared sensitive path and `:rf.size/large-elided` markers at each declared large path. There is **no value-match** arm — a secret re-keyed off its classified path is **not** chased by value (the removed "propagation / taint by another name"); it ships raw, the intended **fail-open** (to redact it, classify the destination path). When the frame carries no declarations the value passes through unchanged.

> **Removed surfaces (EP-0025).** `redact-derived-slots` (the value-based derived-tree dual) and `populate-elision-from-schemas!` / `populate-sensitive-from-schemas!` (the schema-prop boot hydrators) were **removed** from the facade and from `re-frame.elision`. Value-match is propagation by another name; schemas are not a second durable-classification route. Use the path-based surfaces instead: classify durable `app-db` paths with the commit-plane effects (a `reg-event` returning `:sensitive` / `:large` alongside `:db`), and project a derived tree (rendered hiccup, a resolved `:effective-args` map, a snapshot body) with **`project-egress`** under a `:rf.observe/derived-tree` record — it path-walks each tree slot through `elide-wire-value` against the frame's registry.

`elide-wire-value` is the low-level *value* walker. The public, record-level boundary primitive is **`project-egress`**: real egress surfaces (handled-event records, error records, epoch records, MCP snapshots, HTTP diagnostics) emit *records*, and `project-egress` projects a whole record under the owning frame's classification and a named `:rf.egress/*` profile — delegating to `elide-wire-value` for each tree-shaped slot. Sinks and tools call `project-egress`; they rarely call the walker directly. See [Guide ch.23 — Privacy and large things](../guide/how-to/keep-secrets-out-of-traces.md) for the full projection model and the closed `:rf.egress/*` profile enum.

### `project-egress`

- **Kind**: function
- **Signature**:
  ```clojure
  (project-egress record-or-value opts)
  ```
- **Description**: The public, record-level boundary primitive (EP-0015) — **the required step before any off-box sink**. Dispatches on a record's `:kind` (`:rf.observe/handled-event` / `:rf.observe/error`) to a per-kind projector, falling back to walking a kindless input as a tree-shaped value (the direct-read path); each tree-shaped slot is delegated to `elide-wire-value` against the frame's classification. `opts` carries `:rf.egress/profile` (the closed six-member enum), `:frame`, `:path`, and the advanced `:rf.size/*` overrides (which compose on top of the profile — the override wins). An unknown profile throws `:rf.error/unknown-egress-profile`. **Fail-closed**: a tree slot projects only when the frame is known — there is no `:rf/default` synthesis. To project a derived tree (rendered hiccup, a resolved `:effective-args` map, a snapshot body), wrap it in a `:rf.observe/derived-tree` record; `project-egress` path-walks each slot (a re-keyed value ships raw — the intended fail-open). Per Spec 015 §Projection.

```clojure
;; Verify what an off-box sink will receive before wiring it.
(rf/project-egress
  {:kind     :rf.observe/handled-event
   :frame    :app/main
   :event-id :auth/sign-in
   :event    [:auth/sign-in {:password "hunter2"}]}
  {:rf.egress/profile :rf.egress/off-box-observability})
;; => {:kind :rf.observe/handled-event :frame :app/main
;;     :event-id :auth/sign-in ...}   ;; no :event slot off-box
```

## Frame-owned observability sinks

The normal production observability story (Spec 015 §Frame-owned observability sink policy). An app declares a sink under a frame's `:observability` config and registers the concrete sink fn against that sink id here. The runtime routes one handled-event record per processed event, and one error record per `:rf.error/*` site, through `project-egress` (under the frame's classification + the sink's egress profile) to the declared sinks. **Sinks consume already-projected records — there is no sink-local redaction.** The framework ships no Datadog / Sentry client (that is an app / integration-library concern); these two verbs are the registration seam.

This is parallel to (and the production-normal home of) the advanced corpus-wide `register-listener!` / `register-event-listener!` / `register-error-listener!` surfaces above: the sink routing is frame-scoped and profile-projected, where the corpus-wide listeners are cross-frame and raw.

### `register-observability-sink!`

- **Kind**: function
- **Signature**:
  ```clojure
  (register-observability-sink! sink-id f)
  ```
- **Description**: Register an observability sink fn `f` under the keyword `sink-id` — the id a frame's `:observability {:handled-events [{:sink <sink-id> :rf.egress/profile …}]}` entry names (EP-0015 §9). `f` receives a single **already-projected** record (a `:rf.observe/handled-event` or `:rf.observe/error`, projected under the owning frame's classification and the entry's egress profile); it does **no** sink-local redaction. Re-registering the same id replaces. Returns `sink-id`. **Always-on** — survives CLJS `:advanced` + `goog.DEBUG=false` (the frame `:observability` stream is the production observation stream).

```clojure
(rf/reg-frame :app/main
  {:observability {:handled-events
                   [{:sink :my-app.sinks/datadog
                     :rf.egress/profile :rf.egress/off-box-observability}]}
   :initial-events [[:auth/init]]})

(rf/register-observability-sink! :my-app.sinks/datadog
  (fn [projected-record]
    ;; already projected — no sink-local redaction
    (datadog/send projected-record)))
```

### `unregister-observability-sink!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unregister-observability-sink! sink-id) → nil
  ```
- **Description**: Drop the observability sink registered under `sink-id`. Returns nil.

See [08 — Schemas §Data classification](08-schemas.md#data-classification) for the declaration side — durable `app-db` classification is event-owned (a `reg-event` returns the commit-plane `:sensitive` / `:large` effects alongside `:db`), and per-slot `:sensitive?` / `:large?` schema props own machine `:data` / resource / HTTP-body classification.

## Privacy predicate

### `sensitive?`

- **Kind**: function
- **Signature**:
  ```clojure
  (sensitive? trace-event) → boolean
  ```
- **Description**: True iff `trace-event` is a map carrying `:sensitive? true` at the top level (not under `:tags`). The framework-published predicate every consumer composes against — replaces per-consumer reimplementations of the same five-token check.

## DOM source-coord annotations

Every adapter whose host has a DOM-attribute concept (Reagent / UIx / Helix on the browser) injects `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element of each registered view. Format and exemptions live in [Spec 006 §Source-coord annotation](../../spec/006-ReactiveSubstrate.md#source-coord-annotation-mandatory).

The annotation is gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production `:advanced` builds elide the attribute via dead-code elimination — there is no DOM-bytes cost in shipped bundles. The JVM SSR emitter mirrors the contract per [Spec 011 §Source-coord annotation under SSR](../../spec/011-SSR.md#source-coord-annotation-under-ssr).

## The error contract

Errors are emitted as structured trace events with `:op-type :error` (or `:warning` / `:info` / `:fx` / `:flow` / `:frame`) and a per-category `:operation` keyword. The complete normative catalogue — every `:rf.error/*`, `:rf.warning/*`, `:rf.fx/*`, `:rf.cofx/*`, `:rf.ssr/*`, `:rf.epoch/*`, `:rf.flow/*`, `:rf.http/*`, `:rf.http.interceptor/*`, `:rf.frame/*`, and `:rf.route.nav-token/*` event the runtime emits — lives at [009 §Error event catalogue](../../spec/009-Instrumentation.md#error-event-catalogue) (single source of truth for category names, `:op-type` discriminator, trigger conditions, default `:recovery`, and `:tags` payload keys).

Per-category Malli `:tags` schemas are canonicalised at [Spec-Schemas §Per-category `:tags` schemas](../../spec/Spec-Schemas.md#per-category-tags-schemas) — one schema per catalogue row.

## See also

- [01 — Core](01-core.md) — the `:trace-buffer`, `:epoch-history`, `:elision` configure keys.
- [08 — Schemas](08-schemas.md) — the registration side of the elision posture.
- [Spec 009 — Instrumentation](../../spec/009-Instrumentation.md) — the normative source.
- [Tool-Pair](../../spec/Tool-Pair.md) — how the epoch buffer, trace bus, and source-coord annotations compose into the pair-shaped tools.
