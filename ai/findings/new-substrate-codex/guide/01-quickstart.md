# Quickstart

## 1. Add the proposed dependencies

The coordinate and version are illustrative until an implementation is published:

```clojure
;; deps.edn
{:deps
 {day8/re-frame2-ui {:mvn/version "<alpha-version>"}}}
```

Install a patched React 19.2 line and React DOM as peer packages:

```json
{
  "dependencies": {
    "react": "^19.2.4",
    "react-dom": "^19.2.4"
  }
}
```

No Reagent, UIx, or Helix dependency is required.

## 2. Register an event and subscription

```clojure
(ns app.counter
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui]))

(rf/reg-event ::initialize
  (fn [_ _]
    {:db {:counter/value 0}}))

(rf/reg-event ::increment
  (fn [{:keys [db]} _]
    {:db (update db :counter/value inc)}))

(rf/reg-sub ::value
  (fn [db _]
    (:counter/value db 0)))
```

Nothing here is UI-specific. Events remain data-driven transitions and subscriptions remain cached projections.

## 3. Define a view

```clojure
(ui/defview counter [{:keys [label]}]
  (let [n (ui/sub [::value])]
    [:main.counter
     [:h1 label]
     [:p {:aria-live "polite"} n]
     [:button
      {:type :button
       :on-click [::increment]}
      "Increment"]]))
```

Important details:

- `ui/sub` returns the value `n`, not a reaction.
- `:on-click` contains event data, not a function.
- `:button` and its props compile directly to React JSX-runtime calls.
- The component receives one props map.
- The compiler registers view/source/site metadata for Xray in development.

## 4. Define the root view

```clojure
(ui/defview app []
  [counter {:label "A small counter"}])
```

Internal component calls use the same vector grammar as DOM nodes. The compiler knows `counter` is a `defview`, checks its literal props, and emits a direct React component element.

## 5. Mount under an explicit frame

```clojure
(ns app.client
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui]
            [app.counter :as counter]))

(defonce root
  (ui/create-root (js/document.getElementById "app")))

(defn ^:dev/after-load render! []
  (ui/render! root counter/app {}
    {:frame {:id :app/main
             :initial-events [[::counter/initialize]]}}))

(defn start! []
  (rf/init! ui/adapter)
  (render!))
```

`rf/init!` explicitly installs the substrate. `ui/render!` ensures `:app/main` on first render, reuses it without replaying initial events on hot render, and provides it to the view tree.

If the element is absent, fail in application boot rather than allowing a root with nowhere to render:

```clojure
(defn required-element [id]
  (or (js/document.getElementById id)
      (throw (ex-info "Missing mount element" {:id id}))))
```

## 6. What happens on click

The source is short, but the runtime path is precise:

1. The compiled button callback reads its committed event slot.
2. It dispatches `[::increment]` into `:app/main` with the event site's source identity.
3. re-frame2 runs the handler and replaces app-db.
4. `::value` recomputes and changes.
5. The counter's ViewCell is marked dirty once, arming the pending render batch.
6. The batch closes at the next host microtask checkpoint; React renders `counter` once and commits `1`.
7. Xray links the click site, event, app-db change, subscription, and render.

No callback is allocated on the update, and there is no Reagent render queue or runtime markup walk.

## 7. Add a parameterized subscription

```clojure
(rf/reg-sub ::todo
  (fn [db [_ todo-id]]
    (get-in db [:todos/by-id todo-id])))

(ui/defview todo-title [{:keys [todo-id]}]
  (let [todo (ui/sub [::todo todo-id])]
    [:h2 (:todo/title todo)]))
```

The compiler gives the read a stable site. While `todo-id` is `rf=` across renders, it reuses the prior query object. When the ID changes, the commit reconciler acquires the new node before releasing the old one.

## 8. Conditional reads are legal

```clojure
(ui/defview badge [{:keys [expanded?]}]
  (let [summary (ui/sub [::summary])
        detail  (when expanded?
                  (ui/sub [::detail]))]
    [:aside
     [:strong summary]
     (when detail [:pre detail])]))
```

`ui/sub` is not a React Hook. The committed dependency set includes `::detail` only while the committed `expanded?` branch uses it. An interrupted render cannot attach or detach the dependency.

Do not place `ui/sub` inside a list loop. Give each item a child view or register one aggregate subscription.

## 9. Stop the app

```clojure
(defn stop! []
  (ui/unmount! root)
  (rf/destroy-frame! :app/main))
```

Unmount releases ViewCell subscription refs, view resource owners, effects, and debug instance records. Frame destruction remains explicit because a provider scopes/ensures a frame; it does not own durable frame lifetime.

## Next

Read [Views and templates](02-views-and-templates.md) for the compiled grammar, then [State reads and events](03-state-and-events.md) for event extraction, forwarding, and imperative dispatch.
