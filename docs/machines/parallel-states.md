# Parallel regions

Some lifecycles aren't *one* question — they're several, all live at once. A todos screen is *somewhere in its fetch* (nothing / loading / empty / some / too-many), *somewhere in its form* (neutral / valid / invalid), and *somewhere in its page mode* (active / archived). Those three axes are **orthogonal**: each moves on its own, and any combination is legal.

Model that as one flat machine and the states multiply — three axes of three states each is `3 × 3 × 3 = 27` cross-product states, most of them named things like `:loading-and-invalid-and-active`. **Parallel regions** keep the axes apart. One machine declares `:type :parallel` and a `:regions` map; each region is a full little state-tree minding one axis; all regions are active simultaneously. Three axes of three states becomes **nine states across three regions**, not twenty-seven flat ones.

> **Coming from XState?** This is XState's `type: 'parallel'` state (and SCXML's `<parallel>`). Same concept, idiomatic spelling: `:type :parallel` plus a `:regions` map keyed by region name. The behaviour is the contract re-frame2 tracks — see [Coming from XState](coming-from-xstate.md) for the full delta, and [the machines guide](concepts.md#when-the-machine-grows) for where this sits among the other grammar extensions.

## When to reach for parallel regions

Reach for them when you have **multiple orthogonal axes of one feature** that share a domain: one form with data / validity / display-mode axes, one connection with auth + lifecycle + request-queue, one widget with display + interaction state. The giveaway is a single conceptual thing whose state is naturally a *tuple* of independent sub-states.

The axes share a single [`:data`](concepts.md#the-same-flow-as-a-transition-table) map *because they're facets of one domain* — they read and write the *same* data, just sliced differently. If your axes are genuinely separate features that share nothing (a websocket connection plus an unrelated auth flow plus a route), that's not parallel regions — that's **N separate machines** colocated in [runtime-db](../core/glossary.md#runtime-db). Per-region `:data` is deliberately **not** supported; if your axes need encapsulated data, that's the substrate telling you to register N machines instead. (The full parallel-regions-versus-N-machines trade-off lives in the spec — see Further reading.)

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

> **Coming from XState?** XState's parallel state value is a nested object, `{ data: 'loading', form: 'neutral', mode: 'active' }`. re-frame2's `:state` map is the same idea in Clojure data, and you read it through one ordinary subscription — `@(rf/subscribe [:rf/machine :ui/nine-states])` — not an `actor.getSnapshot()`. The [`[:rf/machine machine-id]`](../api/re-frame.machines.md#rfmachine-machine-id) sub returns the whole snapshot; named projections chain off it like any other [subscription](../core/concepts/subscriptions.md).

## Each region has its own `:initial`

Every region declares its own `:initial`, just like a flat machine — a region missing `:initial` is a registration-time error. At machine boot the initial snapshot's `:state` is the map of each region's initial cascade: a flat region lands on its `:initial` keyword; a compound region descends its `:initial` chain to a leaf. Each region's `:entry` cascade runs once at boot, and each region's birth `:always` settles independently as part of the initial step.

So immediately after the machine above starts, before any event:

```clojure
@(rf/subscribe [:rf/machine :ui/nine-states])
;; => {:state {:data :nothing :form :neutral :mode :active}
;;     :data  {:items [] :error nil :archived-at nil}
;;     :tags  #{:data/nothing :form/neutral :mode/active}}
```

All three regions are alive at their initial states, and the `:tags` set already carries one tag per region.

## Broadcast: one event reaches every region

This is the heart of it. Every event dispatched at a parallel machine is **broadcast to every region**. Each region's *currently-active* state independently decides whether the event matches one of its `:on` keys:

- **The region has a matching transition whose guard passes** → that region transitions (exit cascade → action → entry cascade), and any `:fx` its action returns joins the macrostep.
- **The region has no matching transition** (or its guard returns false) → that region stays put. No per-region complaint is raised.
- **No region anywhere handled the event** → the machine consults the [root `:on` fallback](#the-root-on-an-ancestor-fallback), and only if *that* also declines does it emit the benign `:rf.machine.event/unhandled-no-op` trace, exactly once. An unhandled event is a no-op, not an error.

In the nine-states machine, dispatching `:fetch-started` reaches all three regions, but only `:data` lists it, so only `:data` moves:

```clojure
(rf/dispatch-sync [:ui/nine-states [:fetch-started]])
;; :data → :loading ; :form and :mode have no :fetch-started, so they hold.
;; :state {:data :loading :form :neutral :mode :active}

(rf/dispatch-sync [:ui/nine-states [:fetch-succeeded {:items [{:id 1} {:id 2}]}]])
;; :data handles it: :loading → :resolving (running :set-items), then the
;; region's own :always cascade reads the now-2 :items and falls through to :some.
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

> **A subtle, load-bearing rule — selection is order-independent.** The broadcast is **select-then-apply**: every region's enabled transition is *selected* against **one frozen pre-broadcast snapshot** of the configuration, and only *then* are the selected transitions *applied* in declaration order. Declaration order governs only the apply order (action / `:fx` order, `:data` accumulation) — **never** which transitions are selected. Reorder the regions and you get the *same* selected set, and the new configuration is computed **atomically** old→new: no region ever observes an intermediate state where some siblings have moved and others haven't. This matches XState v5 / SCXML, where a parallel macrostep selects against the pre-event configuration. (It matters most for the next section.)

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

> **Coming from XState?** This is XState's "[multiple transitions in parallel states](https://stately.ai/docs/state-machines-and-statecharts)" — a transition on the `<parallel>` node targeting one or several regions, `target: ['.a.x', '.b.y']`, which re-frame2 spells `:target [[:a :x] [:b :y]]`. The atomic suppression-when-a-region-competes is XState v5 / SCXML behaviour.

## Coordinating regions: tags as `stateIn`

Orthogonal regions are independent — but sometimes one region's transition should depend on *where a sibling is*. The classic case: a `:checkout` region's `:submit` should only fire when the `:form` region is `:valid`.

In XState you'd write a `stateIn({ form: 'valid' })` guard (SCXML's `In()` predicate). re-frame2 ships the same **behaviour** without a separate combinator (behavioural parity, not API mimicry). When a guard or action runs **inside a region** of a parallel machine, its context map carries two extra cross-region keys alongside the usual `:data` / `:event` / `:state` / `:meta`:

| ctx key | value | use |
|---|---|---|
| **`:tags`** | the machine-wide active-configuration tag union | the **coarse** read — a sibling advertises a tag, you ask `(contains? tags :form/valid)`. The idiomatic choice: a tag is a *named* render-state that survives a sibling renaming its internal states. |
| **`:all-state`** | the full region → active-state map, e.g. `{:form :valid :checkout :idle}` | the **precise** read — `(= :valid (:form all-state))` matches a sibling's discrete state value directly, the literal analog of `stateIn({form: 'valid'})`. |

```clojure
;; xstate stateIn({form: 'valid'}), as re-frame2's tag substitute: :checkout's
;; :submit fires only while the :form region advertises :form/valid.
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

> **Both keys are frozen.** `:tags` and `:all-state` reflect the **frozen pre-broadcast snapshot** — the sibling configuration as of the *start* of the macrostep. A region's guard never sees a sibling's *same-event* move during selection (regions are genuinely simultaneous), which is exactly why selection is declaration-order-independent. A region reading its *own* state uses `:state` (which does evolve through its own `:always` loop); `:tags` / `:all-state` are the mechanism for reading *siblings*, and they're frozen. These two keys appear **only** for region guards/actions — a flat / compound machine's ctx stays exactly `{:data :event :state :meta}`, because its own `:state` is its `stateIn` substitute.

The two `dispatch-sync` calls above are **separate events**, which is why each `:submit` reads the prior committed config. If you need a *single* event to flip `:form` to `:valid` **and** advance `:checkout` in the same breath, two statechart-idiomatic paths re-couple them — they differ in *when* convergence lands:

- **`:raise` — converges in the SAME macrostep.** `:form`'s `:complete` action returns `:fx [[:raise [:form-valid]]]`; that internal event re-broadcasts across every region (next microstep, still inside the one atomic macrostep), and `:checkout`'s `:on {:form-valid …}` resolves it against the now-`:valid` `:form`. This is the strictly-same-macrostep path.
- **A guarded `:always` reading the sibling — converges on the NEXT EVENT.** `:checkout` carries `{:always {:target :submitting :guard :form-valid?}}`. A *plain* sibling move doesn't trigger an in-macrostep re-broadcast, so this re-selects against the committed `:form/valid` on the next event delivered — it fires, just one event later.

Both are bounded by the `:always-depth-limit` / `:raise-depth-limit` (default 16), so neither can spin.

## Per-region `:always` / `:after` / `:spawn` — and the `:raise` exception

Each region's state-node keys are **scoped to that region**:

- **`:always`** — the microstep loop runs *per region*. After a region's transition, that region's new state's `:always` entries are checked and settle to *that region's* fixed point; siblings aren't re-evaluated for `:always` on this region's microstep. (That's exactly the `:resolving → :empty | :some | :too-many` cascade in the nine-states `:data` region.)
- **`:after`** — a timer arms / cancels on *its region's* state entry / exit. A sibling region transitioning does **not** cancel this region's in-flight `:after` timer; each region keeps its own timer epoch.
- **`:spawn`** — a region's [`:spawn`](glossary.md#spawn)-bearing state starts and tears down child actors bound to *that region's* state; siblings never see the spawn / destroy cascade.
- **`:entry` / `:exit`** — fire on the region's own transitions, never on a sibling's.

> **`:raise` is the exception — it BROADCASTS.** A `[:raise [:event]]` emitted by any region's action does **not** stay local. It re-enters the parallel machine's single internal-event queue and re-broadcasts across **every** region — exactly as XState / SCXML deliver a `raise`d internal event to the whole machine, every active parallel state included. Each re-broadcast is its own microstep (a fresh frozen sibling view that already reflects the prior microstep's moves, while the in-flight `:data` flows through), the queue drains FIFO, and the whole macrostep — external event + every raised event + every region's `:always` settling — commits **once, atomically**, bounded by the `:raise-depth-limit`.

## Tags compose across regions

A parallel machine's `:tags` is the **union of every active state's tags across every active region** — walk each region's active configuration (root → leaf for compound regions), union the tags, then union across regions. So in the nine-states machine at boot, `:data`'s `:nothing` contributes `#{:data/nothing}`, `:form`'s `:neutral` contributes `#{:form/neutral}`, `:mode`'s `:active` contributes `#{:mode/active}`, and the snapshot's `:tags` is `#{:data/nothing :form/neutral :mode/active}`.

The framework [`machine-has-tag?`](../api/re-frame.machines.md) sub works unchanged — it asks "does the union contain this tag?", and the answer is yes iff *any* active state in *any* region declared it. That's what lets a view disable itself with `@(rf/machine-has-tag? :ui/nine-states :mode/read-only)` without caring which region (or which state of it) carries the read-only intent — *ask, don't tell* ([state tag](glossary.md#state-tag)).

The composed tag union also delivers the headline payoff: **collapsing N live axes down to one render decision**. Several tags are live at once, but the page draws one thing, so a plain-data priority table picks the winner:

```clojure
(def render-priority                       ;; read top to bottom; first match wins
  [{:tag :mode/done    :render :done}      ;; an archived list trumps everything
   {:tag :form/success :render :correct}   ;; transient form acknowledgements next
   {:tag :form/invalid :render :incorrect}
   {:tag :data/loading :render :loading}   ;; then the resting data axis
   {:tag :data/nothing :render :nothing}
   {:tag :data/empty   :render :empty}
   {:tag :data/some    :render :some}
   {:tag :data/too-many :render :too-many}])

(rf/reg-sub :ui/render
  :<- [:rf/machine :ui/nine-states]
  (fn [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}] (when (contains? tags tag) render))
            render-priority))))
```

The root view then branches with a single `case` over `@(rf/subscribe [:ui/render])`. Three regions, nine states, **one** branch site — and the display priorities live in *one* readable table, not scattered across ten views.

## A divergence to know: no nested parallel regions

**Nested parallel regions are not supported in v1.** A region whose own tree declares `:type :parallel` is rejected at registration with `:rf.error/machine-parallel-nested-not-supported`. XState permits arbitrary nesting; re-frame2 does not (yet). Model a would-be two-level cross-product as a flatter set of regions, or — more idiomatically — as multiple top-level parallel-region machines. A region *may* still be a compound (hierarchical) state-tree; it just can't itself be parallel.

## Further reading

- **Normative contract** — [Spec 005 §Parallel regions](../../spec/005-StateMachines.md#parallel-regions) is the exhaustive source: the select-then-apply broadcast model, the root-`:on` / root-`:after` ancestor fallback, the frozen `:tags` / `:all-state` reads, per-region scoping, capability gating, and the registration-error taxonomy.
- **The machine surface** — the [`re-frame.machines` API](../api/re-frame.machines.md) for `reg-machine`, the [`[:rf/machine machine-id]`](../api/re-frame.machines.md#rfmachine-machine-id) subscription, and `machine-has-tag?`.
- **The other grammar** — [Machines concepts](concepts.md#when-the-machine-grows) (where parallel regions sit among hierarchy, history, `:after`, `:spawn`, and final states) and [Coming from XState](coming-from-xstate.md).
- **Runnable code** — the full [Nine States of UI example](../../examples/patterns/nine_states), where this exact machine drives a real managed-HTTP fetch, a form slice, and a read-only archive mode.
- **The broader idea** — parallel regions realise the orthogonal-region concept from the statechart literature: [XState statecharts docs](https://stately.ai/docs/state-machines-and-statecharts) and [Fulcro statecharts](https://fulcrologic.github.io/statecharts/) cover the same `parallel` / `In()` concepts in their own runtimes.
