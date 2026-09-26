# One graph: derivations and their algebra views

You're reading a re-frame2 app you didn't write, and a tool such as Xray has drawn its
dependency graph — subscriptions, a flow, a resource cache, a machine — as one
picture. This page explains how to read that picture. The model: a subscription, a
flow, a resource and a machine are all nodes in one dependency graph rooted at your
state, and they differ in *where the value is kept* and *when it is recomputed*.

You don't need this page to write an app; [Where should this value live?](where-state-lives.md)
picks the right home for a value with four questions. This page is for reading an
app's graph without opening every source file.

## Start with a spreadsheet

In a spreadsheet, every cell is either an entered value or a formula over other cells,
and the engine recalculates the cells downstream of an edit. A value declares its
inputs and recomputes when they change.

re-frame2's [subscriptions](subscriptions.md) work like formula cells. A subscription
is a *derivation*: a value computed from other values by a pure function, which is
what "derivation" means throughout this page. You write the formula and the framework
recomputes it when its inputs change.

??? info "Coming from Solid / Vue / Preact signals?"

    The signals graph — `createMemo`, `computed`, signals — is the same idea: a
    derived value declares its inputs and recomputes when they change. A re-frame2
    subscription is a derived signal. If you've used `useMemo` to avoid recomputing a
    value on every render, the idea is the same: declare the inputs and let the
    framework decide when to recompute.

re-frame2 goes further than signals libraries: **flows, resources, route state and
machines are nodes in the same graph.** A flow is a cell that writes its result back
into the sheet. A resource is a cell whose authoritative value lives on a server, with
a local copy that can go stale. A machine is a cell with memory, whose next value
depends on its current one.

re-frame2 calls this model the **derivation/process algebra**: a small fixed set of
node kinds plus rules for combining them into a graph, in the sense that relational
algebra is the set of operations you compose into a SQL query. One vocabulary
describes every node.

## One function, two policies

The count of open todos, written twice with the same function:

```clojure
(defn count-remaining [todos]
  (count (remove :done? todos)))

;; Source form A: a subscription.
(rf/reg-sub :todo/remaining-count
  {:inputs [[:todo/all]]}
  (fn [[todos] _] (count-remaining todos)))

;; Source form B: a flow.
(rf/reg-flow :todo/remaining-count
  {:inputs      [[:todos]]
   :output-path [:remaining-count]}
  (fn [todos] (count-remaining (vals todos))))
```

`count-remaining` is one function, used by both. What differs is the *policy*: where the
answer is kept and when it is recomputed:

| | Subscription | Flow |
|---|---|---|
| Where the value lives | nowhere durable; recomputed on demand | in app-db, at `[:remaining-count]` |
| When it recomputes | when something reads it | after each event, in that event's commit |
| Who keeps it alive | a subscription-cache entry | the frame |

The subscription stores nothing and recomputes on demand. The [flow](flows.md) stores
its answer in [app-db](glossary.md#app-db) and recomputes after each event. The
function is the same; the storage and evaluation policy differ, and the rest of this
page applies the same view to resources, routes and machines.

## The five questions every node answers

Every node in the graph — a subscription, a flow, a resource read, the route, a machine
selector — answers the same five questions. Refinements and diagnostic fields, covered
below, add detail but no sixth question.

| Question | Field | Example answers |
|---|---|---|
| What does it read? | `:inputs` | other subs, app-db paths, resource/machine refs, params, events… |
| What does it produce? | `:output` | an ephemeral fact, or a durable app-db / runtime-db address |
| Where does the value live? | `:storage` | `:ephemeral` · `:app-db` · `:runtime-db` · `:host-transient` |
| When does it run? | `:evaluation` | `:on-demand` · `:after-event` · `:on-reply` · `:on-route` · `:on-transition` · `:scheduled` · `:manual` |
| Who keeps it alive? | `:lifecycle` | a cache entry · the frame · a route · a resource key · a machine instance |

In this vocabulary the subscription is `:ephemeral` / `:on-demand` / cache
entry, and the flow is `:app-db` / `:after-event` / frame. The `:inputs` and the
function are the same; three policy fields differ.

The four `:storage` classes are the four places a value can be kept:
**`:ephemeral`**, nowhere durable, recomputed on demand (a subscription);
**`:app-db`**, in your app's state map (a flow); **`:runtime-db`**, in the
framework's partition beside app-db (paths under `:rf.db/runtime`), which holds route
and machine state and local copies of server data; and **`:host-transient`**, outside
durable state, for things that can't be serialized, such as an in-flight request
handle or a timer.

The five registration forms — `reg-sub`, `reg-flow`, `reg-resource`, `reg-route`,
`reg-machine` — are each translated into this one shape, called the node's **algebra
view**.

!!! note "You never write or call the algebra view"

    There is no `reg-fact` and no `reg-derivation`. The view is derived from the
    registration you already wrote, so a tool can answer "where does this value come
    from, when does it run, where does it live, what keeps it alive?" without reading
    your function bodies. There is no public accessor for the assembled graph either:
    Xray and the conformance fixtures use it internally, and the maps on this page are
    what those tools display.

??? note "Going deeper — two superkinds, nothing more"

    Every node is one of two superkinds. A **derivation** computes a fact from
    declared inputs with a pure function and has no memory beyond its output. A
    **process** also has state, a lifecycle and commands over time: it reacts to
    events, async replies, route changes and timers. Subscriptions and flows are
    derivations; resources, routes and machines are processes. A tool that
    understands only `:derivation` and `:process` can classify every node; finer
    labels are *refinements* of one of the two.

## One node, opened up

Here is the complete algebra view of the `:todo/remaining-count` subscription. It is
the five questions plus labels and diagnostics:

```clojure
{:id          :todo/remaining-count
 :kind        :derivation                      ;; superkind: :derivation | :process
 :refinement  nil                              ;; finer label; nil for a plain sub
 :source-form {:kind :reg-sub :id :todo/remaining-count} ;; what the author wrote
 :inputs      [[:sub [:todo/all]]]
 :output      [:fact :todo/remaining-count]
 :storage     :ephemeral
 :authority   nil                              ;; present only when the fact is remote
 :evaluation  :on-demand
 :lifecycle   :subscription-cache-entry
 :materialized? false
 :derive      <the fn passed to reg-sub>       ;; opaque token, never serialized code
 :schema      :int                             ;; the output's schema, when registered
 :source      {:ns "app.todos" :file "src/app/todos.cljs" :line 42}}
```

Notes:

1. **`:kind`** is one of exactly two closed superkinds — `:derivation` or
   `:process`. A tool that understands only those two can still classify every node.
2. **`:refinement`** carries the finer labels you'll meet below
   (`:resource-process`, `:route-fact`, `:machine-process`, `:machine-selector`). They
   never live in `:kind`, so a refinement always refines its node's superkind and
   never invents a third one.
3. **`:derive`** is an opaque token for the function you registered, never its
   source code. The graph contract is about dependencies, storage, evaluation and
   ownership; it never requires serializing your functions.

The trailing fields — `:schema` (the output's Malli [schema](glossary.md#schema)),
`:source` (namespace, file, line) and a doc string — are optional diagnostics. A node
without them is still complete, but they answer the first questions a reader of an
unfamiliar app asks: where is this defined, and what shape does it produce?

### Parametric nodes and the don't-execute rule

Some subscriptions compute their inputs from the [query vector](glossary.md#query-vector):
`[:todo/row 1]` reads a different todo than `[:todo/row 2]`.
The static graph can't know those edges before a concrete query exists, so it reports
`:parametric` and names the function that produces the inputs. The live graph reports
the actual edges for each query in use:

```clojure
;; STATIC — derived from registrations alone
{:id             :todo/row
 :kind           :derivation
 :inputs         :parametric
 :input-producer #'app.todos/todo-row-inputs}

;; LIVE — one node per concrete query vector
{:id        [:sub [:todo/row 1]]
 :kind      :derivation
 :inputs    [[:sub [:todo/by-id 1]]
             [:sub [:todo.ui/editing-id]]]
 :lifecycle :subscription-cache-entry}
```

This is the **don't-execute rule**: static inspection never runs your input, param or
scope functions. It reads declarations only, which makes the static graph safe to
compute anywhere (tests, docs, an editor) with no side effects. A `:parametric` node
contributes no static edges; its edges appear in the live graph, observed from the
running app.

??? info "Coming from SQL?"

    Compare `EXPLAIN` on a parameterized query with the rows a particular binding
    returns. The static graph is the query plan: derived from declarations, the same
    for every parameter. The live graph is the result for concrete bindings, one node
    per query vector in use. A `:parametric` node marks what only a real binding can
    answer.

## Processes: nodes with state and a lifecycle

**Processes** are nodes with state, a lifecycle and commands over time. The first is
the **resource**: a fact whose authoritative value lives on a server, with a local
cached copy ([Server state: resources](../resources/concepts.md)). One `reg-resource`
produces more than one node: a process node for the cache entry, plus its read
selectors (`:rf/resource`, `:rf.resource/data`, `:rf.resource/loading?`, …), each an
ordinary on-demand derivation over that entry. Reading a selector never starts a
fetch.

```clojure
;; STATIC ALGEBRA VIEW of (rf/reg-resource :todo/list {…})
{:id          :todo/list
 :kind        :process
 :refinement  :resource-process
 :inputs      [[:param :rf.params] [:scope {:from-db :app/session}]]
 :output      [:runtime [:rf.runtime/resources :entries]]
 :storage     :runtime-db                     ;; the LOCAL cache lives here
 :authority   {:kind :remote :system :server  ;; the truth lives elsewhere
               :transport :rf.http/managed}
 :evaluation  #{:on-route :on-reply :scheduled :manual}   ;; a set (#{…}) — this process has many triggers
 :lifecycle   :scoped-resource-key
 :materialized? true
 :selectors   [:rf/resource :rf.resource/data :rf.resource/status
               :rf.resource/loading? :rf.resource/fetching? :rf.resource/stale?
               :rf.resource/error :rf.resource/refresh-error :rf.resource/has-data?
               :rf.resource/previous-data]}
```

**`:storage` always names the local home**, here the runtime-db cache entry.
**`:authority` names where the fact really comes from**, here a server. "Remote" is
not a storage class: you always hold a local copy, and the server being authoritative
doesn't change where that copy is kept. So this resource's `:storage` is
`:runtime-db` and its `:authority` is the remote server, two separate fields.

??? info "Coming from TanStack Query?"

    Your query cache has the same split, unnamed. The server is the `:authority`; the
    `queryCache` is the local `:storage`; `isLoading`, `data` and `error` are read
    selectors over one cache entry; and the staleness and refetch policy is the
    entry's `:evaluation`. re-frame2 records these as fields rather than leaving them
    implicit in a hook's behaviour, which is why a tool can draw a resource the same
    way it draws a subscription.

??? note "Going deeper — where the non-serializable leftovers go"

    An in-flight request handle or a timer can't be serialized into durable state, so
    it is classed `:host-transient`: kept outside durable frame state and torn down at
    the end of its lifecycle. It is never the only copy of a fact; replay and restore
    use the durable `:runtime-db` entry. In a live resource view it appears as
    `:host-transient [[:rf.http/in-flight <request-id>]]` plus a `:work-ledger`
    summary (the in-flight attempt's identity, owners, causes and transport), present
    only while a fetch is in flight.

### Buying back static visibility: named resolvers

Because static inspection runs nothing, anything hidden in a function body is
invisible to it. Scope is the fact a tool most wants to show, since it is the cache's
tenant boundary, so a scope is declared as data. A **named scope resolver** such as
`{:from-db :session/current-tenant}` has declared inputs of its own, which the static
graph reads without running anything:

```clojure
;; STATIC ALGEBRA VIEW — named-resolver scope
{:id          :todo/list
 :kind        :process
 :refinement  :resource-process
 :inputs      [[:param :rf.params]
               [:scope {:from-db :session/current-tenant}]]   ;; the reference, verbatim
 :scope-resolver {:id        :session/current-tenant
                  :inputs    [[:db [:session :tenant-id]]]     ;; its declared inputs are static facts
                  :whole-db? false}}
```

The same holds throughout the algebra: a dependency declared as data is visible to a
tool before the app runs, and one inside a function body is not.

### Machines: a node with memory

A [machine](../machines/glossary.md#machine) is a node with memory: its next value
depends on its current one, which a pure derivation can't express, so a machine is a
process. Its [snapshot](../machines/glossary.md#snapshot) is durable runtime-db
state, written only by its own transitions. Here is its view, beside one of its
selectors:

```clojure
;; STATIC ALGEBRA VIEW of (rf/reg-machine :todo/sync {…})
{:id          :todo/sync
 :kind        :process
 :refinement  :machine-process
 :inputs      [[:event :todo.sync/start]      ;; the :on event keys across the state tree
               [:event :todo.sync/done]
               [:event :todo.sync/conflict]
               [:event :todo.sync/cancel]
               [:event :todo.sync/resolve]
               [:event :todo.sync/retry]]
 :storage     :runtime-db
 :evaluation  #{:on-transition :scheduled}   ;; :scheduled because it has an :after
 :lifecycle   :machine-instance}

;; A selector, how a view reads the machine, is an ordinary subscription.
(rf/reg-sub :todo/sync-state
  {:inputs [[:rf/machine :todo/sync]]}
  (fn [[snapshot] _] (:state snapshot)))
```

A machine's `:inputs` are the event ids its transitions listen for: every `:on` key
across the whole state tree, including compound, hierarchical and parallel states,
without duplicates. The framework's reserved triggers (`:rf.machine/*` and the `:*`
wildcard) are not listed as edges. Its `:evaluation` is a set: always
`:on-transition`, since only a transition advances a snapshot, plus `:scheduled` when
the machine declares an `:after` delayed transition, plus `:on-reply` when it spawns
child actors.

The `:todo/sync-state` selector's view is an `:ephemeral`, `:on-demand` derivation
like any other subscription, with the `:machine-selector` refinement and an edge to
the machine it reads. The graph takes the machine id from the selector's static
`[:rf/machine …]` input, so in an app with several machines each selector's edge
comes from the one machine it names ([State machines](../machines/concepts.md)).

??? note "Going deeper — spawned actors in the live graph"

    The static graph shows one node per registered machine type. A spawned actor has
    no registration of its own; it exists while its snapshot is in runtime-db. The
    live graph therefore shows one node per snapshot, with the instance's type (read
    from the snapshot) and its current `:state`. As with parametric subscriptions,
    the static graph knows the types you registered and the live graph knows the
    instances running.

### Routes: the same fact, every route

Every route produces the same route fact, `:rf/route`, the name for the route slice in
runtime-db, with the route's own id recorded in `:source-form` and evaluation
`:on-route`. Its `:inputs` are the framework's route-transition events
(`:rf.route/navigate`, `:rf.route/handle-url-change`), which are the same for every
route, so an app with forty routes has one route fact.

A route's `:resources` declaration becomes an **activation edge**, owned by the route,
into each resource it ensures ([Routing](../routing/concepts.md)):

```clojure
;; one :resources entry → one route-owned activation edge under :resource-edges
{:from   [:runtime [:rf.runtime/routing :current :params]]
 :to     [:resource :todo/list]
 :role   :param
 :target :parametric        ;; concrete scoped key needs a live match + scope
 :blocking? true}           ;; transition stays :loading until the resource settles
```

The edge runs from the route's matched params into the resource's params and carries
the resource id, so a tool shows which resource the route owns. `:blocking?`, when
declared, says whether the transition waits at `:loading` until the resource settles
or proceeds and lets the view show a spinner. The concrete target is `:parametric` in
the static graph, by the don't-execute rule; the concrete edge
(`[:route route-id nav-token]` → the scoped key) appears in the live graph once a
navigation has committed.

!!! note "A view only reads the graph"

    The nodes above produce values; a UI view consumes them. It derefs
    subscriptions, resources and the route fact, turns them into hiccup, and adds no
    edge of its own. A route's activation edge and an event's dispatch are *causes*
    that advance the graph; reading a node never does. This is why re-frame2 has no
    API for loading data from a view.

## Reading the assembled graph

A tool combines the views into one value, a map of `:nodes` and a vector of `:edges`:

```clojure
{:mode  :live
 :frame :app
 :nodes
 {[:sub [:todo/shared-list "team"]] {:kind :derivation :storage :ephemeral :evaluation :on-demand}
  [:sub [:rf/resource {:resource :todo/list :params {:list-id "team"}}]]
                                    {:kind :derivation :storage :ephemeral :evaluation :on-demand}
  :rf/route                         {:kind :process :storage :runtime-db
                                     :output [:runtime [:rf.runtime/routing :current]]}
  [:resource [:rf.scope/global :todo/list {:list-id "team"}]]
                                    {:kind :process :storage :runtime-db :status :loaded}}
 :edges
 [{:from  :rf/route
   :to    [:resource [:rf.scope/global :todo/list {:list-id "team"}]]
   :role  :param
   :owner [:route :todo/team-list 3]}                ;; [:route route-id nav-token]
  {:from [:sub [:rf/resource {:resource :todo/list :params {:list-id "team"}}]]
   :to   [:sub [:todo/shared-list "team"]] :role :input}]}
```

Four rules for reading it:

- **A node's key is its id, not its output address**: `[:sub <query>]`,
  `[:resource <scoped-key>]`, `:rf/route` for the live route slice. The runtime path
  a node writes to is recorded inside the node as `:output`.
- **Redaction keeps the structure.** Graph payloads carry source coordinates and value
  summaries, never raw sensitive values, so a redacted param is still an edge.
  ([Data classification](glossary.md#data-classification) is the mechanism.)
- **Every derivation is a whole-value function**: recomputing its entire output
  from its declared inputs gives the correct value. Memoization and equality pruning
  are optimizations that must not change that value.
- **Optional families may be absent.** Flows, resources, routes and machines each
  ship in a separate artefact that core doesn't depend on, and an app that doesn't
  load one contributes no nodes of that kind. A sparse graph is an app that uses
  fewer homes, not a broken graph.

To see one live, open [Xray](glossary.md#xray) on a running app. Its dependency-graph
panel draws this assembled view, one node per algebra view and one arrow per edge.
[Debug with Xray](../xray/index.md) shows the workflow.

## When the graph is wrong: errors as graph facts

The model also names the ways a graph can be unhealthy, in the same node vocabulary, so a diagnostic can say which node and which field is at fault. Where a concrete error id exists today, it is given:

- **Unknown input fact.** A derivation declares an input that nothing produces. For subscriptions, `subscribe` raises `:rf.error/no-such-sub` naming the missing id.
- **Cycle in an acyclic graph.** A flow whose inputs depend, directly or through other flows, on its own output. Flows are ordered topologically before they run, so `reg-flow` throws `:rf.error/flow-cycle` at registration, with a `:cycle` vector naming the chain (`[:a :b :a]` for `:a → :b → :a`). Subscriptions have no registration-time cycle check, because their graph is resolved dynamically as views read it; the first `subscribe` that closes a cycle reports `:rf.error/sub-cycle` in development builds, with `:cycle` naming the chain, and yields `nil`.
- **Illegal storage write.** A registration tries to write where its storage class doesn't allow. A flow writes `app-db` only, so an `:output-path` under `:rf.db/runtime` raises `:rf.error/flow-reserved-output-path`.
- **Missing lifecycle owner.** A process with nothing to keep it alive or release it. The model treats a graph that shows dependencies but not ownership as incomplete.
- **Unresolved resource scope.** A scope resolver that can't produce a key (its `:from-db` source is empty, say) raises `:rf.error/resource-sub-unresolved-scope`. Without a scope the resource can't form its `[cache-scope resource-id canonical-params]` identity, so it raises instead of serving another principal's data. A resource registered with no scope policy at all raises `:rf.error/resource-missing-scope-policy` at registration.
- **Stale reply suppressed.** An async reply arrived for a request that a newer navigation or fetch had already superseded, and the process dropped it by its declared identity. This is correct behaviour; the model lists it as a diagnostic so a tool can show that it happened.

[Errors](errors.md) explains how to read any of these records.

## Advanced

### How optional families plug in

Core can't `:require` flows, resources, routing or machines without defeating their bundle isolation and breaking a core-only build. So the internal graph composer reaches each optional family through a **contributor map**, `{family {:static-fn :live-fn …}}`. On the JVM the default contributors find whichever family artefacts are on the classpath; in the browser, the tool that draws the graph, which already requires the families it supports, supplies the map. An absent family contributes nothing, which is why an app without flows shows no flow nodes. The composer is bundle-isolated and has no public accessor.

### The whole-value law

Because every derivation is correct when recomputed whole, conformance tests verify a node by recomputing it, a tool can trust declared edges without running app code, and any optimization that preserves the value (memoization, equality pruning, dirty checks) is allowed. re-frame2 has no incremental (delta) evaluation. The model reserves a rule for one (a delta path must agree with whole-value recomputation) and a seventh graph error, *delta law check failed*, for a delta that doesn't; nothing raises it.
