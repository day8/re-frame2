# Subscriptions

[App-db](glossary.md#app-db) holds your **facts**. Your
[views](glossary.md#view) want **conclusions**: "the visible articles", "can this
form submit?", "the current user's initials". A
[subscription](glossary.md#subscription) sits in between: a named, cached
derivation that turns facts into a conclusion and re-runs only when its inputs
change.

## Don't Store What You Can Derive

The [app-db](app-db.md) counter shows a number. Suppose we also want to show whether
that number is odd or even. The tempting move is to store a parity flag in app-db and
update it alongside the value. Don't: odd-or-even is a *consequence* of a fact you
already have, and a stored copy is one more thing every handler must keep in step.
Derive it instead:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :initialise
  (fn [{:keys [db]} _] {:db (assoc db :value 5)}))

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

[rf/frame-root {:id :app :initial-events [[:initialise]]}
 [parity-counter]]
```

This is a two-cell spreadsheet. `:value` is a cell and `:parity` is a formula over
it. The `:inputs` line declares the dependency, so `:parity` recomputes only when
`:value` changes, and a view reading `:parity` re-renders only when the *answer*
changes.

## What Is a Subscription, Exactly?

A function from app-db to a value some view wants, registered under a name:

```clojure
(rf/reg-sub :cart/category-filter
  (fn [db _query]
    (:cart/category-filter db)))
```

A view reads it with the `subscribe` that `reg-view` provides, and a deref:

```clojure
@(subscribe [:cart/category-filter])
```

The vector `[:cart/category-filter]` is the [**query vector**](glossary.md#query-vector):
the id, plus any arguments. `[:cart/quantity "sku-1"]` carries one argument, and
the sub destructures it from the same vector it was called with:

```clojure
(rf/reg-sub :cart/quantity
  (fn [db [_ sku]]
    (get-in db [:cart/quantities sku])))
```

The `@` does two jobs. It unwraps the reactive reference to a plain value, and it
registers the view as a *dependent* of that value, so the view re-renders when, and
only when, the value changes. The view never polls and never listens to a store-wide
"something changed" signal.

If you subscribe to an id nobody registered (a typo, a namespace that hasn't loaded),
re-frame2 emits `:rf.error/no-such-sub`, an always-on
[error record](glossary.md#error-record) that survives into production and carries
the offending `:rf.sub/id`. The subscription then yields `nil` so the view still
renders. The failed lookup leaves no cache entry behind, so registering the sub later
(boot order, a lazy load) lets the next subscribe build cleanly.

## Why Bother Naming Something So Trivial?

`(:cart/category-filter db)` in the view would be shorter, but a view has no `db` to
read, and naming the read buys two things:

1. **Decoupling.** Where the value lives in app-db is the subscription's secret.
   Move it tomorrow and you change one registration, not forty views.
2. **Sharing.** Every view asking for `[:cart/category-filter]` reads the *same*
   cached node. The cache is keyed by query vector (per
   [frame](glossary.md#frame) — for now, read that as "per app"), so the
   computation runs once per change no matter how many views consume it.

Query vectors are compared by value, so an argument built inline on every render, such as
`@(subscribe [:report/rows {:cols cols}])`, shares one cache node for as long as it
stays `=` to the last one. An argument that is never `=` to anything else, usually a
freshly built closure, misses the cache every time.

??? info "Coming from Redux, Solid or Jotai?"

    A subscription is a selector (Reselect's `createSelector` with the memoisation
    built in), or a derived signal. The differences: subscriptions are *named* in a
    registry, so tools can draw the whole graph without running your app; change
    detection is deep value equality (`=`) rather than reference identity; and
    dependencies are declared as data rather than discovered by watching a function
    run.

## Three Layers, One Graph

A subscription's input doesn't have to be app-db. It can be another subscription —
you saw that with `:parity`. Once derivations feed derivations, you have a directed
acyclic graph, and re-frame2 keeps it up to date for you.

The graph has layers, and a sub's layer is decided entirely by what it reads:

- **Layer 1 — extractors.** Read app-db directly. Their one job: pluck out a raw
  slice. No computation. They re-run on every app-db change (to check whether
  their slice moved — the next section explains why that's cheap).
- **Layer 2 — derivations.** Read other subs, declared under `:inputs`. Sort, filter, join,
  shape. They re-run when an input's value changes by `=`.
- **Layer 3 and up — compositions.** Subs over subs over subs. Same rule.

Here's a three-layer chain from a shopping cart:

```clojure
;; Layer 1 — extractors: read app-db, pluck a slice, nothing else.
(rf/reg-sub :cart/items
  (fn [db _] (:cart/items db)))

(rf/reg-sub :cart/category-filter
  (fn [db _] (:cart/category-filter db)))

;; Layer 2 — reads :cart/items (a sub), never app-db.
(rf/reg-sub :cart/by-price {:inputs [[:cart/items]]}
  (fn [[items] _]
    (sort-by :price #(compare %2 %1) items)))

;; Layer 3 — composes two subs.
(rf/reg-sub :cart/visible {:inputs [[:cart/by-price] [:cart/category-filter]]}
  (fn [[items category] _]
    (if category
      (filterv #(= category (:category %)) items)
      items)))
```

Read `:inputs` as "this sub's inputs come from". Notice what changed between layers:
`:cart/by-price` does **not** take `db`. It takes the already-extracted value that
`:cart/items` produced. Declared inputs always arrive as a **vector**, in the order
you listed them — `[items]` for one, `[items category]` for two — so adding a
second input never reshapes the body you already wrote.

The registration determines the graph. `(fn [db _] ...)` with no `:inputs` is an
extractor; `:inputs` makes a composer. Because the dependencies are data in the
registry, [Xray](glossary.md#xray) can draw your subscription graph without running a
single computation function (`re-frame.subs.tooling/sub-topology` reads it straight
from the registry).

## The Equality Gate

One rule keeps the graph cheap: a subscription's cached value is invalidated only
when one of its inputs changes value, checked with `=` (deep value equality).

Walk it through. App-db changes. The layer-1 extractors re-run, all of them, because
app-db is their input. Each one's new output is compared with its previous output
by `=`. If the slice didn't change, the cached value stands and propagation stops
there: downstream subs don't re-run and views don't re-render.

So layer 1 shields everything behind it. Change
`:cart/category-filter` and the `:cart/items` extractor re-runs, sees its slice is
`=` to last time, and stops there: the sort in `:cart/by-price` never executes.
The same check sits at *every* node, so a layer-2 sub that recomputes but produces an
`=` result stops propagation to its dependents too. You get memoisation at every node
without writing `memo` or dependency arrays.

The check applies to app-db itself. A no-op write, such as a handler that assocs a
key to the value it already holds, produces an app-db that is `=` to the old one, so
nothing recomputes. Writing state that didn't change cannot cause a render storm.

??? info "Coming from Reselect?"

    Reselect memoises on *reference* identity, so a reducer that returns a freshly
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

## Watch It Prune

The cell below is self-contained: two independent app-db slices, one extractor and
one derivation per branch, one view reading both. Click into it, press
**`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to evaluate, then click **add item** a
few times:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :cart/initialise
  (fn [{:keys [db]} _event]
    {:db (assoc db
                :cart/count    0       ;; this slice will change
                :cart/currency "USD")})) ;; this one never does

(rf/reg-event :cart/add-item
  (fn [{:keys [db]} _event] {:db (update db :cart/count inc)}))

;; Layer 1 — one tiny extractor per slice.
(rf/reg-sub :cart/count    (fn [db _query] (:cart/count db)))
(rf/reg-sub :cart/currency (fn [db _query] (:cart/currency db)))

;; Layer 2 — one derivation per branch.
(rf/reg-sub :cart/count-label {:inputs [[:cart/count]]}
  (fn [[n] _query] (str n " item(s) in cart")))

(rf/reg-sub :cart/currency-label {:inputs [[:cart/currency]]}
  (fn [[c] _query] (str "prices shown in " c)))

(rf/reg-view cart-summary []
  [:div
   [:p @(subscribe [:cart/count-label])]
   [:p @(subscribe [:cart/currency-label])]
   [:button {:on-click #(dispatch [:cart/add-item])} "add item"]])

[rf/frame-root {:id :cart :initial-events [[:cart/initialise]]}
 [cart-summary]]
```

Every click builds a new app-db value, and *both* branches hang off it, yet only the
count line changes. The `:cart/currency` extractor *ran* on every click, but it
produced an `=` value each time, so `:cart/currency-label` never recomputed.

To see the no-op case, add this event to the cell,
`(rf/reg-event :cart/restate-currency (fn [{:keys [db]} _] {:db (assoc db :cart/currency "USD")}))`
plus a button that dispatches it, and re-evaluate. Clicking it changes nothing: the
new app-db is `=` to the old one, so no subscription recomputes.

To see these decisions rather than infer them, run the same shape in your own app
with [Xray](glossary.md#xray) attached ([Debug with Xray](../xray/index.md)), click
**add item**, select the newest event row, and open the **Views** tab:
`:cart/count-label` is marked as the re-render's trigger while `:cart/currency-label`
sits beside it, unmarked.

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
test/SSR path. Because SSR uses that pure path, a sub that throws during a server
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

### When the Inputs Depend on the Arguments

Subscriptions take arguments — `@(subscribe [:article/page article-id])` — and
sometimes the *arguments* decide which upstream subs you need. An article page
needs *that* article, *that* article's comments, and the current viewer. A literal
`:inputs` vector can't express this: it lists query vectors at registration time,
and `article-id` doesn't exist yet.

For that case, give `:inputs` a **function** instead of a vector — an *input
function*, which the runtime calls with the outer query vector:

```clojure
(rf/reg-sub
  :article/page
  ;; :inputs as a producer: outer query vector -> a vector of input query vectors
  {:inputs (fn [[_ article-id]]
             [[:article/by-id article-id]
              [:comments/for-article article-id]
              [:viewer/current]])}
  ;; computation-fn: the resolved input values (same order), plus the query vector
  (fn [[article comments viewer] [_ article-id]]
    {:id        article-id
     :article   article
     :comments  comments
     :can-edit? (:edit? viewer)}))
```

The input function answers *what does this sub depend on?* The computation function
answers *what does it compute?* Nothing about the body changes — the resolved input
values arrive as a vector, in declaration order, exactly as they do for a literal
`:inputs`.

**Write the literal vector whenever the edges don't depend on the outer query
vector; use a function only when they do.** A literal is a constant input function
without the boilerplate, and tools can draw its edges without running anything.

Notes, in descending order of how often each one bites:

1. **The input function returns *data*, not live subs.** Query vectors, never
   `subscribe` calls. Pure over the query vector: no app-db deref, no dispatch, no
   IO. The runtime does the subscribing. And a single input is still a *vector of
   one query vector* — `[[:item/by-id id]]`, not `[:item/by-id id]`. The scalar
   shape is rejected because `[:x :y]` is ambiguous: one query with an argument, or
   two inputs? re-frame2 refuses to guess.
2. **It is not on the hot path.** It runs once, when a concrete query vector like
   `[:article/page :a1]` is first materialised. From then on that entry is an
   ordinary cached node; `[:article/page :a2]` is a separate entry with its own
   inputs.
3. **Dependencies cannot come from app-db.** A sub whose edges changed with state
   would break disposal, hot reload, and Xray's topology view. When the parameter
   you need lives in app-db, read it at the call site and thread it through the
   query vector — the dynamism lives at the view boundary, where mount/unmount
   already manages lifecycle:

```clojure
(rf/reg-view article-pane []
  (let [article-id @(subscribe [:current-route/article-id])
        page       @(subscribe [:article/page article-id])]
    ...))
```

#### The Exact Return Grammar

This is the one corner of `reg-sub` with a strict shape, so here it is in one
place. An input function **must** return a vector, and **every element** must
itself be a query vector — a vector whose head is a keyword:

```clojure
;; Accepted
[[:article/by-id id] [:viewer/current]]   ;; multiple inputs
[[:item/by-id id]]                         ;; a single input — still a vector OF query vectors
[]                                         ;; no inputs (unusual, but valid)

;; Rejected — each signals :rf.error/sub-input-fn-bad-return
:viewer/current                            ;; a bare keyword
[:article/by-id id]                        ;; a scalar query vector (ambiguous: arg vs two inputs)
[[:article/by-id id] :viewer]              ;; a mix of query vector and bare keyword
{:article [:article/by-id id]}             ;; a map
```

None of these are silently coerced — they
[fail loud](glossary.md#fail-loud-not-silent), because a typo that quietly
produced the wrong dependency edges would cost you an afternoon. Three distinct
errors keep three distinct mistakes apart: a malformed registration shape signals
`:rf.error/reg-sub-bad-args` at `reg-sub` time; a bad return value signals
`:rf.error/sub-input-fn-bad-return` when the concrete subscription is first
materialised; a throw inside the input function signals
`:rf.error/sub-input-fn-exception`. All three are catalogued in
[Errors and recovery](errors.md).

### Saying Things About a Sub: Metadata

Any `reg-sub` may carry an optional metadata map right after the id — declarations
*about* the subscription, as opposed to its computation:

```clojure
(require '[clojure.string :as str])

(rf/reg-sub :user/initials
  {:doc    "The current user's initials, for the avatar badge."
   :schema [:maybe :string]
   :tags   #{:user}
   :inputs [[:user/name]]}
  (fn [[name] _]
    (->> (str/split (or name "") #"\s+")
         (map first)
         (str/join))))
```

The keys you'll reach for:

1. **`:doc`** — a human-readable description. Optional, but the dev build emits
   `:rf.warning/missing-doc` once per registration that omits it, because tools
   (Xray's sub list, the topology view) show it.
2. **`:schema`** — a [Malli](https://github.com/metosin/malli)
   [schema](glossary.md#schema) for the sub's **output**. When present, the dev
   build validates the computed value at the `:sub-return` boundary — a fail-loud
   guard, and one you declared over your own sub, so it is
   [elided](glossary.md#elide) from production.
   The full story: [Validate with schemas](how-to/validate-with-schemas.md).
3. **`:tags`** — a set of keywords for your own grouping and tooling.

Two more keys come from the [data-classification](glossary.md#data-classification)
model, and they exist because the observability pipeline captures sub outputs into
traces. **`:sensitive`** marks paths in the output that hold secrets (`[[]]` marks
the whole output); **`:large`** marks paths big enough to summarise rather than
capture verbatim (a 5,000-row table, a decoded blob):

```clojure
(rf/reg-sub :auth/session-token
  {:doc       "The raw bearer token — never goes to traces."
   :sensitive [[]]}                       ;; the whole output is sensitive
  (fn [db _] (:auth/token db)))
```

**Classification does not propagate.** A sub does *not*
inherit its inputs' `:sensitive`/`:large` declarations. If a derived value is
sensitive, classify it at the sub that produces it. (The narrative:
[Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).) A malformed
declaration is rejected at registration with `:rf.error/bad-classification`.

### Testing, Briefly

A layer-1/2/3 computation is a pure function of `(inputs, query-v)`. So you don't
need a reactive runtime — or a DOM, or a browser — to test what a subscription
*computes*. `rf/compute-sub` runs a sub's body against an app-db **value**,
resolving the whole declared dependency graph for you, JVM-runnable, no live cache. The recipe,
both styles, gotchas included: [Test a subscription](testing/subscriptions.md).

### Lifecycle: a Sub Exists Only While Something Watches

A subscription node is not a permanent fixture. It's reference-counted. A view
derefs `[:cart/visible]`; the cache materialises the node (computing the whole
input chain) and bumps a ref-count. A second view sharing the query vector bumps it
again and reads the same cached value. A view unmounts; its reference is released.
And on the **last** release — ref-count hits zero — the slot is disposed
**synchronously, in the same tick**: reaction torn down, input ref-counts released
(which can cascade disposal up the chain), slot removed. A `:rf.sub/dispose`
[trace event](glossary.md#trace-event) marks the eviction.

This matters in two everyday ways:

1. **No grace-period timer.** Disposal is immediate on the 1 → 0 edge, so a sub
   can't linger, recomputing pointlessly, after its last reader has gone. And
   re-subscribing after disposal is just a fresh cache miss that rebuilds against
   the registered body — same body, same db, so the value is `=` to what was
   disposed, and a remount observes no flicker.
2. **Hot-reload and teardown are clean.** Re-registering a sub disposes every
   cached slot for that query, regardless of ref-count — the next subscribe builds
   against the new body. Destroying a [frame](glossary.md#frame) disposes every
   slot it owns. A `shadow-cljs` reload therefore needs no special handling.

Two functions step outside the deref-driven lifecycle on purpose:

- **`rf/subscribe-once`** — subscribe, deref once, immediately unsubscribe, return
  the plain value. A **non-reactive** read: you get the value as of now, and you
  are *not* registered for changes. Use it at the REPL, in tests, and in tools.
  It takes the same `{:frame f}` opts as `subscribe` for reading a named frame
  from outside any scope. Don't call it from an event handler, which would make
  the handler read state it did not declare: when a handler needs a derived
  value, materialise it with a [flow](glossary.md#flow) (see below) or declare it
  as a [coeffect](coeffects.md).
- **`rf/unsubscribe`** — decrement the ref-count by hand, for the rare case where
  you took a reference programmatically. Views never call this; mount/unmount does
  it for them.

### The Framework's Own Subs

The framework registers a handful of subscriptions for you, and you read subsystem
state through them exactly as you read your own:

- **`[:rf/machine <machine-id>]`** — a [state machine](../machines/glossary.md#machine)'s
  [snapshot](../machines/glossary.md#snapshot) `{:state :data :tags}`, or `nil` before
  it's initialised. The canonical way to drive a view off a machine.
- The router publishes a family — **`:rf/route`**, **`:rf.route/id`**,
  **`:rf.route/params`**, **`:rf.route/query`**, **`:rf.route/transition`**,
  **`:rf.route/chain`**, and more — covered in [Routing](../routing/concepts.md).

Ids under `:rf/…` or `:rf.<subsystem>/…` are reserved for the framework. Keep your
own subs out of those namespaces.

### When a Subscription Is the Wrong Tool

Subscriptions serve views. The full chooser is
[Where should this value live?](where-state-lives.md); the common cases:

| Signal | Prefer |
|---|---|
| A **handler** must read the derivation | [flow](flows.md) (materialised into app-db) |
| Value comes from a **server** (cache/stale/refetch) | [resource](../resources/concepts.md); sub over the cache |
| Value has **named stages / lifecycle** | [machine](../machines/concepts.md) |
| Cross-frame read | Don't — frames are isolated ([Frames](frames.md)) |

??? info "Coming from TanStack Query?"

    `useQuery` both fetches and derives. re-frame2 splits those: resources own
    fetch-cache-invalidate; subscriptions derive over state already in hand.

### One derivation graph

Subscriptions are one kind of node on a larger
[derivation graph](glossary.md#the-derivation-graph): flows, resources, route facts
and machine selectors live on it too.
[One graph: derivations and their algebra views](derivations-and-algebra-views.md)
covers the whole family.
