# Getting started

The counter in [Installation](00-installation.md) used the three forms every
Fresco view is built from: `h/defview`, `h/sub` and event vectors. This chapter
shows them in the todo list the rest of the guide uses, and explains what
changes compared with a Reagent or UIx view.

```clojure
(ns todo.views
  (:require [re-frame.fresco :as h]))

(h/defview todo-row [{:keys [id]}]
  (let [{:keys [title done?]} (h/sub [:todo/by-id id])]
    [:li {:class (when done? "done")}
     [:span title]
     [:button {:on-click [:todo/toggle id]} "Toggle"]]))

(h/defview todo-list [_]
  [:ul
   (for [{:keys [id]} (h/sub [:todo/visible])]
     [todo-row {:key id :id id}])])
```

[Fresco](glossary.md#fresco) interprets this [Hiccup](../glossary.md#hiccup)
and produces React elements. Everything outside the views is ordinary
re-frame2: app-db holds `{:todos {1 {:id 1 :title "Buy milk" :done? false} …}}`,
`:todo/by-id` and `:todo/visible` are subscriptions registered with
`rf/reg-sub`, and `:todo/toggle` is an event registered with `rf/reg-event`.

## What changes in a Fresco view

### Markup and common handlers stay data

The button contains the event vector `[:todo/toggle id]` directly. Fresco
creates the React callback and dispatches that vector when the button is
clicked.

Because the Hiccup tree still contains the event, tests and tools can print,
inspect, and compare it with `=`. You can still use a function when a handler
needs imperative work or the callback's arguments;
[Events as data](03-events-as-data.md) covers when.

### Read subscriptions where they are used

`h/sub` is an ordinary function call. It can appear in a `let`, a conditional,
a loop, or a plain helper the view calls, so a parent does not have to
subscribe just to pass a value down.

A view created with [`h/defview`](glossary.md#defview) tracks the subscriptions
read during its body. When one of them changes, that view re-renders. A plain
`defn` helper does not re-render on its own; its reads belong to the view that
calls it.

### Controlled fields use ordinary attributes

A controlled text input uses `:value` and `:on-input`:

```clojure
[:input {:value    (h/sub [:todo.ui/draft id])
         :on-input [:todo.ui/edit id ::h/value]}]
```

`::h/value` is replaced with the input's current value when the event fires.
Fresco keeps the caret in place and handles IME composition, so you do not need
a local atom to keep the field usable.
[Controlled inputs](04-controlled-inputs.md) has the details.

### Optimise measured hot regions explicitly

Screens use interpreted Hiccup by default. If profiling identifies a hot part
of the tree, that region can move to native React or UIx while staying on the
same frame and app-db. [Performance](19-performance.md) describes how to
measure, and [Islands](10-native-tier.md) how to move a region.

## Fresco, Reagent, and UIx

Reagent and UIx remain supported view layers.

**Reagent** suits an existing application whose view layer already works and
where migration cost dominates. Fresco looks familiar because both use Hiccup,
but Fresco has no ratoms, reactions or Form-2 components.
[Migrating from Reagent](20-migration-from-reagent.md) covers the differences.

**UIx** is usually better when React organises the view layer: hooks are
common, a React design system dominates the tree, and the team thinks in React
component lifecycles. Fresco can host foreign React components through
[`h/defhost`](09-interop.md), but it does not try to replace a React-first
authoring model.

Choose Fresco when the application is primarily a re-frame2 application and you
want markup, reads, and ordinary interactions to stay inspectable data.

## Costs and limits

Interpreting Hiccup has a runtime cost. Cold mount can be slower than a
hand-written UIx equivalent, and each Fresco view pays a small fixed cost for
tracking its reads. Measure before moving code: a React island is for the part
of a screen that profiling identifies, not the default authoring style.

Fresco has no second reactive store inside the view layer. State that other
views, tests, tools, routing, or SSR must observe belongs in app-db. The few
cases for DOM-owned or local UI state are covered in
[Ephemeral state](11-ephemeral-state.md).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Calling a `defview` as `(todo-row {:id 7})` throws | A Fresco view is a React component used as a Hiccup head, not a function to call | Mount it as `[todo-row {:id 7}]`; use a plain `defn` for an inline helper |
| A plain helper written as `[row-icon props]` raises `:rf.error/fresco-bad-head` | A plain function appeared in Hiccup head position | Call it as `(row-icon props)`, or define it with `h/defview` when it needs to re-render on its own |
| `h/sub` raises `:rf.error/fresco-sub-outside-render` | The read ran outside the synchronous execution of a Fresco view | Read inside the view body and pass or close over the value |
| An event vector raises `:rf.error/fresco-intent-outside-boundary` | The event vector was turned into a callback outside any view's render, for example inside a function a foreign component calls later | Keep event vectors in Hiccup a view returns; inside a foreign callback, use `h/event` |
| A controlled field drops characters or moves the caret | The write path became asynchronous, or the field left Fresco's controlled path | Dispatch the edit synchronously and follow [Controlled inputs](04-controlled-inputs.md) |
