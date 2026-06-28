# 15 — Removed / not shipped

This chapter is the tombstone register. It exists so re-frame v1 migrators can find out, in one place, **what's gone** and **what to use instead**.

| Removed / not shipped | What to do instead | Reference |
|---|---|---|
| `dispatch-with` (master) | `(dispatch event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-sync-with` (master) | `(dispatch-sync event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatcher` | `(:dispatch (rf/capture-frame))` *or* the `dispatch` injected in a `reg-view` body. | 002 |
| `subscriber` | `(:subscribe (rf/capture-frame))` *or* the `subscribe` injected in a `reg-view` body. | 002 |
| `bound-fn` (CLJS macro) | `(rf/capture-frame)` — the keystone operation bundle; or an explicit `{:frame …}` opt. (`frame-bound-fn` / `frame-bound-fn*` are internal, not app API.) | 002 |
| `current-frame` | Use `current-frame-id` — it returns a frame-id keyword. | 002 |
| `get-frame-db` | Use `app-db-value` — it returns the `app-db` VALUE (a plain map), `nil` for an unregistered / destroyed frame. The container accessor is the internal `re-frame.frame/app-db-container`. | 002 |
| Bare `[:my-view "args"]` keyword-tagged hiccup | Var form `[my-view "args"]` (canonical) or `[(rf/view :my-view) "args"]` for late-binding by id. | 004 |
| `reg-frame` / `make-frame` / `frame-provider` `:on-create` construction key | `:initial-events` — an ordered vector of setup events dispatched synchronously at frame creation: `:initial-events [[:checkout/open] …]`. Construction is events-only. Supplying `:on-create` **fails loud at `reg-frame`** with `:rf.error/on-create-retired` (never a silent ignore). | EP-0027 |
| `reg-frame` / `make-frame` / `frame-provider` `:initial-db` construction key | There is no separate `app-db`-seeding data key — seeding `app-db` is itself an event, `[:rf/set-db {…}]` (see [01 — Core §Standard events](01-core.md#standard-events)), placed in `:initial-events`: `:initial-events [[:rf/set-db {:n 0}]]`. Supplying `:initial-db` **fails loud** with `:rf.error/initial-db-retired`. | EP-0027 |
| `reg-global-interceptor` | `reg-frame :interceptors` — frame-level is the canonical "global within this frame." For cross-frame observation, use `register-listener!`. | MIGRATION M-17 |
| `clear-global-interceptor` | No replacement needed — re-register `reg-frame` with an updated `:interceptors` vector (absent-key semantics clear it). | MIGRATION M-17 |
| `reg-sub-raw` | `reg-sub` for app-db reads; Pattern-AsyncEffect for non-app-db sources; state machines for lifecycle; the [006](../../spec/006-ReactiveSubstrate.md) adapter contract for bridging external reactivity. | MIGRATION M-18 |
| `re-frame.alpha/reg` | The shipped per-kind registrars: `reg-event` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-flow`. (The v1 event trio `reg-event-db` / `reg-event-fx` / `reg-event-ctx` is **not** a v2 target — those are removed/withdrawn throwing stubs and migration inputs only, see the rows below and EP-0018; `reg-event` is the single event-registration form.) The `re-frame.alpha` namespace is dissolved. | MIGRATION M-23 |
| `re-frame.alpha/sub` | Vector-form `(rf/subscribe [::id arg])`. | MIGRATION M-23 |
| `re-frame.alpha/reg-sub-lifecycle` and built-in lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) | Sub-cache uses a single algorithm — synchronous ref-counting (dispose on derefer-count → 0). | MIGRATION M-23 |
| `debug` interceptor | Trace surface ([Spec 009](../../spec/009-Instrumentation.md)) + 10x / re-frame-pair | MIGRATION M-21 |
| `trim-v` interceptor | Canonical map-payload call shape | MIGRATION M-21 |
| `on-changes` interceptor | Flows ([Spec 013](../../spec/013-Flows.md)) | MIGRATION M-21 |
| `enrich` interceptor | Flows (derived state) / `:schema` (validation) / custom `->interceptor` (escape hatch) | MIGRATION M-21 |
| `after` interceptor | Registered fx (`:fx [[:my-fx ...]]`) for side-effects; custom `->interceptor` for context-shaped work; vendor from v1 if the helper is wanted as a local utility | MIGRATION M-21 |
| `inject-cofx` / `inject-cofx*` (coeffect-delivery interceptors) | Declare the facts a handler needs with `:rf.cofx/requires` registration metadata; the runtime supplies them. Register the supplier with value-returning `reg-cofx`. There is no interceptor that delivers a coeffect — see [01 — Core §`reg-cofx`](01-core.md#reg-cofx) and [Guide — Effects and coeffects](../guide/concepts/effects-and-coeffects.md). | EP-0017 |
| `reg-event` positional interceptor vector middle slot | Put the chain in registration metadata: `(reg-event :id {:interceptors [i1 i2]} handler)`. If a call also has metadata, merge `:interceptors` into that map. | MIGRATION M-70 |
| `reg-event-db` | Use `reg-event` (no alias). Destructure `:db` from the coeffects map and wrap the return in `{:db …}`: `(reg-event id (fn [{:keys [db]} ev] {:db BODY}))`. A stale call raises the always-on hard error `:rf.error/reg-event-db-removed` naming `reg-event`. | EP-0018 / MIGRATION M-73 |
| `reg-event-fx` | Use `reg-event` (no alias) — the identical shape under the bare name (coeffects in, effects out); just rename the call. A stale call raises `:rf.error/reg-event-fx-removed`. | EP-0018 / MIGRATION M-73 |
| `reg-event-ctx` | A framework-internal primitive, not a public surface. Express application full-context work as a **registered interceptor** (`reg-interceptor` with `:before` / `:after`, referenced by id from a `reg-event` registration's `:interceptors` chain). A stale public call raises `:rf.error/reg-event-ctx-removed` naming `reg-interceptor`. | EP-0018 / MIGRATION M-73 |
| `with-overrides` (v1 macro name) | Use `with-fx-overrides`. | MIGRATION M-50 |
| `add-marks` / `set-marks` (imperative `app-db` path-marks) | Classify durable `app-db` paths with the **commit-plane effects**: a `reg-event` returns `:sensitive` / `:large` (or `:clear-sensitive` / `:clear-large`) alongside `:db`, run at frame creation via `:initial-events`. There is no `re-frame.marks` namespace; classification rides `re-frame.classification` / `re-frame.elision`. | EP-0025 / 015 |
| `reg-frame` / `make-frame` `:sensitive` / `:large` frame keys (frame-owned durable classification + the `:sensitive {:http …}` HTTP carrier block) | A config carrying `:sensitive` or `:large` **fails loud at registration**. Classify durable `app-db` paths with the commit-plane effects (above); declare app-specific HTTP carrier names on the `:rf.http/managed` `reg-fx` registration's `:carriers` block (`(rf/reg-fx :rf.http/managed {:carriers {:headers […]}} h)`). The frame still owns the `:observability` sink policy. | EP-0025 / 015 |
| `redact-interceptor` (positional payload-scrub interceptor) | Classify transient payloads on the **registration**: `reg-event-*` `:sensitive` / `:large` metadata. Projection happens centrally at egress, not via interceptor placement. | EP-0025 §7 / 015 |
| Schema-attached `:sensitive?` / `:large?` as the **`app-db`** classification route | `app-db` classification is event-owned via the commit-plane effects (above). Per-slot `:sensitive?` / `:large?` Malli props remain the route for *owner-local schema'd* data — machine `:data`, resource data/params, HTTP `:decode` bodies. | EP-0025 §8 / 015 |
| `redact-derived-slots` / `populate-elision-from-schemas!` / `populate-sensitive-from-schemas!` (value-match derived-tree dual + schema-prop boot hydrators) | Not on the facade or in `re-frame.elision`. Path-walk a derived tree with **`project-egress`** under a `:rf.observe/derived-tree` record (it path-walks each slot through `elide-wire-value`); classify durable `app-db` paths with the commit-plane effects. Schemas are not a second durable-classification route. | EP-0025 / 015 |
| `:rf.privacy/show-sensitive?` / `set-show-sensitive!` (process-global on/off privacy toggle) | There is no process-global toggle. On-box visibility is a named `:rf.egress/*` profile **per (tool, frame)** — `:rf.egress/local-redacted` (default, fail-closed) / `:rf.egress/local-raw` (trusted-local opt-in). | EP-0015 issue 7 / 015 |

## See also

- [MIGRATION.md](../../migration/from-re-frame-v1/README.md) — the AI-driven migration spec, with one rule per row above.
- [01 — Core](01-core.md) — the surfaces that replaced the removed ones.
