(ns re-frame.mcp-base.elision-test
  "Pins the elision-marker walker, whose count rides the response envelope
  as the `:elided-large` indicator slot."
  (:require [clojure.test :refer [are deftest]]
            [re-frame.mcp-base.elision :as rf.mcp-base.elision]))

(def ^:private marker
  {:rf.size/large-elided {:path   [:user :pdf]
                          :bytes  102400
                          :type   :string
                          :reason :schema
                          :handle [:rf.elision/at [:user :pdf]]}})

(deftest count-elided-markers-walks-the-payload
  (are [v n] (= n (rf.mcp-base.elision/count-elided-markers v))
    nil                                                                 0
    {:ok? true :payload {:a 1 :b [2 3]}}                                0
    marker                                                              1
    {:slice1 marker :slice2 {:nested marker} :slice3 [{:deep marker}]} 3
    (map identity [{:slice 1} marker {:nested marker}])                 2
    ;; A marker body is opaque: a marker-shaped value inside it is not counted.
    (assoc-in marker [:rf.size/large-elided :extra] marker)             1))
