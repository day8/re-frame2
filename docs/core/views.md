# Views: pure functions of data

A [view](glossary.md#view) turns application state into a screen. It reads values
through [subscriptions](subscriptions.md), returns [hiccup](hiccup.md) describing the
screen for those values, and dispatches events when the user interacts. It stores no
state and performs no side effects. When a value it reads changes, the framework
re-runs it and React updates the DOM.

This is the last stage of the pure part of the
[event pipeline](glossary.md#event-pipeline): [events](events.md) change
[app-db](app-db.md), subscriptions derive values from it, and views render them.

??? info "For JavaScript developers"

    A re-frame2 view is a React function component with everything except rendering
    removed. No `useState`: state lives in [app-db](app-db.md), your app's single
    state map, and arrives through [subscriptions](subscriptions.md). No
    `useEffect`: anything that touches the world is an [effect](effects.md),
    produced as data by an [event handler](glossary.md#event-handler) and never run
    from a component. No JSX: a view returns plain Clojure data.

## The counter gets components

The [app-db](app-db.md) counter rendered the whole UI in one view. Real screens are
built from pieces, and views compose the way the hiccup they return does: one vector
inside another. Three registered views, a display, a reusable button, and a parent:

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

1. A child view is used as data. `[counter-button "−" [:dec]]` is a vector whose
   tail is the child's arguments: the same view, with two argument sets.
2. The display subscribes for itself. The parent does not read the value and hand
   it down, so when the value changes, only `counter-display` re-renders.
3. The button takes the *event to dispatch* as an argument, so it stays reusable
   without knowing what its click means.

Because a view returns plain data, you can `pprint` its output and read it, and a
function that walks hiccup and emits an HTML string can run on the server. That is
how [server-side rendering](../ssr/concepts.md) renders the same views without a
browser.

## Subscribe in, dispatch out

A view has two connections to the rest of the app, and each goes one way.

It reads state by dereferencing a [subscription](glossary.md#subscription):

```clojure
@(subscribe [:cart/total])
```

This is the only way a view learns application state. It doesn't read
[app-db](glossary.md#app-db) directly, and it doesn't need the value threaded down as
an argument through its ancestors. It asks for the value it needs by
[query vector](glossary.md#query-vector), and re-renders when that value changes.

It reports what happened by [dispatching](glossary.md#dispatch) an
[event](glossary.md#event):

```clojure
[:button {:on-click #(dispatch [:cart/add id])} "Add"]
```

`dispatch` and `subscribe` here are the locals that `reg-view` injects. They are
bound to the view's [frame](glossary.md#frame), and `dispatch` still reaches that
frame when the click fires after the render has finished; the reason is covered
[below](#the-trap-a-callback-that-fires-after-render-has-no-frame).

A dispatch hands the framework an event and returns immediately. It does not change
state. The [event pipeline](glossary.md#event-pipeline) runs the handler, commits the
new app-db, recomputes subscriptions, and finally re-renders this view. A click never
mutates the number next to it; it produces a new app-db, and the new number comes back
through a subscription. The view holds no state, so it cannot take a shortcut around
that path.

??? info "Coming from Redux?"

    `subscribe` is `useSelector` and `dispatch` is `dispatch`: the same
    unidirectional data flow. The "selector" is a named, cached node in a derivation
    graph (see [subscriptions](subscriptions.md)) rather than a function you pass
    inline, and the event is dispatched as data rather than through a thunk.

## A view, live

Press **Ctrl-Enter** (**Cmd-Enter** on macOS) to evaluate, then click the buttons:

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
`[:div [qty-stepper] [qty-stepper]]`, then re-evaluate. Click either stepper: both
move. Both mount under the same `:demo` [frame](glossary.md#frame) and read the same
app-db value, so there is no local copy to fall out of sync. (Replacing the whole
`frame-root` with the bare `[:div …]` would drop the `:demo` seed and mount the
steppers on a frame that was never initialised, so keep the wrapper.)

With [events](events.md), [app-db](app-db.md), [subscriptions](subscriptions.md) and
views you have every pure stage of the pipeline:

```text
intent (event) → one map (app-db) → named conclusions (subs) → screen (view)
```

Many features need nothing more. When the app must touch the outside world, handlers
return [effects](effects.md).

## What `reg-view` adds

`reg-view` defines the same render function a `defn` would, plus two things:

1. **A registry entry** under an id derived from the namespace and name (`my.app` +
   `qty-stepper` → `:my.app/qty-stepper`). Tools list the view, jump to its source,
   and name its renders in the trace.
2. **Frame-bound `dispatch` and `subscribe`.** Unqualified `dispatch` and `subscribe`
   in the body are locals bound to the [frame](glossary.md#frame) the view renders
   under, so the same view can mount under several frames unchanged.

```clojure
(rf/reg-view qty-stepper []
  [:div
   [:button {:on-click #(dispatch [:cart/qty-dec])} "−"]
   [:span @(subscribe [:cart/qty])]
   [:button {:on-click #(dispatch [:cart/qty-inc])} "+"]])
```

Like `defn`, `reg-view` takes an optional docstring (stored as the registry `:doc`).
To keep an id stable across a rename, give it explicitly with `^{:rf/id :cart/line}`
on the symbol.

!!! note "Hot reload"

    Re-evaluating a `reg-view` overwrites its registry entry; mounted instances
    pick up the new body on the next render. The swap emits
    `:rf.registry/handler-replaced` so tools can refresh their lists.

??? note "`reg-view` is the Reagent surface"

    The `defn`-shape macro is specific to **Reagent** (this page's default). On
    UIx you write native components and reach the frame through adapter
    hooks (`use-sub`, `use-frame`). The pure-function rule, the
    compute-in-subs rule, and frame isolation hold on every substrate. See
    [Use UIx or reagent-slim](how-to/use-uix-or-slim.md).

### Which views are `reg-view`s

A view that calls `subscribe` or `dispatch` is a `reg-view`. A plain `defn` is a
helper that takes data and callbacks only. Don't pass `dispatch` or `subscribe` down
as arguments.

```clojure
;; Don't do this — state operations passed as arguments; the child is
;; anonymous in the trace
(defn todo-item [dispatch subscribe {:keys [id title]}]
  [:li {:on-click #(dispatch [:todo/toggle id])} title])

(rf/reg-view todo-list []
  [:ul
   (for [todo @(subscribe [:todos/visible])]
     ^{:key (:id todo)} [todo-item dispatch subscribe todo])])
```

```clojure
;; Do this — the child that touches state is registered; the parent passes data
(rf/reg-view todo-item [{:keys [id title]}]
  (let [editing? @(subscribe [:todo/editing? id])]
    [:li {:class    (when editing? "editing")
          :on-click #(dispatch [:todo/toggle id])}
     title]))

(rf/reg-view todo-list []
  [:ul
   (for [todo @(subscribe [:todos/visible])]
     ^{:key (:id todo)} [todo-item todo])])
```

Helpers stay plain: inputs that take `:value` and an `:on-change` callback,
formatters, presentational wrappers. The [TodoMVC example](../../examples/core/todomvc)
follows this split.

!!! warning "A plain `defn` cannot find its frame"

    An unregistered `defn` that calls `rf/subscribe` or `rf/dispatch` under
    `frame-provider` / `frame-root` raises `:rf.error/no-frame-context`.
    Registration is how a view finds its frame. If a helper must stay
    unregistered, pass the frame explicitly (`{:frame …}` on each call, or the
    `:dispatch` from a `(rf/capture-frame)` taken in a registered ancestor) —
    see [Frames](frames.md).

!!! note "Setup on mount → `:initial-events`"

    Don't `dispatch` from the render body to "load the cart on mount". The
    dispatch runs on every render, and under a reactive substrate it can loop.
    Name the setup event and list it on the frame:

    ```clojure
    [rf/frame-root {:id :cart :initial-events [[:cart/load]]}
     [cart-view]]
    ```

??? info "From re-frame v1 — Form-2 / Form-3"

    **Form-2** (an outer function that runs once and returns the render function)
    still works for setup that depends on the view's arguments; prefer
    `:initial-events` for fixed setup. **Form-3** (`reagent.core/create-class`),
    for imperative DOM libraries, is not supported by the `reg-view` macro; use
    `reg-view*` (API: [Views](../api/re-frame.core.md#views)).
    Full delta: [From re-frame v1](25-from-re-frame-v1.md).

## Views compute hiccup only

Sorting, filtering, formatting, deriving and joining belong in a subscription. A view
walks the values it is given and returns hiccup.

The temptation is a small `sort-by` or `.toFixed` in the view when the subscribed
list is *almost* what the screen needs:

```clojure
;; Don't do this — the sort and the price format re-run on EVERY re-render
;; of this view, whether or not the cart changed.
(rf/reg-view cart-lines []
  [:ul
   (for [item (sort-by :name @(subscribe [:cart/items]))]
     ^{:key (:id item)} [:li (:name item) " — $" (.toFixed (:price item) 2)])])
```

Move the derivation into a [subscription](subscriptions.md):

```clojure
;; The sub computes once per change to :cart/items; the view renders.
(rf/reg-sub :cart/lines-display {:inputs [[:cart/items]]}
  (fn [[items] _]
    (->> items
         (map #(update % :price (fn [n] (.toFixed n 2))))
         (sort-by :name))))

(rf/reg-view cart-lines []
  [:ul
   (for [item @(subscribe [:cart/lines-display])]
     ^{:key (:id item)} [:li (:name item) " — $" (:price item)])])
```

A view re-runs whenever a value it dereferences changes and whenever a re-rendering
parent passes it changed arguments, and a `sort-by` in the view re-runs every time.
The same `sort-by` in a sub re-runs only when `:cart/items` changes, and every view
that wants the sorted list shares the cached result. This is the most common way
re-frame2 apps get slow; [Find and fix a slow view](how-to/fix-a-slow-view.md) covers
finding and fixing it.

??? note "Need the derived value in an *event handler*?"

    A subscription's value is only available to views. When a handler needs the same
    derivation as plain state, materialise it with a [flow](glossary.md#flow) —
    [Flows](flows.md); chooser: [Where state lives](where-state-lives.md).

!!! note "What's the `^{:key (:id item)}` for?"

    Same as React's `key`. Give each list element a stable identity
    (`^{:key (:id item)} [:li …]`) so React matches elements by identity rather
    than position. Key by durable data, never the loop index. Missing or
    colliding keys only produce a console warning, and can leave stale DOM, drop
    a row, or duplicate one.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Screen shows wrong data | A wrong event handler or sub, not the view | Inspect the data with [Xray](../xray/index.md); test the handler or sub without a browser |
| View re-renders too often | Sort/filter in the view, or a sub that returns more than the view needs | Move the work into a sub; see [Find and fix a slow view](how-to/fix-a-slow-view.md) |
| `:rf.error/no-frame-context` from a click | A bare `rf/dispatch` in a callback that fires after render, or an unregistered `defn` that dispatches | `reg-view` it and use the injected `dispatch`; for detached callbacks capture with `rf/capture-frame` |
| List flickers or duplicates rows | Missing or colliding `^{:key …}` | Stable keys from data |
| Anonymous entries in the trace | Unregistered children that touch state | `reg-view` anything that subscribes or dispatches |

When something renders wrong, the bug is usually in the data the view was given, so
check the subscription and the event handler first.

In development each render is traced with a `:render-key` (`[view-id
instance-token]`) and what triggered it, which answers "why did this re-render?".
Registered views have names; plain helpers appear as `[:rf.view/anonymous nil]`.
Render tracing is [elided](glossary.md#elide) from production builds.

## The trap: a callback that fires after render has no frame

A view's `:on-*` handler runs *later*, when the user clicks, not when the view
renders. By then the render is over: the dynamic [frame](glossary.md#frame) binding
has unwound and the [frame-provider](glossary.md#frame-provider)'s React context is
no longer being read. The adapter does not re-wrap `:on-*` callbacks to restore it
(see [frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found)).
What works is capturing the frame *at render time*, which is what `reg-view`'s
injected `dispatch` and `subscribe` do: each is a
[`capture-frame`](glossary.md#capture-frame) operation bound to the render frame. So
use the injected `dispatch` rather than the fully qualified `rf/dispatch`:

```clojure
;; Don't do this — `rf/dispatch` looks for the frame when the event fires,
;; and by then there is none → :rf.error/no-frame-context
[:div {:on-animation-end #(rf/dispatch [:tile/finished])}]

;; Do this — the injected `dispatch` captured the frame at render
[:div {:on-animation-end #(dispatch [:tile/finished])}]
```

Attaching a listener imperatively from a render body fails the same way, and every
re-render adds another listener:

```clojure
;; Don't do this — fires later with no frame, and leaks a listener per render
[:div {:ref (fn [el]
              (when el
                (.addEventListener el "animationend"
                  #(rf/dispatch [:tile/finished]))))}]
```

If there is no `:on-*` for what you need (`setTimeout`, `fetch`, observers, sockets),
that work belongs in a registered [effect](effects.md), not the view. When you must
hold a `dispatch` for a detached callback, such as a socket message or a timer you
own, capture it explicitly: `(:dispatch (rf/capture-frame))`, called during render,
returns a dispatch locked to the render frame that works after any async hop.
[Frames](frames.md) covers the pattern.

---

## Advanced

### Rendering reads; interaction dispatches

A render body may read anything: props, local pure values, and any subscription,
including framework ones such as `:rf.route/id` or `:rf/resource`. It must not
*cause* anything: no fetch, no resource `ensure`, no navigation and no dispatch just
because it rendered. A user or host interaction (a click, a keypress, an `:on-*`
callback) is where causing belongs.

```clojure
;; Do this — the render reads; the click causes.
(rf/reg-view article-link [slug]
  (let [current @(subscribe [:rf.route/id])]
    [:a {:class    (when (= current :route/article) "active")
         :on-click #(dispatch [:rf.route/navigate
                               {:to :route/article :params {:slug slug}}])}
     "Read it"]))

;; Don't do this — the ensure runs because the view rendered, so it re-fires on
;; every re-render and races every other view showing the same article.
(rf/reg-view article [slug]
  (dispatch [:rf.resource/ensure {:resource :article/by-slug
                                  :params   {:slug slug}}])
  [:article @(subscribe [:rf/resource {:resource :article/by-slug
                                       :params   {:slug slug}}])])
```

Calling `fetch`, a resource `ensure`, `navigate` or `dispatch` during render is not a
supported pattern, and re-frame2 has no load-from-view API. Move the cause to where it
belongs: a [route's `:resources`](../routing/concepts.md) declaration, an event
handler's `:fx`, or the `:on-*` handler an interaction fires.

### Targeting a different frame

Injected `dispatch` and `subscribe` always use the frame the view renders under. To
address another frame deliberately, pass `{:frame …}`:

```clojure
(let [their-total @(rf/subscribe [:cart/total] {:frame :other-tab})]
  [:button {:on-click #(rf/dispatch [:cart/clear] {:frame :other-tab})}
   (str "Other tab: " their-total)])
```

This is for the rare view that must reach another frame. To point a whole subtree at
a frame, wrap it in `frame-provider` — see [Frames](frames.md).

### The substrate boundary

Handlers, subs and app-db never name a rendering library. The adapter you install at
boot (`(rf/init! reagent-adapter/adapter)`) is where hiccup becomes DOM. Switching
substrates changes the `init!` call and the view notation only —
[Use UIx or reagent-slim](how-to/use-uix-or-slim.md).

### Fresco: another way to write views

[Fresco](fresco/index.md) is re-frame2's own view layer. It replaces `reg-view` with
`h/defview`: markup is still inspectable hiccup, subscriptions are read as plain
values wherever the body needs them, and handlers are written as event vectors. An
application writes its views one way or the other, not a mix.

Events, app-db, subscriptions and the purity rule are identical under both. Fresco is
not a substrate: a Fresco application still calls `init!` with the Reagent or UIx
adapter. Fresco is pre-alpha, and its guide is a draft.
