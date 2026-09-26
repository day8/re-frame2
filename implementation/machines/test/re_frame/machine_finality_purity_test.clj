(ns re-frame.machine-finality-purity-test
  "Direct pure-engine (JVM) coverage for the three finality predicates the
  lifecycle handler recomputes at the macrostep boundary to decide whether
  to fire `:on-done` + auto-destroy:

    - `rf.machines.transition/final-state-node?` — the per-node `:final? true` flag.
    - `rf.machines.transition/final-on-leaf?`    — the active LEAF of a snapshot is final.
    - `rf.machines.lifecycle-fx.finalize/all-regions-final?`  — a parallel machine is final iff
      EVERY region's active leaf is final.

  The riskiest of the three is the parallel union (`all-regions-final?`,
  defined in parallel.cljc): a partial-final parallel snapshot (some regions
  final, some not) finalising prematurely would surface only as a
  downstream dispatch symptom, not a clean predicate failure. These
  tests pin the predicates at the cheapest-and-most-precise layer — pure
  fns of their arguments, no frame, no dispatch loop, no app-db.

  Per Spec 005 §Final states:
    - `:final?` is a first-class state-node key (D1), NOT stashed under
      `:meta`.
    - A parallel-region machine is `:final?` only when EVERY region's
      active leaf is `:final?` (§Parallel regions and `:final?`)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.transition :as rf.machines.transition]))

;; ---------------------------------------------------------------------------
;; final-state-node? — the per-node :final? flag (transition.cljc)
;; ---------------------------------------------------------------------------

(deftest final-state-node?-reads-the-first-class-flag
  (testing ":final? true on a state-node ⇒ true"
    (is (true? (rf.machines.transition/final-state-node? {:final? true}))
        "a node declaring :final? true is final"))

  (testing "absent / false / non-true :final? ⇒ false (strict true? check)"
    (is (false? (rf.machines.transition/final-state-node? {}))
        "a node with no :final? key is not final")
    (is (false? (rf.machines.transition/final-state-node? {:final? false}))
        ":final? false is explicitly not final")
    (is (false? (rf.machines.transition/final-state-node? {:on {:go :other}}))
        "an ordinary transition-bearing node is not final")
    ;; Per Spec 005 D1 the flag is a FIRST-CLASS key. A truthy-
    ;; but-not-true value (e.g. stashed under :meta, or a non-boolean) is
    ;; NOT final — the predicate is `(true? ...)`, not `(boolean ...)`.
    (is (false? (rf.machines.transition/final-state-node? {:meta {:final? true}}))
        ":final? under :meta is NOT the first-class key (D1) — not final")
    (is (false? (rf.machines.transition/final-state-node? {:final? :yes}))
        "a non-true truthy :final? value is not final (strict true? check)")
    (is (false? (rf.machines.transition/final-state-node? nil))
        "a nil node (path did not resolve) is not final")))

;; ---------------------------------------------------------------------------
;; final-on-leaf? — the active leaf of a snapshot is final
;; (transition.cljc)
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
    (is (true? (rf.machines.transition/final-on-leaf? flat-final-machine :done))
        "the :done leaf is :final?")
    (is (true? (rf.machines.transition/final-on-leaf? flat-final-machine [:done]))
        "vector-form :state resolves to the same leaf"))

  (testing "a flat snapshot at a non-final leaf ⇒ false"
    (is (false? (rf.machines.transition/final-on-leaf? flat-final-machine :running))
        "the :running leaf is not :final?")))

(deftest final-on-leaf?-compound-state
  (testing "a compound snapshot whose active leaf is :final? ⇒ true"
    (is (true? (rf.machines.transition/final-on-leaf? compound-final-machine [:wrapper :done]))
        "the deeply-resolved [:wrapper :done] leaf is :final?"))

  (testing "a compound snapshot at a non-final leaf ⇒ false"
    (is (false? (rf.machines.transition/final-on-leaf? compound-final-machine [:wrapper :running]))
        "the [:wrapper :running] leaf is not :final?"))

  (testing "finality keys off the LEAF, not an ancestor"
    ;; The :wrapper compound node itself carries no :final? — finality is
    ;; a leaf property. Resolving the parent path (which final-on-leaf?
    ;; never does for a committed leaf snapshot, but pin the leaf-only
    ;; contract anyway via node-at) returns the non-final compound node.
    (is (false? (rf.machines.transition/final-state-node?
                  (rf.machines.transition/node-at compound-final-machine [:wrapper])))
        "the compound :wrapper ancestor is not itself :final?")))

;; ---------------------------------------------------------------------------
;; all-regions-final? — parallel union (parallel.cljc)
;;
;; THE riskiest predicate: a partial-final parallel
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

;; ---------------------------------------------------------------------------
;; all-regions-final? — declared-region KEY PARITY
;;
;; A partial parallel snapshot must NEVER vacuously read as all-final.
;; `all-regions-final?` requires EXACT declared-region key parity, so a map
;; missing a declared region (e.g. `{:left :done}` for a 2-region machine)
;; reads false rather than passing `every?` over the one present-and-final
;; region — which would otherwise read true and (downstream) fire root
;; :on-done / auto-destroy with a whole region absent.
;; ---------------------------------------------------------------------------

(deftest all-regions-final?-requires-every-declared-region-at-a-final-leaf
  (let [nested  {:type    :parallel
                 :data    {}
                 :regions {:left  {:initial :wrap
                                   :states  {:wrap {:initial :running
                                                    :states  {:running {:on {:finish :done}}
                                                              :done    {:final? true}}}}}
                           :right {:initial :running
                                   :states  {:running {:on {:finish :done}}
                                             :done    {:final? true}}}}}
        history {:type    :parallel
                 :data    {}
                 :regions {:left  {:initial :a
                                   :states  {:a    {:on {:go :b}}
                                             :b    {:final? true}
                                             :hist {:type :history}}}
                           :right {:initial :running
                                   :states  {:running {:on {:finish :done}}
                                             :done    {:final? true}}}}}]
    (doseq [[msg m state expected]
            [["a parallel machine is final iff EVERY region's leaf is :final?"
              parallel-machine {:left :done :right :done} true]
             ;; the premature-finalisation guard: one region final is not enough
             ["left-final + right-running must NOT finalise (partial-final guard)"
              parallel-machine {:left :done :right :running} false]
             ["right-final + left-running must NOT finalise (symmetric)"
              parallel-machine {:left :running :right :done} false]
             ["both regions running ⇒ not final"
              parallel-machine {:left :running :right :running} false]
             ["all-regions-final? short-circuits false for a non-parallel machine"
              flat-final-machine :done false]
             ["a keyword :state is not a region→leaf map ⇒ not all-regions-final"
              parallel-machine :done false]
             ;; a region's leaf may be a NESTED (vector) path
             ["a nested region leaf at [:wrap :done] resolves and is :final?"
              nested {:left [:wrap :done] :right :done} true]
             ["a nested NON-final region leaf prevents finalisation"
              nested {:left [:wrap :running] :right :done} false]
             ;; declared-region KEY PARITY: a partial map must never read as
             ;; vacuously all-final over the regions it does carry
             ["{:left :done} for a 2-region machine must NOT read all-final — :right is absent"
              parallel-machine {:left :done} false]
             ["symmetric — :left absent"
              parallel-machine {:right :done} false]
             ["an empty region map is not all-final (every declared region absent)"
              parallel-machine {} false]
             ["a stale :middle region (not declared) breaks exact key parity ⇒ not final"
              parallel-machine {:left :done :right :done :middle :done} false]
             ["a region occupying a history pseudo-state is malformed ⇒ not all-final"
              history {:left :hist :right :done} false]]]
      (is (= expected (rf.machines.lifecycle-fx.finalize/all-regions-final? m state)) msg))))

;; ---------------------------------------------------------------------------
;; parallel-state-valid? — the ONE shared snapshot-shape predicate
;; ---------------------------------------------------------------------------

(deftest parallel-state-valid?-requires-exact-key-parity-and-occupiable-leaves
  (testing "valid iff map? + EXACT declared region keys + every region occupiable"
    (is (true? (rf.machines.parallel/parallel-state-valid?
                 parallel-machine {:left :running :right :done}))
        "exactly the declared regions, each resolving to a real leaf ⇒ valid")
    (is (false? (rf.machines.parallel/parallel-state-valid?
                  parallel-machine {:left :running}))
        "missing a declared region ⇒ invalid")
    (is (false? (rf.machines.parallel/parallel-state-valid?
                  parallel-machine {:left :running :right :done :extra :running}))
        "an extra/stale region ⇒ invalid")
    (is (false? (rf.machines.parallel/parallel-state-valid?
                  parallel-machine {:left :nope :right :done}))
        "a region path that does not resolve ⇒ invalid")
    (is (false? (rf.machines.parallel/parallel-state-valid? parallel-machine :running))
        "a non-map :state ⇒ invalid")
    (is (false? (rf.machines.parallel/parallel-state-valid? flat-final-machine {:left :running :right :done}))
        "a non-parallel machine ⇒ invalid (the predicate is parallel-only)")))

;; ---------------------------------------------------------------------------
;; state-occupiable? — rejects missing states AND history pseudo-states
;; ---------------------------------------------------------------------------

(deftest state-occupiable?-rejects-history-and-missing
  (let [m {:initial :playing
           :states  {:playing {:type    :compound
                               :initial :a
                               :states  {:a    {:on {:go :b}}
                                         :b    {}
                                         :hist {:type :history}}}}}]
    (testing "a real occupiable leaf ⇒ true"
      (is (true? (rf.machines.transition/state-occupiable? m [:playing :a]))
          "[:playing :a] resolves to a real leaf")
      (is (true? (rf.machines.transition/state-occupiable? m [:playing :b]))))
    (testing "a :type :history pseudo-state is targetable but NEVER occupied ⇒ false"
      (is (false? (rf.machines.transition/state-occupiable? m [:playing :hist]))
          "an occupied history pseudo-state is malformed"))
    (testing "a path that does not resolve ⇒ false"
      (is (false? (rf.machines.transition/state-occupiable? m [:playing :gone])))
      (is (false? (rf.machines.transition/state-occupiable? m :no-such-state))))))
