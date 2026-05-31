# 05 - Subscriptions

Your view needs to read state. But it must *not* know where in `app-db` that state lives, and it must *not* recompute its slice every time some unrelated corner of the map twitches. A subscription is the answer to both demands at once — a named derivation that views ask for by id — and the graph those derivations form, quietly, behind your back, is where almost all of re-frame2's performance comes from. This chapter is that graph. The next chapter, [06 — Views](06-views.md), is the boring render functions that sit on top of it; this one is the machinery underneath that makes them cheap.

## The problem nobody tells you is a problem

Let's start, as is tradition, with a complaint.

Here is the thing that goes wrong in every state-management story I've ever used, and it goes wrong so consistently that people have stopped noticing it's a bug and started treating it as weather. You have a big blob of state. You have a hundred views. Each view needs *some* of the state. And the question that determines whether your app is fast or a slideshow is: **when one tiny piece of that blob changes, which views re-render?**

The naive answer — the one you get for free in the most straightforward setup — is "all of them." State changed, so re-render the tree, so run every render function, so every view recomputes its slice from scratch whether or not its slice actually moved. React's whole reconciliation industry exists to claw back the cost of this default. `memo`, `useMemo`, `useCallback`, dependency arrays you get subtly wrong, the `===` referential-equality dance where you carefully don't allocate a new object so the memo doesn't bust — that entire genre of code is people fighting, by hand, one view at a time, the consequences of not having an answer to the question above.

re-frame2 has an answer to the question above. The answer is the subscription graph, and the beautiful part is that you don't write any of the fighting. You write what your view wants. The graph works out who recomputes.

## A subscription is a named derivation

Strip away everything fancy and a subscription is a function from `app-db` to some value a view wants. That's it. You register it with a keyword id, and from then on a view asks for the *value* by the *id*, never by reaching into the map.

```clojure
(rf/reg-sub :counter/value
  (fn [db _query]
    (:counter/value db)))
```

Read it left to right: register a subscription called `:counter/value`; when asked, take the current `db` and pull out the `:counter/value` key. The `_query` argument is the full query vector (`[:counter/value]` here, but subscriptions can take arguments — `[:user/by-id 42]` — and that's where the rest of the vector shows up); the leading underscore is the Clojure idiom for "I'm ignoring this." A view reads the current value by *deref*-ing the subscription:

```clojure
@(rf/subscribe [:counter/value])
```

The `@` (deref) is "give me the current contents of this reactive thing." `rf/subscribe` hands you back a reactive reference; `@` unwraps it to a plain value *and* — this is the load-bearing part — registers the deref-ing view as a dependent, so it gets re-run when, and only when, that value changes. The view declared a dependency and walked away. It never polls. It never subscribes to a store-wide "something changed" firehose. It said "I care about `:counter/value`" and the graph remembers.

Why bother naming a derivation this trivial — why not just write `(:counter/value @app-db)` in the view and skip the ceremony? Two reasons, and they're the same two reasons that show up everywhere in this framework. **Decoupling:** the view asks for `:counter/value`, and where that value actually *lives* in `app-db` is now the subscription's secret. Move it into a richer slice tomorrow — `(get-in db [:counters :main :value])` — and you change exactly one line, the subscription, and not one of the forty views that read it. **Sharing:** every view that wants `:counter/value` shares *one* computation and *one* cached result, not forty independent reads. Both of those are about to get much more interesting, because subscriptions don't just read `app-db` — they *chain*.

## The graph: three layers

Here's the move that turns a pile of derivations into a *system*. A subscription's input doesn't have to be `app-db`. It can be **another subscription**. And once derivations can feed derivations, you have a graph — a directed acyclic graph, rooted at `app-db`, with your views hanging off the leaves — and re-frame2 walks it for you.

The vocabulary for the graph comes straight from re-frame v1, and it's worth learning because the tooling speaks it. Subscriptions come in layers, and the layer a subscription is on is determined entirely by *what it reads*.

| Layer | What it reads | Its one job | Recomputes when |
|---|---|---|---|
| **Layer 1 — extractors** | `app-db` directly | Pluck out a raw slice. No computation. | The slice it reads changes by `=`. |
| **Layer 2 — materialised** | Other subs, via `:<-` | Shape, sort, filter, join, derive. | Any input sub's value changes by `=`. |
| **Layer 3+ — composed** | Other subs (some of them layer 2) | Compose derivations of derivations. | Any input sub's value changes by `=`. |

Read it as: **layer 1 reaches into the map, everybody else reaches into layer 1** (or into each other). The number isn't sacred — layer 3, 4, 5 are all just "reads other subs," and the framework treats them identically — but the layer-1-versus-the-rest distinction is real, structural, and the whole performance story hinges on it. We'll get there in a minute. First, the syntax.

### `:<-`, the chaining arrow

A layer-1 extractor reads `app-db`, so its computation function takes `db`:

```clojure
;; Layer 1 — reads app-db. Tiny. Just a slice.
(rf/reg-sub :products
  (fn [db _query]
    (:products db)))
```

A layer-2 sub reads *other subscriptions*, and it names them with the `:<-` arrow, which sits between the id and the computation function:

```clojure
;; Layer 2 — reads :products (a sub), never app-db.
(rf/reg-sub :products/sorted
  :<- [:products]
  (fn [products _query]
    (sort-by :name products)))
```

The `:<-` reads as "this sub's input comes from" — the glyph is an arrow pointing back at the source. Now `:products/sorted` does *not* take `db`. It takes `products` — the already-extracted value that `:products` produced. It never touches `app-db` directly, and that's not an accident, it's the entire point: **the shape of the registration tells the framework which layer the sub is on.** A sub written with `(fn [db _] ...)` is, by construction, an extractor. A sub written with `:<-` is, by construction, a composer. The framework reads the registry and *knows* — which is how the [Xray](../xray/index.md) tooling can draw your subscription graph without running your app: the topology is right there in the registrations, as data.

You can have more than one input. A sub that needs two upstream values lists two arrows:

```clojure
;; Layer 3 — composes two subs.
(rf/reg-sub :products/visible
  :<- [:products/sorted]
  :<- [:products/filter-text]
  (fn [[sorted filter-text] _query]
    (filter #(str/includes? (:name %) filter-text) sorted)))
```

Two arrows, so the computation function's first argument is a *vector* of the two input values, destructured here as `[sorted filter-text]`. (One arrow, one bare value; many arrows, a vector. That's the only wrinkle.) `:products/visible` reads `:products/sorted`, which reads `:products`, which reads `app-db`. That chain — `app-db → :products → :products/sorted → :products/visible → your view` — is a path through the graph, and it is exactly the path that data flows down when something changes.

> **A note on the middle position.** `:<-` is sugar. The general form of `reg-sub` is `(reg-sub id ?metadata signal-fn computation-fn)`, where the *signal function* returns the input subscriptions explicitly. The `:<- [:products]` form expands into a signal function that subscribes to `:products` and hands its value on. You'll basically never write the long form by hand — `:<-` covers the cases you actually have — but when you read "signal function" in the spec or the tooling, that's the slot it's talking about: the part of a subscription that declares *what it depends on*, separate from the part that declares *what it computes*. Inputs and computation, named separately, on purpose.

## Why the graph is fast: the equality gate

Now the payoff. I claimed the graph is where the performance comes from, and I've shown you a graph. Here's the engine inside it, and it is one rule, stated once:

> **A subscription's cached value is invalidated only when one of its inputs actually changes value — checked with `=`.**

That's it. That's the whole trick. Let me unpack why it's so much more than it looks.

When `app-db` changes, the framework re-runs the **layer-1 extractors** — it has to, they all read `app-db`, so they all have to be re-consulted to find out whether their slice moved. But — and here is the move — it then compares each extractor's *new* output against its *previous* output with `=`, Clojure's deep value equality. If the slice didn't change, the extractor's cached value stands, and **propagation stops right there.** The layer-2 subs downstream don't re-run. The layer-3 subs don't re-run. The views don't re-render. Nothing past the unchanged extractor learns that anything happened, because as far as it's concerned, nothing did.

This makes layer 1 a **circuit breaker** for the entire graph. Change `:user/profile` in `app-db`, and the `:cart/total` extractor re-runs, sees its slice (`(:cart db)`) is `=` to what it was, and shuts the gate. The whole cart subtree — the sorting, the tax calculation, the line-item view — never wakes up. You wrote zero `memo`. You wrote zero dependency arrays. You declared what each sub reads, and the equality gate did the memoisation *for free*, at every node, all the way up.

And it works in the other direction too, which is the part that delights me. A no-op write — `(assoc db :counter/value (:counter/value db))`, setting a key to the value it already has — produces an `app-db` that is `=` to the old one. So *nothing recomputes*. Not the extractor, not anything downstream. You can't accidentally trigger a render storm by writing state that didn't change, because "didn't change" is checked by value, structurally, and structural sharing in Clojure's persistent data structures makes that check cheap even on a big map. The default isn't "re-render and hope memo saves me." The default is "prove something changed before anyone lifts a finger."

Here is the rule that falls out of all of this, and it's the one practical thing to carry away:

> **Keep extractors tiny. Put the work in layer 2.**

Why this matters, concretely. Every layer-1 extractor re-runs on *every* `app-db` change — they're the circuit breakers, they have to fire to decide whether to propagate. So an extractor must be *cheap*: a `get`, a `get-in`, a key lookup. If you stuff a `sort-by` into an extractor, that sort now runs on every single `app-db` change — every keystroke in an unrelated form, every tick of an unrelated timer — because the extractor re-runs unconditionally to check its gate. You've put expensive work *before* the circuit breaker instead of after it. Push the sort into a layer-2 sub and it runs only when the *extracted slice* actually changes. Same code, dramatically sharper reactivity. Extractors decide whether to propagate; layer 2 does the thinking once propagation is warranted.

## See it prune

Reading about a circuit breaker is one thing. Watching one node stay silent while its neighbour fires is another. The cell below builds a tiny three-layer graph and a counter that ticks every second. The graph has two independent branches — a "live" branch that depends on the ticking clock, and a "static" branch that depends on a value nobody ever changes — and each branch counts how many times *its own* layer-2 sub actually recomputed.

Click into the cell and hit **`Ctrl-Enter`** (or **`Cmd-Enter`** on a Mac) to run it. Watch the two recompute counters. (Live cells use plain `defn` views with explicit `rf/subscribe`/`rf/dispatch`; `reg-view` is sugar over exactly this and is [the next chapter](06-views.md)'s job.)

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; --- app-db has two independent slices ---
(rf/reg-event-db :graph/initialise
  (fn [_db _event]
    {:graph/ticks   0          ;; changes every second
     :graph/frozen  "constant"})) ;; never changes

(rf/reg-event-db :graph/tick
  (fn [db _event] (update db :graph/ticks inc)))

;; --- Layer 1: two tiny extractors, one per slice ---
(rf/reg-sub :graph/ticks  (fn [db _] (:graph/ticks db)))
(rf/reg-sub :graph/frozen (fn [db _] (:graph/frozen db)))

;; --- Layer 2: each derives from one branch and counts its own recomputes ---
(def live-recomputes   (atom 0))
(def static-recomputes (atom 0))

(rf/reg-sub :graph/live-derived
  :<- [:graph/ticks]
  (fn [ticks _]
    (swap! live-recomputes inc)
    (str "tick #" ticks)))

(rf/reg-sub :graph/static-derived
  :<- [:graph/frozen]
  (fn [frozen _]
    (swap! static-recomputes inc)
    (str "still " frozen)))

;; --- A view that reads both branches ---
(defn graph-demo []
  [:div {:style {:font-family "monospace" :line-height "1.6"}}
   [:div "live branch:   " @(rf/subscribe [:graph/live-derived])
    "  (recomputed " @live-recomputes " times)"]
   [:div "static branch: " @(rf/subscribe [:graph/static-derived])
    "  (recomputed " @static-recomputes " times)"]])

;; --- Seed, then tick once a second forever ---
(rf/dispatch-sync [:graph/initialise])
(js/setInterval #(rf/dispatch [:graph/tick]) 1000)
[graph-demo]
```

Watch what happens. `app-db` changes once a second — every tick is a brand-new `app-db`. Both layer-1 extractors re-run every second to check their gates. But the **live** branch's recompute counter climbs steadily, once per second, while the **static** branch's counter sits at `1` and never moves. Same number of `app-db` changes hitting both. The `:graph/frozen` extractor re-runs every tick, sees its slice is `=` to last time, and slams the gate — so `:graph/static-derived` never recomputes, even though `app-db` itself changed a hundred times. That's the circuit breaker, live, and it's the reason a re-frame2 app with a busy corner doesn't drag the quiet corners down with it.

> **Try it.** Add a third layer-2 sub that depends on `:graph/live-derived` (a layer-3 sub: `(rf/reg-sub :graph/loud :<- [:graph/live-derived] (fn [s _] (str s "!")))`), give it its own recompute counter, surface it in the view, and re-evaluate. It climbs in lockstep with the live branch — change flows *down* the chain. Now point a fourth sub at `:graph/frozen` instead and watch it stay frozen at `1`. You're feeling the topology: change propagates exactly as far as the values actually move, and not one node further.

## Why every view shares one computation

There's a second gift in the graph, hiding behind the first, and it's the one that quietly saves you from a class of bug you'd otherwise never even diagnose.

A subscription's cache is keyed by its query vector, *per frame* (frames are [chapter 18](18-frames.md)'s job — for now, "per app"). So when ten different views all `@(rf/subscribe [:products/sorted])`, they are not running ten sorts. They are all reading the *same cached value* from the *same node* in the graph. The sort runs once, when `:products` changes, and ten views read the result. Add an eleventh view tomorrow and it costs nothing — the computation was already happening; the new view just attaches to the existing node.

This is the property that makes the rule from the [views chapter](06-views.md) — *views compute hiccup only, derivations live in subscriptions* — not just tidy but *fast*. If a view sorts its own list inline, that sort runs on every re-render of that view, and a sibling view that needs the same sorted list runs its own copy. Move the sort into a `:<-` sub and the cost collapses to once-per-change, shared by every consumer. The decoupling and the performance are the *same mechanism* seen from two angles: name your derivations, and the graph caches them, shares them, and prunes them, all without you writing a line of cache-management code.

That's the read side of re-frame2: a graph of named derivations rooted in `app-db`, recomputing only along the paths where values genuinely moved, sharing every result among everyone who asks. Your views sit at the leaves of this thing and read from it. What they do with what they read — and the one strict rule about what they're allowed to do — is [the next chapter](06-views.md).
