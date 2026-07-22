(ns re-frame.freehand.reserved-head-reject-cljs-test
  "re-frame.freehand rejects an UNRECOGNISED `:rf/*` hiccup head (rf2-01zvu, the
  client half of the rf2-j81hs SS4 ruling).

  Unlike reagent-slim — whose head classification is a RUNTIME dispatch —
  re-frame.freehand classifies heads at COMPILE time: `analyze` routes every
  keyword head to `analyze-element` → `parse-tag`, which already rejected
  EVERY namespaced keyword with `:rf.ui.compile/bad-tag`. So re-frame.freehand
  never painted a phantom; the gap here was the ADVICE. For a reserved
  head the generic message read \"tag keywords carry no namespace (write
  :suspense-boundary)\" — which tells the author to strip the namespace and
  paint the very phantom the guard exists to prevent.

  These rows pin the reserved arm's distinct message and, just as
  importantly, pin that a NON-`rf` namespaced head (`:svg/circle`,
  `:ns/div`) keeps the generic arm — the reserved carve-out must not widen
  the existing reject.

  Compile-tier `:rf.ui.compile/*` diagnostic — no Spec 009 catalogue row
  (the catalogue covers the RUNTIME `:rf.error/*` axis)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.analyze-accept-cljs-test :refer [mk-env]]))

(defn- reject
  "{:id … :msg …} for a rejected form; nil when accepted."
  [form]
  (try
    (ana/analyze (mk-env) form)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      {:id  (:rf.ui.compile/error (ex-data ex))
       :msg (ex-message ex)})))

(deftest reserved-rf-head-is-rejected-with-reserved-advice
  (testing "a misspelt reserved head is rejected"
    (let [r (reject [:rf/suspense-boundry "x"])]
      (is (= :rf.ui.compile/bad-tag (:id r))
          "stays on the existing closed-vocabulary id — no new reject id")
      (is (str/includes? (:msg r) "framework-reserved")
          "the message names the reserved scheme")))

  (testing "the reserved arm does NOT advise stripping the namespace"
    ;; The generic namespaced-keyword arm says \"write :<name>\". For a
    ;; reserved head that advice paints the phantom.
    (let [msg (:msg (reject [:rf/suspense-boundry "x"]))]
      (is (not (str/includes? msg "write :suspense-boundry"))
          "must not tell the author to strip :rf/ and paint the phantom")))

  (testing ":rf/suspense-boundary has no client meaning either"
    (is (= :rf.ui.compile/bad-tag (:id (reject [:rf/suspense-boundary "x"])))))

  (testing "a dotted rf.<area> namespace is reserved too"
    (is (= :rf.ui.compile/bad-tag (:id (reject [:rf.ssr/nope "x"]))))
    (is (str/includes? (:msg (reject [:rf.ssr/nope "x"])) "framework-reserved"))))

(deftest non-reserved-namespaced-heads-keep-the-generic-arm
  (testing "an unrelated namespaced head still rejects, with the generic advice"
    (let [r (reject [:ns/div "x"])]
      (is (= :rf.ui.compile/bad-tag (:id r))
          "unchanged — this row is pinned by analyze-reject-cljs-test too")
      (is (not (str/includes? (:msg r) "framework-reserved"))
          "the reserved carve-out must not swallow ordinary namespaced heads")
      (is (str/includes? (:msg r) "unqualified")
          "the generic arm still teaches the unqualified-keyword rule"))))

(deftest unreserved-heads-still-compile
  (testing "ordinary element heads are untouched"
    (is (nil? (reject [:div "x"])))
    (is (nil? (reject [:div.card#main "x"])))
    (is (nil? (reject [:my-element "x"])))))
