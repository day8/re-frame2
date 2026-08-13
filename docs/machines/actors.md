# 8. Actors

<a id="actors"></a>
<a id="actors-spawning-child-machines"></a>

Every machine so far has been a **singleton**: one `reg-machine` id, one live
instance. `[:rf/machine :auth.login/flow]` is that instance, or `nil` before
the first event.

A **spawned** actor is another live instance of a machine **type**, created
at run time. It gets an allocated id (`:auth/request#0`). Use one when you
need many concurrent instances, or a child whose lifetime is bound to a
parent state.

The spec heading says "dynamic actors." That is an adjective for spawned
instances, not a third kind. This guide says **singleton** and **spawned**.

Login stays the singleton. The HTTP request becomes a spawned child: it
starts when `:submitting` is entered and is destroyed on every exit.

Assumes the [flat table](concepts.md). Child completion often uses
[final states](concepts.md#final-states).

## State-bound spawn

Put `:spawn` on a state node. Entering the state creates the child. Leaving the
state — by any transition — destroys it.

The singleton login machine spawns a request actor on `:submitting`:

```clojure
(rf/reg-machine :auth/request
  {:initial :running
   :data    {}
   :states
   {:running
    {:entry :issue-request
     :on    {:server-ok {:target :done
                         :action :keep-token}
             :server-err :failed}}
    :done   {:final? true :output-key :token}
    :failed {:final? true :error? true}}})

:submitting
{:tags  #{:auth/busy}
 :spawn {:machine-id :auth/request
         :data       (fn [{:keys [event]}]
                       {:credentials (second event)})
         :on-done    (fn [{:keys [data result]}]
                       (assoc data :token result))
         :on-error   {:target :error-shown}}
 :on    {:auth.login/cancel :idle}}
```

`:auth.login/flow` is still the singleton. `:auth/request` is the **type**.
Each visit to `:submitting` allocates a new spawned id. Leaving
`:submitting` — success, error, cancel, timeout — destroys that actor.

A larger shipped case binds one socket actor to a parent that spans several
children:

```clojure
;; cf. examples/patterns/websocket
(rf/reg-machine :ws/connection
  {:initial :disconnected
   :data    {:url nil :auth-token nil}
   :states
   {:disconnected
    {:on {:ws/connect {:target :active
                       :action :record-options}}}

    :active
    {:spawn {:machine-id :websocket/socket
             :data       (fn [{snap :snapshot}]
                           {:url        (-> snap :data :url)
                            :auth-token (-> snap :data :auth-token)})}
     :on {:ws/closed :reconnecting
          :ws/fatal  :failed}
     :initial :connecting
     :states  {:connecting     {:on {:ws/opened :authenticating}}
               :authenticating {:on {:ws/auth-ok :connected}}
               :connected      {}}}}})
```

The socket is spawned on the `:active` *parent*, so one actor spans
`:connecting` → `:authenticating` → `:connected`. Cleanup is tied to the
statechart, not to hand-written cancel branches.

A state carries at most one `:spawn`. For several children, use a compound
state with one actor per substate, or [`:spawn-all`](#fan-out-and-join-with-spawn-all).
Events are not forwarded to children; dispatch to the child id yourself. To
read a child's snapshot:

```clojure
@(rf/subscribe [:rf/machine actor-id])
```

## Spawn spec keys

Supply `:machine-id` or `:definition`, not both.

| Key | Meaning |
|---|---|
| `:machine-id` | registered machine type to spawn |
| `:definition` | inline machine definition instead of a registered id |
| `:data` | child's initial data — a map, or `(fn [{:keys [snapshot event]}] …)` evaluated on entry against the **post-action** snapshot |
| `:id-prefix` | base for the allocated id (`:websocket/socket#0`); defaults to `:machine-id`. Ids are counters, never `gensym` |
| `:system-id` | stable role name for lookup and messaging |
| `:start` | first event sent to the newborn |
| `:on-spawn` | advisory hook; **its return is dropped** (see [Recording the spawned id](#recording-the-spawned-id)) |
| `:on-done` | data-fold when the child reaches a successful final state |
| `:on-error` | transition when the child reaches an error final state or fails |
| `:timeout` / `:on-timeout` | wall-clock deadline on this child's lifetime; lowers onto the state's `:after` |
| `:fixed-actor-id` | explicit actor id for a per-state singleton |

The [API reference](../api/re-frame.machines.md) lists the exact shapes.

## Child runtime stamps

A **declaratively** spawned child gets three reserved keys in its `:data`:

| Key | Meaning |
|---|---|
| `:rf/self-id` | this actor's live id |
| `:rf/parent-id` | the parent actor's id (the address you dispatch to) |
| `:rf/invoke-id` | the path of the state that spawned it (e.g. `[:active]`) |

```clojure
:actions
{:notify-open
 (fn [{data :data}]
   {:fx [[:dispatch [(:rf/parent-id data)
                     [:ws/opened {:source-socket-id (:rf/self-id data)}]]]]})}
```

A hand-emitted `[:rf.machine/spawn …]` stamps only `:rf/self-id`. There is no
structural parent, so pass a correspondent address through `:data` yourself.

## Starting the child

The child always runs its initial `:entry` cascade first. Prefer putting startup
work there.

If you omit `:start`, the runtime also dispatches a synthetic
`[:rf.machine.spawn/spawned]`. Most children can ignore it. If you set
`:start`, that event is sent **instead** — never both.

```clojure
:spawn {:machine-id :worker
        :start      [:worker/start {:shard :a}]}
```

## Messaging

There is one messaging primitive: `dispatch` to the actor id.

```clojure
{:fx [[:dispatch [child-id [:worker/cancel]]]]}
```

A parent gets the id from `:rf/spawned` or from a `:system-id`.
A child gets the parent from `:rf/parent-id`. There is no separate *send* verb.

### `:system-id`

Bind the actor to a role name when you want to address the role, not a generated
instance id.

```clojure
:spawn {:machine-id :request/protocol
        :system-id  :primary-request
        :data       {:url "/api/user"}}
```

From an action (which cannot read app-db):

```clojure
{:fx [[:rf.machine/dispatch-to-system
       [:primary-request [:request/cancel]]]]}
```

That fx is a no-op if the name is unbound. Outside an action, resolve the id
and dispatch to it:

```clojure
(when-let [actor (re-frame.machines/machine-by-system-id :primary-request)]
  (rf/dispatch [actor [:request/cancel]]))
```

## Recording the spawned id

`:on-spawn` is an observation hook. The runtime calls
`(fn [{:keys [data id]}] …)` and **drops the return**. Writing the id back
into `:data` records nothing, and a dev build emits
`:rf.warning/on-spawn-return-ignored`.

```clojure
;; Don't do this
:on-spawn (fn [{:keys [data id]}]
            (assoc data :child-id id))
```

On every declarative `:spawn` / `:spawn-all`, the runtime already writes the
new id into the **parent's** `:data` under `:rf/spawned`, keyed by the
`:spawn`-bearing state's path:

```clojure
;; cf. examples/patterns/websocket
:authenticating
{:entry (fn [{data :data}]
          (let [socket (get-in data [:rf/spawned [:active]])]
            {:fx [[:dispatch [socket [:send {:type :auth}]]]]}))}
```

The slot clears itself when the actor is destroyed. A later read returns `nil`,
not a dead id.

Use `:system-id` instead when you only need role-based messaging. The same id
is also at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` for reads
*outside* a machine action.

## When a child finishes

A one-shot child reports success by entering a **root-level** `:final?` leaf
and naming the `:data` slot to hand up with `:output-key`. The parent folds
that value in `:on-done`:

```clojure
(rf/reg-machine :auth/request
  {:initial :running
   :data    {}
   :states
   {:running {:on {:server-ok {:target :done
                               :action (fn [{data :data [_ token] :event}]
                                         {:data (assoc data :token token)})}}}
    :done    {:final?     true
              :output-key :token}}})

:authenticating
{:spawn {:machine-id :auth/request
         :on-done    (fn [{:keys [data result]}]
                       (assoc data :token result))
         :on-error   :idle}
 :on    {:cancel :idle}}
```

- **`:on-done` is a data-fold**, not a transition:
  `(fn [{:keys [data result]}] new-data)`. The parent's state does not move.
- **`:on-error` is a transition.** A child that fails — a `:final?` leaf
  flagged `:error? true`, or a thrown action — routes the parent through that
  `:on`-shaped spec.
- Entering a root-level `:final?` destroys the child after the parent is
  notified. A nested `:final?` only tells the compound "this sub-flow is
  done"; see [Hierarchical states](hierarchical-states.md#when-a-sub-flow-finishes-nested-final-states).

## Imperative spawn and destroy

Declarative `:spawn` lowers to reserved fx you can also emit from any `:fx`
vector:

```clojure
{:fx [[:rf.machine/spawn
       {:machine-id :logger
        :system-id  :logger
        :data       {:buffer []}
        :start      [:logger/connect]}]]}

{:fx [[:rf.machine/destroy actor-id]]}
```

Inside a machine state, prefer declarative `:spawn`. From an ordinary event
handler — when the number or timing of children is not one state node — emit
the fx.

An unregistered `:machine-id` (and no `:definition`) fails closed:
**no** snapshot, **no** id, **no** `:start`. The runtime raises
`:rf.error/machine-spawn-unregistered-type`.

Destroy is silently idempotent. Destroying an already-gone actor is a no-op.

## Cancellation

A spawned actor is destroyed when:

- the parent exits the spawn-bearing state;
- a timeout or `:after` transition exits that state;
- a `:spawn-all` join cancels surviving siblings;
- you emit `[:rf.machine/destroy actor-id]`;
- the **frame** is torn down with `destroy-frame!`. A view unmount or a
  route change does **not** destroy the frame — tear one down only when you
  mean to.

Destroy releases exactly three framework-managed kinds:

- in-flight `:rf.http/managed` requests this actor issued (the reply is
  `{:kind :rf.http/aborted :reason :actor-destroyed}`);
- this actor's armed `:after` timers;
- `:rf.resource/*` owners this actor holds.

Anything else — a raw `js/WebSocket`, a `setInterval`, a Worker — you close
yourself in the child's `:exit`. That action runs on every destroy path:

```clojure
:connected
{:entry :open-socket
 :exit  :close-socket
 :on    {:disconnect :idle}}
```

The [long-running-work example](../../examples/patterns/long_running_work/)
cancels by leaving `:working`: that exit destroys every surviving child,
pending `:after` yield-timers included.

## Timeouts

There is no `:timeout-ms` on `:spawn` or `:spawn-all`. Registration rejects
it with `:rf.error/spawn-timeout-ms-removed`.

`:timeout` / `:on-timeout` **on the spawn spec** is fine. It lowers onto the
spawn-bearing state's `:after`, so the deadline is anchored to that state's
entry and spans the child's internal retries:

```clojure
:authenticating
{:spawn {:machine-id :auth/request
         :timeout    "PT30S"
         :on-timeout {:target :auth-failed}}
 :on    {:cancel :idle
         :auth-ok :authenticated}}
```

The same deadline as a state-level `:after`:

```clojure
:authenticating
{:spawn {:machine-id :auth/request}
 :after {30000 :auth-failed}
 :on    {:cancel :idle
         :auth-ok :authenticated}}
```

One timer mechanism. When it fires, the state exits and the child is
destroyed. Durations are a positive integer (ms) or an ISO-8601 string
(`"PT30S"`). A `"5s"` shorthand is rejected.

## Fan-out and join with `:spawn-all`

Use `:spawn-all` when one state starts **N children in parallel** and
resumes on a join.

```clojure
;; cf. examples/patterns/long_running_work
:working
{:spawn-all
 {:children
  [{:id :s1 :machine-id :work/processor :data {:shard :s1 :total 100}}
   {:id :s2 :machine-id :work/processor :data {:shard :s2 :total 100}}
   {:id :s3 :machine-id :work/processor :data {:shard :s3 :total 100}}]

  :join            :all
  :on-child-done   :work/child-done
  :on-child-error  :work/child-error
  :on-all-complete [:work/all-done]
  :on-any-failed   [:work/any-failed]}

 :on
 {:progress        {:action :record-progress}   ;; no :target — don't respawn
  :work/all-done   {:target :complete}
  :work/any-failed {:target :failed}
  :cancel          {:target :cancelled}}}
```

Each child is an ordinary machine. When it finishes, it dispatches the
parent's `:on-child-done` keyword, carrying its own `:id`:

```clojure
:done {:entry (fn [{data :data}]
                {:fx [[:dispatch [:work/flow [:work/child-done (:shard data)]]]]})}
```

The runtime owns the join bookkeeping. When the join resolves it fires the
parent event **and** destroys any siblings still in flight.

Rules:

- **Each child needs a unique `:id`** (the join key) on top of the usual
  spawn keys. Duplicates are `:rf.error/machine-spawn-all-duplicate-id`.
- **`:join` is only `:all` or `:any`.** There is no `{:n n}` and no
  predicate. Quorum ("N of M") is `:after` / `:always` plus a
  `:done-guard` that reads the join's done count — not a `:join` mode.
- **`:on-all-complete` is required for `:all`.** **`:on-some-complete` is
  required for `:any`.** Missing either is
  `:rf.error/machine-spawn-all-bad-shape`.
- **An unregistered child type fails the whole invoke**, atomically —
  nothing is spawned, so an `:all` join cannot hang on a child that never
  runs (`:rf.error/machine-spawn-unregistered-type`).
- A wall-clock bound on the join is the same as single `:spawn`: `:after`
  or `:timeout` / `:on-timeout` on the spawn-all-bearing state.

Independently valuable children — fire-and-forget, no cancel-the-rest — are
N separate `:spawn`s, not a non-cancelling join.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Parent `:data` never gets the child id; `:rf.warning/on-spawn-return-ignored` | `:on-spawn` return is dropped | Read `(get-in data [:rf/spawned invoke-path])`, or use `:system-id` |
| No snapshot, no id; `:rf.error/machine-spawn-unregistered-type` | `:machine-id` is not registered and there is no `:definition` | Register the child type first |
| Registration throws `:rf.error/spawn-timeout-ms-removed` | `:timeout-ms` on `:spawn` / `:spawn-all` | Use `:timeout` / `:on-timeout`, or `:after` on the parent state |
| Registration throws `:rf.error/machine-spawn-all-bad-shape` on `:join` | `:join` was `{:n n}`, a predicate, or another non-enum | `:join` is only `:all` or `:any`. Quorum is `:after` / `:always` + `:done-guard` |
| `:join :all` rejected | missing `:on-all-complete` | Give `:on-all-complete` an event vector |
| `:join :any` rejected | missing `:on-some-complete` | Give `:on-some-complete` an event vector |
| Socket / interval / Worker still open after destroy | not a framework-managed resource | Close it in the child's `:exit` |
| Children torn down (or respawned) on a progress event | the parent's `:on` had a `:target` | Omit `:target` so the transition is internal |
