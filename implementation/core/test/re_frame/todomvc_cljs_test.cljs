(ns re-frame.todomvc-cljs-test
  "Event handlers and sub graph of the TodoMVC example (`todomvc.core`). The
  active filter is never stored: `:todo/showing` derives it from the route id,
  so the sub reads take a full frame-state value with the route id injected.
  The cold-boot sorted-map invariant on the add path lives in the adapter
  tree's `re-frame.todomvc-example-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [todomvc.core]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

(defn- todo-frame!
  "A fresh anon frame seeded by `:todo/initialise`. Node has no localStorage,
  so the seed is an empty sorted-map."
  []
  (let [f (rf.frame/make-anon-frame-record! {:doc "todomvc test frame"})]
    (rf/dispatch-sync [:todo/initialise] {:frame f})
    f))

(defn- todos [f]
  (:todos (rf/app-db-value f)))

(defn- with-route
  "Frame `f`'s full state with `route-id` as the current route, so the
  `:rf.route/id`-derived `:todo/showing` chain computes as after a navigation."
  [f route-id]
  (assoc-in (rf/frame-state-value f)
            [:rf.db/runtime :rf.runtime/routing :current :route-id]
            route-id))

(defn- titles [todo-seq] (set (map :title todo-seq)))

(deftest add-allocates-ids-and-skips-blank
  (let [f (todo-frame!)]
    (rf/dispatch-sync [:todo/add "Buy milk"] {:frame f})
    (rf/dispatch-sync [:todo/add "   "] {:frame f})
    (rf/dispatch-sync [:todo/add "  Walk dog  "] {:frame f})
    (is (= {1 {:id 1 :title "Buy milk" :completed false}
            2 {:id 2 :title "Walk dog" :completed false}}
           (todos f))
        "a blank title allocates no id, and titles are trimmed")))

(deftest toggle-completed-flips-one-row
  (let [f (todo-frame!)]
    (rf/dispatch-sync [:todo/add "a"] {:frame f})
    (rf/dispatch-sync [:todo/toggle-completed 1] {:frame f})
    (is (true? (get-in (todos f) [1 :completed])))
    (rf/dispatch-sync [:todo/toggle-completed 1] {:frame f})
    (is (false? (get-in (todos f) [1 :completed])) "toggling again flips back")))

(deftest toggle-all-inverts-only-when-not-already-all-complete
  (let [f (todo-frame!)]
    (rf/dispatch-sync [:todo/add "a"] {:frame f})
    (rf/dispatch-sync [:todo/add "b"] {:frame f})
    (rf/dispatch-sync [:todo/toggle-completed 2] {:frame f})
    (rf/dispatch-sync [:todo/toggle-all] {:frame f})
    (is (every? :completed (vals (todos f))) "mixed -> all complete")
    (rf/dispatch-sync [:todo/toggle-all] {:frame f})
    (is (not-any? :completed (vals (todos f))) "all complete -> all active")))

(deftest save-trims-non-blank-and-deletes-on-blank
  (let [f (todo-frame!)]
    (rf/dispatch-sync [:todo/add "a"] {:frame f})
    (rf/dispatch-sync [:todo/add "b"] {:frame f})
    (rf/dispatch-sync [:todo/save 1 "  renamed  "] {:frame f})
    (rf/dispatch-sync [:todo/save 2 "   "] {:frame f})
    (is (= {1 {:id 1 :title "renamed" :completed false}} (todos f)))))

(deftest delete-removes-the-row
  (let [f (todo-frame!)]
    (rf/dispatch-sync [:todo/add "a"] {:frame f})
    (rf/dispatch-sync [:todo/add "b"] {:frame f})
    (rf/dispatch-sync [:todo/delete 1] {:frame f})
    (is (= {2 {:id 2 :title "b" :completed false}} (todos f)))))

(deftest clear-completed-removes-completed-and-keeps-sorted-map
  (let [f (todo-frame!)]
    (rf/dispatch-sync [:todo/add "a"] {:frame f})
    (rf/dispatch-sync [:todo/add "b"] {:frame f})
    (rf/dispatch-sync [:todo/toggle-completed 1] {:frame f})
    (rf/dispatch-sync [:todo/clear-completed] {:frame f})
    (is (= [[2] true] [(vec (keys (todos f))) (sorted? (todos f))])
        "only the active row survives, still in a sorted-map (the id allocator reads its last key)")))

(deftest visible-todos-filters-per-showing
  (let [f (todo-frame!)]
    (rf/dispatch-sync [:todo/add "active-one"] {:frame f})
    (rf/dispatch-sync [:todo/add "done-one"] {:frame f})
    (rf/dispatch-sync [:todo/toggle-completed 2] {:frame f})
    (doseq [[route-id expected] [[:todo/all       #{"active-one" "done-one"}]
                                 [:todo/active    #{"active-one"}]
                                 [:todo/completed #{"done-one"}]]]
      (is (= expected (titles (rf/compute-sub [:todo/visible-todos] (with-route f route-id))))
          (str route-id)))
    (is (= :all (rf/compute-sub [:todo/showing] (rf/frame-state-value f)))
        "with no route set, :todo/showing falls through to :all")))

(deftest all-complete?-and-footer-counts
  (let [f (todo-frame!)]
    (is (not (rf/compute-sub [:todo/all-complete?] (rf/frame-state-value f)))
        "an empty list is not all-complete")
    (rf/dispatch-sync [:todo/add "a"] {:frame f})
    (rf/dispatch-sync [:todo/add "b"] {:frame f})
    (rf/dispatch-sync [:todo/toggle-completed 1] {:frame f})
    (is (= [false [1 1]]
           [(rf/compute-sub [:todo/all-complete?] (rf/frame-state-value f))
            (rf/compute-sub [:todo/footer-counts] (rf/frame-state-value f))]))
    (rf/dispatch-sync [:todo/toggle-completed 2] {:frame f})
    (is (= [true [0 2]]
           [(rf/compute-sub [:todo/all-complete?] (rf/frame-state-value f))
            (rf/compute-sub [:todo/footer-counts] (rf/frame-state-value f))]))))
