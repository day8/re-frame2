(ns re-frame.sub-topology-test
  "Tests for the `(re-frame.subs.tooling/sub-topology)` static
  dependency-graph query (rf2-8nzo; JVM-aliased as `subs/sub-topology`).
  rf2-80mmlf demoted the `rf/sub-topology` facade alias — it is a tooling
  surface, addressed through its owning `re-frame.subs.tooling` namespace.
  Per Spec 002 §The public registrar query API and
  Spec 006 §Subscription topology vs subscription tracking.

  `sub-topology` returns a map of
    `sub-id → {:input-kind <kind> :inputs <inputs> :doc :ns :line :file}`
  derived purely from the registrar at registration time. No app-db,
  no per-frame cache, no reactive runtime — JVM-runnable.

  `:input-kind` discriminates the input producer (`:db` / `:static` /
  `:parametric`); `:inputs` carries the kind-specific static edge set —
  `[]` for `:db`, the literal `:<-` input QUERY-VECTORS (args preserved)
  for `:static`, and the `:parametric` keyword sentinel for an
  `input-fn` sub (whose realized edges depend on the concrete outer
  query vector and are NOT statically enumerable — rf2-e3acps / the
  parametric-subscription-inputs EP §Tooling).

  These tests exercise the contract end-to-end: empty registry,
  layer-1 / layer-2 / layer-3 chains, multi-input fanout, source-coord
  capture, doc passthrough, declaration-order preservation, the
  parametric two-level topology (registrar reports `:parametric`,
  realized edges live in the cache), and a declared self-reference
  cycle (which is allowed by registration — the topology surface
  reports the static :<- chain regardless of whether the resulting sub
  would resolve at runtime)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as subs]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
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
    (is (= {} (subs/sub-topology)))
    (is (map? (subs/sub-topology)))))

(deftest layer-1-sub-has-empty-inputs
  (testing "a layer-1 sub (reads app-db directly) reports :input-kind :db / :inputs []"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (let [topo (subs/sub-topology)]
      (is (contains? topo :n))
      (is (= :db (:input-kind (topo :n)))
          "layer-1 / direct-app-db reader is :input-kind :db")
      (is (= [] (:inputs (topo :n)))
          ":inputs is always present and is the empty vector for layer-1 subs"))))

;; ---- :<- chain capture ---------------------------------------------------

(deftest single-input-layer-2-sub-reports-the-upstream-query-vector
  (testing "a layer-2 sub with one :<- declares one upstream input QUERY-VECTOR"
    (rf/reg-sub :n  (fn [db _] (:n db)))
    (rf/reg-sub :n2 :<- [:n] (fn [n _] (* 2 n)))
    (let [topo (subs/sub-topology)]
      (is (= :db (:input-kind (topo :n))))
      (is (= :static (:input-kind (topo :n2))))
      (is (= [] (:inputs (topo :n))))
      (is (= [[:n]] (:inputs (topo :n2)))
          ":static inputs are the literal :<- query-vectors (Spec 002 §registrar query API)"))))

(deftest multi-input-layer-2-sub-preserves-declaration-order
  (testing "multi-input :<- chain order is preserved (matters for body fn arity)"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :c (fn [db _] (:c db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] :<- [:c]
                (fn [[a b c] _] (+ a b c)))
    (let [entry ((subs/sub-topology) :sum)]
      (is (= :static (:input-kind entry)))
      (is (= [[:a] [:b] [:c]] (:inputs entry))
          "declaration order is preserved so tools can reconstruct the body's input shape"))))

(deftest deeper-chain-each-link-recorded
  (testing "layer-3 chain — every node carries its direct upstream query-vectors"
    (rf/reg-sub :raw   (fn [db _] (:n db)))
    (rf/reg-sub :step1 :<- [:raw]   (fn [r _] (inc r)))
    (rf/reg-sub :step2 :<- [:step1] (fn [s _] (* 10 s)))
    (let [topo (subs/sub-topology)]
      (is (= []        (:inputs (topo :raw))))
      (is (= [[:raw]]   (:inputs (topo :step1))))
      (is (= [[:step1]] (:inputs (topo :step2)))))))

(deftest static-inputs-preserve-query-vector-args
  (testing ":static :inputs preserve per-input query-vector args (full query-vectors)"
    ;; The registration form is `:<- [:upstream :some-arg]`; the static
    ;; topology now reports the literal query-vector (args preserved),
    ;; per Spec 002 §The public registrar query API row + Spec 006
    ;; §Subscription topology (the rf2-e3acps reconciliation). Tools that
    ;; want the bare sub-id project with `(mapv first ...)`.
    (rf/reg-sub :upstream (fn [db [_ _arg]] (:n db)))
    (rf/reg-sub :downstream :<- [:upstream :some-arg]
                (fn [u _] (str u)))
    (is (= [[:upstream :some-arg]] (:inputs ((subs/sub-topology) :downstream)))
        "static inputs carry the full :<- query-vector, args and all")))

;; ---- parametric input-fn topology (rf2-e3acps) ---------------------------

(deftest parametric-sub-reports-parametric-sentinel
  (testing "a parametric input-fn sub reports :input-kind :parametric / :inputs :parametric"
    ;; The realized edge set depends on the concrete outer query vector
    ;; and is NOT statically enumerable — the static surface reports the
    ;; `:parametric` sentinel rather than fabricating un-materialized
    ;; edges (EP §Tooling two-level contract; realized edges live in the
    ;; runtime cache via sub-cache).
    (rf/reg-sub :article/by-id        (fn [db [_ id]] (get-in db [:articles id])))
    (rf/reg-sub :comments/for-article (fn [db [_ id]] (get-in db [:comments id])))
    (rf/reg-sub :viewer/current       (fn [db _] (:viewer db)))
    (rf/reg-sub :article/page
                (fn [[_ id]]
                  [[:article/by-id id]
                   [:comments/for-article id]
                   [:viewer/current]])
                (fn [[article comments viewer] [_ id]]
                  {:id id :article article :comments comments :viewer viewer}))
    (let [entry ((subs/sub-topology) :article/page)]
      (is (= :parametric (:input-kind entry))
          "parametric subs are discriminated by :input-kind")
      (is (= :parametric (:inputs entry))
          ":inputs is the :parametric sentinel — realized edges are per-query-v cache state")
      (is (not (vector? (:inputs entry)))
          "the static surface must NOT pretend the parametric edge set is a static vector"))))

(deftest single-input-parametric-sub-still-parametric
  (testing "a single-input parametric sub still reports the :parametric sentinel"
    (rf/reg-sub :item/by-id (fn [db [_ id]] (get-in db [:items id])))
    (rf/reg-sub :item/title
                (fn [[_ id]] [[:item/by-id id]])
                (fn [[item] _] (:title item)))
    (let [entry ((subs/sub-topology) :item/title)]
      (is (= :parametric (:input-kind entry)))
      (is (= :parametric (:inputs entry))))))

;; ---- :doc and source-coord passthrough -----------------------------------

(deftest source-coords-are-included
  (testing ":ns / :line / :file are auto-captured by reg-sub and surface in topology"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (let [entry ((subs/sub-topology) :n)]
      (is (some? (:ns entry))   ":ns captured at the call site")
      (is (number? (:line entry)) ":line captured at the call site")
      (is (some? (:file entry)) ":file captured at the call site"))))

(deftest user-supplied-doc-passes-through
  (testing ":doc supplied via the meta-map first arg surfaces in topology"
    (rf/reg-sub :counter
                {:doc "Counter sub — a layer-1 sub that reads :n from app-db."}
                (fn [db _] (:n db)))
    (is (= "Counter sub — a layer-1 sub that reads :n from app-db."
           (:doc ((subs/sub-topology) :counter))))))

(deftest no-doc-key-when-not-supplied
  (testing ":doc is absent when the registration didn't supply one"
    ;; Don't surface a nil :doc — match the spec row's "keys present
    ;; when the registration carries them" semantics.
    (rf/reg-sub :n (fn [db _] (:n db)))
    (is (not (contains? ((subs/sub-topology) :n) :doc)))))

;; ---- registry semantics --------------------------------------------------

(deftest unregistered-ids-absent
  (testing "subs that were never registered don't appear"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (let [topo (subs/sub-topology)]
      (is (contains? topo :a))
      (is (not (contains? topo :ghost))))))

(deftest cleared-subs-are-removed
  (testing "(rf/clear-sub id) removes the sub from the topology"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (let [topo (subs/sub-topology)]
      (is (contains? topo :a))
      (is (contains? topo :b)))
    (subs/clear-sub :a)
    (let [topo (subs/sub-topology)]
      (is (not (contains? topo :a)))
      (is (contains? topo :b)))))

(deftest reregistration-replaces-the-entry
  (testing "re-registering a sub replaces its topology entry"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (is (= :db (:input-kind ((subs/sub-topology) :a))))
    (is (= [] (:inputs ((subs/sub-topology) :a))))
    ;; Re-register :a as a layer-2 sub composing :b. The topology
    ;; reports the new :<- chain (last-write-wins per Spec 001
    ;; §Hot-reload semantics).
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :a :<- [:b] (fn [b _] (str b)))
    (is (= :static (:input-kind ((subs/sub-topology) :a))))
    (is (= [[:b]] (:inputs ((subs/sub-topology) :a)))))

  (testing "re-registering a :static sub as :parametric flips :input-kind + :inputs"
    (rf/reg-sub :x (fn [db _] (:x db)))
    (rf/reg-sub :p :<- [:x] (fn [x _] x))
    (is (= :static (:input-kind ((subs/sub-topology) :p))))
    (is (= [[:x]] (:inputs ((subs/sub-topology) :p))))
    (rf/reg-sub :p
                (fn [[_ id]] [[:x id]])
                (fn [[x] _] x))
    (is (= :parametric (:input-kind ((subs/sub-topology) :p))))
    (is (= :parametric (:inputs ((subs/sub-topology) :p))))))

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
    (is (= [[:loop]] (:inputs ((subs/sub-topology) :loop)))))

  (testing "a 2-node cycle :<- declarations are similarly verbatim"
    (rf/reg-sub :a :<- [:b] (fn [b _] b))
    (rf/reg-sub :b :<- [:a] (fn [a _] a))
    (let [topo (subs/sub-topology)]
      (is (= [[:b]] (:inputs (topo :a))))
      (is (= [[:a]] (:inputs (topo :b)))))))
