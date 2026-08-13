# Build a view

A working Freehand screen, one idea at a time: subscription as a value, handler as
data, controlled field, mount. Not a tour of every option. The project
[depends on Freehand](install.md).

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

**How you mount it:** write `[counter {}]` in a parent, or at the mount root.  
**How you must not call it:** `(counter {})` raises `:rf.error/view-called-directly`.
Parentheses are for plain `defn` helpers, which run inside whatever boundary
called them.

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
  (rf/init! v/adapter)
  (v/mount [counter {}]
           (js/document.getElementById "root")
           {:frame {:id :my.app/main :initial-events [[:app/init]]}}))
```

Three things happen there, in order. `rf/init!` installs the reactive substrate —
without it, minting a frame raises `:rf.error/no-adapter-installed`. `v/adapter` is
Freehand's own; an app already on Reagent or UIx installs that adapter here instead.
The `:frame`
plan mints `:my.app/main` and drains `[:app/init]` **before** React is handed
anything, so the first render reads a seeded app-db rather than flashing an empty
count. Then the root attaches to `#root`.

Think of two layers:

| Layer | Meaning |
|---|---|
| **DOM container** | e.g. `#root` — where React and Freehand attach |
| **Frame** | re-frame world: app-db, events, subs for this UI |

The contracts to remember:

1. seed through the mount's `:frame` plan, not with a `dispatch-sync` beforehand  
2. hot reload **re-renders the live root** when the root-id and container are
   unchanged, so app-db survives a save  
3. multi-root pages need a `:disambiguator` or explicit `:root-id` when one view
   mounts twice  
4. embedding Freehand inside foreign React uses `v/->react` and an **existing**
   frame

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
  (rf/init! v/adapter)
  (v/mount [counter {}]
           (js/document.getElementById "root")
           {:frame {:id :my.app/main :initial-events [[:app/init]]}}))
```

That is a complete Freehand view: a subscription as a value, an event as data, a
controlled field on the sync door, mounted under a frame. No view-local
reactive state, and no dispatch closures on the paved path.

## When not

- Heavy markup helpers that return structure at runtime — stay interpreted, or
  extract declared children before compiling.  
- Second reactive store for the view — re-frame or a host boundary, never a
  view-local cell.

## Recap

You can stop here and still ship a simple Freehand screen if you can:

- register events and subs in plain re-frame2  
- write a `v/defview` that uses `(sub …)` / `(v/sub …)` as a value  
- put an event vector on `:on-click`  
- wire a controlled input with `::v/value` on `:on-input`  
- mount with `v/mount`, seeding the frame in the same call

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Button does nothing | event not registered / wrong frame | check `reg-event` id; confirm mount bound a live frame |
| Input caret jumps or characters drop | not on the controlled door | use `:value` + `:on-input` (or `:on-change`) with a data handler |
| `:rf.error/view-read-outside-render` | `v/sub` outside a render | use `rf/subscribe-once` in tools; `t/with-render` in tests |
| `:rf.error/view-called-directly` | `(my-view {})` | mount it: `[my-view {}]` |
| First paint empty then flash | seeding after mount | seed with the mount's `:frame` `:initial-events` |
