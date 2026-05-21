(ns re-frame.machine-finality-purity-test
  "Per rf2-vf5cf §G1. Direct pure-engine (JVM) coverage for the three
  finality predicates the lifecycle handler recomputes at the macrostep
  boundary to decide whether to fire `:on-done` + auto-destroy:

    - `transition/final-state-node?` — the per-node `:final? true` flag.
    - `transition/final-on-leaf?`    — the active LEAF of a snapshot is final.
    - `finalize/all-regions-final?`  — a parallel machine is final iff
      EVERY region's active leaf is final.

  Pre-rf2-vf5cf a grep of the machines test tree returned ZERO direct
  matches for these symbols — they were exercised only TRANSITIVELY via
  integration dispatch in `final_state_cljs_test.cljc`. The riskiest of
  the three is the parallel union (`all-regions-final?`,
  finalize.cljc:69): a partial-final parallel snapshot (some regions
  final, some not) finalising prematurely would surface only as a
  downstream dispatch symptom, not a clean predicate failure. These
  tests pin the predicates at the cheapest-and-most-precise layer — pure
  fns of their arguments, no frame, no dispatch loop, no app-db.

  Per Spec 005 §Final states (rf2-gn80):
    - `:final?` is a first-class state-node key (D1), NOT stashed under
      `:meta`.
    - A parallel-region machine is `:final?` only when EVERY region's
      active leaf is `:final?` (§Parallel regions and `:final?`)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines.lifecycle-fx.finalize :as finalize]
            [re-frame.machines.transition :as transition]))

;; ---------------------------------------------------------------------------
;; final-state-node? — the per-node :final? flag (transition.cljc:914)
;; ---------------------------------------------------------------------------

(deftest final-state-node?-reads-the-first-class-flag
  (testing ":final? true on a state-node ⇒ true"
    (is (true? (transition/final-state-node? {:final? true}))
        "a node declaring :final? true is final"))

  (testing "absent / false / non-true :final? ⇒ false (strict true? check)"
    (is (false? (transition/final-state-node? {}))
        "a node with no :final? key is not final")
    (is (false? (transition/final-state-node? {:final? false}))
        ":final? false is explicitly not final")
    (is (false? (transition/final-state-node? {:on {:go :other}}))
        "an ordinary transition-bearing node is not final")
    ;; Per Spec 005 rf2-gn80 D1 the flag is a FIRST-CLASS key. A truthy-
    ;; but-not-true value (e.g. stashed under :meta, or a non-boolean) is
    ;; NOT final — the predicate is `(true? ...)`, not `(boolean ...)`.
    (is (false? (transition/final-state-node? {:meta {:final? true}}))
        ":final? under :meta is NOT the first-class key (D1) — not final")
    (is (false? (transition/final-state-node? {:final? :yes}))
        "a non-true truthy :final? value is not final (strict true? check)")
    (is (false? (transition/final-state-node? nil))
        "a nil node (path did not resolve) is not final")))

;; ---------------------------------------------------------------------------
;; final-on-leaf? — the active leaf of a snapshot is final
;; (transition.cljc:922; called from commit-or-finalize, registration.cljc:328)
;; ---------------------------------------------------------------------------

(def flat-final-machine
  "A flat machine whose :done leaf is :final?."
  {:initial :running
   :data    {}
   :states  {:running {:on {:finish :done}}
             :done    {:final? true}}})

(def compound-final-machine
  "A compound machine whose nested [:wrapper :done] leaf is :final?."
  {:initial :wrapper
   :data    {}
   :states  {:wrapper {:initial :running
                       :states  {:running {:on {:finish :done}}
                                 :done    {:final? true}}}}})

(deftest final-on-leaf?-flat-state
  (testing "a flat snapshot whose :state is the :final? leaf ⇒ true"
    (is (true? (transition/final-on-leaf? flat-final-machine :done))
        "the :done leaf is :final?")
    (is (true? (transition/final-on-leaf? flat-final-machine [:done]))
        "vector-form :state resolves to the same leaf"))

  (testing "a flat snapshot at a non-final leaf ⇒ false"
    (is (false? (transition/final-on-leaf? flat-final-machine :running))
        "the :running leaf is not :final?")))

(deftest final-on-leaf?-compound-state
  (testing "a compound snapshot whose active leaf is :final? ⇒ true"
    (is (true? (transition/final-on-leaf? compound-final-machine [:wrapper :done]))
        "the deeply-resolved [:wrapper :done] leaf is :final?"))

  (testing "a compound snapshot at a non-final leaf ⇒ false"
    (is (false? (transition/final-on-leaf? compound-final-machine [:wrapper :running]))
        "the [:wrapper :running] leaf is not :final?"))

  (testing "finality keys off the LEAF, not an ancestor"
    ;; The :wrapper compound node itself carries no :final? — finality is
    ;; a leaf property. Resolving the parent path (which final-on-leaf?
    ;; never does for a committed leaf snapshot, but pin the leaf-only
    ;; contract anyway via node-at) returns the non-final compound node.
    (is (false? (transition/final-state-node?
                  (transition/node-at compound-final-machine [:wrapper])))
        "the compound :wrapper ancestor is not itself :final?")))

;; ---------------------------------------------------------------------------
;; all-regions-final? — parallel union (finalize.cljc:69)
;;
;; THE riskiest predicate (rf2-vf5cf §G1): a partial-final parallel
;; snapshot must NOT finalise. The fn returns true ONLY when the machine
;; is :type :parallel, the snapshot :state is a region→leaf map, AND every
;; region's active leaf is :final?.
;; ---------------------------------------------------------------------------

(def parallel-machine
  "A two-region parallel machine. Region :left has a :final? :done leaf;
  region :right has a :final? :done leaf. Each region also has a
  non-final :running leaf."
  {:type    :parallel
   :data    {}
   :regions {:left  {:initial :running
                     :states  {:running {:on {:finish :done}}
                               :done    {:final? true}}}
             :right {:initial :running
                     :states  {:running {:on {:finish :done}}
                               :done    {:final? true}}}}})

(deftest all-regions-final?-every-region-final
  (testing "BOTH regions at their :final? leaf ⇒ true"
    (is (true? (finalize/all-regions-final?
                 parallel-machine
                 {:left :done :right :done}))
        "a parallel machine is final iff EVERY region's leaf is :final?")))

(deftest all-regions-final?-partial-final-is-not-final
  (testing "ONE region final, the other still running ⇒ false (the risky branch)"
    ;; This is the premature-finalisation guard: a snapshot where :left has
    ;; reached :done but :right is still :running must NOT report final.
    (is (false? (finalize/all-regions-final?
                  parallel-machine
                  {:left :done :right :running}))
        "left-final + right-running must NOT finalise (partial-final guard)")
    (is (false? (finalize/all-regions-final?
                  parallel-machine
                  {:left :running :right :done}))
        "right-final + left-running must NOT finalise (symmetric)"))

  (testing "NEITHER region final ⇒ false"
    (is (false? (finalize/all-regions-final?
                  parallel-machine
                  {:left :running :right :running}))
        "both regions running ⇒ not final")))

(deftest all-regions-final?-non-parallel-or-non-map-state
  (testing "a non-parallel machine ⇒ false regardless of state"
    (is (false? (finalize/all-regions-final?
                  flat-final-machine :done))
        "all-regions-final? short-circuits false for a non-parallel machine"))

  (testing "a parallel machine with a non-map :state ⇒ false"
    (is (false? (finalize/all-regions-final? parallel-machine :done))
        "a keyword :state is not a region→leaf map ⇒ not all-regions-final")))

(deftest all-regions-final?-vector-region-leaves
  (testing "regions whose active leaf is a NESTED (vector) path are resolved"
    ;; A region whose final leaf is nested under a compound state — the
    ;; per-region leaf resolution must walk the vector path, not just a
    ;; top-level keyword.
    (let [m {:type    :parallel
             :data    {}
             :regions {:left  {:initial :wrap
                               :states  {:wrap {:initial :running
                                                :states  {:running {:on {:finish :done}}
                                                          :done    {:final? true}}}}}
                       :right {:initial :running
                               :states  {:running {:on {:finish :done}}
                                         :done    {:final? true}}}}}]
      (is (true? (finalize/all-regions-final?
                   m {:left [:wrap :done] :right :done}))
          "a nested region leaf at [:wrap :done] resolves and is :final?")
      (is (false? (finalize/all-regions-final?
                    m {:left [:wrap :running] :right :done}))
          "a nested NON-final region leaf prevents finalisation"))))
