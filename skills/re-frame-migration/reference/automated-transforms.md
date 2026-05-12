# automated-transforms

Type A patterns: the unambiguous mechanical rewrites the agent applies without asking. Each entry gives the **search shape**, the **rewrite shape**, the **rule id** (so the report cites it correctly), and any **edge case** that promotes a sub-case to Type B.

For the *why* of each rule, see [`MIGRATION.md`](../../../spec/MIGRATION.md). This leaf is a shape catalogue, not a rationale.

## Contents

- Dep-coord and namespace rewrites
- Effect-map consolidation (M-8)
- Dispatch-shape rewrites (M-4, M-9, M-16)
- Framework keyword renames (M-20)
- Interceptor list cleanup (M-21 mechanical half)
- View / hiccup rewrites (M-22, M-24)
- Test-namespace rename (M-25)
- `reg-event-fx` shape (M-26 mechanical half)
- Init / adapter (M-40, M-38)
- Per-feature artefact adds (M-27 through M-33)

---

## Dep-coord and namespace rewrites

### M-0 — Dep coord swap

See `reference/setup.md` for the per-build-tool detail. Applied once, in Phase 2.

### M-1 — Private-namespace requires

```clojure
;; SEARCH
(:require [re-frame.db :as db])
(:require [re-frame.db :refer [app-db]])
(:require [re-frame.router :as router])
(:require [re-frame.subs :as subs])
(:require [re-frame.events :as events])
(:require [re-frame.registrar :as reg])

;; REWRITE
;; Remove the :require entirely; replace usages per the table:
@db/app-db           → (rf/get-frame-db :rf/default)
@re-frame.db/app-db  → (rf/get-frame-db :rf/default)
(reset! re-frame.db/app-db v) → flag (Type B — see M-15) — propose
                                 (rf/dispatch-sync [::reset-app-db v])
(subs/clear-subscription-cache!) → (rf/clear-subscription-cache! :rf/default)
(reg/get-handler kind id) → (rf/get-handler kind id)
```

**Edge case → Type B**: `(reset! re-frame.db/app-db ...)` is intent-sensitive (real bypass vs. test reset vs. seeding); promote to M-15 review.

### M-38 — Substrate adapter ns rename

```clojure
;; SEARCH
(:require [re-frame.substrate.reagent :as reagent-adapter])

;; REWRITE
(:require [re-frame.adapter.reagent :as reagent-adapter])
```

Same for `uix` / `helix` variants.

### M-23 — `re-frame.alpha` removal (mechanical half)

```clojure
;; SEARCH
(:require [re-frame.alpha :as rf])  ; or :refer [reg sub]

;; REWRITE — remove the require; rewrite each call site:
(reg :event-fx :id ...)              → (reg-event-fx :id ...)
(reg :event-db :id ...)              → (reg-event-db :id ...)
(reg :event-ctx :id ...)             → (reg-event-ctx :id ...)
(reg :sub :id ...)                   → (reg-sub :id ...)
(reg :fx :id ...)                    → (reg-fx :id ...)
(reg :cofx :id ...)                  → (reg-cofx :id ...)
(reg :flow :id ...)                  → (reg-flow ...)
(sub <vector>)                       → (subscribe <vector>)
(sub {:re-frame/q ::id :param 1})    → (subscribe [::id 1])   ; vectorize the query-map
```

**Edge case → Type B**: any `:re-frame/lifecycle` annotation in the original — drop the annotation, file a follow-up bead if the user explicitly wanted non-default lifecycle.

### M-25 — `re-frame.test` rename

```clojure
;; SEARCH
(:require [re-frame.test :as rf-test])
(:require [day8.re-frame.test :as rf-test])

;; REWRITE
(:require [re-frame.test-support :as rf-test])
```

Helper names (`dispatch-sequence`, `assert-state`, `run-test-sync`) unchanged.
Also: drop `day8/re-frame-test` from the Maven coords.

---

## Effect-map consolidation (M-8)

The single highest-impact mechanical rewrite. The transformation is structural.

```clojure
;; SEARCH
{:db   ...
 :dispatch       <event-vec>}

{:dispatch-later <map-or-vec-of-maps>}

{:dispatch-n     [<event-vec> ...]}

{:db   ...
 :<user-fx-id>   <args>}

;; REWRITE — fold every non-:db key into :fx
{:db ...
 :fx [[:dispatch <event-vec>]]}

{:fx [[:dispatch-later <map>] ...]}           ; one entry per map in the original vector

{:fx [[:dispatch <e1>] [:dispatch <e2>] ...]} ; one entry per event-vec

{:db ...
 :fx [[:<user-fx-id> <args>]]}
```

**Procedure** (sweep first, then per-handler rewrite):

1. Discover the project's user-fx ids: `grep -E "\\(rf/reg-fx\\s+:" -r src/`.
2. Add the built-ins: `:dispatch`, `:dispatch-later`, `:dispatch-n`.
3. For each `reg-event-fx` body, walk the returned effect map literal.
4. For each top-level key other than `:db`:
   - In the discovered set → rewrite per the rules above.
   - Not in the set → **flag** (might be a destructure key, not an effect).
5. If `:fx` already exists, concatenate: existing `:fx` first, new entries after.

**Edge case → flag**: an unknown top-level key. Could be a destructure or a typo'd fx-id.

---

## Dispatch-shape rewrites

### M-4 — `dispatch-with` / `dispatch-sync-with`

```clojure
;; SEARCH
(rf/dispatch-with      <event-vec> <opts-shape>)
(rf/dispatch-sync-with <event-vec> <opts-shape>)

;; REWRITE
(rf/dispatch      <event-vec> {:fx-overrides <opts-shape>})
(rf/dispatch-sync <event-vec> {:fx-overrides <opts-shape>})
```

The `opts-shape` shape carries the same content; only the slot key changes (it now lives inside `:fx-overrides`).

### M-9 — `dispatch-sync` inside a handler

```clojure
;; SEARCH — lexically inside (reg-event-* :id ... (fn ...))
(rf/dispatch-sync <event-vec>)

;; REWRITE — move the event into :fx
{:db ...
 :fx [[:dispatch <event-vec>]]}
```

If the handler was `reg-event-db`, promote it to `reg-event-fx` so it can return an `:fx` slot.

### M-16 — `^:flush-dom` metadata

```clojure
;; SEARCH
^:flush-dom <event-vec>

;; REWRITE
;; Wrap in a :dispatch-later fx
{:fx [[:dispatch-later {:ms 0 :dispatch <event-vec>}]] ...}
```

Old form:
```clojure
{:dispatch ^:flush-dom [:do-work]
 :db       (assoc db :processing true)}
```

New form:
```clojure
{:db (assoc db :processing true)
 :fx [[:dispatch-later {:ms 0 :dispatch [:do-work]}]]}
```

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

`on-changes` / `enrich` / `after` → Type B, see `reference/guided-checklist.md`.

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
(rf/with-trace ...)                  → (rf/emit-trace! op-type operation tags)
(rf/merge-trace! ...)                → no equivalent; drop or convert to one emit-trace!
(rf/finish-trace ...)                → drop
rf/trace-api-version                 → drop (no replacement)
(rf/purge-event-queue)               → drop (no replacement); rewrite tests to use 008's helpers
(rf/dispatch-and-settle e)           → (rf/dispatch-sync e)
@(rf/dispatch-and-settle e)          → (rf/dispatch-sync e)   ; the deref is gone; settle is default
(rf/spawn-machine spec)              → wrap in event-fx returning {:fx [[:rf.machine/spawn spec]]}
(rf/destroy-machine id)              → wrap in event-fx returning {:fx [[:rf.machine/destroy id]]}
```

**Type B → see guided-checklist**: `add-post-event-callback` / `remove-post-event-callback` / `reg-event-error-handler`.

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

- It is not a complete migration script. Type B rules live in `reference/guided-checklist.md`.
- It is not a substitute for [`MIGRATION.md`](../../../spec/MIGRATION.md)'s per-rule rationale — when you apply a rewrite, you cite the rule id; you don't quote the rule's text inline.
- It is not exhaustive. The shapes here are the most common Type A trigger patterns. If a call site matches the *intent* of a Type A rule but not the *shape* here, apply the rewrite — the shapes are illustrative.

When the rewrite shape doesn't fit a real call site exactly, **stop and consult the full rule in [`MIGRATION.md`](../../../spec/MIGRATION.md)**. Don't improvise.
