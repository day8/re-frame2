# 06 - Views

Views are the least interesting part of a re-frame2 app, and that is the entire point of this chapter. In most frameworks the view is where everything lives — state, effects, fetching, lifecycle, the works — which is precisely why the view is also where everything *breaks*. re-frame2 took that real estate and evicted everybody. What's left is a render function so boring, so derivative, so structurally incapable of getting into a weird state, that it barely deserves a chapter. This is that chapter: hiccup, the one boundary a view is allowed to touch, and why "your views are boring" is the best news in the framework.

If [chapter 05](05-subscriptions.md) was the read graph — the machinery that derives what views need and recomputes it only when it must — this chapter is what sits on the leaves of that graph. A view subscribes to the graph, dispatches events back into it, and in between does exactly one thing: turn data into a description of the screen.

## A view is a pure function of subscriptions

Here is the whole job, in one sentence, and I want you to hold it because everything else is a footnote to it:

> **A view is a pure function from subscription values to hiccup.**

Inputs come from subscriptions. Output is hiccup — a data structure describing the DOM. In between, no state, no fetching, no timers, no reaching into anything. Take some already-shaped data; lay it out. That's the contract, and it's almost insultingly small.

This is what we mean by views being **derivative rather than causal.** A view doesn't *cause* anything to happen and it doesn't *own* anything. It is *derived from* state — it's a projection, the way a shadow is a projection of an object. Change the object and the shadow follows; the shadow never moves the object. Your view follows `app-db` (via subscriptions); it is never the home of the state it displays. There's nothing to "lift up" out of a view because there was never anything down in it to begin with.

Why is this such a big deal? Because the most bug-infested place in a typical frontend is exactly the place where state, effects, and rendering get tangled together — the component that owns a piece of state, *and* fetches some data, *and* renders, *and* has three `useEffect`s racing each other to keep it all consistent. re-frame2's views can't get into that mess because they're not allowed to hold any of those things. State is in `app-db`. Derivation is in subscriptions. Effects are described by event handlers as data ([chapter 07](07-effects-and-coeffects.md)). The view's slice of the universe is "given these values, here's the screen," and a pure function of values to values has a *very* small space of ways to be wrong.

## Hiccup: the screen as data

The thing a view returns is **hiccup**: nested Clojure vectors that look like the DOM tree they describe.

```clojure
[:div.counter
 [:h1 "Apples"]
 [:p "Count: " [:strong "5"]]]
```

A vector whose first element is a keyword is an element. `:div.counter` is a `<div>` with class `counter` (the `.class` shorthand is borrowed from CSS selectors; `:input#email.form-control` gives you `id="email" class="form-control"`). A map in second position is the attributes — `[:button {:on-click f, :disabled true} "Go"]`. Everything after that is children: strings become text, nested vectors become nested elements, and a sequence of vectors becomes a list of siblings.

The deep thing here — and it *is* deep, deeper than it looks — is that **hiccup is just data.** Not "data-like." Not "almost data, except for the language extensions." Actual Clojure vectors and maps and keywords, the same data structures you manipulate everywhere else in the program.

Sit with what that buys you. You can `pprint` a view's output and read it. You can build hiccup programmatically — `(into [:ul] (for [x items] [:li x]))` — using the same `map`, `filter`, `for` you'd use on any other data, with no special template syntax to learn. You can write a function that *takes* hiccup and *returns* hiccup and composes views like any other values. And — this is the one that pays off in [chapter 20](20-server-side.md) — you can write a hiccup-to-HTML emitter that runs on the JVM, which is exactly how server-side rendering works: the *same* hiccup your browser renders, turned into an HTML string on the server, by a pure function over data.

Compare the alternatives and the choice sharpens. JSX is *almost* data, but the language extension and the component objects make it not-quite — you can't trivially `map` over it or ship it across a wire. Templating languages are *strings*, which means building them is string concatenation, which is the exact mechanism by which injection vulnerabilities exist. Hiccup is data the whole way down, and "the whole way down" is what makes it inspectable, composable, and portable. The screen is a value. Values are the only thing this framework really believes in.

## The two boundaries: subscribe in, dispatch out

A view is pure, but it isn't sealed — it has exactly two openings to the rest of the app, and learning to see them as a matched pair is most of understanding the architecture from the view's side.

**Reading state in:** a view derefs a subscription. `@(rf/subscribe [:counter/value])` is the view declaring "I depend on this derived value; re-run me when it changes." This is the only way a view learns anything about application state. It does not read `app-db`. It does not receive a giant props object plumbed down through ten ancestors. It asks the [subscription graph](05-subscriptions.md) for exactly the slice it needs, by name, and the graph hands it the cached value and remembers to re-run this view when that value moves.

**Sending events out:** a view dispatches. `#(rf/dispatch [:counter/inc])` — typically wired to an `:on-click` or `:on-change` — is the view saying "something happened: a click." That's *all* it says. It does not change state. It does not reach into `app-db`. It does not know or care what `:counter/inc` does. It announces an event and returns immediately; the [six-domino cascade](04-events-and-the-cascade.md) takes it from there, runs the pure handler, swaps `app-db`, repropagates the subscription graph, and — at the very end — re-renders this view to match.

That's the loop, and notice its shape: **subscribe in at the top, dispatch out at the bottom, and the entire round trip goes through the cascade, never directly.** A click doesn't mutate the number; it dispatches an event that produces a new `app-db` that flows back through a subscription that re-renders the view. The view never short-circuits that path. It can't — it has no handle on state to short-circuit *with*. The two boundaries are deliberately one-way and deliberately routed through the machine, and that's the discipline that keeps the view from ever being the thing that put the app in a weird state.

### `defn` views and the `reg-view` equivalence

A view is a function, so the simplest way to write one is `defn`:

```clojure
(defn counter []
  [:div
   [:button {:on-click #(rf/dispatch [:counter/dec])} "-"]
   [:span @(rf/subscribe [:counter/value])]
   [:button {:on-click #(rf/dispatch [:counter/inc])} "+"]])
```

You use it by referencing it inside other hiccup — `[counter]` — and the substrate calls the function and splices its return value into the tree. A view that takes arguments takes them like any function: `(defn labelled-counter [label] [:div [:h1 label] ...])`, used as `[labelled-counter "Apples"]`.

In real project code you'll more often see views registered with the **`reg-view`** macro:

```clojure
(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/inc])} "+"]
   [:span @(subscribe [:counter/value])]])
```

This is the *same component*. `reg-view` is sugar over the `defn` shape, and it's worth knowing exactly what the sugar adds, because the live cells in this guide use `defn` (the live environment is functions-only) while the prose and your real code lean on `reg-view`. Two things change:

1. **The view goes into the registry.** Registered under an auto-derived id, it sits alongside your events and subs where tooling can list it and introspect its metadata. (You'll meet why that matters in [17 — Tooling](17-tooling.md): the inspector resolves a clicked DOM node back to the `reg-view` that produced it.)
2. **`dispatch` and `subscribe` are injected, frame-aware, into the body.** Inside a `reg-view`, you write `dispatch` and `subscribe` *without* the `rf/` prefix — the macro binds local, frame-correct versions so the view body never names a frame out loud. This is what lets the same view render in two isolated [frames](18-frames.md) at once, each reading and writing its own state, with the view none the wiser. In a plain `defn` (and so in every live cell here), you write the qualified `rf/dispatch` / `rf/subscribe`, which target the default frame — exactly right for a single-app page.

So: `defn` view with explicit `rf/dispatch`/`rf/subscribe`, or `reg-view` with injected `dispatch`/`subscribe` — same render function, the macro just adds registry presence and frame-awareness. The live cells below use the `defn` form; mentally map `dispatch`→`rf/dispatch` and you've read the `reg-view` form too.

## The view, live

Enough prose. Here's a view doing its whole job — reading from a subscription, dispatching events, and returning hiccup — running in your browser right now. Click into the cell and press **`Ctrl-Enter`** (or **`Cmd-Enter`** on a Mac) to evaluate it, then click the buttons.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; --- Events: pure (db, event) -> next db ---
(rf/reg-event-db :greet/initialise
  (fn [_db _event] {:greet/name "world" :greet/excited? false}))

(rf/reg-event-db :greet/set-name
  (fn [db [_ new-name]] (assoc db :greet/name new-name)))

(rf/reg-event-db :greet/toggle-excited
  (fn [db _event] (update db :greet/excited? not)))

;; --- Subscriptions: layer 1 extractors + a layer 2 that shapes the greeting ---
(rf/reg-sub :greet/name     (fn [db _] (:greet/name db)))
(rf/reg-sub :greet/excited? (fn [db _] (:greet/excited? db)))

(rf/reg-sub :greet/message
  :<- [:greet/name]
  :<- [:greet/excited?]
  (fn [[name excited?] _]
    (str "Hello, " name (if excited? "!!!" "."))))

;; --- The view: subscribe in, dispatch out, hiccup between ---
(defn greeter []
  [:div {:style {:font-family "sans-serif"}}
   [:p {:style {:font-size "1.4em"}} @(rf/subscribe [:greet/message])]
   [:input {:type "text"
            :value @(rf/subscribe [:greet/name])
            :on-change #(rf/dispatch [:greet/set-name (.. % -target -value)])
            :style {:margin-right "0.5em"}}]
   [:button {:on-click #(rf/dispatch [:greet/toggle-excited])}
    (if @(rf/subscribe [:greet/excited?]) "calm down" "get excited")]])

;; --- Seed app-db, then hand the view back to be rendered ---
(rf/dispatch-sync [:greet/initialise])
[greeter]
```

Look at what the view does *not* do. It does not store the typed name in a local field — there's no `useState`, no Reagent-local atom. It reads the name from a subscription and writes it back through a dispatch on every keystroke; the *single source of truth* is `app-db`, and the input is just a window onto it. It does not compute the greeting string — that's `:greet/message`, a [layer-2 subscription](05-subscriptions.md), so the view receives the finished message and merely displays it. The view is three boundaries and a layout: subscribe to the message, subscribe to the name, dispatch on change, dispatch on click, arrange in hiccup. Boring. Correct. Hard to break.

> **Try it.** Move the excitement into the subscription instead of the view. Right now the greeting's punctuation is decided in `:greet/message` — good. But suppose you were tempted to format it *in the view*: change the message `:p` to `[:p (str @(rf/subscribe [:greet/name]) "!!!")]` and delete the `:greet/message` sub usage. Re-evaluate. It still works — *and that's exactly the trap*. Now the view is computing, and if a second view wanted the same greeting it'd compute it again. Put it back. The next section is the rule that tells you why.

## The one rule: views compute hiccup only

There is exactly one discipline that views must keep, and it pays for itself within about a day of writing real screens:

> **Views compute hiccup only. Everything else — sorting, filtering, formatting, deriving, joining — happens in a subscription.**

The temptation is relentless and it always looks innocent. A view reads a list from a subscription, and the list is *almost* what the screen needs — one little `sort-by` here, one `.toFixed` there for the price, and we're done. Don't. Here's the before, with the view quietly doing two jobs that aren't its own:

```clojure
;; Before — the view computes. Both the sort and the price-format
;; re-run on EVERY re-render of this view (or any ancestor).
(rf/reg-view product-list []
  [:ul
   (for [p (sort-by :name @(subscribe [:products]))]
     ^{:key (:id p)} [:li (:name p) " — $" (.toFixed (:price p) 2)])])
```

And the after, with the derivation pushed up into a [subscription](05-subscriptions.md) where it belongs:

```clojure
;; After — the sub computes once per change to :products; the view renders.
(rf/reg-sub :products/display
  :<- [:products]
  (fn [products _]
    (->> products
         (map #(update % :price (fn [n] (.toFixed n 2))))
         (sort-by :name))))

(rf/reg-view product-list []
  [:ul
   (for [p @(subscribe [:products/display])]
     ^{:key (:id p)} [:li (:name p) " — $" (:price p)])])
```

Read the "after" view and ask it its job: *all I do is walk the list and emit `<li>`s.* That's a view that knows what it's for.

Why the rule pays, mechanically, ties straight back to the last chapter. A view re-renders any time *any* value it derefs changes — and ancestors re-rendering can trigger it too. So a `sort-by` living in the view re-runs on every one of those, regardless of whether the list actually moved. The same `sort-by` living in a `:<-` sub re-runs *only when `:products` changes*, sits in the [subscription cache](05-subscriptions.md#why-the-graph-is-fast-the-equality-gate), and is *shared* by every view that wants the sorted list — compute once, read many. Compute-in-view is the single most common way people accidentally make a re-frame2 app slow, and the fix is always the same: if it isn't structurally turning data into hiccup, it belongs upstream in a subscription. (The full performance story — what compute-in-views costs and the other ways apps get slow — is [15 — Performance](15-performance.md).)

## What views don't do — and the one trap inside it

"Pure function of subscriptions to hiccup" rules a few things out, and one of them is subtle enough to bite people who otherwise have the idea cold. Stated as the rule:

> **A view does not dispatch from its render body, does not attach native DOM listeners (`addEventListener`, `setTimeout`, raw `requestAnimationFrame`), and does not own imperative library lifecycles.**

The first part — don't dispatch *during render* — is just purity: rendering is supposed to compute hiccup, and dispatching from inside the render pass means rendering has side effects, which breaks the "view is a pure function" contract and the cascade's ordering with it. Dispatch from *event handlers* (`:on-click`, `:on-change`) — those fire later, in response to something happening, not while you're computing the screen.

The subtle part is the second clause, and here's the mechanism, because it's the kind of thing that "works" right up until it spectacularly doesn't. When you write `:on-click #(dispatch [:inc])` in hiccup, the substrate adapter *wraps* that lambda at render time so the eventual callback closes over the surrounding [frame](18-frames.md). That's the invisible service that makes a bare `dispatch` inside an `:on-click` route to the right frame without you ever writing `:frame` explicitly — and it covers every React-synthetic prop: `:on-change`, `:on-key-down`, `:on-animation-end`, all of them.

What is *not* wrapped is anything you attach imperatively from inside the render body — `(.addEventListener el "animationend" ...)`, a raw `js/setTimeout`, a hand-rolled `requestAnimationFrame`. Those fire later, on a fresh stack, with no frame in scope, so a `dispatch` from inside them silently routes to the default frame. The view *works* in a single-frame app and then breaks the first time it's rendered inside a frame-provider — a failure that doesn't show up until you build a story canvas or a split-pane and is miserable to track down. So:

```clojure
;; WRONG — the listener is attached imperatively, outside the synthetic-event
;; system; the dispatch fires on a fresh stack with no frame and silently
;; routes to the default frame.
(rf/reg-view tile [props]
  [:div {:ref (fn [el]
                (when el
                  (.addEventListener el "animationend"
                    #(dispatch [:tile/finished]))))}])

;; RIGHT — :on-animation-end is a React-synthetic prop; the adapter wraps it;
;; the dispatch closes over the surrounding frame.
(rf/reg-view tile [props]
  [:div {:on-animation-end #(dispatch [:tile/finished])}])
```

The rule of thumb: if there's a synthetic-event prop for what you need, use it and the framework keeps the frame straight for you. If there genuinely isn't one — a `setTimeout`, a `fetch`, an `IntersectionObserver`, a WebSocket — that's not a view's job at all; it's a registered effect, which captures the frame at registration time and dispatches its reply event with the frame already resolved. That's the subject of [chapter 07](07-effects-and-coeffects.md), and the HTTP instance of it is [chapter 10](10-http.md). For genuinely imperative mount-time work against a specific DOM node — a charting library, a map widget — the substrate's lifecycle hook is the documented escape hatch, but it's an escape hatch, not a pattern, and you'll know when you've earned it.

## Why this is the good news

Step back. The view layer is the smallest, dullest part of a re-frame2 app: pure functions from subscription values to hiccup, with two one-way boundaries — subscribe in, dispatch out — and a single rule that they compute hiccup and nothing else. There's no state in them to get out of sync, no effects in them to misfire, no lifecycle in them to leak. When something renders wrong, the bug is almost never *in the view* — it's in the data the view was handed, which means you go look at a [subscription](05-subscriptions.md) or an [event handler](04-events-and-the-cascade.md), both of which are pure functions you can test without a browser ([chapter 13](13-testing.md)).

That's the whole pitch for boring views. You took the part of the app where everything used to go wrong and you made it the part where, structurally, almost nothing *can*. Your render functions are boring, derivative, and impossible to get into a weird state — and that is not a limitation you're apologising for. It's the feature.

Of course, a real app still has to talk to the world. The trick is preserving this boring view layer while letting handlers ask for time, randomness, storage, HTTP, navigation, and everything else the browser can do. That is the effects/coeffects boundary.
