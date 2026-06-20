(ns re-frame.trace-enhancements-rf2-82a0u-test
  "Three trace enhancements feed the Xray epoch panel's Handler section.
  This test file pins their emit shapes:

    1. `:rf.machine/action-ran` carries `:phase` from the closed set
       `:exit / :transition / :entry / :always / :after-action /
       :initial-entry / :destroy-exit`.
    2. `:rf.machine/guard-evaluated` carries `:outcome :threw` with
       `:exception` when the guard fn throws. The throwing guard is
       treated as `:fail` (the candidate walk continues), preserving
       Spec 005 §Guards candidate semantics.
    3. `:rf.machine.timer/cancelled` (single canonical event id) is
       emitted on every cancellation path with `:reason` from the
       closed set `:on-exit / :on-destroy / :on-resolution /
       :on-supersede / :on-frame-destroy`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers ---------------------------------------------------------------

;; Routed through the shared `mtest/with-trace-capture`
;; — guaranteed unregister in a `finally`.
(defn- record-traces!
  [body-fn]
  (mtest/with-trace-capture seen
    (body-fn)
    @seen))

(defn- ops [evs op]
  (filterv #(= op (:operation %)) evs))

;; =====================================================================
;; (1) :rf.machine/action-ran carries :phase
;; =====================================================================

(deftest action-ran-phase-exit-transition-entry
  (testing "an :on-driven cascade emits one action-ran per slot with
            :phase :exit / :transition / :entry shallowest-first"
    (rf/reg-machine :rf2-82a0u/cascade
      {:initial :idle
       :actions {:exit-idle  (fn [_] nil)
                 :do-go      (fn [_] nil)
                 :enter-done (fn [_] nil)}
       :states  {:idle {:exit :exit-idle
                        :on   {:go {:target :done :action :do-go}}}
                 :done {:entry :enter-done}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:rf2-82a0u/cascade [:go]])))
          as  (ops evs :rf.machine/action-ran)
          phases (mapv #(-> % :tags :phase) as)
          ids    (mapv #(-> % :tags :action-id) as)]
      (is (= [:exit-idle :do-go :enter-done] ids)
          "cascade order preserved")
      (is (= [:exit :transition :entry] phases)
          "phase tags per slot — exit / transition / entry"))))

(deftest action-ran-phase-always
  (testing "an `:always` step's action-ran carries :phase :always"
    (rf/reg-machine :rf2-82a0u/always
      {:initial :a
       :data    {:hop? true}
       :guards  {:hop? (fn [{d :data}] (:hop? d))}
       :actions {:do-step (fn [_] {:data {:hop? false}})
                 :tap     (fn [_] nil)}
       :states  {:a {:on {:go {:target :b}}}
                 :b {:always [{:guard :hop? :target :c :action :do-step}]}
                 :c {:entry :tap}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:rf2-82a0u/always [:go]])))
          as  (ops evs :rf.machine/action-ran)
          by-id (into {} (map (juxt #(-> % :tags :action-id)
                                    #(-> % :tags :phase)))
                          as)]
      (is (= :always (:do-step by-id))
          ":do-step ran from the :always microstep — :phase :always")
      (is (= :entry (:tap by-id))
          ":tap is :c's :entry from the :always-driven entry cascade"))))

(deftest action-ran-phase-after-action
  (testing "an :after-driven transition action-ran carries :phase :after-action"
    (rf/reg-machine :rf2-82a0u/after
      {:initial :loading
       :actions {:do-timeout (fn [_] nil)}
       :states  {:loading {:after {1000 {:target :done :action :do-timeout}}}
                 :done    {}}})
    ;; Bootstrap into :loading first (so the per-path epoch lands at 1).
    ;; Then dispatch the synthetic after-elapsed event carrying the
    ;; matching delay-key + carried-epoch + carried-decl-path tuple.
    (let [_ (rf/dispatch-sync [:rf2-82a0u/after [:rf.machine/start]])
          evs (record-traces!
                (fn []
                  (rf/dispatch-sync
                    [:rf2-82a0u/after
                     [:rf.machine.timer/after-elapsed 1000 1 [:loading]]])))
          as  (ops evs :rf.machine/action-ran)
          do-t (some #(when (= :do-timeout (-> % :tags :action-id)) %) as)]
      (is (some? do-t) ":do-timeout fired")
      (is (= :after-action (-> do-t :tags :phase))
          "the timer-driven transition's :action stamps :after-action"))))

(deftest action-ran-phase-initial-entry
  (testing "bootstrap entry cascade actions stamp :phase :initial-entry"
    (rf/reg-machine :rf2-82a0u/boot
      {:initial :idle
       :actions {:hello (fn [_] nil)}
       :states  {:idle {:entry :hello}}})
    (let [evs (record-traces!
                (fn []
                  ;; Any dispatch into a freshly-registered machine
                  ;; triggers the bootstrap entry cascade before the
                  ;; event itself is handled.
                  (rf/dispatch-sync [:rf2-82a0u/boot [:rf.machine/start]])))
          as  (ops evs :rf.machine/action-ran)
          hello (some #(when (= :hello (-> % :tags :action-id)) %) as)]
      (is (some? hello) ":hello fired from initial-entry")
      (is (= :initial-entry (-> hello :tags :phase))
          "bootstrap cascade stamps :initial-entry"))))

(deftest action-ran-phase-closed-set-only
  (testing "every action-ran emit carries :phase from the closed set"
    (rf/reg-machine :rf2-82a0u/closed
      {:initial :idle
       :actions {:tap (fn [_] nil)}
       :states  {:idle {:exit :tap
                        :on   {:go {:target :done :action :tap}}}
                 :done {:entry :tap}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:rf2-82a0u/closed [:go]])))
          as  (ops evs :rf.machine/action-ran)
          phases (set (map #(-> % :tags :phase) as))
          allowed #{:exit :transition :entry :always :after-action
                    :initial-entry :destroy-exit}]
      (is (every? allowed phases)
          (str "every :phase ∈ closed set; got " phases)))))

;; =====================================================================
;; (2) :rf.machine/guard-evaluated outcome :threw
;; =====================================================================

(deftest guard-evaluated-threw-emits-outcome-and-exception
  (testing "a throwing guard emits :outcome :threw + :exception slot;
            the candidate walk continues (treated as :fail)"
    (rf/reg-machine :rf2-82a0u/guard-throws
      {:initial :idle
       :guards  {:boom (fn [_] (throw (ex-info "guard boom" {})))
                 :ok   (fn [_] true)}
       :states  {:idle  {:on {:go [{:guard :boom :target :A}
                                   {:guard :ok   :target :B}]}}
                 :A     {}
                 :B     {}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:rf2-82a0u/guard-throws [:go]])))
          gs  (ops evs :rf.machine/guard-evaluated)]
      (is (= 2 (count gs))
          "two guards evaluated — the throwing one + the next sibling")
      (let [g1 (first gs)
            g2 (second gs)]
        (is (= :boom (-> g1 :tags :guard-id)))
        (is (= :threw (-> g1 :tags :outcome))
            ":threw outcome marker on the throwing guard")
        (is (instance? Throwable (-> g1 :tags :exception))
            ":exception slot carries the thrown Throwable")
        (is (= :ok (-> g2 :tags :guard-id))
            "candidate walk continued to the next sibling")
        (is (= :pass (-> g2 :tags :outcome))
            ":ok guard passed and the transition fired")))))

(deftest guard-throw-treated-as-fail-not-cascade-abort
  (testing "a throwing guard does NOT abort the cascade — the engine
            walks past it to the next candidate. The transition still
            fires (via the second candidate)"
    (rf/reg-machine :rf2-82a0u/threw-fall
      {:initial :idle
       :guards  {:boom (fn [_] (throw (ex-info "boom" {})))}
       :data    {:n 0}
       :actions {:bump (fn [{d :data}] {:data {:n (inc (:n d))}})}
       :states  {:idle {:on {:go [{:guard :boom :target :nope}
                                  {:target :done :action :bump}]}}
                 :nope {}
                 :done {}}})
    (rf/dispatch-sync [:rf2-82a0u/threw-fall [:go]])
    ;; The snapshot is now in :done — the throwing-guard candidate
    ;; failed-and-was-walked-past; the unguarded candidate fired.
    (let [db   (frame/frame-runtime-db-value :rf/default)
          snap (get-in db [:rf.runtime/machines :snapshots :rf2-82a0u/threw-fall])]
      (is (= :done (:state snap))
          "transition reached the second candidate's target")
      (is (= 1 (-> snap :data :n))
          ":bump action ran from the firing candidate"))))

(deftest guard-evaluated-pass-fail-outcomes-unchanged
  (testing "pass / fail outcomes still emit the same shape (no
            unintended schema drift from the rf2-82a0u change)"
    (rf/reg-machine :rf2-82a0u/pass-fail
      {:initial :idle
       :data    {:ready? false}
       :guards  {:ready? (fn [{d :data}] (:ready? d))}
       :states  {:idle  {:on {:go [{:guard :ready? :target :done}]}}
                 :done  {}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:rf2-82a0u/pass-fail [:go]])))
          g (first (ops evs :rf.machine/guard-evaluated))]
      (is (= :fail (-> g :tags :outcome))
          ":fail outcome (the guard returned false)")
      (is (nil? (-> g :tags :exception))
          "no :exception slot on the non-throw path"))))

;; =====================================================================
;; (3) :rf.machine.timer/cancelled — unified event with :reason
;; =====================================================================

(defn- timer-cancellations [evs]
  (ops evs :rf.machine.timer/cancelled))

(deftest cancelled-on-exit-emits-reason-on-exit
  (testing ":after timer cancelled by state-exit emits :reason :on-exit"
    (rf/reg-machine :rf2-82a0u/cancel-exit
      {:initial :armed
       :states  {:armed {:after {60000 :timeout}
                         :on    {:cancel :idle}}
                 :timeout {}
                 :idle    {}}})
    (let [evs (record-traces!
                (fn []
                  ;; Bootstrap into :armed — fx layer arms the timer.
                  (rf/dispatch-sync [:rf2-82a0u/cancel-exit [:rf.machine/start]])
                  ;; Exit :armed — fires after-cancel-fx.
                  (rf/dispatch-sync [:rf2-82a0u/cancel-exit [:cancel]])))
          cs  (timer-cancellations evs)
          ev  (first cs)]
      (is (= 1 (count cs))
          "exactly one cancellation trace from the exit")
      (is (= :on-exit (-> ev :tags :reason))
          ":reason :on-exit stamped on the unified event")
      (is (= :rf2-82a0u/cancel-exit (-> ev :tags :actor-id))
          ":actor-id present (payload mirrors :scheduled)")
      (is (some? (-> ev :tags :epoch))
          ":epoch present (mirrors :scheduled for pairing)")
      (is (some? (-> ev :tags :frame))
          ":frame present (epoch-capture admission)"))))

(deftest cancelled-on-destroy-emits-reason-on-destroy
  (testing "destroying a machine with an armed `:after` timer cancels
            via the destroy path with `:reason :on-destroy`"
    (rf/reg-machine :rf2-82a0u/destroy-target
      {:initial :armed
       :states  {:armed {:after {60000 :done}}
                 :done  {}}})
    (rf/reg-machine :rf2-82a0u/destroyer
      {:initial :ready
       :states  {:ready {:on {:fire {:action (fn [_]
                                               {:fx [[:rf.machine/destroy
                                                      :rf2-82a0u/destroy-target]]})}}}}})
    (let [evs (record-traces!
                (fn []
                  ;; Bootstrap the target so its :after arms.
                  (rf/dispatch-sync [:rf2-82a0u/destroy-target [:rf.machine/start]])
                  ;; Bootstrap the destroyer, then trigger destroy.
                  (rf/dispatch-sync [:rf2-82a0u/destroyer [:rf.machine/start]])
                  (rf/dispatch-sync [:rf2-82a0u/destroyer [:fire]])))
          cs  (timer-cancellations evs)
          destroy-evs (filter #(= :on-destroy (-> % :tags :reason)) cs)]
      (is (seq destroy-evs)
          (str "at least one :reason :on-destroy emit; got " (mapv #(-> % :tags :reason) cs))))))

(deftest cancelled-payload-mirrors-scheduled
  (testing "the :cancelled payload mirrors :scheduled's shape for
            arm-cancel pairing by (machine-id, state, epoch)"
    (rf/reg-machine :rf2-82a0u/mirror
      {:initial :loading
       :states  {:loading {:after {30000 :timeout}
                           :on    {:cancel :idle}}
                 :idle    {}
                 :timeout {}}})
    (let [evs (record-traces!
                (fn []
                  (rf/dispatch-sync [:rf2-82a0u/mirror [:rf.machine/start]])
                  (rf/dispatch-sync [:rf2-82a0u/mirror [:cancel]])))
          sched-ev  (first (ops evs :rf.machine.timer/scheduled))
          cancel-ev (first (timer-cancellations evs))]
      (is (some? sched-ev) ":scheduled fired")
      (is (some? cancel-ev) ":cancelled fired")
      (is (= (-> sched-ev :tags :actor-id)
             (-> cancel-ev :tags :actor-id))
          ":actor-id matches across the pair")
      (is (= (-> sched-ev :tags :state)
             (-> cancel-ev :tags :state))
          ":state matches")
      (is (= (-> sched-ev :tags :epoch)
             (-> cancel-ev :tags :epoch))
          ":epoch matches — the cancel closes the same arm's slot"))))

(deftest cancelled-reason-closed-set
  (testing "every :rf.machine.timer/cancelled emit's :reason is in the
            closed set"
    (rf/reg-machine :rf2-82a0u/closed-reasons
      {:initial :a
       :states  {:a {:after {60000 :b}
                     :on    {:cancel :c}}
                 :b {}
                 :c {}}})
    (let [evs (record-traces!
                (fn []
                  (rf/dispatch-sync [:rf2-82a0u/closed-reasons [:rf.machine/start]])
                  (rf/dispatch-sync [:rf2-82a0u/closed-reasons [:cancel]])))
          cs  (timer-cancellations evs)
          reasons (set (map #(-> % :tags :reason) cs))
          allowed #{:on-exit :on-destroy :on-resolution
                    :on-supersede :on-frame-destroy}]
      (is (every? allowed reasons)
          (str "every :reason ∈ closed set; got " reasons)))))
