# Actors

<a id="8-actors"></a>
<a id="actors"></a>
<a id="actors-spawning-child-machines"></a>

The login request should stop when the user cancels or its deadline expires.
Put that request in a child machine and spawn it on `:submitting`. Leaving
that state destroys the child and aborts the managed HTTP request it issued.

The login flow remains a **singleton**, one live instance per frame. Each
request is a **spawned actor**, a new instance with its own allocated id.

## State-bound spawn

Register the child type before the parent can enter `:submitting`. It starts
the request in its initial state's `:entry` and reports by reaching a final
state:

```clojure
(ns app.login
  (:require [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.http.managed]))

(rf/defmachine login-request
  {:initial :running
   :data {}
   :sensitive [[:data :credentials] [:data :token]]

   :actions
   {:issue-request
    (fn [{:keys [data]}]
      {:fx [[:rf.http/managed
             {:request {:method :post :url "/api/login"
                        :body (:credentials data)
                        :request-content-type :json
                        :sensitive? true}
              :decode :json
              :on-success [(:rf/self-id data) [:server-ok]]
              :on-failure [(:rf/self-id data) [:server-err]]}]]})
    :keep-token
    (fn [{[_ reply] :event}]
      {:data {:token (get-in reply [:value :token])}})
    :keep-error
    (fn [{[_ reply] :event}]
      {:data {:reason (:error reply)}})}

   :states
   {:running {:entry :issue-request
              :on {:server-ok {:target :done :action :keep-token}
                   :server-err {:target :failed :action :keep-error}}}
    :done   {:final? true :output-key :token}
    :failed {:final? true :error? true :output-key :reason}}})

(rf/reg-machine :auth/request login-request)
```

The runtime puts `:rf/self-id` in the child's `:data`. Addressing HTTP replies
to that id keeps them with this request instance. The
[`:sensitive` declarations](../core/how-to/keep-secrets-out-of-traces.md#classify-subsystem-data-on-the-subsystem)
redact credentials and the token in machine traces.

In the [tutorial's `login-flow`](tutorial.md#the-complete-machine), add these
two parent actions, replace its `:submitting` node, and register the updated
table again. The other guards, actions and states stay as defined there:

```clojure
;; Add under :actions.
:store-child-session
(fn [{event :event}]
  {:fx [[:dispatch [:auth.session/store
                    {:token (:result (nth event 2))}]]]})

:record-child-error
(fn [{data :data event :event}]
  (let [reason (nth event 2)]
    {:data {:attempts (inc (:attempts data))
            :error (or (:message reason) "Login failed.")}}))

;; Replace under :states.
:submitting
{:tags #{:auth/busy}
 :spawn {:machine-id :auth/request
         :data (fn [{:keys [event]}]
                 {:credentials (second event)})
         :on-done {:target :authed :action :store-child-session}
         :on-error [{:target :error-shown
                     :guard :under-retry-limit
                     :action :record-child-error}
                    {:target :locked-out :action :record-child-error}]
         :timeout "PT8S"
         :on-timeout [{:target :error-shown
                       :guard :under-retry-limit
                       :action :record-timeout}
                      {:target :locked-out :action :record-timeout}]}
 :on {:auth.login/cancel :idle}}
```

The parent now enters `:submitting` without issuing HTTP itself. Each visit
spawns a fresh `:auth/request` instance. The child's initial entry issues the
request; success, failure, timeout or cancel leaves `:submitting` and cleans
up that instance. The session handler still stores the token in app-db,
while the login machine keeps only its attempt count and error.

Put `:spawn` on a [compound parent](hierarchical-states.md#parent-lifecycle-spans-child-states)
when one child should span several states. On the
[machine root](hierarchical-states.md#the-machine-root), it lasts for the
whole machine's life.

<a id="fan-out-and-join-with-spawn-all"></a>

A state carries one `:spawn`. Use [fan-out and join](fan-out-and-join.md) for
several children with one completion policy, or separate spawns in parallel
regions for independent lifetimes.

## When a child finishes

A child finishes by entering a **root-level** `:final?` leaf. `:output-key`
selects the value from its `:data` to report. The runtime destroys the child
before notifying the parent.

The parent's `:on-done` takes either a transition or a data fold:

| Form | Use it when |
| --- | --- |
| `{:target :authed :action :store-child-session}` | Success should move the parent and describe effects |
| `(fn [{:keys [data result]}] (assoc data :profile result))` | Success should update the parent's private data |

A transition action receives a trigger shaped like
`[:rf.machine.spawn/done invoke-id {:result token ...}]`, so it reads
`(:result (nth event 2))`. A fold receives `result` directly and returns the
parent's whole next data map. The completion event then runs the parent's
normal transition calculation, so an `:always` guard may use the folded data.

`:on-error` takes a transition. Its third trigger element is the error
payload itself, without a `:result` wrapper. In this example it is the
`:reason` slot from the child's failed leaf. A thrown child action reports
exception details instead.

A final leaf nested inside a compound finishes only that sub-flow; it does
not finish the whole child. [Nested final states](hierarchical-states.md#when-a-sub-flow-finishes-nested-final-states)
explains that distinction. A resting screen such as the parent's `:authed`
omits `:final?`.

## Cancellation

Leaving the spawn-bearing state destroys its child on every exit path. A
parent's destruction or a frame's `destroy-frame!` also destroys the children
that its declarative `:spawn` and `:spawn-all` created. Unmounting a view or
changing a route does not itself destroy the frame.

Destroy aborts in-flight managed HTTP issued by that actor, cancels its
`:after` timers and releases its resource owners. The login child's
self-addressed reply is then suppressed as stale; it cannot complete the
next request instance.

A reply addressed to an ordinary event outside the actor still dispatches
with `:status :cancelled` and
`:error {:kind :rf.http/aborted :reason :actor-destroyed}`. Choose the
self-addressed form above when a reply should lose relevance with its actor.

## Timeouts

The spawn's `:timeout` and `:on-timeout` arm a deadline on the parent state.
The eight seconds in the example include the child's whole lifetime,
including any retries it implements. When the deadline transition leaves
`:submitting`, the child is destroyed.

The equivalent state-level form is `:after {8000 ...}` on `:submitting`.
Use either form. [Timeout durations](automatic-transitions.md#timeout-durations)
are positive integer milliseconds or ISO-8601 strings; `"8s"` is not accepted.

## Messaging

Send an ordinary event to the actor's id:

```clojure
{:fx [[:dispatch [child-id [:request/cancel]]]]}
```

The child must handle that trigger in its table. Events sent to the parent
are not forwarded automatically. For the login example, sending
`[:auth.login/flow [:auth.login/cancel]]` to the parent is enough: its exit
destroys the child.

Read a live child's snapshot with the same subscription as a singleton:

```clojure
@(rf/subscribe [:rf/machine child-id])
```

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `:rf.error/machine-spawn-unregistered-type` and no child snapshot | The child type was not registered | Register `:auth/request` before entering `:submitting` |
| Parent stores `nil` after success | The action read the completion as a raw HTTP reply | Read `(:result (nth event 2))`; the child already extracted the token |
| Request continues after cancel | The parent issued HTTP itself or did not leave the spawning state | Issue HTTP in the child and transition out of `:submitting` |
| `:rf.error/machine-spawn-all-duplicate-id` when two parents spawn one type | Both parents allocate the same default prefix | Give their spawns distinct `:id-prefix` values |
| Socket, interval or Worker remains open after destroy | It is a custom effect's handle | Close it in the child's `:exit` |

## Advanced

### Spawn spec keys

For the normal state-bound request, supply `:machine-id`, a `:data` function
and the parent's completion transitions. `:data` may also be a map; a supplied
map replaces the child definition's defaults, so merge defaults explicitly
when they are needed. The function receives `{:snapshot ... :event ...}`
after the parent's transition action has run.

Use `:definition` for an inline child instead of `:machine-id`. An inline
child needs an `:id-prefix` or `:fixed-actor-id` to name its instances. The
[spawn reference](../api/re-frame.machines.md#declarative-spawn-and-spawn-all)
records every key and default.

### Child runtime stamps

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

### Recording the spawned id

A declarative `:spawn` writes the new id into the **parent's** `:data` under
`:rf/spawned`, keyed by the spawning state's path. A `:spawn-all` stores a
map of child `:id` to actor id at that path:

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

### Starting the child

The child always runs its initial `:entry` cascade first. Prefer putting startup
work there.

If you omit `:start`, the runtime also dispatches a synthetic
`[:rf.machine.spawn/spawned]`. Most children can ignore it. If you set
`:start`, that event is sent **instead** — never both.

```clojure
:spawn {:machine-id :worker
        :start      [:worker/start {:shard :a}]}
```

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
same address. Spawning at an occupied fixed id destroys the previous actor
first, running its exits. A dispatch to that address reaches the current
incarnation; use distinct addresses when both instances must stay alive.

### Imperative spawn and destroy

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

### Custom effect cleanup

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
