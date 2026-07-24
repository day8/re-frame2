# Build a view

This is the walkthrough the [API reference](../../api/re-frame.ui.md) can't be:
one small view built up a step at a time, so each new form lands on its own. We'll
build a counter that increments through an event and reveals a help line through
local state — enough to touch `defview`, `sub`, an event handler, `local`, and
the mount, which together are most of what ordinary view work needs.

The setup this assumes — the dependency and the two Shadow settings — is the
[Install re-frame.ui and configure Shadow](../how-to/install-re-frame-ui.md)
recipe. Everything below lives in one namespace.

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview sub]]))
```

## Step 1 — the dataflow

None of this is new. The counter's state, the event that changes it, and the
subscription that reads it are plain re-frame2 — the same code you'd write behind
any adapter.

```clojure
(rf/reg-event :app/init  (fn [_ _] {:db {:count 0}}))
(rf/reg-event :count/inc (fn [{:keys [db]} _] {:db (update db :count inc)}))
(rf/reg-sub   :count     (fn [db _] (:count db)))
```

`:app/init` seeds app-db, `:count/inc` bumps the count, and `[:count]` reads it.
The view is going to *consume* these, not reinvent them.

## Step 2 — the view

Here is the whole component, in its first form:

```clojure
(defview counter []
  [:div.counter
   [:p "Count: " (sub [:count])]])
```

`defview` takes a props map — here there are no props, so the argument vector is
empty — and returns a template. `(sub [:count])` is the current value of the
subscription, dropped straight into the paragraph; when `[:count]` changes, this
view re-renders and the number updates. No `@`, no deref, no ratom. That's the
whole reactive path, in one line.

## Step 3 — an event

To change the count, dispatch the event. In `re-frame.ui` a handler is an event
vector — data, not a closure:

```clojure hl_lines="4"
(defview counter []
  [:div.counter
   [:p "Count: " (sub [:count])]
   [:button {:on-click [:count/inc]} "Increment"]])
```

`{:on-click [:count/inc]}` says: when this button is clicked, dispatch
`[:count/inc]` to the frame this view runs under. The handler is visible in
source and in [Xray](../observability.md) before it ever fires. Click the button,
`:count/inc` runs, app-db updates, `[:count]` changes, and Step 2's paragraph
re-renders with the new value — the full pipeline, driven by one vector.

## Step 4 — local state

Some state has no business in app-db. Whether a help line is *showing* is a
detail of this one view: no other view reads it, you don't want it in the trace,
and time-travel shouldn't rewind it. That's what `local` is for — component-local
ephemera that lives outside re-frame2's epochs and re-renders only this view.

```clojure hl_lines="2 6 7 8 9"
(defview counter []
  (let [[open? _ update-open!] (ui/local false)]
    [:div.counter
     [:p "Count: " (sub [:count])]
     [:button {:on-click [:count/inc]} "Increment"]
     [:button {:on-click (ui/handler [_] (update-open! not))}
      (if open? "Hide help" "Show help")]
     (when open?
       [:p.help "The Increment button dispatches the event vector [:count/inc]."])]))
```

`(ui/local false)` returns a **three-tuple** `[value set! update!]`, bound in the
view's top-region `let`. Here we read `open?` and keep `update!`; `update-open!`
applies a function to the latest value, so `(update-open! not)` flips the flag.

The toggle button uses `ui/handler` rather than an event vector, because flipping
local state is imperative work, not a dispatch — its body runs and its return is
ignored. (Setters are host-only: you call them from a committed handler like this
one, never during render.) Then a plain `when` shows or hides the help line based
on `open?`. Note how the count still flows through app-db and events, while the
help toggle stays local — the same view, two kinds of state, each in its right
home.

## Step 5 — mount it

A view becomes a running app when you install the adapter and mount a root:

```clojure
(defn ^:export run []
  (rf/init! ui/adapter)
  (ui/mount [ui/frame-root {:id :app :initial-events [[:app/init]]}
             [counter]]
            (js/document.getElementById "root")))
```

`(rf/init! ui/adapter)` selects the `re-frame.ui` substrate once, at boot.
`ui/mount` creates the React root and renders the form into `#root`.
`frame-root` ensures the `:app` frame exists and drains `:initial-events`
(`[:app/init]`) exactly once, *before* React renders — so the count is `0` on the
first paint, not `nil`. A hot reload finds the frame already live and reuses it,
so your state survives edits.

## The complete view

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview sub]]))

;; ---- dataflow: plain re-frame2 --------------------------------------------

(rf/reg-event :app/init  (fn [_ _] {:db {:count 0}}))
(rf/reg-event :count/inc (fn [{:keys [db]} _] {:db (update db :count inc)}))
(rf/reg-sub   :count     (fn [db _] (:count db)))

;; ---- view -----------------------------------------------------------------

(defview counter []
  (let [[open? _ update-open!] (ui/local false)]
    [:div.counter
     [:p "Count: " (sub [:count])]
     [:button {:on-click [:count/inc]} "Increment"]
     [:button {:on-click (ui/handler [_] (update-open! not))}
      (if open? "Hide help" "Show help")]
     (when open?
       [:p.help "The Increment button dispatches the event vector [:count/inc]."])]))

;; ---- mount ----------------------------------------------------------------

(defn ^:export run []
  (rf/init! ui/adapter)
  (ui/mount [ui/frame-root {:id :app :initial-events [[:app/init]]}
             [counter]]
            (js/document.getElementById "root")))
```

That's a complete, runnable re-frame.ui view: a subscription read as a value, an
event dispatched as data, and ephemeral state kept local — mounted under an
explicit frame. For a standalone project carrying exactly this shape plus the
five setup files the scaffold needs, the
[`examples/ui/minimal-counter`](https://github.com/day8/re-frame2/tree/main/examples/ui/minimal-counter)
scaffold is the thing to copy.

[Reactivity and ownership](reactivity-and-ownership.md) covers what re-computes
when `[:count]` changes, and why the subscription never leaks.
