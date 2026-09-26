# 6. Parallel regions

<a id="parallel-regions"></a>
<a id="parallel-states"></a>

The session machine so far has one active leaf. A login **page** often has
two independent axes at once:

- the form: editing, valid, or invalid;
- the submit flow: idle, submitting, authed, or error.

Those axes are orthogonal. A flat machine would have to name the
cross-product (`:idle-and-invalid`, `:submitting-and-valid`, …). Parallel
regions keep the axes separate.

## When to use parallel regions

Use parallel regions when:

- the axes belong to one conceptual feature;
- they share one `:data` map;
- more than one axis can transition in response to the same event;
- the cross-product would be awkward to name.

Do not use parallel regions when the axes are separate features that do not share
data. Register separate machines instead.

## The shape

A parallel machine has `:type :parallel` and `:regions` at the root.

```clojure
(rf/defmachine login-page
  {:type :parallel
   :data {:attempts 0 :error nil}

   :guards
   {:form-valid?
    (fn [{:keys [tags]}]
      (contains? tags :form/valid))}

   :regions
   {:form
    {:initial :editing
     :states
     {:editing {:tags #{:form/editing}
                :on   {:form/valid   :valid
                       :form/invalid :invalid}}
      :valid   {:tags #{:form/valid}
                :on   {:form/invalid :invalid}}
      :invalid {:tags #{:form/invalid}
                :on   {:form/valid :valid}}}}

    :auth
    {:initial :idle
     :states
     {:idle
      {:on {:auth.login/submit {:target :submitting
                                :guard  :form-valid?}}}
      :submitting
      {:tags #{:auth/busy}
       :on   {:auth.login/success :authed
              :auth.login/failure :error-shown}}
      :authed      {:tags #{:auth/authed}}
      :error-shown {:on {:auth.login/dismiss :idle}}}}}})

(rf/reg-machine :auth.login/flow login-page)
```

Still one **singleton**. The form region does not know the auth region's
state names. Submit reads `:form/valid` off the tag union.

The [Nine States example](../../examples/patterns/nine_states) is the same
shape with a third `:mode` region.

Each region body is the root of a small machine: it has `:initial` and
`:states`, and may add `:entry` and `:exit` (run at birth and teardown),
`:tags`, an `:on` fallback for the region, and an `:on-done` that runs when the
region reaches a `:final?` child. That `:on-done` targets a state inside the
region. A region body may not declare `:after`
(`:rf.error/machine-non-parallel-root-after-not-supported`); put the timer on a
state inside the region.

At the top level, a parallel machine does not also declare root `:initial` and
root `:states`. Registration throws `:rf.error/machine-parallel-bad-shape` if
the root has either, or if a region is missing its own `:initial`.

## The snapshot state is a region map

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state {:form :valid
;;             :auth :submitting}
;;     :data  {:attempts 0 :error nil}
;;     :tags  #{:form/valid :auth/busy}}
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
(rf/dispatch-sync [:auth.login/flow [:form/valid]])
;; only the :form region handles it
;; {:form :valid, :auth :idle}
```

If several regions handle the same event, their actions run in region
declaration order and write to the shared `:data` in that order.

## Select first, then apply

Transition selection is done against the frozen pre-event configuration. Then
the selected transitions are applied.

That means region order can affect action/data accumulation order, but it does
not affect which transitions are selected. It also means a region guard cannot
see a sibling region's move from the same event
([Coordinating regions](#coordinating-regions-tags-as-statein)). Use `:raise`
if one region's move should trigger a second broadcast inside the same
macrostep.

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

A parallel root may also declare its own `:after` — the timer-driven analog
of the root `:on`. It arms at machine birth and belongs to the root, so no
region's own transitions cancel or restart it.

## When every region finishes

When every region reaches a `:final?` leaf, the root's `:on-done` fires. A
parallel root's `:on-done` runs its `:action` and emits its `:fx` only; a
`:target` is rejected at registration
(`:rf.error/machine-parallel-on-done-target`) because the root has no
sibling state to land on. The machine stays in the all-final configuration.

Without a root `:on-done`, all-regions-final ends the machine the way a
root-level `:final?` leaf does: a singleton is destroyed, and a spawned
child reports to its parent
([Actors](actors.md#when-a-child-finishes)). A child with a root `:on-done`
stays in the all-final configuration, so it never reports.

A finishing parallel child's result comes from the shared `:data`, at the
`:output-key` of the first region, in declaration order, whose final leaf
declares one. Declare it on one region. Two regions naming different keys emit
`:rf.error/machine-parallel-output-key-conflict`, and the first region's key
wins. If any region's final leaf has `:error? true`, the child fails instead
and reports that region's `:output-key` slot through the parent's `:on-error`.

## Coordinating regions: tags as `stateIn`

A region guard or action gets two extra context keys. They appear only inside a
parallel region; a flat machine's context stays `{:data :event :state :meta}`.

| Key | Meaning |
|---|---|
| `:tags` | the machine-wide tag union |
| `:all-state` | the full region → active-state map |

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

Because the tag union spans every region, a view can also ask one tag question
without knowing which region owns it, or collapse every axis into one
[render-priority table](tags.md#collapsing-many-states-into-one-render-decision).

## `:always` stabilization is parent-owned

A region chooses *where* its `:always` targets — those targets stay inside that
region. The **parent** owns settle.
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

A region state's `:after` timer and `:spawn` child belong to that region state:
sibling transitions neither cancel the timer nor destroy the child. A raised
event, by contrast, is broadcast to every region, like an external event, but
inside the current macrostep.

## Limitations

Nested parallel regions are not supported. A region may be hierarchical, but it
may not itself declare `:type :parallel`. Registration throws
`:rf.error/machine-parallel-nested-not-supported`.

If you find yourself wanting nested parallel, flatten the axes into one
parallel root or split the feature into several machines.

A `:regions` map of more than eight entries does not keep its written order,
and region order decides the order actions run in. Such a machine lists its
region names, in order, as `:region-order`; without it registration throws
`:rf.error/machine-parallel-region-order-required`, and an order that does not
name every region exactly once throws
`:rf.error/machine-parallel-region-order-mismatch`.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Registration throws `:rf.error/machine-parallel-bad-shape` | Root also has `:initial` / `:states`, or a region lacks `:initial` | `:type :parallel` uses `:regions` only; every region declares `:initial` |
| Registration throws `:rf.error/machine-root-slot-not-supported` naming `:regions` | The root has `:regions` but no `:type :parallel` | Add `:type :parallel`; the root then drops `:initial` and `:states` |
| Registration throws `:rf.error/machine-parallel-root-on-bad-target` | Root `:on` used a bare keyword target | Region-qualify: `[:left :two]` or `[[:left :two] [:right :two]]` |
| Registration throws `:rf.error/machine-parallel-nested-not-supported` | A region itself declares `:type :parallel` | Flatten the axes, or split into separate machines |
| Shared `:data` incremented twice on one event | Two regions handled the same event and both wrote | Put the write in one region, or on a root `:on` |
| Guard cannot see a sibling's same-event move | Selection is frozen for the round | Use `:raise`, or a later `:always` round |
| A spawned parallel child reports the wrong region's result, with `:rf.error/machine-parallel-output-key-conflict` | Final leaves in two regions name different `:output-key`s | Declare `:output-key` in one region, or the same key in each |
