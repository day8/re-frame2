# Where should this value live?

A count of open todos, a todo list fetched from a server, the step a sync is in: each value in a re-frame2 app has four possible homes. A [**subscription**](glossary.md#subscription) is a derived value computed on demand; a [**flow**](glossary.md#flow) is a derived value written into your state; a [**resource**](../resources/glossary.md#resource) is a cached copy of server data; a [**machine**](../machines/glossary.md#machine) is a process with named states.

A value in the wrong home goes stale, hides from your handlers, or spreads across booleans that drift out of sync. Four questions, asked in order, pick the home.

## The four questions

Ask them top to bottom and stop at the first *yes*.

1. **Can you recompute it, every time, from state you already have?** It's a **subscription**. ([Subscriptions](subscriptions.md))
2. **Must it live *in* [`app-db`](glossary.md#app-db), because an [event handler](glossary.md#event-handler) reads it, your schema covers it, or time-travel must carry it?** It's a **flow**. ([Flows](flows.md))
3. **Does it come from a server, where it can go stale and needs caching, refetch and invalidation?** It's a **resource**. ([Server state: resources](../resources/concepts.md))
4. **Does it have a lifecycle of its own: named states, timers, retries, cancellation?** It's a **machine**. ([State machines](../machines/concepts.md))

The questions are ordered by cost. A subscription stores nothing. A flow adds an `app-db` write. A resource adds a cache. A machine adds a transition table. Use a heavier home only when the value needs what it provides.

??? info "For JavaScript developers"

    You already make this choice across four libraries: a selector (reselect) for a derived value, Redux for store state, TanStack Query for the server cache, XState for process state. re-frame2 has all four in one framework, with one trace stream.

??? info "From re-frame v1"

    v1 had two homes, subscriptions and `app-db`. Server caches, loading states and multi-step processes were built on `app-db` with `:loading?` booleans and effect handlers. re-frame2 makes those three patterns built-in homes: flows, resources and machines.

## One app, four homes

The sections below follow the todo app as it grows: a value that starts as a pure recompute, then one a handler needs, then data from a server, then a process.

### Question 1: can you recompute it? Then it's a subscription

The number of open todos is a count over todos already in `app-db`, so it can be computed with nothing stored. That is a [subscription](glossary.md#subscription):

```clojure
(rf/reg-sub :todo/remaining-count {:inputs [[:todo/all]]}
  (fn [[todos] _]
    (count (remove :done? todos))))
```

There is no second copy to drift out of date and no "update the count" handler; a view reads `@(subscribe [:todo/remaining-count])`. It recomputes when the todos change, and only while something is subscribed. Most derived values belong here.

??? info "Coming from Redux?"

    This is a reselect selector, and `{:inputs [[:todo/all]]}` plays the role of the input selectors you pass to `createSelector`. The inputs are data rather than closures, so tools can draw the [derivation graph](glossary.md#the-derivation-graph).

A subscription must stay pure. One that fetches, writes `localStorage` or reads the clock is in the wrong home; [the wrong-home table](#signs-you-picked-the-wrong-home) says where that value should go.

### Question 2: must a handler read it? Then make it a flow

A new requirement: `:todo/add` refuses a new todo while 50 are still open, so the handler needs the count. Handlers can't read subscriptions: a handler receives app-db as a plain `db` value, and a subscription's value is not in it. Recomputing the count inside the handler puts the formula in two places.

The value now needs to be part of the application's state. That is a [flow](glossary.md#flow): when these `app-db` paths change, recompute this and write the result to that path.

```clojure
(rf/reg-flow :todo/remaining-count
  {:inputs      [[:todos]]             ;; app-db paths to watch
   :output-path [:remaining-count]}    ;; where the result is written
  (fn [todos]
    (count (remove :done? (vals todos)))))
```

The formula is the same; the value's home changed. `:todo/add` now reads `(:remaining-count db)` like any other state. Because the value is part of the [frame](glossary.md#frame)'s state it is covered by schemas, time-travel and SSR, and in [Xray](glossary.md#xray) the flow recomputes in the same [pipeline run](glossary.md#run) that changed its inputs.

A flow has four parts: the id; `:inputs`, the paths to watch, whose values are passed to the function in order; the pure function; and `:output-path`, where the result is written. The cost is an `app-db` write on every recompute. A typical app has dozens of subscriptions and a handful of flows; if no handler reads a flow's output, use a subscription.

!!! note "You write the inputs, never the output"

    Handlers write the inputs (`[:todos]`), and the flow writes the output (`[:remaining-count]`) in the same event. A handler that writes the output by hand brings back the two-copies problem. ([Flows](flows.md) covers the rules for `:output-path`.)

Flows fail at registration when the graph is wrong. Two flows whose inputs and outputs form a cycle throw `:rf.error/flow-cycle`, whose `ex-data` carries the chain as `:cycle` (for example `[:a :b :a]`), and two flows writing overlapping paths throw `:rf.error/flow-path-overlap`. At run time a flow is atomic with its event: if its function throws, the whole event aborts before any `:db` is committed.

!!! warning "Gotcha: a flow's `:schema` reports, it doesn't guard"

    A flow may carry an optional `:schema` (a [Malli schema](glossary.md#schema)) for its output, checked in dev on every recompute. A violation does not abort the event: a downstream flow may already have read the value, so the runtime writes it, commits the event, and emits `:rf.error/schema-validation-failure` (`:where :flow-output`) with the failing path and value. Flow schemas are [elided](glossary.md#elide) from production builds.

### Question 3: does it come from a server and go stale? Then it's a resource

So far the todos are local: the user typed them. Now the app also shows a team's shared list, and that data lives on a server. What you hold is a cache of it, which can go stale. A value with a remote origin, an identity naming what you fetched, staleness, refetching and invalidation is a [resource](../resources/glossary.md#resource).

A resource has two parts: a **read** and a **cause**. The read is an ordinary subscription a view reads; it never touches the network. The fetch is started separately by a cause: a route opening, an event, a machine entering a state. A view that fetched while rendering would fetch again on every re-render, and two views showing the same list would both fetch it. A cause fires once, for a reason you can see in the trace.

```clojure
;; Register once: params schema, scope, then the request function.
(rf/reg-resource :todo/list
  {:params-schema [:map [:list-id :string]]
   :scope         :rf.scope/global}
  (fn [{:keys [list-id]} _ctx]
    {:request {:method :get :url (str "/api/todos?list=" list-id)}
     :decode  :json}))

;; A cause starts the fetch, here an entry in an event handler's :fx vector.
;; Declaring the resource on a route is the most common cause.
[:dispatch [:rf.resource/ensure {:resource :todo/list
                                 :params   {:list-id "team"}
                                 :cause    [:event :todo.list/opened]}]]

;; A view reads it and never fetches.
@(subscribe [:rf/resource {:resource :todo/list :params {:list-id "team"}}])
;; → {:status :loaded :data {:todos [...]} :has-data? true ...}
```

A registration needs three things: **`:params-schema`** (the parameters that identify what you fetch), **`:scope`** (whose cache the entry belongs to), and the request function, returning a [managed-HTTP](../async/http.md) args map. A `reg-resource` missing one throws at registration: `:rf.error/resource-missing-scope-policy` for the scope, `:rf.error/resource-bad-spec` for the others.

The params are the identity: two screens asking for `{:list-id "team"}` share one cache entry and one request. The cache is part of runtime-db (`:rf.runtime/resources`), so it is reverted, serialised and hydrated with the rest of the frame's state.

??? info "Coming from TanStack Query?"

    A resource is your query (identity from params, staleness, tag invalidation) except that reads are subscriptions and fetches are started by routes and events, never by rendering.

!!! note "Scope is what stops a cross-user data leak"

    Every resource declares its scope; there is no default. `:rf.scope/global` means the same params give the same data for everyone, as for a list the whole team shares. `{:from-db :app/session}` names a resolver that derives the scope from the current viewer, for a per-user or per-tenant cache. If the resolver produces nothing (nobody is logged in), a read raises `:rf.error/resource-sub-unresolved-scope` instead of reading a shared entry.

Read a resource through subscriptions, never the raw cache. `[:rf/resource …]` returns the whole entry; for one fact there are single-value subs such as `[:rf.resource/data …]`, `[:rf.resource/status …]` and `[:rf.resource/loading?]` (the full list is in [Server state: resources](../resources/concepts.md)). They keep the first-load and background-refresh cases apart:

```clojure
;; First-load failure: no data ever arrived.
{:status :error  :data nil
 :error {:kind :rf.http/http-5xx :status 503}
 :refresh-error nil  :has-data? false}

;; Background-refresh failure: the old list stays on screen, with a warning.
{:status :loaded  :data {:todos [{:id 1 :title "Buy milk" :done? false}]}
 :error nil
 :refresh-error {:kind :rf.http/http-5xx :status 503}
 :has-data? true}
```

Ensuring a stale entry refetches it in the background while the old data stays on screen, and a write elsewhere can invalidate entries by tag. A route declaring its `:resources`, the most common cause, is covered in [Routing](../routing/concepts.md).

#### Reading a resource's state, and a write's

A write to the server is a [mutation](../resources/concepts.md#mutations-invalidate-by-tag), registered with `reg-mutation` and run by dispatching `[:rf.mutation/execute {:mutation … :params … :instance …}]`. The instance id is yours to choose, and it names the write whose progress you read. A view reads it the way it reads a resource:

```clojure
@(subscribe [:rf/mutation {:instance [:todo/save 1]}])
;; → {:status :pending :result nil :error nil
;;    :pending? true :success? false :error? false :settled? false …}
```

Outside a view (at the REPL, in a test, in a tool) read the stored entry directly with `rf/resource-state` and `rf/mutation-state`, naming the frame:

```clojure
(rf/resource-state {:resource :todo/list :params {:list-id "team"} :frame :app})
;; → {:resource/id :todo/list :status :loaded :data {:todos [...]}
;;    :error nil :refresh-error nil …}

(rf/mutation-state {:instance [:todo/save 1] :frame :app})
;; → {:mutation/id :todo/save :status :pending :result nil :error nil …}
```

Both return only the stored facts, such as `:status`, `:data` or `:result`, and `:error`. The booleans (`:loading?`, `:has-data?`, `:pending?`) are computed by the subscriptions, so reading one off these maps gives `nil`. Each returns `nil` when nothing has been ensured or executed under that identity yet. Without `:frame` they raise `:rf.error/no-frame-context` instead of returning a `nil` you could mistake for a missing entry. Views keep reading through the subscriptions, which re-render when the entry changes; [Testing resources](../resources/testing.md) uses both forms.

### Question 4: does it have its own lifecycle? Then it's a machine

The user clicks **Sync** to push local todos to the server, and you are now modelling a process: idle, syncing, then synced, or in conflict, or failed and retrying. There are rules about which state may follow which, a timeout and cancellation. The question is now "what state are we in, and what moves us on?", and that is a [machine](../machines/glossary.md#machine). The usual sign you need one:

```clojure
;; Don't do this: three booleans pretending to be one state.
{:sync/syncing?  true
 :sync/conflict? false
 :sync/error?    false}
```

Three booleans encode eight combinations, but sync has five legal states, and every handler grows a `cond` working out which one it is really in.

```clojure
;; One named state, transitions as data.
(rf/reg-machine :todo/sync
  {:initial :idle
   :states
   {:idle     {:on {:todo.sync/start    {:target :syncing}}}
    :syncing  {:after {30000 {:target :failed}}
               :on {:todo.sync/done     {:target :synced}
                    :todo.sync/conflict {:target :conflict}
                    :todo.sync/cancel   {:target :idle}}}
    :conflict {:on {:todo.sync/resolve  {:target :syncing}}}
    :synced   {:on {:todo.sync/start    {:target :syncing}}}
    :failed   {:on {:todo.sync/retry    {:target :syncing}}}}})
```

Sync can now only be in a state it can reach. The timeout belongs to `:syncing` and is cancelled when the machine leaves it, so a stale timer can't fire later. The machine's current state, its [snapshot](../machines/glossary.md#snapshot), lives in [runtime-db](glossary.md#runtime-db), the framework's part of frame state, where handlers can't overwrite it and time-travel and Xray can see it. A view reads it with `@(subscribe [:rf/machine :todo/sync])`.

An event the current state doesn't handle is a no-op: dispatch `:todo.sync/done` while the machine is `:idle` and the snapshot is unchanged, with a `:rf.machine.event/unhandled-no-op` trace. You don't need to check the state before dispatching.

??? info "Coming from XState?"

    `:initial`, `:states`, `:on` and `:after` map almost directly onto XState's `initial`, `states`, `on` and `after`, and a machine's working memory is `:data` rather than `context`. [Coming from XState](../machines/coming-from-xstate.md) maps the rest.

## Signs you picked the wrong home

| The smell | What it means | Move it to |
|---|---|---|
| A subscription that fetches, writes `localStorage` or reads the clock | A subscription must be a pure read | A **resource** for remote data; otherwise an effect for the write or a declared coeffect for the read ([Effects and coeffects](coeffects.md)) |
| A flow whose output no handler reads | An `app-db` write for a value only views use | A **subscription** |
| A handler writing a flow's `:output-path` | Two writers for one value | Let the handler write an input; the flow writes the output |
| A machine wrapping a single fetch, with only `:loading` and `:loaded` | A remote read with a status, not a process | A **resource**; its status is that lifecycle |
| Remote data in `app-db` with `:loading?` and `:error?` set by success and failure handlers | The resource cache rebuilt by hand, with its race conditions | A **resource** |
| Booleans like `:syncing?` / `:conflict?` / `:error?` that handlers keep decoding | A process split into flags whose combinations are mostly illegal | A **machine** |
| A view that dispatches, fetches, ensures a resource or navigates while rendering | Rendering should only read; this runs again on every re-render | A [route's `:resources`](../routing/concepts.md), an event's `:fx`, or the `:on-*` handler of the interaction |

A click handler that dispatches is fine, because the cause is the click. re-frame2 has no API for loading data from a view; when a view seems to need one, a cause is missing from a route or an event.

## Advanced

### More on flows

**Inputs can read runtime-db.** An input path starting with `:rf.db/runtime` reads the framework's part of frame state, so a flow can derive an `app-db` value from a machine's snapshot or the current route, for example `[:rf.db/runtime :rf.runtime/machines :snapshots :todo/sync :state]`. The output is always an `app-db` path; an `:output-path` under `:rf.db/runtime` raises `:rf.error/flow-reserved-output-path`.

**Flows are frame-scoped.** `reg-flow` registers against the current frame; add a `:frame` key to the metadata, or wrap the call in `with-frame`, to target a specific one. A `reg-flow` with no frame in scope raises `:rf.error/no-frame-context`.

### One graph underneath

All four homes are nodes in one [derivation graph](glossary.md#the-derivation-graph) rooted at your state. They differ in where the value is kept and when it is recomputed. A subscription stores nothing and recomputes on demand. A flow is stored in `app-db` and recomputed after each event. A resource is stored in runtime-db and recomputed on a cause or when stale. A machine is stored as a snapshot in runtime-db and advanced by transitions. [One graph: derivations and their algebra views](derivations-and-algebra-views.md) explains the model.
