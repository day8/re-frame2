# Build a view

A working Freehand screen, one idea at a time: subscription as a value, handler as
data, controlled field, mount. Not a tour of every option.

**Prerequisites:** basic re-frame2 events, subscriptions, and frames.

!!! note "Implementation status"

    Names match the ratified design. Adapter install and Shadow recipes land with
    implementation; sketches may use placeholder adapter names.

One namespace:

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v :refer [sub]]))
```

The `:as v` alias matters for projection keywords later. With that alias,
`::v/value` means Freehand’s reserved value marker
(`:re-frame.freehand/value`), and the same idea applies to `::v/checked` and
`::v/key`.

## Step 1 — the dataflow

None of this is Freehand-specific. The counter’s state, the event that changes it,
and the subscription that reads it are plain re-frame2 — the same code you would
write behind Reagent or UIx.

```clojure
(rf/reg-event :app/init  (fn [_ _] {:db {:count 0 :note ""}}))
(rf/reg-event :count/inc (fn [{:keys [db]} _] {:db (update db :count inc)}))
(rf/reg-event :note/set
  (fn [{:keys [db]} [_ text]]
    {:db (assoc db :note text)}))

(rf/reg-sub :count (fn [db _] (:count db)))
(rf/reg-sub :note  (fn [db _] (:note db)))
```

`:app/init` seeds app-db. `:count/inc` bumps the count. `:note/set` stores the
note text. The view will only *consume* these pieces; it will not invent its own
store.

## Step 2 — the view

Here is the component in its first form:

```clojure
(v/defview counter [_]
  [:div.counter
   [:p "Count: " (sub [:count])]])
```

`v/defview` takes one props map. We are not using props yet, so the parameter is
`_`. The body returns Hiccup.

`(sub [:count])` is the current value of that subscription, dropped straight into
the paragraph. When `[:count]` changes, this view boundary re-renders. There is no
`@` and no ratom.

**How you call it:** write `[counter {}]` in a parent (or at the mount root).  
**How you must not call it:** `(counter {})` — that is for helpers, not for
declared views.

## Step 3 — an event as data

To change the count, dispatch an event. In Freehand the common handler is a
vector, not a closure:

```clojure hl_lines="4"
(v/defview counter [_]
  [:div.counter
   [:p "Count: " (sub [:count])]
   [:button {:on-click [:count/inc]} "Increment"]])
```

`{:on-click [:count/inc]}` means: when this button is clicked, dispatch
`[:count/inc]` on the frame this view is running under.

The intent is visible in source and in tools before anything runs. The pipeline is
the usual re-frame one: click → event → app-db → subscription change → this view
re-renders with the new number.

## Step 4 — a controlled field

Now put the note in app-db and wire a controlled input. Freehand fills in
`::v/value` from the live DOM event when the input fires:

```clojure hl_lines="5 6 7"
(v/defview counter [_]
  [:div.counter
   [:p "Count: " (sub [:count])]
   [:button {:on-click [:count/inc]} "Increment"]
   [:input {:value       (sub [:note])
            :on-input    [:note/set ::v/value]
            :placeholder "A note…"}]])
```

Because the element has both `:value` and `:on-input`, this site uses Freehand’s
**synchronous controlled-input door**. In plain language: Freehand dispatches the
event, lets re-frame settle, and updates the views that care about this frame
*before* React finishes handling the keystroke. That is what keeps the caret and
IME from fighting the controlled value. You do not configure the door; eligible
sites get it by law.

This field is the simple pattern: **every keystroke updates domain state** in
app-db. That is often exactly what you want. Other patterns (draft-then-commit;
shared library field protocols) exist when you need them.

## Step 5 — a keyed child (optional, but typical)

Real screens have lists. Freehand’s natural shape is a **keyed declared child** so
each row is its own reactive boundary:

```clojure
(v/defview note-row [{:keys [id]}]
  (let [text (sub [:notes/by-id id])]
    [:li
     [:input {:value    text
              :on-input [:notes/set id ::v/value]}]]))

(v/defview note-list [_]
  [:ul
   (for [id (sub [:notes/ids])]
     [note-row {:key id :id id}])])
```

A few points worth fixing in your head now:

- `[note-row …]` mounts a boundary. If one note’s text changes, that row is what
  re-renders — not every row by default.  
- A plain helper called with parentheses would **inline** into the parent. That is
  fine for pure markup. It is the wrong tool when each row must subscribe and
  update on its own.  
- `:key` tells Freehand and React which sibling is which across reorders. Prefer a
  stable domain id, not the loop index, when identity matters.

## Step 6 — mount it (Freehand + frame into the DOM)

A view becomes a running app when you mount a Freehand **root** into a DOM node.
That is how Freehand (and its frame) enter the page — not by tagging raw HTML with
a frame id.

```clojure
(defn ^:export run []
  ;; Adapter install follows re-frame2's (rf/init! adapter) pattern
  (rf/init! freehand-adapter)
  (v/mount [counter {}]
           (js/document.getElementById "root")))
```

Think of two layers:

| Layer | Meaning |
|---|---|
| **DOM container** | e.g. `#root` — where React/Freehand attaches |
| **Frame** | re-frame world: app-db, events, subs for this UI |

**Seeding before first paint** is frame **preflight** (the Root Descriptor story).
In production style, the frame should exist and can drain `:initial-events` such as
`[:app/init]` *before* React paints, so you do not flash empty count and note.

The contracts to remember:

1. seed once before first render  
2. hot reload should **reuse** the live frame so state survives edits  
3. multi-root pages need an explicit root identity when containers could collide  
4. embedding Freehand inside foreign React uses `v/->react` and an **existing**
   frame  

Until samples land with polished preflight helpers, a temporary dev boot may
`dispatch-sync` init before `mount`. Prefer preflight for the real boot path so
first paint and HMR share one story.

## The complete counter

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v :refer [sub]]))

;; ---- dataflow: plain re-frame2 --------------------------------------------

(rf/reg-event :app/init  (fn [_ _] {:db {:count 0 :note ""}}))
(rf/reg-event :count/inc (fn [{:keys [db]} _] {:db (update db :count inc)}))
(rf/reg-event :note/set
  (fn [{:keys [db]} [_ text]]
    {:db (assoc db :note text)}))

(rf/reg-sub :count (fn [db _] (:count db)))
(rf/reg-sub :note  (fn [db _] (:note db)))

;; ---- view -----------------------------------------------------------------

(v/defview counter [_]
  [:div.counter
   [:p "Count: " (sub [:count])]
   [:button {:on-click [:count/inc]} "Increment"]
   [:input {:value       (sub [:note])
            :on-input    [:note/set ::v/value]
            :placeholder "A note…"}]])

;; ---- mount ----------------------------------------------------------------

(defn ^:export run []
  (rf/init! freehand-adapter)
  ;; Prefer frame preflight so seed events drain before paint — see install.md
  (v/mount [counter {}]
           (js/document.getElementById "root")))
```

That is a complete Freehand view: a subscription as a value, an event as data, a
controlled field on the sync door, mounted under a frame. No component-local
reactive state, and no dispatch closures on the paved path.

## Day-one checklist

You can stop here and still ship a simple Freehand screen if you can:

- register events and subs in plain re-frame2  
- write a `v/defview` that uses `(sub …)` / `(v/sub …)` as a value  
- put an event vector on `:on-click`  
- wire a controlled input with `::v/value` on `:on-input`  
- mount with `v/mount` (and seed the frame before paint when you care about flash)

## If something feels wrong

| Symptom | Likely cause | Fix |
|---|---|---|
| Button does nothing | event not registered / wrong frame | check `reg-event` id; confirm mount bound a live frame |
| Input caret jumps or characters drop | not on the controlled door | use `:value` + `:on-input` (or `:on-change`) with a data handler |
| `v/sub` throws outside render | render-only rule | use `rf/subscribe-once` in tools/tests |
| `(my-view {})` fails | descriptors are not `IFn` | call `[my-view {}]` |
| First paint empty then flash | seed after paint | preflight / seed before mount |
