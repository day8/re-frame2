# Views: pure functions of data

The update and commit phases are in place, and so are the derivations
([events](events.md) → [app-db](app-db.md) → [subscriptions](subscriptions.md)).
This page is the last pure stage of the [event pipeline](glossary.md#event-pipeline):
turn derived values into a screen.

A [view](glossary.md#view) has one job. Not "manage local state" — a view stores
nothing. Not "fetch what it needs" — a view never touches the world. Not
"coordinate a lifecycle" — there's nothing to coordinate. It reads some application
state, returns the description of the screen for that state, and it's finished.
State changes; the framework re-runs the view; the DOM catches up.

> **A view is a pure function from subscription values to hiccup.**

**Views are derivative, not causal.** [Events](glossary.md#event) update
centralised state. [Subscriptions](subscriptions.md) derive values from it. Views
sit at the end of the flow and render whatever arrives — a **window** onto the
application, not the room itself.

**On this page — two speeds.**

1. **Day one:** composition, hiccup, subscribe-in / dispatch-out, `reg-view`,
   compute-in-subs. After the live qty cell the **pure pipeline is complete**.
2. **Going further:** Form-2/3, multi-frame targeting, substrate seam. Open only
   when a need appears.

??? info "For JavaScript developers"

    A re-frame2 view is a React function component with everything except rendering removed. No `useState` — state lives in [app-db](app-db.md), your app's single state map, and arrives through [subscriptions](subscriptions.md). No `useEffect` — anything that touches the world is an [effect](effects.md), produced as data by an [event handler](glossary.md#event-handler) and never run from a component. No JSX — a view returns plain Clojure data. The design here is in what got *subtracted*, not in anything added.

## The counter gets components

The [app-db](app-db.md) counter rendered the whole UI in one view. Real screens are
built from *pieces*. Views compose the way the hiccup they return does — one vector
inside another. Three registered views: a display, a reusable button, a parent:

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

[rf/frame-root {:id :app :initial-events [[:initialise]]}
 [counter]]
```

Notes:

1. A child view is used *as data*. `[counter-button "−" [:dec]]` is a vector whose
   tail is the child's arguments — same view, two argument sets.
2. The display subscribes for itself. The parent does not fetch the value and hand
   it down — so when the value changes, only `counter-display` re-renders.
3. The button takes the *event to announce* as data. That keeps the piece reusable
   without knowing what its click means.

## Hiccup: the screen is data

A view returns [hiccup](hiccup.md) — the notation from early in the track: nested Clojure vectors shaped like the DOM they describe:

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

And because hiccup is just data, everything you already know how to do with data works on screens. A function can take hiccup and return hiccup. You can `pprint` a view's output and *read* it. A pure function that walks hiccup and emits an HTML string can run on the server — which is how [server-side rendering](../ssr/concepts.md) renders the *same* views without a browser in the building.

??? info "For JavaScript developers"

    Template strings can do none of this. They don't compose, they don't diff, and string-built markup is where injection bugs come from. Hiccup is closer in spirit to React's `createElement` calls — a tree of data describing the UI — except it's plain literals you can map, filter, and pass around, with no build-time transform.

??? note "Going deeper"

    Hiccup is the ClojureScript render-tree — the shape that survives serialisation across the JVM/browser boundary. Other hosts use their own render-tree shape behind the same contract.

## Subscribe in, dispatch out

A static screen isn't much use. A view needs to read live application state, and it needs to react to clicks and typing. Two jobs, exactly two openings — and both are one-way.

**Reading state in: the view derefs a [subscription](glossary.md#subscription).**

```clojure
@(rf/subscribe [:cart/total])
```

This declares "I depend on this derived value. Re-run me when it changes." That's the only way a view learns application state. It doesn't read [app-db](glossary.md#app-db) directly, and it doesn't receive the value as an argument threaded down through ten ancestors. It asks the [derivation graph](glossary.md#the-derivation-graph) for exactly the slice it needs, *by name* — via a [query vector](glossary.md#query-vector), the `[id & args]` shape that names the subscription and keys its cache. (More on subscriptions in [Subscriptions](subscriptions.md).)

**Sending events out: the view [dispatches](glossary.md#dispatch).** Wire the view's `dispatch` to an [event handler](glossary.md#event-handler):

```clojure
[:button {:on-click #(dispatch [:cart/add id])} "Add"]
```

(`dispatch` here is the local `reg-view` injects — bound to the view's
[frame](glossary.md#frame), and captured so it still routes correctly when the
click fires, *after* the render. More on that [below](#the-trap-a-callback-that-fires-after-render-has-no-frame).)

A dispatch *announces that something happened* by handing the framework an [event](glossary.md#event) — a plain vector naming what occurred — and returns immediately. It does not change state. It does not know or care what the handler will do with it. The [event pipeline](glossary.md#event-pipeline) takes it from there: the handler runs, `app-db` moves, subscriptions repropagate, and at the very end this view re-renders to match. (The whole traversal is the [Introduction](introduction.md)'s subject.)

Notice the shape of the round trip, because it's the whole idea. A click never mutates the number it sits next to. It dispatches an event that produces a *new* `app-db`, which flows back through a subscription. The view can't short-circuit that path, because it holds no state to short-circuit with. In window terms: you can see into the room, and you can knock. What happens after the knock is the room's business, not the window's.

??? info "Coming from Redux?"

    `subscribe` is `useSelector` and `dispatch` is `dispatch` — the same unidirectional
    dataflow. The difference is that the "selector" is a named, cached node in a
    derivation graph (see [subscriptions](subscriptions.md)) rather than a function
    you pass inline, and the event is dispatched as data rather than through a thunk.

## A view, live

Subscribe in, dispatch out, hiccup between. Press **Ctrl-Enter** (**Cmd-Enter** on
macOS) to evaluate, then click the buttons:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :views.qty/initialise
  (fn [{:keys [db]} _] {:db (assoc db :views.qty/value 1)}))
(rf/reg-event :views.qty/inc
  (fn [{:keys [db]} _] {:db (update db :views.qty/value inc)}))
(rf/reg-event :views.qty/dec
  (fn [{:keys [db]} _] {:db (update db :views.qty/value (fnil dec 1))}))
(rf/reg-sub :views.qty/value
  (fn [db _] (:views.qty/value db)))

(rf/reg-view qty-stepper []
  [:div
   [:button {:on-click #(dispatch [:views.qty/dec])} "−"]
   [:span {:style {:margin "0 1em"}} @(subscribe [:views.qty/value])]
   [:button {:on-click #(dispatch [:views.qty/inc])} "+"]])

[rf/frame-root {:id :demo :initial-events [[:views.qty/initialise]]}
 [qty-stepper]]
```

Keep the `:demo` `frame-root` and change its child from `[qty-stepper]` to
`[:div [qty-stepper] [qty-stepper]]`, then re-evaluate. Click either stepper:
both move. Both mount under the same seeded `:demo` [frame](glossary.md#frame),
so neither owns the number — each is a window onto the one app-db value. There is
no local copy to fall out of sync. (Replacing the whole `frame-root` with the
bare `[:div …]` would drop the `:demo` seed and land the steppers on an
uninitialised frame — keep the wrapper.)

### The pure pipeline is complete

With [events](events.md), [app-db](app-db.md), [subscriptions](subscriptions.md), and
views, you have the pure stages of the [event pipeline](glossary.md#event-pipeline)
end to end — no impurity yet:

```text
intent (event) → one map (app-db) → named conclusions (subs) → screen (view)
```

Many features never need more. The rest of this page is how views stay registered,
fast, and debuggable. When the app must touch the world, open [Effects](effects.md);
isolation and carry are [Frames](frames.md); packaging a real entry point is
[Boot and mount an app](how-to/boot-and-mount-an-app.md).

## `reg-view`: the project form

`reg-view` defines the **same render function** as a `defn`, plus two things:

1. **A registry entry** under an auto-derived id (`my.app` + `qty-stepper` →
   `:my.app/qty-stepper`). Tooling lists the view, jumps to source, and names
   renders in the trace.
2. **Frame-aware injection.** Unqualified `dispatch` and `subscribe` are locals
   bound to the [frame](glossary.md#frame) the view renders under — so the same
   view mounts in several worlds without renaming anything.

```clojure
(rf/reg-view qty-stepper []
  [:div
   [:button {:on-click #(dispatch [:cart/qty-dec])} "−"]
   [:span @(subscribe [:cart/qty])]
   [:button {:on-click #(dispatch [:cart/qty-inc])} "+"]])
```

!!! note "Hot-reload just works"

    Re-evaluating a `reg-view` overwrites its registry entry; mounted instances
    pick up the new body on the next render. The swap emits
    `:rf.registry/handler-replaced` so tooling can refresh its list.

??? note "`reg-view` is the Reagent surface"

    The `defn`-shape macro is specific to **Reagent** (this page's default). On
    UIx you write native components and reach the frame through adapter
    hooks (`use-subscribe`, `use-frame`). The pure-function rule, the
    compute-in-subs rule, and frame isolation hold on every substrate. See
    [Use UIx or reagent-slim](how-to/use-uix-or-slim.md).

??? note "Docstring and explicit id"

    Like `defn`, `reg-view` takes an optional docstring (registry `:doc`) and an
    explicit id via `^{:rf/id :cart/line}` on the symbol when the auto-derived id
    must stay stable across rename.

### House rule: who is a `reg-view`?

> **A view that `subscribe`s or `dispatch`es is a `reg-view`. A plain `defn` is a
> helper that takes data + callbacks only. Never thread `dispatch`/`subscribe`
> down as arguments.**

```clojure
;; Anti-pattern — state ops drilled as args; child is anonymous in the trace
(rf/reg-view todo-list []
  [:ul
   (for [todo @(subscribe [:todos/visible])]
     ^{:key (:id todo)} [todo-item dispatch subscribe todo])])

(defn todo-item [dispatch subscribe {:keys [id title]}]
  [:li {:on-click #(dispatch [:todo/toggle id])} title])

;; House rule — state-touching child is registered; parent passes data only
(rf/reg-view todo-list []
  [:ul
   (for [todo @(subscribe [:todos/visible])]
     ^{:key (:id todo)} [todo-item todo])])

(rf/reg-view todo-item [{:keys [id title]}]
  (let [editing? @(subscribe [:todo/editing? id])]
    [:li {:class (when editing? "editing")
          :on-click #(dispatch [:todo/toggle id])} title]))
```

Helpers stay plain: inputs with `:value` + `on-change`, formatters, presentational
wrappers. The [TodoMVC example](../../examples/core/todomvc) follows this split.

!!! warning "Plain `defn` under a frame is not free"

    An unregistered `defn` that calls `rf/subscribe` under `frame-provider` /
    `frame-root` fails with `:rf.error/no-frame-context`. Registration is how the
    view finds its frame. If a helper must stay unregistered, carry the frame
    explicitly (`{:frame …}` on each call, or a capture from a registered
    ancestor) — [Frames](frames.md).

!!! note "Setup on mount → `:initial-events`"

    Do not `dispatch` from the render body to "load the cart on mount." That couples
    reads to writes and can loop under a reactive substrate. Name the setup event
    and list it on the frame:

    ```clojure
    [rf/frame-root {:id :cart :initial-events [[:cart/load]]}
     [cart-view]]
    ```

??? info "From re-frame v1 — Form-2 / Form-3"

    **Form-2** (outer runs once, returns inner render fn) still works for
    prop-dependent mount setup; prefer `:initial-events` for stable setup.
    **Form-3** (`reagent.core/create-class`) for imperative DOM libraries is out of
    scope for the `reg-view` macro — use `reg-view*` (API: [Views](../api/re-frame.core.md#views)).
    Full delta: [From re-frame v1](25-from-re-frame-v1.md).

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

Why so strict? Because a view re-runs whenever any value it derefs changes, and
whenever a re-rendering parent hands it changed arguments — and a `sort-by` in the
view re-runs on every single one of those. The same `sort-by` in a sub re-runs
*only when `:cart/items` changes*, sits in the subscription cache, and is shared by
every view that wants the sorted list. Compute once, read many. This is the single
most common way re-frame2 apps get accidentally slow; the hunt and the fix are in
[Find and fix a slow view](how-to/fix-a-slow-view.md).

??? note "Need the derived value in an *event handler*, not just a view?"

    A subscription's value is only available to views. When a handler needs the same
    derivation as plain state, materialise it with a [flow](glossary.md#flow) —
    [Flows](flows.md); chooser: [Where state lives](where-state-lives.md).

!!! note "What's the `^{:key (:id item)}` for?"

    Same as React's `key`. Give each list element a stable identity
    (`^{:key (:id item)} [:li …]`) so the substrate diffs by identity, not
    position. Key by durable data, never the loop index. Missing or colliding keys
    can silently keep stale DOM, drop a row, or duplicate one — not an error.

## Day-one checklist

You can compose registered views, subscribe in / dispatch out, keep computation in
subs, and seed setup via `:initial-events`. That closes the pure pipeline stages.

## When things go wrong

| Symptom | Likely cause | Fix |
|---|---|---|
| Screen shows wrong data | Bad event or sub, not the view | Inspect data with [Xray](../xray/index.md); test handler/sub without a browser |
| View re-renders too often | Sort/filter in the view, or too-coarse sub | Move work into a sub; [slow-view recipe](how-to/fix-a-slow-view.md) |
| `:rf.error/no-frame-context` from a click path | Bare `rf/dispatch` in a handler that fires after render, or an unregistered `defn` that dispatches | `reg-view` it and use the injected `dispatch`; for detached callbacks capture with `rf/capture-frame` |
| List flickers or duplicates | Missing / colliding `^{:key …}` | Stable keys from data |
| Anonymous noise in the trace | Unregistered state-touching children | House rule: `reg-view` for anything that subscribes |

> **When something renders wrong, the bug is almost never in the view — it's in the
> data the view was handed.**

Each render can also be traced by `:render-key` (`[view-id instance-token]`) with
what triggered it — that is how you answer "why did this re-render?" Registered
views have names; plain helpers fall under `[:rf.view/anonymous nil]`. Dev-only;
[elided](glossary.md#elide) in production.

## The trap: a callback that fires after render has no frame

A view's `:on-*` handler runs *later* — when the user clicks, not when the view
renders. By then the render is over: the dynamic [frame](glossary.md#frame) scope
has unwound and the [frame-provider](glossary.md#frame-provider)'s React context
has been popped. The adapter does **not** re-wrap `:on-*` callbacks to restore it —
[frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found).
What survives that boundary is capturing the frame *at render time*, which is
exactly what `reg-view`'s injected `dispatch` / `subscribe` do: each is a
[`capture-frame`](glossary.md#capture-frame) op bound to the render frame. So reach
for the injected `dispatch` — not a fresh, fully-qualified `rf/dispatch`:

```clojure
;; WRONG — `rf/dispatch` resolves the frame when the event fires, and by then
;; there is none → :rf.error/no-frame-context
[:div {:on-animation-end #(rf/dispatch [:tile/finished])}]

;; RIGHT — the injected `dispatch` captured the frame at render, so it
;; dispatches correctly after the render boundary
[:div {:on-animation-end #(dispatch [:tile/finished])}]
```

Attaching a listener *imperatively* from a render body fails the same way, and
worse: the callback still fires with no frame, **and** every re-render stacks
another listener.

```clojure
;; WRONG — fires later with no frame, and leaks a listener per render
[:div {:ref (fn [el]
              (when el
                (.addEventListener el "animationend"
                  #(rf/dispatch [:tile/finished]))))}]
```

If there is no `:on-*` for what you need (`setTimeout`, `fetch`, observers,
sockets), that work belongs in a registered [effect](effects.md), not the view.
When you must hold a `dispatch` for a genuinely detached callback — a socket
message, a timer you own — capture it explicitly:
`(:dispatch (rf/capture-frame))` returns a dispatch locked to the render frame
that survives any async hop. [Frames](frames.md) is that pattern's home.

---

## Going further

??? note "Targeting a different frame"

    Injected `dispatch` / `subscribe` always hit the frame the view renders under.
    To address another world deliberately:

    ```clojure
    (let [their-total @(rf/subscribe [:cart/total] {:frame :other-tab})]
      [:button {:on-click #(rf/dispatch [:cart/clear] {:frame :other-tab})}
       (str "Other tab: " their-total)])
    ```

    Escape hatch, not the daily path. For a whole subtree, `rf/with-frame` or
    `frame-provider` — [Frames](frames.md).

??? note "The substrate seam"

    Handlers, subs, and app-db never name a rendering library. The adapter
    (`(rf/init! reagent-adapter/adapter)`) is the seam where hiccup becomes pixels.
    Port substrates and only `init!` plus view notation change —
    [Use UIx or reagent-slim](how-to/use-uix-or-slim.md). A more
    radical, still-experimental option is [re-frame.ui](re-frame.ui/index.md), a
    first-party *compiled* view substrate where views are macro-compiled rather
    than interpreted at runtime.
