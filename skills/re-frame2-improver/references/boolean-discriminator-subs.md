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

Each boolean sub is a fresh probe of the same underlying state, with no machine-readable declaration of the mutual exclusion. The view re-renders on **every** discriminator deref. The mutual-exclusion invariant is enforced by convention only — nothing prevents `:loading?` and `:error?` from being simultaneously `true` if a careless handler forgets a `dissoc`. New states (`:stale`, `:reloading`, `:partial`) require a new sub + a new view branch + an audit of all existing booleans for the new mutual-exclusion case — a cost that scales with the *square* of the state count.

A re-frame2 state machine declares the states once; tags label the per-state intent; one selector sub answers the render question.

## The canonical fix

[`tags.md`](../../re-frame2/references/state-machines/tags.md) — declare a `reg-machine` whose states carry `:tags`, then resolve the page's render through **one selector sub** over a data-shaped render-priority table (the view does a single `case`). For full page-level rendering with cardinality buckets, [`nine-states.md`](../../re-frame2/patterns/nine-states.md) is the canonical pattern.

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

**After** — a machine whose states carry `:tags`, one selector sub over a data render-priority table, one `case` in the view:

```clojure
(rf/reg-machine :article
  {:initial :loading
   :states  {:loading {:tags #{:article/loading} :on {…}}   ;; guards/actions/transitions elided
             :error   {:tags #{:article/error}}
             :empty   {:tags #{:article/empty}}
             :loaded  {:tags #{:article/loaded}}}})

(def render-priority                                          ;; printable, testable — first match wins
  [{:tag :article/loading :render :loading}
   {:tag :article/error   :render :error}
   {:tag :article/empty   :render :empty}
   {:tag :article/loaded  :render :loaded}])

(rf/reg-sub :article/render
  :<- [:rf/machine :article]
  (fn [snap _]
    (or (some (fn [{:keys [tag render]}] (when (contains? (:tags snap) tag) render))
              render-priority)
        :uninitialised)))                                     ;; snapshot may be nil before first run

(defn article-page []
  (case @(rf/subscribe [:article/render])
    :loading [spinner] :error [error-banner]
    :empty [empty-state] :loaded [article-body]
    :uninitialised [spinner]))                                ;; lazy-init boundary — see callout
```

Adding a `:stale` state is one row in the table plus one `case` clause — no audit of mutual-exclusion across a boolean cluster. **Full machine mechanics (guards, actions, transitions), the render-priority idiom, and lazy-init handling: see [`tags.md`](../../re-frame2/references/state-machines/tags.md) and [`nine-states.md`](../../re-frame2/patterns/nine-states.md).**

> **The lazy-initialisation boundary.** `:initial :loading` declares where the machine *starts*, not that a snapshot exists: until it first runs, `[:rf/machine :article]` is `nil` and the selector resolves no tag ([Spec 005](../../../spec/005-StateMachines.md) §When creation happens). Two complementary safeguards — both required — (1) dispatch the eager-start kick `(rf/dispatch [:article [:rf.machine/start]])` from app/frame init so the `:loading` snapshot is materialised before first paint, and (2) keep a `:uninitialised` default branch so the root `case` cannot throw on the nil/no-match keyword. The kick alone is insufficient: a render racing ahead of init, or a frame reverting past the machine's birth, can still present `nil`.

> **`:rf/machine-has-tag?` vs the selector sub.** Reach for `@(rf/subscribe [:rf/machine-has-tag? :article :some/tag])` directly for **single one-off affordances** — disabling a button while in-flight, showing a "read-only" badge. Route **mutually-exclusive whole-page render states** through one selector sub over a priority table, as above. A `cond` over multiple `:rf/machine-has-tag?` derefs in the root view re-introduces the very multi-boolean branch this leaf exists to retire.

## Edge cases — when boolean subs are fine

- **Genuinely independent predicates** that aren't mutually exclusive — `:cart/has-items?` and `:cart/over-shipping-threshold?` can both be `true` and aren't states of one FSM. Keep them as subs.
- **Layer-1 readers of one boolean app-db key** that aren't an FSM — `:flag/feature-x-enabled?` reading `(:feature-x? db)` is fine.
- **A two-state toggle** (`:open?` / `:closed?`) is small enough that a single sub + `if` costs less than a machine. The smell scales: 3+ mutually-exclusive booleans on the same path is the trigger.
