# Parallel regions

Sometimes one feature has several independent state axes active at the same time.

A todos page might be:

- in a data state: `:nothing | :loading | :empty | :some | :error`;
- in a form state: `:neutral | :valid | :invalid`;
- in a mode state: `:active | :archived`.

Those axes are orthogonal. A flat machine would have to name the cross-product.
Parallel regions keep the axes separate.

## When to use parallel regions

Use parallel regions when:

- the axes belong to one conceptual feature;
- they share one `:data` map;
- more than one axis can transition in response to the same event;
- the cross-product would be awkward to name.

Do not use parallel regions when the axes are separate features that do not share
data. Register separate machines instead.

There is no per-region `:data`. A parallel machine has one shared `:data` map.

## The shape

A parallel machine has `:type :parallel` and `:regions` at the root.

```clojure
;; cf. examples/patterns/nine_states
(rf/defmachine nine-states
  {:type :parallel
   :data {:items [] :error nil}

   :guards
   {:empty? (fn [{data :data}]
              (zero? (count (:items data))))}

   :actions
   {:set-items (fn [{data :data [_ {:keys [items]}] :event}]
                 {:data (assoc data :items (vec items))})}

   :regions
   {:data
    {:initial :nothing
     :states
     {:nothing   {:tags #{:data/nothing}
                  :on   {:fetch-started :loading}}
      :loading   {:tags #{:data/loading}
                  :on   {:fetch-succeeded {:target :resolving
                                           :action :set-items}
                         :fetch-failed    :error}}
      :resolving {:always [{:guard :empty? :target :empty}
                           {:target :some}]}
      :empty     {:tags #{:data/empty}}
      :some      {:tags #{:data/some}}
      :error     {:tags #{:data/error}}}}

    :form
    {:initial :neutral
     :states
     {:neutral {:tags #{:form/neutral}
                :on   {:submit-valid   :valid
                       :submit-invalid :invalid}}
      :valid   {:tags #{:form/valid}}
      :invalid {:tags #{:form/invalid}}}}

    :mode
    {:initial :active
     :states
     {:active   {:tags #{:mode/active}
                 :on   {:archive :archived}}
      :archived {:tags #{:mode/archived :mode/read-only}}}}}})

(rf/reg-machine :ui/nine-states nine-states)
```

Each region body looks like a small machine: it has `:initial` and `:states`.

At the top level, a parallel machine does not also declare root `:initial` and
root `:states`. Registration throws `:rf.error/machine-parallel-bad-shape` if
the root has both, or if a region is missing its own `:initial`.

## The snapshot state is a region map

```clojure
@(rf/subscribe [:rf/machine :ui/nine-states])
;; => {:state {:data :loading
;;             :form :neutral
;;             :mode :active}
;;     :data  {:items [] :error nil}
;;     :tags  #{:data/loading :form/neutral :mode/active}}
```

A compound region contributes a path as its region value:

```clojure
{:state {:auth [:authenticated :dashboard]
         :mode :active}
 :data  {...}}
```

## Every region starts

At machine birth, every region enters its own initial state. If a region is
hierarchical, it follows its own `:initial` cascade to a leaf.

Every region's entry actions run first. Then the parent settles `:always` across
the whole configuration — the same freeze / select / apply rounds used after an
event. See [`:always` stabilization](#always-stabilization-is-parent-owned).

## Event broadcast

Every event dispatched at a parallel machine is broadcast to every region.

For each region:

- if the active state has a matching transition whose guard passes, that region
  transitions;
- otherwise that region stays where it is.

```clojure
(rf/dispatch-sync [:ui/nine-states [:fetch-started]])
;; only the :data region handles it
;; {:data :loading, :form :neutral, :mode :active}
```

If several regions handle the same event, their actions run in region
declaration order and write to the shared `:data` in that order.

## Select first, then apply

Transition selection is done against the frozen pre-event configuration. Then
the selected transitions are applied.

That means region order can affect action/data accumulation order, but it does
not affect which transitions are selected.

A region guard cannot see a sibling region's move from the same broadcast event.
It sees the sibling state as that broadcast's selection froze it. One macrostep
can run several selections — the broadcast, then parent `:always` rounds, then
any `:raise` re-broadcast — and each one freezes the view afresh.

Use `:raise` if one region's move should trigger a second broadcast inside the
same macrostep.

## Shared data

Because all regions share `:data`, two regions handling one event can both
update it. `:data` merges, so an action that returns `{:data {:count n}}` writes
only `:count`.

```clojure
:actions
{:bump (fn [{data :data}]
         {:data {:count (inc (:count data))}})}
```

If two regions run `:bump`, `:count` increments twice.

If that is not what you meant, put the update in one region, or model the
coordination as a root transition.

## Root `:on` fallback

A parallel machine may declare root `:on` alongside `:regions`.

```clojure
(rf/reg-machine :board
  {:type :parallel
   :data {}
   :regions
   {:left  {:initial :one :states {:one {} :two {}}}
    :right {:initial :one :states {:one {} :two {}}}}

   :on
   {:go-all {:target [[:left :two] [:right :two]]}}})
```

The root transition fires only when no region handles the event.

If any region handles the event, the root fallback is suppressed entirely. It
is not applied to only the regions that did not handle it.

Root targets are region-qualified:

```clojure
[:left :two]
[[:left :two] [:right :two]]
```

A bare keyword target at the root of a parallel machine is rejected at
registration with `:rf.error/machine-parallel-root-on-bad-target`.

## Coordinating regions: tags as `stateIn`

A region guard or action gets two extra context keys. They appear only inside a
parallel region; a flat machine's context stays `{:data :event :state :meta}`.

| Key | Meaning |
|---|---|
| `:tags` | the machine-wide tag union from the pre-event snapshot |
| `:all-state` | the full region → active-state map from the pre-event snapshot |

Prefer tags:

```clojure
:guards
{:form-valid?
 (fn [{:keys [tags]}]
   (contains? tags :form/valid))}
```

Use `:all-state` only when exact state names are the contract:

```clojure
(fn [{:keys [all-state]}]
  (= :valid (:form all-state)))
```

Both keys are frozen for the **selection round** that is currently choosing
transitions, not for the whole macrostep. Between rounds the view is re-frozen,
so each round sees the completed result of the one before it. A same-event move
in a sibling region becomes visible on the next round, not on this one.

## `:always`, `:after`, and `:spawn` are region-scoped

A region chooses *where* its `:always` targets — those targets stay inside that
region. The parent owns settle; see
[below](#always-stabilization-is-parent-owned).

A region state's `:after` timer belongs to that region state. Sibling
transitions do not cancel it.

A region state's `:spawn` child is bound to that region state. Sibling
transitions do not destroy it.

The exception is `:raise`: a raised event is broadcast to every region, just
like an external event, but still inside the current macrostep.

## `:always` stabilization is parent-owned

A region chooses *where* its `:always` targets. The **parent** owns settle.
After the event set has applied, the parent freezes the whole configuration,
selects every enabled regional `:always` against that one frozen view, applies
the selected set, and freezes again. It repeats until a round selects nothing.

The loop is not "each region settles itself." One region's `:always` action can
enable a sibling's `:always`, and that sibling waits for the *next* parent
round. A tiny case: `:source` writes `:ready?`, `:gate`'s `:always` reads it and
writes `:cleared?`, `:audit`'s `:always` reads `:cleared?` — one event, two
parent rounds, then the snapshot commits.

The loop is bounded by `:always-depth-limit` (default 16). The limit counts
parent **rounds** — a round in which five regions move is one round, not five.

## Tags compose across regions

The snapshot's `:tags` is the union of every active state in every region.

That lets a view ask one question without knowing which region owns the tag:

```clojure
@(rf/subscribe [:rf.machine/has-tag? :ui/nine-states :mode/read-only])
```

It also lets you build a single render-priority table across all axes. See
[State tags](tags.md#collapsing-many-states-into-one-render-decision).

## Limitations

Nested parallel regions are not supported. A region may be hierarchical, but it
may not itself declare `:type :parallel`. Registration throws
`:rf.error/machine-parallel-nested-not-supported`.

If you find yourself wanting nested parallel, flatten the axes into one
parallel root or split the feature into several machines.
