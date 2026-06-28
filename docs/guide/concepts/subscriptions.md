# Subscriptions: the derivation graph

**[App-db](../glossary.md#app-db) stores facts; subscriptions derive conclusions.** App-db is your app's single state map, and your [views](../glossary.md#view) never read it directly. Instead they ask, by name, for a conclusion — "the visible articles", "can this form submit?", "the current user's initials" — and a [subscription](../glossary.md#subscription) is that question answered: a named, cached derivation that turns state into the value a view wants. Those derivations form a graph rooted at app-db, with your views hanging off the leaves. The nice part is that re-frame2 recomputes only along the paths where values actually changed.

This page builds that graph one concept at a time: a single named derivation first, then chaining derivations into a graph, then the one rule that makes it fast, then parametric inputs, metadata, testing, and lifecycle. By the end you'll read a subscription's registration and know exactly when it recomputes.

## A subscription is a named derivation

At bottom, a subscription is just a function from app-db to a value some view wants. That's the whole idea. You register it under a keyword id:

```clojure
(rf/reg-sub :cart/category-filter
  (fn [db _query]
    (:cart/category-filter db)))
```

A view reads the current value by deref-ing the subscription:

```clojure
@(rf/subscribe [:cart/category-filter])
```

> **Gotcha — a wrong sub-id fails loud, not silent.** Subscribe to an id that was never registered — a typo, a not-yet-loaded namespace — and re-frame2 doesn't quietly hand back `nil` and leave you guessing. It emits `:rf.error/no-such-sub` (an always-on [error record](../glossary.md#error-record) that survives production, carrying the offending `:rf.sub/id`) and recovers the reaction to `nil` so the view still renders. The same id appearing as a `:<-` input of another sub reports the same way. Because the miss isn't cached, registering the sub later — boot order, a lazy load — lets the next subscribe build cleanly against the real body.

The vector `[:cart/category-filter]` is the [**query vector**](../glossary.md#query-vector): the id plus any arguments. `[:cart/line-item "sku-1"]` carries one argument. The whole vector arrives as the computation function's second argument — named `_query` above, where the leading underscore is the Clojure convention for "a parameter I'm deliberately ignoring." This sub takes no arguments, so it ignores the query vector entirely.

That little `@` is doing two jobs at once. It unwraps the reactive reference to a plain value, and it registers the deref-ing view as a dependent, so the view re-renders when — and only when — that value changes. The view declared a dependency and walked away. It never polls, and it never listens to a store-wide "something changed" firehose.

So why name a derivation this trivial instead of just writing `(:cart/category-filter db)` in the view? Two reasons, and they recur everywhere in this framework:

- **Decoupling.** Where the value lives in app-db is the subscription's secret. Move it tomorrow and you change one registration, not forty views.
- **Sharing.** Every view asking for `[:cart/category-filter]` reads the *same* cached node. The subscription cache is keyed by query vector (per [frame](../glossary.md#frame) — an isolated running instance with its own app-db and subscription cache; for now read that as "per app"), so a computation runs once per change no matter how many views consume it. Adding the forty-first reader costs nothing.

Both reasons get stronger the moment derivations start feeding each other, which is the actual design.

> **Gotcha — don't rebuild a non-primitive argument inline every render.** Sharing works because the cache keys on the query vector. An id plus keyword/string/number args is value-stable, so `[:article/by-id "BK-1"]` from a hundred views is one cache node. But if you pass a *fresh* map, set, or collection assembled in the render body — `@(subscribe [:report/rows {:cols cols}])` where `{:cols cols}` is built right there each time — you risk minting a new cache entry per render instead of reusing one: unbounded growth, zero hit-rate, and the sub still computes the right value so nothing looks wrong. The dev build catches this with a one-shot `:rf.warning/sub-arg-cache-fragmentation` per sub-id. The fix is to hoist the argument to a value-stable reference — a `let`-bound, subscribed, or memoised value — so repeated subscribes share one slot.

> **Coming from Redux?** A subscription is a selector — Reselect's `createSelector` with the memoisation built in. **Coming from Solid or Jotai?** It's a derived signal / derived atom. Three deliberate divergences from both: subscriptions are named by keyword in a registry, so tools can draw the whole graph without running your app; change detection is deep value equality (`=`), never reference identity, so there is no "don't allocate a new object or you'll bust the memo" dance; and dependencies are declared as data, not discovered by tracking a function run.

## Three layers, one graph

A subscription's input doesn't have to be app-db. It can be **another subscription**. Once derivations feed derivations you have a directed acyclic graph, and re-frame2 walks it for you. That graph has layers, and a subscription's layer is decided entirely by what it reads:

| Layer | What it reads | Its one job | Recomputes when |
|---|---|---|---|
| **Layer 1 — extractors** | app-db directly | Pluck out a raw slice. No computation. | Every app-db change (to re-check whether their slice moved — see [The equality gate](#the-equality-gate)). |
| **Layer 2 — derivations** | Other subs, via `:<-` | Sort, filter, join, shape. | An input sub's value changes by `=`. |
| **Layer 3+ — compositions** | Other subs, some of them layer 2 | Compose derivations of derivations. | An input sub's value changes by `=`. |

Layer 1 reaches into the map. Everybody else reaches into layer 1, or into each other. Here is a three-layer chain from a shopping cart:

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

The `:<-` arrow reads as "this sub's input comes from". Notice what changed between layers. `:cart/by-price` does **not** take `db`. It takes the already-extracted value that `:cart/items` produced. One arrow delivers that input as a bare value; two or more deliver a vector, destructured above as `[items category]`. That's the only wrinkle in the syntax, and once you've seen it you've seen it.

Here's the part worth pausing on: the shape of the registration *is* the topology. `(fn [db _] ...)` makes an extractor by construction; `:<-` makes a composer by construction. The framework reads the registry and knows the whole graph as data. That is how [Xray](../glossary.md#xray) can draw your subscription topology without executing a single computation function — the static-graph projection it reads, `re-frame.subs.tooling/sub-topology`, is a literal read of the registry that never runs your bodies.

> **Going deeper.** That "the registration *is* the topology" property is what lets the whole subscription layer be treated as data rather than as opaque closures. Each `:<-` edge is a static arrow in a DAG; the layer of a node is just the longest path back to app-db. Because the edges are declared rather than discovered at run time, the graph is a *value* you can analyse, draw, diff, and reason about without evaluation — the same move that makes [flows, resources, route facts, and machine selectors](../glossary.md#the-derivation-graph) compose on one shared graph with one shared algebra. The whole derivation family is one essay; this page is the subscriptions-shaped slice of it.

## The equality gate

I said the graph is fast without tuning. Here is the entire mechanism, one rule:

> **A subscription's cached value is invalidated only when one of its inputs actually changes value — checked with `=`, deep value equality.**

When app-db changes, the layer-1 extractors re-run. They read app-db, so every change makes them re-check. Then each extractor's new output is compared with its previous output by `=`. If the slice didn't change, the cached value stands and **propagation stops right there**. Downstream layer-2 subs don't re-run, views don't re-render, and nothing past the unchanged extractor even learns that an [event](../glossary.md#event) happened.

That makes layer 1 a **circuit breaker** for everything behind it. Change `:cart/category-filter` and the `:cart/items` extractor re-runs, sees its slice is `=` to last time, and shuts the gate — so the sort in `:cart/by-price` never executes. The same gate sits at every node: a layer-2 sub that recomputes but produces an `=` result stops propagation to *its* dependents too. You wrote zero `memo` and zero dependency arrays; you declared what each sub reads and got memoisation at every node for free. It works in reverse, too. A no-op write — a handler that assocs a key to the value it already holds — produces an app-db that is `=` to the old one, so *nothing* recomputes anywhere. You cannot cause a render storm by writing state that didn't change.

> **Coming from Redux?** In Reselect you carry the memoisation discipline yourself: a selector memoises on *reference* identity, so the moment a reducer returns a freshly-allocated array that's element-wise identical to the old one, every downstream selector and component recomputes anyway. The fix is to never allocate unless something changed — a rule you must hold in your head at every reducer. re-frame2 compares by value (`=`), so that whole category of "I accidentally busted the memo" bug doesn't exist. Equal values are equal, however they were allocated.

One practical rule falls out of all this, and it's the one to carry away:

> **Keep extractors tiny — put the work in layer 2.** Extractors run on every app-db change. They're the circuit breakers, so they must fire to decide whether to propagate, which means an extractor has to be cheap: a `get`, a `get-in`, nothing more. Put a `sort-by` inside an extractor and that sort runs on every keystroke in every unrelated form — you've placed expensive work *before* the gate instead of behind it. Move it into a `:<-` sub and it runs only when the extracted slice actually changes. Same code, dramatically less work. And when a view is mysteriously slow, "is there computation in a layer-1 sub?" is the first question [Find and fix a slow view](../how-to/fix-a-slow-view.md) asks.

The gate scales further than you'd guess. The [Cells spreadsheet example](../../../examples/core/seven_guis/cells) derives 2,600 mounted cell values from one shared input sub. The `=` check on each result means only cells whose displayed value genuinely changed re-render: correct propagation with no hand-maintained dependency edges at all.

## Watch it prune

Reading about a circuit breaker is one thing; watching one branch stay silent while its neighbour fires is better. Drop this into your app. It's self-contained: two independent app-db slices, one extractor and one derivation per branch, one view reading both.

A few names appear below that this is the first concept page to use, so here they are in one breath. An [event](../glossary.md#event) is an inert data vector you [`dispatch`](../glossary.md#dispatch) to change state; [`reg-event`](../glossary.md#event-handler) registers the [event handler](../glossary.md#event-handler) that processes it and returns the next app-db. [`reg-view`](../glossary.md#view) registers a view and injects the frame-aware `dispatch` and `subscribe` locals its body calls (that's why they appear unqualified inside the `reg-view` body — [Views](views.md) tells that story). None of these are the subject of this page; they're the scaffolding that lets you watch the gate work.

```clojure
(rf/reg-event :cart/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db
                :cart/count    0       ;; this slice will change
                :cart/currency "USD")})) ;; this one never does

(rf/reg-event :cart/add-item
  (fn [{:keys [db]} _] {:db (update db :cart/count inc)}))

;; Layer 1 — one tiny extractor per slice.
(rf/reg-sub :cart/count    (fn [db _] (:cart/count db)))
(rf/reg-sub :cart/currency (fn [db _] (:cart/currency db)))

;; Layer 2 — one derivation per branch.
(rf/reg-sub :cart/count-label
  :<- [:cart/count]
  (fn [n _] (str n " item(s) in cart")))

(rf/reg-sub :cart/currency-label
  :<- [:cart/currency]
  (fn [c _] (str "prices shown in " c)))

(rf/reg-view cart-summary []
  [:div
   [:p @(subscribe [:cart/count-label])]
   [:p @(subscribe [:cart/currency-label])]
   [:button {:on-click #(dispatch [:cart/add-item])} "add item"]])
```

Seed it once at boot — `(rf/dispatch-sync [:cart/initialise])` next to your app's existing init — and mount `[cart-summary]` somewhere visible. (`dispatch-sync` is the synchronous sibling of `dispatch`: it runs the event immediately rather than enqueuing it, so app-db is seeded before the first paint.)

Now **observe**. With Xray attached (the one-line setup is in [Debug with Xray](../how-to/debug-with-xray.md)), click **add item** a few times, select the newest event row, and open the **Views** tab. It lists each view that re-rendered in that cascade, and under it the subscriptions the view read:

- `:cart/count-label` is marked as the trigger. Its value changed since the last cascade, which is why `cart-summary` re-rendered.
- `:cart/currency-label` sits beside it unmarked. It never recomputed. Its extractor `:cart/currency` *ran* — every extractor re-checks on every app-db change — but produced an `=` value, so the gate closed and the currency branch never woke up.

Every add is a brand-new app-db value, and both branches are attached to it. The difference between them is the gate. Change flows exactly as far as values actually move, and not one node further.

> **Try it.** Register a deliberate no-op — `(rf/reg-event :cart/restate-currency (fn [{:keys [db]} _] {:db (assoc db :cart/currency "USD")}))` — give it a button, and dispatch it. The event row appears and the cascade ends immediately: app-db is `=` to before, so nothing recomputed and nothing re-rendered. The graph proved nothing changed and went back to sleep.

## Parametric inputs: the two-function form

Subscriptions take arguments — `@(rf/subscribe [:article/page article-id])` — and sometimes the *arguments* decide which upstream subs you need. An article page needs *that* article, *that* article's comments, and the current viewer. `:<-` can't express this, because it lists query vectors literally at registration time, and `article-id` doesn't exist yet.

For that case `reg-sub` takes **two functions**: an *input function* and the computation function.

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

The input function answers *what does this sub depend on?* Here, three subscriptions, two of them parameterised by the `article-id` plucked from the query vector. The computation function answers *what does it compute?* It receives the resolved input values as a vector, in the order the input function listed them. (Always a vector in this form, even for a single input.)

The choice between the two forms is sharp: **use `:<-` for static inputs; reach for an input function only when the upstream query vectors need values from the outer query vector.** `:<-` is exactly a constant input function with the boilerplate removed, and its edges are statically drawable. The two-function form trades that for parametricity, so spend it only where you need it.

A few things keep this form predictable. The first trips people up, so it leads:

> **Gotcha — the input function returns *data*, not live subs.** It returns query vectors, never `subscribe` calls. It must be pure over the query vector: no deref of app-db, no `subscribe`, no dispatch, no IO. The runtime does the subscribing. And a single input is still a *vector of one query vector* — `[[:item/by-id id]]`, not `[:item/by-id id]`. The scalar shape is rejected because `[:x :y]` is ambiguous: one query with an argument, or two inputs? A wrong shape fails loud rather than guessing; the full return grammar and error ids live in the [API reference](../../../spec/API.md#reg-sub-input-production-modes).

The other two predictability rules are gentler:

- **It is not on the hot path.** It runs once, when a concrete query vector like `[:article/page :a1]` is first materialised. From then on that entry is an ordinary cached node, and `[:article/page :a2]` is a separate entry with its own inputs.
- **Dependencies cannot come from app-db.** A sub whose edges changed with state would break disposal, hot reload, and Xray's topology view. So when the parameter you need lives in app-db, read it at the call site and thread it through the query vector:

```clojure
(rf/reg-view article-pane []
  (let [article-id @(subscribe [:current-route/article-id])
        page       @(subscribe [:article/page article-id])]
    ...))
```

The dynamism lives at the view boundary, where view mount and unmount already manage subscription lifecycle. Each concrete cache entry keeps the same edges for its whole life.

> **From re-frame v1.** Your signal functions returned live `(rf/subscribe ...)` calls — v2 input functions return query vectors as plain data instead, and the single-input and map-returning v1 shapes need rewriting. [From re-frame v1](../25-from-re-frame-v1.md) has the mechanical recipes.

### The exact return grammar — what's accepted, what's rejected

This is the one corner of `reg-sub` with a strict shape, so it's worth seeing the whole grammar in one place. An input function **must** return a vector, and **every element** must itself be a query vector (a vector whose head is a keyword):

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

These aren't silent coercions — they [fail loud](../glossary.md#fail-loud-not-silent) so a typo can't quietly produce the wrong dependency edges:

- A **malformed registration shape** (e.g. a stray non-fn in the tail) is caught at `reg-sub` time and signals `:rf.error/reg-sub-bad-args`. It's a programming error to fix, surfaced at registration rather than first use.
- A **bad return value** from the input function signals `:rf.error/sub-input-fn-bad-return` when the concrete subscription is first materialised.
- A **throw inside the input function** signals `:rf.error/sub-input-fn-exception`.

All three are catalogued in [Errors and recovery](errors.md) and the spec's [error-event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue). When something computes the wrong thing, that's where to look first.

## Registration metadata: docs, schema, and classification

Any `reg-sub` may carry an optional **metadata map** immediately after the id, before the `:<-` chain or the body. It's where you put declarations *about* the subscription rather than its computation:

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

- **`:doc`** — a human-readable description. It's structurally optional, but the dev build *warns* when a registration omits it, because tools (Xray's sub list, the topology view) surface it. Treat it as a SHOULD.
- **`:schema`** — a [Malli](https://github.com/metosin/malli) [schema](../glossary.md#schema) (or your implementation's equivalent) describing the sub's **output**. When present, the runtime validates the computed value against it at the `:sub-return` validation boundary — a fail-loud guard that catches a derivation quietly producing the wrong shape, long before a view chokes on it. (`:schema` is the canonical key.) The full schema-everywhere story is in [Validate with schemas](../how-to/validate-with-schemas.md).
- **`:tags`** — a set of keywords for your own grouping and tooling.

Two metadata keys are specific to subscriptions, both from the [data-classification](../glossary.md#data-classification) model ([EP-0025](../../../spec/015-Data-Classification.md)). They classify the sub's **own output** so the observability pipeline knows what to redact or summarise when it captures a value into a trace:

- **`:sensitive`** — a vector of paths into the output shape that hold sensitive data (`[[]]` marks the whole output). A sub deriving a token, a card number, or a session secret should classify it so it's elided from traces and recordings.
- **`:large`** — a vector of paths into the output that are big enough to summarise rather than capture verbatim (a 5,000-row table, a decoded blob).

```clojure
(rf/reg-sub :auth/session-token
  {:doc       "The raw bearer token — never goes to traces."
   :sensitive [[]]}                       ;; the whole output is sensitive
  (fn [db _] (:auth/token db)))
```

> **Gotcha — classification doesn't propagate.** A sub does **not** inherit its inputs' `:sensitive`/`:large` declarations — derived-output sensitivity does not propagate. If a derived value is sensitive, classify it *at the sub that produces it*. Each output path is classified where it's declared, full stop. The narrative and the keep-it-out-of-traces recipe live in [Keep secrets out of traces](../how-to/keep-secrets-out-of-traces.md).

A malformed `:sensitive`/`:large` value is rejected at registration with `:rf.error/bad-classification` — another fail-loud guard rather than a silent drop.

## Testing a subscription without a browser

Because a layer-1/2/3 computation is just a pure function of `(inputs, query-v)`, you don't need a reactive runtime — or a DOM, or a browser — to test what a subscription *computes*. `rf/compute-sub` runs a sub's body against an app-db **value** and returns the result. It's JVM-runnable: no Reagent, no React, no installed [adapter](../glossary.md#adapter), no live cache.

```clojure
(deftest visible-items-honour-the-category-filter
  (let [db {:cart/items           [{:sku "a" :category "books"  :price 2}
                                   {:sku "b" :category "snacks" :price 1}]
            :cart/category-filter "books"}]
    ;; compute-sub resolves the whole :<- chain — :cart/items and
    ;; :cart/by-price run automatically as inputs.
    (is (= ["a"]
           (mapv :sku (rf/compute-sub [:cart/visible] db))))))
```

`compute-sub` resolves the entire input chain for you — pass it the outer query vector and a `db`, and it computes `:cart/items`, then `:cart/by-price`, then `:cart/visible`, in dependency order. It's **pure**: the same `(query-v, db)` always returns the same value, with no cache carried between calls. That makes it the workhorse for sub tests and the function the conformance corpus invokes for `:sub-values` assertions.

There's a sharper, more robust variant when the `db` shape matters. Instead of hand-rolling a literal map — which silently rots when your handler-side schema evolves — drive real events through a test frame and then read the sub against the resulting db:

```clojure
(deftest cart-count-after-events
  (rf/with-new-frame [f (rf/make-frame {})]
    ;; with-new-frame pins f as the current frame for the body, so the
    ;; dispatches below land in f without naming it each time.
    (rf/dispatch-sync [:cart/add-item {:sku "BK-1"}])
    (rf/dispatch-sync [:cart/add-item {:sku "BK-2"}])
    (is (= 2 (count (rf/compute-sub [:cart/items] (rf/app-db-value f)))))))
```

> **Two styles, one rule of thumb.** `compute-sub` against a literal `db` is the escape hatch for very simple readers where the dispatch path adds nothing. For anything that depends on the *shape* events produce, dispatch real events into a frame and read `(rf/app-db-value f)` — your test then exercises the same db your handlers actually build, so it can't drift from reality. Avoid `subscribe` + deref in tests altogether: the reactive runtime is pure overhead for a value assertion, and it needs a live cache and an installed adapter. The full testing matrix is in [Test an event handler](../how-to/test-an-event-handler.md) and [Spec 008 §Sub testing](../../../spec/008-Testing.md#sub-testing--compute-sub-vs-dispatch-sync--app-db-value).

> **For JavaScript developers.** This is the payoff of computation functions being pure. There is no React Testing Library, no `renderHook`, no jsdom, no provider wrapper to set up — a subscription test is a plain function call asserting on a plain value, and it runs on the JVM at unit-test speed. The reactive runtime exists only to *cache and notify* in a live app; the *logic* is just data in, data out, testable in isolation.

## Lifecycle: a sub exists only while something watches it

A subscription node isn't a permanent fixture in the cache — it's reference-counted. When a view derefs `[:cart/visible]`, the cache materialises the node (computing the whole input chain) and bumps a ref-count. A second view sharing the same query vector bumps it again and reads the same cached value. When a view unmounts, its dependency is released, and on the **last** release — ref-count hits zero — the cache slot is disposed **synchronously, in the same tick**: the reaction is torn down, its input ref-counts are released (which can cascade disposal up the chain), and the slot is removed. A `:rf.sub/dispose` [trace event](../glossary.md#trace-event) marks the eviction.

This matters in two everyday ways:

- **There's no grace-period timer.** Disposal is immediate on the 1 → 0 edge, so a sub can't be kept alive — recomputing pointlessly — across a state change that lands after its last reader has gone. (Equally: re-subscribing after disposal is a fresh cache miss that rebuilds against the registered body. Because the body and the db are the same, the recomputed value `=` what was disposed, so a remount observes no flicker.)
- **Hot-reload and frame teardown are clean.** Re-registering a sub disposes every cached slot for that query, regardless of ref-count — the next subscribe builds against the new body. Destroying a [frame](../glossary.md#frame) disposes every cached slot it owns. You get correct behaviour across a `shadow-cljs` reload without thinking about it.

Two functions let you step outside the deref-driven lifecycle deliberately:

- **`rf/subscribe-once`** — `(subscribe-once query-v)` (or `(subscribe-once frame-id query-v)`) subscribes, derefs once, and immediately unsubscribes, returning the plain value. It's a **non-reactive** read: you get the value as of right now and you are *not* registered for change notification. It's the right tool for a one-shot read inside a REPL session, a machine action, or a handler body that genuinely needs a derived value once. If you reach for it routinely from a handler, the value probably wants to be a [flow](../glossary.md#flow) instead (see below).
- **`rf/unsubscribe`** — `(unsubscribe query-v)` decrements the ref-count by hand, for the rare case where you took a reference programmatically and need to release it. Views never call this; their mount/unmount lifecycle does it for you.

## Standard registered subscriptions

The framework registers a handful of subscriptions for you — you read subsystem state through them exactly as you'd read your own:

- **`[:rf/machine <machine-id>]`** returns a [state machine](../../machines/glossary.md#machine)'s [snapshot](../../machines/glossary.md#snapshot) `{:state :data}` (or `nil` before it's initialised). It's the canonical way to drive a view off a machine.
- The router publishes a family — **`:rf/route`** (the whole route slice), **`:rf.route/id`**, **`:rf.route/params`**, **`:rf.route/query`**, **`:rf.route/transition`**, **`:rf.route/chain`**, and more — covered in [Routing](../../routing/concepts.md).

These follow the [reserved-namespace convention](../../../spec/Conventions.md): anything under `:rf/…` or `:rf.<subsystem>/…` is framework-owned. Keep your own subs out of that namespace and the two never collide.

## When a subscription is the wrong tool

Subscriptions are view-facing and pull-based: a node exists in the cache only while some view is watching it. That boundary is what tells you when to reach for something else. The full decision belongs to [the four homes](../glossary.md#the-four-homes-where-state-lives) router — but here are the edges where a sub is the wrong answer:

> **Reach past a subscription when…**
>
> - **An event handler needs the derived value.** Handlers don't subscribe — that's what [flows](../glossary.md#flow) are for: derived values materialised *into* app-db, where a handler can read them as plain state. (`rf/subscribe-once` exists for a one-shot read, but if you reach for it routinely, the value wants to be a flow.)
> - **The value comes from a server.** Subscriptions never fetch — computation functions are pure, no IO. Server-owned data belongs to [resources](../../resources/glossary.md#resource); subscriptions derive *over* the cached resource state.
> - **The value crosses frames.** A subscription must not reach into another [frame](../glossary.md#frame)'s state; frames are isolated worlds by design.
> - **Unsure where a value belongs at all?** [Where should this value live?](../where-state-lives.md) sorts a value into a sub, flow, resource, or machine with four questions.

> **Coming from TanStack Query?** Note the split: TanStack Query gives you *one* hook (`useQuery`) that both fetches server state and derives over it. re-frame2 keeps those concerns apart — [resources](../../resources/glossary.md#resource) own the fetch-cache-invalidate lifecycle for server-owned data, and subscriptions are the pure derivation layer that computes *over* whatever's already in app-db (resource state included). When you want to fetch, that's a resource; when you want to shape what's already there, that's a sub.

## Advanced

Three corners you won't need on day one, but will want when something goes sideways — what the graph does when a computation throws, how a schema'd sub recovers from a bad value, and what subscribing during teardown reports.

### When a computation throws

A computation function is just code, and code can throw — a `nil` where you assumed a map, a divide-by-zero in a derived total. re-frame2 treats that as a [fail-loud](../glossary.md#fail-loud-not-silent) event, not a crash: it emits `:rf.error/sub-exception` and **recovers the sub to `nil`**, so the throw can't take down the render. The record is always-on (it reaches your production error listeners — Sentry, Datadog), and its `:where` tag tells you which path threw: `:reactive` for the live cache path a view drives, `:compute-sub` for the pure test/SSR path. Both surface the same way, so a sub that throws mid-render-to-string projects a fail-closed 5xx rather than shipping a silent 200 with `nil`-shaped HTML. Recovery is the framework's built-in "return `nil`"; there's no per-frame recovery policy to configure. The full catalogue entry is in [Errors and recovery](errors.md) and the [error-event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue).

### When a schema'd sub computes the wrong shape

If a sub carries a `:schema` (see [Registration metadata](#registration-metadata-docs-schema-and-classification)), the runtime validates the computed value *after* the body runs, at the `:sub-return` boundary. On a mismatch it emits `:rf.error/schema-validation-failure` with `:where :sub-return` and, by default, **surfaces `nil`** to the consumer (the same `:replaced-with-default` posture as a throw) — so a derivation quietly producing the wrong shape is caught at the sub that produced it, not three layers downstream where a view chokes on it. A strict mode re-raises instead, for CI. Like every schema check, this whole boundary is [elided](../glossary.md#elide) in production builds — it's a development guard, not a runtime tax.

> **Gotcha — subscribing during teardown returns `nil`, loudly.** Subscribe against a [frame](../glossary.md#frame) that's already been destroyed — a stray async callback firing after a `frame-provider` unmounted, a hot-reload race — and re-frame2 recovers (the subscribe returns `nil`) while emitting a production-survivable `:rf.error/frame-destroyed` carrying the frame id and the attempted query vector. So a teardown race fails safe, but a genuine use-after-destroy bug stays visible on the stream you watch in production. (A *rootless* subscribe — one issued under no frame scope at all — is the different `:rf.error/no-frame-context`; see [frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found).)

Subscriptions are also one face of a larger family — flows, resources, route facts, and machine selectors all live on [one derivation graph](../glossary.md#the-derivation-graph); [One graph: derivations and their algebra views](../derivations-and-algebra-views.md) is the essay-length tour.
