(ns re-frame.todomvc-cljs-test
  "Behavioural regression coverage for the TodoMVC example (rf2-km3ssc).

  TodoMVC's dataflow — one event per intent, a layered sub graph, and a filter
  the app DELIBERATELY never stores (it derives `:todo/showing` from the route
  id: `one fact, one home`) — had almost no behavioural coverage. The only
  prior test pinned the cold-boot id-allocation / sorted-map invariant on the
  ADD path (rf2-mzqd4.1). Everything else was untested: `:todo/toggle-completed`,
  `:todo/save` (blank title -> delete), `:todo/delete`, `:todo/clear-completed`,
  `:todo/toggle-all` (mark-all-complete UNLESS all already complete), and the
  sub graph — `:todo/showing` (route -> filter), `:todo/visible-todos` (filter
  predicate), `:todo/all-complete?`, `:todo/footer-counts`. A regression in
  toggle-all's all-complete? inversion, the visible-todos predicate, or the
  route->filter mapping would produce a visibly broken TodoMVC with a green
  suite.

  These belong in the framework test tree, NOT under `examples/` (examples stay
  test-free per rf2-8cevm). They `:require` `todomvc.core` (a Reagent-coupled
  `.cljs`-only entry ns, transitively pulling its events / subs / db) so they
  run under the consolidated `:node-test` CLJS build, which has
  `../examples/core` on its source paths.

  Two axes:
    1. The event handlers — driven via `dispatch-sync` on an anon frame,
       asserting the resulting `:todos` app-db slice.
    2. The sub graph — read via `rf/compute-sub`. `:todo/showing` is a
       runtime-db sub chain (`:<- [:rf.route/id]`, which reads the route slice
       at `[:rf.runtime/routing :current :route-id]`), so those reads use a
       FULL frame-state value with the route-id injected — exercising the exact
       route->filter mapping across all three routes plus the fallback."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; Requiring the entry ns fires todomvc's ns-load reg-event /
            ;; reg-sub / reg-route / reg-fx / reg-cofx forms against the live
            ;; registrar (captured into this ns's fixture baseline).
            [todomvc.core]))

;; `:ambient-frame nil` — these tests create + drive their own anon frames with
;; an explicit `{:frame f}`; no ambient `:rf/default` scope is wanted.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- todo-frame!
  "Boot a fresh anon frame seeded via `:todo/initialise`. The initialise cofx
  reads localStorage; under Node there is none, so it seeds an empty
  sorted-map — deterministic. Returns the frame id."
  []
  (let [f (frame/make-anon-frame-record! {:doc "todomvc test frame"})]
    (rf/dispatch-sync [:todo/initialise] {:frame f})
    f))

(defn- todos [f]
  (:todos (rf/app-db-value f)))

(defn- with-route
  "The frame's full state with the route-id injected into the runtime-db route
  slice, so the `:rf.route/id`-derived `:todo/showing` chain computes as it
  would under a real navigation. Pass to `compute-sub` for the showing /
  visible-todos reads."
  [f route-id]
  (assoc-in (rf/frame-state-value f)
            [:rf.db/runtime :rf.runtime/routing :current :route-id]
            route-id))

(defn- titles [todo-seq] (set (map :title todo-seq)))

;; ---------------------------------------------------------------------------
;; events
;; ---------------------------------------------------------------------------

(deftest add-allocates-ids-and-skips-blank
  (testing "add appends with a monotonic id off the sorted-map's high key, trims
            the title, and a blank/whitespace title is a no-op that allocates
            no id"
    (let [f (todo-frame!)]
      (is (= [] (vec (keys (todos f)))) "initialise seeds an empty list")
      (rf/dispatch-sync [:todo/add "Buy milk"] {:frame f})
      (rf/dispatch-sync [:todo/add "   "] {:frame f})        ;; blank -> skipped
      (rf/dispatch-sync [:todo/add "  Walk dog  "] {:frame f})
      (is (= [1 2] (vec (keys (todos f))))
          "blank allocated no id — the second real add is 2, not 3")
      (is (= "Buy milk" (get-in (todos f) [1 :title])))
      (is (= "Walk dog" (get-in (todos f) [2 :title])) "title is trimmed")
      (is (false? (get-in (todos f) [1 :completed])) "new todos start active"))))

(deftest toggle-completed-flips-one-row
  (testing "toggle-completed flips exactly the addressed row's :completed flag"
    (let [f (todo-frame!)]
      (rf/dispatch-sync [:todo/add "a"] {:frame f})
      (rf/dispatch-sync [:todo/add "b"] {:frame f})
      (rf/dispatch-sync [:todo/toggle-completed 1] {:frame f})
      (is (true?  (get-in (todos f) [1 :completed])))
      (is (false? (get-in (todos f) [2 :completed])) "the other row is untouched")
      (rf/dispatch-sync [:todo/toggle-completed 1] {:frame f})
      (is (false? (get-in (todos f) [1 :completed])) "toggling again flips back"))))

(deftest toggle-all-inverts-only-when-not-already-all-complete
  (testing "rf2-km3ssc — toggle-all marks every row complete UNLESS they are
            already all complete, in which case it marks them all active. A
            regression in that all-complete? inversion is a silent bug"
    (let [f (todo-frame!)]
      (rf/dispatch-sync [:todo/add "a"] {:frame f})
      (rf/dispatch-sync [:todo/add "b"] {:frame f})
      (rf/dispatch-sync [:todo/add "c"] {:frame f})
      ;; one already complete, two active -> NOT all complete -> mark all complete
      (rf/dispatch-sync [:todo/toggle-completed 2] {:frame f})
      (rf/dispatch-sync [:todo/toggle-all] {:frame f})
      (is (every? :completed (vals (todos f))) "mixed -> all complete")
      ;; now all complete -> toggle-all marks them all active
      (rf/dispatch-sync [:todo/toggle-all] {:frame f})
      (is (not-any? :completed (vals (todos f))) "all-complete -> all active"))))

(deftest save-trims-non-blank-and-deletes-on-blank
  (testing "save with a non-blank title updates + trims that row; save with a
            blank title deletes the row entirely"
    (let [f (todo-frame!)]
      (rf/dispatch-sync [:todo/add "a"] {:frame f})
      (rf/dispatch-sync [:todo/add "b"] {:frame f})
      (rf/dispatch-sync [:todo/save 1 "  renamed  "] {:frame f})
      (is (= "renamed" (get-in (todos f) [1 :title])) "non-blank save trims + updates")
      (rf/dispatch-sync [:todo/save 2 "   "] {:frame f})
      (is (nil? (get-in (todos f) [2])) "blank save deletes the row")
      (is (= [1] (vec (keys (todos f))))))))

(deftest delete-removes-the-row
  (testing "delete removes exactly the addressed row"
    (let [f (todo-frame!)]
      (rf/dispatch-sync [:todo/add "a"] {:frame f})
      (rf/dispatch-sync [:todo/add "b"] {:frame f})
      (rf/dispatch-sync [:todo/delete 1] {:frame f})
      (is (= [2] (vec (keys (todos f)))))
      (is (= "b" (get-in (todos f) [2 :title]))))))

(deftest clear-completed-removes-completed-and-keeps-sorted-map
  (testing "clear-completed drops every completed row, keeps the active ones,
            and the result stays a sorted-map (allocate-next-id depends on it)"
    (let [f (todo-frame!)]
      (rf/dispatch-sync [:todo/add "a"] {:frame f})
      (rf/dispatch-sync [:todo/add "b"] {:frame f})
      (rf/dispatch-sync [:todo/add "c"] {:frame f})
      (rf/dispatch-sync [:todo/toggle-completed 1] {:frame f})
      (rf/dispatch-sync [:todo/toggle-completed 3] {:frame f})
      (rf/dispatch-sync [:todo/clear-completed] {:frame f})
      (is (= [2] (vec (keys (todos f)))) "only the active row survives")
      (is (sorted? (todos f)) "clear-completed re-folds into a sorted-map"))))

;; ---------------------------------------------------------------------------
;; sub graph — showing / visible-todos / all-complete? / footer-counts
;; ---------------------------------------------------------------------------

(deftest showing-derives-filter-from-route-id
  (testing "rf2-km3ssc — :todo/showing maps the route id to the active filter;
            the not-found route AND an unset route both fall through to :all
            (the filter the app never stores). A regression in this mapping is
            a silent bug"
    (let [f (todo-frame!)]
      (is (= :all       (rf/compute-sub [:todo/showing] (with-route f :todo/all))))
      (is (= :active    (rf/compute-sub [:todo/showing] (with-route f :todo/active))))
      (is (= :completed (rf/compute-sub [:todo/showing] (with-route f :todo/completed))))
      (is (= :all       (rf/compute-sub [:todo/showing] (with-route f :rf.route/not-found)))
          "not-found falls through to :all")
      (is (= :all       (rf/compute-sub [:todo/showing] (rf/frame-state-value f)))
          "an unset route (no slice) also falls through to :all"))))

(deftest visible-todos-filters-per-showing
  (testing "rf2-km3ssc — :todo/visible-todos applies the per-showing predicate:
            :all -> everything, :active -> incomplete only, :completed ->
            complete only. A wrong predicate is a silent bug"
    (let [f (todo-frame!)]
      (rf/dispatch-sync [:todo/add "active-one"] {:frame f})
      (rf/dispatch-sync [:todo/add "done-one"] {:frame f})
      (rf/dispatch-sync [:todo/toggle-completed 2] {:frame f})  ;; done-one complete
      (is (= #{"active-one" "done-one"}
             (titles (rf/compute-sub [:todo/visible-todos] (with-route f :todo/all)))))
      (is (= #{"active-one"}
             (titles (rf/compute-sub [:todo/visible-todos] (with-route f :todo/active)))))
      (is (= #{"done-one"}
             (titles (rf/compute-sub [:todo/visible-todos] (with-route f :todo/completed))))))))

(deftest all-complete?-and-footer-counts
  (testing ":todo/all-complete? is false while any row is active and true only
            when there are rows and all are complete; :todo/footer-counts is
            [active-count completed-count]"
    (let [f (todo-frame!)]
      (is (false? (boolean (rf/compute-sub [:todo/all-complete?] (rf/frame-state-value f))))
          "empty list is not all-complete")
      (rf/dispatch-sync [:todo/add "a"] {:frame f})
      (rf/dispatch-sync [:todo/add "b"] {:frame f})
      (rf/dispatch-sync [:todo/toggle-completed 1] {:frame f})
      (is (false? (rf/compute-sub [:todo/all-complete?] (rf/frame-state-value f)))
          "one active row -> not all complete")
      (is (= [1 1] (rf/compute-sub [:todo/footer-counts] (rf/frame-state-value f)))
          "one active, one completed")
      (rf/dispatch-sync [:todo/toggle-completed 2] {:frame f})
      (is (true? (rf/compute-sub [:todo/all-complete?] (rf/frame-state-value f)))
          "every row complete -> all complete")
      (is (= [0 2] (rf/compute-sub [:todo/footer-counts] (rf/frame-state-value f)))))))
