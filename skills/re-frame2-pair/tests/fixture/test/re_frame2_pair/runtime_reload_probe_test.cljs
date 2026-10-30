(ns re-frame2-pair.runtime-reload-probe-test
  (:require [cljs.reader]
            [cljs.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame2-pair.runtime :as rt]))

(deftest handler-meta-holding-a-regex-reads-back-as-edn
  ;; The MCP server reads every reply back as EDN, which has no regex
  ;; literal, so one RegExp left in the meta made the whole reply
  ;; unserializable.
  (rf/reg-event :review/regex-meta
    {:schema [:cat [:= :review/regex-meta] [:map [:email [:re #"^\S+@\S+$"]]]]}
    (fn [_ _] {}))
  (let [described (rt/registrar-describe :event :review/regex-meta)]
    (is (= [:cat [:= :review/regex-meta] [:map [:email [:re "^\\S+@\\S+$"]]]]
           (:schema (cljs.reader/read-string (pr-str described))))
        "the regex reads back as its source string")))

(defn- handler-with-value [value]
  (fn [_ _] {:db {:value value}}))

(deftest reload-probe-distinguishes-replacement-closures
  ;; A config edit can re-create a handler with identical JavaScript source
  ;; and different captured data. The probe must observe the new function.
  (let [before-handler (handler-with-value :before)
        after-handler (handler-with-value :after)]
    (is (= (str before-handler) (str after-handler))
        "the regression requires identical function source")
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
