(ns re-frame.grammar-parity-test
  "Registration validation and the runtime transition engine must agree on
  the machine grammar BY CONSTRUCTION, not by a hand-kept comment that one
  mirrors the other.

  Both layers consume `re-frame.machines.grammar` (the shared
  state-tree descent + transition-value-form recogniser):

    - registration (`lifecycle-fx.validation`) resolves a transition
      `:target` against the scope `:states` to decide accept/reject; and
    - the runtime (`transition`) resolves the SAME `:target` against the
      SAME tree to drive the transition.

  These tests pin the shared recogniser and descent directly
  (`candidate-targets`, `node-at`), and registration's verdict on malformed
  target shapes and on parallel-region scope. Registration's acceptance of
  keyword sibling, vector absolute, `:same-state` and `:type :history`
  targets is pinned by `nested_validation_test`, and their runtime resolution
  by the transition suites (`machine_active_path_geometry_test` among them).

  The registration walk (via `rf/reg-machine`) runs on the JVM through the
  plain-atom substrate."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.grammar :as rf.machines.grammar]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- registration-error-id
  "Register `machine` and return the thrown `:rf.error/id` discriminator,
  or nil if registration succeeded."
  [machine-id machine]
  (try (rf/reg-machine machine-id machine) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

;; ---- shared-grammar unit agreement ---------------------------------------
;;
;; The lowest level: the shared form recogniser and descent the two
;; layers both consume. If these drift, both layers drift together (the
;; whole point), so pin the primitive directly.

(deftest candidate-targets-present-marker
  (testing "candidate-targets tags each declared :target with :present?"
    (is (= [] (rf.machines.grammar/candidate-targets nil))
        "nil value (forbidden-transition / internal) declares no target")
    (is (= [{:present? true :target :authed}]
           (rf.machines.grammar/candidate-targets :authed)))
    (is (= [{:present? true :target [:a :b]}]
           (rf.machines.grammar/candidate-targets [:a :b])))
    (is (= [{:present? true :target :x} {:present? false :target nil}]
           (rf.machines.grammar/candidate-targets [{:target :x} {:action :a}]))
        "a vector-of-maps tags each candidate; an action-only map is :target-absent")
    (is (= [{:present? false :target nil}]
           (rf.machines.grammar/candidate-targets {:action :a}))
        "a single action-only map is an internal transition (:target absent)")))

(deftest node-at-shared-descent
  (testing "node-at resolves an absolute path through a :states scope"
    (let [states {:a {:states {:b {:states {:c {}}}}}}]
      (is (= {} (rf.machines.grammar/node-at states [:a :b :c])))
      (is (nil? (rf.machines.grammar/node-at states [:a :missing])))
      (is (nil? (rf.machines.grammar/node-at states []))
          "an empty path resolves to nil (the scope root is not a node)"))))

;; ---- malformed target shapes ---------------------------------------------

(deftest malformed-target-shape-rejected-at-registration
  (testing "a non-keyword / non-vector target shape is rejected at registration"
    (let [m {:initial :idle
             :states  {:idle {:on {:go {:target 42}}}}}]
      (is (= :rf.error/machine-bad-target
             (registration-error-id :bad/shape m))
          "registration rejects the malformed :target shape (42) loudly")))

  (testing "an EMPTY vector :target is malformed-shape, not unresolved"
    ;; Spec 005 §error taxonomy (005:4250) + Spec-Schemas §TransitionTarget
    ;; require a NON-EMPTY vector path. `[]` names no node — it is a caller
    ;; typo/schema error in the same class as `{:target 42}`, NOT an
    ;; unresolved absolute path. Tools/conformance consumers branch on
    ;; `:rf.error/id`, so the taxonomy must be exact. Aligned with XState v5
    ;; (malformed targets are rejected at machine creation, not degraded to a
    ;; missing-state reference).
    (let [m {:initial :idle
             :states  {:idle {:on {:go {:target []}}}}}]
      (is (= :rf.error/machine-bad-target
             (registration-error-id :bad/empty-vec m))
          "registration rejects an empty-vector :target as malformed-shape"))
    ;; CONTRAST: a NON-empty vector that names no declared state stays
    ;; `:rf.error/machine-unresolved-target` (a real-but-missing path), so the
    ;; empty-vector branch must not over-reach into the resolution branch.
    (let [m {:initial :idle
             :states  {:idle {:on {:go {:target [:missing]}}}}}]
      (is (= :rf.error/machine-unresolved-target
             (registration-error-id :bad/missing-vec m))
          "a non-empty vector naming no state stays unresolved-target"))))

;; ---- parallel-region scope -----------------------------------------------

(deftest parallel-region-scope-parity
  (testing "a vector target inside a parallel region resolves within THAT region's scope"
    (let [m {:type    :parallel
             :regions {:r1 {:initial :a
                            :states  {:a {:on {:go [:b]}}
                                      :b {}}}
                       :r2 {:initial :x
                            :states  {:x {}}}}}]
      (is (nil? (registration-error-id :par/ok m))
          "registration accepts the within-region absolute target")))

  (testing "a parallel region target that escapes its region scope is rejected"
    (let [m {:type    :parallel
             :regions {:r1 {:initial :a
                            :states  {:a {:on {:go [:x]}}
                                      :b {}}}
                       ;; :x lives in r2, not r1 — a vector target is
                       ;; absolute FROM THE REGION ROOT, so [:x] does not
                       ;; resolve within r1's scope.
                       :r2 {:initial :x
                            :states  {:x {}}}}}]
      (is (= :rf.error/machine-unresolved-target
             (registration-error-id :par/bad m))
          "registration rejects a target that resolves only in a sibling region"))))
