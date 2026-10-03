# Getting started

Build a todo list whose buttons toggle an item's completion. This page
replaces the counter from [Installation](00-installation.md) with three
application files: the views, their event and subscription registrations, and
the entry point.

```clojure
;; src/my/app/views.cljs
(ns my.app.views
  (:require [my.app.model]
            [re-frame.fresco :as h]))

(h/defview todo-row [{:keys [id]}]
  (let [{:keys [title done?]} (h/sub [:todo/by-id id])]
    [:li
     [:span title (when done? " (done)") " "]
     [:button {:type "button" :on-click [:todo/toggle id]} "Toggle"]]))

(h/defview todo-list [_]
  [:ul
   (for [{:keys [id]} (h/sub [:todo/visible])]
     [todo-row {:key id :id id}])])
```

`todo-list` reads the visible todos. Each keyed row reads its own todo and
sends `[:todo/toggle id]` when clicked. Fresco creates the callback and
dispatches to the row's frame; when the subscription value changes, the row
re-renders.

## The todo model

Put the registrations in `src/my/app/model.cljs`. Requiring this namespace
runs its `reg-event` and `reg-sub` forms before the views render. The model is
ordinary re-frame2, so it can also be used with Reagent or UIx.

The first screen uses `:todo/initialise`, `:todo/toggle`, `:todo/by-id`, and
`:todo/visible`. The add, delete, and filter registrations give later examples
one model to build on.

```clojure
;; src/my/app/model.cljs
;; cf. examples/core/todomvc/events.cljs and subs.cljs
(ns my.app.model
  (:require [re-frame.core :as rf]))

(def initial-db
  {:todos   {1 {:id 1 :title "Buy milk"     :done? false}
             2 {:id 2 :title "Walk the dog" :done? true}}
   :showing :all})

(rf/reg-event :todo/initialise
  (fn [_ _]
    {:db initial-db}))

(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [id (inc (apply max 0 (keys (:todos db))))]
      {:db (assoc-in db [:todos id] {:id id :title title :done? false})})))

(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todos id :done?] not)}))

(rf/reg-event :todo/delete
  (fn [{:keys [db]} [_ id]]
    {:db (update db :todos dissoc id)}))

(rf/reg-event :todo/set-showing
  (fn [{:keys [db]} [_ showing]]
    {:db (assoc db :showing showing)}))

(rf/reg-sub :todo/todos
  (fn [db _]
    (:todos db)))

(rf/reg-sub :todo/all {:inputs [[:todo/todos]]}
  (fn [[todos] _]
    (vec (sort-by :id (vals todos)))))

(rf/reg-sub :todo/by-id
  (fn [db [_ id]]
    (get-in db [:todos id])))

(rf/reg-sub :todo/showing
  (fn [db _]
    (:showing db)))

(rf/reg-sub :todo/visible
  {:inputs [[:todo/all] [:todo/showing]]}
  (fn [[todos showing] _]
    (case showing
      :active (filterv (complement :done?) todos)
      :done   (filterv :done? todos)
      todos)))
```

## Mount the todo list

Create `src/my/app.cljs`:

```clojure
;; cf. examples/substrates/fresco/login/core.cljs (client-only boot)
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as h]
            [re-frame.fresco.substrate :as substrate]
            [my.app.views :as views]))

(defonce app-root (h/client-root))

(defn ^:dev/after-load mount! []
  (h/render! app-root
             [h/frame-root {:id :app :initial-events [[:todo/initialise]]}
              [views/todo-list]]
             (js/document.getElementById "app")))

(defn ^:export init []
  (rf/init! substrate/adapter)
  (mount!)
  nil)
```

In Installation's `shadow-cljs.edn`, replace `:init-fn counter.core/init`
with `:init-fn my.app/init`. Keep its dependencies and `public/index.html`,
then restart `npx shadow-cljs watch app` and reload the page. A reload creates
a new frame; a hot reload would keep the counter's existing app-db.

The page shows **Buy milk** and **Walk the dog (done)**. Click either Toggle
button and its `(done)` text appears or disappears. `:initial-events` seeds
the todos before the first render, and subsequent hot reloads preserve them.

You can also run it here. The cell below holds the views and the part of the
model the first screen uses, and it ends with the tree `mount!` renders. Edit
it and press **`Ctrl-Enter`** (**`Cmd-Enter`** on macOS) to run it again.

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.fresco :as h])

(rf/reg-event :todo/initialise
  (fn [_ _]
    {:db {:todos   {1 {:id 1 :title "Buy milk"     :done? false}
                    2 {:id 2 :title "Walk the dog" :done? true}}
          :showing :all}}))

(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todos id :done?] not)}))

(rf/reg-sub :todo/todos (fn [db _] (:todos db)))
(rf/reg-sub :todo/showing (fn [db _] (:showing db)))

(rf/reg-sub :todo/all {:inputs [[:todo/todos]]}
  (fn [[todos] _]
    (vec (sort-by :id (vals todos)))))

(rf/reg-sub :todo/by-id
  (fn [db [_ id]]
    (get-in db [:todos id])))

(rf/reg-sub :todo/visible
  {:inputs [[:todo/all] [:todo/showing]]}
  (fn [[todos showing] _]
    (case showing
      :active (filterv (complement :done?) todos)
      :done   (filterv :done? todos)
      todos)))

(h/defview todo-row [{:keys [id]}]
  (let [{:keys [title done?]} (h/sub [:todo/by-id id])]
    [:li
     [:span title (when done? " (done)") " "]
     [:button {:type "button" :on-click [:todo/toggle id]} "Toggle"]]))

(h/defview todo-list [_]
  [:ul
   (for [{:keys [id]} (h/sub [:todo/visible])]
     [todo-row {:key id :id id}])])

[h/frame-root {:id :app :initial-events [[:todo/initialise]]}
 [todo-list]]
```

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
| Boot reports `:rf.error/no-such-handler` for `:todo/initialise`, or the view reports `:rf.error/no-such-sub` | The model namespace was not loaded | Keep `[my.app.model]` in the view namespace's requires |
| Switching from the counter leaves an empty list or reports `:rf.error/frame-root-reconfigured` | Hot reload kept the counter's frame or changed its mounted options | Reload the page so `:todo/initialise` seeds a new frame |
