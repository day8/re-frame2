(ns reagent2.impl.diag-cljs-test
  "Content-free diagnostic summaries match the independent re-frame implementation."
  (:require [cljs.test :refer-macros [deftest is]]
            [reagent2.impl.diag :as diag]
            [re-frame.error :as rf.error]))

(deftest value-summary-survives-a-throwing-tostring
  (let [boom (js-obj)]
    (set! (.-toString boom) (fn [] (throw (js/Error. "boom"))))
    (is (= {:type :scalar} (diag/value-summary boom))
        "an unknown host object summarises to its bare shape tag")
    (is (= {:type :map :count 2} (diag/value-summary {boom :v :ok 1}))
        "and cannot ride out through a map's key list either")))

;; One value per branch; map keys and text remain sensitive payloads.
(def ^:private parity-corpus
  [nil
   {"SENTINELSENTINELSENTINEL" 1}
   [:div {:class "x"} "leaf text over twenty-four characters long here"]
   #{:x :y :z}
   "a string that is definitely longer than twenty-four characters"
   :ws.app/request
   'reagent2.template/as-element
   true
   -12345678901234567890
   '(1 2 3)
   (fn [] :x)
   #js [1 2 3]
   (js/Date. 0)])

(deftest value-summary-mirrors-re-frame-error-diag-value-summary
  (doseq [v parity-corpus]
    (is (= (rf.error/diag-value-summary v) (diag/value-summary v))
        (str "mirror drift for value: " (pr-str v)))))
