(ns re-frame.flows-path-cljs-test
  "CLJS-host coverage for flow :output-path / overlap semantics
  (EP-0012 §The :rf/path algebra).

  The flow path validation (`valid-path?` → `re-frame.path/segment?`) and the
  output-path overlap relation (`topo/output-paths-overlap?` →
  `re-frame.path/overlap?`) are CLJC, but the existing path-semantics tests
  (`flows_test.clj`, `flows_path_overlap_test.clj`) are JVM-ONLY `.clj`. The
  one CLJS flow test (`flows_trace_emit_elision_prod_test.cljs`) is a
  prod-elision probe, NOT path semantics — so a host-sensitive divergence in
  the flow path boundary (e.g. a raw `#js {}` object or a JS function reaching
  the CLJS `segment?` discrimination) would be invisible to the CLJS suite.

  This file is `*-cljs-test.cljc` so the shadow-cljs `:node-test` build
  (ns-regexp `cljs-test$`) discovers it AND the cognitect JVM runner runs it
  (the `-test` suffix) — the path boundary is then exercised on BOTH hosts
  with host-appropriate adversarial values (a raw JS object + JS function on
  CLJS; a host Object + fn on the JVM). It mirrors the JVM accepted/rejected
  segment cases and the integrated overlap rejection."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows :as flows]
   [re-frame.flows.topo :as topo]
   [re-frame.test-support :as test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(defn- error-id [ex]
  (:rf.error/id (ex-data ex)))

(defn- reg-flow-throws
  "Register a flow and return the thrown ExceptionInfo (or nil).
  rf2-bqstzr — the 3-slot grammar: id is slot 1, :derive is the value slot,
  the remaining reflection keys are the metadata middle slot."
  [flow]
  (try
    (rf/reg-flow (:id flow) (dissoc flow :id :derive) (:derive flow))
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))

;; ===========================================================================
;; 1. pure output-path overlap relation (no runtime) — host-portable
;; ===========================================================================

(deftest output-paths-overlap-on-cljs
  (testing "the shared prefix-in-either-direction relation (Spec 013
            §Disjoint output paths) computes identically on this host"
    (is (true?  (topo/output-paths-overlap? [:x] [:x]))       "identical paths overlap")
    (is (true?  (topo/output-paths-overlap? [:x] [:x :y]))    "parent/child overlap")
    (is (true?  (topo/output-paths-overlap? [:x :y] [:x]))    "symmetric parent/child")
    (is (false? (topo/output-paths-overlap? [:x :y] [:x :z])) "siblings are disjoint")
    (is (false? (topo/output-paths-overlap? [:a] [:b]))       "unrelated are disjoint")))

;; ===========================================================================
;; 2. reg-flow accepts the SHARED concrete-segment domain (CLJS host)
;; ===========================================================================

(deftest reg-flow-accepts-shared-domain-segments-on-cljs
  (testing "every shared EP-0012 segment kind is admitted as a flow path
            segment on this host (keyword / string / int / symbol / bool /
            UUID / instant / nil) — mirrors the JVM accepted cases"
    (doseq [[label elt] [[:kw      :kw]
                         [:string  "str"]
                         [:int     42]
                         [:symbol  'sym]
                         [:bool    true]
                         [:uuid    #?(:clj  (java.util.UUID/fromString
                                              "00000000-0000-0000-0000-000000000001")
                                      :cljs (uuid "00000000-0000-0000-0000-000000000001"))]
                         [:instant #inst "2026-06-12T00:00:00.000-00:00"]
                         [:nilkey  nil]]]
      (let [flow-id (keyword "elt" (name label))]
        (is (some? (rf/reg-flow flow-id {:inputs [[:root elt]] :output-path [:out elt]} identity))
            (str "shared-domain segment " (pr-str elt) " is accepted on this host"))
        (flows/clear-flow flow-id)))))

;; ===========================================================================
;; 3. reg-flow REJECTS host / composite segments (CLJS host — the gap)
;; ===========================================================================

(deftest reg-flow-rejects-host-and-composite-segments-on-cljs
  (testing "a composite or HOST-sensitive path segment fails closed with
            :rf.error/flow-bad-path — on CLJS this is where a raw #js {} object
            and a JS function are the native host values a careless caller
            would smuggle (the divergence the JVM suite cannot see)"
    (doseq [[label bad-elt] [[:composite-vector [:nested]]
                             [:composite-map    {:k 1}]
                             [:composite-set    #{:a}]
                             [:function         (fn [_])]
                             #?(:clj  [:host-object (Object.)]
                                :cljs [:raw-js-object #js {:a 1}])]]
      (let [ex (reg-flow-throws {:id     :bad/seg
                                 :inputs [[:n]]
                                 :derive identity
                                 :output-path   [:out bad-elt]})]
        (is (some? ex)
            (str "a " (name label) " path segment must fail closed"))
        (is (= :rf.error/flow-bad-path (error-id ex))
            (str "structured :rf.error/flow-bad-path for a " (name label) " segment"))))))

;; ===========================================================================
;; 4. integrated overlap rejection (CLJS host)
;; ===========================================================================

(deftest reg-flow-rejects-overlapping-outputs-on-cljs
  (testing "two same-frame flows with overlapping OUTPUT :paths but disjoint
            inputs are rejected at registration on this host
            (:rf.error/flow-path-overlap) — the prior registration survives"
    (rf/reg-flow :a {:inputs [[:src-a]] :output-path [:dest]} identity)
    (let [ex (reg-flow-throws {:id :b :inputs [[:src-b]] :derive identity :output-path [:dest :child]})]
      (is (some? ex) "the overlapping-output registration throws")
      (is (= :rf.error/flow-path-overlap (error-id ex))
          "structured :rf.error/flow-path-overlap"))
    ;; DISJOINT sibling outputs (the common, valid case) still register cleanly.
    (is (some? (rf/reg-flow :c {:inputs [[:src-c]] :output-path [:other :x]} identity))
        "disjoint sibling outputs still register")))
