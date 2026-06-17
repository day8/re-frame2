(ns re-frame.nested-validation-test
  "Per rf2-oz9t — verify-the-gap-first probe for the machine
  registration-time validator's coverage of :guard / :action keyword
  references in NESTED states and in non-`:on` slots
  (`:always`, `:entry`, `:exit`).

  Background: in `re-frame.machines`, `make-machine-handler`
  validates keyword `:guard` / `:action` references via a manual
  top-level `doseq` over `(:states machine)`, walking only `:on`
  transitions. The sibling helper `walk-state-nodes` (used just above
  for other registration-time validation) walks recursively but is NOT
  reused for guard/action validation. Spec 005 implies the
  registration-time validator should cover the full state tree and
  every transition-bearing slot, not just top-level `:on`.

  These tests pin the observable behaviour. If a misuse passes
  registration silently and only manifests at runtime, the gap is real
  and the validator needs to drive off the recursive walker. If the
  registration throws clearly, the narrow pass is structurally
  sufficient and a code comment should explain why.

  Each test registers a machine that points at an unregistered
  keyword from a single misuse site, isolated so the failure mode is
  unambiguous."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- registration-throws?
  "Try registering `machine` under `machine-id`. Returns the
  ExceptionInfo if registration threw, else nil. Quarantines the
  thrown error so individual tests can assert on its shape."
  [machine-id machine]
  (try (rf/reg-machine machine-id machine) nil
       (catch clojure.lang.ExceptionInfo e e)))

;; ---- baseline: TOP-level :on misuse IS caught ----------------------------

(deftest top-level-on-guard-keyword-unresolved
  (testing "Top-level :on transition with unregistered :guard keyword fails registration (baseline)"
    (let [m {:initial :idle
             :guards  {}
             :actions {}
             :states  {:idle {:on {:go [{:target :other
                                         :guard  :no-such-guard}]}}
                       :other {}}}
          thrown (registration-throws? :rf.nested-validation/top-on-guard m)]
      (is (some? thrown) "top-level :on :guard misuse SHOULD throw at registration")
      (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data thrown)))
          "error category names the unresolved-guard contract")
      (is (= :no-such-guard (:guard (ex-data thrown)))
          "ex-data carries the offending guard keyword"))))

(deftest top-level-on-action-keyword-unresolved
  (testing "Top-level :on transition with unregistered :action keyword fails registration (baseline)"
    (let [m {:initial :idle
             :guards  {}
             :actions {}
             :states  {:idle {:on {:go [{:target :other
                                         :action :no-such-action}]}}
                       :other {}}}
          thrown (registration-throws? :rf.nested-validation/top-on-action m)]
      (is (some? thrown) "top-level :on :action misuse SHOULD throw at registration")
      (is (= :rf.error/machine-unresolved-action (:rf.error/id (ex-data thrown)))
          "error category names the unresolved-action contract")
      (is (= :no-such-action (:action (ex-data thrown)))
          "ex-data carries the offending action keyword"))))

;; ---- gap probe: NESTED-state :on -----------------------------------------

(deftest nested-on-guard-keyword-unresolved
  (testing "Nested-state :on transition with unregistered :guard keyword fails registration"
    (let [m {:initial :outer
             :guards  {}
             :actions {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:on {:go [{:target :other
                                                            :guard  :no-such-guard}]}}
                                         :other {}}}}}
          thrown (registration-throws? :rf.nested-validation/nested-on-guard m)]
      (is (some? thrown)
          "nested-state :on :guard misuse SHOULD throw at registration"))))

(deftest nested-on-action-keyword-unresolved
  (testing "Nested-state :on transition with unregistered :action keyword fails registration"
    (let [m {:initial :outer
             :guards  {}
             :actions {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:on {:go [{:target :other
                                                            :action :no-such-action}]}}
                                         :other {}}}}}
          thrown (registration-throws? :rf.nested-validation/nested-on-action m)]
      (is (some? thrown)
          "nested-state :on :action misuse SHOULD throw at registration"))))

;; ---- gap probe: :always slot (top-level + nested) -----------------------

(deftest top-level-always-guard-keyword-unresolved
  (testing "Top-level :always with unregistered :guard keyword fails registration"
    (let [m {:initial :idle
             :guards  {}
             :actions {}
             :states  {:idle {:always [{:target :other
                                        :guard  :no-such-guard}]}
                       :other {}}}
          thrown (registration-throws? :rf.nested-validation/top-always-guard m)]
      (is (some? thrown)
          "top-level :always :guard misuse SHOULD throw at registration"))))

(deftest top-level-always-action-keyword-unresolved
  (testing "Top-level :always with unregistered :action keyword fails registration"
    (let [m {:initial :idle
             :guards  {}
             :actions {}
             :states  {:idle {:always [{:target :other
                                        :action :no-such-action}]}
                       :other {}}}
          thrown (registration-throws? :rf.nested-validation/top-always-action m)]
      (is (some? thrown)
          "top-level :always :action misuse SHOULD throw at registration"))))

(deftest nested-always-guard-keyword-unresolved
  (testing "Nested-state :always with unregistered :guard keyword fails registration"
    (let [m {:initial :outer
             :guards  {}
             :actions {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:always [{:target :other
                                                           :guard  :no-such-guard}]}
                                         :other {}}}}}
          thrown (registration-throws? :rf.nested-validation/nested-always-guard m)]
      (is (some? thrown)
          "nested :always :guard misuse SHOULD throw at registration"))))

(deftest nested-always-action-keyword-unresolved
  (testing "Nested-state :always with unregistered :action keyword fails registration"
    (let [m {:initial :outer
             :guards  {}
             :actions {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:always [{:target :other
                                                           :action :no-such-action}]}
                                         :other {}}}}}
          thrown (registration-throws? :rf.nested-validation/nested-always-action m)]
      (is (some? thrown)
          "nested :always :action misuse SHOULD throw at registration"))))

;; ---- regression (rf2-zg579): single-map :always must be normalised ------
;; The grammar admits `:always` as a single entry map OR a vector of entry
;; maps. The guard/action ref-validation loop used to iterate `(:always
;; state-node)` directly; for a single-map `:always` that yields the map's
;; MapEntries, so `(:guard t)`/`(:action t)` no-op'd and a dangling ref
;; slipped past fail-fast registration (surfacing only late, at runtime).
;; The fix routes the loop through the in-file `always-entries` normaliser.
;; The vector form (above) was already covered; these pin the single-map
;; form so both shapes are guarded.

(deftest top-level-always-single-map-guard-keyword-unresolved
  (testing "Top-level single-map :always with unregistered :guard keyword fails registration"
    (let [m {:initial :idle
             :guards  {}
             :actions {}
             :states  {:idle {:always {:target :other
                                       :guard  :no-such-guard}}
                       :other {}}}
          thrown (registration-throws? :rf.nested-validation/top-always-singlemap-guard m)]
      (is (some? thrown)
          "single-map :always :guard misuse SHOULD throw at registration")
      (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data thrown)))
          "error category names the unresolved-guard contract")
      (is (= :no-such-guard (:guard (ex-data thrown)))
          "ex-data carries the offending guard keyword"))))

(deftest top-level-always-single-map-action-keyword-unresolved
  (testing "Top-level single-map :always with unregistered :action keyword fails registration"
    (let [m {:initial :idle
             :guards  {}
             :actions {}
             :states  {:idle {:always {:target :other
                                       :action :no-such-action}}
                       :other {}}}
          thrown (registration-throws? :rf.nested-validation/top-always-singlemap-action m)]
      (is (some? thrown)
          "single-map :always :action misuse SHOULD throw at registration")
      (is (= :rf.error/machine-unresolved-action (:rf.error/id (ex-data thrown)))
          "error category names the unresolved-action contract")
      (is (= :no-such-action (:action (ex-data thrown)))
          "ex-data carries the offending action keyword"))))

(deftest nested-always-single-map-guard-keyword-unresolved
  (testing "Nested-state single-map :always with unregistered :guard keyword fails registration"
    (let [m {:initial :outer
             :guards  {}
             :actions {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:always {:target :other
                                                          :guard  :no-such-guard}}
                                         :other {}}}}}
          thrown (registration-throws? :rf.nested-validation/nested-always-singlemap-guard m)]
      (is (some? thrown)
          "nested single-map :always :guard misuse SHOULD throw at registration")
      (is (= :no-such-guard (:guard (ex-data thrown)))
          "ex-data carries the offending guard keyword"))))

(deftest nested-always-single-map-action-keyword-unresolved
  (testing "Nested-state single-map :always with unregistered :action keyword fails registration"
    (let [m {:initial :outer
             :guards  {}
             :actions {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:always {:target :other
                                                          :action :no-such-action}}
                                         :other {}}}}}
          thrown (registration-throws? :rf.nested-validation/nested-always-singlemap-action m)]
      (is (some? thrown)
          "nested single-map :always :action misuse SHOULD throw at registration")
      (is (= :no-such-action (:action (ex-data thrown)))
          "ex-data carries the offending action keyword"))))

;; ---- gap probe: :entry / :exit action references -------------------------

(deftest top-level-entry-action-keyword-unresolved
  (testing "Top-level :entry referencing an unregistered action keyword fails registration"
    (let [m {:initial :idle
             :guards  {}
             :actions {}
             :states  {:idle {:entry :no-such-action}}}
          thrown (registration-throws? :rf.nested-validation/top-entry-action m)]
      (is (some? thrown)
          "top-level :entry action misuse SHOULD throw at registration"))))

(deftest top-level-exit-action-keyword-unresolved
  (testing "Top-level :exit referencing an unregistered action keyword fails registration"
    (let [m {:initial :idle
             :guards  {}
             :actions {}
             :states  {:idle {:exit :no-such-action
                              :on   {:go :other}}
                       :other {}}}
          thrown (registration-throws? :rf.nested-validation/top-exit-action m)]
      (is (some? thrown)
          "top-level :exit action misuse SHOULD throw at registration"))))

(deftest nested-entry-action-keyword-unresolved
  (testing "Nested-state :entry referencing an unregistered action keyword fails registration"
    (let [m {:initial :outer
             :guards  {}
             :actions {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:entry :no-such-action}}}}}
          thrown (registration-throws? :rf.nested-validation/nested-entry-action m)]
      (is (some? thrown)
          "nested-state :entry action misuse SHOULD throw at registration"))))

(deftest nested-exit-action-keyword-unresolved
  (testing "Nested-state :exit referencing an unregistered action keyword fails registration"
    (let [m {:initial :outer
             :guards  {}
             :actions {}
             :states  {:outer {:initial :inner
                               :states  {:inner {:exit :no-such-action
                                                 :on   {:go :sibling}}
                                         :sibling {}}}}}
          thrown (registration-throws? :rf.nested-validation/nested-exit-action m)]
      (is (some? thrown)
          "nested-state :exit action misuse SHOULD throw at registration"))))

;; ---- gap probe: parallel-region keyword refs (rf2-rp0y / PR #307 gap) ----
;;
;; Per Spec 005 §Parallel regions and machines.cljc:1903-1990:
;; `walk-state-nodes` iterates parallel regions via the `(parallel?
;; machine)` branch, so the registration-time validator at lines
;; 1977-1990 SHOULD catch keyword-ref typos inside any region.
;; nested_validation_test covers flat + compound only; rf2-rp0y adds
;; the parallel-region coverage.

(deftest parallel-region-on-guard-keyword-unresolved
  (testing "Parallel region :on with unregistered :guard keyword fails registration"
    (let [m {:type    :parallel
             :guards  {}
             :actions {}
             :regions
             {:region-a {:initial :a
                         :states  {:a {:on {:go [{:target :b
                                                  :guard  :no-such-guard}]}}
                                   :b {}}}
              :region-b {:initial :x
                         :states  {:x {} :y {}}}}}
          thrown (registration-throws? :rf.nested-validation/par-on-guard m)]
      (is (some? thrown)
          "parallel-region :on :guard misuse SHOULD throw at registration")
      (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data thrown)))
          "error category names the unresolved-guard contract"))))

(deftest parallel-region-on-action-keyword-unresolved
  (testing "Parallel region :on with unregistered :action keyword fails registration"
    (let [m {:type    :parallel
             :guards  {}
             :actions {}
             :regions
             {:region-a {:initial :a
                         :states  {:a {:on {:go [{:target :b
                                                  :action :no-such-action}]}}
                                   :b {}}}
              :region-b {:initial :x
                         :states  {:x {}}}}}
          thrown (registration-throws? :rf.nested-validation/par-on-action m)]
      (is (some? thrown)
          "parallel-region :on :action misuse SHOULD throw at registration")
      (is (= :rf.error/machine-unresolved-action (:rf.error/id (ex-data thrown)))))))

(deftest parallel-region-entry-action-keyword-unresolved
  (testing "Parallel region :entry referencing an unregistered action fails registration"
    (let [m {:type    :parallel
             :guards  {}
             :actions {}
             :regions
             {:region-a {:initial :a
                         :states  {:a {:entry :no-such-action}}}
              :region-b {:initial :x
                         :states  {:x {}}}}}
          thrown (registration-throws? :rf.nested-validation/par-entry m)]
      (is (some? thrown)
          "region root :entry action misuse SHOULD throw at registration"))))

(deftest parallel-region-exit-action-keyword-unresolved
  (testing "Parallel region :exit referencing an unregistered action fails registration"
    (let [m {:type    :parallel
             :guards  {}
             :actions {}
             :regions
             {:region-a {:initial :a
                         :states  {:a {:exit :no-such-action
                                       :on   {:go :b}}
                                   :b {}}}
              :region-b {:initial :x
                         :states  {:x {}}}}}
          thrown (registration-throws? :rf.nested-validation/par-exit m)]
      (is (some? thrown)
          "region state :exit action misuse SHOULD throw at registration"))))

(deftest parallel-region-deeply-nested-action-unresolved
  (testing "Parallel region with a DEEPLY-NESTED state referencing an
            unregistered action keyword fails registration"
    ;; The bead's case (2): inside a region, descend through a compound
    ;; state's :states to a nested leaf. The validator must walk down
    ;; into the region's compound states, not stop at the region root.
    (let [m {:type    :parallel
             :guards  {}
             :actions {}
             :regions
             {:region-a
              {:initial :outer
               :states
               {:outer {:initial :inner
                        :states  {:inner {:on {:go [{:target :sibling
                                                     :action :no-such-action}]}}
                                  :sibling {}}}}}
              :region-b
              {:initial :x
               :states  {:x {}}}}}
          thrown (registration-throws? :rf.nested-validation/par-deep m)]
      (is (some? thrown)
          "deeply-nested action misuse in a region SHOULD throw at registration"))))

(deftest parallel-region-always-guard-unresolved
  (testing "Parallel region :always with unregistered :guard keyword fails registration"
    (let [m {:type    :parallel
             :guards  {}
             :actions {}
             :regions
             {:region-a
              {:initial :a
               :states
               {:a {:always [{:target :b :guard :no-such-guard}]}
                :b {}}}
              :region-b
              {:initial :x
               :states  {:x {}}}}}
          thrown (registration-throws? :rf.nested-validation/par-always m)]
      (is (some? thrown)
          ":always misuse inside a region SHOULD throw at registration"))))

;; ---- :always self-loop forbidden at registration (rf2-hh1pi) -------------
;;
;; Per Spec 005 §Self-loop forbidden at registration (005:1290-1301): a
;; state whose `:always` targets itself is rejected at construction time
;; via `:rf.error/machine-always-self-loop`. Before rf2-hh1pi this was
;; only caught LATE at runtime via the depth-exceeded backstop.

(deftest always-self-loop-keyword-target-rejected
  (testing "an :always entry whose keyword :target names its own state is rejected"
    (let [m {:initial :checking
             :guards  {:ready? (fn [_] true)}
             :states  {:checking {:always [{:guard :ready? :target :checking}]}}}
          thrown (registration-throws? :rf.always-self-loop/kw m)]
      (is (some? thrown) "same-state :always self-loop SHOULD throw at registration")
      (is (= :rf.error/machine-always-self-loop (:rf.error/id (ex-data thrown)))
          "error category names the self-loop contract")
      (is (= :checking (:state (ex-data thrown)))
          "ex-data carries the declaring state-keyword"))))

(deftest always-self-loop-no-guard-rejected
  (testing "an :always entry that self-targets with no guard is rejected"
    (let [m {:initial :spin
             :states  {:spin {:always [{:target :spin}]}}}
          thrown (registration-throws? :rf.always-self-loop/no-guard m)]
      (is (some? thrown) "guard-less self-target SHOULD throw")
      (is (= :rf.error/machine-always-self-loop (:rf.error/id (ex-data thrown)))))))

(deftest always-internal-no-target-permitted
  (testing "an internal :always (no :target, just :action) is the canonical
            action-microstep pattern and is NOT a self-loop (control)"
    ;; Per Spec 005 §What :always is: `{:guard :more? :action :step}`
    ;; runs the action and settles when the guard flips false. This is
    ;; the flush-queue / counter pattern — registration must accept it.
    (let [m {:initial :working
             :guards  {:more? (fn [_] false)}
             :actions {:step  (fn [{d :data}] {:data d})}
             :states  {:working {:always [{:guard :more? :action :step}]}}}
          thrown (registration-throws? :rf.always-self-loop/internal m)]
      (is (nil? thrown)
          "an internal action-only :always must NOT be rejected at registration"))))

(deftest always-targetless-fixed-point-demo-registers
  (testing "the CANONICAL fixed-point / re-evaluate-until-condition machine —
            a targetless guarded :always with an :action — registers via
            reg-machine without throwing (acdlp ruling B)."
    ;; This is the exact machine the SCXML conformance corpus models in
    ;; `scxml-eventless-settles-to-fixed-point` (the :more?/:bump counter
    ;; that settles :n 0→3). That corpus drives the PURE engine via `step`;
    ;; this case proves the same shape is also registration-legal via the
    ;; full `reg-machine` validator (per the acdlp acceptance: the demo must
    ;; register, not only step). A self-:target form of this counter would
    ;; throw :rf.error/machine-always-self-loop — see the rejection tests
    ;; above — so the targetless form is the only registration-legal one.
    (let [m {:initial :a
             :data    {:n 0}
             :guards  {:more? (fn [{d :data}] (< (:n d) 3))}
             :actions {:bump  (fn [{d :data}] {:data (update d :n inc)})}
             :states  {:a {:always [{:guard :more? :action :bump}]}}}
          thrown (registration-throws? :rf.always-self-loop/fixed-point m)]
      (is (nil? thrown)
          "the canonical targetless guarded :always counter must register cleanly"))))

(deftest always-self-loop-vector-target-rejected
  (testing "an :always entry whose vector :target is its own absolute path is rejected"
    (let [m {:initial :outer
             :guards  {:p? (fn [_] true)}
             :states  {:outer {:initial :inner
                               :states  {:inner {:always [{:guard  :p?
                                                           :target [:outer :inner]}]}}}}}
          thrown (registration-throws? :rf.always-self-loop/vec m)]
      (is (some? thrown) "vector self-target SHOULD throw")
      (is (= :rf.error/machine-always-self-loop (:rf.error/id (ex-data thrown))))
      (is (= :inner (:state (ex-data thrown)))
          "ex-data names the declaring leaf state"))))

(deftest always-self-loop-single-map-form-rejected
  (testing "an :always declared as a single map (not a vector) is still walked"
    (let [m {:initial :checking
             :guards  {:ready? (fn [_] true)}
             :states  {:checking {:always {:guard :ready? :target :checking}}}}
          thrown (registration-throws? :rf.always-self-loop/single m)]
      (is (some? thrown) "single-map :always self-loop SHOULD throw")
      (is (= :rf.error/machine-always-self-loop (:rf.error/id (ex-data thrown)))))))

(deftest always-sibling-target-permitted
  (testing "an :always targeting a DIFFERENT sibling registers cleanly (control)"
    (let [m {:initial :asking
             :guards  {:enough? (fn [_] true)}
             :states  {:asking {:always [{:guard :enough? :target :winner}]}
                       :winner {}}}
          thrown (registration-throws? :rf.always-self-loop/sibling-ok m)]
      (is (nil? thrown)
          "a non-self :always target is legitimate and must not be rejected"))))

(deftest always-self-loop-in-region-rejected
  (testing "an :always self-loop inside a parallel region is rejected"
    (let [m {:type    :parallel
             :guards  {:p? (fn [_] true)}
             :actions {}
             :regions
             {:region-a {:initial :a
                         :states  {:a {:always [{:guard :p? :target :a}]}}}
              :region-b {:initial :x
                         :states  {:x {}}}}}
          thrown (registration-throws? :rf.always-self-loop/region m)]
      (is (some? thrown) "region :always self-loop SHOULD throw at registration")
      (is (= :rf.error/machine-always-self-loop (:rf.error/id (ex-data thrown)))))))

;; ---- compound state missing :initial rejected (rf2-boryv) ----------------
;;
;; Per Spec 005 §Initial-state cascading (005:930): every compound
;; state-node MUST declare `:initial`. Without it the cascade has no
;; entry-point and would silently yield a non-leaf `:state` snapshot
;; instead of failing registration.

(deftest compound-state-missing-initial-rejected
  (testing "a top-level compound state without :initial is rejected"
    (let [m {:initial :authenticated
             :states  {:authenticated
                       {:states {:dashboard {}
                                 :settings  {}}}}}     ;; no :initial — rejected
          thrown (registration-throws? :rf.missing-initial/top m)]
      (is (some? thrown) "compound state without :initial SHOULD throw")
      (is (= :rf.error/machine-compound-state-missing-initial (:rf.error/id (ex-data thrown)))
          "error category names the missing-initial contract")
      (is (= :authenticated (:state (ex-data thrown)))
          "ex-data carries the compound state-keyword"))))

(deftest nested-compound-state-missing-initial-rejected
  (testing "a deeply-nested compound state without :initial is rejected"
    (let [m {:initial :outer
             :states  {:outer {:initial :mid
                               :states  {:mid {:states {:leaf {}}}}}}}  ;; :mid compound, no :initial
          thrown (registration-throws? :rf.missing-initial/nested m)]
      (is (some? thrown) "nested compound state without :initial SHOULD throw")
      (is (= :rf.error/machine-compound-state-missing-initial (:rf.error/id (ex-data thrown))))
      (is (= :mid (:state (ex-data thrown)))
          "ex-data names the offending nested compound state"))))

(deftest compound-state-with-initial-registers
  (testing "a compound state that DOES declare :initial registers cleanly (control)"
    (let [m {:initial :authenticated
             :states  {:authenticated
                       {:initial :dashboard
                        :states  {:dashboard {}
                                  :settings  {}}}}}
          thrown (registration-throws? :rf.missing-initial/ok m)]
      (is (nil? thrown)
          "a compound state with :initial is well-formed and must not be rejected"))))

(deftest leaf-state-without-initial-registers
  (testing "a leaf state (no :states) does NOT require :initial (control)"
    (let [m {:initial :idle
             :states  {:idle {:on {:go :done}}
                       :done {}}}
          thrown (registration-throws? :rf.missing-initial/leaf-ok m)]
      (is (nil? thrown)
          "a leaf state must not be required to declare :initial"))))

(deftest parallel-region-good-shape-registers
  (testing "Sanity: a parallel machine whose region-internal keyword refs ARE
            resolvable registers cleanly (control case for the negative tests)"
    (let [m {:type    :parallel
             :guards  {:always-true (fn [_] true)}
             :actions {:noop-action (fn [{data :data}] {:data data})}
             :regions
             {:region-a
              {:initial :a
               :states
               {:a {:entry :noop-action
                    :on    {:go [{:target :b :guard :always-true :action :noop-action}]}}
                :b {}}}
              :region-b
              {:initial :x
               :states  {:x {} :y {}}}}}
          thrown (registration-throws? :rf.nested-validation/par-good m)]
      (is (nil? thrown)
          "a well-shaped parallel machine should register without throwing"))))

;; ---- multi-hop keyword-indirection in :guards / :actions (rf2-ylpnn) ------
;; The runtime resolver `transition/chase-ref` tolerates keyword INDIRECTION:
;; a :guards / :actions entry value may itself be a keyword pointing at
;; another entry. The registration validator must follow that SAME chain to
;; its terminal fn — testing membership of only the FIRST key let a multi-hop
;; chain whose terminal hop is missing pass registration and blow up only at
;; runtime, defeating the fail-fast contract (Spec 005 §Registration).

(deftest multi-hop-guard-indirection-resolves-registers
  (testing "a :guard ref through TWO hops of keyword indirection to a fn
            registers cleanly (control — the runtime resolves it)"
    (let [m {:initial :idle
             :guards  {:a :b
                       :b :c
                       :c (fn [_] true)}
             :actions {}
             :states  {:idle  {:on {:go [{:target :other :guard :a}]}}
                       :other {}}}
          thrown (registration-throws? :rf.ylpnn/guard-multi-hop-ok m)]
      (is (nil? thrown)
          "a multi-hop guard indirection that terminates at a fn must register"))))

(deftest multi-hop-guard-indirection-dangling-tail-rejected
  (testing "a :guard ref whose multi-hop indirection chain dangles at the
            terminal hop (no entry for :b) is rejected at REGISTRATION,
            not deferred to runtime (rf2-ylpnn)"
    (let [m {:initial :idle
             :guards  {:a :b}                    ;; :a → :b, but no :b entry
             :actions {}
             :states  {:idle  {:on {:go [{:target :other :guard :a}]}}
                       :other {}}}
          thrown (registration-throws? :rf.ylpnn/guard-dangling-tail m)]
      (is (some? thrown)
          "a dangling multi-hop guard chain MUST throw at registration")
      (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data thrown)))
          "error category names the unresolved-guard contract")
      (is (= :a (:guard (ex-data thrown)))
          "ex-data names the head ref the author wrote"))))

(deftest multi-hop-action-indirection-dangling-tail-rejected
  (testing "a :action ref whose multi-hop indirection chain dangles at the
            terminal hop is rejected at REGISTRATION (rf2-ylpnn)"
    (let [m {:initial :idle
             :guards  {}
             :actions {:a :b}                    ;; :a → :b, but no :b entry
             :states  {:idle  {:on {:go [{:target :other :action :a}]}}
                       :other {}}}
          thrown (registration-throws? :rf.ylpnn/action-dangling-tail m)]
      (is (some? thrown)
          "a dangling multi-hop action chain MUST throw at registration")
      (is (= :rf.error/machine-unresolved-action (:rf.error/id (ex-data thrown)))
          "error category names the unresolved-action contract")
      (is (= :a (:action (ex-data thrown)))
          "ex-data names the head ref the author wrote"))))

(deftest cyclic-guard-indirection-rejected
  (testing "a CYCLIC :guard indirection (:a → :b → :a, never a fn) is rejected
            at registration — chase-ref returns nil on a cycle, so the
            validator must treat it as unresolved rather than loop (rf2-ylpnn)"
    (let [m {:initial :idle
             :guards  {:a :b
                       :b :a}                     ;; cycle, no terminal fn
             :actions {}
             :states  {:idle  {:on {:go [{:target :other :guard :a}]}}
                       :other {}}}
          thrown (registration-throws? :rf.ylpnn/guard-cycle m)]
      (is (some? thrown)
          "a cyclic guard indirection MUST throw at registration (not loop)")
      (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data thrown)))
          "error category names the unresolved-guard contract"))))

;; ---- transition :target shape + resolution (rf2-w84jv) -------------------
;;
;; Per Spec 005 (005:441 "a transition targeting an unknown state fails
;; registration") + Spec-Schemas §TransitionTarget ([:or :keyword [:vector
;; :keyword]]): every transition slot's `:target` must be a well-formed,
;; resolvable target. Before rf2-w84jv only `:guard` / `:action` refs were
;; validated, so a malformed `{:target 42}` registered and threw
;; `:rf.error/machine-bad-state-form` later at the triggering dispatch, and a
;; missing `{:target [:missing]}` registered and committed an invalid snapshot.
;; XState v5 rejects unresolvable targets at machine creation; we align.

(deftest on-scalar-target-rejected-at-registration
  (testing "an :on transition whose :target is a non-keyword/non-vector scalar is rejected at registration (rf2-w84jv)"
    (let [m {:initial :idle
             :states  {:idle  {:on {:go {:target 42}}}
                       :other {}}}
          thrown (registration-throws? :rf.w84jv/scalar-target m)]
      (is (some? thrown)
          "a scalar :target SHOULD throw at registration, not at dispatch")
      (is (= :rf.error/machine-bad-target (:rf.error/id (ex-data thrown)))
          "error category names the bad-target contract")
      (is (= 42 (:target (ex-data thrown)))
          "ex-data carries the offending target"))))

(deftest on-missing-vector-target-rejected-at-registration
  (testing "an :on transition whose vector :target names no declared state is rejected at registration (rf2-w84jv)"
    (let [m {:initial :idle
             :states  {:idle  {:on {:go {:target [:missing]}}}
                       :other {}}}
          thrown (registration-throws? :rf.w84jv/missing-vector-target m)]
      (is (some? thrown)
          "an unresolved vector :target SHOULD throw at registration, not commit an invalid snapshot at dispatch")
      (is (= :rf.error/machine-unresolved-target (:rf.error/id (ex-data thrown)))
          "error category names the unresolved-target contract")
      (is (= [:missing] (:target (ex-data thrown)))
          "ex-data carries the offending target"))))

(deftest on-missing-keyword-target-rejected-at-registration
  (testing "an :on transition whose keyword :target names no sibling state is rejected at registration (rf2-w84jv)"
    (let [m {:initial :idle
             :states  {:idle  {:on {:go :nowhere}}
                       :other {}}}
          thrown (registration-throws? :rf.w84jv/missing-kw-target m)]
      (is (some? thrown)
          "an unresolved keyword :target SHOULD throw at registration")
      (is (= :rf.error/machine-unresolved-target (:rf.error/id (ex-data thrown)))
          "error category names the unresolved-target contract")
      (is (= :nowhere (:target (ex-data thrown)))))))

(deftest nested-keyword-target-resolves-as-sibling-not-cross-level
  (testing "a keyword :target from a NESTED state resolves as a sibling (direct child of the parent compound), so a target naming a state at a different level is rejected (rf2-w84jv)"
    ;; :leaf is at [:outer :mid :leaf]; keyword :sib resolves to
    ;; [:outer :mid :sib] (sibling), NOT [:outer :sib]. Targeting :outer's
    ;; child :elsewhere by bare keyword does NOT resolve — must be a vector.
    (let [m {:initial :outer
             :states  {:outer {:initial :mid
                               :states  {:mid {:initial :leaf
                                               :states  {:leaf {:on {:go :elsewhere}}}}
                                         :elsewhere {}}}}}
          thrown (registration-throws? :rf.w84jv/cross-level-kw m)]
      (is (some? thrown)
          "a keyword target reaching past the immediate parent level SHOULD throw")
      (is (= :rf.error/machine-unresolved-target (:rf.error/id (ex-data thrown)))))))

(deftest after-unresolved-target-rejected-at-registration
  (testing "an :after entry whose :target is unresolved is rejected at registration (rf2-w84jv)"
    (let [m {:initial :idle
             :states  {:idle  {:after {1000 :nowhere}}
                       :other {}}}
          thrown (registration-throws? :rf.w84jv/after-target m)]
      (is (some? thrown)
          "an unresolved :after :target SHOULD throw at registration")
      (is (= :rf.error/machine-unresolved-target (:rf.error/id (ex-data thrown)))))))

(deftest on-done-unresolved-target-rejected-at-registration
  (testing "a compound's :on-done whose :target is unresolved is rejected at registration (rf2-w84jv)"
    (let [m {:initial :outer
             :states  {:outer {:initial :done
                               :on-done :nowhere
                               :states  {:done {:final? true}}}}}
          thrown (registration-throws? :rf.w84jv/on-done-target m)]
      (is (some? thrown)
          "an unresolved compound :on-done :target SHOULD throw at registration")
      (is (= :rf.error/machine-unresolved-target (:rf.error/id (ex-data thrown)))))))

(deftest spawn-on-error-unresolved-target-rejected-at-registration
  (testing "a :spawn :on-error whose :target is unresolved is rejected at registration (rf2-w84jv)"
    (let [m {:initial :working
             :states  {:working {:spawn {:machine-id :rf.w84jv/some-child
                                         :on-error {:target :nowhere}}}
                       :other   {}}}
          thrown (registration-throws? :rf.w84jv/spawn-on-error-target m)]
      (is (some? thrown)
          "an unresolved :spawn :on-error :target SHOULD throw at registration")
      (is (= :rf.error/machine-unresolved-target (:rf.error/id (ex-data thrown)))))))

(deftest parallel-region-unresolved-target-rejected-at-registration
  (testing "an unresolved :target inside a parallel REGION is rejected at registration (rf2-w84jv)"
    (let [m {:type    :parallel
             :regions {:region-a {:initial :a
                                  :states  {:a {:on {:go :nowhere}}
                                            :b {}}}
                       :region-b {:initial :x
                                  :states  {:x {}}}}}
          thrown (registration-throws? :rf.w84jv/region-target m)]
      (is (some? thrown)
          "an unresolved region :target SHOULD throw at registration")
      (is (= :rf.error/machine-unresolved-target (:rf.error/id (ex-data thrown)))))))

(deftest valid-targets-register-cleanly
  (testing "well-formed resolvable targets (keyword sibling, vector absolute, :same-state, history pseudo-state) register cleanly (control) (rf2-w84jv)"
    (let [m {:initial :idle
             :states  {:idle  {:on {:go     :other          ;; keyword sibling
                                    :abs    [:nested :deep]  ;; vector absolute
                                    :self   :same-state}}    ;; self-target sentinel
                       :other {}
                       :nested {:initial :deep
                                :states  {:deep {}
                                          :hist {:type :history}}}}}
          thrown (registration-throws? :rf.w84jv/valid-targets m)]
      (is (nil? thrown)
          "well-formed resolvable targets must NOT be rejected"))))

(deftest history-pseudo-state-target-registers
  (testing "a vector :target naming a :type :history pseudo-state resolves (it lives in :states) and registers (control) (rf2-w84jv)"
    (let [m {:initial :idle
             :states  {:idle    {:on {:resume [:compound :hist]}}
                       :compound {:initial :a
                                  :states  {:a    {}
                                            :hist {:type :history}}}}}
          thrown (registration-throws? :rf.w84jv/history-target m)]
      (is (nil? thrown)
          "a history-pseudo-state vector target must resolve and register cleanly"))))
