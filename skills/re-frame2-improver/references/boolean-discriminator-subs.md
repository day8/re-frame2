# Anti-pattern — Boolean discriminator subs for FSM states

A cluster of boolean subscriptions all reading the same `app-db` path, each answering "is the screen in state X?" — `:screen/loading?`, `:screen/error?`, `:screen/empty?`, `:screen/loaded?`. The view then chains `cond` clauses derefing each. The cluster is a hand-rolled finite-state machine pretending to be subs.

## Detection rules

Greppable signals:

- Three or more `reg-sub` declarations whose ids end in `?` and read the **same** `app-db` path (or the same parent sub).
- Sub ids in an FSM-shaped set: `:*-loading?` / `:*-loaded?` / `:*-error?` / `:*-empty?` / `:*-pending?` / `:*-ready?` for one logical screen.
- A view body that derefs 3+ such subs and routes via `cond`.
- Sub handlers shaped like `(= :loading (:status db))` / `(some? (:error db))` / `(empty? (:items db))` reading the same parent map.

Structural signal: the boolean subs are mutually exclusive (exactly one is `true` at any time) — that is the definition of an FSM, and it should be modelled as one.

## Why it's an anti-pattern

Each boolean sub is a fresh probe of the same underlying state, with no machine-readable declaration of the mutual exclusion. The view re-renders on **every** discriminator deref. The mutual-exclusion invariant is enforced by convention only — nothing prevents `:loading?` and `:error?` from being simultaneously `true` if a careless handler forgets a `dissoc`. New states (e.g. `:stale`, `:reloading`, `:partial`) require a new sub + a new view branch + an audit of all the existing booleans for the new mutual-exclusion case — a cost that scales with the *square* of the state count.

A re-frame2 state machine declares the states once; tags label the per-state intent; one selector sub answers the render question.

## The canonical fix

[`skills/re-frame2/reference/state-machines/tags.md`](../../re-frame2/reference/state-machines/tags.md) — declare a `reg-machine` whose states carry `:tags`, then use `@(rf/machine-has-tag? machine-id tag)` (or a single render-priority selector sub) instead of the boolean cluster.

For full page-level rendering with cardinality buckets, [`skills/re-frame2/patterns/nine-states.md`](../../re-frame2/patterns/nine-states.md) is the canonical pattern.

Spec source: [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) §Tags.

## Worked example

**Before** — boolean discriminator cluster:

```clojure
(rf/reg-sub :article/loading? (fn [db _] (= :loading (get-in db [:article :status]))))
(rf/reg-sub :article/error?   (fn [db _] (= :error   (get-in db [:article :status]))))
(rf/reg-sub :article/empty?   (fn [db _] (and (= :loaded (get-in db [:article :status]))
                                              (empty?   (get-in db [:article :data])))))
(rf/reg-sub :article/loaded?  (fn [db _] (and (= :loaded (get-in db [:article :status]))
                                              (seq      (get-in db [:article :data])))))

(defn article-page []
  (cond
    @(rf/subscribe [:article/loading?]) [spinner]
    @(rf/subscribe [:article/error?])   [error-banner]
    @(rf/subscribe [:article/empty?])   [empty-state]
    @(rf/subscribe [:article/loaded?])  [article-body]))
```

**After** — machine + tags + one selector:

```clojure
(rf/reg-machine :article
  {:initial :loading
   :states  {:loading {:tags #{:article/loading}}
             :error   {:tags #{:article/error}}
             :empty   {:tags #{:article/empty}}
             :loaded  {:tags #{:article/loaded}}}})

(defn article-page []
  (cond
    @(rf/machine-has-tag? :article :article/loading) [spinner]
    @(rf/machine-has-tag? :article :article/error)   [error-banner]
    @(rf/machine-has-tag? :article :article/empty)   [empty-state]
    @(rf/machine-has-tag? :article :article/loaded)  [article-body]))
```

## Edge cases — when boolean subs are fine

- **Genuinely independent predicates** that aren't mutually exclusive — `:cart/has-items?` and `:cart/over-shipping-threshold?` can both be `true` and aren't states of one FSM. Keep them as subs.
- **Layer-1 readers of one boolean app-db key** that aren't an FSM — `:flag/feature-x-enabled?` reading `(:feature-x? db)` is fine.
- **A two-state toggle** (`:open?` / `:closed?`) is small enough that a single sub + `if` in the view costs less than declaring a machine. The smell scales: 3+ mutually-exclusive booleans on the same path is the trigger.
