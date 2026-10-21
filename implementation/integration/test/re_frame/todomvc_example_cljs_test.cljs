(ns re-frame.todomvc-example-cljs-test
  "Cold boot preserves the sorted TodoMVC map and every title/id beyond unordered-map promotion."
  (:require [cljs.test :refer-macros [deftest use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.integration-adapter :as rf.integration-adapter]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            [re-frame.routing]
            [todomvc.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.integration-adapter/adapter
     :ambient-frame nil}))

(defn- todos [frame]
  (get (rf/app-db-value frame) :todos))

(deftest todomvc-cold-boot-id-allocation
  (with-new-frame [f (rf.frame/make-anon-frame-record!
                       {:fx-overrides {:todo.storage/save :rf/no-op}
                        :initial-events [[:todo/initialise]]})]
    (is (= [true 0] [(sorted? (todos f)) (count (todos f))]))
    ;; Cross the unordered-map promotion threshold that caused id reuse.
    (let [n 15]
      (dotimes [i n]
        (rf/dispatch-sync [:todo/add (str "todo-" i)] {:frame f}))
      (let [m (todos f)]
        (is (= [true (set (map #(str "todo-" %) (range n))) (range 1 (inc n))]
               [(sorted? m) (set (map :title (vals m))) (sort (keys m))]))))))
