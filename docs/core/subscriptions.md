# Subscriptions

[App-db](glossary.md#app-db) holds your facts. Your [views](glossary.md#view) want
conclusions: "the visible todos", "how many are left", "is this number odd?". A
[subscription](glossary.md#subscription) sits in between: a named, cached derivation
that turns facts into a conclusion and re-runs only when its inputs change.

## Don't store what you can derive

The [app-db](app-db.md) counter shows a number. Suppose we also want to show whether
that number is odd or even. You could store a parity flag in app-db and update it
alongside the value, but parity follows from a fact you already have, and a stored
copy is one more thing every handler must keep in step. Derive it instead. The cell
re-registers the counter without its step size, so the new subscription is the only
thing to read:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :initialise
  (fn [_ [_ start]] {:db {:value start}}))

(rf/reg-event :inc
  (fn [{:keys [db]} _] {:db (update db :value inc)}))

(rf/reg-event :dec
  (fn [{:keys [db]} _] {:db (update db :value dec)}))

(rf/reg-sub :value
  (fn [db _] (:value db)))

;; the new idea: a subscription whose input is ANOTHER subscription
(rf/reg-sub :parity {:inputs [[:value]]}
  (fn [[n] _] (if (odd? n) :odd :even)))

(rf/reg-view parity-counter []
  [:div
   [:button {:on-click #(dispatch [:dec])} "−"]
   [:span @(subscribe [:value]) " is " (name @(subscribe [:parity]))]
   [:button {:on-click #(dispatch [:inc])} "+"]])

[rf/frame-root {:id :app :initial-events [[:initialise 5]]}
 [parity-counter]]
```

Think of a two-cell spreadsheet: `:value` is a cell and `:parity` is a formula over
it. The `:inputs` line declares the dependency, so `:parity` recomputes only when
`:value` changes, and a view reading `:parity` re-renders only when the answer
changes.

## A todo list

One number doesn't leave much to derive. From here on, the examples use a small todo
list. Its app-db holds two facts, the todos keyed by id and which ones to show:

```clojure
{:todos   {1 {:id 1 :title "Buy milk"     :done? false}
           2 {:id 2 :title "Walk the dog" :done? true}}
 :showing :all}   ;; or :active, :done
```

Everything else a todo screen shows (the visible list, the count left to do) is a
conclusion from those two facts, so it belongs in subscriptions.

## What is a subscription?

A function from app-db to a value some view wants, registered under a name:

```clojure
(rf/reg-sub :todo/showing
  (fn [db _query]
    (:showing db)))
```

A view reads it with the `subscribe` that `reg-view` provides, and a deref:

```clojure
@(subscribe [:todo/showing])
```

The vector `[:todo/showing]` is the [**query vector**](glossary.md#query-vector): the
id, plus any arguments. `[:todo/by-id 2]` carries one argument, and the sub
destructures it from the same vector it was called with:

```clojure
(rf/reg-sub :todo/by-id
  (fn [db [_ id]]
    (get-in db [:todos id])))
```

The `@` does two jobs. It unwraps the reactive reference to a plain value, and it
registers the view as a dependent of that value, so the view re-renders when, and
only when, the value changes. The view never polls and never listens to a store-wide
"something changed" signal.

If you subscribe to an id nobody registered (a typo, a namespace that hasn't loaded),
re-frame2 emits `:rf.error/no-such-sub`, an always-on
[error record](glossary.md#error-record) that survives into production and carries
the offending `:rf.sub/id`. The subscription then yields `nil` so the view still
renders. The failed lookup leaves no cache entry behind, so registering the sub later
(boot order, a lazy load) lets the next subscribe build cleanly.

## Why name something so trivial?

A view has no `db` to read, so `(:showing db)` isn't available to it. Naming the read
also buys two things:

1. **Decoupling.** Where the value lives in app-db is the subscription's business.
   Move it and you change one registration, not forty views.
2. **Sharing.** Every view asking for `[:todo/showing]` reads the same cached node.
   The cache is keyed by query vector (per [frame](glossary.md#frame); for now, read
   that as "per app"), so the computation runs once per change however many views
   read it.

Query vectors are compared by value, so a query vector built fresh on every render,
such as `[:todo/by-id id]`, shares one cache node for as long as it stays `=` to the
last one. That holds for map arguments too. An argument that is never `=` to anything
else, usually a freshly built closure, misses the cache every time.

??? info "Coming from Redux, Solid or Jotai?"

    A subscription is a selector (Reselect's `createSelector` with the memoisation
    built in), or a derived signal. The differences: subscriptions are named in a
    registry, so tools can draw the whole graph without running your app; change
    detection is value equality (`=`) rather than reference identity; and
    dependencies are declared as data rather than discovered by watching a function
    run.

## Three layers, one graph

A subscription's input can be app-db or other subscriptions, as `:parity` showed.
Once derivations feed derivations, you have a directed acyclic graph, and re-frame2
keeps it up to date for you.

A sub's layer is decided by what it reads:

- **Layer 1: extractors.** Read app-db directly and pluck out a raw slice, with no
  computation. They re-run on every app-db change to check whether their slice moved
  (the next section explains why that's cheap).
- **Layer 2: derivations.** Read other subs, declared under `:inputs`. Sort, filter,
  join, shape. They re-run when an input's value changes by `=`.
- **Layer 3 and up: compositions.** Subs over subs over subs, with the same rule.

The todo list's subscriptions form a three-layer chain:

```clojure
;; Layer 1: extractors. Read app-db, pluck a slice, nothing else.
(rf/reg-sub :todo/todos
  (fn [db _] (:todos db)))

(rf/reg-sub :todo/showing
  (fn [db _] (:showing db)))

;; Layer 2: reads :todo/todos (a sub), never app-db.
(rf/reg-sub :todo/all {:inputs [[:todo/todos]]}
  (fn [[todos] _]
    (vec (sort-by :id (vals todos)))))

;; Layer 3: composes two subs.
(rf/reg-sub :todo/visible {:inputs [[:todo/all] [:todo/showing]]}
  (fn [[todos showing] _]
    (case showing
      :active (filterv (complement :done?) todos)
      :done   (filterv :done? todos)
      todos)))

(rf/reg-sub :todo/remaining-count {:inputs [[:todo/all]]}
  (fn [[todos] _]
    (count (remove :done? todos))))
```

Read `:inputs` as "this sub's inputs come from". `:todo/all` does not take `db`. It
takes the value `:todo/todos` produced. Declared inputs always arrive as a vector, in
the order you listed them (`[todos]` for one, `[todos showing]` for two), so adding a
second input never reshapes the body you already wrote.

The registration determines the graph: `(fn [db _] ...)` with no `:inputs` is an
extractor, and `:inputs` makes a derivation. Because the dependencies are data in the
registry, [Xray](glossary.md#xray) can draw your subscription graph without running a
single computation function (`re-frame.subs.tooling/sub-topology` reads it from the
registry).

## The equality gate

One rule keeps the graph cheap: a subscription's cached value is invalidated only
when one of its inputs changes value, checked with `=`.

When app-db changes, every layer-1 extractor re-runs, because app-db is its input.
Each new output is compared with the previous one by `=`. If the slice didn't change,
the cached value stands and propagation stops there: downstream subs don't re-run
and views don't re-render.

So layer 1 shields everything behind it. Dispatch `[:todo/set-showing :active]` and
the `:todo/todos` extractor re-runs, sees its slice is `=` to last time, and stops:
the sort in `:todo/all` never executes. Only `:todo/visible`, which reads
`:todo/showing`, recomputes. The same check sits at every node, so a layer-2 sub
that recomputes but produces an `=` result stops propagation to its dependents too.
You get memoisation at every node without writing `memo` or dependency arrays.

The check applies to app-db itself. A handler that writes a value a key already
holds produces an app-db that is `=` to the old one, so nothing recomputes. Writing
state that didn't change cannot cause a render storm.

??? info "Coming from Reselect?"

    Reselect memoises on reference identity, so a reducer that returns a freshly
    allocated but element-wise identical array makes everything downstream
    recompute, and "never allocate unless something changed" becomes a rule you
    follow at every reducer. Here, equal values are equal however they were
    allocated.

The practical rule: **keep extractors tiny and put the work in layer 2.** An
extractor runs on every app-db change, so it should be a `get` or `get-in` and
nothing more. A `sort-by` inside an extractor runs on every keystroke in every
unrelated form; the same `sort-by` in a layer-2 sub runs only when its input slice
changes. "Is there computation in a layer-1 sub?" is the first question
[Find and fix a slow view](how-to/fix-a-slow-view.md) asks.

The [Cells spreadsheet example](../../examples/core/seven_guis/cells) derives 2,600
mounted cell values from one shared input sub; the `=` check on each result means
only cells whose displayed value changed re-render.

## Watch it prune

The cell below has two app-db slices, `:todos` and `:showing`, with an extractor and
a derivation on each branch, and one view reading both. Press **`Ctrl-Enter`**
(**`Cmd-Enter`** on macOS) to evaluate, then click **add todo** a few times:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :todo/initialise
  (fn [_ _] {:db {:todos {} :showing :all}}))

(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [id (inc (apply max 0 (keys (:todos db))))]
      {:db (assoc-in db [:todos id] {:id id :title title :done? false})})))

(rf/reg-event :todo/set-showing
  (fn [{:keys [db]} [_ showing]] {:db (assoc db :showing showing)}))

;; Layer 1: one tiny extractor per slice.
(rf/reg-sub :todo/todos   (fn [db _] (:todos db)))
(rf/reg-sub :todo/showing (fn [db _] (:showing db)))

;; Layer 2: one derivation per branch.
(rf/reg-sub :todo/remaining-count {:inputs [[:todo/todos]]}
  (fn [[todos] _] (count (remove :done? (vals todos)))))

(rf/reg-sub :todo/showing-label {:inputs [[:todo/showing]]}
  (fn [[showing] _] (str "showing " (name showing))))

(rf/reg-view todo-summary []
  [:div
   [:p @(subscribe [:todo/remaining-count]) " left to do"]
   [:p @(subscribe [:todo/showing-label])]
   [:button {:on-click #(dispatch [:todo/add "Buy milk"])} "add todo"]
   [:button {:on-click #(dispatch [:todo/set-showing :all])} "show all"]])

[rf/frame-root {:id :todos :initial-events [[:todo/initialise]]}
 [todo-summary]]
```

Every click on **add todo** builds a new app-db value, and both branches hang off
it, yet only the count line changes. The `:todo/showing` extractor ran on every
click, but it produced an `=` value each time, so `:todo/showing-label` never
recomputed.

**show all** is the no-op case. `:showing` is already `:all`, so the new app-db is
`=` to the old one and no subscription recomputes.

To see these decisions rather than infer them, run the same shape in your own app
with [Xray](glossary.md#xray) attached ([Debug with Xray](../xray/index.md)), click
**add todo**, select the newest event row, and open the **Views** tab:
`:todo/remaining-count` is marked as the re-render's trigger while
`:todo/showing-label` sits beside it, unmarked.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| A sub reads `nil` and `:rf.error/no-such-sub` is reported | The id is not registered: a typo, or its namespace hasn't loaded | Fix the id or require the namespace; the next subscribe after registration builds normally |
| A sub reads `nil` and `:rf.error/sub-exception` is reported | The computation function threw | Fix the computation; the record's `:where` says which path threw (below) |
| A sub reads `nil` and `:rf.error/schema-validation-failure` is reported with `:where :sub-return` | The computed value doesn't match the sub's `:schema` | Fix the computation or the schema |
| `:rf.error/no-frame-context` from `subscribe` | The subscribe ran under no frame, e.g. in a plain `defn` or an async callback | Subscribe from a `reg-view`, or pass `{:frame id}` |
| `:rf.error/frame-destroyed` from `subscribe` | The frame was destroyed before the subscribe ran (a stray async callback, a hot-reload race) | Stop the callback when the frame goes away |
| Typing in one form is slow everywhere | Computation in a layer-1 extractor | Move it into a layer-2 sub |

A computation that throws is recovered to `nil` so it cannot take down the render.
The error record is always on, so it reaches your production error listeners, and its
`:where` tag is `:reactive` for the live cache path or `:compute-sub` for the pure
test/SSR path. Because SSR uses the pure path, a sub that throws during a server
render lets the server return a real error response instead of HTML built from
`nil`s.

Schema validation runs after the body, so a wrong shape is caught at the sub that
produced it rather than three layers downstream. The whole `:sub-return` check is
[elided](glossary.md#elide) from production builds. That applies to this boundary
only; see [What goes and what stays](how-to/validate-with-schemas.md#in-production-what-goes-what-stays).

`:rf.error/frame-destroyed` also yields `nil` and carries the frame id and the
attempted query vector. It is always on, so a genuine use-after-destroy bug stays
visible in production. A subscribe issued under no frame at all is the different
`:rf.error/no-frame-context`; see
[frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found).
All of these ids are catalogued in [Errors and recovery](errors.md).

## Advanced

### When the inputs depend on the arguments

Sometimes the arguments in the query vector decide which upstream subs you need. A
row of the todo list needs that todo, plus whether it is the one being edited. A
literal `:inputs` vector can't express this: it lists query vectors at registration
time, before any `id` exists.

For that case, give `:inputs` a function instead of a vector. The runtime calls this
*input function* with the outer query vector:

```clojure
(rf/reg-sub :todo/row
  ;; input function: outer query vector -> a vector of input query vectors
  {:inputs (fn [[_ id]]
             [[:todo/by-id id]
              [:todo.ui/editing-id]])}
  ;; computation function: the resolved input values (same order), plus the query vector
  (fn [[todo editing-id] [_ id]]
    (assoc todo :editing? (= editing-id id))))
```

The input function answers "what does this sub depend on?" and the computation
function answers "what does it compute?". The resolved input values arrive as a
vector, in declaration order, exactly as they do for a literal `:inputs`.

Write the literal vector whenever the inputs don't depend on the query vector, and a
function only when they do. A literal is a constant input function without the
boilerplate, and tools can draw its edges without running anything.

Notes, most common first:

1. **The input function returns data, not live subs.** Return query vectors, never
   `subscribe` calls. Keep it pure over the query vector: no app-db deref, no
   dispatch, no IO. The runtime does the subscribing. A single input is still a
   vector of one query vector, `[[:todo/by-id id]]` rather than `[:todo/by-id id]`,
   because `[:x :y]` is ambiguous (one query with an argument, or two inputs?).
2. **It is not on the hot path.** It runs once, when a concrete query vector like
   `[:todo/row 1]` is first materialised. From then on that entry is an ordinary
   cached node; `[:todo/row 2]` is a separate entry with its own inputs.
3. **Dependencies cannot come from app-db.** A sub whose edges changed with state
   would break disposal, hot reload, and Xray's topology view. When the parameter
   you need lives in app-db, read it in the view and pass it through the query
   vector. Mount and unmount already manage lifecycle there:

```clojure
(rf/reg-view editing-row []
  (let [id  @(subscribe [:todo.ui/editing-id])
        row @(subscribe [:todo/row id])]
    ...))
```

#### The exact return grammar

An input function must return a vector, and every element must itself be a query
vector (a vector whose head is a keyword):

```clojure
;; Accepted
[[:todo/by-id id] [:todo.ui/editing-id]]  ;; multiple inputs
[[:todo/by-id id]]                        ;; a single input: still a vector OF query vectors
[]                                        ;; no inputs (unusual, but valid)

;; Rejected: each signals :rf.error/sub-input-fn-bad-return
:todo.ui/editing-id                       ;; a bare keyword
[:todo/by-id id]                          ;; a scalar query vector (argument, or two inputs?)
[[:todo/by-id id] :todo.ui/editing-id]    ;; a mix of query vector and bare keyword
{:todo [:todo/by-id id]}                  ;; a map
```

None of these are silently coerced; they [fail loud](glossary.md#fail-loud-not-silent),
because wrong dependency edges are hard to debug. Three errors keep three mistakes
apart: a malformed registration signals `:rf.error/reg-sub-bad-args` at `reg-sub`
time; a bad return value signals `:rf.error/sub-input-fn-bad-return` when the
concrete subscription is first materialised; a throw inside the input function
signals `:rf.error/sub-input-fn-exception`. All three are catalogued in
[Errors and recovery](errors.md).

### Saying things about a sub: metadata

Any `reg-sub` may carry an optional metadata map right after the id, holding
declarations about the subscription rather than its computation:

```clojure
(rf/reg-sub :todo/remaining-count
  {:doc    "How many todos are not done yet, for the footer."
   :schema :int
   :tags   #{:todo}
   :inputs [[:todo/all]]}
  (fn [[todos] _]
    (count (remove :done? todos))))
```

The keys you'll reach for:

1. **`:doc`**: a human-readable description. Optional, but the dev build emits
   `:rf.warning/missing-doc` once per registration that omits it, because tools
   (Xray's sub list, the topology view) show it.
2. **`:schema`**: a [Malli](https://github.com/metosin/malli)
   [schema](glossary.md#schema) for the sub's output. When present, the dev build
   validates the computed value at the `:sub-return` boundary. Because you declared
   it over your own sub, it is [elided](glossary.md#elide) from production. See
   [Validate with schemas](how-to/validate-with-schemas.md).
3. **`:tags`**: a set of keywords for your own grouping and tooling.

Two more keys come from the [data-classification](glossary.md#data-classification)
model, because the observability pipeline captures sub outputs into traces.
`:sensitive` marks paths in the output that hold secrets (`[[]]` marks the whole
output); `:large` marks paths big enough to summarise rather than capture verbatim (a
5,000-row table, a decoded blob):

```clojure
(rf/reg-sub :auth/session-token
  {:doc       "The raw bearer token. Never goes to traces."
   :sensitive [[]]}                       ;; the whole output is sensitive
  (fn [db _] (:auth/token db)))
```

Classification does not propagate: a sub does not inherit its inputs'
`:sensitive`/`:large` declarations, so classify a sensitive derived value at the sub
that produces it ([Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md)).
A malformed declaration is rejected at registration with
`:rf.error/bad-classification`.

### Testing

A subscription's computation is a pure function of its inputs and query vector, so
you don't need a reactive runtime, a DOM, or a browser to test what it computes.
`rf/compute-sub` runs a sub against an app-db value, resolving its declared inputs
for you, on the JVM and with no live cache.
[Test a subscription](testing/subscriptions.md) has the recipe.

### Lifecycle: a sub exists only while something watches

A subscription node is reference-counted. A view derefs `[:todo/visible]`; the cache
materialises the node (computing its whole input chain) and bumps a ref-count. A
second view with the same query vector bumps it again and reads the same cached
value. When a view unmounts, its reference is released. On the last release the slot
is disposed synchronously, in the same tick: the reaction is torn down, its inputs
are released (which can cascade disposal up the chain), and the slot is removed. A
`:rf.sub/dispose` [trace event](glossary.md#trace-event) marks the eviction.

This matters in two everyday ways:

1. **No grace-period timer.** Disposal is immediate, so a sub can't linger,
   recomputing, after its last reader has gone. Re-subscribing after disposal is a
   fresh cache miss against the same body and db, so the value is `=` to what was
   disposed and a remount shows no flicker.
2. **Hot reload and teardown are clean.** Re-registering a sub disposes every cached
   slot for that query, regardless of ref-count, and the next subscribe builds
   against the new body. Destroying a [frame](glossary.md#frame) disposes every slot
   it owns. A `shadow-cljs` reload needs no special handling.

Two functions step outside the deref-driven lifecycle:

- **`rf/subscribe-once`** subscribes, derefs once, unsubscribes and returns the plain
  value. It is a non-reactive read: you get the value as of now and are not
  registered for changes. Use it at the REPL, in tests, and in tools. It takes the
  same `{:frame f}` opts as `subscribe` for reading a named frame from outside any
  scope. Don't call it from an event handler, which would make the handler read
  state it did not declare: when a handler needs a derived value, materialise it
  with a [flow](glossary.md#flow) or declare it as a [coeffect](coeffects.md).
- **`rf/unsubscribe`** decrements the ref-count by hand, for the rare case where you
  took a reference programmatically. Views never call it; mount and unmount do it
  for them.

### The framework's own subs

The framework registers some subscriptions for you, and you read subsystem state
through them exactly as you read your own:

- **`[:rf/machine <machine-id>]`**: a [state machine](../machines/glossary.md#machine)'s
  [snapshot](../machines/glossary.md#snapshot) `{:state :data :tags}`, or `nil` before
  it's initialised. This is how a view reads a machine.
- The router publishes a family (**`:rf/route`**, **`:rf.route/id`**,
  **`:rf.route/params`**, **`:rf.route/query`**, **`:rf.route/transition`**,
  **`:rf.route/chain`**, and more), covered in [Routing](../routing/concepts.md).

Ids under `:rf/…` or `:rf.<subsystem>/…` are reserved for the framework. Keep your
own subs out of those namespaces.

### When a subscription is the wrong tool

Subscriptions serve views. The full chooser is
[Where should this value live?](where-state-lives.md); the common cases:

| Signal | Prefer |
|---|---|
| A **handler** must read the derivation | [flow](flows.md) (materialised into app-db) |
| Value comes from a **server** (cache/stale/refetch) | [resource](../resources/concepts.md); sub over the cache |
| Value has **named stages / lifecycle** | [machine](../machines/concepts.md) |
| Cross-frame read | Don't; frames are isolated ([Frames](frames.md)) |

??? info "Coming from TanStack Query?"

    `useQuery` both fetches and derives. re-frame2 splits those: resources own
    fetch-cache-invalidate; subscriptions derive over state already in hand.

### One derivation graph

Subscriptions are one kind of node on a larger
[derivation graph](glossary.md#the-derivation-graph): flows, resources, route facts
and machine selectors live on it too.
[One graph: derivations and their algebra views](derivations-and-algebra-views.md)
covers the whole family.
