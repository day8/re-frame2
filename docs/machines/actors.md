# 8. Actors

<a id="actors"></a>
<a id="actors-spawning-child-machines"></a>

Every machine so far has been a **singleton**: one `reg-machine` id, one live
instance in the frame. `[:rf/machine :auth.login/flow]` is that instance, or
`nil` before the first event.

A **spawned** actor is another live instance of a machine **type**, created
at run time. It gets an allocated id (`:auth/request#1`). Use one when you
need many concurrent instances, or a child whose lifetime is bound to a
parent state.

Login stays the singleton. The HTTP request becomes a spawned child: it
starts when `:submitting` is entered and is destroyed on every exit.

## State-bound spawn

Put `:spawn` on a state node. Entering the state creates the child. Leaving the
state — by any transition — destroys it. On the
[machine root](hierarchical-states.md#the-machine-root), the child lives as
long as the machine.

The singleton login machine spawns a request actor on `:submitting`:

```clojure
(rf/reg-machine :auth/request
  {:initial :running
   :data    {}

   :actions
   {:issue-request
    ;; The tutorial's managed request, addressed to this actor's own id.
    (fn [{data :data}]
      {:fx [[:rf.http/managed
             {:request    {:method :post :url "/api/login"
                           :body (:credentials data)
                           :request-content-type :json}
              :decode     :json
              :on-success [(:rf/self-id data) [:server-ok]]
              :on-failure [(:rf/self-id data) [:server-err]]}]]})

    :keep-token
    (fn [{data :data [_ {:keys [value]}] :event}]
      {:data (assoc data :token (:token value))})}

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
         :on-done    {:target :authed
                      :action (fn [{data :data ev :event}]
                                {:data (assoc data :token (:result (nth ev 2)))})}
         :on-error   {:target :error-shown}}
 :on    {:auth.login/cancel :idle}}
```

`:auth.login/flow` is still the singleton. `:auth/request` is the **type**.
Each visit to `:submitting` allocates a new spawned id. Leaving
`:submitting` — success, error, cancel, timeout — destroys that actor.

The child reports by finishing, and sends its parent nothing. `:done` names
`:token` as its `:output-key`; the parent's `:on-done` is a transition to
`:authed` whose action reads that value at `(:result (nth ev 2))`. `:failed`
routes the parent through `:on-error` instead, and there `(nth ev 2)` is the
failure payload itself: the error leaf's `:output-key` slot (`nil` here, since
`:failed` names none), or the exception details when a child action threw.

A larger shipped case binds one socket actor to a parent that spans several
children:

```clojure
;; cf. examples/patterns/websocket
(rf/reg-machine :ws/connection
  {:initial :disconnected
   :data    {:url nil :auth-token nil}

   :actions
   {:record-options
    (fn [{data :data [_ {:keys [url auth-token]}] :event}]
      {:data (assoc data :url url :auth-token auth-token)})}

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
               :connected      {}}}

    :reconnecting {:on {:ws/connect :active}}
    :failed       {:on {:ws/connect :active}}}})
```

The socket is spawned on the `:active` *parent*, so one actor spans
`:connecting` → `:authenticating` → `:connected`.

A state carries at most one `:spawn`. For several children, use a compound
state with one actor per substate, or [`:spawn-all`](fan-out-and-join.md).
One state cannot declare both (`:rf.error/machine-spawn-all-with-spawn`).
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
| `:id-prefix` | base for the allocated id (`:websocket/socket#1`); defaults to `:machine-id`, so a `:definition` spawn needs it or `:fixed-actor-id`. Ids are counters, never `gensym`, and each parent keeps its own: two parent machines spawning one type need distinct prefixes |
| `:start` | first event sent to the newborn |
| `:on-done` | transition when the child reaches a successful final state — or, as a fn, a `:data` fold |
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

## Recording the spawned id

On every declarative `:spawn` / `:spawn-all`, the runtime writes the
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
not a dead id. Outside a machine, read the same slot off the parent's snapshot:
`(get-in @(rf/subscribe [:rf/machine :ws/connection]) [:data :rf/spawned [:active]])`.

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

A parent gets the id from `:rf/spawned`, or — when it chose the address —
from its own `:data`. A child gets the parent from `:rf/parent-id`. There is no
separate *send* verb.

### `:fixed-actor-id`

Give the actor a well-known address when you want a stable name rather than an
allocated instance id.

```clojure
:spawn {:machine-id     :request/protocol
        :fixed-actor-id :primary-request
        :data           {:url "/api/user"}}
```

From an action (which cannot read app-db) — the same `:dispatch` as anywhere
else:

```clojure
{:fx [[:dispatch [:primary-request [:request/cancel]]]]}
```

Re-entering the spawning state creates a new incarnation of the child at the
same address. A dispatch to that address reaches whichever incarnation currently
holds it; the runtime tells incarnations apart itself.

## When a child finishes

A one-shot child reports success by entering a **root-level**
[`:final?`](concepts.md#final-states) leaf and naming the `:data` slot to hand
up with `:output-key`, as `:auth/request`'s `:done` does above. Instead of
moving, the parent can fold that value into its own `:data`:

```clojure
:submitting
{:spawn {:machine-id :auth/request
         :data       (fn [{:keys [event]}]
                       {:credentials (second event)})
         :on-done    (fn [{:keys [data result]}]
                       (assoc data :token result))
         :on-error   :error-shown}
 :on    {:auth.login/cancel :idle}}
```

- **`:on-done` is a transition or a data-fold, chosen by its value.** An
    `:on`-shaped value — `{:target :configured :action …}`, a keyword or path
    target, or guarded candidates — moves the parent like `:on-error`, with
    the result at `(:result (nth ev 2))`. A fn is a data-fold:
    `(fn [{:keys [data result]}] new-data)`. The fold itself does not move the
    parent — but the completion **event** then flows into the parent's ordinary
    macrostep, so the parent *can* advance on it. Fold the result in `:on-done`
    and let an `:always` guard read it:

    ```clojure
    :configuring
    {:spawn  {:machine-id :app/loader
              :on-done    (fn [{:keys [data result]}] (assoc data :config result))}
     :always [{:guard :config-loaded? :target :loading-deps}]}
    ```

    An explicit `:on {:rf.machine.spawn/done {:target :loading-deps}}` works too,
    and fires only on a success: a failed child arrives as
    `:rf.machine.spawn/error` instead.
    `:on-done` is applied on the parent's **next** macrostep, not
    inside the child's teardown cascade.

- **`:on-error` is a transition.** A child that fails — a `:final?` leaf
    flagged `:error? true`, or a thrown action — routes the parent through that
    `:on`-shaped spec.

- Entering a root-level `:final?` destroys the child; only then is the
    parent notified. A nested `:final?` only tells the compound "this sub-flow is
    done"; see [Hierarchical states](hierarchical-states.md#when-a-sub-flow-finishes-nested-final-states).

## Imperative spawn and destroy

Declarative `:spawn` lowers to reserved fx you can also emit from any `:fx`
vector:

```clojure
{:fx [[:rf.machine/spawn
       {:machine-id     :logger
        :fixed-actor-id :logger
        :data           {:buffer []}
        :start          [:logger/connect]}]]}

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
- its parent is destroyed, when a `:spawn` or `:spawn-all` spawned it (an actor
  an action hand-emitted outlives its spawner);
- a timeout or `:after` transition exits that state;
- a `:spawn-all` join cancels surviving siblings;
- you emit `[:rf.machine/destroy actor-id]`;
- the **frame** is torn down with `destroy-frame!`. A view unmount or a
  route change does **not** destroy the frame — tear one down only when you
  mean to.

Destroy releases exactly three framework-managed kinds:

- in-flight `:rf.http/managed` requests this actor issued;
- this actor's armed `:after` timers;
- `:rf.resource/*` owners this actor holds.

The request is always aborted. A reply addressed to an ordinary event still
dispatches, with `:status :cancelled` and
`:error {:kind :rf.http/aborted :reason :actor-destroyed}`. One addressed back
to the destroyed actor, the `[(:rf/self-id data) …]` shape `:auth/request`
uses above, is suppressed as `:status :stale` and only traced
(`:rf.http/stale-suppressed`).

Anything else — a `js/WebSocket`, an interval, a Worker — is owned by an
effect you register, keyed by the actor's `:rf/self-id`. The handle itself
never goes in `:data`, which [must print and read back](concepts.md#the-snapshot).
The child's actions stay pure and return that effect: `:entry` opens, `:exit`
closes, and `:exit` runs on every destroy path:

```clojure
(defonce sockets (atom {}))

(rf/reg-fx :ws/open
  (fn [_ctx {:keys [id url]}]
    (swap! sockets assoc id (js/WebSocket. url))))

(rf/reg-fx :ws/close
  (fn [_ctx id]
    (some-> (get @sockets id) .close)
    (swap! sockets dissoc id)))

:actions
{:open-socket  (fn [{data :data}]
                 {:fx [[:ws/open {:id (:rf/self-id data) :url (:url data)}]]})
 :close-socket (fn [{data :data}]
                 {:fx [[:ws/close (:rf/self-id data)]]})}

:connected
{:entry :open-socket
 :exit  :close-socket
 :on    {:disconnect :idle}}
```

The [long-running-work example](../../examples/patterns/long_running_work/)
cancels by leaving `:working`: that exit destroys every surviving child,
pending `:after` yield-timers included.

## Timeouts

`:timeout` / `:on-timeout` on the spawn spec bounds the child's lifetime. It
lowers onto the spawn-bearing state's `:after`, so the deadline is anchored to
that state's entry and spans the child's internal retries:

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
destroyed. [Timeout durations](automatic-transitions.md#timeout-durations)
lists the accepted forms. A `:timeout-ms` key on `:spawn` or `:spawn-all`
makes `reg-machine` throw `:rf.error/spawn-timeout-ms-removed`.

## Fan-out and join with `:spawn-all`

When one state starts several children at once and moves on when they
finish, use `:spawn-all`. [Fan-out and join](fan-out-and-join.md) teaches it.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Parent `:data` never gets the child id | the spawn was hand-emitted from an action's `:fx`, so it carries no declarative invoke-id to key `:rf/spawned` under | Choose an explicit `:fixed-actor-id`, store it in `:data`, or use a declarative `:spawn` |
| No snapshot, no id; `:rf.error/machine-spawn-unregistered-type` | `:machine-id` is not registered and there is no `:definition` | Register the child type first |
| Spawn refused with `:rf.error/machine-spawn-all-duplicate-id` | Two parent machines spawn one type, so both mint `<type>#1` | Give each parent's spawn its own `:id-prefix` |
| Socket / interval / Worker still open after destroy | not a framework-managed resource | Close it in the child's `:exit` |
| A self-addressed `:on-failure` never fires when the actor is destroyed | the reply target names the actor being torn down, so it is obsolete | Expect no reply — it is suppressed as `:status :stale`. Clean up in the child's `:exit`, or address the reply to an event outside the actor |

`:rf.error/spawn-timeout-ms-removed` is covered in
[Automatic transitions → Troubleshooting](automatic-transitions.md#troubleshooting).
