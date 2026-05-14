# auto-cross-cutting

Type A — cross-cutting mechanical rewrites the agent applies without asking. Covers framework-keyword renames, interceptor-list cleanup, view / hiccup rewrites, dropped public-surface drops, init wiring, and per-feature artefact adds.

For the *why* of each rule, see [`MIGRATION.md`](../../../spec/MIGRATION.md). This leaf is a shape catalogue, not a rationale. For per-call-site mechanical rewrites (namespaces, effect-map, dispatch shapes), see [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md). For judgment-call rewrites, see [`guided-handlers-state.md`](guided-handlers-state.md) and [`guided-interceptors-subs.md`](guided-interceptors-subs.md).

## Contents

- Framework keyword renames (M-20, M-35)
- Interceptor list cleanup (M-21 mechanical half)
- View / hiccup rewrites (M-22, M-24)
- `reg-event-fx` shape (M-26 mechanical half)
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

**Framework `:route/*` ids are the closed list** in [`MIGRATION.md`](../../../spec/MIGRATION.md) M-20 (`:route/navigate`, `:route/url-changed`, `:route/handle-url-change`, `:route/not-found`, `:route/navigation-blocked`, `:route/continue`, `:route/cancel`, `:route/error`, `:route/transition`, `:route/resolved`, `:route/auth-guard`, `:route/equal`, `:route/chain`).

**User `:route/<name>` ids** are user-defined and left alone (mechanical) or rewritten to a feature prefix (suggested, Type B). The closed framework list is the discriminator.

Also rewrite the app-db slice key `[:route]` → `[:rf/route]` and the subscription head `[:route]` → `[:rf/route]`.

### M-35 — actor-lifecycle fx-id rename

```
[:spawn ...]              → [:rf.machine/spawn ...]
[:destroy-machine ...]    → [:rf.machine/destroy ...]
```

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
- It is not a substitute for [`MIGRATION.md`](../../../spec/MIGRATION.md)'s per-rule rationale — when you apply a rewrite, you cite the rule id; you don't quote the rule's text inline.
- It is not exhaustive. The shapes here are the most common Type A trigger patterns. If a call site matches the *intent* of a Type A rule but not the *shape* here, apply the rewrite — the shapes are illustrative.

When the rewrite shape doesn't fit a real call site exactly, **stop and consult the full rule in [`MIGRATION.md`](../../../spec/MIGRATION.md)**. Don't improvise.
