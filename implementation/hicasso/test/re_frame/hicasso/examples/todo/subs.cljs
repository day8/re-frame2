(ns re-frame.hicasso.examples.todo.subs
  "THE DERIVATION GRAPH (rf2-hic-086).

  Layers, and every one of them a pure function of the layer below. The
  bottom reads `app-db`; nothing above it does. `h/sub` in a view reads
  the top.

  The `:<-` chain rather than `reg-sub`'s two-fn form throughout, and for
  the reason rf2-hic-025 recorded: the two-fn form puts a ONE-argument
  input fn beside a TWO-argument computation fn in the same form, the
  mistake compiles, and the chain has no such adjacency. Confirmed here
  from a second application.

  ## The filter is not stored, so it cannot be stale

  [[showing]] is the interesting one. It reads routing's own
  `[:rf.route/id]` and `[:rf.route/params]` — registered by the routing
  artefact, reached by ID rather than by dependency, so this namespace
  requires only `re-frame.core` and its own routes — and answers a
  keyword. A URL is user input, so `/hicasso-todo/banana` and an
  unmatched URL both coerce to `:all` rather than producing a filter
  nothing can render."
  (:require [re-frame.core :as rf]
            [re-frame.hicasso.examples.todo.routes :as routes]))

;; ---------------------------------------------------------------------------
;; What is on the page
;; ---------------------------------------------------------------------------

(rf/reg-sub ::todos
  (fn [db _] (vals (:todos db))))

(rf/reg-sub ::new-todo
  {:doc "The controlled new-to-do box's text — the only place it lives."}
  (fn [db _] (:new-todo db)))

(rf/reg-sub ::showing
  {:doc "The active filter, derived from the URL: `:all`, `:active` or
         `:completed`. Two route ids and one path parameter collapse into one
         keyword here, so every reader downstream — the list, the three tabs —
         asks one question and `app-db` holds no copy of the answer."}
  :<- [:rf.route/id]
  :<- [:rf.route/params]
  (fn [[route-id params] _]
    (if (= routes/filtered route-id)
      (case (:filter params)
        "active"    :active
        "completed" :completed
        :all)
      :all)))

(rf/reg-sub ::visible
  {:doc "The rows the list renders, in insertion order."}
  :<- [::todos]
  :<- [::showing]
  (fn [[todos showing] _]
    (case showing
      :active    (remove :done? todos)
      :completed (filter :done? todos)
      todos)))

;; ---------------------------------------------------------------------------
;; The counts the chrome branches on
;; ---------------------------------------------------------------------------

(rf/reg-sub ::any?
  {:doc "Is there anything at all? The whole main section and the footer are
         absent when there is not — the Todo class's one piece of conditional
         chrome."}
  :<- [::todos]
  (fn [todos _] (boolean (seq todos))))

(rf/reg-sub ::active-count
  :<- [::todos]
  (fn [todos _] (count (remove :done? todos))))

(rf/reg-sub ::completed-count
  :<- [::todos]
  (fn [todos _] (count (filter :done? todos))))

(rf/reg-sub ::all-done?
  {:doc "Drives the toggle-all checkbox. An empty list is NOT all-done —
         otherwise the box would sit checked over nothing."}
  :<- [::todos]
  (fn [todos _] (and (boolean (seq todos)) (every? :done? todos))))
