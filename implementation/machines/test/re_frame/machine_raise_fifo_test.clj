(ns re-frame.machine-raise-fifo-test
  "A `:raise` depth-limit abort discards the WHOLE macrostep. (FIFO drain order
  itself is pinned by `scxml-irp-test144-internal-raise-fifo`.)"
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as rf.machines]))

(deftest depth-bound-rollback-discards-intermediate-mutations-and-fx
  (testing "a :raise depth-limit abort is a failed macrostep that threads no
            snapshot and no fx"
    ;; Every intermediate raise mutates state + :data AND emits a non-raise
    ;; fx before the limit trips, so a non-atomic abort would be visible.
    (let [spec {:initial :idle
                :raise-depth-limit 3
                :states {:idle {:on {:start :a}}
                         :a {:entry (fn [{d :data}]
                                      {:data (update d :n inc)
                                       :fx   [[:raise [:to-b]] [:side-effect-a 1]]})
                             :on {:to-b :b}}
                         :b {:entry (fn [{d :data}]
                                      {:data (update d :n inc)
                                       :fx   [[:raise [:to-a]] [:side-effect-b 1]]})
                             :on {:to-a :a}}}}
          r    (rf.machines/machine-transition spec {:state :idle :data {:n 0}} [:start])]
      (is (= [:error :rf.error/machine-raise-depth-exceeded nil nil]
             ((juxt :status (comp :kind :error) :snapshot :fx) r))))))
