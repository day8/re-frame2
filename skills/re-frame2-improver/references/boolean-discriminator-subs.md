# Anti-pattern — Boolean discriminator subs for FSM states

A cluster of boolean subscriptions all reading the same `app-db` path, each answering "is the screen in state X?" — `:screen/loading?`, `:screen/error?`, `:screen/empty?`, `:screen/loaded?`. The view then chains `cond` clauses derefing each. The cluster is a hand-rolled finite-state machine pretending to be subs.

## Detection rules

Greppable signals:

- Three or more `reg-sub` declarations whose ids end in `?` and read the **same** `app-db` path (or the same parent sub).
- Sub ids in an FSM-shaped set: `:*-loading?` / `:*-loaded?` / `:*-error?` / `:*-empty?` / `:*-pending?` / `:*-ready?` for one logical screen.
- A view body that derefs 3+ such subs and routes via `cond`.
- Sub handlers shaped like `(= :loading (:status db))` / `(some? (:error db))` / `(empty? (:items db))` reading the same parent map.

**Required condition — the mutual-exclusion test.** The greppable signals above narrow the search; this one decides it. The predicates must describe exclusive alternatives for the same render decision: **at most one is truthy** in each reachable state. None may match before the first load, and `(seq data)` is truthy without being the literal `true`. If two can be truthy at once — `:loading?` and `:fetching?`, where one subsumes the other — the set is **not** this anti-pattern, whatever the ids look like. Check the expressions and reachable states before reporting, not just their names.

## Why it's an anti-pattern

Each predicate repeats part of the same render decision, spreading its maintenance across several subs and the view's branch order. Adding a state means checking those expressions together. This is a maintainability finding: dereferencing a subscription does not itself schedule a render, and the substrate batches invalidations. Do not claim one re-render per deref or a quadratic cost without evidence. When all predicates compare one status keyword, that keyword already enforces their exclusion; the correction centralises the render derivation rather than repairing a nonexistent state-coherence bug.

The correction is the same move at either size: declare the render question **once**, in one derivation, and let the view do a single `case`. Over a `:status` slice that is one `reg-sub`; over a state machine the states declare themselves once, tags label the per-state intent, and the same one selector sub answers the question. Which of the two the code needs is a separate judgement — see below.

## The canonical fix

**Smallest correction first.** The lifecycle in the `Before` is already a single `:status` keyword at `[:article :status]` — a Pattern-RemoteData slice. Use **one selector sub** over that state, folding the empty-vs-loaded cardinality question into the same derivation, and one `case` in the view. The render alternatives then live in one expression a reader can check. This needs no machine and makes no promise about render counts.

Canonical idiom: [`remote-data.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/patterns/remote-data.md) §Canonical declaration — slice form, whose explicit `:status` enum and layered convenience subs are precisely this shape, and which calls it "the dominant shape … the vast majority of cases".

**When the machine pays.** Reach for `reg-machine` when the lifecycle has actually earned it, not because the render code was untidy. [`slice-or-machine.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/decision-trees/slice-or-machine.md) is the owning decision: it defaults to **slice** unless one of four tells fires — multi-step async phases with phase-distinct transitions, a cancellation cascade, a terminal state, or orthogonal axes. Add the two [`remote-data.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/patterns/remote-data.md) §When to choose each form gives for this pattern specifically: the lifecycle is a region of a larger parallel machine (Nine States), or the team wants tag-shaped queries instead of slice-field comparisons.

When one of those holds, the shape of the fix does not change — it is still **one selector sub, one `case`** — it just reads the machine's `:tags` instead of the slice's `:status`: declare a `reg-machine` whose states carry `:tags` and resolve the render through one selector sub over a data-shaped render-priority table ([`tags.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/state-machines/tags.md)). For full page-level rendering with cardinality buckets across several axes, [`nine-states.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/patterns/nine-states.md) is the canonical pattern.

**Report the two as different findings with different patch sizes.** The one-sub correction is the immediate repair; the machine is the optional broader redesign, and only when a tell fires.

Spec sources: [`spec/Pattern-RemoteData.md`](https://github.com/day8/re-frame2/blob/main/spec/Pattern-RemoteData.md) (the status slice) and [`spec/005-StateMachines.md`](https://github.com/day8/re-frame2/blob/main/spec/005-StateMachines.md) §Tags (the machine).

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
  {:inputs [[:article]]}
  (fn [[{:keys [status data]}] _]
    (case status
      :loading :loading
      :error   :error
      (:loaded :fetching) (if (empty? data) :empty :loaded) ;; retain prior data while refreshing
      :idle)))                                     ;; nil / :idle — nothing fetched yet

(rf/reg-view article-page []
  (case @(subscribe [:article/render])
    :idle [placeholder] :loading [spinner]
    :error [error-banner] :empty [empty-state]
    :loaded [article-body]))
```

Four subs become one, the view derefs once, and the mutual exclusion is a single `case` rather than an invariant spread across four handlers. `:fetching` keeps the prior loaded or empty view visible while the slice revalidates, including when this selector is combined with the [manual-loading-flags correction](manual-loading-flags.md). There is no lazy-initialisation boundary to manage: the slice is ordinary `app-db`, so the `:idle` default covers "nothing fetched yet" with no eager-start kick. Adding `:stale` is one `case` clause on each side.

**After — the optional redesign** (only when a [`slice-or-machine.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/decision-trees/slice-or-machine.md) tell fires, or the page is already a Nine States machine) — a machine whose states carry `:tags`, one selector sub over a data render-priority table, one `case` in the view:

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
  {:inputs [[:rf/machine :article]]}
  (fn [[snap] _]
    (or (some (fn [{:keys [tag render]}] (when (contains? (:tags snap) tag) render))
              render-priority)
        :uninitialised)))                                     ;; snapshot may be nil before first run

(rf/reg-view article-page []
  (case @(subscribe [:article/render])                        ;; injected subscribe — no rf/ prefix
    :loading [spinner] :error [error-banner]
    :empty [empty-state] :loaded [article-body]
    :uninitialised [spinner]))                                ;; lazy-init boundary — see callout
```

Adding a `:stale` state is one row in the table plus one `case` clause — no audit of mutual-exclusion across a boolean cluster. **Full machine mechanics (guards, actions, transitions), the render-priority idiom, and lazy-init handling: see [`tags.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/state-machines/tags.md) and [`nine-states.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/patterns/nine-states.md).**

> **The lazy-initialisation boundary.** `:initial :loading` declares where the machine *starts*, not that a snapshot exists: until it first runs, `[:rf/machine :article]` is `nil` and the selector resolves no tag ([Spec 005](https://github.com/day8/re-frame2/blob/main/spec/005-StateMachines.md) §When creation happens). Two complementary safeguards — both required — (1) fire the eager-start kick `[:article [:rf.machine/start]]` from the frame's `:initial-events` (the atomic entry point, dispatched synchronously at frame construction) so the `:loading` snapshot is materialised before first paint — not as a bare `rf/dispatch` after `make-frame`, which runs under no frame scope, and (2) keep a `:uninitialised` default branch so the root `case` cannot throw on the nil/no-match keyword. The kick alone is insufficient: a render racing ahead of init, or a frame reverting past the machine's birth, can still present `nil`.

> **`:rf.machine/has-tag?` vs the selector sub.** Reach for `@(rf/subscribe [:rf.machine/has-tag? :article :some/tag])` directly for **single one-off affordances** — disabling a button while in-flight, showing a "read-only" badge. Route **mutually-exclusive whole-page render states** through one selector sub over a priority table, as above. A `cond` over multiple `:rf.machine/has-tag?` derefs in the root view re-introduces the very multi-boolean branch this leaf exists to retire.

> **Both rewrites register with `rf/reg-view`, and that is load-bearing rather than style.** A plain `(defn …)` view carries no `:contextType`, so it cannot read the surrounding `frame-provider`'s frame; under EP-0002 there is no `:rf/default` floor, so its ambient `rf/subscribe` / `rf/dispatch` resolve to nil and raise `:rf.error/no-frame-context` the moment they run — a recommended rewrite in that shape throws in the reader's app at first render. `reg-view` injects frame-bound `dispatch` / `subscribe` locals, which is why the bodies above drop the `rf/` prefix. Same rule as [`view-side-hook-state.md`](view-side-hook-state.md); the framework statement is [`views.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/fundamentals/views.md) §Common gotchas. The `Before` is deliberately left a plain `defn` — it is the code under review, not a recommendation.

## Edge cases — when boolean subs are fine

- **Convenience predicates DERIVED from one `:status` sub** — `(rf/reg-sub :articles/loading? {:inputs [[:articles/status]]} …)` beside `:articles/fetching?` / `:articles/error?` — are [`Pattern-RemoteData`](https://github.com/day8/re-frame2/blob/main/spec/Pattern-RemoteData.md)'s canonical layer, not the cluster. Two tells, and they agree: each predicate derives from **one shared `:status` sub** rather than recomputing the state from the raw db path, and they are not mutually exclusive (`:loading?` implies `:fetching?`). The sibling leaf [`manual-loading-flags.md`](manual-loading-flags.md) teaches an agent to *add* exactly this layer, so proposing its deletion would contradict the catalogue. Keep them. (The `Before` above is the contrast: four `?`-subs each re-reading `[:article :status]` with no shared derivation.)
- **Genuinely independent predicates** that aren't mutually exclusive — `:cart/has-items?` and `:cart/over-shipping-threshold?` can both be `true` and aren't states of one FSM. Keep them as subs.
- **Layer-1 readers of one boolean app-db key** that aren't an FSM — `:flag/feature-x-enabled?` reading `(:feature-x? db)` is fine.
- **A two-state toggle** (`:open?` / `:closed?`) is small enough that a single sub + `if` costs less than a machine. The smell scales: 3+ mutually-exclusive booleans on the same path is the trigger.
