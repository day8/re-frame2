(ns re-frame.machine-source-coord-cljs-test
  "The CLJS reader puts `:line` / `:column` on map and vector literals, so here
  `reg-machine` co-locates a `:source-coords` on each map node of the `:states`
  tree (Spec 005 §Source-coord stamping). The platform-neutral element and
  inline-fn source stamps are covered in machine_source_coord_test.clj."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; The registered machine's SPEC map, read through the generic registrar query
;; + `:rf/machine` projection (Spec 005 §Querying machines). nil unless the
;; `:event` registration carries `:rf/machine? true`.
(defn- machine-spec [machine-id]
  (:rf/machine (rf/handler-meta {:source :store :kind :event :id machine-id})))

;; Read a co-located reference-site `:source-coords` off the MAP node
;; (state-node / transition map) at `spec-path` in the registered spec.
(defn- node-coords [machine-id spec-path]
  (get-in (machine-spec machine-id) (conj (vec spec-path) :source-coords)))

;; ---- reference-site stamping (transition / state-node / inline-fn) --------

(deftest reg-machine-co-locates-vector-of-transitions-cljs
  (testing "vector :on transitions co-locate a coord per transition map index"
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
    (is (some? (node-coords :rf2-8bp3/vec [:states :idle :on :tick 0])))
    (is (some? (node-coords :rf2-8bp3/vec [:states :idle :on :tick 1])))
    (is (some? (node-coords :rf2-8bp3/vec [:states :idle :on :tick 2])))))

(deftest reg-machine-co-locates-always-cljs
  (testing ":always vector — each transition map co-locates a coord per index"
    (rf/reg-machine :rf2-8bp3/always
      {:initial :a
       :guards  {:enough? (fn [_] true)}
       :states
       {:a {:always [{:guard :enough? :target :b}]}
        :b {}}})
    (is (some? (node-coords :rf2-8bp3/always [:states :a :always 0])))))

(deftest reg-machine-co-locates-hierarchical-states-cljs
  (testing "nested :states recurse — state-node coords co-locate at the
  full leaf-prefixed path"
    (rf/reg-machine :rf2-8bp3/hier
      {:initial :outer
       :states
       {:outer {:initial :inner
                :states  {:inner   {:on {:go {:target :sibling}}}
                          :sibling {}}}}})
    (is (some? (node-coords :rf2-8bp3/hier [:states :outer]))
        "outer state-node co-locates its coord")
    (is (some? (node-coords :rf2-8bp3/hier [:states :outer :states :inner]))
        "inner state-node co-locates its coord at the recursive path")
    (is (some? (node-coords :rf2-8bp3/hier [:states :outer :states :inner :on :go]))
        "transition map inside hierarchical inner state co-locates its coord")))

;; ---- inline-fn :source-code co-location -----------------------------------

;; Read the inline-fn `:source-code` string for an inline slot off the
;; enclosing `:states`-tree map node.
(defn- inline-source [machine-id enclosing-path slot]
  (get-in (machine-spec machine-id)
          (conj (vec enclosing-path) :source-code slot)))

(deftest reg-machine-stamps-inline-action-source-code-cljs
  (testing "inline transition `:action` / state `:entry` / `:exit` / inline
  `:guard` fns carry their `:source-code` on the enclosing `:states`-tree map
  node — parity with the named-guard `:source-code` stamp. The
  inline slot value itself stays a bare fn (the runtime resolves it via fn?)."
    (rf/reg-machine :rf2-se70xj/inline
      {:initial :idle
       :guards  {:ok? (fn [_] true)}
       :states
       {:idle {:entry (fn [_] {:data {:entered? true}})
               :exit  (fn [_] {:data {:exited? true}})
               :on    {:submit {:target :done :guard (fn [{data :data}] (:ready? data))}
                       :cancel {:target :idle :action (fn [_] {:data {:cancelled? true}})}}}
        :done {}}})
    ;; Inline transition :action.
    (let [src (inline-source :rf2-se70xj/inline [:states :idle :on :cancel] :action)]
      (is (string? src) "inline :action carries :source-code")
      (is (re-find #":cancelled\?" src)))
    ;; Inline state :entry / :exit.
    (is (re-find #":entered\?" (inline-source :rf2-se70xj/inline [:states :idle] :entry)))
    (is (re-find #":exited\?"  (inline-source :rf2-se70xj/inline [:states :idle] :exit)))
    ;; Inline transition :guard.
    (is (re-find #":ready\?"   (inline-source :rf2-se70xj/inline [:states :idle :on :submit] :guard)))
    ;; Slot values stay bare fns — not wrapped into a map.
    (is (fn? (get-in (machine-spec :rf2-se70xj/inline) [:states :idle :on :cancel :action])))
    (is (fn? (get-in (machine-spec :rf2-se70xj/inline) [:states :idle :entry])))))

;; ---- >8 stampable nodes: transient promotion must not drop stamps --------

(deftest reg-machine-stamps-every-node-past-the-8th-cljs
  (testing "a machine with MORE than 8 stampable map-nodes co-locates coords on
  EVERY node, not just the first 8. The walkers accumulate into a transient; a
  transient array-map promotes to a hash-map on its 9th distinct key and returns
  a NEW object, so the accumulator is threaded through a volatile; discarding
  that return would silently drop the 9th+ stamps."
    ;; 12 state-node maps (reference-site family, walk-states-tree) AND 12 inline
    ;; :entry fns (inline-source family, walk-states-inline-source) — both exceed
    ;; the 8-entry array-map cap.
    (rf/reg-machine :rf2-src8cap/many
      {:initial :s0
       :states
       {:s0  {:entry (fn [_] {}) :on {:next :s1}}
        :s1  {:entry (fn [_] {}) :on {:next :s2}}
        :s2  {:entry (fn [_] {}) :on {:next :s3}}
        :s3  {:entry (fn [_] {}) :on {:next :s4}}
        :s4  {:entry (fn [_] {}) :on {:next :s5}}
        :s5  {:entry (fn [_] {}) :on {:next :s6}}
        :s6  {:entry (fn [_] {}) :on {:next :s7}}
        :s7  {:entry (fn [_] {}) :on {:next :s8}}
        :s8  {:entry (fn [_] {}) :on {:next :s9}}
        :s9  {:entry (fn [_] {}) :on {:next :s10}}
        :s10 {:entry (fn [_] {}) :on {:next :s11}}
        :s11 {:entry (fn [_] {}) :on {:next :s0}}}})
    (doseq [s [:s0 :s1 :s2 :s3 :s4 :s5 :s6 :s7 :s8 :s9 :s10 :s11]]
      ;; reference-site :source-coords on the state-node map.
      (is (some? (node-coords :rf2-src8cap/many [:states s]))
          (str "state-node " s " must carry co-located :source-coords"))
      ;; inline-source :source-code on the state's :entry fn.
      (is (string? (inline-source :rf2-src8cap/many [:states s] :entry))
          (str "state-node " s " :entry must carry co-located :source-code")))))
