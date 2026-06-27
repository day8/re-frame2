(ns todomvc.subs
  "The derivation graph the views read from.

  This is `reg-sub` composed in layers. The bottom layer reads app-db directly
  (`:todo/sorted-todos`); the layers above stack on top of it
  (`:todo/visible-todos` filters the list against `:todo/showing`,
  `:todo/footer-counts` adds up the tallies). `:todo/showing` is the neat one —
  it derives the active filter from the route id, the very filter app-db makes a
  point of never storing. It's pure functions all the way down, and each one
  recomputes only when an input it actually depends on moves.
  See docs/guide/glossary.md (subscription)."
  (:require [re-frame.core :as rf]))

;; :todo/showing turns the route id into the active filter. Both
;; :rf.route/not-found and an unset route quietly fall through to :all, so the UI
;; always has a sensible default to show.
(rf/reg-sub :todo/showing
  :<- [:rf.route/id]
  (fn [route-id _]
    (case route-id
      :todo/active    :active
      :todo/completed :completed
      :all)))

(rf/reg-sub :todo/sorted-todos
  (fn [db _]
    (:todos db)))

(rf/reg-sub :todo/todos
  :<- [:todo/sorted-todos]
  (fn [sorted-todos _]
    (vals sorted-todos)))

(rf/reg-sub :todo/visible-todos
  :<- [:todo/todos]
  :<- [:todo/showing]
  (fn [[todos showing] _]
    (let [predicate (case showing
                      :active    (complement :completed)
                      :completed :completed
                      identity)]
      (filter predicate todos))))

(rf/reg-sub :todo/all-complete?
  :<- [:todo/todos]
  (fn [todos _]
    (and (seq todos) (every? :completed todos))))

(rf/reg-sub :todo/completed-count
  :<- [:todo/todos]
  (fn [todos _]
    (count (filter :completed todos))))

(rf/reg-sub :todo/footer-counts
  :<- [:todo/todos]
  :<- [:todo/completed-count]
  (fn [[todos completed-count] _]
    [(- (count todos) completed-count) completed-count]))

;; ---- UI / form state projections (the `:ui` slice) ------------------------
;;
;; These are what the views read instead of stashing state in view-local atoms.
;; `:value` comes from a draft sub, and `:todo.ui/editing?` answers one small
;; question per row — "am I the one being edited?" — so a row only bothers to
;; render its controlled edit input when it actually owns the editing id.

(rf/reg-sub :todo.ui/editing-id
  (fn [db _]
    (get-in db [:ui :editing-id])))

(rf/reg-sub :todo.ui/editing?
  :<- [:todo.ui/editing-id]
  (fn [editing-id [_ id]]
    (= editing-id id)))

(rf/reg-sub :todo.ui/draft
  {:doc "The live text inside a controlled input. `which` is `:new` or `:edit`."}
  (fn [db [_ which]]
    (get-in db [:ui :drafts which])))
