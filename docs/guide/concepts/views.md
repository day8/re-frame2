# Views: pure functions of data

If you write React, you already write view functions, and a re-frame2 view is a React function component with everything except rendering removed. There's no `useState`, because state lives in [app-db](app-db.md) — your app's single state map — and arrives through [subscriptions](subscriptions.md), the named queries a view reads from. There's no `useEffect` either: anything that touches the world is an [effect](effects-and-coeffects.md) — a described side effect like an HTTP call — and it's data produced by an event handler, never run from a component. And there's no JSX, because a view returns **hiccup**, which is plain Clojure data. What's left is a function from data to a description of the screen. The design here is in what got subtracted, not in anything added.

> **Coming from re-frame v1?** `reg-view` is new and is more than sugar — registration is how a view finds its frame now that the implicit default frame is gone (the equivalence section below has the details) — full delta in [From re-frame v1](../25-from-re-frame-v1.md).

## Subscribe in, dispatch out

A view has exactly two openings to the rest of the app, and both are one-way.

**Reading state in:** the view derefs a subscription. `@(rf/subscribe [:cart/total])` declares "I depend on this derived value. Re-run me when it changes." That is the only way a view learns application state. It doesn't read `app-db` directly. It doesn't receive a props object threaded down through ten ancestors. It asks the [derivation graph](subscriptions.md) for exactly the slice it needs, by name.

**Sending events out:** the view dispatches — that is, it announces that something happened by handing an event (a plain vector naming what occurred) to the framework. Wire `#(rf/dispatch [:cart/add id])` to an `:on-click` or `:on-change`, and it announces "something happened" and returns immediately. It does not change state. It does not know or care what the handler — the pure function that will process that event — will do. The [cascade](events-and-the-cascade.md) takes it from there: the handler runs, `app-db` moves, subscriptions repropagate, and at the very end this view re-renders to match.

Notice the shape of the round trip, because it's the whole idea. A click never mutates the number it sits next to. It dispatches an event that produces a new `app-db` that flows back through a subscription. The view can't short-circuit that path, because it holds no state to short-circuit with. The whole contract fits in one sentence: a view is a pure function from subscription values to hiccup.

## Hiccup: the screen is data

A view returns nested Clojure vectors shaped like the DOM they describe:

```clojure
[:div.cart
 [:h1 "Cart"]
 [:p "Items: " [:strong 3]]]
```

A vector whose first element is a keyword is an element. `:div.cart` is a `<div class="cart">` (the `.class` shorthand comes from CSS selectors; `:input#email.wide` adds an id). A map in second position is the attributes: `[:button {:on-click f :disabled true} "Go"]`. Everything after that is children. Strings become text, nested vectors become nested elements.

The important word is *data*. Not "data-like", the way JSX is — these are actual vectors, maps, and keywords, the same structures you manipulate everywhere else in the program. So you build screens with ordinary code and no template syntax: `(into [:ul] (for [item items] [:li (:name item)]))`. You can `pprint` a view's output and read it. Functions can take hiccup and return hiccup, so views compose like any other values. And because a pure hiccup-to-HTML emitter can run on the JVM, [server-side rendering](ssr.md) can render the *same* views without a browser. Template strings can do none of that. They don't compose, they don't diff, and string-built markup is where injection bugs come from. The full render-tree contract, including what survives serialisation, is [spec 004 — Views](../../../spec/004-Views.md).

## The `defn` / `reg-view` equivalence

The simplest view is a `defn`:

```clojure
(defn counter []
  [:div
   [:button {:on-click #(rf/dispatch [:counter/dec])} "-"]
   [:span @(rf/subscribe [:counter/value])]
   [:button {:on-click #(rf/dispatch [:counter/inc])} "+"]])
```

You use it by referencing it inside other hiccup — `[counter]` — and a view that takes arguments takes them like any function: `[labelled-counter "Apples"]`. In project code you'll write the registered form:

```clojure
;; cf. examples/reagent/counter/core.cljs
(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])
```

Here's the part worth pinning down, because it's the one thing that trips people up: a `reg-view` and a `defn` define the *same render function*. `reg-view` adds exactly two things on top.

The first is **a registry entry.** The view is registered under an auto-derived id — `(keyword *ns* 'counter)` here — so tooling can list it, jump to its source, and resolve a rendered DOM node back to the view that produced it.

The second is **frame-aware injection.** Inside the body, unqualified `dispatch` and `subscribe` are locals, bound to the frame — the isolated re-frame2 world — that the view renders under. That binding is what lets the same view mount in several isolated [frames](frames.md) at once, each reading and writing only its own world. Nothing else differs about the render function.

So to *read* a `reg-view` body as a `defn`, map `dispatch` → `rf/dispatch` and `subscribe` → `rf/subscribe`. The reverse, though, is not a free rewrite, which is worth knowing before you try it: the qualified forms resolve their frame from the surrounding scope, and a mounted app's frame-provider hands a frame only to *registered* views. An unregistered `defn` deref'ing `rf/subscribe` under it fails loudly with `:rf.error/no-frame-context`. (A plain fn that must stay unregistered captures a `(rf/frame-handle)` at render instead — [spec 004](../../../spec/004-Views.md).)

Every live cell in this guide uses the `defn` spelling, because the cells run in a functions-only environment. `reg-view` isn't available there, `rf/dispatch` / `rf/subscribe` resolve as plain functions, and the cell environment supplies the frame scope that makes the qualified forms resolve. So when a cell and a prose listing differ in this one way, this section is why: same component, two spellings. In project code, write `reg-view`.

??? note "The other lane: tooling and library registration"

    `reg-view` and Var references are the whole app-facing story — register a screen, render it by name. There's a second, separate lane you'll only meet if you write *tooling* or *library* code: `reg-view*`, the plain-fn surface beneath the macro, which registers a view from a **computed id** or a non-`defn` render fn, and `(rf/view id)`, which resolves a registered view by id at render time. That pairing is how a tool panel or story canvas hosts a view it doesn't know at the call site, how a code-gen pipeline emits views from a manifest, and how Reagent class components (`create-class`) register. If you're building screens, you won't reach for either — they're the host/tooling entry points, not the app-facing one. The full split is in the API reference under [Tooling / host view registration](../../api/02-views.md#tooling--host-view-registration); the contract is in [spec 004](../../../spec/004-Views.md).

## A view, live

Here is a `defn` view doing its whole job: subscribe in, dispatch out, hiccup between. Click into the cell, press **Ctrl-Enter** (**Cmd-Enter** on macOS) to evaluate, then click the buttons.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; Adapted from examples/reagent/counter/core.cljs (defn spelling for the cell).
(rf/reg-event :views.counter/initialise
  (fn [{:keys [db]} _event] {:db (assoc db :views.counter/value 5)}))

(rf/reg-event :views.counter/inc
  (fn [{:keys [db]} _event] {:db (update db :views.counter/value inc)}))

(rf/reg-event :views.counter/dec
  (fn [{:keys [db]} _event] {:db (update db :views.counter/value dec)}))

(rf/reg-sub :views.counter/value
  (fn [db _query] (:views.counter/value db)))

(defn counter []
  [:div
   [:button {:on-click #(rf/dispatch [:views.counter/dec])} "-"]
   [:span {:style {:margin "0 1em"}} @(rf/subscribe [:views.counter/value])]
   [:button {:on-click #(rf/dispatch [:views.counter/inc])} "+"]])

(rf/dispatch-sync [:views.counter/initialise])
[counter]
```

!!! note "Try it"

    Change the last form to `[:div [counter] [counter]]` and re-evaluate. Click either counter: both move, because neither owns the number — each is a window onto the same `app-db` value. There is no local copy to fall out of sync.

## The one rule: views compute hiccup only

Views must keep exactly one discipline, and it pays for itself within a day of writing real screens:

> **Views compute hiccup only. Everything else — sorting, filtering, formatting, deriving, joining — happens in a subscription.**

The temptation always looks innocent. The subscribed list is *almost* what the screen needs, so you reach for one little `sort-by` here, one `.toFixed` there. Don't — and here's the before, with the view quietly doing two jobs that aren't its own:

```clojure
;; Before — the view computes. The sort and the price-format re-run on
;; EVERY re-render of this view, whether or not the list changed.
(rf/reg-view product-list []
  [:ul
   (for [p (sort-by :name @(subscribe [:products]))]
     ^{:key (:id p)} [:li (:name p) " — $" (.toFixed (:price p) 2)])])
```

And the after, with the derivation pushed up into a [subscription](subscriptions.md) where it belongs:

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

Ask the "after" view what it does: all it does is walk the list and emit `<li>`s. That's a view that knows what it's for.

> **What's the `^{:key (:id p)}` for?** Same as React's `key`. When you emit a *list* of elements, give each a stable identity so the substrate diffs by identity instead of position — insert or remove one item and only that item's DOM moves, not everything below it. Attach it as metadata on the element (`^{:key v} [:li ...]`) and key by something durable from the data, never the loop index. More on why this matters for big lists in [Find and fix a slow view](../how-to/fix-a-slow-view.md).

Here is why this pays. A view re-runs whenever any value it derefs changes, and an ancestor re-render can trigger it too. A `sort-by` in the view re-runs on every one of those. The same `sort-by` in a sub re-runs *only when `:products` changes*, sits in the subscription cache, and is shared by every view that wants the sorted list. Compute once, read many.

!!! warning "Compute-in-view is the most common way apps get slow"

    Pushing computation into the view is the single most common way re-frame2 apps get accidentally slow, because the work re-runs on every render instead of only when its inputs change. The hunt and the fix are in [Find and fix a slow view](../how-to/fix-a-slow-view.md).

## The trap: imperative listeners lose the frame

Hiccup's event attrs — `:on-click`, `:on-change`, `:on-animation-end`, the whole synthetic-event surface — are wrapped by the substrate at render time, so a `dispatch` inside them is routed to the right [frame](frames.md) automatically. Anything you attach *imperatively* from a render body is not wrapped, though. It fires later, on a fresh stack, with no frame in scope, and the dispatch fails loudly with `:rf.error/no-frame-context`:

```clojure
;; WRONG — imperative listener: the callback fires on a fresh stack with no
;; frame in scope; the dispatch raises :rf.error/no-frame-context.
(defn tile []
  [:div {:ref (fn [el]
                (when el
                  (.addEventListener el "animationend"
                    #(rf/dispatch [:tile/finished]))))}])

;; RIGHT — :on-animation-end is a synthetic prop; the adapter wraps it
;; and the dispatch carries the frame.
(defn tile []
  [:div {:on-animation-end #(rf/dispatch [:tile/finished])}])
```

!!! warning "It's still wrong inside a `reg-view`"

    Inside a `reg-view` body the injected `dispatch` happens to survive, because it captured its frame at render time. But the imperative attach is wrong there too: render bodies re-run, each run adds another listener, and nothing ever removes them. Attach through the attrs map either way.

Rule of thumb: if a synthetic prop exists for what you need, use it. If one doesn't — a `js/setTimeout`, a `fetch`, an `IntersectionObserver`, a WebSocket — that work was never a view's job. It belongs in a registered [effect](effects-and-coeffects.md), which captures the frame for you. The loud failure is deliberate: the runtime refuses to guess which world a frameless dispatch belongs to. Why, and the carried-frame mechanism behind it, is explained in [Frames: isolated worlds](frames.md).

## The substrate seam, in one paragraph

Everything upstream of the view — handlers, subscriptions, effects, `app-db` itself — is operations on Clojure data, and never names a rendering library. The one place re-frame2 touches React is the seam where hiccup becomes pixels and a click becomes a dispatch. That seam is an **adapter**: a small map of functions named once at boot, `(rf/init! reagent-adapter/adapter)`. Port an app from Reagent to UIx or Helix and your handlers, subs, and `app-db` don't change by a character. Only the `init!` line and the view bodies' notation change, because the view body is the one place the substrate is visible. The practical how-to is [Use UIx, Helix, or reagent-slim](../how-to/use-uix-helix-or-slim.md); the adapter contract itself is [spec 006 — Reactive substrate](../../../spec/006-ReactiveSubstrate.md).

## When something renders wrong

Step back and notice what all this buys you at debugging time. A view holds no state, runs no effects, owns no lifecycle. It is a pure function from subscription values to hiccup, so there is almost nothing *in* it to break:

> **When something renders wrong, the bug is almost never in the view — it's in the data the view was handed.**

So don't debug views. Inspect data. Follow the value upstream: the [subscription](subscriptions.md) that computed it, then the [event handler](events-and-the-cascade.md) that wrote it. Both are pure functions you can test without a browser. With Xray open, find the event row for the action that preceded the bad render and look at the data it produced. The wrong value is usually sitting there, visibly wrong, before the view ever ran ([Debug with Xray](../how-to/debug-with-xray.md)).

---

**You can now:**

- name a view's only two openings — subscribe in, dispatch out — and explain why the round trip always goes through the cascade
- read `reg-view` and `defn` views as the same component, and say exactly what the macro adds (a registry entry and frame-aware `dispatch`/`subscribe`)
- push computation out of views into subscriptions, and spot the compute-in-view smell in review
- key a dynamic list with stable `^{:key …}` metadata so the substrate diffs by identity, not position
- avoid the imperative-listener trap, and say why the failure is loud instead of silent
