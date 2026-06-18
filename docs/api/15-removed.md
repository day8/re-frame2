# 15 — Removed / not shipped

This chapter is the tombstone register. It exists so v1 migrators and readers cross-checking against blog posts can find out, in one place, **what's gone**, **what was proposed but not shipped**, and **what to use instead**.

If a row says "(proposed earlier)," that means the surface was named in an early draft of the v2 spec and dropped before ship. The replacement is canonical; the proposed name never existed at run-time.

| Removed / not shipped | What to do instead | Reference |
|---|---|---|
| `dispatch-with` (master) | `(dispatch event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-sync-with` (master) | `(dispatch-sync event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-to` (proposed earlier) | `(dispatch event {:frame :todo})` | 002 |
| `subscribe-to` (proposed earlier) | `(subscribe query-v {:frame :todo})` | 002 |
| `frame-dispatcher` / `bound-dispatcher` / `bound-subscriber` (proposed earlier) | `(rf/frame-handle)` — the keystone operation bundle; captures the frame at creation time and is safe to call during render and from async callbacks. | 002 |
| `dispatcher` | `(:dispatch (rf/frame-handle))` *or* the `dispatch` injected in a `reg-view` body. | 002 |
| `subscriber` | `(:subscribe (rf/frame-handle))` *or* the `subscribe` injected in a `reg-view` body. | 002 |
| `bound-fn` (CLJS macro) | Renamed to `frame-bound-fn` — same `fn`-syntax + frame-capture. (The old name shadowed `clojure.core/bound-fn` and over-promised: it captured the frame, not every dynamic binding.) | 002 |
| `frame-bound-fn` (fn form, 1- and 2-arity) | Renamed to `frame-bound-fn*` (the `*`-twin); `frame-bound-fn` is now the macro (`fn`-syntax). The `*`-twin keeps both `(f)` and `(frame-id f)` arities. | 002 |
| `current-frame` | Renamed to `current-frame-id` — returns a frame-id keyword. | 002 |
| `get-frame-db` | Renamed to `app-db-value` — returns the `app-db` VALUE (a plain map), `nil` for an unregistered / destroyed frame. The container accessor is the internal `re-frame.frame/app-db-container`. | 002 |
| `enable-performance-api-tracing!` (proposed earlier) | Performance-API instrumentation is gated on the compile-time `re-frame.performance/enabled?` `goog-define`, not a runtime toggle. See [11 — Instrumentation](11-instrumentation.md). | 009 |
| `add-trace-listener` / `remove-trace-listener` (proposed earlier) | `register-listener!` / `unregister-listener!` | 009 |
| `register-trace-listener` / `unregister-trace-listener` (no-bang, proposed earlier) | Renamed to `register-listener!` / `unregister-listener!` — the bang form matches the side-effecting nature of listener registration. | 009 |
| Bare `[:my-view "args"]` keyword-tagged hiccup | Var form `[my-view "args"]` (canonical) or `[(rf/view :my-view) "args"]` for late-binding by id. | 004 |
| `h` macro (proposed earlier) | Removed. Use the Var form `[my-view "args"]` or `[(rf/view :my-view) "args"]`. | 004 |
| `reg-global-interceptor` | `reg-frame :interceptors` — frame-level is the canonical "global within this frame." For cross-frame observation, use `register-listener!`. | MIGRATION M-17 |
| `clear-global-interceptor` | No replacement needed — re-register `reg-frame` with an updated `:interceptors` vector (absent-key semantics clear it). | MIGRATION M-17 |
| `reg-sub-raw` | `reg-sub` for app-db reads; Pattern-AsyncEffect for non-app-db sources; state machines for lifecycle; the [006](../../spec/006-ReactiveSubstrate.md) adapter contract for bridging external reactivity. | MIGRATION M-18 |
| `re-frame.alpha/reg` | The shipped per-kind registrars: `reg-event` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-flow`. (The v1 event trio `reg-event-db` / `reg-event-fx` / `reg-event-ctx` is **not** a v2 target — those are removed/withdrawn throwing stubs and migration inputs only, see the rows below and EP-0018; `reg-event` is the single event-registration form.) The `re-frame.alpha` namespace is dissolved. | MIGRATION M-23 |
| `re-frame.alpha/sub` | Vector-form `(rf/subscribe [::id arg])`. | MIGRATION M-23 |
| `re-frame.alpha/reg-sub-lifecycle` and built-in lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) | Sub-cache uses a single algorithm — synchronous ref-counting (dispose on derefer-count → 0). For specific edge cases, file a follow-up bead. | MIGRATION M-23 |
| `debug` interceptor | Trace surface ([Spec 009](../../spec/009-Instrumentation.md)) + 10x / re-frame-pair | MIGRATION M-21 |
| `trim-v` interceptor | Canonical map-payload call shape | MIGRATION M-21 |
| `on-changes` interceptor | Flows ([Spec 013](../../spec/013-Flows.md)) | MIGRATION M-21 |
| `enrich` interceptor | Flows (derived state) / `:schema` (validation) / custom `->interceptor` (escape hatch) | MIGRATION M-21 |
| `after` interceptor | Registered fx (`:fx [[:my-fx ...]]`) for side-effects; custom `->interceptor` for context-shaped work; vendor from v1 if the helper is wanted as a local utility | MIGRATION M-21 |
| `inject-cofx` / `inject-cofx*` (coeffect-delivery interceptors) | Declare the facts a handler needs with `:rf.cofx/requires` registration metadata; the runtime supplies them. Register the supplier with value-returning `reg-cofx`. There is no interceptor that delivers a coeffect — see [01 — Core §`reg-cofx`](01-core.md#reg-cofx) and [Guide — Effects and coeffects](../guide/concepts/effects-and-coeffects.md). | EP-0017 |
| `reg-event` positional interceptor vector middle slot | Put the chain in registration metadata: `(reg-event :id {:interceptors [i1 i2]} handler)`. If a call also has metadata, merge `:interceptors` into that map. | MIGRATION M-70 |
| `reg-event-db` | Use `reg-event` (no alias). Destructure `:db` from the coeffects map and wrap the return in `{:db …}`: `(reg-event id (fn [{:keys [db]} ev] {:db BODY}))`. A stale call raises the always-on hard error `:rf.error/reg-event-db-removed` naming `reg-event`. | EP-0018 / MIGRATION M-73 |
| `reg-event-fx` | Use `reg-event` (no alias) — the identical shape under the bare name (coeffects in, effects out); just rename the call. A stale call raises `:rf.error/reg-event-fx-removed`. | EP-0018 / MIGRATION M-73 |
| `reg-event-ctx` | Demoted to a framework-internal primitive. Express application full-context work as a **registered interceptor** (`reg-interceptor` with `:before` / `:after`, referenced by id from a `reg-event` registration's `:interceptors` chain — EP-0022). A stale public call raises `:rf.error/reg-event-ctx-removed` naming `reg-interceptor`. | EP-0018 / MIGRATION M-73 |
| `with-overrides` (v1 macro name) | Renamed to `with-fx-overrides`. | MIGRATION M-50 |
| `add-marks` / `set-marks` (imperative `app-db` path-marks) | Declare durable `app-db` classification on the **frame**: `reg-frame` `:sensitive` / `:large` `{:app-db [...]}` path maps. The underlying `re-frame.marks/*` fns remain internal/test helpers only. | EP-0015 / 015 |
| `redact-interceptor` (positional payload-scrub interceptor) | Classify transient payloads on the **registration**: `reg-event-*` `:sensitive` / `:large` metadata. Projection happens centrally at egress, not via interceptor placement. | EP-0015 §7 / 015 |
| Schema-attached `:sensitive?` / `:large?` as the **`app-db`** classification route | `app-db` classification is frame-owned (above). Per-slot `:sensitive?` / `:large?` Malli props remain the route for *owner-local schema'd* data — machine `:data`, resource data/params, HTTP `:decode` bodies. | EP-0015 §8 / 015 |
| `:rf.privacy/show-sensitive?` / `set-show-sensitive!` (process-global on/off privacy toggle) | There is no process-global toggle. On-box visibility is a named `:rf.egress/*` profile **per (tool, frame)** — `:rf.egress/local-redacted` (default, fail-closed) / `:rf.egress/local-raw` (trusted-local opt-in). | EP-0015 issue 7 / 015 |

## Why these went

A few one-line rationales for the larger cuts:

- **The frame-affordance surface** (`dispatcher`, `subscriber`, `bound-fn`, plus the `frame-bound-fn` fn → `frame-bound-fn*` shift) — the multi-frame surface had accreted ~8 affordances that were mostly patches around one root cause: ambient frame lookup doesn't survive async boundaries. The redesign collapses them so you choose by INTENT, not mechanism — **hold** a frame's ops as a value via `frame-handle` (the common case) or `frame-bound-fn` / `frame-bound-fn*` (wrap an arbitrary fn), **scope** with `with-frame` / `with-new-frame` / `frame-provider`, **override** per-call with `{:frame …}`. `dispatcher` / `subscriber` folded into `(:dispatch (rf/frame-handle))` / `(:subscribe (rf/frame-handle))`; `bound-fn` was renamed `frame-bound-fn` (it shadowed `clojure.core/bound-fn` and only captured the frame, not every binding). Full design: [Spec 002 §The multi-frame surface](../../spec/002-Frames.md#the-multi-frame-surface--choose-by-intent).
- **`reg-global-interceptor`** — the v1 surface conflated "global" (process-wide) and "global within this frame" (frame-wide). The frame-wide answer is `reg-frame :interceptors`; the process-wide answer is `register-listener!` (observation, not mutation). Splitting the two ended the confusion.
- **`reg-sub-raw`** — the v1 escape hatch covered four distinct use cases (app-db reads, async sources, lifecycle, external reactivity). Each now has its own surface — `reg-sub`, Pattern-AsyncEffect, state machines, the adapter contract — and the escape hatch isn't necessary.
- **`re-frame.alpha`** — the alpha namespace was an experiment to unify registration / dispatch under generic `reg` / `sub` / `dis` verbs. The unification didn't pay for itself — the per-kind macros read better at the call site and survive better in the linter — and the alpha namespace is dissolved. No APIs in this reference live outside `re-frame.core` (with the documented per-namespace exceptions).
- **The five v1 interceptors** (`debug`, `trim-v`, `on-changes`, `enrich`, `after`) — each was either trivially `->interceptor`-able (so didn't earn its own surface), or replaced by a richer mechanism (flows, schemas, the trace surface).
- **`inject-cofx` / `inject-cofx*`** — EP-0017 made coeffect delivery a *declared* contract, not an interceptor side-channel. A handler lists the world facts it consumes in `:rf.cofx/requires`, suppliers are value-returning `reg-cofx` fns, and the runtime grades/records facts by id. Injecting a coeffect through the interceptor vector hid the dependency from `handler-meta`, the cofx graph, and the recordable-fact grading, so the interceptor form was removed. Full model: [Guide — Effects and coeffects](../guide/concepts/effects-and-coeffects.md).
- **The pre-EP-0015 privacy surfaces** (`add-marks` / `set-marks` / `redact-interceptor` / schema-attached `app-db` marks / the process-global `:rf.privacy/show-sensitive?` toggle) — five overlapping ways to express one fact. [EP-0015](../EP/EP-0015-frame-owned-egress-policy.md) collapsed them to one model: classification is declarative and local to the **owner** (frame config for durable `app-db`; per-slot schema props for owner-local schema'd data; registration metadata for transient payloads), projection is centralized at trust boundaries (`project-egress` + the closed `:rf.egress/*` profile enum), and sinks consume already-projected records. The full teaching is [Guide ch.23](../guide/how-to/keep-secrets-out-of-traces.md).

## See also

- [MIGRATION.md](../../migration/from-re-frame-v1/README.md) — the AI-driven migration spec, with one rule per row above.
- [01 — Core](01-core.md) — the surfaces that replaced the removed ones.
