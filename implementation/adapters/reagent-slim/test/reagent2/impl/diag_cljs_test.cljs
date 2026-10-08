(ns reagent2.impl.diag-cljs-test
  "`reagent2.impl.diag/value-summary` is the slim build's diagnostic redaction
  primitive: an error carries a content-free shape summary, never the
  app-owned value. It is a hand-copied mirror of
  `re-frame.error/diag-value-summary` (the slim production build may not
  require re-frame.*), so this pins the two equal over a corpus reaching
  every arm, plus the hostile-toString case the corpus cannot carry.
  `re-frame.diag-value-summary-cljs-test` pins each arm on the original."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.diag :as diag]
            [re-frame.error :as rf.error]))

;; ---- a hostile toString cannot throw OUT of the diagnostic ---------------

(deftest value-summary-survives-a-throwing-tostring
  (testing "no leg calls `(str v)` on a value the framework knows nothing
            about, so a hostile `toString` cannot throw out of the
            summariser and destroy the failure it is describing"
    (let [boom (js-obj)]
      (set! (.-toString boom) (fn [] (throw (js/Error. "boom"))))
      (is (= {:type :scalar} (diag/value-summary boom))
          "an unknown host object summarises to its bare shape tag")
      (is (= {:type :map :count 2} (diag/value-summary {boom :v :ok 1}))
          "and cannot ride out through a map's key list either"))))

;; ---- MIRROR PARITY vs re-frame.error/diag-value-summary -------------------

(def ^:private parity-corpus
  "A value corpus spanning every `value-summary` branch, including strings
  either side of 24 chars, sentinel-bearing dynamic map
  keys and a hiccup-shaped vector. Each value is fed to BOTH summariser
  twins; the summaries must be `=`."
  [nil
   {}
   {:a 1 :b {:nested "v"} :c/d 2}
   {"SENTINELSENTINELSENTINEL" 1}                          ;; dynamic string key
   {(keyword "SENTINELSENTINELSENTINEL") 1}                ;; dynamic keyword key
   [:div {:class "x"} "leaf text over twenty-four characters long here"]
   #{:x :y :z}
   ""
   "short"
   "boundary-exactly-24-chrs"                              ;; exactly 24 chars
   "a string that is definitely longer than twenty-four characters"
   :ws.app/request
   'reagent2.template/as-element
   true
   false
   0
   -12345678901234567890                                   ;; long numeric printed form
   3.14159
   '(1 2 3)
   (map inc [1 2 3])
   (fn [] :x)                                              ;; :fn arm
   #js [1 2 3]                                             ;; seqable? arm (not seq?)
   (js/Date. 0)])                                          ;; final :scalar arm

(deftest value-summary-mirrors-re-frame-error-diag-value-summary
  (testing "the slim mirror agrees with re-frame.error/diag-value-summary
            byte-for-byte over the corpus (drift guard for the hand-copy)"
    (doseq [v parity-corpus]
      (is (= (rf.error/diag-value-summary v) (diag/value-summary v))
          (str "mirror drift for value: " (pr-str v))))))
