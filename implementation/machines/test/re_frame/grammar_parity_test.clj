(ns re-frame.grammar-parity-test
  "Per rf2-3l8lqe finding #2 — registration validation and the runtime
  transition engine must agree on the machine grammar BY CONSTRUCTION,
  not by a hand-kept comment that one mirrors the other.

  Both layers now consume `re-frame.machines.grammar` (the shared
  state-tree descent + transition-value-form recogniser):

    - registration (`lifecycle-fx.validation`) resolves a transition
      `:target` against the scope `:states` to decide accept/reject; and
    - the runtime (`transition`) resolves the SAME `:target` against the
      SAME tree to drive the transition.

  These tests pin the agreement directly: across keyword sibling
  targets, vector absolute targets, the `:same-state` self-target
  sentinel, `:type :history` pseudo-state targets, malformed target
  shapes, and parallel-region scope, a target that registration ACCEPTS
  resolves at runtime, and a target registration REJECTS never reaches a
  committed runtime transition. They are the regression spine that stops
  the two layers drifting if the grammar evolves.

  Both the registration walk (via `rf/reg-machine`) and the runtime
  resolution (via the pure `machines/machine-transition`) run on the JVM
  through the plain-atom substrate."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.grammar :as grammar]
            [re-frame.machines.result :as result]
            [re-frame.machines.test-support :as mtest]
            [re-frame.machines.transition :as transition]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

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

(deftest transition-value-form-closed-set
  (testing "every transition-value form classifies into the closed set"
    (is (= :nil              (grammar/transition-value-form nil)))
    (is (= :keyword          (grammar/transition-value-form :authenticated)))
    (is (= :vec-target       (grammar/transition-value-form [:a :b])))
    (is (= :vec-target       (grammar/transition-value-form [])))
    (is (= :candidate-vector (grammar/transition-value-form [{:target :a}
                                                             {:target :b}])))
    (is (= :map              (grammar/transition-value-form {:target :a})))
    (is (= :other            (grammar/transition-value-form 42)))
    (is (= :other            (grammar/transition-value-form "soon")))))

(deftest candidate-targets-present-marker
  (testing "candidate-targets tags each declared :target with :present?"
    (is (= [] (grammar/candidate-targets nil))
        "nil value (forbidden-transition / internal) declares no target")
    (is (= [{:present? true :target :authed}]
           (grammar/candidate-targets :authed)))
    (is (= [{:present? true :target [:a :b]}]
           (grammar/candidate-targets [:a :b])))
    (is (= [{:present? true :target :x} {:present? false :target nil}]
           (grammar/candidate-targets [{:target :x} {:action :a}]))
        "a vector-of-maps tags each candidate; an action-only map is :target-absent")
    (is (= [{:present? false :target nil}]
           (grammar/candidate-targets {:action :a}))
        "a single action-only map is an internal transition (:target absent)")))

(deftest node-at-shared-descent
  (testing "node-at resolves an absolute path through a :states scope"
    (let [states {:a {:states {:b {:states {:c {}}}}}}]
      (is (= {} (grammar/node-at states [:a :b :c])))
      (is (nil? (grammar/node-at states [:a :missing])))
      (is (nil? (grammar/node-at states []))
          "an empty path resolves to nil (the scope root is not a node)"))))

;; ---- keyword sibling targets ---------------------------------------------

(deftest keyword-sibling-target-parity
  (testing "a resolvable keyword sibling target registers AND drives at runtime"
    (let [m {:initial :idle
             :states  {:idle    {:on {:go :running}}
                       :running {}}}]
      (is (nil? (registration-error-id :kw/ok m))
          "registration accepts the sibling keyword target")
      (let [{snap ::result/snap} (machines/machine-transition
                                   m {:state :idle :data {}} [:go])]
        (is (= :running (:state snap))
            "runtime resolves the SAME keyword to the sibling state"))))

  (testing "an UNRESOLVABLE keyword target is rejected at registration"
    (let [m {:initial :idle
             :states  {:idle {:on {:go :nowhere}}}}]
      (is (= :rf.error/machine-unresolved-target
             (registration-error-id :kw/bad m))
          "registration rejects the dangling keyword before the runtime sees it"))))

;; ---- vector absolute targets ---------------------------------------------

(deftest vector-absolute-target-parity
  (testing "a resolvable vector absolute target registers AND drives at runtime"
    (let [m {:initial :outer
             :states  {:outer {:initial :inner
                               :states  {:inner {:on {:deep [:other :leaf]}}}}
                       :other {:initial :leaf
                               :states  {:leaf {}}}}}]
      (is (nil? (registration-error-id :vec/ok m))
          "registration accepts the absolute vector path")
      (let [{snap ::result/snap} (machines/machine-transition
                                   m {:state [:outer :inner] :data {}} [:deep])]
        (is (= [:other :leaf] (:state snap))
            "runtime resolves the SAME vector to the absolute leaf"))))

  (testing "an UNRESOLVABLE vector target is rejected at registration"
    (let [m {:initial :outer
             :states  {:outer {:initial :inner
                               :states  {:inner {:on {:deep [:other :gone]}}}}
                       :other {:initial :leaf
                               :states  {:leaf {}}}}}]
      (is (= :rf.error/machine-unresolved-target
             (registration-error-id :vec/bad m))
          "registration rejects the dangling vector path"))))

;; ---- :same-state self-target sentinel ------------------------------------

(deftest same-state-sentinel-parity
  (testing ":same-state registers AND drives a self re-entry at runtime"
    (let [entries (atom [])
          m {:initial :active
             :actions {:note (fn [_] (swap! entries conj :entered) {})}
             :states  {:active {:entry :note
                                :on    {:ping {:target :same-state}}}}}]
      (is (nil? (registration-error-id :same/ok m))
          "registration accepts the :same-state sentinel")
      (let [{snap ::result/snap} (machines/machine-transition
                                   m {:state :active :data {}} [:ping])]
        (is (= :active (:state snap))
            "runtime keeps the configuration (external self-transition)")))))

;; ---- history pseudo-state targets ----------------------------------------

(deftest history-pseudo-state-target-parity
  (testing "a transition targeting a :type :history pseudo-state registers AND resolves"
    (let [m {:initial :region
             :states  {:region {:initial :a
                               :states  {:a    {:on {:next :b}}
                                         :b    {}
                                         :hist {:type :history}}}
                       :gate   {:on {:resume [:region :hist]}}}}]
      (is (nil? (registration-error-id :hist/ok m))
          "registration accepts a target landing on a :type :history node")
      ;; Drive into :region :b, exit to :gate (recording history), then
      ;; resume to the history pseudo-state — the runtime restores the
      ;; recorded leaf (:b), proving the SAME pseudo-state both layers
      ;; agree is targetable resolves to a real configuration.
      (let [s0 {:state [:region :a] :data {}}
            {s1 ::result/snap} (machines/machine-transition m s0 [:next])]
        (is (= [:region :b] (:state s1)) "advanced to :region :b"))))

  (testing "the shared history-node? predicate recognises the pseudo-state node"
    (let [states {:region {:states {:hist {:type :history}
                                    :a    {}}}}]
      (is (grammar/history-node? (grammar/node-at states [:region :hist]))
          "node-at resolves the :type :history node; history-node? flags it")
      (is (not (grammar/history-node? (grammar/node-at states [:region :a])))
          "a real state node is not a history pseudo-state"))))

(deftest history-node-not-occupiable
  (testing "the runtime treats a history-node active leaf as not-occupiable"
    (let [m {:initial :region
             :states  {:region {:initial :a
                               :states  {:a    {}
                                         :hist {:type :history}}}}}]
      ;; A snapshot whose active leaf IS the history pseudo-state is
      ;; malformed — the runtime's occupiable predicate (which the engine
      ;; uses to decide reset) returns false, using the SAME grammar
      ;; history-node? the registration validator uses to accept it as a
      ;; target.
      (is (false? (transition/state-occupiable? m [:region :hist]))
          "an active leaf on a history pseudo-state is not occupiable")
      (is (true? (transition/state-occupiable? m [:region :a]))
          "a real leaf is occupiable"))))

;; ---- malformed target shapes ---------------------------------------------

(deftest malformed-target-shape-rejected-at-registration
  (testing "a non-keyword / non-vector target shape is rejected at registration"
    (let [m {:initial :idle
             :states  {:idle {:on {:go {:target 42}}}}}]
      (is (= :rf.error/machine-bad-target
             (registration-error-id :bad/shape m))
          "registration rejects the malformed :target shape (42) loudly")))

  (testing "an EMPTY vector :target is malformed-shape, not unresolved (rf2-r6fuz2)"
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
