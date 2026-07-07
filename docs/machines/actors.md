# Actors: spawning child machines

A machine doesn't have to be a singleton. When a state needs to run some self-contained, asynchronous activity — an HTTP request, a websocket session, a worker grinding through a shard — you can **spawn a child machine** to do it, scoped to that state's lifetime. The child is a full machine instance with its own `:state` and `:data`; we call it an **actor**.

The pattern earns its keep when an activity has a *lifetime that should match a state's lifetime*: start it when you enter the state, and — this is the part that's easy to get wrong by hand — tear it down on **every** way out of the state, including the ones you forgot about. Declare the binding once and the runtime enforces it.

A spawned child can be **state-bound** — alive for exactly as long as a state is active — or **dynamically created**; re-frame2 spells both as **`:spawn`**. There is **no actor object**: a spawned actor's *liveness is its snapshot* — a plain value at `[:rf.runtime/machines :snapshots <actor-id>]` in the [frame's runtime-db](glossary.md#snapshot). You address it by `dispatch`-ing to its id, exactly like any other handler, and it reverts with the frame on time-travel.

This page assumes you've met the transition table and [guards and actions](concepts.md#guards-and-actions) from [Concepts](concepts.md), plus [`:after` timers](automatic-transitions.md) — they carry the timeout story below.

---

## Your first spawned actor — declarative `:spawn`

Put a `:spawn` map on a state node. While the machine sits in that state, a child actor of the named machine exists; on any transition out of the state, it's destroyed.

Here's the heart of the [websocket example](../../examples/patterns/websocket/): a connection machine whose `:active` state owns a live socket. The socket is its own machine (`:websocket/socket`), spawned on the `:active` *parent* so one socket spans the `:connecting` → `:authenticating` → `:connected` leaves nested inside it.

```clojure
(rf/reg-machine :ws/connection
  {:initial :disconnected
   :data    {:url nil :auth-token nil}
   :states
   {:disconnected
    {:on {:ws/connect {:target :active :action :record-opts}}}

    :active
    {:spawn {:machine-id :websocket/socket
             ;; :data can be a fn — evaluated on entry against the
             ;; post-action snapshot, so it reads whatever's current.
             :data       (fn [{snap :snapshot}]
                           {:url        (-> snap :data :url)
                            :auth-token (-> snap :data :auth-token)})}
     :on    {:ws/closed {:target :reconnecting}
             :ws/fatal  {:target :failed}}
     ;; ... compound :initial / nested leaves omitted ...
     }}})
```

Enter `:active` → the runtime spawns a `:websocket/socket` actor. Leave `:active` by *any* door — `:ws/closed`, `:ws/fatal`, a frame teardown — and the runtime destroys it. The parent never wrote "spawn" or "destroy"; `:spawn` is **registration-time sugar** that rewrites the state's `:entry`/`:exit` into the imperative spawn/destroy effects you'll meet below. From the outside it's indistinguishable from a machine that wired those slots by hand.

### The spawn-spec keys

The map under `:spawn` accepts:

| key | what it does |
|---|---|
| `:machine-id` **or** `:definition` | which machine to spawn — a registered machine id, or an inline transition table. Supply **exactly one** (both, or neither, is a registration error). |
| `:data` | initial `:data` for the child — a literal map, or `(fn [{:keys [snapshot event]}] data)` evaluated at entry against the **post-action** snapshot (the transition's own `:action` has already run, so its writes are visible). |
| `:id-prefix` | base for the gensym'd actor id (`:websocket/socket#0`); defaults to `:machine-id`. |
| `:start` | an event vector dispatched to the newborn as its first event (see [the synthetic kick-off](#the-synthetic-spawned-event-and-start) below). |
| `:system-id` | bind the actor to a per-frame **name** so other code can address it without knowing the gensym'd id (see [Messaging](#cross-machine-messaging)). |
| `:on-spawn` | `(fn [{:keys [data id]}] _)` — an **advisory** observation hook; **its return is dropped** (see [Recording the id](#recording-the-spawned-id)). |
| `:on-done` | `(fn [{:keys [data result]}] new-data)` — success notification when the child reaches a non-error `:final?` leaf. |
| `:on-error` | an `:on`-shaped **transition** — fires when the child fails (an `:error?` final leaf, or a thrown action). The parent changes state. |
| `:fixed-actor-id` | an explicit actor id instead of a gensym, for a per-state singleton actor. |

`:on-done` / `:on-error` are the completion story — they pair with the child declaring a `:final?` leaf, and they get [their own section below](#when-a-child-finishes). The [API reference](../api/re-frame.machines.md) lists the exact shapes.

!!! note "One `:spawn` per state"

    A state node carries at most one `:spawn` — for multiple children, use a compound state with one actor per substate, or [`:spawn-all`](#fan-out-and-join-with-spawn-all) for parallelism. Events aren't auto-forwarded to children — forward them explicitly with `:fx [[:dispatch [child-id ev]]]`. To observe a child's snapshot, read it with the `[:rf/machine actor-id]` subscription.

---

## Runtime stamps: how a child knows who it is

When the runtime builds a declaratively-spawned child's initial snapshot, it stamps three framework-reserved keys into the child's `:data` — so the child can address itself and its parent without the parent having to thread that information through:

| key | value |
|---|---|
| `:rf/self-id` | the actor's own address (e.g. `:websocket/socket#0`) |
| `:rf/parent-id` | the parent machine's registration id |
| `:rf/invoke-id` | the absolute path of the `:spawn`-bearing state node |

The child reads these as ordinary `:data` lookups inside its actions. Here's the socket actor reporting back to its parent on open (simplified from the [websocket example](../../examples/patterns/websocket/)):

```clojure
(rf/reg-machine :websocket/socket
  {:initial :opening
   :data    {:url nil :auth-token nil}
   :actions
   {:open-socket
    (fn [{data :data}]
      (let [self-id   (:rf/self-id data)       ;; my own address
            parent-id (:rf/parent-id data)      ;; who spawned me
            socket    (open-host-socket! self-id (:url data))]
        (store-socket! self-id socket)
        ;; Report up to the parent, tagging the message with my socket id.
        {:fx [[:dispatch [parent-id [:ws/opened {:source-socket-id self-id}]]]]}))}
   :states
   {:opening {:entry :open-socket
              :always [{:target :open}]}
    :open    {:on {:send {:action :send-via-socket}}}}})
```

!!! note "Addressing the parent"

    A child can address its parent without being told who it is — the runtime stamps `:rf/parent-id` and the child reads it. **Caveat:** the stamp is written **only on the declarative `:spawn` / `:spawn-all` path**. A *hand-emitted* `[:rf.machine/spawn …]` (next section) records only `:rf/self-id` — there's no structural parent — so a hand-spawned child must be handed a correspondent address through its `:data`.

---

## The synthetic spawned event, and `:start`

A freshly-spawned actor needs a first nudge. Two ways:

- **No `:start` (the common case).** The runtime dispatches a synthetic `[:rf.machine.spawn/spawned]` into the newborn. The child's initial-state `:entry` cascade fires first regardless, so the **canonical** shape is to do the actor's first work in `:entry` (as `:open-socket` does above). A generic child can also listen for the kick-off explicitly:

  ```clojure
  ;; A child springing into action the instant it's spawned (no :start needed).
  ;; The runtime conjures [:rf.machine.spawn/spawned] when there's nothing to :start.
  :idle {:on {:rf.machine.spawn/spawned :processing}}
  ```

  Both work; new code prefers `:entry` (it fires for every kick-off mode). An unhandled `:rf.machine.spawn/spawned` is a benign no-op — it's reserved framework lifecycle traffic, exempt from the [unhandled-event trace](concepts.md#one-thing-that-wont-throw-the-unhandled-event).

- **With `:start [:begin url]`.** Hand the child a specific first event payload. The two paths are mutually exclusive — an actor gets the synthetic kick-off *or* your `:start`, never both.

---

## Recording the spawned id

You will reach for `:on-spawn` to "capture the child's id into `:data`" — and it won't work. `:on-spawn` is an **advisory observation hook**: the runtime calls it with `{:data <parent-data> :id <new-id>}` and **drops its return value**. Writing `(assoc data :pending id)` records nothing (and emits a `:rf.warning/on-spawn-return-ignored` in dev). The runtime already tracks the id for you — so there's no self-dispatch dance to write, and no side-channel atom either.

There are **two** first-class mechanisms; reach for whichever fits.

### 1. The `:rf/spawned` `:data` slot (read the id in-snapshot)

On every declarative `:spawn` / `:spawn-all`, the pure transition reducer binds the new actor's id into the *spawning* machine's own `:data` under the reserved per-invoke map `:rf/spawned` — `{:rf/spawned {<invoke-id> <actor-id>}}`, keyed by the absolute path of the `:spawn`-bearing state. A later action reads it straight off its own `:data`:

```clojure
:active
{:spawn {:machine-id :websocket/socket
         :data       (fn [{snap :snapshot}] {:url (-> snap :data :url)})}
 ;; ... nested leaves; a leaf action reads the spawned socket's id:
 :states
 {:authenticating
  {:entry (fn [{data :data}]
            ;; the socket actor's id — no :on-spawn, no atom
            (let [socket (get-in data [:rf/spawned [:active]])]
              {:fx [[:dispatch [socket [:send {:type :auth}]]]]}))}}}
```

This is re-frame2's spelling of XState v5's `spawn(...)`-into-`context` capture, except the id rides the **revertible, SSR-survivable snapshot** rather than a live object reference. It's keyed by `<invoke-id>` (the same path the child records under `:rf/invoke-id`), so multiple `:spawn`-bearing states don't collide.

**The slot clears itself on teardown.** When the actor is destroyed — leaving the state by any door, a completing `:final?`, a frame teardown — the runtime dissocs `[:rf/spawned <invoke-id>]` from the parent's `:data`, so it mirrors the runtime registry exactly. A read after the child is gone returns `nil`, never a dead id. That's what makes the [websocket example](../../examples/patterns/websocket/) treat the slot as its *connection clock*: the live socket's id **is** the epoch, and it goes `nil` the instant the socket dies — no `:exit` action nulling anything.

### 2. `:system-id` (address the actor by a stable name)

Give the spawn a `:system-id` and message it by that *role name* instead of the gensym'd id — best when you want a stable correspondent rather than to hold the id yourself. See [Addressing by name with `:system-id`](#addressing-by-name-with-system-id) below.

!!! note "The runtime registry, for outside-the-machine reads"

    The id is *also* always at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` in the frame's runtime-db — the same value the `:rf/spawned` `:data` slot mirrors. Read it there when you're *outside* the machine's own action context (mechanism 1 needs the action's `:data`); inside an action, prefer the `:rf/spawned` slot.

---

## Cross-machine messaging

There is **one** mechanism: `dispatch` by id. A spawned actor's id is a handler address, so a parent messages a child — and a child messages its parent — the same way:

```clojure
;; parent → child, or child → parent: same primitive.
{:fx [[:dispatch [some-machine-id [:some-event payload]]]]}
```

### Addressing by name with `:system-id`

When you don't want to thread the gensym'd id around, name the actor at spawn with `:system-id`, then message it by that name. The canonical action-side surface is the reserved `:rf.machine/dispatch-to-system` fx (a machine action can't read app-db, so it can't look the id up itself):

```clojure
;; spawn under a role-name:
:spawn {:machine-id :request/protocol
        :system-id  :primary-request
        :data       {:url "/api/foo"}}

;; later, from any action's :fx — message it by name (no-op if unbound):
{:fx [[:rf.machine/dispatch-to-system [:primary-request [:cancel]]]]}
```

From a non-action call site you can resolve the id directly with `(re-frame.machines/machine-by-system-id :primary-request)` and `dispatch` to it. The binding lives in the frame's runtime-db, so it reverts with everything else.

### Including a reply address

There's no `sender`/`sendTo`-reply special form. Put the reply event *in the request* (the standard re-frame2 reply convention): address the request by name, but carry the reply id in the payload, so the reply lands in a specific actor rather than whoever currently owns the name.

---

## When a child finishes

A spawned child that genuinely *completes* — a one-shot protocol, a finished handshake — declares a **`:final?` leaf**. Entering it terminates the child: the runtime reads the child's result, notifies the parent, then destroys the actor. The child names its result with **`:output-key`** — the slot of its final `:data` to hand up — and the parent's `:spawn` declares **`:on-done`**, which receives that value as `result`:

```clojure
;; Child — a one-shot auth handshake that reports its token, then ends.
(rf/reg-machine :auth-flow
  {:initial :running
   :data    {}
   :states
   {:running {:on {:server-ok {:target :done
                               :action (fn [{data :data ev :event}]
                                         {:data (assoc data :token (second ev))})}}}
    :done    {:final?     true
              :output-key :token}}})       ;; report :data's :token back to the parent

;; Parent — :on-done folds the child's result into its own :data.
:authenticating
{:spawn {:machine-id :auth-flow
         :on-done    (fn [{data :data result :result}]   ;; result = the child's :token
                       (assoc data :token result))
         :on-error   :idle}                               ;; child failed → a transition
 :on    {:auth/cancelled :idle}}
```

When `:auth-flow` enters `:done`, the runtime reads its `:token`, hands it to the parent's `:on-done` as `result`, then tears the child down — no stale id left behind. The details worth internalising:

- **`:on-done` is a data-fold, not a transition.** It's `(fn [{:keys [data result]}] new-data)` — it returns the parent's next `:data`; the parent's state doesn't move.
- **`:on-error` IS a transition.** A child that fails — it reaches a `:final?` leaf flagged `:error? true`, or one of its actions throws — routes the parent through the `:on`-shaped `:on-error` spec. Failure is control flow, not just observability.
- **Completion is event-shaped.** The `:output-key` value flows to `:on-done` at the moment of completion, then the child is gone — there is no long-lived output slot to read later. Want a *computed* output? Write it into the final state's `:data` with a transition `:action`; there's no `:output-fn`.
- **Final means final — even for a singleton.** A root-level `:final?` leaf auto-destroys *any* machine, spawned or not. A state a machine rests in indefinitely (an `:authed` end-screen) is an ordinary leaf with `:final?` omitted.
- **Nested finals are different.** A `:final?` leaf *inside a compound state* doesn't end the machine — it signals "this sub-flow is done" to the enclosing compound's own `:on-done`, and the machine keeps running. That's the hierarchical pattern; see [Hierarchical states → When a sub-flow finishes](hierarchical-states.md#when-a-sub-flow-finishes-nested-final-states).

---

## Imperative spawn and destroy

`:spawn` desugars to two reserved effects you can also emit by hand from any `:fx` vector. They are **fx-ids inside `:fx`**, not top-level effect keys:

```clojure
;; Spawn from an event handler (e.g. a boot-time bootstrap event):
(rf/reg-event :session/start-logger
  (fn [_ _]
    {:fx [[:rf.machine/spawn {:machine-id :machines/log-shipper
                              :id-prefix  :logger
                              :system-id  :logger        ;; address it later by name
                              :data       {:buffer []}
                              :start      [:logger/connect]}]]}))

;; Tear it down later:
(rf/reg-event :session/stop-logger
  (fn [_ _]
    {:fx [[:rf.machine/dispatch-to-system [:logger [:logger/flush]]]
          [:rf.machine/destroy (re-frame.machines/machine-by-system-id :logger)]]}))
```

`[:rf.machine/spawn <spec>]` and `[:rf.machine/destroy <actor-id>]` are the surface; the [API reference](../api/re-frame.machines.md) documents them in full. Two properties worth internalising:

- **Spawning an unregistered `:machine-id` fails closed.** No snapshot, no id, no `:start` — the spawn rejects with `:rf.error/machine-spawn-unregistered-type`. There is no "spec-less spawn."
- **Destroy is silently idempotent.** Destroying an already-gone actor is a no-op — no error, no second trace. The actor has exactly one Active → Stopped transition.

For the rare case of spawning from outside a handler (true boot time), wrap the `:rf.machine/spawn` in a one-shot event and `dispatch-sync` it. Inside a machine, prefer declarative `:spawn`; inside an ordinary handler, the imperative fx is the canonical surface.

---

## Cooperative cancellation — teardown on every exit path

This is why `:spawn` is worth using instead of firing an HTTP request from a plain handler: **the child's death is guaranteed on every code path out of its state.** The runtime destroys a spawned actor on *any* of these triggers:

1. **Parent state exit** — any transition out of the `:spawn`-bearing state.
2. **The parent's `:after` firing** — a wall-clock timeout (next section) is just a state exit.
3. **`:spawn-all` join resolution** — surviving siblings are unconditionally torn down when the join resolves.
4. **Imperative `[:rf.machine/destroy <id>]`**.
5. **Frame destroy** — when the [frame is torn down](glossary.md#snapshot) by an explicit `destroy-frame!`, every surviving machine in it is destroyed. Note that **a frame is not destroyed by a view unmounting or by navigating away** — frames [survive unmount by design](../core/frames.md) (tearing one down is a deliberate `destroy-frame!`, not a cleanup effect). So this trigger fires when a component that *owns* a frame's whole lifetime destroys it (a modal with a throwaway world torn down on close), or when a per-request SSR frame is disposed at end-of-request — not on every route change. Cross-route teardown of a machine's resources rides its `:exit` cascade (triggers 1–4 above), which is why you don't wire abort calls into route-leave handlers even though the frame itself lives on.

### What auto-cancels — and what you wire by hand

The runtime knows how to release exactly **three** framework-managed resource kinds on destroy, by any trigger:

- **In-flight `:rf.http/managed` requests** the actor issued — aborted automatically. The reply path sees `{:kind :rf.http/aborted :reason :actor-destroyed}`. *This is the headline:* an HTTP request issued from inside a spawned actor is bound to that actor's lifetime; a request fired from a plain `reg-event` handler has no such peg and is **not** cancelled. If you want lifetime-bound HTTP, spawn a child that issues it.
- **Armed `:after` timers** the actor scheduled — cancelled, so a killed worker won't wake up later.
- **`:rf.resource/*` owner leases** the actor holds.

!!! note "Auto-cancel covers three resource kinds"

    re-frame2 auto-cancels exactly the three kinds above — the ones the framework manages, and so knows how to release. **Anything else** the child holds — a raw `setInterval`, a `js/WebSocket`, a Web Worker, a third-party SDK subscription — you release yourself, in the child's **`:exit` action**. It runs on every destroy cascade, so one line covers all five triggers:

```clojure
{:connected
 {:entry :open-socket
  :exit  :close-socket          ;; runs on EVERY exit, including parent-destroy
  :on    {:disconnect :idle}}}
```

The destroy path runs the actor's `:exit` *before* dissociating its snapshot, so cleanup gets to read the final `:data`. There is no `core.async` in this path — close the host handle directly, don't reach for a channel close. (The websocket example keeps its mock socket in a host-side atom and leans on actor-destroy plus a mock that holds no real OS resource; a real `js/WebSocket` would `.close()` in `:exit`.)

The [long-running-work example](../../examples/patterns/long_running_work/) shows the cascade end-to-end: the user hitting **Cancel** dispatches `:cancel`, which leaves the `:working` state, and *that exit* fires a `:rf.machine/destroy` for every still-running child — pending `:after` yield-timers and all. The author wrote no per-child teardown.

---

## Wall-clock timeouts — use the parent state's `:after`

"The whole activity must finish within N seconds, spanning any internal retries." There is **no `:timeout-ms` slot** on `:spawn` (it's rejected at registration). The answer is the [`:after`](automatic-transitions.md) primitive on the `:spawn`-bearing state — one timer mechanism, not two:

```clojure
{:authenticating
 {:spawn {:machine-id :login-request                  ;; a child MACHINE that issues the request and retries internally
          :data       {:request {:method :post :url "/api/login" :body credentials}}}
  :after {30000 :auth-failed}      ;; wall-clock guard — spans the child's retries
  :on    {:succeeded      :authenticated
          :user/cancelled :idle}}}
```

The timer is anchored to `:authenticating`'s **entry**, so it bounds the child's whole lifetime no matter how many times the child retries internally. When it fires, the state exits and the standard cancellation cascade destroys the in-flight child (aborting its HTTP). Three independent triggers — the user cancels, 30s elapses, or the frame is torn down — all route through the *same* teardown.

> The same guard has a named-intent spelling, `:timeout "PT30S"` + `:on-timeout {:target :auth-failed}` (ISO-8601 durations), which **lowers onto the same `:after` timer**. Use whichever reads better; they're one mechanism. (Durations are ISO-8601 or integer milliseconds; a bare `"5s"` shorthand isn't accepted.)

---

## Fan-out and join with `:spawn-all`

When a state needs to spawn **N children in parallel** and resume on a join condition — boot hydration, parallel asset loads, a swarm of workers — use `:spawn-all`. The [long-running-work example](../../examples/patterns/long_running_work/) is the canonical case: one parent coordinator spawns a worker per shard and joins when all are done.

```clojure
(rf/reg-machine :work/flow
  {:initial :idle
   :data    {:progress {}}
   :states
   {:idle
    {:on {:start {:target :working :action :reset-progress}}}

    :working
    {:spawn-all
     {:children        [{:id :s1 :machine-id :work/processor :data {:shard :s1 :total 100}}
                        {:id :s2 :machine-id :work/processor :data {:shard :s2 :total 100}}
                        {:id :s3 :machine-id :work/processor :data {:shard :s3 :total 100}}]
      :join            :all                   ;; :all (default) or :any
      :on-child-done   :work/child-done       ;; keyword each child dispatches on success
      :on-child-error  :work/child-error      ;; ...and on failure
      :on-all-complete [:work/all-done]        ;; parent event when :all resolves
      :on-any-failed   [:work/any-failed]}     ;; parent event when a child fails
     :on {;; A child checking in — no :target makes it an internal self-transition,
          ;; so the children aren't re-spawned and aren't torn down.
          :progress        {:action :record-progress}
          ;; The join landing back in the parent as an ordinary event:
          :work/all-done   {:target :complete  :action :stamp-outcome}
          :work/any-failed {:target :error     :action :stamp-outcome}
          ;; The user pulling the plug — leaving :working destroys every child:
          :cancel          {:target :cancelled :action :stamp-outcome}}}

    :complete  {:on {:reset {:target :idle}}}
    :cancelled {:on {:reset {:target :idle}}}
    :error     {:on {:reset {:target :idle}}}}})
```

**The child completion protocol.** Each child is an ordinary machine. When it finishes, its terminal state dispatches the parent's `:on-child-done` keyword, carrying its own `:id`:

```clojure
;; In the child (:work/processor), at its terminal state:
:done {:entry (fn [{data :data}]
                {:fx [[:dispatch [:work/flow [:work/child-done (:shard data)]]]]})}
```

The runtime intercepts these at the parent's boundary, updates join state, and once the condition resolves, fires the parent event (`:on-all-complete` here) **and** unconditionally cancels any siblings still in flight. The bookkeeping — who's done, who failed, the resolution latch — is **runtime-owned** at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`; the parent keeps none of it in `:data`.

A few rules worth knowing:

- **Each child needs a unique `:id`** (the join key) on top of the usual spawn keys. Duplicate ids are a registration error.
- **Join discriminators:** `:all` (default) or `:any`. `:on-all-complete` is required for `:all`; `:on-some-complete` is required for `:any`. A quorum ("N of M") join uses the data-only `:after` + `:done-guard` idiom, not a `:join` mode ([Spec 005 §Join semantics](../../spec/005-StateMachines.md#join-semantics)).
- **Sibling cancellation on join resolution is unconditional** — when the join resolves, surviving siblings are torn down. A late result from an already-decided join fires no further parent event. A fan-out where each child is independently valuable is modelled as N independent single-`:spawn`s (fire-and-forget), not a non-cancelling join.
- **An unregistered child type fails the whole join closed** before any join-state is seeded — a child that can never run can never deadlock an `:all` join.
- **Wall-clock timeout:** same as single `:spawn` — an `:after` on the `:spawn-all`-bearing state. When it fires, the exit cascade cancels every surviving child.

`:spawn-all` packages the fan-out, the join condition, and the cancel-on-resolution cascade into one declaration.

---
