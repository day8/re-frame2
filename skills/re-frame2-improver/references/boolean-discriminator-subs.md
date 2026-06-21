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

[`skills/re-frame2/references/state-machines/tags.md`](../../re-frame2/references/state-machines/tags.md) — declare a `reg-machine` whose states carry `:tags`, then resolve the page's render through **one selector sub** over a data-shaped render-priority table (the view does a single `case`). Use `@(rf/machine-has-tag? machine-id tag)` directly only for single one-off affordances, not for the mutually-exclusive whole-page render branch — that is what the selector sub is for.

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

**After** — machine + tags + one selector. The states carry `:tags` for the render question **and** `:on` transitions for the driving events, so the machine actually moves through its lifecycle (a tag-only machine would be stuck in `:loading` forever). The render priority lives in a **data** table; one selector sub resolves the tag union to a single render keyword; the view does **one** `case`:

```clojure
(rf/reg-machine :article
  {:initial :loading
   :data    {:data nil :error nil}
   :guards  {:empty-result? (fn [{[_ data] :event}] (empty? data))}
   :actions {:set-data  (fn [{d :data [_ data] :event}] {:data (assoc d :data data :error nil)})
             :set-error (fn [{d :data [_ err]  :event}] {:data (assoc d :error err)})}
   :states
   {:loading {:tags #{:article/loading}
              :on   {:load-success [{:guard :empty-result? :target :empty :action :set-data}
                                     {:target :loaded :action :set-data}]
                     :load-failure {:target :error :action :set-error}}}
    :error   {:tags #{:article/error} :on {:reload :loading}}
    :empty   {:tags #{:article/empty} :on {:reload :loading}}
    :loaded  {:tags #{:article/loaded} :on {:reload :loading}}}})

;; Bring the machine alive so its snapshot is materialised before the view
;; renders. `:initial :loading` in the spec above is NOT an already-present
;; snapshot — registration creates the *handler*, not the snapshot, and
;; `[:rf/machine :article]` returns nil until the machine first runs (Spec 005
;; §When creation happens). Dispatch the synthetic eager-start kick from your
;; app/frame init (e.g. the frame's `:initial-events`) so the `:loading`
;; snapshot exists on first paint:
;;
;;   (rf/dispatch [:article [:rf.machine/start]])  ;; eager creation kick → snapshot now :loading
;;
;; Drive it thereafter:
;;           (rf/dispatch [:article [:load-success items]])  ;; → :empty or :loaded
;;           (rf/dispatch [:article [:load-failure err]])    ;; → :error
;;           (rf/dispatch [:article [:reload]])              ;; back to :loading

;; Render priority as data — printable, testable, diffable. First matching tag wins.
(def render-priority
  [{:tag :article/loading :render :loading}
   {:tag :article/error   :render :error}
   {:tag :article/empty   :render :empty}
   {:tag :article/loaded  :render :loaded}])

;; One selector sub over the machine's tag union. Snapshots are LAZY —
;; `[:rf/machine :article]` is nil before the machine first runs, so `snap`
;; (and its `:tags`) may be nil. `contains?` against a possibly-nil set is the
;; right test, and `some` returns nil when nothing matches — the view must
;; tolerate that nil (see the `:rf/uninitialised` default below).
(rf/reg-sub :article/render
  :<- [:rf/machine :article]
  (fn [snap _]
    (let [tags (:tags snap)]
      (or (some (fn [{:keys [tag render]}]
                  (when (contains? tags tag) render))
                render-priority)
          :rf/uninitialised))))

;; The view branches once, in a `case`. The `:rf/uninitialised` default is the
;; pre-snapshot guard: even with the eager-start kick above, a `case` with no
;; default would throw on the nil/no-match render keyword, so keep a branch for
;; "machine not addressed yet" rather than relying on the kick alone.
(defn article-page []
  (case @(rf/subscribe [:article/render])
    :loading        [spinner]
    :error          [error-banner]
    :empty          [empty-state]
    :loaded         [article-body]
    :rf/uninitialised [spinner]))   ;; lazy-init boundary — render the resting/booting UI
```

The guarded-vector transition on `:load-success` (first match wins) routes an empty payload to `:empty` and a non-empty one to `:loaded` — the same discrimination the boolean cluster encoded, now declared once in the transition table instead of recomputed in four subs. The render priority that was previously hidden in the `cond` clause order now lives in the `render-priority` vector; the root view holds a single deref and a single branch. Adding a `:stale` state is one row in the table plus one `case` clause — no audit of mutual-exclusion across a boolean cluster.

> **The lazy-initialisation boundary.** `:initial :loading` declares where the machine *starts*, not that a snapshot already exists: until the machine first runs, `[:rf/machine :article]` is `nil` and the selector resolves no tag. Two complementary safeguards keep the pasted view safe — (1) dispatch the eager-start kick `[:article [:rf.machine/start]]` from app/frame init so the `:loading` snapshot is materialised before first paint, and (2) keep a `:rf/uninitialised` default branch so the root `case` cannot throw if the machine is rendered before any event reaches it. The kick alone is not enough — a render that races ahead of init, or a frame that reverts past the machine's birth, can still present `nil`; the default branch is the belt to the kick's braces.

For full page-level rendering across parallel render axes (data × form × mode), the same selector-sub idiom scales up to the Nine States pattern — see [`skills/re-frame2/patterns/nine-states.md`](../../re-frame2/patterns/nine-states.md).

> **`machine-has-tag?` vs the selector sub.** Reach for `@(rf/machine-has-tag? :article :some/tag)` directly for **single one-off affordances** — disabling a button while in-flight, showing a "read-only" badge — where you ask one independent tag-question. Route **mutually-exclusive whole-page render states** through one selector sub over a priority table, as above. A `cond` over multiple `machine-has-tag?` derefs in the root view re-introduces the very multi-boolean branch shape this leaf exists to retire.

## Edge cases — when boolean subs are fine

- **Genuinely independent predicates** that aren't mutually exclusive — `:cart/has-items?` and `:cart/over-shipping-threshold?` can both be `true` and aren't states of one FSM. Keep them as subs.
- **Layer-1 readers of one boolean app-db key** that aren't an FSM — `:flag/feature-x-enabled?` reading `(:feature-x? db)` is fine.
- **A two-state toggle** (`:open?` / `:closed?`) is small enough that a single sub + `if` in the view costs less than declaring a machine. The smell scales: 3+ mutually-exclusive booleans on the same path is the trigger.
