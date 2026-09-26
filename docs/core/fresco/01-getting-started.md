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
re-frame2: the app-db seed, `:todo/visible`, `:todo/by-id`, `:todo/toggle` and
the other registrations are [the todo model](cookbook.md#the-todo-model), and
later chapters add to it. Boot it as in
[Installation](00-installation.md#mount-a-first-screen), rendering
`[h/frame-root {:id :app :initial-events [[:todo/initialise]]} [todo-list]]`.

## What changes in a Fresco view

- No `@`: `h/sub` returns the value, and a helper the body calls can read too
  ([Views and reads](02-views-and-reads.md)).
- No dispatch closures: `[:todo/toggle id]` is the handler
  ([Events as data](03-events-as-data.md)).
- No local atom for a text field: `:value` plus `:on-input` with `::h/value`
  keeps the caret ([Controlled inputs](04-controlled-inputs.md)).
- Keys go in the props map, as in `[todo-row {:key id :id id}]`; `^{:key}`
  metadata is not read ([Lists and collections](06-lists-and-collections.md)).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A `defview` called as `(todo-row {:id 7})` ignores its props, or fails with React's invalid-hook error | A Fresco view is a React component used as a Hiccup head, not a function to call | Mount it as `[todo-row {:id 7}]`; use a plain `defn` for an inline helper |
| A plain helper written as `[row-icon props]` raises `:rf.error/fresco-bad-head` | A plain function appeared in Hiccup head position | Call it as `(row-icon props)`, or define it with `h/defview` when it needs to re-render on its own |
| `h/sub` raises `:rf.error/fresco-sub-outside-render` | The read ran outside the synchronous execution of a Fresco view | Read inside the view body and pass or close over the value |
| A controlled field drops characters or moves the caret | The write path became asynchronous, or the field left Fresco's controlled path | Dispatch the edit synchronously and follow [Controlled inputs](04-controlled-inputs.md) |
