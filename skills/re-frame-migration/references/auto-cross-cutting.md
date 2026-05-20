# auto-cross-cutting

Type A — cross-cutting mechanical rewrites the agent applies without asking. Covers framework-keyword renames, interceptor-list cleanup, view / hiccup rewrites, dropped public-surface drops, init wiring, and per-feature artefact adds.

For the *why* of each rule, see [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md). This leaf is a shape catalogue, not a rationale. For per-call-site mechanical rewrites (namespaces, effect-map, dispatch shapes), see [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md). For judgment-call rewrites, see [`guided-handlers-state.md`](guided-handlers-state.md) and [`guided-interceptors-subs.md`](guided-interceptors-subs.md).

## Contents

- Framework keyword renames (M-20, M-35, M-54)
- Tear-down verb renames (M-53)
- Listener-registration verb unification (M-55)
- Interceptor list cleanup (M-21 mechanical half)
- View / hiccup rewrites (M-22, M-24)
- `reg-event-fx` shape (M-26 mechanical half)
- `:rf.http/managed` `:retry :on` closed-set (M-31b)
- Init / adapter (M-40)
- Per-feature artefact adds (M-27 through M-33)

---

## Framework keyword renames (M-20)

Closed mechanical rename table. Apply across all source files.

```
:re-frame/<x>                → :rf/<x>                  ; v1-survivors (mechanical rename only; no runtime alias)
:registry/<x>                → :rf.registry/<x>
:machine/<x>                 → :rf.machine/<x>
:machine.lifecycle/<x>       → :rf.machine.lifecycle/<x>
:machine.timer/<x>           → :rf.machine.timer/<x>
:machine.event/<x>           → :rf.machine.event/<x>
:machine.microstep/<x>       → :rf.machine.microstep/<x>
:nav/<x>                     → :rf.nav/<x>
:route/<framework-id>        → :rf.route/<framework-id>
```

**Framework `:route/*` ids are the closed list** in [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) M-20 (`:route/navigate`, `:route/url-changed`, `:route/handle-url-change`, `:route/not-found`, `:route/navigation-blocked`, `:route/continue`, `:route/cancel`, `:route/error`, `:route/transition`, `:route/resolved`, `:route/auth-guard`, `:route/equal`, `:route/chain`). One exception to the mechanical `:route/<x>` → `:rf.route/<x>` rewrite: `:route/url-changed` maps to the runtime event `:rf/url-changed` (rf2-cj9fn — the v2 trace op `:rf.route/url-changed` was renamed to `:rf.route/fragment-changed`, leaving no `:rf.route/url-changed` rename target). The closed framework-id list in [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) M-20 is the source of truth for the per-id rewrite target.

**User `:route/<name>` ids** are user-defined and left alone (mechanical) or rewritten to a feature prefix (suggested, Type B). The closed framework list is the discriminator.

Also rewrite the app-db slice key `[:route]` → `[:rf/route]` and the subscription head `[:route]` → `[:rf/route]`.

### M-35 — actor-lifecycle fx-id rename

```
[:spawn ...]              → [:rf.machine/spawn ...]
[:destroy-machine ...]    → [:rf.machine/destroy ...]
```

### M-54 — schema vocabulary unification (`:spec` → `:schema`, rf2-ieu0i)

Closed mechanical rename set. Apply across all source files. Per rf2-0zlcd (pre-alpha posture: no back-compat shims), the dual-key read `(or (:schema meta) (:spec meta))` was stripped — `:spec` on `reg-*` metadata is no longer accepted, and the `:rf.warning/deprecated-schema-alias` warning is gone with it. Stale `:spec` slots are silently ignored (schemaless registrations), so an incomplete rewrite is a correctness hazard — sweep every slot.

```
;; Framework-reserved keyword renames — single-token global rewrites:
:rf.spec/violation               → :rf.schema/violation
:spec/at-boundary                → :rf.schema/at-boundary

;; Trace-tag rename — only inside trace-handler destructures or tag maps:
:spec-id                         → :schema-id

;; Per-`reg-*` metadata key rename — only inside registration metadata maps
;; (the position immediately after the reg-* id, before any interceptor
;; vector / handler-fn):
{:spec <schema>}                 → {:schema <schema>}
```

**What to rewrite (positional rule for `:spec` → `:schema`).**

```clojure
;; SEARCH — :spec inside a reg-* metadata map
(rf/reg-event-fx :auth/login
  {:doc "..." :spec LoginSchema}                          ;; <- target
  (fn ...))

;; REWRITE
(rf/reg-event-fx :auth/login
  {:doc "..." :schema LoginSchema}
  (fn ...))
```

**What to NOT rewrite.** Do NOT rewrite the bare `:spec` keyword outside a registration metadata-map slot:

- `{:keys [spec]}` destructure of a non-framework data shape — leave alone.
- `(:spec invoke-all-state)` — the machine `:spawn-all` join state carries `:spec` for the live spec map (see [Spec-Schemas §`:rf/spawned`](../../../spec/Spec-Schemas.md#rfspawned-reserved-app-db-key)); that `:spec` is a different domain and is NOT renamed by M-54.
- The namespace `re-frame.spec` — NOT renamed; the ns alias is preserved for back-compat. Reach the interceptor through `re-frame.core/validate-at-boundary-interceptor` going forward.

**No alias semantics (rf2-0zlcd).** Per pre-alpha posture, the framework no longer accepts `:spec` on `reg-*` metadata — the dual-key read and the `:rf.warning/deprecated-schema-alias` were stripped. A `:spec` slot left in metadata is silently ignored (the registration becomes schemaless), so an incomplete rewrite is a correctness hazard. Sweep every `:spec` metadata-map slot to `:schema` in one pass; do not rely on a deprecation warning to find stragglers.

**Cross-references.** [`MIGRATION.md` §M-54](../../../migration/from-re-frame-v1/README.md#m-54-schema-vocabulary-unification--spec--schema-rf2-ieu0i) for the full table and rationale; [`breaking-changes.md`](breaking-changes.md) for the surface-level breakage summary.

---

## Tear-down verb renames (M-53)

Closed mechanical rename table. Per the tear-down verb axis discipline (rf2-cmabc; see [Conventions §Tear-down verb axis](../../../spec/Conventions.md#tear-down-verb-axis--clear--vs-destroy-)) the public tear-down surface collapses onto two verbs — `clear-` (registrar / cache / buffer decrement) and `destroy-` (lifecycle boundary). One outlier name renames:

```
(rf/dispose-adapter!)       → (rf/destroy-adapter!)
```

Per rf2-0zlcd (pre-alpha posture: no back-compat shims), the public `re-frame.core/dispose-adapter!` Var is **removed** — stale call sites raise unresolved-symbol at compile time. There is no deprecation cycle. The adapter-spec **map key** `:dispose-adapter!` (the slot adapter implementations provide) is unchanged — adapters keep returning `{:dispose-adapter! (fn [] ...)}` in their spec map. Only the public `re-frame.core` wrapper name moves.

`rf/unsubscribe` is **not** renamed: the natural target `clear-sub` is already taken by the symmetric inverse of `reg-sub` (the registrar decrement). The `un-` prefix is carved out as the singular form for the sub-cache ref-count decrement. See the [Conventions §Tear-down verb axis — Carve-out](../../../spec/Conventions.md#carve-out-unsubscribe).

The rest of the tear-down surface (`clear-event` / `clear-sub` / `clear-sub-cache!` / `destroy-frame!` / `clear-trace-buffer!` / `clear-fx` / `clear-flow` / `clear-http-interceptor` / `clear-trace-listeners!`) is already on the two-verb axis and needs no rewrite.

---

## Listener-registration verb unification (M-55)

Closed mechanical rename table. Per rf2-dcyjm (the listener-registration verb-shape unification) the trace and epoch listener APIs collapse onto the same `register-*-listener!` / `unregister-*-listener!` shape already used by `register-event-emit-listener!` / `register-error-emit-listener!`. Affects v2-pre-rename codebases only — v1 had no trace/epoch-listener concept (v1's `add-post-event-callback` lands on the new name via M-26).

```
(rf/register-trace-cb!  ...)     → (rf/register-trace-listener!  ...)
(rf/remove-trace-cb!    ...)     → (rf/unregister-trace-listener! ...)
(rf/clear-trace-cbs!    ...)     → (rf/clear-trace-listeners!     ...)
(rf/register-epoch-cb!  ...)     → (rf/register-epoch-listener!   ...)
(rf/remove-epoch-cb!    ...)     → (rf/unregister-epoch-listener! ...)
(rf/clear-epoch-cbs!    ...)     → (rf/clear-epoch-listeners!     ...)
```

**Late-bind hook keys** (tool authors only — most apps will not touch these):

```
:trace.tooling/register-trace-cb!  → :trace.tooling/register-trace-listener!
:trace.tooling/remove-trace-cb!    → :trace.tooling/unregister-trace-listener!
:epoch/register-epoch-cb!          → :epoch/register-epoch-listener!
:epoch/remove-epoch-cb!            → :epoch/unregister-epoch-listener!
:epoch/clear-epoch-cbs!            → :epoch/clear-epoch-listeners!
```

Per rf2-dcyjm (pre-alpha posture: no back-compat shims), the old names are **removed** — stale call sites raise unresolved-symbol at compile time. There is no deprecation cycle.

**Cross-references.** [`MIGRATION.md` §M-55](../../../migration/from-re-frame-v1/README.md#m-55-listener-registration-verb-unification--register--cb--register--listener-rf2-dcyjm) for the full table; [009 §The trace event model](../../../spec/009-Instrumentation.md#the-trace-event-model) (the trace listener API).

---

## `:rf.http/managed` `:retry :on` closed-set (M-31b)

Per rf2-apwkm, the `:retry :on` set on `:rf.http/managed` requests no longer accepts arbitrary `:rf.http/*` keywords. The closed retryable subset is:

```
#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}
```

Any keyword outside this set in `:retry :on` raises `:rf.error/http-bad-retry-on` at fx-call time, before the request is issued. The three excluded `:rf.http/*` categories (`:rf.http/aborted` / `:rf.http/decode-failure` / `:rf.http/accept-failure`) are deterministic on retry and were silently retrying as a no-op pre-rf2-apwkm. Sweep `:retry :on` sets, drop excluded categories. v1 had no `:rf.http/managed` fx; v2-pre-rename codebases only.

**Cross-references.** [`MIGRATION.md` §M-31b](../../../migration/from-re-frame-v1/README.md#m-31b-rfhttpmanaged-retry-on-is-a-closed-set-rf2-apwkm); [Spec 014 §Closed-set `:retry :on` validation](../../../spec/014-HTTPRequests.md#closed-set-retry-on-validation--rf2-apwkm).

---

## Interceptor list cleanup (M-21 mechanical half)

Drop `debug` and `trim-v` from interceptor lists:

```clojure
;; SEARCH
(rf/reg-event-fx :foo
  [rf/debug rf/trim-v <other-interceptors>]
  <handler>)

;; REWRITE
(rf/reg-event-fx :foo
  [<other-interceptors>]
  <handler>)
```

If the interceptor list becomes empty after dropping `debug`/`trim-v`, drop the empty vector slot entirely:

```clojure
(rf/reg-event-fx :foo <handler>)
```

**`trim-v` reaches M-19 territory** (the handler may have positional destructure). Flag the handler shape — the M-19 sweep handles destructure rewriting separately.

`on-changes` / `enrich` / `after` → Type B, see [`guided-interceptors-subs.md`](guided-interceptors-subs.md).

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

Inside the body, drop `(rf/dispatcher)` / `(rf/subscriber)` captures — `dispatch` and `subscribe` are auto-injected:

```clojure
;; SEARCH (inside reg-view body)
(let [d (rf/dispatcher)
      s (rf/subscriber)]
  [:button {:on-click #(d [:inc])} @(s [:count])])

;; REWRITE
[:button {:on-click #(dispatch [:inc])} @(subscribe [:count])]
```

**Edge case → flag (Type B)**: body is not a literal `(fn [args] body)` (Var ref, `reagent.core/create-class`, computed `fn`). Use `re-frame.core/reg-view*` (plain-fn surface) and surface to the author.

### M-24 — `rf/h` removal

```clojure
;; SEARCH — namespaced view keyword nested in hiccup
(rf/h [:div [:my-app/widget arg]])
;; REWRITE
[:div [my-app/widget arg]]    ; Var ref (resolves to the symbol the reg-view macro defed)

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

## `reg-event-fx` shape (M-26 mechanical half)

Drop / rewrite the dropped public surfaces:

```clojure
(rf/with-trace ...)                  → (rf/emit-trace-event! op-type operation tags)
(rf/merge-trace! ...)                → no equivalent; drop or convert to one emit-trace-event!
(rf/finish-trace ...)                → drop
rf/trace-api-version                 → drop (no replacement)
(rf/purge-event-queue)               → drop (no replacement); rewrite tests to use 008's helpers
(rf/dispatch-and-settle e)           → (rf/dispatch-sync e)
@(rf/dispatch-and-settle e)          → (rf/dispatch-sync e)   ; the deref is gone; settle is default
(rf/spawn-machine spec)              → wrap in event-fx returning {:fx [[:rf.machine/spawn spec]]}
(rf/destroy-machine id)              → wrap in event-fx returning {:fx [[:rf.machine/destroy id]]}
```

**Type B → see [`guided-interceptors-subs.md`](guided-interceptors-subs.md) (M-26) and [`guided-handlers-state.md`](guided-handlers-state.md) (M-13)**: `add-post-event-callback` / `remove-post-event-callback` / `reg-event-error-handler`.

---

## Init / adapter (M-40)

```clojure
;; SEARCH
(rf/init!)

;; REWRITE
(rf/init! reagent-adapter/adapter)   ; or uix-adapter/adapter, helix-adapter/adapter
```

Pair with M-38's substrate-ns rename so the symbol resolves.

---

## Per-feature artefact adds (M-27 through M-33)

When a per-feature surface is in use, add the dep AND add the `:require` of the implementing namespace to the file where it's used.

| Surface in code | Dep to add | Namespace to require |
|---|---|---|
| `reg-app-schema` / `reg-event-schema` | `day8/re-frame2-schemas` | `re-frame.schemas` |
| `reg-machine` / `sub-machine` | `day8/re-frame2-machines` | `re-frame.machines` |
| `reg-route` / `:rf.route/*` events | `day8/re-frame2-routing` | `re-frame.routing` |
| `reg-flow` / `:rf.fx/reg-flow` | `day8/re-frame2-flows` | `re-frame.flows` |
| `[:rf.http/managed ...]` | `day8/re-frame2-http` | `re-frame.http` |
| `render-to-string` (SSR) | `day8/re-frame2-ssr` | `re-frame.ssr` |
| `epoch-history` / `restore-epoch` | `day8/re-frame2-epoch` | `re-frame.epoch` |

The `:require` is what triggers the artefact's load-time hook registrations. Without it the public surface throws `:rf.error/<artefact>-artefact-missing` at the first call.

---

## What this leaf is NOT

- It is not the full Type A catalogue — per-call-site mechanical rewrites (namespaces, effect-map, dispatch shapes) live in [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md).
- It is not a substitute for [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md)'s per-rule rationale — when you apply a rewrite, you cite the rule id; you don't quote the rule's text inline.
- It is not exhaustive. The shapes here are the most common Type A trigger patterns. If a call site matches the *intent* of a Type A rule but not the *shape* here, apply the rewrite — the shapes are illustrative.

When the rewrite shape doesn't fit a real call site exactly, **stop and consult the full rule in [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md)**. Don't improvise.
