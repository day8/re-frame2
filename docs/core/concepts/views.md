# Views: pure functions of data

A [view](../glossary.md#view) has one job: turn data — the values [subscriptions](subscriptions.md) spent the last page deriving — into a picture of the screen.

Not "manage local state" — a view stores nothing. Not "fetch what it needs" — a view never touches the world. Not "coordinate a lifecycle" — there's nothing to coordinate. It reads some application state, returns the description of the screen for that state, and it's finished. State changes; the framework re-runs the view; the DOM catches up.

So the entire contract fits in one sentence:

> **A view is a pure function from subscription values to hiccup.**

Give that line a second read, because every heading below is it being unpacked. That's the whole page, right there.

Behind it sits a conviction, and I'd rather own it out loud than have you discover it sideways: **views are derivative, not causal.** For about ten years the React world has organised itself around one gravitational centre — the component. Components own state. Components fetch data. Components route. Components subscribe to stores through whatever `useFoo` hook this year's library plumbed in. Effects colocate with the view tree because the view tree is what the framework can see, so the view tree is where *everything* ends up living — state, IO, navigation, the lot, bolted onto render functions. Too grumpy? Maybe; those tools ship real products. But re-frame2 makes the opposite bet. [Events](../glossary.md#event) update centralised state. [Subscriptions](subscriptions.md) derive values from it. Views sit at the very end of the flow and render whatever arrives. A view is a **window** onto your application's state: it shows you the room. It doesn't get to be the room.

Hold onto the window; we'll be looking through it for the rest of the page. The route: first the output — [*hiccup*](../glossary.md#hiccup), the data a view returns — then the two ways a view talks to the rest of the app, then a complete view running live, and finally the one discipline that keeps views fast, plus the handful of escape hatches you'll occasionally reach for.

??? info "For JavaScript developers"

    A re-frame2 view is a React function component with everything except rendering removed. No `useState` — state lives in [app-db](app-db.md), your app's single state map, and arrives through [subscriptions](subscriptions.md). No `useEffect` — anything that touches the world is an [effect](effects.md), produced as data by an [event handler](../glossary.md#event-handler) and never run from a component. No JSX — a view returns plain Clojure data. The design here is in what got *subtracted*, not in anything added.

## The counter gets components

[Our first app](../first-app.md) rendered the whole counter in one view. Real screens are built from *pieces*, and views compose exactly the way the hiccup they return does — one vector inside another. Here's the counter split into three registered views: a display, a reusable button, and a parent that assembles them:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :initialise (fn [_ _] {:db {:value 0}}))
(rf/reg-event :inc (fn [{:keys [db]} _] {:db (update db :value inc)}))
(rf/reg-event :dec (fn [{:keys [db]} _] {:db (update db :value dec)}))
(rf/reg-sub :value (fn [db _] (:value db)))

;; the new idea: views compose — each piece registered, each pure
(rf/reg-view counter-display []
  [:span {:style {:margin "0 0.5em"}} @(subscribe [:value])])

(rf/reg-view counter-button [label event]
  [:button {:on-click #(dispatch event)} label])

(rf/reg-view counter []
  [:div
   [counter-button "−" [:dec]]
   [counter-display]
   [counter-button "+" [:inc]]])

(rf/reg-frame :app {:initial-events [[:initialise]]})

[rf/frame-provider {:frame :app}
 [counter]]
```

Notes:

1. A child view is used *as data*. `[counter-button "−" [:dec]]` is just a vector whose tail is the child's arguments, so `counter-button` renders twice with different args and never knows it.
2. The display subscribes for itself. The parent doesn't fetch the value and hand it down — so when the value changes, only `counter-display` re-renders.
3. The button takes the *event to announce* as an argument. Passing events as data is how a piece stays reusable without knowing what its click means.

The rest of this page unpacks all three.

## Hiccup: the screen is data

A view returns [hiccup](../glossary.md#hiccup) — nested Clojure vectors shaped like the DOM they describe:

```clojure
[:div.cart
 [:h1 "Cart"]
 [:p "Items: " [:strong 3]]]
```

The rules are quick to learn:

- A vector whose first element is a keyword is an **element**. `:div.cart` is a `<div class="cart">` — the `.class` shorthand comes from CSS selectors, and `:input#email.wide` adds an id too.
- A map in second position is the **attributes**: `[:button {:on-click f :disabled true} "Go"]`.
- Everything after that is **children**. Strings become text; nested vectors become nested elements.
- A vector whose first element is a **view** (not a keyword) renders that view, with the rest of the vector as its arguments — `[counter-button "−" [:dec]]` above. That's the composition rule the counter used.

The important word is *data*. Not "data-like". Actual vectors, maps, and keywords — the same structures you manipulate everywhere else in the program. So you build screens with ordinary code and no template syntax:

```clojure
(into [:ul] (for [item items] [:li (:name item)]))
```

And because hiccup is just data, everything you already know how to do with data works on screens. A function can take hiccup and return hiccup. You can `pprint` a view's output and *read* it. A pure function that walks hiccup and emits an HTML string can run on the server — which is how [server-side rendering](../../ssr/concepts.md) renders the *same* views without a browser in the building.

??? info "For JavaScript developers"

    Template strings can do none of this. They don't compose, they don't diff, and string-built markup is where injection bugs come from. Hiccup is closer in spirit to React's `createElement` calls — a tree of data describing the UI — except it's plain literals you can map, filter, and pass around, with no build-time transform.

??? note "Going deeper"

    Hiccup is the ClojureScript render-tree — the shape that survives serialisation across the JVM/browser boundary. Other hosts use their own render-tree shape behind the same contract.

## Subscribe in, dispatch out

A static screen isn't much use. A view needs to read live application state, and it needs to react to clicks and typing. Two jobs, exactly two openings — and both are one-way.

**Reading state in: the view derefs a [subscription](../glossary.md#subscription).**

```clojure
@(rf/subscribe [:cart/total])
```

This declares "I depend on this derived value. Re-run me when it changes." That's the only way a view learns application state. It doesn't read [app-db](../glossary.md#app-db) directly, and it doesn't receive the value as an argument threaded down through ten ancestors. It asks the [derivation graph](../glossary.md#the-derivation-graph) for exactly the slice it needs, *by name* — via a [query vector](../glossary.md#query-vector), the `[id & args]` shape that names the subscription and keys its cache. (More on subscriptions in [Subscriptions](subscriptions.md).)

**Sending events out: the view [dispatches](../glossary.md#dispatch).** Wire a `dispatch` to an [event handler](../glossary.md#event-handler):

```clojure
[:button {:on-click #(rf/dispatch [:cart/add id])} "Add"]
```

A dispatch *announces that something happened* by handing the framework an [event](../glossary.md#event) — a plain vector naming what occurred — and returns immediately. It does not change state. It does not know or care what the handler will do with it. The [event pipeline](../glossary.md#event-pipeline) takes it from there: the handler runs, `app-db` moves, subscriptions repropagate, and at the very end this view re-renders to match. (The whole traversal is the [Introduction](../introduction.md)'s subject.)

Notice the shape of the round trip, because it's the whole idea. A click never mutates the number it sits next to. It dispatches an event that produces a *new* `app-db`, which flows back through a subscription. The view can't short-circuit that path, because it holds no state to short-circuit with. In window terms: you can see into the room, and you can knock. What happens after the knock is the room's business, not the window's.

??? info "Coming from Redux?"

    `subscribe` is `useSelector` and `dispatch` is `dispatch` — the same unidirectional loop. The difference is that the "selector" is a named, cached node in a derivation graph (see [subscriptions](subscriptions.md)) rather than a function you pass inline, and the event is dispatched as data rather than through a thunk.

## A view, live

Here is a view doing its whole job: subscribe in, dispatch out, hiccup between. Click into the cell, press **Ctrl-Enter** (**Cmd-Enter** on macOS) to evaluate, then click the buttons.

```cljs-rf2
(require '[re-frame.core :as rf])

;; A line in the shopping cart: one quantity you can step up and down.
(rf/reg-event :views.qty/initialise
  (fn [{:keys [db]} _event] {:db (assoc db :views.qty/value 1)}))

(rf/reg-event :views.qty/inc
  (fn [{:keys [db]} _event] {:db (update db :views.qty/value inc)}))

(rf/reg-event :views.qty/dec
  (fn [{:keys [db]} _event] {:db (update db :views.qty/value (fnil dec 1))}))

(rf/reg-sub :views.qty/value
  (fn [db _q] (:views.qty/value db)))

(defn qty-stepper []
  [:div
   [:button {:on-click #(rf/dispatch [:views.qty/dec])} "-"]
   [:span {:style {:margin "0 1em"}} @(rf/subscribe [:views.qty/value])]
   [:button {:on-click #(rf/dispatch [:views.qty/inc])} "+"]])

(rf/reg-frame :demo {:initial-events [[:views.qty/initialise]]})

[rf/frame-provider {:frame :demo}
 [qty-stepper]]
```

That's a complete view. You use it by referencing it inside other hiccup — `[qty-stepper]` — and a view that takes arguments takes them like any function: `[labelled-stepper "Apples"]`.

Now try something. Change the last form to `[:div [qty-stepper] [qty-stepper]]` and re-evaluate. Click either stepper: both move, because neither owns the number — each is a window onto the same `app-db` value, and two windows onto one room show the same furniture. There is no local copy to fall out of sync. There is no local copy at all.

## `reg-view`: registering a view for project code

The cell above writes the view as a plain `defn`, and that genuinely *is* a view. But in real project code you'll write the [registered](../glossary.md#registration) form instead:

```clojure
(rf/reg-view qty-stepper []
  [:div
   [:button {:on-click #(dispatch [:cart/qty-dec])} "-"]
   [:span @(subscribe [:cart/qty])]
   [:button {:on-click #(dispatch [:cart/qty-inc])} "+"]])
```

A `reg-view` and a `defn` define the **same render function**. `reg-view` adds exactly two things on top:

1. **A registry entry.** The view is registered in the process-wide [registrar](../glossary.md#registrar) — the one table every `reg-*` form writes to — under an auto-derived id pairing namespace with symbol: in `my.app`, `qty-stepper` registers as `:my.app/qty-stepper`. That id is what lets tooling list the view, jump to its source, and resolve a rendered DOM node back to the view that produced it.

2. **Frame-aware injection.** Inside the body, the unqualified `dispatch` and `subscribe` are locals, bound to the [frame](../glossary.md#frame) — the isolated re-frame2 world — that the view renders under. That binding is what lets the same view mount in several frames at once, each reading and writing only its own world.

So to *read* a `reg-view` body as a `defn`, map `dispatch` → `rf/dispatch` and `subscribe` → `rf/subscribe`. Nothing else differs about the render function.

!!! note "Hot-reload just works"

    Re-evaluating a `reg-view` form overwrites its registry entry, and mounted instances pick up the new body on their next render — no manual remount, no cache to bust. The registered view resolves its render fn through the registry on every render; the swap also emits a `:rf.registry/handler-replaced` trace event, which is how tooling refreshes its view list.

??? note "`reg-view` is the Reagent surface"

    The `defn`-shape macro rewrite is specific to **Reagent**, the default React-based rendering [substrate](../glossary.md#substrate) this page's examples assume ("The substrate seam" below introduces the full cast). On the hooks-shaped [substrates](../glossary.md#substrate) — UIx and Helix — you write native components and reach for the frame through the adapter's hooks (`use-subscribe`, `use-current-frame`) and a captured `(rf/capture-frame)` instead; there's no `reg-view` macro and most views need no registration at all, because UIx/Helix components compose by Var reference like ordinary React components. The `reg-view*` lookup lane (below) is still available on every substrate for registry-keyed addressing. Everything else on this page — the one rule, the imperative-listener trap, frame isolation — holds identically across all three. See [Use UIx, Helix, or reagent-slim](../how-to/use-uix-helix-or-slim.md).

### The three call shapes

`reg-view` is *defn-shape*, and like `defn` it takes an optional docstring and lets you override the auto-derived id. All three call shapes produce the same registered view; the choice is only about what you want at the source level:

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
;;    (a stable id across rename, or matching an external contract).
;;    The ^{...} prefix is Clojure's reader syntax for attaching metadata to
;;    the next form — here, pinning the registry id onto the cart-line symbol.
(rf/reg-view ^{:rf/id :cart/line} cart-line [item]
  [:tr [:td (:name item)] [:td (:qty item)]])
```

In all three the symbol `cart-line` is `def`-ed, so you write `[cart-line item]` from sibling code regardless.

One warning before moving on. Get the *shape* wrong — pass a render fn instead of an args vector, or hand it a pre-built component object (`reagent.core/create-class …` — a legacy Reagent shape covered under *From re-frame v1* below) — and the macro refuses at compile time with a stable error pointing you at `re-frame.core/reg-view*` (the plain-fn surface described under *Tooling and library registration* below). The macro is the defn-shaped 80% lane; those cases have a different door.

??? info "From re-frame v1"

    `reg-view` is new, and it's more than sugar. In v1 a view was just a `defn` and there was an implicit default frame for it to find. That default frame is gone, so registration is now how a view finds its frame — which is why the macro injects frame-bound `dispatch`/`subscribe`. The full delta is in [From re-frame v1](../25-from-re-frame-v1.md).

### Plain `defn` views, and when they break

A plain `defn` view still works — but only when it renders *inside* a frame scope it can read. The qualified `rf/dispatch` / `rf/subscribe` resolve their frame from the surrounding React context, and a mounted app's [frame-provider](../glossary.md#frame-provider) — which broadcasts "the frame to render under" — hands that frame only to **registered** views. An unregistered `defn` that derefs `rf/subscribe` under a non-default provider fails loud with a named [error record](../glossary.md#error-record): `:rf.error/no-frame-context`.

That isn't an oversight — it's [frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found) doing its job. An operation reads its frame from scope; the runtime never invents one. So the reverse rewrite — turning a `reg-view` into a `defn` — is not free. If a view genuinely must stay an unregistered plain fn, it captures a [`(rf/capture-frame)`](../glossary.md#capture-frame) at render time and uses its bound ops instead.

!!! note "A view that runs setup on mount"

    If a screen needs an event to fire when its frame comes up — load the cart, hydrate a form — don't `dispatch` from the render body. That couples reads to writes and, under a reactive substrate, can loop the render. Name the setup as an event and list it in the frame's `:initial-events`:

    ```clojure
    (rf/reg-frame :cart {:initial-events [[:cart/load]]})
    ```

    The events fire once, synchronously, in order, the moment the frame is created, and they show up in the trace by name. That's the home for "do this on mount", and it keeps the view itself a plain render fn — see [Frames](frames.md).

??? info "From re-frame v1"

    Reagent's **Form-2** and **Form-3** view shapes are still supported, mostly for migration:

    **Form-2** is a view whose body returns *another fn*. The outer fn runs once per mount (a place for per-mount setup that genuinely depends on props); the inner fn is the render fn, re-run each render. Lexical closure does the right thing — the injected `dispatch` / `subscribe` are in scope for both:

    ```clojure
    (rf/reg-view cart-line-with-init [sku]
      (dispatch [:cart/load-line sku])          ;; outer: fires once on mount
      (fn [sku]                                 ;; inner: the actual render fn
        [:button {:on-click #(dispatch [:cart/qty-inc sku])}
         (str sku ": " @(subscribe [:cart/qty sku]))]))
    ```

    Prefer Form-1 + a frame `:initial-events` step over Form-2 for *stable* setup — the outer fn hides a mount-time side effect that doesn't appear at the call site. Reach for Form-2 only when the setup truly needs the per-mount props.

    **Form-3** (`reagent.core/create-class`, with `:component-did-mount` / `:component-will-unmount`) is the escape hatch for wrapping a stateful imperative library (a chart, a map, a code editor) that owns its own DOM. It is *out of scope for the `reg-view` macro* — register it through `reg-view*` instead, where the body can be any callable.

??? note "Tooling and library registration: `reg-view*` and `(rf/view id)`"

    `reg-view` and Var references are the whole app-facing story — register a screen, render it by name. There's a second, separate lane you'll only meet if you write *tooling* or *library* code:

    - `reg-view*`, the plain-fn surface beneath the macro, registers a view from a **computed id** or a non-`defn` render fn.
    - `(rf/view id)` resolves a registered view by id at render time.

    That pairing is how a tool panel or story canvas hosts a view it doesn't know at the call site, how a code-gen pipeline emits views from a manifest, and how Reagent class components (`create-class`) register. If you're building screens, you won't reach for either — they're the host/tooling entry points, not the app-facing one. The full split is in the API reference's [Views section](../../api/re-frame.core.md#views).

### Which style for which view: the authoring lane

`reg-view` and plain `defn` both produce a valid view, so a real app quickly faces a choice at every component: which one? There's a single rule, and holding to it is what keeps a codebase readable — every view announces its kind by its form:

> **A view that touches state — that `subscribe`s or `dispatch`es — is a `reg-view`. A plain `defn` is for a helper that takes *data + callbacks* only and reaches for no state of its own. Never thread `dispatch`/`subscribe` down as arguments.**

The pull toward the anti-pattern is real, because it *works*. You register the root view, and then it feels natural to hand its injected `dispatch` and `subscribe` down to each child as ordinary function arguments — `[todo-item dispatch subscribe todo]` — so the children can stay plain `defn`s. The tree renders correctly. But now every child is an unregistered fn: it has no id, so it renders under the fallback trace key `[:rf.view/anonymous nil]` (see [When something renders wrong](#when-something-renders-wrong)), and a reader can't tell at a glance which components are the app's real, addressable views and which are throwaway helpers. The house rule disappears into the argument lists.

Register the state-touching child instead, and it subscribes to exactly the slice it needs:

```clojure
;; Anti-pattern — dispatch/subscribe drilled down as args. The child is an
;; unregistered fn: no trace name, and the "which style?" rule is invisible.
(rf/reg-view todo-list []
  [:ul
   (for [todo @(subscribe [:todos/visible])]
     ^{:key (:id todo)} [todo-item dispatch subscribe todo])])   ; drilled

(defn todo-item [dispatch subscribe {:keys [id title]}]          ; plain fn, but touches state
  [:li {:on-click #(dispatch [:todo/toggle id])} title])

;; House rule — the state-touching child is a reg-view. It subscribes to what it
;; needs by its own id; the parent hands it only data. Named in the trace.
(rf/reg-view todo-list []
  [:ul
   (for [todo @(subscribe [:todos/visible])]
     ^{:key (:id todo)} [todo-item todo])])                      ; data only

(rf/reg-view todo-item [{:keys [id title]}]
  (let [editing? @(subscribe [:todo/editing? id])]               ; asks for its own state
    [:li {:class (when editing? "editing")
          :on-click #(dispatch [:todo/toggle id])} title]))
```

The plain-`defn` lane is not a lesser option — it's the right lane for a *genuine helper*: an input helper that takes its current `:value` and an `on-change` callback as arguments, a formatting fn that turns a data map into hiccup, a presentational wrapper. What marks a helper is that everything it depends on is in its signature and it touches no state. The [TodoMVC example](../../../examples/core/todomvc) is written to this rule: its state-touching views (`task-entry`, `task-list`, `todo-item`, `footer-controls`) are each a `reg-view`; only `todo-input` (data + callbacks) and `filter-link` (pure data → hiccup) stay plain fns.

??? note "On the hooks substrates"

    The lane rule holds on UIx and Helix too. There's no `reg-view` macro there (see *"`reg-view` is the Reagent surface"* above) — a component that reads state reads it through the adapter's hooks (`use-subscribe`, `use-current-frame`) itself, rather than receiving a `dispatch`/`subscribe` (or their hook results) drilled down as props. Reach for state where you need it; don't thread it.

## The one rule: views compute hiccup only

Now the single discipline that keeps views fast, correct, and easy to debug. It pays for itself within a day of writing real screens:

> **Views compute hiccup only. Everything else — sorting, filtering, formatting, deriving, joining — happens in a subscription.**

The temptation always looks innocent. The subscribed list is *almost* what the screen needs, so you reach for one little `sort-by` here, one `.toFixed` there. Don't. Here's the *before*, with the view quietly doing two jobs that aren't its own:

```clojure
;; Before — the view computes. The sort and the price-format re-run on
;; EVERY re-render of this view, whether or not the cart changed.
(rf/reg-view cart-lines []
  [:ul
   (for [item (sort-by :name @(subscribe [:cart/items]))]
     ^{:key (:id item)} [:li (:name item) " — $" (.toFixed (:price item) 2)])])
```

And the *after*, with the derivation pushed up into a [subscription](subscriptions.md) where it belongs:

```clojure
;; After — the sub computes once per change to :cart/items; the view renders.
(rf/reg-sub :cart/lines-display
  :<- [:cart/items]
  (fn [items _]
    (->> items
         (map #(update % :price (fn [n] (.toFixed n 2))))
         (sort-by :name))))

(rf/reg-view cart-lines []
  [:ul
   (for [item @(subscribe [:cart/lines-display])]
     ^{:key (:id item)} [:li (:name item) " — $" (:price item)])])
```

Ask the "after" view what it does: all it does is walk the list and emit `<li>`s. That's a view that knows what it's for.

Why so strict? Because a view re-runs whenever any value it derefs changes, and whenever a re-rendering parent hands it changed arguments — and a `sort-by` in the view re-runs on every single one of those. The same `sort-by` in a sub re-runs *only when `:cart/items` changes*, sits in the subscription cache, and is shared by every view that wants the sorted list. Compute once, read many. This is the single most common way re-frame2 apps get accidentally slow; the hunt and the fix are in [Find and fix a slow view](../how-to/fix-a-slow-view.md).

??? note "Need the derived value in an *event handler*, not just a view?"

    A subscription's value is only available to views. When a handler needs the same derivation as plain state, materialise it with a [flow](../glossary.md#flow) — a pure derivation re-frame2 keeps written at a path *in* `app-db`. Same "compute once" idea, the other side of the loop. ([Flows](flows.md) has the full picture; [Where state lives](../where-state-lives.md) is the chooser.)


!!! note "What's the `^{:key (:id item)}` for?"

    Same as React's `key`. When you emit a *list* of elements, give each a stable identity so the substrate diffs by identity instead of position — insert or remove one item and only that item's DOM moves, not everything below it. Attach it as metadata on the element (`^{:key v} [:li ...]`) and key by something durable from the data, never the loop index. More on why this matters for big lists in [Find and fix a slow view](../how-to/fix-a-slow-view.md).

    **Gotcha — missing or colliding keys.** Omit the key on a list and the substrate falls back to *position*; key two siblings the same and they collide. Either way reconciliation can keep stale DOM in place, drop a row, or duplicate one when the list changes — a silent visual bug, not an error. The `^{:key …}` rides through `reg-view` to React untouched, so a registered row keys exactly like a plain one. (This `:key` is React's reconciliation key — distinct from the trace `:render-key` further down, which is instrumentation identity, not reconciliation.)

## The trap: imperative listeners lose the frame

Hiccup's event attrs — `:on-click`, `:on-change`, `:on-animation-end`, the whole `:on-*` family (*synthetic events*, in the substrate's vocabulary) — are wrapped by the *substrate* — the rendering library underneath the view — at render time, so a `dispatch` inside them is routed to the right [frame](../glossary.md#frame) automatically. Anything you attach *imperatively* from a render body is **not** wrapped, though. It fires later, on a fresh stack, with no frame in scope, and the dispatch fails loud with `:rf.error/no-frame-context`:

```clojure
;; WRONG — imperative listener: the callback fires on a fresh stack with no
;; frame in scope; the dispatch raises :rf.error/no-frame-context.
;; (:ref calls this fn with the real DOM element once it's on the page.)
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

Rule of thumb: if an `:on-*` attr exists for what you need, use it. If one doesn't — a `js/setTimeout`, a `fetch`, an `IntersectionObserver`, a WebSocket — that work was never a view's job. It belongs in a registered [effect](effects.md), which captures the frame for you. The loud failure is deliberate: the runtime refuses to guess which world a frameless dispatch belongs to.

And don't let a `reg-view` lull you. Inside a `reg-view` body the injected `dispatch` happens to survive, because it captured its frame at render time — but the imperative attach is wrong there too: render bodies re-run, each run adds another listener, and nothing ever removes them. Attach through the attrs map either way.

??? note "Going deeper"

    The carried-frame mechanism — a [capture-frame](../glossary.md#capture-frame) closing over the right world — is what lets a registered effect's closure dispatch back into its frame: the *carried invariant*. The full account is in [Frames: isolated worlds](frames.md).

## Targeting a different frame, deliberately

The injected `dispatch` / `subscribe` always read *the frame the view is rendering under* — that's the whole point of the injection, and it's what you want almost always. Once in a while a view needs to read or write a *different* frame than its own: a side-by-side comparison panel, a story-tool variant that drives a sibling variant, a debug overlay watching another world. For that, the qualified two-arg forms take a `{:frame …}` opt that names the target explicitly and bypasses the injection:

```clojure
;; Subscribe to / dispatch into a named OTHER frame, from a view rendering in this one.
(let [their-total @(rf/subscribe [:cart/total] {:frame :other-tab})]
  [:button {:on-click #(rf/dispatch [:cart/clear] {:frame :other-tab})}
   (str "Other tab: " their-total)])
```

This is the deliberate escape hatch, not the daily path — reaching across frames is the exception that proves the isolation rule. To re-point a whole *subtree* of children at an existing frame instead of opt-ing call by call, scope them with `rf/with-frame` (or `rf/frame-provider {:frame …}` across a React boundary) — see [Frames](frames.md).

## The substrate seam, in one paragraph

Everything upstream of the view — handlers, subscriptions, effects, `app-db` itself — is operations on Clojure data, and never names a rendering library. The one place re-frame2 touches React is the seam where hiccup becomes pixels and a click becomes a dispatch. That seam is an [adapter](../glossary.md#adapter): a small map of functions named once at boot, `(rf/init! reagent-adapter/adapter)`. The adapter is a *value*; the rendering library it binds to — Reagent, UIx, Helix, reagent-slim — is the [substrate](../glossary.md#substrate). Port an app from one substrate to another and your handlers, subs, and `app-db` don't change by a character. Only the `init!` line and the view bodies' notation change, because the view body is the one place the substrate is visible. The practical how-to is [Use UIx, Helix, or reagent-slim](../how-to/use-uix-helix-or-slim.md).

## When something renders wrong

Step back and notice what all this buys you at debugging time. A view holds no state, runs no effects, owns no lifecycle. It is a pure function from subscription values to hiccup, so there is almost nothing *in* it to break:

> **When something renders wrong, the bug is almost never in the view — it's in the data the view was handed.**

So don't debug views. Inspect data. When the room looks wrong, you don't repaint the window — you go and look at the room. Follow the value upstream: the [subscription](subscriptions.md) that computed it, then the [event handler](../glossary.md#event-handler) that wrote it. Both are pure functions you can test without a browser. With [Xray](../glossary.md#xray) open, find the [event](../glossary.md#event) row that preceded the bad render and look at the data it produced. The wrong value is usually sitting there, visibly wrong, before the view ever ran ([Debug with Xray](../../xray/index.md)).

The render itself is observable too, which helps with the *other* failure mode — not "wrong value" but "why did this re-render at all?" Each render emits a [trace event](../glossary.md#trace-event) keyed by a `:render-key` — the tuple `[view-id instance-token]`, where the token disambiguates two mounted instances of the same view (`[:cart/row 1473]` vs `[:cart/row 1474]`). The entry also carries what *triggered* the render (the sub or props that changed) and the view's render args, so an over-rendering view shows its cause rather than leaving you to guess.

This is also the practical reason to register the views you care about: plain unregistered fns render under the fallback key `[:rf.view/anonymous nil]` — a registered `reg-view` is what gives a render a *name* in the trace. All of this sits behind the dev-build gate and [elides](../glossary.md#elide) completely in production.

A view is a pure function from subscription values to hiccup. That's the whole job: show the room, pass along the knocks, keep nothing. Everything interesting happens on the other side of the glass — and the rest of this guide is about that side.
