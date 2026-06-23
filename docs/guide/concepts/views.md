# Views: pure functions of data

A view's whole job is to turn data into a description of the screen. It reads some application state, returns a picture of what the screen should look like for that state, and that's it — no stored state of its own, no side effects, no lifecycle to manage. When the state changes, the framework re-runs the view and updates the DOM to match. So the entire contract fits in one sentence, and it's worth holding onto as you read the rest of this page:

> **A view is a pure function from subscription values to hiccup.**

This page builds that sentence up one piece at a time. We'll start with the output — *hiccup*, the data a view returns — then add the two ways a view talks to the rest of the app, then look at a complete view running live, and finally cover the one discipline that keeps views fast and the handful of escape hatches you'll occasionally reach for.

> **For JavaScript developers.** A re-frame2 view is a React function component with everything except rendering removed. There's no `useState`, because state lives in [app-db](app-db.md) — your app's single state map — and arrives through [subscriptions](subscriptions.md). There's no `useEffect`, because anything that touches the world is an [effect](effects-and-coeffects.md), produced as data by an event handler and never run from a component. And there's no JSX, because a view returns plain Clojure data. The design here is in what got *subtracted*, not in anything added.

## Hiccup: the screen is data

A view returns nested Clojure vectors shaped like the DOM they describe:

```clojure
[:div.cart
 [:h1 "Cart"]
 [:p "Items: " [:strong 3]]]
```

The rules are quick to learn:

- A vector whose first element is a keyword is an **element**. `:div.cart` is a `<div class="cart">` — the `.class` shorthand comes from CSS selectors, and `:input#email.wide` adds an id too.
- A map in second position is the **attributes**: `[:button {:on-click f :disabled true} "Go"]`.
- Everything after that is **children**. Strings become text; nested vectors become nested elements.

The important word is *data*. Not "data-like" — these are actual vectors, maps, and keywords, the same structures you manipulate everywhere else in the program. So you build screens with ordinary code and no template syntax:

```clojure
(into [:ul] (for [item items] [:li (:name item)]))
```

Because hiccup is just data, views compose like any other values: a function can take hiccup and return hiccup, you can `pprint` a view's output and read it, and a pure hiccup-to-HTML emitter can run on the JVM — which is how [server-side rendering](ssr.md) renders the *same* views without a browser.

> **For JavaScript developers.** Template strings can do none of this. They don't compose, they don't diff, and string-built markup is where injection bugs come from. Hiccup is closer in spirit to React's `createElement` calls — a tree of data describing the UI — except it's plain literals you can map, filter, and pass around, with no build-time transform.

> **Going deeper.** The full render-tree contract — what a conformant render-tree must be, and what survives serialisation across the JVM/browser boundary for SSR — is [spec 004 — Views](../../../spec/004-Views.md). Hiccup is the CLJS render-tree; other hosts use their own shape behind the same contract.

## Subscribe in, dispatch out

A static screen isn't much use. A view needs to read live application state, and it needs to react to clicks and typing. It does both through exactly two openings — and both are one-way.

**Reading state in: the view derefs a subscription.**

```clojure
@(rf/subscribe [:cart/total])
```

This declares "I depend on this derived value. Re-run me when it changes." That's the only way a view learns application state. It doesn't read `app-db` directly, and it doesn't receive a props object threaded down through ten ancestors. It asks the [derivation graph](subscriptions.md) for exactly the slice it needs, *by name*.

**Sending events out: the view dispatches.** Wire a `dispatch` to an event handler:

```clojure
[:button {:on-click #(rf/dispatch [:cart/add id])} "Add"]
```

A dispatch *announces that something happened* by handing the framework an event — a plain vector naming what occurred — and returns immediately. It does not change state. It does not know or care what the handler will do with it. The [cascade](events-and-the-cascade.md) takes it from there: the handler runs, `app-db` moves, subscriptions repropagate, and at the very end this view re-renders to match.

Notice the shape of the round trip, because it's the whole idea. A click never mutates the number it sits next to. It dispatches an event that produces a *new* `app-db`, which flows back through a subscription. The view can't short-circuit that path, because it holds no state to short-circuit with.

> **Coming from Redux?** `subscribe` is `useSelector` and `dispatch` is `dispatch` — the same unidirectional loop. The difference is that the "selector" is a named, cached node in a derivation graph (see [subscriptions](subscriptions.md)) rather than a function you pass inline, and the event is dispatched as data rather than dispatched through a thunk.

## A view, live

Here is a view doing its whole job: subscribe in, dispatch out, hiccup between. Click into the cell, press **Ctrl-Enter** (**Cmd-Enter** on macOS) to evaluate, then click the buttons.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; Adapted from examples/reagent/counter/core.cljs.
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

That's a complete view. You use it by referencing it inside other hiccup — `[counter]` — and a view that takes arguments takes them like any function: `[labelled-counter "Apples"]`.

!!! note "Try it"

    Change the last form to `[:div [counter] [counter]]` and re-evaluate. Click either counter: both move, because neither owns the number — each is a window onto the same `app-db` value. There is no local copy to fall out of sync.

## `reg-view`: registering a view for project code

The cell above writes the view as a plain `defn`, and that genuinely *is* a view. But in real project code you'll write the registered form instead:

```clojure
;; cf. examples/reagent/counter/core.cljs
(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])
```

A `reg-view` and a `defn` define the **same render function**. `reg-view` adds exactly two things on top:

1. **A registry entry.** The view is registered under an auto-derived id — `(keyword *ns* 'counter)`, e.g. `:my.app/counter` — so tooling can list it, jump to its source, and resolve a rendered DOM node back to the view that produced it.

2. **Frame-aware injection.** Inside the body, the unqualified `dispatch` and `subscribe` are locals, bound to the [frame](frames.md) — the isolated re-frame2 world — that the view renders under. That binding is what lets the same view mount in several isolated frames at once, each reading and writing only its own world.

So to *read* a `reg-view` body as a `defn`, map `dispatch` → `rf/dispatch` and `subscribe` → `rf/subscribe`. Nothing else differs about the render function.

> **Why every live cell uses `defn`.** The cells in this guide run in a functions-only environment where `reg-view` isn't available, `rf/dispatch` / `rf/subscribe` resolve as plain functions, and the cell supplies the frame scope. So a cell and a prose listing of the same component differ in exactly this one way — same component, two spellings. In project code, write `reg-view`.

### The three call shapes

`reg-view` is *defn-shape*, and like `defn` it takes an optional docstring and lets you override the auto-derived id. There are three call shapes; they all produce the same registered view, and the choice is only about what you want at the source level:

```clojure
;; 1. Bare — id auto-derived as (keyword *ns* "cart-line"), e.g. :my.app.cart/cart-line
(rf/reg-view cart-line [item]
  [:tr [:td (:name item)] [:td (:qty item)]])

;; 2. With a docstring — lands in the registry's :doc field, so tooling shows it
(rf/reg-view cart-line
  "One row in the cart table; receives a normalised item map."
  [item]
  [:tr [:td (:name item)] [:td (:qty item)]])

;; 3. With an explicit id via metadata — when the symbol shouldn't drive the id
;;    (a stable id across rename, or matching an external contract)
(rf/reg-view ^{:rf/id :cart/line} cart-line [item]
  [:tr [:td (:name item)] [:td (:qty item)]])
```

In all three the symbol `cart-line` is `def`-ed, so you write `[cart-line item]` from sibling code regardless. The docstring and the `^{:rf/id …}` override are the only two knobs; the body is just hiccup.

> **Gotcha.** Get the *shape* wrong — pass a render fn instead of an args vector, or hand it a Form-3 `(reagent.core/create-class …)` — and the macro refuses at compile time with a stable error pointing you at `re-frame.core/reg-view*` (the plain-fn surface described under *Tooling and library registration* below). The macro is the defn-shaped 80% lane; those cases have a different door.

> **From re-frame v1.** `reg-view` is new, and it's more than sugar. In v1 a view was just a `defn` and there was an implicit default frame for it to find. That default frame is gone, so registration is now how a view finds its frame — which is why the macro injects frame-bound `dispatch`/`subscribe`. The full delta is in [From re-frame v1](../25-from-re-frame-v1.md).

### Plain `defn` views, and when they break

A plain `defn` view still works — but only when it renders *inside* a frame scope it can read. The qualified `rf/dispatch` / `rf/subscribe` resolve their frame from the surrounding React context, and a mounted app's frame-provider hands a frame only to **registered** views. An unregistered `defn` that derefs `rf/subscribe` under a non-default provider fails loudly with `:rf.error/no-frame-context`.

So the reverse rewrite — turning a `reg-view` into a `defn` — is not free. If a view genuinely must stay an unregistered plain fn, it captures a `(rf/frame-handle)` at render time and uses its bound ops instead. The full rule is in [spec 004](../../../spec/004-Views.md).

> **A view that runs setup on mount.** If a screen needs an event to fire when its frame comes up — load the cart, hydrate a form — don't `dispatch` from the render body. That couples reads to writes and, under a reactive substrate, can loop the render. Name the setup as an event and list it in the frame's `:initial-events`:
>
> ```clojure
> (rf/reg-frame :cart {:initial-events [[:cart/load]]})
> ```
>
> The events fire once, synchronously, in order, the moment the frame is created, and they show up in the trace by name. That's the Form-1-friendly home for "do this on mount" — see [Frames](frames.md).

> **From re-frame v1.** Reagent's **Form-2** and **Form-3** view shapes are still supported, mostly for migration:
>
> **Form-2** is a view whose body returns *another fn*. The outer fn runs once per mount (a place for per-mount setup that genuinely depends on props); the inner fn is the render fn, re-run each render. Lexical closure does the right thing — the injected `dispatch` / `subscribe` are in scope for both:
>
> ```clojure
> (rf/reg-view counter-with-init [label]
>   (dispatch [:counter/initialise label])    ;; outer: fires once on mount
>   (fn [label]                               ;; inner: the actual render fn
>     [:button {:on-click #(dispatch [:counter/inc])}
>      (str label ": " @(subscribe [:counter/value]))]))
> ```
>
> Prefer Form-1 + a frame `:initial-events` step over Form-2 for *stable* setup — the outer fn hides a mount-time side effect that doesn't appear at the call site. Reach for Form-2 only when the setup truly needs the per-mount props.
>
> **Form-3** (`reagent.core/create-class`, with `:component-did-mount` / `:component-will-unmount`) is the escape hatch for wrapping a stateful imperative library (a chart, a map, a code editor) that owns its own DOM. It is *out of scope for the `reg-view` macro* — register it through `reg-view*` instead, where the body can be any callable. Full detail in [spec 004 §Form-1, Form-2, Form-3](../../../spec/004-Views.md#form-1-form-2-form-3-components).

??? note "Tooling and library registration: `reg-view*` and `(rf/view id)`"

    `reg-view` and Var references are the whole app-facing story — register a screen, render it by name. There's a second, separate lane you'll only meet if you write *tooling* or *library* code:

    - `reg-view*`, the plain-fn surface beneath the macro, registers a view from a **computed id** or a non-`defn` render fn.
    - `(rf/view id)` resolves a registered view by id at render time.

    That pairing is how a tool panel or story canvas hosts a view it doesn't know at the call site, how a code-gen pipeline emits views from a manifest, and how Reagent class components (`create-class`) register. If you're building screens, you won't reach for either — they're the host/tooling entry points, not the app-facing one. The full split is in the API reference under [Tooling / host view registration](../../api/02-views.md#tooling--host-view-registration); the contract is in [spec 004](../../../spec/004-Views.md).

## The one rule: views compute hiccup only

Now the single discipline that keeps views fast, correct, and easy to debug. It pays for itself within a day of writing real screens:

> **Views compute hiccup only. Everything else — sorting, filtering, formatting, deriving, joining — happens in a subscription.**

The temptation always looks innocent. The subscribed list is *almost* what the screen needs, so you reach for one little `sort-by` here, one `.toFixed` there. Don't. Here's the *before*, with the view quietly doing two jobs that aren't its own:

```clojure
;; Before — the view computes. The sort and the price-format re-run on
;; EVERY re-render of this view, whether or not the list changed.
(rf/reg-view product-list []
  [:ul
   (for [p (sort-by :name @(subscribe [:products]))]
     ^{:key (:id p)} [:li (:name p) " — $" (.toFixed (:price p) 2)])])
```

And the *after*, with the derivation pushed up into a [subscription](subscriptions.md) where it belongs:

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

> **Why this matters.** A view re-runs whenever any value it derefs changes, and an ancestor re-render can trigger it too. A `sort-by` in the view re-runs on every one of those. The same `sort-by` in a sub re-runs *only when `:products` changes*, sits in the subscription cache, and is shared by every view that wants the sorted list. Compute once, read many.

!!! warning "Compute-in-view is the most common way apps get slow"

    Pushing computation into the view is the single most common way re-frame2 apps get accidentally slow, because the work re-runs on every render instead of only when its inputs change. The hunt and the fix are in [Find and fix a slow view](../how-to/fix-a-slow-view.md).

> **What's the `^{:key (:id p)}` for?** Same as React's `key`. When you emit a *list* of elements, give each a stable identity so the substrate diffs by identity instead of position — insert or remove one item and only that item's DOM moves, not everything below it. Attach it as metadata on the element (`^{:key v} [:li ...]`) and key by something durable from the data, never the loop index. More on why this matters for big lists in [Find and fix a slow view](../how-to/fix-a-slow-view.md).

## The trap: imperative listeners lose the frame

Hiccup's event attrs — `:on-click`, `:on-change`, `:on-animation-end`, the whole synthetic-event surface — are wrapped by the substrate at render time, so a `dispatch` inside them is routed to the right [frame](frames.md) automatically. Anything you attach *imperatively* from a render body is **not** wrapped, though. It fires later, on a fresh stack, with no frame in scope, and the dispatch fails loudly with `:rf.error/no-frame-context`:

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

Rule of thumb: if a synthetic prop exists for what you need, use it. If one doesn't — a `js/setTimeout`, a `fetch`, an `IntersectionObserver`, a WebSocket — that work was never a view's job. It belongs in a registered [effect](effects-and-coeffects.md), which captures the frame for you. The loud failure is deliberate: the runtime refuses to guess which world a frameless dispatch belongs to.

!!! warning "It's still wrong inside a `reg-view`"

    Inside a `reg-view` body the injected `dispatch` happens to survive, because it captured its frame at render time. But the imperative attach is wrong there too: render bodies re-run, each run adds another listener, and nothing ever removes them. Attach through the attrs map either way.

> **Going deeper.** Why the runtime fails fast rather than synthesising a default frame — and the carried-frame mechanism that lets a registered effect's closure dispatch back into the right world — is the EP-0002 *carried invariant*. The full account is in [Frames: isolated worlds](frames.md) and [spec 004](../../../spec/004-Views.md).

## Targeting a different frame, deliberately

The injected `dispatch` / `subscribe` always read *the frame the view is rendering under* — that's the whole point of the injection, and it's what you want almost always. Once in a while a view needs to read or write a *different* frame than its own: a side-by-side comparison panel, a story-tool variant that drives a sibling variant, a debug overlay watching another world. For that, the qualified two-arg forms take a `{:frame …}` opt that names the target explicitly and bypasses the injection:

```clojure
;; Subscribe to / dispatch into a named OTHER frame, from a view rendering in this one.
(let [their-total @(rf/subscribe [:cart/total] {:frame :other-tab})]
  [:button {:on-click #(rf/dispatch [:cart/clear] {:frame :other-tab})}
   (str "Other tab: " their-total)])
```

This is the deliberate escape hatch, not the daily path — reaching across frames is the exception that proves the isolation rule. To re-point a whole *subtree* of children at an existing frame instead of opt-ing call by call, scope them with `rf/with-frame` (or `rf/frame-provider-existing` across a React boundary) — see [Frames](frames.md).

## The substrate seam, in one paragraph

Everything upstream of the view — handlers, subscriptions, effects, `app-db` itself — is operations on Clojure data, and never names a rendering library. The one place re-frame2 touches React is the seam where hiccup becomes pixels and a click becomes a dispatch. That seam is an **adapter**: a small map of functions named once at boot, `(rf/init! reagent-adapter/adapter)`. Port an app from Reagent to UIx or Helix and your handlers, subs, and `app-db` don't change by a character. Only the `init!` line and the view bodies' notation change, because the view body is the one place the substrate is visible. The practical how-to is [Use UIx, Helix, or reagent-slim](../how-to/use-uix-helix-or-slim.md); the adapter contract itself is [spec 006 — Reactive substrate](../../../spec/006-ReactiveSubstrate.md).

## When something renders wrong

Step back and notice what all this buys you at debugging time. A view holds no state, runs no effects, owns no lifecycle. It is a pure function from subscription values to hiccup, so there is almost nothing *in* it to break:

> **When something renders wrong, the bug is almost never in the view — it's in the data the view was handed.**

So don't debug views. Inspect data. Follow the value upstream: the [subscription](subscriptions.md) that computed it, then the [event handler](events-and-the-cascade.md) that wrote it. Both are pure functions you can test without a browser. With Xray open, find the event row for the action that preceded the bad render and look at the data it produced. The wrong value is usually sitting there, visibly wrong, before the view ever ran ([Debug with Xray](../how-to/debug-with-xray.md)).

The render itself is observable too, which helps with the *other* failure mode — not "wrong value" but "why did this re-render at all?" Each render emits a trace entry keyed by a `:render-key` — the tuple `[view-id instance-token]`, where the token disambiguates two mounted instances of the same view (`[:cart/row 1473]` vs `[:cart/row 1474]`). The entry also carries what *triggered* the render (the sub or props that changed) and the view's render args, so an over-rendering view shows its cause rather than leaving you to guess.

> **Why register the views you care about.** Plain unregistered fns render under the fallback key `[:rf.view/anonymous nil]` — a registered `reg-view` is what gives a render a *name* in the trace. That's one more reason to register the views you want to see. All of this sits behind the dev-build gate and elides completely in production. The slow-render hunt that uses these signals is [Find and fix a slow view](../how-to/fix-a-slow-view.md).
