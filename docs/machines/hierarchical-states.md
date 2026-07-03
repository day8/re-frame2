# Hierarchical (compound) states

A flat machine is a single list of states, one active at a time. That carries
most flows. But some states are really a *cluster*: three leaves that share a
resource, a sub-flow with its own beginning and end, a family of states that
should all answer the same event the same way. A **compound state** is a state
that contains its own `:states` map — a little machine nested inside one state
of the bigger one.

You reach for hierarchy when:

- **Several leaves share a lifecycle or a resource.** A WebSocket's
  `:connecting → :authenticating → :connected` happy path all ride *one* live
  socket. They aren't independent states — they're three phases of being
  *connected*. Nest them under one `:active` parent and the socket spans all
  three.
- **You want to factor a transition to a parent.** Every authenticated screen
  should honour `:logout` the same way. Declare it *once* on the parent; every
  descendant inherits it (the [parent-fallthrough](#transition-resolution-deepest-wins-with-parent-fallthrough)
  rule below). No restating, no drift.
- **A sub-flow has a clear "done."** A checkout collects, submits, confirms —
  then the outer flow moves on. Mark the sub-flow's terminal leaf and the
  parent advances automatically (the [done signal](#when-a-sub-flow-finishes-nested-final-states) below).

The grammar recurses and stays additive: a substate is either a **leaf** (no
`:states`) or another **compound** (has `:states`, and must declare
`:initial`). Flat machines stay flat — you only pay for hierarchy where you use
it.

The canonical worked example is the connection machine in
[`../../examples/patterns/websocket/`](../../examples/patterns/websocket) — its
`:active` state parents the three happy-path leaves. The snippets on this page
are simplified from it. For the flat grammar this builds on, read
[Concepts](concepts.md) first.

---

## Anatomy of a compound state

Here is the shape, lifted and trimmed from the WebSocket connection machine.
The three happy-path leaves live *inside* `:active`, because they share the one
thing they can't do without: the live socket, spawned on the parent.

```clojure
(rf/reg-machine :ws/connection
  {:initial :disconnected
   :data    {:url nil :socket-id nil :queue []}

   :actions
   {:record-opts (fn [{data :data [_ {:keys [url]}] :event}]
                   {:data (assoc data :url url)})
    :send-now    (fn [{data :data [_ msg] :event}]
                   {:fx [[:dispatch [(:socket-id data) [:send msg]]]]})
    :enqueue     (fn [{data :data [_ msg] :event}]
                   {:data (update data :queue conj msg)})}

   :states
   {:disconnected
    {:on {:ws/connect {:target :active :action :record-opts}}}

    :active                                   ;; ← compound: the socket's home
    {:initial :connecting                     ;; required — every compound declares it

     ;; One socket actor, spawned on the PARENT, so it spans all three leaves
     ;; below without re-spawning on each leaf transition. (See Concepts > :spawn.)
     :spawn {:machine-id :websocket/socket}

     ;; Transitions every leaf inherits. A leaf may override any of them; what
     ;; a leaf doesn't handle falls through to here.
     :on    {:ws/closed     {:target :reconnecting}
             :ws/send       {:action :enqueue}    ;; default: queue while not yet connected
             :ws/disconnect {:target :disconnected}}

     :states
     {:connecting     {:tags #{:ws/connecting}
                       :on   {:ws/opened {:target :authenticating}}}

      :authenticating {:tags  #{:ws/authenticating}
                       :on    {:ws/auth-ok     {:target :connected}
                               :ws/auth-failed {:target [:failed]}}}  ;; ← vector! (root-level)

      :connected      {:tags #{:ws/connected}
                       :on   {:ws/send {:action :send-now}}}}}        ;; ← overrides parent :ws/send

    :reconnecting {:on {:ws/connect {:target :active}}}
    :failed       {:on {:ws/connect {:target :active}}}}})
```

Three things to notice, each expanded below:

1. `:active` declares `:initial :connecting` — entering `:active` lands you in
   `:connecting`, never in `:active` "bare."
2. `:ws/auth-failed` targets `[:failed]` (a **vector**), because `:failed` lives
   at the root, not as a sibling inside `:active`.
3. `:connected` redeclares `:ws/send` — it **overrides** the parent's default
   for that one event while it's the active leaf.

---

## The snapshot becomes a path

A flat machine's snapshot `:state` is a single keyword (`:disconnected`). For a
**compound** machine, `:state` is a **vector path** from the root down to the
active leaf:

```clojure
@(rf/sub-machine :ws/connection)
;; flat-ish state:   {:state :disconnected         :data {…} :tags #{}}
;; compound state:   {:state [:active :connected]  :data {…} :tags #{:ws/connected}}
```

`:data` is the machine's extended state — one shared map, not per-state. `:tags`
is the **union** of the `:tags` sets on every active node along the path. A
single keyword `:K` read against a hierarchical definition is treated as the
path `[:K]` (it must name a leaf at the root).

The practical upshot: **views ask about tags, not paths.** Don't unfold the
`:state` vector in a view to discover "are we connected?" — ask the tag:

```clojure
(when @(rf/machine-has-tag? :ws/connection :ws/connected)
  [send-box])
```

The view doesn't care *which* leaf carries the `:ws/connected` intent, only that
the tag is present — so you can re-shape the hierarchy later without touching
the view. (See the [snapshot](glossary.md#snapshot) and
[state tag](glossary.md#state-tag) glossary entries; the snapshot lives in the
framework's [runtime-db](../core/glossary.md#runtime-db), read like any other
[subscription](../core/concepts/subscriptions.md).)

---

## Initial-state cascading

**Every compound state MUST declare `:initial`** — the substate to enter when
control reaches the compound without a deeper target. Entering a compound
*cascades down its `:initial` chain* until it reaches a leaf:

```clojure
{:initial :outer
 :states  {:outer {:entry   :enter-outer        ;; fires first
                   :initial :mid
                   :states  {:mid  {:entry   :enter-mid     ;; then this
                                    :initial :leaf
                                    :states  {:leaf {:entry :enter-leaf}}}}}}}
;; Targeting :outer lands the snapshot at [:outer :mid :leaf].
;; Entry actions fire shallowest-first: :enter-outer, :enter-mid, :enter-leaf.
```

So in the WebSocket machine, `{:target :active}` resolves to `[:active :connecting]`,
and the cascade fires `:active`'s entry slots (including its `:spawn`) and then
`:connecting`'s — shallowest-first — as the path lengthens.

A compound state *without* `:initial` is a registration error
(`:rf.error/machine-compound-state-missing-initial`) — it fails loud at
`reg-machine` time, not on the unlucky dispatch.

!!! note "The initial cascade also runs at birth"

    When a machine first comes to
    life — a singleton on its first dispatched event, or a spawned actor — the
    whole `:initial` chain's `:entry` actions fire once, shallowest-first, as part
    of bringing it into existence — *every* level along the chain, not just the
    leaf.

---

## Targets: vector vs keyword

A transition's `:target` admits two forms, and the difference is *where the
target is resolved from*:

| Form | Means | Example |
|---|---|---|
| **Vector** `[:a :b]` | **Absolute path from the root**, no matter where the transition is declared. Unambiguous; the recommended form for cross-level jumps. | `:ws/auth-failed {:target [:failed]}` |
| **Keyword** `:b` | **Relative — a sibling** of the state that *declares* the transition (resolved against the declaring state's parent's `:states`). | `:ws/opened {:target :authenticating}` |

The "declaring state" is the node that **owns the `:on` map** — not whatever
leaf the machine happens to sit in at runtime. It's a static rule you can read
straight off the transition table.

This is exactly the WebSocket gotcha. From inside `:authenticating` (a child of
`:active`), a bare `:auth-failed {:target :failed}` would resolve to the
*sibling* `[:active :failed]` — which doesn't exist. The absolute vector
`[:failed]` says "from the root, please":

```clojure
:authenticating
{:on {:ws/auth-ok     {:target :connected}       ;; keyword: sibling inside :active → [:active :connected]
      :ws/auth-failed {:target [:failed]}}}       ;; vector: root-level [:failed]
```

A target naming a **compound** state implicitly cascades through its `:initial`
chain (above). To land on a *specific* leaf inside a compound, name it with a
vector: `:target [:active :connected]`.

Flat-machine targets you've already written (`{:target :editing}`) are keyword
form, root-relative — unchanged, because in a flat machine the declaring state's
parent *is* the root.

---

## Transition resolution: deepest-wins with parent fallthrough

When an event arrives, the runtime walks the active path **from the leaf up to
the root**, and the first node that *enables* a transition for the event wins.
Within each node it tries three descriptor tiers in priority order before moving
up: the **exact** key, then the namespace wildcard `:ns/*`, then the total
wildcard `:*`. A guard-blocked candidate is **not** enabled, so it doesn't end
the search — resolution keeps falling through.

Two consequences make hierarchy pay off:

- **A child overrides a parent.** `:connected` redeclares `:ws/send` to send
  immediately; while connected, that leaf wins and the parent's "enqueue"
  default never runs. Same event, different answer depending on where you are.
- **A parent factors a common transition.** `:active` declares `:ws/closed`,
  `:ws/disconnect`, and the `:ws/send` default *once*; every leaf that doesn't
  handle them inherits them. Add a fourth leaf and it inherits them too, for
  free.

```clojure
;; While the snapshot is [:active :connected], dispatching :ws/disconnect:
;;   :connected   — no :ws/disconnect  → keep walking up
;;   :active       — match!             → {:target :disconnected}
;; Dispatching :ws/send instead resolves at :connected (override → :send-now).
```

### Opting a child OUT of an inherited transition

Sometimes a child needs the *inverse* of inheritance — to **block** a
factored-to-parent transition while it's active, without replacing it. Because
the walk stops at the first *enabled* match, a child can declare a matching
**internal no-op** that consumes the event so the parent is never reached:

```clojure
:modal {:on {:logout {}            ;; FORBIDDEN: matching internal no-op, halts the walk
             ;; equivalently:  :logout nil
             :close  :dashboard}}
```

While the machine rests in `:modal`, `:logout` resolves *at* `:modal` to a no-op
and the parent's `:logout` is **not** inherited. Leave `:modal` and it's
inherited again. Both spellings — the empty map `{}` and a present key with
`nil` value — normalise to the same blocking no-op.

The block turns entirely on the key being **present**. A child with *no*
`:logout` entry keeps falling through (absence means "I don't handle this");
a deliberately-`nil` key blocks (presence means "I consume this here").

### Unhandled events are a benign no-op

If *no* level enables a transition for an unknown event, the snapshot is
unchanged and the runtime emits a benign `:rf.machine.event/unhandled-no-op`
trace. Nothing throws — but benign is not invisible: the trace lets a debugger
report that an event arrived and was ignored. To "fail loud on unknown," declare
a `:*` wildcard whose action throws.

---

## Entry/exit cascading along the LCA

Moving from one path to another isn't a single hop — it fires a *cascade* of
`:exit` and `:entry` actions for every state you actually leave and enter. The
runtime computes the **LCA** (least common compound ancestor — the deepest
compound state that is a proper ancestor of both the source leaf and the target
node), then fires three boundaries in order:

1. **Exit cascade** — walk the source path from the leaf back toward the LCA,
   firing each state's `:exit` action, **deepest-first**. Stop *below* the LCA
   (you aren't leaving it).
2. **Transition `:action`** — runs once, at the LCA boundary, between exit and
   entry.
3. **Entry cascade** — walk the target path from just below the LCA down to the
   target node, firing each `:entry` action, **shallowest-first**. If the target
   is itself compound, keep cascading through its `:initial` chain.

For the common case — source and target in disjoint subtrees — the LCA is simply
the longest common prefix of the two paths.

Take this auth-flow machine (a compound `:authenticated` over a `:dashboard` /
`:settings` / `:cart` sub-tree, with `:cart` itself compound):

```clojure
(rf/reg-machine :shop
  {:initial :unauthenticated
   :states
   {:unauthenticated
    {:on {:login [:authenticated]}}              ;; vector target — absolute from root

    :authenticated
    {:initial :dashboard
     :on      {:logout [:unauthenticated]}       ;; factored to the parent — every descendant inherits
     :states
     {:dashboard {:on {:open-settings :settings  ;; keyword target — sibling of :dashboard
                       :open-cart     :cart}}
      :settings  {:on {:close :dashboard}}
      :cart      {:initial :browsing
                  :on      {:close :dashboard}
                  :states  {:browsing  {:on {:checkout :paying}}
                            :paying    {:on {:success :confirmed
                                             :failure :browsing}}
                            :confirmed {}}}}}}})
```

| Event | Source path | Target path | What fires |
|---|---|---|---|
| `:login` | `[:unauthenticated]` | `[:authenticated :dashboard]` | LCA is the root: exit `:unauthenticated`. Target `[:authenticated]` cascades `:initial :dashboard`, so entry is `:authenticated`, then `:dashboard`. |
| `:open-cart` | `[:authenticated :dashboard]` | `[:authenticated :cart :browsing]` | Keyword `:cart` = sibling of `:dashboard`; cascades `:initial :browsing`. LCA is `:authenticated`: exit `:dashboard`; enter `:cart`, `:browsing`. |
| `:checkout` | `[:authenticated :cart :browsing]` | `[:authenticated :cart :paying]` | Sibling hop inside `:cart`. LCA `:cart`: exit `:browsing`; enter `:paying`. |
| `:logout` | `[:authenticated :cart :paying]` | `[:unauthenticated]` | Deepest-wins walks `:paying` (no match), `:cart` (no), `:authenticated` (match). LCA is the root: exit `:paying → :cart → :authenticated` (deepest-first); enter `:unauthenticated`. |

> This generalises the flat `exit → action → entry` rule: in a flat machine the
> path length is 1 and the LCA is always the root.

Actions are **pure returns**, not imperative writes: an `:entry` /
`:exit` / transition `:action` is `(fn [{:keys [data event state]}] effects)`
returning `{:data … :fx …}`. The `:fx` flow through the ordinary
[event pipeline](../core/concepts/events-and-the-pipeline.md) like any handler's.

---

## When a sub-flow finishes: nested final states

A compound state often *is* a sub-flow with a clear end: collect, submit, paid.
re-frame2 ships the statechart pattern for "the sub-flow finished, now advance
the outer flow" first-class — and **the machine keeps running**.

Mark the sub-flow's terminal leaf `:final? true`. The moment a compound's active
child becomes that final leaf, the engine raises a synthetic, transitionable
event `[:rf.machine/done <compound-path>]`, and the compound's **`:on-done`**
takes it:

```clojure
(rf/reg-machine :checkout
  {:initial :flow
   :states
   {:flow {:initial :collecting
           :on-done :next                        ;; ← fires when :flow reaches its :final? child
           :states  {:collecting {:on {:submit :submitting}}
                     :submitting {:on {:ok :paid}}
                     :paid       {:final? true}}} ;; ← embedded final = "sub-flow done"
    :next {:on {:reset [:flow]}}}})
```

When `:flow` reaches `[:flow :paid]`, `:on-done :next` advances the machine to
the sibling `:next` — **in the same macrostep**, no teardown. `:on-done` on a
compound is an ordinary `:on`-shaped transition spec (a keyword sibling target,
a vector path, or a full `{:target :guard :action}` / candidate vector), and a
keyword target resolves as a **sibling of the compound** — the natural "advance
the outer flow" placement.

> The node id rides as the raised event's single argument so the `:on` table
> stays keyed on one reserved keyword. The raise lands in the same internal FIFO
> queue an action's `[:raise …]` uses, drained before the macrostep settles.

There's also a lower-level escape hatch: if the done node declares no `:on-done`,
the raised `[:rf.machine/done <path>]` walks the active path leaf→root like any
event, so an **ancestor** can catch it with an explicit
`:on {:rf.machine/done {:guard … :target …}}` — a guard reads the raised path off
`:event` to disambiguate *which* node is done.

### Depth decides the meaning — embedded vs whole-machine final

The same `:final?` key means two different things depending on **how deep the
leaf sits.** This is the one subtlety worth internalising:

| `:final?` leaf placement | Meaning | Effect |
|---|---|---|
| **Embedded inside a compound** (path length ≥ 2) | the *compound* is done | raises `[:rf.machine/done <compound>]`; the enclosing `:on-done` advances; **the machine keeps running** |
| **Direct child of the root** (path length 1) | the *whole machine* is done | **auto-destroys** (a singleton tears itself down; a spawned child notifies its parent — below) |

So you do **not** have to *avoid* `:final?` to keep a machine alive after a
sub-flow completes — you put the final leaf *inside* the compound. A final leaf
at the root, by contrast, ends the actor.

!!! note "Final means final"

    A *singleton* (top-level, un-spawned) machine that
    reaches a **root-level** `:final?` leaf auto-destroys — the snapshot is gone.
    If you want a state the machine rests in indefinitely (an `:authed`
    end-screen), use an ordinary leaf and **omit `:final?`**. `:final?` is for
    "this run is over," not "this is the last screen."

### Reporting a result to a spawning parent: `:output-key`

The *whole-machine* final case is how a **spawned child** reports back: the
child marks its terminal leaf `:final?` and names the `:data` slot to hand up
with `:output-key`; the parent's **`:spawn :on-done`** callback receives it as
`result`, and the runtime then tears the child down. That protocol — including
the `:on-error` failure path — is worked in
[Actors → When a child finishes](actors.md#when-a-child-finishes).

Two distinct `:on-done` hooks, then, named the same on purpose because they're
the same idea at two scopes:

- **`:on-done` on a compound node** — the *transitionable in-machine* signal
  (`done.state.<compound>`). Advances the outer flow; machine survives.
- **`:on-done` on a parent's `:spawn` map** — the *actor-teardown notification*
  `(fn [{:keys [data result]}] new-data)` a parent gets when a spawned child
  reaches a root-level `:final?` and is then destroyed.

### `:final?` constraints

A `:final?` state fails loud at registration if you break these:

- **Leaf-only.** No `:states` / `:initial` on a final state — a compound can't
  be final; its finality is a leaf *inside* it. (`:rf.error/machine-final-state-compound`)
- **No further transitions.** No `:on` / `:always` / `:after` / `:spawn` /
  `:spawn-all` on a final state — final means final. (`:rf.error/machine-final-state-has-transitions`)
  `:entry` and `:exit` *are* allowed (entry runs in the cascade; exit runs from
  teardown).
- **`:output-key` requires `:final?`.** A non-final state declaring
  `:output-key` is an error (`:rf.error/machine-output-key-without-final`). On a
  final leaf it's optional — absent, the parent's `result` is `nil`.

(Inside a parallel-region machine, a `:final?` leaf means "*this region* is
done"; the machine as a whole is done only when *every* region is final.
Parallel regions are a separate axis — they're for orthogonal concerns that
*don't* share a sub-tree — covered in [Parallel states](parallel-states.md).)

---
