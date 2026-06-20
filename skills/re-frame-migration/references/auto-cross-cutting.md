# auto-cross-cutting

Type A — cross-cutting mechanical rewrites the agent applies without asking. Covers framework-keyword renames, interceptor-list cleanup, view / hiccup rewrites, dropped public-surface drops, init wiring, and per-feature artefact adds.

For the *why* of each rule, see [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md). This leaf is a shape catalogue, not a rationale. For per-call-site mechanical rewrites (namespaces, effect-map, dispatch shapes), see [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md). For judgment-call rewrites, see [`guided-handlers-state.md`](guided-handlers-state.md) and [`guided-interceptors-subs.md`](guided-interceptors-subs.md).

## Contents

- Framework keyword renames (M-20, M-35, M-54)
- Tear-down verb renames (M-53)
- Listener-registration verb unification (M-55)
- `:rf.http/managed` `:retry :on` closed-set (M-31b)
- Interceptor list cleanup (M-21 mechanical half)
- Event interceptor chains → metadata `:interceptors` (M-70 — mechanical; **loud-at-runtime, not loud-at-compile**: structural grep up front)
- View / hiccup rewrites (M-22, M-24)
- `reg-event` shape (M-26 mechanical half)
- Init / adapter (M-40 — **Type B**; shape is here, the decision is asked-first)
- Per-feature artefact adds (M-27 through M-33)

---

## Framework keyword renames (M-20)

Closed mechanical rename table. Apply across all source files.

```
:re-frame/<x> → :rf/<x> ; v1-survivors (mechanical rename only; no runtime alias)
:registry/<x> → :rf.registry/<x>
:machine/<x> → :rf.machine/<x>
:machine.lifecycle/<x> → :rf.machine.lifecycle/<x>
:machine.timer/<x> → :rf.machine.timer/<x>
:machine.event/<x> → :rf.machine.event/<x>
:machine.microstep/<x> → :rf.machine.microstep/<x>
:nav/<x> → :rf.nav/<x>
:route/<framework-id> → :rf.route/<framework-id>
```

**Framework `:route/*` ids are the closed list** in [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) M-20 (`:route/navigate`, `:route/url-changed`, `:route/handle-url-change`, `:route/not-found`, `:route/navigation-blocked`, `:route/continue`, `:route/cancel`, `:route/error`, `:route/transition`, `:route/resolved`, `:route/auth-guard`, `:route/equal`, `:route/chain`). One exception to the mechanical `:route/<x>` → `:rf.route/<x>` rewrite: `:route/url-changed` maps to the runtime event `:rf.route/transitioned` (the v2 trace op `:rf.route/url-changed` was renamed to `:rf.route/fragment-changed`, leaving no rename target for the v1 event-id). The closed framework-id list in [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) M-20 is the source of truth for the per-id rewrite target.

**User `:route/<name>` ids** are user-defined and left alone (mechanical) or rewritten to a feature prefix (suggested, Type B). The closed framework list is the discriminator.

The route **slice** rewrite is **NOT blanket Type-A** — scope it to framework routing only. Rewrite the slice path `[:route]` → the runtime-db path `[:rf.runtime/routing :current]`, and the subscription head `[:route]` → `[:rf/route]`, **only** when the app has adopted Spec 012 framework routing (the opt-in O-8 routing migration — evidence: `re-frame.routing` / `day8/re-frame2-routing` loaded, `:rf.route/*` events dispatched, `reg-route` declared). Most v1 apps keep a **third-party router** (`secretary`, `reitit`, `bidi`) and store route state at an app-owned `[:route]` slice — that is **user state**, not a framework slot; rewriting it would break app-owned router state and falsely require the routing artefact. Absent framework-routing evidence or an explicit O-8 decision, leave `[:route]` as user state and surface it as a **Type-B** note. When routing is adopted, the route slice lives in the runtime-db partition at `[:rf.runtime/routing :current]` (the `:rf/route` sub keyword is unchanged), read via the `:rf/route` sub or `runtime-db-value`. (The framework `:route/<id>` keyword renames above stay mechanical Type-A regardless — only the *slice path* rewrite is scoped.)

### M-35 — actor-lifecycle fx-id rename

```
[:spawn ...] → [:rf.machine/spawn ...]
[:destroy-machine ...] → [:rf.machine/destroy ...]
```

### M-54 — schema vocabulary unification (`:spec` → `:schema`)

Closed mechanical rename set. Apply across all source files. The dual-key read `(or (:schema meta) (:spec meta))` was stripped — `:spec` on `reg-*` metadata is no longer accepted, and the `:rf.warning/deprecated-schema-alias` warning is gone with it. Stale `:spec` slots are silently ignored (schemaless registrations), so an incomplete rewrite is a correctness hazard — sweep every slot.

```
;; Framework-reserved keyword renames — single-token global rewrites:
:rf.spec/violation → :rf.schema/violation
:spec/at-boundary → :rf.schema/at-boundary

;; Trace-tag rename — only inside trace-handler destructures or tag maps:
:spec-id → :schema-id

;; Per-`reg-*` metadata key rename — only inside registration metadata maps
;; (the position immediately after the reg-* id, before the handler-fn; for
;; event registrations, interceptor chains live in this map under :interceptors):
{:spec <schema>} → {:schema <schema>}
```

**What to rewrite (positional rule for `:spec` → `:schema`).**

```clojure
;; SEARCH — :spec inside a reg-* metadata map
(rf/reg-event-fx :auth/login
 {:doc "..." :spec LoginSchema} ;; <- target
 (fn ...))

;; REWRITE
(rf/reg-event :auth/login
 {:doc "..." :schema LoginSchema}
 (fn ...))
```

**What to NOT rewrite.** Do NOT rewrite the bare `:spec` keyword outside a registration metadata-map slot:

- `{:keys [spec]}` destructure of a non-framework data shape — leave alone.
- `(:spec invoke-all-state)` — the machine `:spawn-all` join state carries `:spec` for the live spec map (see [Spec-Schemas §runtime-db](../../../spec/Spec-Schemas.md#rfruntime-reserved-app-db-key--the-sole-framework-owned-root) — the join-state lives in runtime-db at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`); that `:spec` is a different domain and is NOT renamed by M-54.
- The namespace `re-frame.spec` — NOT renamed; the ns alias is preserved for back-compat. Do **not** rewrite an `at-boundary` chain entry to the `validate-at-boundary-interceptor` Var: under EP-0022 the boundary validator is cited as the framework-registered ref `:rf.schema/at-boundary` by id in `:interceptors` (e.g. `{:schema S :interceptors [:rf.schema/at-boundary]}`); the `validate-at-boundary-interceptor` Var is the framework's registration-boundary input only, never an inline chain entry.

**No alias semantics.** Per pre-alpha posture, the framework no longer accepts `:spec` on `reg-*` metadata — the dual-key read and the `:rf.warning/deprecated-schema-alias` were stripped. A `:spec` slot left in metadata is silently ignored (the registration becomes schemaless), so an incomplete rewrite is a correctness hazard. Sweep every `:spec` metadata-map slot to `:schema` in one pass; do not rely on a deprecation warning to find stragglers.

**Cross-references.** [`MIGRATION.md` §M-54](../../../migration/from-re-frame-v1/README.md#m-54-schema-vocabulary-unification--spec--schema) for the full table and rationale; [`breaking-changes.md`](breaking-changes.md) for the surface-level breakage summary.

---

## Tear-down verb renames (M-53)

Closed mechanical rename table. Per the tear-down verb axis discipline (see [Conventions §Tear-down verb axis](../../../spec/Conventions.md#tear-down-verb-axis--clear--vs-destroy-)) the public tear-down surface collapses onto two verbs — `clear-` (registrar / cache / buffer decrement) and `destroy-` (lifecycle boundary). One outlier name renames:

```
(rf/dispose-adapter!) → (rf/destroy-adapter!)
```

The public `re-frame.core/dispose-adapter!` Var is **removed** — stale call sites raise unresolved-symbol at compile time. There is no deprecation cycle. The adapter-spec **map key** `:dispose-adapter!` (the slot adapter implementations provide) is unchanged — adapters keep returning `{:dispose-adapter! (fn [] ...)}` in their spec map. Only the public `re-frame.core` wrapper name moves.

`rf/unsubscribe` is **not** renamed: the natural target `clear-sub` is already taken by the symmetric inverse of `reg-sub` (the registrar decrement). The `un-` prefix is carved out as the singular form for the sub-cache ref-count decrement. See the [Conventions §Tear-down verb axis — Carve-out](../../../spec/Conventions.md#carve-out-unsubscribe).

The rest of the tear-down surface (`clear-event` / `clear-sub` / `clear-sub-cache!` / `destroy-frame!` / `clear-trace-buffer!` / `clear-fx` / `clear-flow` / `clear-http-interceptor` / `clear-listeners!`) is already on the two-verb axis and needs no rewrite.

---

## Listener-registration verb unification (M-55)

> **Superseded for the event/error-emit half by M-69.** M-69 consolidates the event-emit / error-emit listener names along a namespace-based axis (`register-event-emit-listener!` → `register-event-listener!`, `register-error-emit-listener!` → `register-error-listener!`, and the trace half `register-trace-listener!` → `register-listener!`). On a v2-pre-rename codebase apply the **M-69** table for those names; see `breaking-changes.md` M-69. (A pure v1→v2 migration lands directly on the current names via M-26 and never sees either M-55 or M-69.)

Closed mechanical rename table. The trace and epoch listener APIs collapse onto the same `register-*-listener!` / `unregister-*-listener!` shape already used by `register-event-listener!` / `register-error-listener!`. Affects v2-pre-rename codebases only — v1 had no trace/epoch-listener concept (v1's `add-post-event-callback` lands on the new name via M-26).

```
(rf/register-trace-cb! ...) → (rf/register-listener! ...)
(rf/remove-trace-cb! ...) → (rf/unregister-listener! ...)
(rf/clear-trace-cbs! ...) → (rf/clear-listeners! ...)
(rf/register-epoch-cb! ...) → (rf/register-epoch-listener! ...)
(rf/remove-epoch-cb! ...) → (rf/unregister-epoch-listener! ...)
(rf/clear-epoch-cbs! ...) → (rf/clear-epoch-listeners! ...)
```

**Late-bind hook keys** (tool authors only — most apps will not touch these):

```
:trace.tooling/register-trace-cb! → :trace.tooling/register-listener!
:trace.tooling/remove-trace-cb! → :trace.tooling/unregister-listener!
:epoch/register-epoch-cb! → :epoch/register-epoch-listener!
:epoch/remove-epoch-cb! → :epoch/unregister-epoch-listener!
:epoch/clear-epoch-cbs! → :epoch/clear-epoch-listeners!
```

The old names are **removed** — stale call sites raise unresolved-symbol at compile time. There is no deprecation cycle.

**Cross-references.** [`MIGRATION.md` §M-55](../../../migration/from-re-frame-v1/README.md#m-55-listener-registration-verb-unification--register--cb--register--listener) for the full table; [009 §The trace event model](../../../spec/009-Instrumentation.md#the-trace-event-model) (the trace listener API).

---

## `:rf.http/managed` `:retry :on` closed-set (M-31b)

The `:retry :on` set on `:rf.http/managed` requests no longer accepts arbitrary `:rf.http/*` keywords. The closed retryable subset is:

```
#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}
```

Any keyword outside this set in `:retry :on` raises `:rf.error/http-bad-retry-on` at fx-call time, before the request is issued. The three excluded `:rf.http/*` categories (`:rf.http/aborted` / `:rf.http/decode-failure` / `:rf.http/accept-failure`) are deterministic on retry and were silently retrying as a no-op. Sweep `:retry :on` sets, drop excluded categories. v1 had no `:rf.http/managed` fx; v2-pre-rename codebases only.

**Cross-references.** [`MIGRATION.md` §M-31b](../../../migration/from-re-frame-v1/README.md#m-31b-rfhttpmanaged-retry-on-is-a-closed-set); [Spec 014 §Closed-set `:retry :on` validation](../../../spec/014-HTTPRequests.md#closed-set-retry-on-validation).

---

## Interceptor list cleanup (M-21 mechanical half)

Drop `debug` and `trim-v` from interceptor lists:

```clojure
;; SEARCH
(rf/reg-event-fx :foo
 [rf/debug rf/trim-v <other-interceptors>]
 <handler>)

;; REWRITE
(rf/reg-event :foo
 {:interceptors [<other-interceptors>]}
 <handler>)
```

If the interceptor list becomes empty after dropping `debug`/`trim-v`, drop the empty vector slot entirely:

```clojure
(rf/reg-event :foo <handler>)
```

The surviving `<other-interceptors>` are **not** carried into the metadata chain as inline values — under EP-0022 the chain holds references. Each survivor that is an inline interceptor value runs through M-70 (register it once with `reg-interceptor`, then reference it by id); a survivor that is already a ref (a bare keyword id or `[:rf.interceptor/path [...]]`) stays as-is.

**`trim-v` reaches M-19 territory** (the handler may have positional destructure). Flag the handler shape — the M-19 sweep handles destructure rewriting separately.

`on-changes` / `enrich` / `after` → Type B, see [`guided-interceptors-subs.md`](guided-interceptors-subs.md).

---

## Event interceptor chains → metadata `:interceptors` (M-70 — mechanical; loud-at-runtime, not loud-at-compile)

v2's `reg-event` puts per-event interceptor chains in the registration metadata map under `:interceptors` — and under EP-0022 that chain carries **interceptor references**, never inline interceptor values. Bare interceptors, positional vectors, and metadata-plus-vector forms all compile but throw at registration / ns-load; an inline value anywhere in the metadata chain throws `:rf.error/inline-interceptor-removed`. So the rewrite is two-step: **register each interceptor value once with `reg-interceptor` (under a qualified id), then reference it by that id in the chain.** Move each chain into metadata, by reference:

```clojure
;; SEARCH — bare interceptor (Var, inline ->interceptor, path, …)
(rf/reg-event-db :save-progress mw/with-progress-completion
 <handler>)

;; REWRITE — register the interceptor value once, then reference it by id.
;; The db handler also reshapes under the one form reg-event:
;;   (fn [db ev] new-db) → (fn [{:keys [db]} ev] {:db new-db})
(rf/reg-interceptor :app/with-progress-completion mw/with-progress-completion)
(rf/reg-event :save-progress
 {:interceptors [:app/with-progress-completion]}    ;; ref by id — NOT the inline value
 (fn [{:keys [db]} ev] {:db <handler-body>}))

;; SEARCH — positional vector
(rf/reg-event-db :save-progress
 [mw/with-progress-completion audit]
 <handler>)

;; REWRITE — register both values once, then reference both by id, in order
(rf/reg-interceptor :app/with-progress-completion mw/with-progress-completion)
(rf/reg-interceptor :app/audit audit)
(rf/reg-event :save-progress
 {:interceptors [:app/with-progress-completion :app/audit]}   ;; refs, not values
 (fn [{:keys [db]} ev] {:db <handler-body>}))

;; SEARCH — metadata + positional vector
(rf/reg-event-db :save-progress
 {:doc "Track save progress."}
 [mw/with-progress-completion]
 <handler>)

;; REWRITE — merge the chain into the existing metadata map, as a ref
(rf/reg-interceptor :app/with-progress-completion mw/with-progress-completion)
(rf/reg-event :save-progress
 {:doc "Track save progress."
  :interceptors [:app/with-progress-completion]}    ;; ref by id
 (fn [{:keys [db]} ev] {:db <handler-body>}))
```

The rewrite is mechanical (`mw/x` / `[mw/x]` / `{:doc ...} [mw/x]` → `reg-interceptor :app/x mw/x` + metadata `:interceptors [:app/x]`), and **this rule is loud-at-runtime — but NOT loud-at-compile**. The throw fires at ns-load / first page-load, so a missed site **compiles clean** and only detonates when the app boots — where it **aborts the offending ns's load** (everything after it, incl. a boot machine's `reg-machine`, never registers → the app hangs). So the *compiler* can't find them: **grep every `reg-event-*` site up front and inspect the post-id SHAPES at each** — do NOT march-the-wall (the compiler never points you at the next occurrence), and the **boot smoke-test** ([`runtime-smoke-test.md`](runtime-smoke-test.md)) surfaces any survivor's throw on the console:

```bash
# Surface every reg-event-* registration; a hit = bare interceptor, positional
# vector, or metadata map followed by a vector. STRUCTURAL — flag ANY chain
# shape, not only rf/unwrap.
rg -n '\(rf/reg-event-(db|fx|ctx)\b' src
```

An existing metadata map with `:interceptors [...]` is already canonical. A metadata map without `:interceptors` and no following vector is fine. The detection is **by slot-shape, not by interceptor identity** — a real worker missed a bare `mw/complete-progress` by anchoring on `unwrap`; flag any bare, vector, or metadata-plus-vector chain shape. The registration guard is loud-at-*runtime* only, so the structural up-front grep + boot smoke-test remain the detectors.

---

## View / hiccup rewrites

### M-22 — `reg-view` defn-shape

```clojure
;; SEARCH — keyword-shape call
(def my-view
 (rf/reg-view :ns/my-view (fn [args] body)))

;; REWRITE — when the id matches (keyword *ns* "my-view")
(rf/reg-view my-view [args] body)

;; REWRITE — when the id is explicit and doesn't match auto-derivation
(rf/reg-view ^{:rf/id :ns/my-view} my-view [args] body)
```

Inside the body, the `reg-view` macro injects frame-aware `dispatch` / `subscribe` locals — call them bare (no `rf/` qualifier, no frame-capture). If a v2-pre-rename body holds the legacy `(rf/dispatcher)` / `(rf/subscriber)` captures, drop them (M-68); the injected locals do the same job:

```clojure
;; SEARCH (inside reg-view body)
[:button {:on-click #(rf/dispatch [:inc])} @(rf/subscribe [:count])]

;; REWRITE — use the injected frame-aware locals
[:button {:on-click #(dispatch [:inc])} @(subscribe [:count])]
```

**Edge case → flag (Type B)**: body is not a literal `(fn [args] body)` (Var ref, `reagent.core/create-class`, computed `fn`). Use `re-frame.core/reg-view*` (plain-fn surface) and surface to the author.

### M-24 — `rf/h` removal

```clojure
;; SEARCH — namespaced view keyword nested in hiccup
(rf/h [:div [:my-app/widget arg]])
;; REWRITE
[:div [my-app/widget arg]] ; Var ref (resolves to the symbol the reg-view macro defed)

;; SEARCH — late-binding intent
(rf/h [:my-app/widget arg])
;; REWRITE
[(rf/view :my-app/widget) arg]

;; SEARCH — HTML-only hiccup wrapped in h
(rf/h [:div [:p "hello"]])
;; REWRITE
[:div [:p "hello"]]
```

Default to Var-ref form unless the call site comments / context indicate late-binding intent. The reverse migration to `view` is a one-line edit.

---

## `reg-event` shape (M-26 mechanical half)

Drop / rewrite the dropped public surfaces:

```clojure
(rf/with-trace ...) → (rf/emit-trace-event! op-type operation tags)
(rf/merge-trace! ...) → no equivalent; drop or convert to one emit-trace-event!
(rf/finish-trace ...) → drop
rf/trace-api-version → drop (no replacement)
(rf/purge-event-queue) → drop (no replacement); rewrite tests to use 008's helpers
(rf/dispatch-and-settle e) → (rf/dispatch-sync e)
@(rf/dispatch-and-settle e) → (rf/dispatch-sync e) ; the deref is gone; settle is default
(rf/spawn-machine spec) → wrap in a reg-event handler returning {:fx [[:rf.machine/spawn spec]]}
(rf/destroy-machine id) → wrap in a reg-event handler returning {:fx [[:rf.machine/destroy id]]}
```

**Type B → see [`guided-interceptors-subs.md`](guided-interceptors-subs.md) (M-26) and [`guided-handlers-state.md`](guided-handlers-state.md) (M-13)**: `add-post-event-callback` / `remove-post-event-callback` / `reg-event-error-handler`.

---

## Init / adapter (M-40)

> **M-40 is Type B — ask first.** MIGRATION.md classifies M-40 as a judgment call: the rewrite shape below is mechanical *given* a chosen adapter, but the agent must surface every `(rf/init!)` call site and let the author confirm which adapter the app boots against (the codebase may run more than one substrate, or boot SSR-side against a different adapter). Do not apply it silently as part of the Type A sweep — present the call sites and the proposed adapter, then apply on confirmation. The shape lives in this leaf only because it pairs with M-38's mechanical ns rename.

> **Single-substrate ⇒ mechanical fast-path** (the same shape M-17 / M-11 carry for single-frame apps). Once M-0 has committed **exactly one** adapter artefact to the classpath (the common case — `day8/re-frame2-reagent` and nothing else), `(rf/init! <adapter>)` is unambiguous: there is only one adapter to install, so there is no real choice to make. Don't stall on the Type-B "ask first" gate for a solo-adapter app — the gate exists for the **multi-substrate / ambiguous-root** cases (a codebase running more than one substrate, a `.cljc` app with platform-branched roots, or an SSR boot that installs a different adapter than the browser boot). Confirm "single adapter on the classpath?" and, if yes, apply the rewrite mechanically (still under the sweep-level announcement, Cardinal rule 4). MIGRATION.md says the same — "single-substrate apps are mechanical, mixed-substrate or `.cljc` apps with platform branches need per-site direction" ([§M-40](../../../migration/from-re-frame-v1/README.md#m-40-rfinit-requires-an-explicit-adapter-spec-map)).

```clojure
;; SEARCH
(rf/init!)

;; REWRITE — after the author confirms the adapter
(rf/init! reagent/adapter) ; or uix/adapter, helix/adapter, per the confirmed substrate
```

The adapter value is the `adapter` Var from the substrate adapter ns (e.g. `(:require [re-frame.adapter.reagent :as reagent])` → `reagent/adapter`), verified against `re-frame.core/init!`'s docstring (`implementation/core/src/re_frame/core.cljc` — "Pass the adapter spec map directly"). Pair with M-38's substrate-ns rename so the symbol resolves; non-map / nil args raise `:rf.error/no-adapter-specified`.

#### Boot-sequence invariant — `init!` must run *before* the first dispatch and the first render

> **A v1 app has no `init!` call site to "find". You must ADD one — and ADD it in the right place.** The `SEARCH` shape above assumes a `(rf/init!)` already exists (the v2-pre-rename case). A genuine v1 app has **no `init!` at all**: in v1 the registry is populated by `reg-event-*` at namespace-load and the global `app-db` ratom exists immediately, so v1 boot code dispatches freely — the canonical v1 boot is `(dispatch-sync [:initialize-db])` *then* `(reagent.dom/render …)`. M-40-as-a-rename does not cover this; for a v1 app, M-40 is an **add**, governed by this invariant.

**The invariant:** `(rf/init! <adapter>)` MUST execute **before the first `dispatch` / `dispatch-sync` AND before the first render**. Under EP-0002 (the carried-frame invariant), `init!` installs adapters and runtime capabilities **only — it does NOT create any frame**. There is **no ambient `:rf/default`** the runtime synthesises for you; frame identity is *carried, not found*. So a v1 boot needs **two** adds, not one: (1) `init!`, and (2) an explicitly-registered **app frame** that boot-time dispatches and the render run *under*. You register it (`reg-frame`) and establish it as a lexical scope (`with-frame`) or React scope (`frame-provider-existing` — the EP-0024 scope-only React-context member, since the frame already exists from `reg-frame`).

**Two distinct failure modes — both are surfaced loud now.**

- **No `init!` (or `init!` too late).** The adapter/runtime isn't wired; the React-context tier of frame resolution is dead.
- **No established frame scope.** A boot `(dispatch-sync [:initialize-db])` issued under no scope (no `with-frame`, no `frame-provider-existing`) now fails **loudly** with **`:rf.error/no-frame-context`** — the runtime refuses to synthesise a frame from absence (it does **not** silently target a default). This is *better* than the old silent no-op: the error fires at the offending call site through the always-on error axis. Cross-link the symptom from the error catalogue ([`error-events.md`](error-events.md) → [Spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue)).

> **Field failure mode:** placing `(rf/init! adapter)` *inside* the render fn (e.g. a `mount-gui` defn) runs it **after** the boot code's seed `dispatch-sync` and any bootstrap dispatch. Put `init!` at the **top of the boot function**, ahead of every boot-time dispatch and the render call — and wrap the boot dispatches in the app frame's scope.

**Corrected canonical v2 boot order** (a v1 app ADDs *both* the `init!` line and the app-frame registration + scope). Seed via `:on-create` — it runs synchronously at `reg-frame` time, inside the frame's own scope:

```clojure
(ns my-app.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent]   ; the adapter ns (M-38)
            [reagent.dom.client :as rdomc]))         ; React-19 createRoot path

(def app-frame :app/main)             ; pick an explicit app-frame id
                                      ; (a migration MAY use :rf/default —
                                      ;  but it is still registered, never inferred)

(defn ^:export init []                ; the boot entry point
  (rf/init! reagent/adapter)          ; 1. ADD: install adapter (creates NO frame)
  (rf/reg-frame app-frame             ; 2. ADD: register the app frame, seeding
    {:on-create [:initialize-db]})    ;    via :on-create — dispatch-sync'd into the
                                      ;    fresh frame; by the time reg-frame RETURNS,
                                      ;    app-db already reflects the seed cascade.
  (rdomc/render (rdomc/create-root el); 3. render LAST, UNDER frame-provider-existing:
    [rf/frame-provider-existing {:frame app-frame}  ; scope-only — frame already exists
     [app-root]]))
```

`:on-create` is the single seed mechanism — **do not also `dispatch-sync` the same event** from a `with-frame` wrap. The framework dispatch-syncs `:on-create` into the freshly-created frame at `reg-frame` time and drains to fixed point, so by the time `reg-frame` returns the seed has already committed (verified in [`spec/002-Frames.md` §reg-frame metadata grammar](../../../spec/002-Frames.md)). Re-dispatching `[:initialize-db]` after `reg-frame` runs the same handler **twice** — harmless for an idempotent seed, but a boot handler that emits effects, starts a machine, increments a counter, or stamps durable state then double-fires. To seed multiple events, the single `:on-create` handler emits them via its `:fx` slot (see the M-15 walkthrough in [`guided-handlers-state.md`](guided-handlers-state.md#m-15--top-level-app-db-seeding)); never repeat the `:on-create` event in a separate boot dispatch.

**Extra boot work before render** (a *different* event, not the seed) — reach for an explicit `with-frame` wrap only when boot logic must run dispatches the `:on-create` cascade doesn't already cover:

```clojure
(defn ^:export init []
  (rf/init! reagent/adapter)
  (rf/reg-frame app-frame
    {:on-create [:initialize-db]})    ; seed runs here, once
  (rf/with-frame app-frame            ; establish a scope for ADDITIONAL boot work …
    (rf/dispatch-sync [:app/extra-boot-work]))  ; … a DISTINCT event, not :initialize-db
  (rdomc/render (rdomc/create-root el)
    [rf/frame-provider-existing {:frame app-frame}
     [app-root]]))
```

Either way, **the render is wrapped in `frame-provider-existing` for the app frame** so every bare `dispatch` / `subscribe` in the tree resolves against it. (`frame-provider-existing` is the EP-0024 scope-only React-context member — the frame already exists from `reg-frame`, so you scope it, not own it; the owned `rf/frame-provider`, which creates-on-mount / destroys-on-unmount, is for view-owned frame lifetimes, not the app root.) If the v1 boot used `(reagent.dom/render …)`, that mount call is itself a React-19 rewrite (M-42 mount-path half → `react-dom/client` `createRoot` + `render`); the **ordering** rule and the provider wrap are independent of which mount API the app lands on.

---

## Per-feature artefact adds (M-27 through M-33)

When a per-feature surface is in use, add the dep AND add the `:require` of the implementing namespace to the file where it's used.

| Surface in code | Dep to add | Namespace to require |
|---|---|---|
| `reg-app-schema` / `:schema` metadata (the per-`reg-*` key — was `:spec` pre-M-54) | `day8/re-frame2-schemas` | `re-frame.schemas` |
| `reg-machine` / `sub-machine` | `day8/re-frame2-machines` | `re-frame.machines` |
| `reg-route` / `:rf.route/*` events | `day8/re-frame2-routing` | `re-frame.routing` |
| `reg-flow` / `:rf.fx/reg-flow` | `day8/re-frame2-flows` | `re-frame.flows` |
| `[:rf.http/managed ...]` (and/or the `rf.http/get` / `post` / … verb helpers) | `day8/re-frame2-http` | `re-frame.http.managed` (registers the `:rf.http/managed` fx at ns-load) **plus** `re-frame.http` (the call-site verb helpers). Require **both** in any ns using managed HTTP — `re-frame.http` is verb helpers only and does **not** register the fx, so a require of `re-frame.http` alone fails at dispatch with `:rf.error/no-such-fx`. |
| `render-to-string` (SSR) | `day8/re-frame2-ssr` | `re-frame.ssr` |
| `epoch-history` / `restore-epoch` | `day8/re-frame2-epoch` | `re-frame.epoch` |
| managed-HTTP canned-stub fxs (`:rf.http/managed-canned-success` / `-canned-failure`) or stub macros (`with-managed-request-stubs` family) **in test code** (M-31a / M-65) | (no separate Maven dep — ships with `day8/re-frame2-http`) | `re-frame.http.test-support` (in the **test** ns require closure) |

The `:require` is what triggers the artefact's load-time hook registrations. Without it the public surface throws `:rf.error/<artefact>-artefact-missing` at the first call (the `re-frame.http.test-support` require is the test-side counterpart — its omission raises `:rf.error/http-artefact-missing` from the `rf/<stub>` re-exports).

---

## What this leaf is NOT

- It is not the full Type A catalogue — per-call-site mechanical rewrites (namespaces, effect-map, dispatch shapes) live in [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md).
- It is not a substitute for [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md)'s per-rule rationale — when you apply a rewrite, you cite the rule id; you don't quote the rule's text inline.
- It is not exhaustive. The shapes here are the most common Type A trigger patterns. If a call site matches the *intent* of a Type A rule but not the *shape* here, apply the rewrite — the shapes are illustrative.

When the rewrite shape doesn't fit a real call site exactly, **stop and consult the full rule in [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md)**. Don't improvise.
