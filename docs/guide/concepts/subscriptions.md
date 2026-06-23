# Subscriptions: the derivation graph

**App-db stores facts; subscriptions derive conclusions.** App-db is your app's single state map, and your views never read it directly. Instead they ask, by name, for a conclusion: "the visible articles", "can this form submit?", "the current user's name". A subscription — a named, cached derivation that turns state into a value a view wants — is that question answered. Those derivations form a graph rooted at [app-db](app-db.md), with your views hanging off the leaves. The nice part is that re-frame2 recomputes only along the paths where values actually changed.

This page builds that graph one concept at a time: a single named derivation first, then chaining derivations into a graph, then the one rule that makes it fast, then parametric inputs, metadata, testing, and lifecycle. By the end you'll be able to read a subscription's registration and know exactly when it recomputes.

## A subscription is a named derivation

At bottom, a subscription is just a function from app-db to a value some view wants. That's all it is. You register it under a keyword id:

```clojure
(rf/reg-sub :feed/tag-filter
  (fn [db _query]
    (:feed/tag-filter db)))
```

A view reads the current value by deref-ing the subscription:

```clojure
@(rf/subscribe [:feed/tag-filter])
```

The vector `[:feed/tag-filter]` is the **query vector**: the id plus any arguments. `[:article/by-slug "intro"]` carries one argument. The whole vector arrives as the computation function's second argument, ignored above as `_query`.

That little `@` is doing two jobs at once. It unwraps the reactive reference to a plain value, and it registers the deref-ing view as a dependent, so the view re-renders when — and only when — that value changes. The view declared a dependency and walked away. It never polls, and it never listens to a store-wide "something changed" firehose.

So why name a derivation this trivial instead of just writing `(:feed/tag-filter db)` in the view? Two reasons, and they recur everywhere in this framework:

- **Decoupling.** Where the value lives in app-db is the subscription's secret. Move it tomorrow and you change one registration, not forty views.
- **Sharing.** Every view asking for `[:feed/tag-filter]` reads the *same* cached node. The subscription cache is keyed by query vector (per [frame](frames.md) — an isolated app-db-plus-handlers world; for now, read that as "per app"), so a computation runs once per change no matter how many views consume it. Adding the forty-first reader costs nothing.

Both reasons get stronger the moment derivations start feeding each other, which is the actual design.

> **Coming from Redux?** A subscription is a selector — Reselect's `createSelector` with the memoisation built in. **Coming from Solid or Jotai?** It's a derived signal / derived atom. Three deliberate divergences from both: subscriptions are named by keyword in a registry, so tools can draw the whole graph without running your app; change detection is deep value equality (`=`), never reference identity, so there is no "don't allocate a new object or you'll bust the memo" dance; and dependencies are declared as data, not discovered by tracking a function run.

## Three layers, one graph

A subscription's input doesn't have to be app-db. It can be **another subscription**. Once derivations feed derivations you have a directed acyclic graph, and re-frame2 walks it for you. That graph has layers, and a subscription's layer is decided entirely by what it reads:

| Layer | What it reads | Its one job | Recomputes when |
|---|---|---|---|
| **Layer 1 — extractors** | app-db directly | Pluck out a raw slice. No computation. | Every app-db change (to check its gate — see below). |
| **Layer 2 — derivations** | Other subs, via `:<-` | Sort, filter, join, shape. | An input sub's value changes by `=`. |
| **Layer 3+ — compositions** | Other subs, some of them layer 2 | Compose derivations of derivations. | An input sub's value changes by `=`. |

Layer 1 reaches into the map. Everybody else reaches into layer 1, or into each other. Here is a three-layer chain from a RealWorld-style article feed:

```clojure
;; Layer 1 — extractors: read app-db, pluck a slice, nothing else.
(rf/reg-sub :articles/all
  (fn [db _] (:articles db)))

(rf/reg-sub :feed/tag-filter
  (fn [db _] (:feed/tag-filter db)))

;; Layer 2 — reads :articles/all (a sub), never app-db.
(rf/reg-sub :articles/by-date
  :<- [:articles/all]
  (fn [articles _]
    (sort-by :created-at #(compare %2 %1) articles)))

;; Layer 3 — composes two subs.
(rf/reg-sub :articles/visible
  :<- [:articles/by-date]
  :<- [:feed/tag-filter]
  (fn [[articles tag] _]
    (if tag
      (filterv #(some #{tag} (:tag-list %)) articles)
      articles)))
```

The `:<-` arrow reads as "this sub's input comes from". Notice what changed between layers. `:articles/by-date` does **not** take `db`. It takes the already-extracted value that `:articles/all` produced. One arrow delivers that input as a bare value; two or more deliver a vector, destructured above as `[articles tag]`. That's the only wrinkle in the syntax, and once you've seen it once it stays put.

Here's the part worth pausing on: the shape of the registration *is* the topology. `(fn [db _] ...)` makes an extractor by construction; `:<-` makes a composer by construction. The framework reads the registry and knows the whole graph as data. That is how [Xray](../how-to/debug-with-xray.md) can draw your subscription topology without executing a single computation function. (The tool that exposes the static graph to debuggers is `re-frame.subs.tooling/sub-topology`, a literal projection of the registry — it never runs your bodies.)

> **Going deeper.** That "the registration *is* the topology" property is what lets the whole subscription layer be treated as data rather than as opaque closures. Each `:<-` edge is a static arrow in a DAG; the layer of a node is just the longest path back to app-db. Because the edges are declared rather than discovered at run time, the graph is a *value* you can analyse, draw, diff, and reason about without evaluation — the same move that makes [flows, resources, route facts, and machine selectors](../derivations-and-algebra-views.md) compose on one shared graph with one shared algebra. The whole derivation family is one essay; this page is the subscriptions-shaped slice of it.

## The equality gate

I said the graph is fast without tuning. Here is the entire mechanism, one rule:

> **A subscription's cached value is invalidated only when one of its inputs actually changes value — checked with `=`, deep value equality.**

When app-db changes, the layer-1 extractors re-run. They read app-db, so every change makes them re-check. Then each extractor's new output is compared with its previous output by `=`. If the slice didn't change, the cached value stands and **propagation stops right there**. Downstream layer-2 subs don't re-run, views don't re-render, and nothing past the unchanged extractor even learns that an event happened.

That makes layer 1 a **circuit breaker** for everything behind it. Change `:feed/tag-filter` and the `:articles/all` extractor re-runs, sees its slice is `=` to last time, and shuts the gate — so the sort in `:articles/by-date` never executes. The same gate sits at every node. A layer-2 sub that recomputes but produces an `=` result stops propagation to *its* dependents too. You wrote zero `memo` and zero dependency arrays; you declared what each sub reads and got memoisation at every node for free. It works in reverse, too: a no-op write — a handler that assocs a key to the value it already has — produces an app-db that is `=` to the old one, so *nothing* recomputes anywhere. You cannot cause a render storm by writing state that didn't change.

> **Coming from Redux?** In Reselect you carry the memoisation discipline yourself: a selector memoises on *reference* identity, so the moment a reducer returns a freshly-allocated array that's element-wise identical to the old one, every downstream selector and component recomputes anyway. The fix is to never allocate unless something changed — a rule you must hold in your head at every reducer. re-frame2 compares by value (`=`), so that whole category of "I accidentally busted the memo" bug doesn't exist. Equal values are equal, however they were allocated.

One practical rule falls out of all this, and it's the one to carry away:

> **Keep extractors tiny — put the work in layer 2.** Extractors run on every app-db change. They're the circuit breakers, so they must fire to decide whether to propagate, which means an extractor must be cheap: a `get`, a `get-in`, nothing more. Put a `sort-by` inside an extractor and that sort runs on every keystroke in every unrelated form — you've placed expensive work *before* the gate instead of behind it. Move it into a `:<-` sub and it runs only when the extracted slice actually changes. Same code, dramatically less work. And when a view is mysteriously slow, "is there computation in a layer-1 sub?" is the first question [Find and fix a slow view](../how-to/fix-a-slow-view.md) asks.

The gate scales further than you'd guess. The [Cells spreadsheet example](../../../examples/reagent/seven_guis/cells/) derives 2,600 mounted cell values from one shared input sub. The `=` check on each result means only cells whose displayed value genuinely changed re-render: correct propagation with no hand-maintained dependency edges at all.

## Watch it prune

Reading about a circuit breaker is one thing; watching one branch stay silent while its neighbour fires is better. Drop this into your app. It's self-contained: two independent app-db slices, one extractor and one derivation per branch, one view reading both. (`rf/reg-view` registers the view and injects the frame-aware `dispatch` / `subscribe` locals its body uses — [Views](views.md) tells that story.)

```clojure
(rf/reg-event :pulse/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db
                :pulse/ticks 0                            ;; this slice will change
                :pulse/motto "facts in, conclusions out")})) ;; this one never does

(rf/reg-event :pulse/tick
  (fn [{:keys [db]} _] {:db (update db :pulse/ticks inc)}))

;; Layer 1 — one tiny extractor per slice.
(rf/reg-sub :pulse/ticks (fn [db _] (:pulse/ticks db)))
(rf/reg-sub :pulse/motto (fn [db _] (:pulse/motto db)))

;; Layer 2 — one derivation per branch.
(rf/reg-sub :pulse/tick-label
  :<- [:pulse/ticks]
  (fn [n _] (str "tick #" n)))

(rf/reg-sub :pulse/motto-label
  :<- [:pulse/motto]
  (fn [m _] (str "motto: " m)))

(rf/reg-view pulse-panel []
  [:div
   [:p @(subscribe [:pulse/tick-label])]
   [:p @(subscribe [:pulse/motto-label])]
   [:button {:on-click #(dispatch [:pulse/tick])} "tick"]])
```

Seed it once at boot — `(rf/dispatch-sync [:pulse/initialise])` next to your app's existing init — and mount `[pulse-panel]` somewhere visible. An event is a message you `dispatch` to change state; an event handler is the function that processes it, and `dispatch-sync` runs one immediately so app-db is ready before the first paint.

Now **observe**. With Xray attached (the one-line setup is in [Debug with Xray](../how-to/debug-with-xray.md)), click **tick** a few times, select the newest event row, and open the **Views** tab. It lists each view that re-rendered in that cascade, and under it the subscriptions the view read:

- `:pulse/tick-label` is marked as the trigger. Its value changed since the last cascade, which is why `pulse-panel` re-rendered.
- `:pulse/motto-label` sits beside it unmarked. It never recomputed. Its extractor `:pulse/motto` *ran* — every extractor re-checks on every app-db change — but produced an `=` value, so the gate closed and the motto branch never woke up.

Every tick is a brand-new app-db value, and both branches are attached to it. The difference between them is the gate. Change flows exactly as far as values actually move, and not one node further.

> **Try it.** Register a deliberate no-op — `(rf/reg-event :pulse/restate (fn [{:keys [db]} _] {:db (assoc db :pulse/motto "facts in, conclusions out")}))` — give it a button, and dispatch it. The event row appears and the cascade ends immediately: app-db is `=` to before, so nothing recomputed and nothing re-rendered. The graph proved nothing changed and went back to sleep.

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

A few things keep this form predictable. The first one trips people up, so it leads:

> **Gotcha.** The input function returns query vectors — plain data — never live subscriptions. It must be pure over the query vector: no deref of app-db, no `subscribe`, no dispatch, no IO. The runtime does the subscribing. And a single input is still a *vector of one query vector* — `[[:item/by-id id]]`, not `[:item/by-id id]`. The scalar shape is rejected because `[:x :y]` is ambiguous: one query with an argument, or two inputs? A wrong shape errors loudly rather than guessing; the full return grammar and error ids live in the [API reference](../../../spec/API.md#reg-sub-input-production-modes).

The other two predictability rules are gentler:

- **It is not on the hot path.** It runs once, when a concrete query vector like `[:article/page :a1]` is first materialised. From then on that entry is an ordinary cached node, and `[:article/page :a2]` is a separate entry with its own inputs.
- **Dependencies cannot come from app-db.** A sub whose edges changed with state would break disposal, hot reload, and Xray's topology view. So when the parameter you need lives in app-db, read it at the call site and thread it through the query vector:

```clojure
(rf/reg-view article-pane []
  (let [article-id @(subscribe [:current-route/article-id])
        page       @(subscribe [:article/page article-id])]
    ...))
```

The dynamism lives at the view boundary, where component mount and unmount already manage subscription lifecycle. Each concrete cache entry keeps the same edges for its whole life.

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

These aren't silent coercions — they fail loudly so a typo can't quietly produce the wrong dependency edges:

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
- **`:schema`** — a [Malli](https://github.com/metosin/malli) schema (or your implementation's equivalent) describing the sub's **output**. When present, the runtime validates the computed value against it at the `:sub-return` validation boundary — a fail-loud guard that catches a derivation that's quietly producing the wrong shape, long before a view chokes on it. (`:schema` is the canonical key; the v1 `:spec` was renamed.) The full schema-everywhere story is in [Validate with schemas](../how-to/validate-with-schemas.md).
- **`:tags`** — a set of keywords for your own grouping and tooling.

There are two metadata keys specific to subscriptions, both from the data-classification model ([EP-0025](../../../spec/015-Data-Classification.md)). They classify the sub's **own output** so the observability pipeline knows what to redact or summarise when it captures a value into a trace:

- **`:sensitive`** — a vector of paths into the output shape that hold sensitive data (`[[]]` marks the whole output). A sub deriving a token, a card number, or a session secret should classify it so it's elided from traces and recordings.
- **`:large`** — a vector of paths into the output that are big enough to summarise rather than capture verbatim (a 5,000-row table, a decoded blob).

```clojure
(rf/reg-sub :auth/session-token
  {:doc       "The raw bearer token — never goes to traces."
   :sensitive [[]]}                       ;; the whole output is sensitive
  (fn [db _] (:auth/token db)))
```

> **Gotcha.** Classification doesn't propagate along the graph. A sub does **not** inherit its inputs' `:sensitive`/`:large` declarations — EP-0025 removed derived-output sensitivity propagation. If a derived value is sensitive, classify it *at the sub that produces it*. Each output path is classified where it's declared, full stop. The narrative and the keep-it-out-of-traces recipe live in [Keep secrets out of traces](../how-to/keep-secrets-out-of-traces.md).

A malformed `:sensitive`/`:large` value is rejected at registration with `:rf.error/bad-classification` — another fail-loud guard rather than a silent drop.

## Testing a subscription without a browser

Because a layer-1/2/3 computation is just a pure function of `(inputs, query-v)`, you don't need a reactive runtime — or a DOM, or a browser — to test what a subscription *computes*. `rf/compute-sub` runs a sub's body against an app-db **value** and returns the result. It's JVM-runnable: no Reagent, no React, no installed adapter, no live cache.

```clojure
(deftest visible-articles-honour-the-tag-filter
  (let [db {:articles      [{:slug "a" :tag-list ["clj"]   :created-at 2}
                            {:slug "b" :tag-list ["redux"] :created-at 1}]
            :feed/tag-filter "clj"}]
    ;; compute-sub resolves the whole :<- chain — :articles/all and
    ;; :articles/by-date run automatically as inputs.
    (is (= ["a"]
           (mapv :slug (rf/compute-sub [:articles/visible] db))))))
```

`compute-sub` resolves the entire input chain for you — pass it the outer query vector and a `db`, and it computes `:articles/all`, then `:articles/by-date`, then `:articles/visible`, in dependency order. It's **pure**: same `(query-v, db)` always returns the same value, with no cache carried between calls. That makes it the workhorse for sub tests and the function the conformance corpus invokes for `:sub-values` assertions.

There's a sharper, more robust variant when the `db` shape matters. Instead of hand-rolling a literal map — which silently rots when your handler-side schema evolves — drive real events through a test frame and then read the sub against the resulting db:

```clojure
(deftest pending-todos-after-events
  (let [f (rf/make-frame {:id :test})]
    (rf/dispatch-sync :test [:add-todo {:text "milk"}])
    (rf/dispatch-sync :test [:add-todo {:text "eggs"}])
    (is (= 2 (count (rf/compute-sub [:pending-todos] (rf/app-db-value f)))))))
```

> **Two styles, one rule of thumb.** `compute-sub` against a literal `db` is the escape hatch for very simple readers where the dispatch path adds nothing. For anything that depends on the *shape* events produce, dispatch real events into a frame and read `(rf/app-db-value f)` — your test then exercises the same db your handlers actually build, so it can't drift from reality. Avoid `subscribe` + deref in tests altogether: the reactive runtime is pure overhead for a value assertion, and it needs a live cache and an installed adapter. The full testing matrix is in [Test an event handler](../how-to/test-an-event-handler.md) and [Spec 008 §Sub testing](../../../spec/008-Testing.md#sub-testing--compute-sub-vs-dispatch-sync--app-db-value).

> **For JavaScript developers.** This is the payoff of computation functions being pure. There is no React Testing Library, no `renderHook`, no jsdom, no provider wrapper to set up — a subscription test is a plain function call asserting on a plain value, and it runs on the JVM at unit-test speed. The reactive runtime exists only to *cache and notify* in a live app; the *logic* is just data in, data out, testable in isolation.

## Lifecycle: a sub exists only while something watches it

A subscription node isn't a permanent fixture in the cache — it's reference-counted. When a view derefs `[:articles/visible]`, the cache materialises the node (computing the whole input chain) and bumps a ref-count. A second view sharing the same query vector bumps it again and reads the same cached value. When a view unmounts, its dependency is released, and on the **last** release — ref-count hits zero — the cache slot is disposed **synchronously, in the same tick**: the reaction is torn down, its input ref-counts are released (which can cascade disposal up the chain), and the slot is removed. A `:rf.sub/dispose` trace event marks the eviction.

This matters in two everyday ways:

- **There's no grace-period timer.** Disposal is immediate on the 1 → 0 edge, so a sub can't be kept alive — recomputing pointlessly — across a state change that lands after its last reader has gone. (Equally: re-subscribing after disposal is a fresh cache miss that rebuilds against the registered body. Because the body and the db are the same, the recomputed value `=` what was disposed, so a remount observes no flicker.)
- **Hot-reload and frame teardown are clean.** Re-registering a sub disposes every cached slot for that query, regardless of ref-count — the next subscribe builds against the new body. Destroying a [frame](frames.md) disposes every cached slot it owns. You get correct behaviour across a `shadow-cljs` reload without thinking about it.

Two functions let you step outside the deref-driven lifecycle deliberately:

- **`rf/subscribe-once`** — `(subscribe-once query-v)` (or `(subscribe-once frame-id query-v)`) subscribes, derefs once, and immediately unsubscribes, returning the plain value. It's a **non-reactive** read: you get the value as of right now and you are *not* registered for change notification. It's the right tool for a one-shot read inside a REPL session, a machine action, or a handler body that genuinely needs a derived value once. If you reach for it routinely from a handler, the value probably wants to be a [flow](flows.md) instead (see below).
- **`rf/unsubscribe`** — `(unsubscribe query-v)` decrements the ref-count by hand, for the rare case where you took a reference programmatically and need to release it. Views never call this; their mount/unmount lifecycle does it for you.

## Standard registered subscriptions

The framework registers a handful of subscriptions for you — you read subsystem state through them exactly as you'd read your own:

- **`[:rf/machine <machine-id>]`** returns a [state machine](machines.md)'s snapshot `{:state :data}` (or `nil` before it's initialised). It's the canonical way to drive a view off a machine.
- The router publishes a family — **`:rf/route`** (the whole route slice), **`:rf.route/id`**, **`:rf.route/params`**, **`:rf.route/query`**, **`:rf.route/transition`**, **`:rf.route/chain`**, and more — covered in [Routing](routing.md).

These follow the [reserved-namespace convention](../../../spec/Conventions.md): anything under `:rf/…` or `:rf.<subsystem>/…` is framework-owned. Keep your own subs out of that namespace and the two never collide.

## When a subscription is the wrong tool

Subscriptions are view-facing and pull-based: a node exists in the cache only while some view is watching it. That boundary is what tells you when to reach for something else.

> **Reach past a subscription when…**
>
> - **An event handler needs the derived value.** Handlers don't subscribe — that's what [flows](flows.md) are for: derived values handlers can read. (`rf/subscribe-once` exists for a one-shot read, but if you reach for it routinely, the value wants to be a flow.)
> - **The value comes from a server.** Subscriptions never fetch — computation functions are pure, no IO. Server-owned data belongs to [resources](server-state.md); subscriptions derive *over* the cached resource state.
> - **The value crosses frames.** A subscription must not reach into another [frame](frames.md)'s state; frames are isolated worlds by design.
> - **Unsure where a value belongs at all?** [Where should this value live?](../where-state-lives.md) sorts a value into a sub, flow, resource, or machine with four questions.

> **Coming from TanStack Query?** Note the split: TanStack Query gives you *one* hook (`useQuery`) that both fetches server state and derives over it. re-frame2 keeps those concerns apart — [resources](server-state.md) own the fetch-cache-invalidate lifecycle for server-owned data, and subscriptions are the pure derivation layer that computes *over* whatever's already in app-db (resource state included). When you want to fetch, that's a resource; when you want to shape what's already there, that's a sub.

Subscriptions are also one face of a larger family — flows, resources, route facts, and machine selectors all live on one derivation graph; [One graph: derivations and their algebra views](../derivations-and-algebra-views.md) is the essay-length tour.
