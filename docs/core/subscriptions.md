# Subscriptions

You have [events](events.md) writing one [app-db](app-db.md) map. Views should not
pluck raw paths out of that map by hand — and they should not store conclusions
next to facts. This page is the missing middle.

[App-db](glossary.md#app-db) holds your **facts**. Your
[views](glossary.md#view) want **conclusions** — "the visible articles", "can this
form submit?", "the current user's initials". A
[subscription](glossary.md#subscription) is the thing in between: a named, cached
derivation that turns facts into a conclusion, and re-runs only when it must.

> **A UI is derived data. Subscriptions are how you name and share those
> derivations.**

Through the prune demo you get derive-don't-store, what a subscription is, layered
graphs, and the equality gate — enough to ship. Argument-dependent inputs, metadata,
lifecycle, framework subs, wrong-tool cases, and recovery live under Advanced.

## Don't Store What You Can Derive

Here's the discipline the whole page hangs off.

The [app-db](app-db.md) counter shows a number. Suppose we also want to
show whether that number is odd or even. The tempting move: store a parity flag in
app-db and keep it updated alongside the value.

Don't.

Odd-or-even isn't a new *fact*. It's a *consequence* of a fact you already have.
Two copies of one truth is two chances to disagree. Derive it, live:

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
(rf/reg-sub :parity
  :<- [:value]
  (fn [n _] (if (odd? n) :odd :even)))

(rf/reg-view parity-counter []
  [:div
   [:button {:on-click #(dispatch [:dec])} "−"]
   [:span @(subscribe [:value]) " is " (name @(subscribe [:parity]))]
   [:button {:on-click #(dispatch [:inc])} "+"]])

[rf/frame-root {:id :app :initial-events [[:initialise]]}
 [parity-counter]]
```

You just built a two-cell spreadsheet. `:value` is a cell. `:parity` is a formula
over it. That `:<-` line declares the dependency, and because the framework *knows*
the dependency, `:parity` recomputes only when `:value` changes, and anything
watching `:parity` re-renders only when the *answer* changes.

The spreadsheet is the metaphor for the rest of the page.

## What Is a Subscription, Exactly?

A function from app-db to a value some view wants, registered under a name:

```clojure
(rf/reg-sub :cart/category-filter
  (fn [db _query]
    (:cart/category-filter db)))
```

A view reads it by deref:

```clojure
@(rf/subscribe [:cart/category-filter])
```

The vector `[:cart/category-filter]` is the [**query vector**](glossary.md#query-vector) —
the id, plus any arguments. `[:cart/line-item "sku-1"]` carries one argument, and
the sub destructures it from the same vector it was called with:

```clojure
(rf/reg-sub :cart/line-item
  (fn [db [_ sku]]
    (get-in db [:cart/items sku])))
```

Now, that little `@`. It is doing two jobs, and the second one is the entire trick
of reactive programming, so don't rush past it. Job one: unwrap the reactive
reference to a plain value. Job two: register the deref-ing view as a *dependent*,
so the view re-renders when — and only when — that value changes.

The view declared a dependency and walked away. It never polls. It never listens
to a store-wide "something changed" firehose. It said what it wanted, once, and
the graph does the rest.

Subscribe to an id nobody registered — a typo, a namespace that hasn't loaded —
and re-frame2 does not quietly hand you `nil` and let you guess. It emits
`:rf.error/no-such-sub` (an always-on [error record](glossary.md#error-record)
that survives into production, carrying the offending `:rf.sub/id`) and then
recovers the subscription to `nil` so the view still renders. Loud, then graceful.
And the failed lookup leaves no cache entry behind, so registering the sub later —
boot order, a lazy load — lets the next subscribe build cleanly.

## Why Bother Naming Something So Trivial?

Fair question. `(:cart/category-filter db)` in the view would be shorter. Two
reasons, and they recur all through this framework:

1. **Decoupling.** Where the value lives in app-db is the subscription's secret.
   Move it tomorrow and you change one registration, not forty views.
2. **Sharing.** Every view asking for `[:cart/category-filter]` reads the *same*
   cached node. The cache is keyed by query vector (per
   [frame](glossary.md#frame) — for now, read that as "per app"), so the
   computation runs once per change no matter how many views consume it. The
   forty-first reader costs nothing.

One rookie mistake will quietly defeat reason 2, so hear it now: **don't build a
non-primitive argument inline on every render.** `[:article/by-id "BK-1"]` from a
hundred views is one cache node, because keywords and strings are value-stable. But
`@(subscribe [:report/rows {:cols cols}])` with `{:cols cols}` assembled right
there in the render body mints a *fresh* cache entry every time that map isn't `=`
to the last one — unbounded cache growth, zero hit-rate, and the sub still computes
the right answer, so nothing *looks* wrong. The dev build catches it with a
one-shot `:rf.warning/sub-arg-cache-fragmentation` per sub-id. The fix: hoist the
argument to a value-stable reference — `let`-bound, subscribed, or memoised — so
repeated subscribes share one slot.

Coming from Redux? A subscription is a selector — Reselect's `createSelector` with
the memoisation built in. From Solid or Jotai? A derived signal. Three deliberate
differences: subscriptions are *named* in a registry, so tools can draw the whole
graph without running your app; change detection is deep value equality (`=`),
never reference identity; and dependencies are declared as data, not discovered by
watching a function run.

## Three Layers, One Graph

A subscription's input doesn't have to be app-db. It can be another subscription —
you saw that with `:parity`. Once derivations feed derivations, you have a directed
acyclic graph, and re-frame2 walks it for you.

The graph has layers, and a sub's layer is decided entirely by what it reads:

- **Layer 1 — extractors.** Read app-db directly. Their one job: pluck out a raw
  slice. No computation. They re-run on every app-db change (to check whether
  their slice moved — the next section explains why that's cheap).
- **Layer 2 — derivations.** Read other subs, via `:<-`. Sort, filter, join,
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
(rf/reg-sub :cart/by-price
  :<- [:cart/items]
  (fn [items _]
    (sort-by :price #(compare %2 %1) items)))

;; Layer 3 — composes two subs.
(rf/reg-sub :cart/visible
  :<- [:cart/by-price]
  :<- [:cart/category-filter]
  (fn [[items category] _]
    (if category
      (filterv #(= category (:category %)) items)
      items)))
```

Read `:<-` as "this sub's input comes from". Notice what changed between layers:
`:cart/by-price` does **not** take `db`. It takes the already-extracted value that
`:cart/items` produced. One arrow delivers its input as a bare value; two or more
deliver a vector, destructured above as `[items category]`. That's the only
wrinkle in the syntax. Once you've seen it, you've seen it.

The *shape of the registration* is the *shape of the graph*. `(fn [db _] ...)` is
an extractor by construction. `:<-` is a composer by construction. The framework
can read the registry and know your entire topology as data — which is exactly how
[Xray](glossary.md#xray) draws your subscription graph without executing a single
computation function (`re-frame.subs.tooling/sub-topology` is a literal read of the
registry). Declared dependencies, not discovered ones.

## The Equality Gate

I said the graph is fast without tuning. Here is the entire mechanism. One rule:

> **A subscription's cached value is invalidated only when one of its inputs
> actually changes value — checked with `=`, deep value equality.**

That rule is what makes layered subs cheap. Walk it through.

App-db changes. The layer-1 extractors re-run — all of them, every time, because
app-db is their input. Each one's new output is compared with its previous output
by `=`. If the slice didn't change, the cached value stands and **propagation stops
right there**. Downstream subs don't re-run. Views don't re-render. Nothing past
the unchanged extractor even learns an [event](glossary.md#event) happened.

That makes layer 1 a **circuit breaker** for everything behind it. Change
`:cart/category-filter` and the `:cart/items` extractor re-runs, sees its slice is
`=` to last time, and shuts the gate: the sort in `:cart/by-price` never executes.
And the same gate sits at *every* node — a layer-2 sub that recomputes but produces
an `=` result stops propagation to its dependents too.

You wrote zero `memo`. Zero dependency arrays. You declared what each sub reads,
and got memoisation at every node of the graph for free.

The gate even guards the root. A no-op write — a handler that assocs a key to the
value it already holds — produces an app-db that is `=` to the old one, so
*nothing* recomputes anywhere. You cannot cause a render storm by writing state
that didn't change. Try. You can't.

(Redux veterans: in Reselect, you carry the memoisation discipline yourself — a
selector memoises on *reference* identity, so the moment a reducer returns a
freshly-allocated array that's element-wise identical to the old one, everything
downstream recomputes anyway, and "never allocate unless something changed" becomes
a rule you hold in your head at every reducer, forever. Here, equal values are
equal, however they were allocated. That entire category of bug does not exist.)

One practical rule falls out of the gate, and it's the one to carry away: **keep
extractors tiny — put the work in layer 2.** An extractor fires on every app-db
change; it must be cheap. A `get`, a `get-in`, nothing more. Put a `sort-by` inside
an extractor and that sort runs on every keystroke in every unrelated form — you've
placed expensive work *in front of* the gate instead of behind it. Move it into a
`:<-` sub and it runs only when its slice actually changes. Same code, dramatically
less work. When a view is mysteriously slow, "is there computation in a layer-1
sub?" is the first question [Find and fix a slow view](how-to/fix-a-slow-view.md)
asks — because the answer is so often yes.

Does the gate scale? The [Cells spreadsheet example](../../examples/core/seven_guis/cells)
derives 2,600 mounted cell values from one shared input sub, and the `=` check on
each result means only cells whose displayed value genuinely changed re-render. A
literal spreadsheet, running on the metaphor.

## Watch It Prune

Reading about a circuit breaker is one thing. Watching one branch stay silent while
its neighbour fires is better.

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
(rf/reg-sub :cart/count-label
  :<- [:cart/count]
  (fn [n _query] (str n " item(s) in cart")))

(rf/reg-sub :cart/currency-label
  :<- [:cart/currency]
  (fn [c _query] (str "prices shown in " c)))

(rf/reg-view cart-summary []
  [:div
   [:p @(subscribe [:cart/count-label])]
   [:p @(subscribe [:cart/currency-label])]
   [:button {:on-click #(dispatch [:cart/add-item])} "add item"]])

[rf/frame-root {:id :cart :initial-events [[:cart/initialise]]}
 [cart-summary]]
```

Every add builds a brand-new app-db value, and *both* branches hang off it — yet
only the count line moves. The `:cart/currency` extractor *ran* on every click, but
it produced an `=` value each time, so the gate closed, and `:cart/currency-label`
never recomputed. Change flows exactly as far as values actually move. Not one
node further.

Want a stronger dose? Add a deliberate no-op to the cell —
`(rf/reg-event :cart/restate-currency (fn [{:keys [db]} _] {:db (assoc db :cart/currency "USD")}))`
plus a button that dispatches it — and re-evaluate. Clicking it does nothing,
anywhere. The new app-db is `=` to the old one, so the graph proves nothing changed
and goes back to sleep.

To see the gate's decisions rather than infer them, run this shape in your own app
with [Xray](glossary.md#xray) attached (one-line setup:
[Debug with Xray](../xray/index.md)), click **add item**, select the newest
event row, and open the **Views** tab: `:cart/count-label` is marked as the
re-render's trigger while `:cart/currency-label` sits beside it, unmarked.

You can:

- keep facts in app-db and **derive** conclusions in named subs;
- build a layered graph with extractors (`(fn [db _] …)`) and `:<-` composers;
- rely on the `=` gate so unchanged slices stop propagation;
- avoid cache fragmentation (stable query-vector args);
- recover from a missing sub (`:rf.error/no-such-sub` → `nil`).

That is enough for real screens. The sections below are optional depth.

---

## Advanced

## When the Inputs Depend on the Arguments

Subscriptions take arguments — `@(rf/subscribe [:article/page article-id])` — and
sometimes the *arguments* decide which upstream subs you need. An article page
needs *that* article, *that* article's comments, and the current viewer. `:<-`
can't express this: it lists query vectors literally at registration time, and
`article-id` doesn't exist yet.

For that case, `reg-sub` takes **two functions** — an *input function* and the
computation function:

```clojure
(rf/reg-sub
  :article/page
  ;; input-fn: outer query vector -> a vector of input query vectors
  (fn [[_ article-id]]
    [[:article/by-id article-id]
     [:comments/for-article article-id]
     [:viewer/current]])
  ;; computation-fn: the resolved input values (same order), plus the query vector
  (fn [[article comments viewer] [_ article-id]]
    {:id        article-id
     :article   article
     :comments  comments
     :can-edit? (:edit? viewer)}))
```

The input function answers *what does this sub depend on?* The computation function
answers *what does it compute?* The resolved input values arrive as a vector, in
the order the input function listed them — always a vector in this form, even for
a single input.

The choice between the two forms is sharp. **Use `:<-` for static inputs; reach for
an input function only when the upstream query vectors need values from the outer
query vector.** `:<-` is exactly a constant input function with the boilerplate
removed — and its edges are statically drawable, which the tooling repays.

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

### The Exact Return Grammar

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

## Saying Things About a Sub: Metadata

Any `reg-sub` may carry an optional metadata map right after the id — declarations
*about* the subscription, as opposed to its computation:

```clojure
(rf/reg-sub :user/initials
  {:doc    "The current user's initials, for the avatar badge."
   :schema [:maybe :string]
   :tags   #{:user}}
  :<- [:user/name]
  (fn [name _]
    (->> (clojure.string/split (or name "") #"\s+")
         (map first)
         (clojure.string/join))))
```

The keys you'll reach for:

1. **`:doc`** — a human-readable description. Structurally optional, but the dev
   build warns when you omit it, because tools (Xray's sub list, the topology
   view) surface it. Treat it as a SHOULD.
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

One thing to hear plainly: **classification does not propagate.** A sub does *not*
inherit its inputs' `:sensitive`/`:large` declarations. If a derived value is
sensitive, classify it at the sub that produces it. (The narrative:
[Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).) A malformed
declaration is rejected at registration with `:rf.error/bad-classification`.

## Testing, Briefly

A layer-1/2/3 computation is a pure function of `(inputs, query-v)`. So you don't
need a reactive runtime — or a DOM, or a browser — to test what a subscription
*computes*. `rf/compute-sub` runs a sub's body against an app-db **value**,
resolving the whole `:<-` chain for you, JVM-runnable, no live cache. The recipe,
both styles, gotchas included: [Test a subscription](testing/subscriptions.md).

## Lifecycle: a Sub Exists Only While Something Watches

A subscription node is not a permanent fixture. It's reference-counted. A view
derefs `[:cart/visible]`; the cache materialises the node (computing the whole
input chain) and bumps a ref-count. A second view sharing the query vector bumps it
again and reads the same cached value. A view unmounts; its reference is released.
And on the **last** release — ref-count hits zero — the slot is disposed
**synchronously, in the same tick**: reaction torn down, input ref-counts released
(which can cascade disposal up the chain), slot removed. A `:rf.sub/dispose`
[trace event](glossary.md#trace-event) marks the eviction.

Why should you care? Two everyday ways:

1. **No grace-period timer.** Disposal is immediate on the 1 → 0 edge, so a sub
   can't linger, recomputing pointlessly, after its last reader has gone. And
   re-subscribing after disposal is just a fresh cache miss that rebuilds against
   the registered body — same body, same db, so the value is `=` to what was
   disposed, and a remount observes no flicker.
2. **Hot-reload and teardown are clean.** Re-registering a sub disposes every
   cached slot for that query, regardless of ref-count — the next subscribe builds
   against the new body. Destroying a [frame](glossary.md#frame) disposes every
   slot it owns. Correct behaviour across a `shadow-cljs` reload, no thought
   required.

Two functions step outside the deref-driven lifecycle on purpose:

- **`rf/subscribe-once`** — subscribe, deref once, immediately unsubscribe, return
  the plain value. A **non-reactive** read: you get the value as of now, and you
  are *not* registered for changes. Right for a one-shot read in a REPL, or a
  handler that genuinely needs a derived value once. (Takes the same
  `{:frame f}` opts as `subscribe` for reading a named frame from outside any
  scope.) If you reach for it *routinely* from handlers, the value probably wants
  to be a [flow](glossary.md#flow) instead — see below.
- **`rf/unsubscribe`** — decrement the ref-count by hand, for the rare case where
  you took a reference programmatically. Views never call this; mount/unmount does
  it for them.

## The Framework's Own Subs

The framework registers a handful of subscriptions for you, and you read subsystem
state through them exactly as you read your own:

- **`[:rf/machine <machine-id>]`** — a [state machine](../machines/glossary.md#machine)'s
  [snapshot](../machines/glossary.md#snapshot) `{:state :data}`, or `nil` before
  it's initialised. The canonical way to drive a view off a machine.
- The router publishes a family — **`:rf/route`**, **`:rf.route/id`**,
  **`:rf.route/params`**, **`:rf.route/query`**, **`:rf.route/transition`**,
  **`:rf.route/chain`**, and more — covered in [Routing](../routing/concepts.md).

Anything under `:rf/…` or `:rf.<subsystem>/…` is framework-owned, per the reserved-
namespace convention. Keep your subs out of that namespace and the two never collide.

## When a Subscription Is the Wrong Tool

Subscriptions are view-facing and pull-based. Local stops only — the full chooser
is [Where should this value live?](where-state-lives.md):

| Signal | Prefer |
|---|---|
| A **handler** must read the derivation | [flow](flows.md) (materialised into app-db) |
| Value comes from a **server** (cache/stale/refetch) | [resource](../resources/concepts.md); sub over the cache |
| Value has **named stages / lifecycle** | [machine](../machines/concepts.md) |
| Cross-frame read | Don't — frames are isolated ([Frames](frames.md)) |

??? info "Coming from TanStack Query?"

    `useQuery` both fetches and derives. re-frame2 splits those: resources own
    fetch-cache-invalidate; subscriptions derive over state already in hand.

## Troubleshooting

Three corners you can skip until something goes sideways — then you'll want them.

**A computation throws.** A `nil` where you assumed a map; a divide-by-zero in a
derived total. re-frame2 treats it as a [fail-loud](glossary.md#fail-loud-not-silent)
event, not a crash: it emits `:rf.error/sub-exception` and recovers the sub to
`nil`, so the throw can't take down the render. The record is always-on — it
reaches your production error listeners — and its `:where` tag names the path that
threw: `:reactive` for the live cache path, `:compute-sub` for the pure test/SSR
path. SSR runs on that same pure path, so a sub that throws during a server render
lets the server fail closed with a real error response instead of shipping HTML
built from `nil`s. Catalogue entry: [Errors and recovery](errors.md).

**A schema'd sub computes the wrong shape.** The runtime validates *after* the
body runs, at the `:sub-return` boundary. On a mismatch it emits
`:rf.error/schema-validation-failure` with `:where :sub-return` and surfaces `nil`
to the consumer — the same recover-to-`nil` posture as a throw — so a derivation
producing the wrong shape is caught at the sub that produced it, not three layers
downstream where some view chokes on it. A strict mode re-raises, for CI. The
whole `:sub-return` boundary is [elided](glossary.md#elide) from production — a
sub's `:schema` is a claim you make about a derivation you wrote, and a release
build takes you at your word. (That is a fact about *this* boundary, not about
schema checking at large: the checks the framework relies on to keep its own
promises hold in every build. [What goes and what
stays](how-to/validate-with-schemas.md#in-production-what-goes-what-stays).)

**Subscribing during teardown.** A stray async callback fires after its
`frame-provider` unmounted; a hot-reload race. The subscribe returns `nil` —
fail-safe — while emitting a production-survivable `:rf.error/frame-destroyed`
carrying the frame id and the attempted query vector, so a genuine
use-after-destroy bug stays visible on the stream you actually watch. (A
*rootless* subscribe — issued under no frame scope at all — is the different
`:rf.error/no-frame-context`; see
[frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found).)

---

One last widening of the lens. Subscriptions are one face of a larger family:
flows, resources, route facts, and machine selectors all live on
[one derivation graph](glossary.md#the-derivation-graph), with one shared
algebra. The essay-length tour is
[One graph: derivations and their algebra views](derivations-and-algebra-views.md).

Derived data, all the way down — and now you can read any registration on the
graph and say exactly when it recomputes.
