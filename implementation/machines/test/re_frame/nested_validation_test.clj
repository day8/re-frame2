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
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

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
      (is (= ":rf.error/machine-unresolved-guard" (.getMessage thrown))
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
      (is (= ":rf.error/machine-unresolved-action" (.getMessage thrown))
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
      (is (= ":rf.error/machine-unresolved-guard" (.getMessage thrown))
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
      (is (= ":rf.error/machine-unresolved-action" (.getMessage thrown))
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
      (is (= ":rf.error/machine-unresolved-guard" (.getMessage thrown))
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
      (is (= ":rf.error/machine-unresolved-action" (.getMessage thrown))))))

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
      (is (= ":rf.error/machine-always-self-loop" (.getMessage thrown))
          "error category names the self-loop contract")
      (is (= :checking (:state (ex-data thrown)))
          "ex-data carries the declaring state-keyword"))))

(deftest always-self-loop-no-guard-rejected
  (testing "an :always entry that self-targets with no guard is rejected"
    (let [m {:initial :spin
             :states  {:spin {:always [{:target :spin}]}}}
          thrown (registration-throws? :rf.always-self-loop/no-guard m)]
      (is (some? thrown) "guard-less self-target SHOULD throw")
      (is (= ":rf.error/machine-always-self-loop" (.getMessage thrown))))))

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

(deftest always-self-loop-vector-target-rejected
  (testing "an :always entry whose vector :target is its own absolute path is rejected"
    (let [m {:initial :outer
             :guards  {:p? (fn [_] true)}
             :states  {:outer {:initial :inner
                               :states  {:inner {:always [{:guard  :p?
                                                           :target [:outer :inner]}]}}}}}
          thrown (registration-throws? :rf.always-self-loop/vec m)]
      (is (some? thrown) "vector self-target SHOULD throw")
      (is (= ":rf.error/machine-always-self-loop" (.getMessage thrown)))
      (is (= :inner (:state (ex-data thrown)))
          "ex-data names the declaring leaf state"))))

(deftest always-self-loop-single-map-form-rejected
  (testing "an :always declared as a single map (not a vector) is still walked"
    (let [m {:initial :checking
             :guards  {:ready? (fn [_] true)}
             :states  {:checking {:always {:guard :ready? :target :checking}}}}
          thrown (registration-throws? :rf.always-self-loop/single m)]
      (is (some? thrown) "single-map :always self-loop SHOULD throw")
      (is (= ":rf.error/machine-always-self-loop" (.getMessage thrown))))))

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
      (is (= ":rf.error/machine-always-self-loop" (.getMessage thrown))))))

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
      (is (= ":rf.error/machine-compound-state-missing-initial" (.getMessage thrown))
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
      (is (= ":rf.error/machine-compound-state-missing-initial" (.getMessage thrown)))
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
