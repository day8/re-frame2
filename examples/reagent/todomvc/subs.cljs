(ns todomvc.subs
  "The derivation graph the views read.

  Demonstrates: `reg-sub` composed in layers. Some subs read app-db directly
  (`:todo/sorted-todos`); others combine subs into a derivation graph
  (`:todo/visible-todos` filters the list against `:todo/showing`,
  `:todo/footer-counts` folds the tallies). `:todo/showing` derives the active
  filter from the route id — the filter that app-db deliberately never stores.
  Pure functions all the way down, recomputed only when an input actually moves.
  See docs/guide/glossary.md (subscription)."
  (:require [re-frame.core :as rf]))

;; :todo/showing derives the active filter from the route id. :rf.route/not-found
;; and an unset route both fall through to :all so the UI defaults sensibly.
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
;; Views read these instead of holding view-local atoms: `:value` reads a draft
;; sub, and `:todo.ui/editing?` answers "is THIS row the one being edited?" so a
;; row renders its controlled edit input only when it owns the editing id.

(rf/reg-sub :todo.ui/editing-id
  (fn [db _]
    (get-in db [:ui :editing-id])))

(rf/reg-sub :todo.ui/editing?
  :<- [:todo.ui/editing-id]
  (fn [editing-id [_ id]]
    (= editing-id id)))

(rf/reg-sub :todo.ui/draft
  {:doc "The live value of a controlled input. `which` is `:new` or `:edit`."}
  (fn [db [_ which]]
    (get-in db [:ui :drafts which])))
