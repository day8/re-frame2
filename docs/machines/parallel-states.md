# Parallel regions

One machine, several **orthogonal** axes active at once — form validity × load
state × mode — without exploding a cross-product of flat states. Assumes the
[flat model](concepts.md); pairs naturally with [tags](tags.md).

Some lifecycles aren't *one* question — they're several, all live at once. A todos
screen is *somewhere in its fetch*, *somewhere in its form*, and *somewhere in its
page mode*. Those three axes are **orthogonal**: each moves on its own, and any
combination is legal.

Model that flat and the states multiply — three axes of three states each is
`3 × 3 × 3 = 27` cross-product states named things like
`:loading-and-invalid-and-active`. **Parallel regions** keep the axes apart: one
machine, `:type :parallel`, a `:regions` map; each region is a full little
state-tree; all regions active simultaneously. Three axes of three states becomes
**nine states across three regions**, not twenty-seven flat ones.

## When to reach for parallel regions

Reach for them when you have **multiple orthogonal axes of one feature** that share
a domain: one form with data / validity / display-mode axes, one connection with
auth + lifecycle + request-queue. The giveaway is a single conceptual thing whose
state is naturally a *tuple* of independent sub-states.

The axes share a single
[`:data`](concepts.md#the-same-flow-as-a-transition-table) map *because they're
facets of one domain*. If your axes are genuinely separate features that share
nothing (websocket + unrelated auth + route), that's **N separate machines** in
[runtime-db](../core/glossary.md#runtime-db). Per-region `:data` is **not**
supported; if axes need encapsulated data, register N machines instead.

## The shape: one machine, several regions

Here's the canonical three-region machine — the "[Nine States of UI](../../examples/patterns/nine_states)" pattern, trimmed to its bones. Three axes, one declaration:

```clojure
(rf/reg-machine :ui/nine-states
  {:type :parallel
   :data {:items [] :error nil :archived-at nil}     ;; shared across every region

   :guards  {:empty?    (fn [{:keys [data]}] (zero? (count (:items data))))
             :too-many? (fn [{:keys [data]}] (> (count (:items data)) 7))}

   :actions {:set-items (fn [{data :data [_ {:keys [items]}] :event}]
                          {:data (assoc data :items (vec items))})}

   :regions
   {:data {:initial :nothing
           :states  {:nothing   {:tags #{:data/nothing}
                                 :on   {:fetch-started :loading}}
                     :loading   {:tags #{:data/loading}
                                 :on   {:fetch-succeeded {:target :resolving
                                                          :action :set-items}}}
                     :resolving {:always [{:guard :empty?    :target :empty}
                                          {:guard :too-many? :target :too-many}
                                          {:target :some}]}
                     :empty     {:tags #{:data/empty}}
                     :some      {:tags #{:data/some}}
                     :too-many  {:tags #{:data/too-many}}}}

    :form {:initial :neutral
           :states  {:neutral   {:tags #{:form/neutral}
                                 :on   {:submit-valid   :correct
                                        :submit-invalid :incorrect}}
                     :incorrect {:tags #{:form/invalid}}
                     :correct   {:tags #{:form/success}}}}

    :mode {:initial :active
           :states  {:active {:tags #{:mode/active}
                              :on   {:archive :done}}
                     :done   {:tags #{:mode/done :mode/read-only}}}}}})
```

Each region body — `:data`, `:form`, `:mode` — is itself an ordinary transition table: its own `:initial`, its own `:states`, with `:on` / `:tags` / `:always` / `:after` / `:spawn` on each node, exactly as a flat machine. The only structural rule at the top: `:type :parallel` is **mutually exclusive** with a root `:initial` / `:states` — you declare *regions* there instead (registration throws `:rf.error/machine-parallel-bad-shape` if you write both).

## The snapshot: `:state` becomes a map

A flat machine's [snapshot](glossary.md#snapshot) `:state` is a single keyword. A parallel machine's `:state` is a **map of region-name → that region's current state**:

```clojure
;; flat region — the value is a keyword
{:state {:data :loading :form :neutral :mode :active}
 :data  {:items [] :error nil :archived-at nil}
 :tags  #{:data/loading :form/neutral :mode/active}}

;; a COMPOUND region — that region's value is a vector path INSIDE the region
{:state {:auth [:authenticated :dashboard] :lifecycle :idle} ...}
```

Three things to read off that snapshot:

- **`:state`** is the per-region map. A flat region contributes a keyword; a compound region (one whose own tree nests sub-states) contributes the in-region vector path.
- **`:data`** is **shared** — one map, seen and written by every region. There is no per-region `:data` slot, on the region body or in the snapshot.
- **`:tags`** is the **union** of every active state's tags across every region (covered [below](#tags-compose-across-regions)).

You read the `:state` map through one ordinary subscription — `@(rf/subscribe [:rf/machine :ui/nine-states])`. The [`[:rf/machine machine-id]`](../api/re-frame.machines.md#rfmachine-machine-id) sub returns the whole snapshot; named projections chain off it like any other [subscription](../core/subscriptions.md).

## Each region has its own `:initial`

Every region declares its own `:initial`, just like a flat machine — a region missing `:initial` is a registration-time error. At machine boot the initial snapshot's `:state` is the map of each region's initial cascade: a flat region lands on its `:initial` keyword; a compound region descends its `:initial` chain to a leaf. Every region's `:entry` cascade runs once, for every region, *before* any eventless work begins. Only then does the machine settle its `:always` transitions — and it settles them the same way it settles them after an event: the parent runs [frozen rounds](#always-stabilization-is-parent-owned) across the whole configuration until nothing more is enabled. A region's birth `:always` guard can therefore read where its siblings landed, because every sibling has already entered.

So immediately after the machine above starts, before any event:

```clojure
@(rf/subscribe [:rf/machine :ui/nine-states])
;; => {:state {:data :nothing :form :neutral :mode :active}
;;     :data  {:items [] :error nil :archived-at nil}
;;     :tags  #{:data/nothing :form/neutral :mode/active}}
```

All three regions are alive at their initial states, and the `:tags` set already carries one tag per region.

## Broadcast: one event reaches every region

Every event dispatched at a parallel machine is **broadcast to every region**. Each
region's *currently-active* state independently decides whether the event matches
one of its `:on` keys:

- **The region has a matching transition whose guard passes** → that region transitions (exit cascade → action → entry cascade), and any `:fx` its action returns joins the macrostep.
- **The region has no matching transition** (or its guard returns false) → that region stays put. No per-region complaint is raised.
- **No region anywhere handled the event** → the machine consults the [root `:on` fallback](#the-root-on-an-ancestor-fallback), and only if *that* also declines does it emit the benign `:rf.machine.event/unhandled-no-op` trace, exactly once. An unhandled event is a no-op, not an error.

In the nine-states machine, dispatching `:fetch-started` reaches all three regions, but only `:data` lists it, so only `:data` moves:

```clojure
(rf/dispatch-sync [:ui/nine-states [:fetch-started]])
;; :data → :loading ; :form and :mode have no :fetch-started, so they hold.
;; :state {:data :loading :form :neutral :mode :active}

(rf/dispatch-sync [:ui/nine-states [:fetch-succeeded {:items [{:id 1} {:id 2}]}]])
;; :data handles it: :loading → :resolving (running :set-items). The event set
;; having applied, the first eventless round reads the now-2 :items and the
;; :resolving cascade lands on :some — all within this one macrostep.
;; :state {:data :some :form :neutral :mode :active}  ·  :data {:items [{:id 1} {:id 2}] ...}
```

(`dispatch-sync` runs the transition synchronously, so the snapshot is readable on the next line — handy in the REPL and tests; in views you dispatch the usual async way.)

### Shared `:data` flows through each region's action

When *several* regions handle the same event, each one's action runs against the **shared** `:data` — and an [action](glossary.md#action) returns the same data-shaped effect map a `reg-event` handler does, `{:data ... :fx ...}`. The selected transitions apply in **region-declaration order**, so each region's action sees the prior region's `:data` writes:

```clojure
(rf/reg-machine :ui/example
  {:type    :parallel
   :data    {:count 0}
   :actions {:bump-count (fn [{d :data}] {:data (update d :count inc)})}
   :regions
   {:left  {:initial :a
            :states  {:a {:tags #{:left/a} :on {:reset {:target :a :action :bump-count}}}}}
    :right {:initial :x
            :states  {:x {:tags #{:right/x} :on {:reset {:target :x :action :bump-count}}}}}}})

(rf/dispatch-sync [:ui/example [:reset]])
;; Both regions handle :reset. :bump-count runs ONCE PER REGION against the
;; shared :data — :count goes 0 → 1 → 2.
;; => {:state {:left :a :right :x} :data {:count 2} :tags #{:left/a :right/x}}
```

If you wanted that event to count *once*, you'd register the coordinating action at the parent-machine level, or shape the regions so only one handles it.

!!! note "Selection is order-independent"

    The broadcast is **select-then-apply**: every region's enabled transition is
    *selected* against **one frozen pre-broadcast snapshot**, and only *then* are
    the selected transitions *applied* in declaration order. Declaration order
    governs apply order (action / `:fx`, `:data` accumulation) — **never** which
    transitions are selected. Reorder the regions and you get the *same* selected
    set; the new configuration is computed **atomically** old→new — no region
    observes an intermediate where some siblings have moved and others haven't.
    Matches SCXML: a parallel macrostep selects against the pre-event configuration.

    The same select-then-apply shape governs `:always` once the event set has
    landed — the parent repeats it per [round](#always-stabilization-is-parent-owned)
    until the machine is quiescent.

## The root `:on`: an ancestor fallback

A `:type :parallel` machine may declare its **own** `:on` at the root — alongside `:regions`, not instead of them. This is the **ancestor fallback**: the parallel analog of a flat or compound machine's root `:on` fallthrough.

```clojure
(rf/reg-machine :ui/board
  {:type :parallel
   :data {}
   :regions
   {:a {:initial :one
        :states  {:one     {:on {:reset :special}}   ;; region :a handles :reset ITSELF
                  :two     {}
                  :special {}}}
    :b {:initial :one
        :states  {:one {} :two {}}}}
   :on {:go-all {:target [[:a :two] [:b :two]]}      ;; no region declares :go-all
        :reset  {:target [[:a :one] [:b :two]]}}})   ;; region :a DOES declare :reset
```

The rule has two halves, and the second is the whole point:

**When no region handled the event, the root fires.** Nothing else lists `:go-all`, so the root catches it and moves the regions it targets:

```clojure
(rf/dispatch-sync [:ui/board [:go-all]])
;; No region declares :go-all → root ancestor fallback fires.
;; :state {:a :two :b :two}
```

**When *any* region handled the event, the root is suppressed *entirely* — atomic, all-or-nothing.** It is *not* "fire the root for the regions that didn't handle it." Region `:a` declares `:reset` locally, so on `:reset` the root transition is dropped wholesale, and regions the root *would* have moved are left untouched:

```clojure
;; from the initial {:a :one :b :one}:
(rf/dispatch-sync [:ui/board [:reset]])
;; :a has a local :reset (:one → :special) → :a competes → the WHOLE root :reset
;; is suppressed → :b is left UNCHANGED (stays :one), even though the root's :reset
;; targeted :b → :two.
;; :state {:a :special :b :one}
```

That last result is the **non-decomposable** case. A naive broadcast decomposition (region `:a`: `reset → special`; region `:b`: `reset → :two`) would fire both independently and give `{:a :special :b :two}`. The atomic suppression instead leaves `:b` untouched — a coordination a *per-region* `:on` simply cannot express, because per-region transitions have no cross-region suppression.

**Target grammar.** A root `:on` target is one of: **targetless / action-only** (runs `:action` / `:fx`, moves no region); a **single region-qualified target** `[<region> & <in-region-path>]` (e.g. `[:a :two]`, or `[:a :compound :leaf]`); or **multiple** `[[<region> …] [<region> …]]` (e.g. `[[:a :x] [:b :y]]`). A bare-keyword target, or one whose head isn't a declared region, is rejected at registration with `:rf.error/machine-parallel-root-on-bad-target` — a root-only parallel machine has no flat sibling state to land a non-region-qualified target on.

A `:type :parallel` root may also declare its own **`:after`** — the timer-driven twin of the root `:on`. It's scheduled at machine birth, owned by the root (alive for the machine's whole life rather than tied to any one region's lifecycle), and uses the very same region-qualified target grammar.

## Coordinating regions: tags as `stateIn`

Orthogonal regions are independent — but sometimes one region's transition should depend on *where a sibling is*. The classic case: a `:checkout` region's `:submit` should only fire when the `:form` region is `:valid`.

re-frame2 lets one region's guard or action read where a sibling sits — no separate combinator needed. When a guard or action runs **inside a region** of a parallel machine, its context map carries two extra cross-region keys alongside the usual `:data` / `:event` / `:state` / `:meta`:

| ctx key | value | use |
|---|---|---|
| **`:tags`** | the machine-wide active-configuration tag union | the **coarse** read — a sibling advertises a tag, you ask `(contains? tags :form/valid)`. The idiomatic choice: a tag is a *named* render-state that survives a sibling renaming its internal states. |
| **`:all-state`** | the full region → active-state map, e.g. `{:form :valid :checkout :idle}` | the **precise** read — `(= :valid (:form all-state))` matches a sibling's discrete state value directly. |

```clojure
;; :checkout's :submit fires only while the :form region
;; advertises :form/valid — read via the cross-region :tags key.
(rf/reg-machine :ui/checkout
  {:type   :parallel
   :data   {}
   :guards {:form-valid? (fn [{:keys [tags]}] (contains? tags :form/valid))}
   :regions
   {:form     {:initial :editing
               :states  {:editing {:tags #{:form/editing} :on {:complete :valid}}
                         :valid   {:tags #{:form/valid}}}}
    :checkout {:initial :idle
               :states  {:idle       {:on {:submit {:target :submitting
                                                    :guard  :form-valid?}}}
                         :submitting {:tags #{:checkout/submitting}}}}}})

(rf/dispatch-sync [:ui/checkout [:submit]])
;; :form is still :editing → :form/valid is NOT in the union → :submit BLOCKED.
;; :state stays {:form :editing :checkout :idle}

(rf/dispatch-sync [:ui/checkout [:complete]])   ;; :form → :valid (advertises :form/valid)
(rf/dispatch-sync [:ui/checkout [:submit]])
;; :checkout's guard now reads :form/valid in (:tags ctx) → :submit FIRES.
;; :state → {:form :valid :checkout :submitting}
```

The precise variant reads the sibling's state value directly:

```clojure
:guards {:form-is-valid? (fn [{:keys [all-state]}] (= :valid (:form all-state)))}
```

Prefer the **tag** query: a tag is a named, tool-legible render-state, and it holds up when a sibling region refactors its internal state names.

!!! note "Both keys are frozen — per selection round"

    `:tags` and `:all-state` are frozen for the **selection round** that is currently choosing transitions, not for the whole macrostep. Within a round every co-selected region reads the *same* sibling view, so no region can observe a sibling's move from the set being selected alongside it — which is exactly why selection is declaration-order-independent. Between rounds the view is **re-frozen**, so each round sees the complete result of the one before it. A region reading its *own* state uses `:state`; `:tags` / `:all-state` are the mechanism for reading *siblings*. These two keys appear **only** for region guards/actions — a flat / compound machine's ctx stays exactly `{:data :event :state :meta}`, because it has no siblings to read; its own `:state` already answers *where am I?*.

The two `dispatch-sync` calls above are **separate events**, which is why each `:submit` reads the prior committed config. If you need a *single* event to flip `:form` to `:valid` **and** advance `:checkout` in the same breath, two statechart-idiomatic paths re-couple them. Both converge inside the one macrostep; they differ in *which* mechanism carries the dependency:

- **`:raise` — the region announces the news.** `:form`'s `:complete` action returns `:fx [[:raise [:form-valid]]]`; that internal event re-broadcasts across every region on the next microstep, and `:checkout`'s `:on {:form-valid …}` resolves it against the now-`:valid` `:form`. Reach for it when the sibling should react to an *event* — something happened — and when you want the dependency written down as a name.
- **A guarded `:always` reading the sibling — the region notices the condition.** `:checkout` carries `{:always {:target :submitting :guard :form-valid?}}`. Once the event set has applied, the parent's first eventless round evaluates that guard against the updated configuration and `:checkout` moves — in the **same** macrostep, before it commits. Reach for it when the sibling should track a *condition* — the world now looks like this — without the announcing region needing to know anyone is listening.

Both are bounded by the `:always-depth-limit` / `:raise-depth-limit` (default 16), so neither can spin.

## Per-region `:always` / `:after` / `:spawn` — and the `:raise` exception

Each region's state-node keys are **scoped to that region**:

- **`:always`** — a region's `:always` entries target states *inside that region*: the `:resolving → :empty | :some | :too-many` cascade in the nine-states `:data` region can only land on `:data`'s own states, never on a sibling's. But **targeting is not stabilization**. *Which* `:always` transitions fire, and *when* the loop is finished, is decided by the **parent** across the whole configuration — see [below](#always-stabilization-is-parent-owned). Keep those two ideas apart and the rest of this section follows.
- **`:after`** — a timer arms / cancels on *its region's* state entry / exit. A sibling region transitioning does **not** cancel this region's in-flight `:after` timer; each region keeps its own timer epoch.
- **`:spawn`** — a region's [`:spawn`](glossary.md#spawn)-bearing state starts and tears down child actors bound to *that region's* state; siblings never see the spawn / destroy cascade.
- **`:entry` / `:exit`** — fire on the region's own transitions, never on a sibling's.

!!! note "`:raise` is the exception — it BROADCASTS"

    A `[:raise [:event]]` emitted by any region's action does **not** stay local. It re-enters the parallel machine's single internal-event queue and re-broadcasts across **every** region — exactly as SCXML delivers a `raise`d internal event to the whole machine, every active parallel state included. Each re-broadcast is its own microstep (a fresh frozen sibling view that already reflects the prior microstep's moves, while the in-flight `:data` flows through), the queue drains FIFO, and the whole macrostep — external event + every raised event + every eventless round — commits **once, atomically**, bounded by the `:raise-depth-limit`. Eventless work has priority: the parent settles `:always` to a fixed point before it dequeues the next raise.

## `:always` stabilization is parent-owned

A region chooses *where* its `:always` transitions go. The **parent** decides *when* they fire. After the selected event transitions have applied, the parent repeats one **round** until nothing is left to do:

1. **Freeze** the current whole configuration and `:data`.
2. **Select** every regional `:always` transition enabled against that one frozen view.
3. **Apply** the whole selected set, in region-declaration order.

Then it freezes again and goes round once more, until a round selects nothing. Only then does the macrostep commit. So the loop is not *"each region settles itself"* — it is *"the machine settles, one whole-configuration round at a time"*.

That distinction is worth one worked example. Three regions, one dispatch:

```clojure
(rf/reg-machine :ui/gate
  {:type :parallel
   :data {:ready? false :cleared? false}

   :guards  {:ready?   (fn [{:keys [data]}] (:ready? data))
             :cleared? (fn [{:keys [data]}] (:cleared? data))}

   :actions {:mark-ready (fn [{d :data}] {:data (assoc d :ready? true)})
             :clear      (fn [{d :data}] {:data (assoc d :cleared? true)})}

   :regions
   {:source {:initial :idle
             :states  {:idle {:on {:go {:target :sent :action :mark-ready}}}
                       :sent {}}}

    :gate   {:initial :closed
             :states  {:closed {:always [{:guard :ready? :target :open :action :clear}]}
                       :open   {}}}

    :audit  {:initial :waiting
             :states  {:waiting {:always [{:guard :cleared? :target :logged}]}
                       :logged  {}}}}})

(rf/dispatch-sync [:ui/gate [:go]])
;; => {:state {:source :sent :gate :open :audit :logged}
;;     :data  {:ready? true :cleared? true}}
```

One event; all three regions moved. Here is how, round by round:

**The event set applies first.** `:go` is broadcast; only `:source` handles it, and its `:mark-ready` action sets `:ready?`. Every selected event action runs to completion **before** any `:always` is considered — so the first round already sees the finished work of the event.

**Round 1 — selection is frozen.** The parent freezes the configuration and `:data` (`:ready?` true, `:cleared?` false) and asks every region what is enabled. `:gate` is: its `:ready?` guard passes, so `:closed → :open` is selected. `:audit` is **not**: its `:cleared?` guard reads the frozen `:data`, where `:cleared?` is still false. The parent then applies the selected set — `:gate` moves and its `:clear` action sets `:cleared?`. Note what *didn't* happen: `:audit` did not move in this round, even though by the time the round finished applying, its guard's condition held. Selection was decided before any of it applied.

**Round 2 — the view is re-frozen.** A new round, a new freeze: `:cleared?` is now true. `:audit`'s guard passes, and `:waiting → :logged` is selected and applied. This is the payoff of re-freezing — each round sees the previous round's completed result.

**Round 3 — the fixed point.** Nothing is enabled. The loop stops and the macrostep commits, publishing `{:source :sent :gate :open :audit :logged}` as one atomic step. External observers never see the intermediate rounds; they see one dispatch and one new configuration.

The same process runs at **birth**, once every region's `:entry` cascade has completed — which is why a birth `:always` guard can read where its siblings landed.

Two consequences worth naming:

**Within one round, reordering the regions cannot change what is selected.** Because each round selects against a frozen view, no region's guard can ever observe a sibling's move from the set being selected alongside it — declaration order never changes *which* transitions a single round chooses. What it does govern is the *apply* order within that round: action and `:fx` order, and the order in which the selected actions accumulate `:data`. That apply-order reaches *across* rounds, because the `:data` a round accumulates is exactly the view the *next* round freezes. When two regions' actions write the same `:data` key with non-commuting values, reordering them changes what a later round sees, and can therefore change which `:always` that later round selects. So order independence is a guarantee about *selection within one frozen round*, not an unconditional guarantee about the final outcome. Write regions whose actions touch disjoint `:data` keys — those writes commute, so their order stops mattering entirely.

**Failure is bounded and atomic.** `:always-depth-limit` (default 16) counts parent **rounds**, not transitions — a round in which five regions move is one round. If a machine spins past the limit, or a guard or action throws, the **whole** macrostep fails and rolls back: no snapshot is published and no effects run. There is no half-settled configuration to reason about, which is what makes the fixed point safe to lean on.

## Tags compose across regions

A parallel machine's `:tags` is the **union of every active state's tags across every active region** — walk each region's active configuration (root → leaf for compound regions), union the tags, then union across regions. So in the nine-states machine at boot, `:data`'s `:nothing` contributes `#{:data/nothing}`, `:form`'s `:neutral` contributes `#{:form/neutral}`, `:mode`'s `:active` contributes `#{:mode/active}`, and the snapshot's `:tags` is `#{:data/nothing :form/neutral :mode/active}`.

The framework [`machine-has-tag?`](../api/re-frame.machines.md) sub works unchanged — it asks "does the union contain this tag?", and the answer is yes iff *any* active state in *any* region declared it. That's what lets a view disable itself with `@(rf/subscribe [:rf.machine/has-tag? :ui/nine-states :mode/read-only])` without caring which region (or which state of it) carries the read-only intent — *ask, don't tell* ([state tag](glossary.md#state-tag)).

The composed tag union also delivers the headline payoff: **collapsing N live axes down to one render decision**. Several tags are live at once, but the page draws one thing, so a plain-data priority table picks the winner and the root view branches with a single `case`. Three regions, nine states, one branch site. That pattern — the priority table, the selector sub, the one-`case` root view — is worked in [Tags → Collapsing many states into one render decision](tags.md#collapsing-many-states-into-one-render-decision).

## A divergence to know: no nested parallel regions

**Nested parallel regions are not supported in v1.** A region whose own tree
declares `:type :parallel` is rejected at registration with
`:rf.error/machine-parallel-nested-not-supported`. Model a would-be two-level
cross-product as a flatter set of regions, or as multiple top-level parallel
machines. A region *may* still be a compound (hierarchical) state-tree; it just
can't itself be parallel. This is a **deferred** divergence — reconsidered if a
real two-level cross-product appears that genuinely cannot flatten.
