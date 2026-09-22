(ns re-frame2-pair.runtime-reload-probe-test
  (:require [cljs.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame2-pair.runtime :as rt]))

(defn- handler-with-value [value]
  (fn [_ _] {:db {:value value}}))

(deftest reload-probe-distinguishes-replacement-closures
  ;; A config edit can re-create a handler with identical JavaScript source
  ;; and different captured data. The probe must observe the new function.
  (let [before-handler (handler-with-value :before)
        after-handler (handler-with-value :after)]
    (is (= (str before-handler) (str after-handler))
        "the regression requires identical function source")
    (is (not= (before-handler nil nil) (after-handler nil nil))
        "the replacement changes observable behavior")
    (rf/reg-event :review/reload-probe before-handler)
    (let [baseline (rt/registrar-handler-ref :event :review/reload-probe)]
      (is (number? baseline) "the probe remains wire-friendly")
      (is (= baseline (rt/registrar-handler-ref :event :review/reload-probe))
          "reading an unchanged handler must not confirm a reload")
      (rf/reg-event :review/reload-probe after-handler)
      (let [replacement (rt/registrar-handler-ref :event :review/reload-probe)]
        (is (not= baseline replacement)
            "a new closure must leave the pre-edit baseline")
        (is (= replacement (:handler-fn-hash
                            (rt/registrar-describe :event :review/reload-probe)))
            "handler-meta and tail-build's probe expose the same identity")
        (rf/reg-event :review/reload-probe after-handler)
        (is (= replacement (rt/registrar-handler-ref :event :review/reload-probe))
            "reusing the same function is not a changed-handler observation")))))

(deftest missing-handler-has-no-reload-probe
  (is (nil? (rt/registrar-handler-ref :event :review/absent-reload-probe))))
