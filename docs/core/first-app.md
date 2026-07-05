# Our First App

Let's write the classic counter application.

Here's the entire app, running live — read it top to bottom, click the buttons, or edit the code and watch it re-render. Then we'll walk through what each part is doing below.

```cljs-rf2
(ns first-app.counter
  (:require [re-frame.core :as rf]))

;; Register three event handlers — one per event
(rf/reg-event :initialise
  (fn [_world [_id first-value]]
    {:db {:value first-value}}))

(rf/reg-event :inc
  (fn [{:keys [db]} _event]
    {:db (update db :value inc)}))

(rf/reg-event :dec
  (fn [{:keys [db]} _event]
    {:db (update db :value dec)}))

;; Register a subscription
(rf/reg-sub :value
  (fn [db _query]
    (:value db)))

;; Register a view — reg-view injects ready-to-use `dispatch` and `subscribe`
(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:dec])} "−"]
   [:span @(subscribe [:value])]
   [:button {:on-click #(dispatch [:inc])} "+"]])

;; Create a frame — an isolated runtime — seeded by dispatching :initialise
(rf/reg-frame :app {:initial-events [[:initialise 5]]})

;; The last form renders — the provider scopes the :app frame around the view
;; (real boot wires this up -- see the counter example, linked below)
[rf/frame-provider {:frame :app}
 [counter]]
```

This code:

- registers three event handlers (one per event)
- registers one subscription handler
- registers one view
- creates a `frame` (an isolated runtime) named `:app`, seeded by dispatching `[:initialise 5]` — and the view renders inside it

Now let's walk the code in the same order the [introduction](introduction.md) walked the concepts.

## The vocabulary: three events

This app's entire language is three [events](glossary.md#event):

```clojure
[:initialise 5]   ;; carries a fact — the starting value
[:inc]            ;; no payload needed
[:dec]
```

That's the vocabulary the introduction promised you'd design: small, and everything the app can ever do is a sentence in it. `:initialise` shows the `& facts` half of the event shape — it carries its starting value as a payload, which is why its handler destructures — pulls apart positionally — `[_id first-value]`.

??? note "The canonical event shape"

    Best practice is `[<id>]` for a trivial event, `[<id> <scalar>]` for one argument, and `[<id> {<k> <v>}]` when you have several — a single map payload rather than positional args. The runtime still *tolerates* variadic `[<id> a b c]` for migration and convenience, but the linter nudges new code toward the map form, because a named map is easier to read, grow, and destructure than a positional tail.


(One `require` and you're done: `[re-frame.core :as rf]` is the whole import — events, subscriptions, and `reg-view` all come through it, no second `:require-macros` line to remember.)

## Dispatch

The buttons announce those events — [`dispatch`](glossary.md#dispatch) in a DOM handler, exactly as the introduction sketched:

```clojure
[:button {:on-click #(dispatch [:dec])} "−"]
```

Each click drops an event onto the frame's FIFO queue, to be processed shortly. (Why can the view call a bare `dispatch`, with no `rf/` alias? Because `reg-view` injects it — that's the read side's story, below.)

## One click, one pipeline run

Click a button and watch: that's one full traversal of the [event pipeline](glossary.md#event-pipeline) from the introduction — **write side** (the `:inc` handler computes `{:db …}`), **commit** (the new app-db lands), **read side** (the `:value` subscription recomputes and the view re-renders). Everything else in this guide just refines that pipeline — adds effects to the handler's output, adds layers to the subscription graph, adds isolation around the whole thing — but never changes its shape.

## The write side

The three handlers are this app's whole write side. Each one is the introduction's `(world, event) → effects` step: the first argument is the `world` map — `{:keys [db]}` pulls out [app-db](glossary.md#app-db), which the introduction said is always in there — and `:initialise` also reads the fact its event carried.

??? note "New to Clojure?"

    A few bits of syntax to read that handler. The first argument `{:keys [db]}` is *destructuring* — it pulls the `:db` entry out of the incoming `world` map and binds it to a local named `db`, so you don't write `(:db world)` by hand. The second argument is named `_event`: a leading underscore is the Clojure convention for "an argument I'm required to accept but don't use here." And `(update db :value inc)` returns a *new* map — a copy of `db` with `:value` run through `inc` (increment) — it never mutates the original. Returning a fresh value rather than editing in place is the move that keeps the handler pure.


The map a handler returns is its [**effect map**](glossary.md#effect-map) — read it as *"the next state, and anything else to do."* For the counter there's nothing else, so `:db` is the whole story. Everything else an app ever does — an HTTP request, a storage write, a follow-up event — rides in that same map under `:fx`, and [Effects](concepts/effects.md) is that page.

## Commit

Notice we wrote no commit code at all. The `:db` key in each effect map is handled by a built-in effect handler that commits the new value to app-db — the impure step, done for us. The day your app needs an impure step of its own (a `localStorage` write, a `postMessage`), you'll register one with `reg-fx`, exactly as the introduction sketched. The counter needs none.

## The read side

The last two registrations are the introduction's read side, one line each:

- `reg-sub :value` is the **derive** step — it turns state into the `view-model` a view wants. Here the derivation is trivially thin; [subscriptions](glossary.md#subscription) earn their keep as apps grow (that's [Subscriptions](concepts/subscriptions.md), two stops down the track).
- `reg-view counter` is the **views** step — a pure function returning hiccup, re-run when its subscriptions change. React does the *reconcile* step. And here's the answer promised above: `reg-view` injects ready-to-use `dispatch` and `subscribe` into the [view](glossary.md#view) body, pre-bound to whichever frame the view renders in — no imports, no wiring.

## The frame

The last two lines give the app somewhere to run. `reg-frame` creates the `:app` [frame](glossary.md#frame) — the introduction's isolated execution context — and seeds it by dispatching `[:initialise 5]`; the `frame-provider` scopes it around the view. One detail worth holding onto: a frame isolates *state*, not registrations — so the same `counter` can later mount into a second frame with its own independent app-db, unchanged ([Frames](concepts/frames.md) is that story).

The cell above is the whole app *except* boot — the bit that installs a rendering [substrate](glossary.md#substrate) and mounts the view into a real page. The runnable version, boot wiring and build config included, is the [counter example](../../examples/core/counter) — clone it and go. And when you want boot *explained* rather than copied, [Boot and mount an app](how-to/boot-and-mount-an-app.md) is the recipe.

That's the whole app, walked end to end. From here, the guide grows this same counter one concept at a time.
