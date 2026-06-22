# Subscriptions: the derivation graph

**App-db stores facts; subscriptions derive conclusions.** App-db is your app's single state map, and your views never read it directly. Instead they ask, by name, for a conclusion: "the visible articles", "can this form submit?", "the current user's name". A subscription — a named, cached derivation that turns state into a value a view wants — is that question answered. Those derivations form a graph rooted at [app-db](app-db.md), with your views hanging off the leaves. The nice part is that re-frame2 recomputes only along the paths where values actually changed. This page walks that graph end to end: how to build it, why it's fast with no tuning from you, and how to watch it prune work in Xray.

> **Coming from Redux?** A subscription is a selector — Reselect's `createSelector` with the memoisation built in. **Coming from Solid or Jotai?** It's a derived signal / derived atom. Three deliberate divergences from both: subscriptions are named by keyword in a registry, so tools can draw the whole graph without running your app; change detection is deep value equality (`=`), never reference identity, so there is no "don't allocate a new object or you'll bust the memo" dance; and dependencies are declared as data, not discovered by tracking a function run.

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

The vector `[:feed/tag-filter]` is the **query vector**: the id plus any arguments. `[:article/by-slug "intro"]` carries one argument. The whole vector arrives as the computation function's second argument, ignored above as `_query`. That little `@` is doing two jobs at once. It unwraps the reactive reference to a plain value, and it registers the deref-ing view as a dependent, so the view re-renders when — and only when — that value changes. The view declared a dependency and walked away. It never polls, and it never listens to a store-wide "something changed" firehose.

So why name a derivation this trivial instead of just writing `(:feed/tag-filter db)` in the view? Two reasons, and they recur everywhere in this framework:

- **Decoupling.** Where the value lives in app-db is the subscription's secret. Move it tomorrow and you change one registration, not forty views.
- **Sharing.** Every view asking for `[:feed/tag-filter]` reads the *same* cached node. The subscription cache is keyed by query vector (per [frame](frames.md) — an isolated app-db-plus-handlers world; for now, read that as "per app"), so a computation runs once per change no matter how many views consume it. Adding the forty-first reader costs nothing.

Both reasons get stronger the moment derivations start feeding each other, which is the actual design.

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

Here's the part worth pausing on: the shape of the registration *is* the topology. `(fn [db _] ...)` makes an extractor by construction; `:<-` makes a composer by construction. The framework reads the registry and knows the whole graph as data. That is how [Xray](../how-to/debug-with-xray.md) can draw your subscription topology without executing a single computation function.

## The equality gate

I said the graph is fast without tuning. Here is the entire mechanism, one rule:

> **A subscription's cached value is invalidated only when one of its inputs actually changes value — checked with `=`, deep value equality.**

When app-db changes, the layer-1 extractors re-run. They read app-db, so every change makes them re-check. Then each extractor's new output is compared with its previous output by `=`. If the slice didn't change, the cached value stands and **propagation stops right there**. Downstream layer-2 subs don't re-run, views don't re-render, and nothing past the unchanged extractor even learns that an event happened.

That makes layer 1 a **circuit breaker** for everything behind it. Change `:feed/tag-filter` and the `:articles/all` extractor re-runs, sees its slice is `=` to last time, and shuts the gate — so the sort in `:articles/by-date` never executes. The same gate sits at every node. A layer-2 sub that recomputes but produces an `=` result stops propagation to *its* dependents too. You wrote zero `memo` and zero dependency arrays; you declared what each sub reads and got memoisation at every node for free. It works in reverse, too: a no-op write — a handler that assocs a key to the value it already has — produces an app-db that is `=` to the old one, so *nothing* recomputes anywhere. You cannot cause a render storm by writing state that didn't change.

> **Why this matters.** In Reselect you carry the discipline yourself: a selector memoises on *reference* identity, so the moment a reducer returns a freshly-allocated array that's element-wise identical to the old one, every downstream selector and component recomputes anyway. The fix is to never allocate unless something changed — a rule you must hold in your head at every reducer. re-frame2 compares by value (`=`), so that whole category of "I accidentally busted the memo" bug doesn't exist. Equal values are equal, however they were allocated.

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

A few things keep this form predictable. The first one trips people up, so it leads:

> **Gotcha.** The input function returns query vectors — plain data — never live subscriptions. It must be pure over the query vector: no deref of app-db, no `subscribe`, no dispatch, no IO. The runtime does the subscribing. And a single input is still a *vector of one query vector* — `[[:item/by-id id]]`, not `[:item/by-id id]`. The scalar shape is rejected because `[:x :y]` is ambiguous: one query with an argument, or two inputs? A wrong shape errors loudly rather than guessing; the full return grammar and error ids live in the [API reference](../../../spec/API.md#reg-sub-input-production-modes).

The other two are gentler:

- **It is not on the hot path.** It runs once, when a concrete query vector like `[:article/page :a1]` is first materialised. From then on that entry is an ordinary cached node, and `[:article/page :a2]` is a separate entry with its own inputs.
- **Dependencies cannot come from app-db.** A sub whose edges changed with state would break disposal, hot reload, and Xray's topology view. So when the parameter you need lives in app-db, read it at the call site and thread it through the query vector:

```clojure
(rf/reg-view article-pane []
  (let [article-id @(subscribe [:current-route/article-id])
        page       @(subscribe [:article/page article-id])]
    ...))
```

The dynamism lives at the view boundary, where component mount and unmount already manage subscription lifecycle. Each concrete cache entry keeps the same edges for its whole life.

The choice between the two forms is sharp: **use `:<-` for static inputs; reach for an input function only when the upstream query vectors need values from the outer query vector.** `:<-` is exactly a constant input function with the boilerplate removed, and its edges are statically drawable. The two-function form trades that for parametricity, so spend it only where you need it.

> **Coming from re-frame v1?** Your signal functions returned live `(rf/subscribe ...)` calls — v2 input functions return query vectors as plain data, and the single-input and map-returning v1 shapes need rewriting; [From re-frame v1](../25-from-re-frame-v1.md) has the mechanical recipes.

## When a subscription is the wrong tool

Subscriptions are view-facing and pull-based: a node exists in the cache only while some view is watching it. That boundary is what tells you when to reach for something else.

> **Reach past a subscription when…**
>
> - **An event handler needs the derived value.** Handlers don't subscribe — that's what [flows](flows.md) are for: derived values handlers can read. (`rf/subscribe-once` exists for a one-shot read, but if you reach for it routinely, the value wants to be a flow.)
> - **The value comes from a server.** Subscriptions never fetch — computation functions are pure, no IO. Server-owned data belongs to [resources](server-state.md); subscriptions derive *over* the cached resource state.
> - **The value crosses frames.** A subscription must not reach into another [frame](frames.md)'s state; frames are isolated worlds by design.
> - **Unsure where a value belongs at all?** [Where should this value live?](../where-state-lives.md) sorts a value into a sub, flow, resource, or machine with four questions.

Subscriptions are also one face of a larger family — flows, resources, route facts, and machine selectors all live on one derivation graph; [One graph: derivations and their algebra views](../derivations-and-algebra-views.md) is the essay-length tour.

---

You can now:

- register a layer-1 extractor and chain derivations off it with `:<-`
- predict which subscriptions recompute after an event — and verify the pruning in Xray's Views tab
- keep extractors tiny so the equality gate cuts work off at the root
- write a parametric subscription whose inputs are computed from the query vector, and thread state-derived parameters through the call site
- tell when a derivation belongs in a sub versus a flow or a resource
