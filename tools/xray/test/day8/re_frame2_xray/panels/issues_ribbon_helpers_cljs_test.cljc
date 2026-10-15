(ns day8.re-frame2-xray.panels.issues-ribbon-helpers-cljs-test
  "The focused epoch's issue subset excludes normal activity."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-xray.panels.issues-ribbon-helpers :as h]))

(deftest project-feed-renders-issues-from-trace-events
  (let [feed (h/project-feed {:trace-events [{:id 1 :op-type :error}
                                            {:id 2 :op-type :rf.event}
                                            {:id 3 :op-type :warning}
                                            {:id 4 :op-type :info}]}
                             :focused)]
    (is (= [1 3] (sort (map :id (:issues feed)))))))
