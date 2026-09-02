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

The correction is the same move at either size: declare the render question **once**, in one derivation, and let the view do a single `case`. Over a `:status` slice that is one `reg-sub`; over a state machine the states declare themselves once, tags label the per-state intent, and the same one selector sub answers the question. Which of the two the code needs is a separate judgement — see below.

## The canonical fix

**Smallest correction first.** The defect is the *cluster*, not the state model. Read the `Before` below and notice what is already there: the lifecycle is a single `:status` keyword at `[:article :status]` — a Pattern-RemoteData slice — and the four booleans are four fresh probes of it. So the proportionate correction is **one selector sub** over the state that already exists, folding the empty-vs-loaded cardinality question into the same derivation, and one `case` in the view. That removes both symptoms outright: the render question is answered once per change instead of once per discriminator (no multi-deref re-render), and the mutual-exclusion invariant becomes a single expression a reader can check, so a new `:stale` state is one clause rather than an audit of the whole cluster. Neither symptom needs a machine to fix.

Canonical idiom: [`remote-data.md`](../../re-frame2/patterns/remote-data.md) §Canonical declaration — slice form, whose explicit `:status` enum and layered convenience subs are precisely this shape, and which calls it "the dominant shape … the vast majority of cases".

**When the machine pays.** Reach for `reg-machine` when the lifecycle has actually earned it, not because the render code was untidy. [`slice-or-machine.md`](../../re-frame2/decision-trees/slice-or-machine.md) is the owning decision: it defaults to **slice** unless one of four tells fires — multi-step async phases with phase-distinct transitions, a cancellation cascade, a terminal state, or orthogonal axes. Add the two [`remote-data.md`](../../re-frame2/patterns/remote-data.md) §When to choose each form gives for this pattern specifically: the lifecycle is a region of a larger parallel machine (Nine States), or the team wants tag-shaped queries instead of slice-field comparisons.

When one of those holds, the shape of the fix does not change — it is still **one selector sub, one `case`** — it just reads the machine's `:tags` instead of the slice's `:status`: declare a `reg-machine` whose states carry `:tags` and resolve the render through one selector sub over a data-shaped render-priority table ([`tags.md`](../../re-frame2/references/state-machines/tags.md)). For full page-level rendering with cardinality buckets across several axes, [`nine-states.md`](../../re-frame2/patterns/nine-states.md) is the canonical pattern.

**Report the two as different findings with different patch sizes.** The one-sub correction is the immediate repair; the machine is the optional broader redesign, and only when a tell fires.

Spec sources: [`spec/Pattern-RemoteData.md`](../../../spec/Pattern-RemoteData.md) (the status slice) and [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) §Tags (the machine).

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

**After — the smallest correction.** The `:status` keyword is already in `app-db`; one derivation answers the render question, and the view does one `case`:

```clojure
(rf/reg-sub :article (fn [db _] (:article db)))

(rf/reg-sub :article/render                        ;; ONE derivation, not four probes
  :<- [:article]
  (fn [{:keys [status data]} _]
    (case status
      :loading :loading
      :error   :error
      :loaded  (if (empty? data) :empty :loaded)   ;; cardinality folded in here
      :idle)))                                     ;; nil / :idle — nothing fetched yet

(rf/reg-view article-page []
  (case @(subscribe [:article/render])
    :idle [placeholder] :loading [spinner]
    :error [error-banner] :empty [empty-state]
    :loaded [article-body]))
```

Four subs become one, the view derefs once, and the mutual exclusion is a single `case` rather than an invariant spread across four handlers. There is no lazy-initialisation boundary to manage: the slice is ordinary `app-db`, so the `:idle` default covers "nothing fetched yet" with no eager-start kick. Adding `:stale` is one `case` clause on each side.

**After — the optional redesign** (only when a [`slice-or-machine.md`](../../re-frame2/decision-trees/slice-or-machine.md) tell fires, or the page is already a Nine States machine) — a machine whose states carry `:tags`, one selector sub over a data render-priority table, one `case` in the view:

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

(rf/reg-view article-page []
  (case @(subscribe [:article/render])                        ;; injected subscribe — no rf/ prefix
    :loading [spinner] :error [error-banner]
    :empty [empty-state] :loaded [article-body]
    :uninitialised [spinner]))                                ;; lazy-init boundary — see callout
```

Adding a `:stale` state is one row in the table plus one `case` clause — no audit of mutual-exclusion across a boolean cluster. **Full machine mechanics (guards, actions, transitions), the render-priority idiom, and lazy-init handling: see [`tags.md`](../../re-frame2/references/state-machines/tags.md) and [`nine-states.md`](../../re-frame2/patterns/nine-states.md).**

> **The lazy-initialisation boundary.** `:initial :loading` declares where the machine *starts*, not that a snapshot exists: until it first runs, `[:rf/machine :article]` is `nil` and the selector resolves no tag ([Spec 005](../../../spec/005-StateMachines.md) §When creation happens). Two complementary safeguards — both required — (1) fire the eager-start kick `[:article [:rf.machine/start]]` from the frame's `:initial-events` (the atomic entry point, dispatched synchronously at frame construction) so the `:loading` snapshot is materialised before first paint — not as a bare `rf/dispatch` after `make-frame`, which runs under no frame scope, and (2) keep a `:uninitialised` default branch so the root `case` cannot throw on the nil/no-match keyword. The kick alone is insufficient: a render racing ahead of init, or a frame reverting past the machine's birth, can still present `nil`.

> **`:rf.machine/has-tag?` vs the selector sub.** Reach for `@(rf/subscribe [:rf.machine/has-tag? :article :some/tag])` directly for **single one-off affordances** — disabling a button while in-flight, showing a "read-only" badge. Route **mutually-exclusive whole-page render states** through one selector sub over a priority table, as above. A `cond` over multiple `:rf.machine/has-tag?` derefs in the root view re-introduces the very multi-boolean branch this leaf exists to retire.

> **Both rewrites register with `rf/reg-view`, and that is load-bearing rather than style.** A plain `(defn …)` view carries no `:contextType`, so it cannot read the surrounding `frame-provider`'s frame; under EP-0002 there is no `:rf/default` floor, so its ambient `rf/subscribe` / `rf/dispatch` resolve to nil and raise `:rf.error/no-frame-context` the moment they run — a recommended rewrite in that shape throws in the reader's app at first render. `reg-view` injects frame-bound `dispatch` / `subscribe` locals, which is why the bodies above drop the `rf/` prefix. Same rule as [`view-side-hook-state.md`](view-side-hook-state.md); the framework statement is [`views.md`](../../re-frame2/references/fundamentals/views.md) §Common gotchas. The `Before` is deliberately left a plain `defn` — it is the code under review, not a recommendation.

## Edge cases — when boolean subs are fine

- **Genuinely independent predicates** that aren't mutually exclusive — `:cart/has-items?` and `:cart/over-shipping-threshold?` can both be `true` and aren't states of one FSM. Keep them as subs.
- **Layer-1 readers of one boolean app-db key** that aren't an FSM — `:flag/feature-x-enabled?` reading `(:feature-x? db)` is fine.
- **A two-state toggle** (`:open?` / `:closed?`) is small enough that a single sub + `if` costs less than a machine. The smell scales: 3+ mutually-exclusive booleans on the same path is the trigger.
