(ns re-frame.sub-topology-test
  "Tests for the public `(rf/sub-topology)` static dependency-graph
  query (rf2-8nzo). Per Spec 002 §The public registrar query API and
  Spec 006 §Subscription topology vs subscription tracking.

  `sub-topology` returns a map of
    `sub-id → {:inputs [<input-sub-ids>] :doc :ns :line :file}`
  derived purely from the registrar at registration time. No app-db,
  no per-frame cache, no reactive runtime — JVM-runnable.

  These tests exercise the contract end-to-end: empty registry,
  layer-1 / layer-2 / layer-3 chains, multi-input fanout, source-coord
  capture, doc passthrough, declaration-order preservation, and a
  declared self-reference cycle (which is allowed by registration —
  the topology surface reports the static :<- chain regardless of
  whether the resulting sub would resolve at runtime)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as subs]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- empty / shape contract ----------------------------------------------

(deftest empty-registry-returns-empty-map
  (testing "(sub-topology) returns {} (not nil) when no subs are registered"
    ;; clear-all! ran in the fixture; the registrar holds zero :sub
    ;; entries. Returning {} (not nil) lets callers compose with
    ;; reduce-kv / get-in / count without nil-pun special cases.
    (registrar/clear-all!)
    (is (= {} (rf/sub-topology)))
    (is (map? (rf/sub-topology)))))

(deftest layer-1-sub-has-empty-inputs
  (testing "a layer-1 sub (reads app-db directly) reports :inputs []"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (let [topo (rf/sub-topology)]
      (is (contains? topo :n))
      (is (= [] (:inputs (topo :n)))
          ":inputs is always present and is the empty vector for layer-1 subs"))))

;; ---- :<- chain capture ---------------------------------------------------

(deftest single-input-layer-2-sub-reports-the-upstream-id
  (testing "a layer-2 sub with one :<- declares one upstream input"
    (rf/reg-sub :n  (fn [db _] (:n db)))
    (rf/reg-sub :n2 :<- [:n] (fn [n _] (* 2 n)))
    (let [topo (rf/sub-topology)]
      (is (= [] (:inputs (topo :n))))
      (is (= [:n] (:inputs (topo :n2)))))))

(deftest multi-input-layer-2-sub-preserves-declaration-order
  (testing "multi-input :<- chain order is preserved (matters for body fn arity)"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :c (fn [db _] (:c db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] :<- [:c]
                (fn [[a b c] _] (+ a b c)))
    (is (= [:a :b :c] (:inputs ((rf/sub-topology) :sum)))
        "declaration order is preserved so tools can reconstruct the body's input shape")))

(deftest deeper-chain-each-link-recorded
  (testing "layer-3 chain — every node carries its direct upstreams"
    (rf/reg-sub :raw   (fn [db _] (:n db)))
    (rf/reg-sub :step1 :<- [:raw]   (fn [r _] (inc r)))
    (rf/reg-sub :step2 :<- [:step1] (fn [s _] (* 10 s)))
    (let [topo (rf/sub-topology)]
      (is (= []       (:inputs (topo :raw))))
      (is (= [:raw]   (:inputs (topo :step1))))
      (is (= [:step1] (:inputs (topo :step2)))))))

(deftest input-sub-ids-strip-query-args
  (testing ":inputs reports sub-ids only — query-vector args are stripped"
    ;; The registration form is `:<- [:upstream arg1 arg2]`; the static
    ;; topology is keyed by sub-id, so :inputs reports just :upstream.
    ;; Per Spec 002 §The public registrar query API row.
    (rf/reg-sub :upstream (fn [db [_ _arg]] (:n db)))
    (rf/reg-sub :downstream :<- [:upstream :some-arg]
                (fn [u _] (str u)))
    (is (= [:upstream] (:inputs ((rf/sub-topology) :downstream)))
        "the per-input-vector args don't change the topology — sub-ids only")))

;; ---- :doc and source-coord passthrough -----------------------------------

(deftest source-coords-are-included
  (testing ":ns / :line / :file are auto-captured by reg-sub and surface in topology"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (let [entry ((rf/sub-topology) :n)]
      (is (some? (:ns entry))   ":ns captured at the call site")
      (is (number? (:line entry)) ":line captured at the call site")
      (is (some? (:file entry)) ":file captured at the call site"))))

(deftest user-supplied-doc-passes-through
  (testing ":doc supplied via the meta-map first arg surfaces in topology"
    (rf/reg-sub :counter
                {:doc "Counter sub — a layer-1 sub that reads :n from app-db."}
                (fn [db _] (:n db)))
    (is (= "Counter sub — a layer-1 sub that reads :n from app-db."
           (:doc ((rf/sub-topology) :counter))))))

(deftest no-doc-key-when-not-supplied
  (testing ":doc is absent when the registration didn't supply one"
    ;; Don't surface a nil :doc — match the spec row's "keys present
    ;; when the registration carries them" semantics.
    (rf/reg-sub :n (fn [db _] (:n db)))
    (is (not (contains? ((rf/sub-topology) :n) :doc)))))

;; ---- registry semantics --------------------------------------------------

(deftest unregistered-ids-absent
  (testing "subs that were never registered don't appear"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (let [topo (rf/sub-topology)]
      (is (contains? topo :a))
      (is (not (contains? topo :ghost))))))

(deftest cleared-subs-are-removed
  (testing "(rf/clear-sub id) removes the sub from the topology"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (let [topo (rf/sub-topology)]
      (is (contains? topo :a))
      (is (contains? topo :b)))
    (subs/clear-sub :a)
    (let [topo (rf/sub-topology)]
      (is (not (contains? topo :a)))
      (is (contains? topo :b)))))

(deftest reregistration-replaces-the-entry
  (testing "re-registering a sub replaces its topology entry"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (is (= [] (:inputs ((rf/sub-topology) :a))))
    ;; Re-register :a as a layer-2 sub composing :b. The topology
    ;; reports the new :<- chain (last-write-wins per Spec 001
    ;; §Hot-reload semantics).
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :a :<- [:b] (fn [b _] (str b)))
    (is (= [:b] (:inputs ((rf/sub-topology) :a))))))

;; ---- self-reference / cycle handling -------------------------------------

(deftest declared-self-reference-is-reported-verbatim
  (testing "a sub declaring :<- [:itself] is reported with that input — no cycle detection at the topology layer"
    ;; The topology surface is a literal projection of the registrar's
    ;; :<- declarations. Cycle detection is a debugger / tool concern,
    ;; not a property of the static topology query — the same way the
    ;; registrar accepts the registration without complaint. Keeping
    ;; sub-topology as a verbatim projection means tools can detect
    ;; cycles by traversing the returned graph; sub-topology itself
    ;; just reports what was registered.
    (rf/reg-sub :loop :<- [:loop] (fn [v _] v))
    (is (= [:loop] (:inputs ((rf/sub-topology) :loop)))))

  (testing "a 2-node cycle :<- declarations are similarly verbatim"
    (rf/reg-sub :a :<- [:b] (fn [b _] b))
    (rf/reg-sub :b :<- [:a] (fn [a _] a))
    (let [topo (rf/sub-topology)]
      (is (= [:b] (:inputs (topo :a))))
      (is (= [:a] (:inputs (topo :b)))))))
