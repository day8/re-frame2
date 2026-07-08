(ns re-frame.parallel-root-on-test
  "Per Spec 005 §Transition broadcast §Root parallel `:on` (XState v5 / SCXML).
  The parallel ROOT's own `:on` is the ANCESTOR FALLBACK for
  its regions: deepest-wins with parent fallthrough, the parallel analog of
  the flat / compound machine-root `:on` fallback.

  Verified against xstate@5.32.0. The selection semantic is the load-bearing
  thing — atomic ancestor-fallback suppression:

    - root `:on`, NO region handles the event  → the root transition fires,
      moving the targeted region(s).            GO  -> {a:two, b:two}
    - root `:on` targeting ONE region           → that region moves, others
      unchanged.                                 ONE -> {a:two, b:one}
    - region-local AND root both match the same event → the DEEPER region
      transition wins; the root transition is NOT merged in (suppressed
      ENTIRELY, atomic).                         GO  -> {a:two, b:one}
    - COMPETING-MULTI-REGION SUPPRESSION (the non-decomposable case): a root
      multi-region default fires UNLESS a region competes, in which case the
      whole root transition is suppressed and the untargeted sibling stays
      UNCHANGED.                                 RESET -> {a:special, b:unchanged}

  Plus: the root `:on` fires (it is registered AND executed), the
  multi-region target grammar, the root `:action` running once, guard/action
  ref validation reaching the root-parallel transition, the region-qualified
  target-shape validation, and the ROOT-LEVEL `:after` — root-owned,
  region-qualified targets, action/fx-only timeouts, guard suppression, and
  cancel-on-exit via the root epoch.

  These JVM tests pair with the conformance fixtures
  spec/conformance/fixtures/parallel-root-on-*.edn."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

;; A two-region machine. Each region: :one -> :two on :go. No region handles
;; :all (only the root will). Used by several cases.
(def two-region
  {:type    :parallel
   :data    {}
   :regions {:a {:initial :one
                 :states  {:one {:on {:go :two}}
                           :two {}}}
             :b {:initial :one
                 :states  {:one {:on {:go :two}}
                           :two {}}}}})

;; ---- 1. root :on fires on a :type :parallel machine -----------------------
;;
;; A root-level :on on a :type :parallel machine is both validated AND
;; executed — it FIRES.

(deftest root-on-no-longer-silently-dropped
  (testing "a root :on on :type :parallel is registered AND executed (was silently dropped)"
    (let [m {:type    :parallel
             :data    {}
             ;; root :on — NO region handles :reset-all.
             :on      {:reset-all {:target [[:a :one] [:b :one]]}}
             :regions {:a {:initial :two :states {:one {} :two {}}}
                       :b {:initial :two :states {:one {} :two {}}}}}
          initial {:state {:a :two :b :two} :data {}}
          {snap ::result/snap} (machines/machine-transition m initial [:reset-all])]
      (is (= {:a :one :b :one} (:state snap))
          "root :on fired and moved BOTH regions — NOT silently dropped"))))

;; ---- 2. root :on, no region handles -> root fires (GO -> {a:two b:two}) ----

(deftest root-on-fires-when-no-region-handles
  (testing "GO: no region declares :go-all; the root :on moves both regions"
    (let [m {:type    :parallel
             :data    {}
             :on      {:go-all {:target [[:a :two] [:b :two]]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          initial {:state {:a :one :b :one} :data {}}
          {snap ::result/snap} (machines/machine-transition m initial [:go-all])]
      (is (= {:a :two :b :two} (:state snap))
          "root :on fired; both targeted regions moved (v5: GO -> {a:two,b:two})"))))

;; ---- 3. root :on targeting ONE region (ONE -> {a:two b:one}) ---------------

(deftest root-on-single-region-target
  (testing "ONE: root :on targets only :a; :b stays unchanged"
    (let [m {:type    :parallel
             :data    {}
             :on      {:one {:target [:a :two]}}    ; single region-qualified target
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          initial {:state {:a :one :b :one} :data {}}
          {snap ::result/snap} (machines/machine-transition m initial [:one])]
      (is (= {:a :two :b :one} (:state snap))
          "root :on moved :a; :b untargeted -> unchanged (v5: ONE -> {a:two,b:one})"))))

;; ---- 4. region-local AND root both match -> region wins, root SUPPRESSED ---

(deftest region-match-suppresses-root-entirely
  (testing "GO: region :a handles :go locally; the root :go is suppressed ENTIRELY"
    (let [m {:type    :parallel
             :data    {}
             ;; root would move BOTH if consulted — but :a handles :go locally,
             ;; so the root is suppressed and :b (which has NO :go) stays put.
             :on      {:go {:target [[:a :two] [:b :two]]}}
             :regions {:a {:initial :one :states {:one {:on {:go :two}} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          initial {:state {:a :one :b :one} :data {}}
          {snap ::result/snap} (machines/machine-transition m initial [:go])]
      (is (= {:a :two :b :one} (:state snap))
          (str "region :a handled :go (deeper wins); the root :on was NOT merged "
               "in -> :b stays :one (v5: GO -> {a:two,b:one})")))))

;; ---- 5. COMPETING-MULTI-REGION SUPPRESSION — the non-decomposable case -----
;;
;; The definitive parity fixture from the bead. Broadcast decomposition would
;; give {a:special, b:initial}; v5 / the atomic ancestor fallback gives
;; {a:special, b:UNCHANGED} because :a competing suppresses the WHOLE root.

(deftest competing-multi-region-suppression
  (testing "RESET: :a wants a special reset; the root multi-region RESET is suppressed; :b unchanged"
    (let [m {:type    :parallel
             :data    {}
             ;; root default: reset BOTH regions.
             :on      {:reset {:target [[:a :initial] [:b :initial]]}}
             :regions {:a {:initial :initial
                           :states  {:initial {:on {:reset :special}}  ; :a wants a special reset
                                     :special {}}}
                       :b {:initial :resting
                           :states  {:initial {} :resting {}}}}}
          initial {:state {:a :initial :b :resting} :data {}}
          {snap ::result/snap} (machines/machine-transition m initial [:reset])]
      (is (= {:a :special :b :resting} (:state snap))
          (str "the WHOLE root RESET is suppressed because :a competed; :b stays "
               ":resting (NOT :initial). Broadcast decomposition would wrongly give "
               "{a:special, b:initial}.")))))

;; ---- 6. multi-region root target — atomic update of all named regions ------

(deftest multi-region-target-atomic
  (testing "a root :on with [[:a :x] [:b :y]] updates both named regions atomically"
    (let [m {:type    :parallel
             :data    {}
             :on      {:advance {:target [[:a :x] [:b :y]]}}
             :regions {:a {:initial :one :states {:one {} :x {}}}
                       :b {:initial :one :states {:one {} :y {}}}
                       :c {:initial :one :states {:one {}}}}}     ; untargeted
          initial {:state {:a :one :b :one :c :one} :data {}}
          {snap ::result/snap} (machines/machine-transition m initial [:advance])]
      (is (= {:a :x :b :y :c :one} (:state snap))
          ":a and :b moved to their named targets; :c (untargeted) unchanged"))))

;; ---- 7. root :on runs its :action ONCE (not per region) --------------------

(deftest root-on-action-runs-once
  (testing "the root transition's :action fires exactly once against the shared :data"
    (let [m {:type    :parallel
             :data    {:count 0}
             :actions {:bump (fn [{d :data}] {:data (update d :count inc)})}
             :on      {:tick {:target [[:a :two] [:b :two]] :action :bump}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          initial {:state {:a :one :b :one} :data {:count 0}}
          {snap ::result/snap} (machines/machine-transition m initial [:tick])]
      (is (= {:a :two :b :two} (:state snap)) "both regions moved")
      (is (= 1 (get-in snap [:data :count]))
          "root :action ran ONCE (not once per targeted region)"))))

;; ---- 8. targetless / action-only root :on ----------------------------------

(deftest root-on-action-only
  (testing "a targetless root :on runs its action; no region moves"
    (let [m {:type    :parallel
             :data    {:log []}
             :actions {:note (fn [{d :data}] {:data (update d :log conj :noted)})}
             :on      {:ping {:action :note}}      ; action-only, no :target
             :regions {:a {:initial :one :states {:one {}}}
                       :b {:initial :one :states {:one {}}}}}
          initial {:state {:a :one :b :one} :data {:log []}}
          {snap ::result/snap} (machines/machine-transition m initial [:ping])]
      (is (= {:a :one :b :one} (:state snap)) "no region moved (targetless)")
      (is (= [:noted] (get-in snap [:data :log])) "the root action ran once"))))

;; ---- 9. root :on guard — selected against the frozen pre-event snapshot ----

(deftest root-on-guard-frozen-selection
  (testing "the root :on guard reads the pre-event :data; a failing guard declines the root"
    (let [m {:type    :parallel
             :data    {:armed? false}
             :guards  {:armed? (fn [{d :data}] (true? (:armed? d)))}
             :on      {:fire {:target [[:a :two] [:b :two]] :guard :armed?}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}]
      (testing "guard false -> root declines -> no move"
        (let [{snap ::result/snap} (machines/machine-transition
                                     m {:state {:a :one :b :one} :data {:armed? false}}
                                     [:fire])]
          (is (= {:a :one :b :one} (:state snap)) "guard false -> root :on not selected")))
      (testing "guard true -> root fires"
        (let [{snap ::result/snap} (machines/machine-transition
                                     m {:state {:a :one :b :one} :data {:armed? true}}
                                     [:fire])]
          (is (= {:a :two :b :two} (:state snap)) "guard true -> root :on moves both"))))))

;; ---- 10. moved region settles its :always after the root transition --------

(deftest root-on-moved-region-settles-always
  (testing "a region moved by the root :on settles its target's :always in the same macrostep"
    (let [m {:type    :parallel
             :data    {}
             :on      {:advance {:target [:a :mid]}}
             :regions {:a {:initial :one
                           :states  {:one {}
                                     :mid {:always [{:target :done}]}  ; transient
                                     :done {:tags #{:a/done}}}}
                       :b {:initial :one :states {:one {}}}}}
          initial {:state {:a :one :b :one} :data {}}
          {snap ::result/snap} (machines/machine-transition m initial [:advance])]
      (is (= :done (get-in snap [:state :a]))
          ":a was moved to :mid by the root, then :always settled past to :done")
      (is (= :one (get-in snap [:state :b])) ":b unchanged")
      (is (= #{:a/done} (:tags snap)) "tag union reflects the settled :done state"))))

;; ---- 11. end-to-end via dispatch (live runtime) ----------------------------

(deftest root-on-end-to-end-dispatch
  (testing "root :on fires through the live dispatch loop"
    (rf/reg-machine :par/root-on two-region)
    (rf/dispatch-sync [:par/root-on [:go]])   ; both regions handle :go locally
    (is (= {:a :two :b :two} (:state (snapshot :par/root-on)))
        "both regions handled :go locally")
    ;; now a fresh machine with a root :on for an event no region handles
    (rf/reg-machine :par/root-fallback
                    {:type    :parallel
                     :data    {}
                     :on      {:bump-a {:target [:a :two]}}
                     :regions {:a {:initial :one :states {:one {} :two {}}}
                               :b {:initial :one :states {:one {} :two {}}}}})
    (rf/dispatch-sync [:par/root-fallback [:bump-a]])
    (is (= {:a :two :b :one} (:state (snapshot :par/root-fallback)))
        "root :on moved :a; :b unchanged, end-to-end")))

;; ---- 12. registration validation: root-parallel transition refs + shape ----

(deftest root-parallel-validation
  (testing "a dangling guard ref on a root parallel :on is rejected at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-unresolved-guard"
          (machines/make-machine-handler
            {:type    :parallel
             :on      {:fire {:target [:a :two] :guard :nope}}  ; :nope not in :guards
             :regions {:a {:initial :one :states {:one {} :two {}}}}}))
        "root-parallel transition guard refs are now validated"))

  (testing "a dangling action ref on a root parallel :on is rejected at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-unresolved-action"
          (machines/make-machine-handler
            {:type    :parallel
             :on      {:fire {:target [:a :two] :action :nope}}
             :regions {:a {:initial :one :states {:one {} :two {}}}}}))
        "root-parallel transition action refs are now validated"))

  (testing "a bare-keyword (non-region-qualified) root :on target is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-root-on-bad-target"
          (machines/make-machine-handler
            {:type    :parallel
             :on      {:go :somewhere}     ; bare keyword — no flat sibling state
             :regions {:a {:initial :one :states {:one {}}}}}))
        "the root has no flat sibling state for a bare keyword target"))

  (testing "a root :on target whose head is not a declared region is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-root-on-bad-target"
          (machines/make-machine-handler
            {:type    :parallel
             :on      {:go {:target [:nonregion :x]}}
             :regions {:a {:initial :one :states {:one {} :x {}}}}}))
        ":nonregion is not a declared region"))

  (testing "a multi-region target with a non-region head is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-root-on-bad-target"
          (machines/make-machine-handler
            {:type    :parallel
             :on      {:go {:target [[:a :two] [:nope :two]]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}))))

  (testing "a well-formed root parallel :on validates silently"
    (is (nil? (machines/validate-machine!
                {:type    :parallel
                 :guards  {:ok? (fn [_] true)}
                 :actions {:act (fn [_] nil)}
                 :on      {:go    {:target [[:a :two] [:b :two]] :guard :ok? :action :act}
                           :one   {:target [:a :two]}
                           :ping  {:action :act}}
                 :regions {:a {:initial :one :states {:one {} :two {}}}
                           :b {:initial :one :states {:one {} :two {}}}}}))
        "region-qualified targets + resolvable refs pass")))

;; ---- 13. ROOT-LEVEL :after on a parallel root -----------------------------
;;
;; XState v5 / SCXML: `after` may be declared at any level, including a
;; <parallel> node. A parallel-ROOT `:after` is ROOT-OWNED — scheduled at
;; machine birth, alive for the whole machine, stale-gated by the root's OWN
;; per-path epoch (NOT bound to any region's lifecycle). It is the timer-driven
;; analog of the root `:on` ancestor fallback, reusing the exact region-
;; qualified target grammar.

(deftest root-after-registers-and-validates
  (testing "a :type :parallel root declaring :after now REGISTERS (was rejected loudly)"
    (is (nil? (machines/validate-machine!
                {:type    :parallel
                 :after   {1000 {:target [:a :two]}}
                 :regions {:a {:initial :one :states {:one {} :two {}}}
                           :b {:initial :one :states {:one {} :two {}}}}}))
        "region-qualified single-target root :after validates silently"))
  (testing "multi-region + action/fx-only root :after value-forms validate"
    (is (nil? (machines/validate-machine!
                {:type    :parallel
                 :guards  {:ok? (fn [_] true)}
                 :actions {:act (fn [_] nil)}
                 :after   {1000 {:target [[:a :two] [:b :two]] :guard :ok?}
                           2000 {:action :act}                       ; action-only
                           3000 [:a :two]                            ; bare vector target
                           4000 [{:guard :ok? :target [:a :two]}     ; candidate vector
                                 {:action :act}]}
                 :regions {:a {:initial :one :states {:one {} :two {}}}
                           :b {:initial :one :states {:one {} :two {}}}}}))
        "multi-region, action-only, bare-vector and candidate-vector forms pass")))

(deftest root-after-bad-target-rejected
  (testing "a non-region-qualified root :after :target is rejected (same keyword as root :on)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-root-on-bad-target"
          (machines/make-machine-handler
            {:type    :parallel
             :after   {1000 {:target :two}}    ; bare keyword — no flat sibling
             :regions {:a {:initial :one :states {:one {} :two {}}}}}))
        "a bare-keyword root :after target has no flat sibling to land on"))
  (testing "a root :after target whose head is not a declared region is rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/machine-parallel-root-on-bad-target"
          (machines/make-machine-handler
            {:type    :parallel
             :after   {1000 {:target [:nonregion :x]}}
             :regions {:a {:initial :one :states {:one {:on {}} :x {}}}}})))))

;; ---- 14. root :after FIRES — moves the targeted region(s), via pure-fn -----
;;
;; The pure `machine-transition` apply path: a root-after-elapsed event with
;; the empty `[]` decl-path routes to the root `:on` apply grammar. The epoch
;; on the snapshot matches the carried epoch, so the timer is live and fires.

(deftest root-after-fires-moves-targeted-regions
  (testing "a live root :after firing moves its region-qualified target(s)"
    (let [m {:type    :parallel
             :data    {}
             :after   {1000 {:target [[:a :two] [:b :two]]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          ;; root epoch 1 (as birth would seed) at the flat root slot.
          initial {:state {:a :one :b :one}
                   :data  {:rf/after-epoch {[] 1}}}
          {snap ::result/snap}
          (machines/machine-transition
            m initial [:rf.machine.timer/after-elapsed 1000 1 []])]
      (is (= {:a :two :b :two} (:state snap))
          "live root :after (matching epoch) moved BOTH targeted regions")))
  (testing "a root :after targeting ONE region leaves untargeted regions unchanged"
    (let [m {:type    :parallel
             :data    {}
             :after   {500 {:target [:a :two]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          initial {:state {:a :one :b :one}
                   :data  {:rf/after-epoch {[] 1}}}
          {snap ::result/snap}
          (machines/machine-transition
            m initial [:rf.machine.timer/after-elapsed 500 1 []])]
      (is (= {:a :two :b :one} (:state snap))
          ":a moved; :b untargeted -> unchanged"))))

(deftest root-after-action-fx-only-fires
  (testing "an action/fx-only root :after (no :target) runs its action, moves no region"
    (let [m {:type    :parallel
             :data    {:fired 0}
             :actions {:bump (fn [{d :data}] {:data (update d :fired inc)})}
             :after   {1000 {:action :bump}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          initial {:state {:a :one :b :one}
                   :data  {:fired 0 :rf/after-epoch {[] 1}}}
          {snap ::result/snap}
          (machines/machine-transition
            m initial [:rf.machine.timer/after-elapsed 1000 1 []])]
      (is (= {:a :one :b :one} (:state snap)) "no region moved (action-only)")
      (is (= 1 (get-in snap [:data :fired])) "the root :after :action ran once"))))

(deftest root-after-guard-suppressed-does-not-fire
  (testing "a root :after whose guard returns false is fired-and-discarded (no move)"
    (let [m {:type    :parallel
             :data    {}
             :guards  {:never (fn [_] false)}
             :after   {1000 {:target [[:a :two] [:b :two]] :guard :never}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          initial {:state {:a :one :b :one}
                   :data  {:rf/after-epoch {[] 1}}}
          {snap ::result/snap}
          (machines/machine-transition
            m initial [:rf.machine.timer/after-elapsed 1000 1 []])]
      (is (= {:a :one :b :one} (:state snap))
          "guard false -> the root :after timer fires-and-discards; no region moved"))))

;; ---- 15. ADVERSARIAL: root :after cancels-on-exit via epoch ----------------
;;
;; Adversarial coverage: the root :after is ROOT-OWNED and stale-gated by the
;; root's OWN per-path epoch. A timer carrying a STALE epoch (the root was torn
;; down / its epoch advanced) DROPS — exactly the cancel-on-exit-via-epoch the
;; per-state :after machinery uses, but at the root.

(deftest root-after-stale-epoch-drops
  (testing "a root :after firing with a STALE epoch does NOT transition (cancel-on-exit)"
    (let [m {:type    :parallel
             :data    {}
             :after   {1000 {:target [[:a :two] [:b :two]]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          ;; The root's current epoch is 2 (a teardown/advance happened); the
          ;; in-flight timer carries the prior epoch 1 -> stale, must drop.
          initial {:state {:a :one :b :one}
                   :data  {:rf/after-epoch {[] 2}}}
          {snap ::result/snap}
          (machines/machine-transition
            m initial [:rf.machine.timer/after-elapsed 1000 1 []])]
      (is (= {:a :one :b :one} (:state snap))
          "stale-epoch root :after dropped; no region moved (cancel-on-exit via epoch)"))))

;; ---- 16. root :after end-to-end through registration + birth ---------------
;;
;; The live runtime path: registration runs the BIRTH cascade, which schedules
;; the root :after (seeds the root epoch at the flat `[]` slot AND emits the
;; `:scheduled` trace). Firing the synthetic timer event with the seeded epoch
;; transitions; a stale epoch drops.

(deftest root-after-end-to-end-birth-schedule-and-fire
  (testing "registration births the root :after: epoch seeded + :scheduled trace; firing transitions"
    (let [m {:type    :parallel
             :data    {}
             :after   {1000 {:target [[:a :two] [:b :two]]}}
             :regions {:a {:initial :one :states {:one {} :two {}}}
                       :b {:initial :one :states {:one {} :two {}}}}}
          traces (atom [])]
      (rf/reg-machine :rootafter/e2e m)
      (rf/register-listener! :trace ::ra (fn [ev] (swap! traces conj ev)))
      ;; Eager birth: kick the start so the initial cascade (and root :after
      ;; schedule) runs and commits.
      (rf/dispatch-sync [:rootafter/e2e [:rf.machine/start]])
      (let [s (snapshot :rootafter/e2e)]
        (is (= {:a :one :b :one} (:state s)) "born in each region's initial")
        (is (= 1 (get-in s [:data :rf/after-epoch []]))
            "the ROOT :after epoch (flat `[]` slot) was seeded at birth"))
      (is (some #(and (= :rf.machine.timer/scheduled (:operation %))
                      (= 1000 (:delay (:tags %)))
                      (= :literal (:delay-source (:tags %))))
                @traces)
          "a :scheduled trace fired for the root :after at birth")
      ;; Fire the matching-epoch synthetic timer -> both regions move.
      (rf/dispatch-sync [:rootafter/e2e [:rf.machine.timer/after-elapsed 1000 1 []]])
      (is (= {:a :two :b :two} (:state (snapshot :rootafter/e2e)))
          "matching-epoch root :after firing moved both regions")
      (is (some #(and (= :rf.machine.timer/fired (:operation %))
                      (= 1000 (:delay (:tags %)))
                      (= true (:fired? (:tags %))))
                @traces)
          "a :fired trace (fired? true) emitted for the root :after")
      (rf/unregister-listener! :trace ::ra))))
