(ns re-frame.machine-source-coord-cljs-test
  "CLJS coverage for per-element source-coord stamping (rf2-8bp3). Per
  Spec 005 §Source-coord stamping the `reg-machine` macro walks the
  literal spec form at expansion time and attaches a flat coord index
  under `:rf.machine/source-coords`, keyed by spec-path tuples.

  The CLJS counterpart of machine_source_coord_test.clj. Per the keyword-
  reference rule the macro stamps:
    - definition sites (each fn literal under :guards / :actions /
      :on-spawn-actions, keyed by [:guards <id>] / [:actions <id>] /
      [:on-spawn-actions <id>])
    - reference sites (each transition map / state node / inline-fn slot
      inside :states, keyed by its full spec-path tuple)

  CLJS reader-meta: the CLJS analyzer / cljs.tools.reader DOES attach
  :line / :column metadata to map and vector literals, so the full
  path-tuple surface is exercisable here (unlike on JVM where the
  standard LispReader only decorates list forms)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- per-element-coords [machine-id]
  (-> (rf/machine-meta machine-id)
      :rf.machine/source-coords))

;; ---- definition-site stamping --------------------------------------------

(deftest reg-machine-stamps-guard-and-action-definitions-cljs
  (testing "fn literals under :guards / :actions / :on-spawn-actions are
  stamped at their definition coords"
    (rf/reg-machine :rf2-8bp3/defs
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :actions {:do  (fn [_] {})}
       :on-spawn-actions {:cap (fn [{data :data id :id}] (assoc data :pending id))}
       :states  {:idle {}}})
    (let [idx (per-element-coords :rf2-8bp3/defs)]
      (is (some? (get idx [:guards :ok?])))
      (is (some? (get idx [:actions :do])))
      (is (some? (get idx [:on-spawn-actions :cap]))))))

;; ---- reference-site stamping (transition / state-node / inline-fn) --------

(deftest reg-machine-stamps-transition-references-cljs
  (testing "transition maps inside :on are stamped at their full path tuples;
  inline-fn references inside also get distinct stamps"
    (rf/reg-machine :rf2-8bp3/refs
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :states
       {:idle {:on {:submit {:target :done :guard :ok?}
                    :cancel {:target :idle :action (fn [_] {})}}}
        :done {}}})
    (let [idx (per-element-coords :rf2-8bp3/refs)]
      (is (some? (get idx [:states :idle :on :submit]))
          "transition map for :submit is stamped at its reader position")
      (is (some? (get idx [:states :idle :on :cancel]))
          "transition map for :cancel is stamped at its reader position")
      ;; Inline-fn :action gets its own slot stamp (the fn-form has reader meta).
      (is (some? (get idx [:states :idle :on :cancel :action]))
          "inline-fn :action literal is stamped at its slot")
      ;; Keyword :guard reference is NOT stamped (definition-site only rule).
      (is (nil? (get idx [:states :idle :on :submit :guard]))
          "keyword :guard reference is not stamped (definition-site rule)"))))

(deftest reg-machine-stamps-state-nodes-cljs
  (testing "state-node maps inside :states are stamped at their path tuples
  (CLJS reader attaches meta to map literals)"
    (rf/reg-machine :rf2-8bp3/nodes
      {:initial :a
       :states
       {:a {:on {:go :b}}
        :b {}}})
    (let [idx (per-element-coords :rf2-8bp3/nodes)]
      (is (some? (get idx [:states :a]))
          "state-node :a is stamped")
      (is (some? (get idx [:states :b]))
          "state-node :b is stamped"))))

(deftest reg-machine-stamps-vector-of-transitions-cljs
  (testing "vector :on transitions stamp per index"
    (rf/reg-machine :rf2-8bp3/vec
      {:initial :idle
       :guards  {:a? (fn [_] true)
                 :b? (fn [_] false)}
       :states
       {:idle
        {:on
         {:tick [{:guard :a? :target :one}
                 {:guard :b? :target :two}
                 {:target :three}]}}
        :one   {}
        :two   {}
        :three {}}})
    (let [idx (per-element-coords :rf2-8bp3/vec)]
      (is (some? (get idx [:states :idle :on :tick 0])))
      (is (some? (get idx [:states :idle :on :tick 1])))
      (is (some? (get idx [:states :idle :on :tick 2]))))))

(deftest reg-machine-stamps-always-cljs
  (testing ":always vector — each transition map stamps per index"
    (rf/reg-machine :rf2-8bp3/always
      {:initial :a
       :guards  {:enough? (fn [_] true)}
       :states
       {:a {:always [{:guard :enough? :target :b}]}
        :b {}}})
    (let [idx (per-element-coords :rf2-8bp3/always)]
      (is (some? (get idx [:states :a :always 0])))
      ;; Definition coord stamped too.
      (is (some? (get idx [:guards :enough?]))))))

(deftest reg-machine-stamps-entry-exit-cljs
  (testing ":entry / :exit slots: keyword refs aren't stamped (definition-site
  rule), but inline-fn slots ARE"
    (rf/reg-machine :rf2-8bp3/ee
      {:initial :a
       :actions {:enter-a (fn [_] {})}
       :states
       {:a {:entry :enter-a
            :exit (fn [_] {})
            :on    {:go :b}}
        :b {}}})
    (let [idx (per-element-coords :rf2-8bp3/ee)]
      ;; Inline-fn :exit IS stamped.
      (is (some? (get idx [:states :a :exit])))
      ;; Keyword :entry reference NOT stamped.
      (is (nil? (get idx [:states :a :entry])))
      ;; Definition coord ARE stamped.
      (is (some? (get idx [:actions :enter-a]))))))

(deftest reg-machine-stamps-hierarchical-states-cljs
  (testing "nested :states recurse — state-node coords stamp at the
  full leaf-prefixed path tuple"
    (rf/reg-machine :rf2-8bp3/hier
      {:initial :outer
       :states
       {:outer {:initial :inner
                :states  {:inner   {:on {:go {:target :sibling}}}
                          :sibling {}}}}})
    (let [idx (per-element-coords :rf2-8bp3/hier)]
      (is (some? (get idx [:states :outer]))
          "outer state-node stamped")
      (is (some? (get idx [:states :outer :states :inner]))
          "inner state-node stamped at the recursive path")
      (is (some? (get idx [:states :outer :states :inner :on :go]))
          "transition map inside hierarchical inner state is stamped"))))

;; ---- programmatic call (no walking) --------------------------------------

(deftest reg-machine-skips-stamping-for-non-literal-spec-cljs
  (testing "when the spec is bound to a symbol (not a literal map form), the
  macro can't walk and the registered spec carries no
  :rf.machine/source-coords key"
    (let [spec {:initial :a :states {:a {}}}]
      (rf/reg-machine :rf2-8bp3/programmatic spec))
    (is (= {:initial :a :states {:a {}}}
           (rf/machine-meta :rf2-8bp3/programmatic))
        "registered spec round-trips with no :rf.machine/source-coords")))

;; ---- reg-machine* plain-fn surface ---------------------------------------

(deftest reg-machine*-plain-fn-surface-cljs
  (testing "reg-machine* registers without macro walking — equivalent to
  the legacy reg-machine defn"
    (rf/reg-machine* :rf2-8bp3/plain
                     {:initial :a :states {:a {}}})
    (is (some? (some #{:rf2-8bp3/plain} (rf/machines)))
        "(rf/machines) lists the plain-fn registered machine")
    (is (= {:initial :a :states {:a {}}}
           (rf/machine-meta :rf2-8bp3/plain))
        "spec round-trips verbatim")))
