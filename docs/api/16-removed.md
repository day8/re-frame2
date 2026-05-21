# 16 — Removed / not shipped

This chapter is the tombstone register. It exists so v1 migrators and readers cross-checking against blog posts can find out, in one place, **what's gone**, **what was proposed but not shipped**, and **what to use instead**.

If a row says "(proposed earlier)," that means the surface was named in an early draft of the v2 spec and dropped before ship. The replacement is canonical; the proposed name never existed at run-time.

| Removed / not shipped | What to do instead | Reference |
|---|---|---|
| `dispatch-with` (master) | `(dispatch event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-sync-with` (master) | `(dispatch-sync event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-to` (proposed earlier) | `(dispatch event {:frame :todo})` | 002 |
| `subscribe-to` (proposed earlier) | `(subscribe query-v {:frame :todo})` | 002 |
| `frame-dispatcher` (proposed earlier) | `dispatcher` — captures the current frame at call time; safe to call during render and from async callbacks | 002 |
| `bound-dispatcher` / `bound-subscriber` (proposed earlier) | Cut as pure aliases for `dispatcher` / `subscriber`. The verb-form names already imply capture-at-call-time semantics. | 002 |
| `enable-performance-api-tracing!` (proposed earlier) | Performance-API instrumentation is gated on the compile-time `re-frame.performance/enabled?` `goog-define`, not a runtime toggle. See [11 — Instrumentation](11-instrumentation.md). | 009 |
| `add-trace-listener` / `remove-trace-listener` (proposed earlier) | `register-listener!` / `unregister-listener!` | 009 |
| `register-trace-listener` / `unregister-trace-listener` (no-bang, proposed earlier) | Renamed to `register-listener!` / `unregister-listener!` — the bang form matches the side-effecting nature of listener registration. | 009 |
| Bare `[:my-view "args"]` keyword-tagged hiccup | Var form `[my-view "args"]` (canonical) or `[(rf/view :my-view) "args"]` for late-binding by id. | 004 |
| `h` macro (proposed earlier) | Removed. Use the Var form `[my-view "args"]` or `[(rf/view :my-view) "args"]`. | 004 |
| `reg-global-interceptor` | `reg-frame :interceptors` — frame-level is the canonical "global within this frame." For cross-frame observation, use `register-listener!`. | MIGRATION M-17 |
| `clear-global-interceptor` | No replacement needed — re-register `reg-frame` with an updated `:interceptors` vector (absent-key semantics clear it). | MIGRATION M-17 |
| `reg-sub-raw` | `reg-sub` for app-db reads; Pattern-AsyncEffect for non-app-db sources; state machines for lifecycle; the [006](../../spec/006-ReactiveSubstrate.md) adapter contract for bridging external reactivity. | MIGRATION M-18 |
| `re-frame.alpha/reg` | Per-kind macros: `reg-event-db` / `reg-event-fx` / `reg-event-ctx` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-flow`. The `re-frame.alpha` namespace is dissolved. | MIGRATION M-23 |
| `re-frame.alpha/sub` | Vector-form `(rf/subscribe [::id arg])`. | MIGRATION M-23 |
| `re-frame.alpha/reg-sub-lifecycle` and built-in lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) | Sub-cache uses a single algorithm — deferred ref-counting with grace-period. For specific edge cases, file a follow-up bead. | MIGRATION M-23 |
| `debug` interceptor | Trace surface ([Spec 009](../../spec/009-Instrumentation.md)) + 10x / re-frame-pair | MIGRATION M-21 |
| `trim-v` interceptor | Canonical map-payload call shape | MIGRATION M-21 |
| `on-changes` interceptor | Flows ([Spec 013](../../spec/013-Flows.md)) | MIGRATION M-21 |
| `enrich` interceptor | Flows (derived state) / `:schema` (validation) / custom `->interceptor` (escape hatch) | MIGRATION M-21 |
| `after` interceptor | Registered fx (`:fx [[:my-fx ...]]`) for side-effects; custom `->interceptor` for context-shaped work; vendor from v1 if the helper is wanted as a local utility | MIGRATION M-21 |
| `with-overrides` (v1 macro name) | Renamed to `with-fx-overrides`. | MIGRATION M-50 |

## Why these went

A few one-line rationales for the larger cuts:

- **`reg-global-interceptor`** — the v1 surface conflated "global" (process-wide) and "global within this frame" (frame-wide). The frame-wide answer is `reg-frame :interceptors`; the process-wide answer is `register-listener!` (observation, not mutation). Splitting the two ended the confusion.
- **`reg-sub-raw`** — the v1 escape hatch covered four distinct use cases (app-db reads, async sources, lifecycle, external reactivity). Each now has its own surface — `reg-sub`, Pattern-AsyncEffect, state machines, the adapter contract — and the escape hatch isn't necessary.
- **`re-frame.alpha`** — the alpha namespace was an experiment to unify registration / dispatch under generic `reg` / `sub` / `dis` verbs. The unification didn't pay for itself — the per-kind macros read better at the call site and survive better in the linter — and the alpha namespace is dissolved. No APIs in this reference live outside `re-frame.core` (with the documented per-namespace exceptions).
- **The five v1 interceptors** (`debug`, `trim-v`, `on-changes`, `enrich`, `after`) — each was either trivially `->interceptor`-able (so didn't earn its own surface), or replaced by a richer mechanism (flows, schemas, the trace surface).

## See also

- [MIGRATION.md](../../migration/from-re-frame-v1/README.md) — the AI-driven migration spec, with one rule per row above.
- [01 — Core](01-core.md) — the surfaces that replaced the removed ones.
