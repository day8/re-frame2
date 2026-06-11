(ns re-frame.machine-reply-lowering-test
  "Conformance: the two machine async completions share the uniform
  reply-envelope status/trace vocabulary (EP-0011 §Machine Completion /
  §Timer Reply; Managed-Effects §The uniform reply envelope, rf2-zqefg3.4).
  INTERNAL LOWERING ONLY — the public statechart API (`:on-done` /
  `:on-error` / `:after` / actor-destroy) is preserved exactly.

  Two conformance requirements:

   1. **`:after` epoch mismatch** does NOT dispatch the app target (the
      transition does not fire) AND records the drop with the
      reply-envelope vocabulary on the `:rf.machine.timer/stale-after`
      trace (`:rf.reply/status :stale`, `:rf.reply/work-status
      :suppressed`, the carried/current declaring-path+epoch gate).

   2. **Spawned-actor completion** forms a canonical reply and drives
      `:on-done` (success → `:data` callback, value from the canonical
      reply's `:value`) / `:on-error` (error terminal → parent
      transition), with the reply-envelope facts riding the
      `:rf.machine/done` trace.

  These dispatch the synthetic `[:rf.machine.timer/after-elapsed …]` event
  manually (the after_test.clj pattern) so the verification is
  deterministic without depending on wall-clock firing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading `re-frame.machines` installs the late-bind hooks
            ;; (`reg-machine`, the `:rf.machine/spawn` / `:rf.machine/destroy`
            ;; / `:rf.machine/after-*` reserved fxs) the runtime needs.
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private snapshot mtest/snapshot)

(defn- capture-traces [id]
  (let [a (atom [])]
    (trace/register-listener! id (fn [ev] (swap! a conj ev)))
    a))

;; ===========================================================================
;; (1) :after epoch mismatch — no app dispatch + records suppressed
;; ===========================================================================

(deftest after-epoch-mismatch-suppresses-with-reply-vocabulary
  (testing "a stale :after timer does NOT fire its transition AND the stale-after trace carries the reply-envelope status/work-status/gate"
    (let [traces (capture-traces ::after-stale)]
      (try
        (rf/reg-machine :rl/after
          {:initial :loading
           :states  {:loading {:after {30000 :timed-out}
                               :on    {:done :ready}}
                     :ready     {}
                     :timed-out {}}})
        ;; Enter :loading (epoch 1 for [:loading]); then a :done event
        ;; exits :loading → :ready, bumping the [:loading] per-path epoch.
        (rf/dispatch-sync [:rl/after [:rf.machine/start]])
        (let [scheduled-epoch (or (get-in (snapshot :rl/after)
                                          [:data :rf/after-epoch [:loading]])
                                  0)]
          (rf/dispatch-sync [:rl/after [:done]])
          (is (= :ready (:state (snapshot :rl/after))))
          (reset! traces [])
          ;; The originally-scheduled 30000ms timer (carrying the old
          ;; epoch + [:loading] decl-path) now fires — STALE.
          (rf/dispatch-sync [:rl/after
                             [:rf.machine.timer/after-elapsed 30000 scheduled-epoch [:loading]]])
          ;; Conformance: the app target (the :timed-out transition) does
          ;; NOT run — the stale timer is suppressed.
          (is (= :ready (:state (snapshot :rl/after)))
              "epoch-mismatch does NOT dispatch the app target (no transition)")
          ;; Conformance: the drop is recorded with the reply vocabulary.
          (let [stale (->> @traces
                           (filter #(= :rf.machine.timer/stale-after (:operation %)))
                           first)]
            (is (some? stale) ":rf.machine.timer/stale-after trace fired")
            ;; `:recovery` is hoisted to the top-level event by `trace/emit!`
            ;; (it is not a `:tags` key) — assert it there.
            (is (= :replaced-with-default (:recovery stale)))
            (let [tags (:tags stale)]
              ;; public trace shape preserved
              (is (= 30000 (:delay tags)))
              (is (= scheduled-epoch (:scheduled-epoch tags)))
              ;; reply-envelope vocabulary (Managed-Effects §9) — records suppressed
              (is (= :stale (:rf.reply/status tags)))
              (is (= :suppressed (:rf.reply/work-status tags)))
              (is (= :rf.machine.timer/after-epoch-mismatch (:rf.reply/stale-reason tags)))
              ;; the declaring path + epoch ARE the data-only suppression gate
              (let [corr (:rf.reply/correlation tags)]
                (is (= {:path [:loading] :rf/after-epoch scheduled-epoch}
                       (:carried corr))
                    "carried gate = declaring path + scheduled epoch")
                (is (= [:loading] (-> corr :current :path)))
                (is (not= scheduled-epoch (-> corr :current :rf/after-epoch))
                    "current epoch advanced — the gate mismatch")))))
        (finally (trace/unregister-listener! ::after-stale))))))

(deftest after-live-still-fires
  (testing "behavioural parity: a LIVE :after timer still fires its transition (lowering did not perturb the live path)"
    (rf/reg-machine :rl/after-live
      {:initial :loading
       :states  {:loading {:after {30000 :timed-out}}
                 :timed-out {}}})
    (rf/dispatch-sync [:rl/after-live [:rf.machine/start]])
    (let [epoch (or (get-in (snapshot :rl/after-live) [:data :rf/after-epoch [:loading]]) 0)]
      (rf/dispatch-sync [:rl/after-live
                         [:rf.machine.timer/after-elapsed 30000 epoch [:loading]]])
      (is (= :timed-out (:state (snapshot :rl/after-live)))
          "the matching (live) epoch drives the transition unchanged"))))

;; ===========================================================================
;; (2) spawned-actor completion — canonical reply drives :on-done / :on-error
;; ===========================================================================

(deftest spawned-success-drives-on-done-and-emits-reply-trace
  (testing "a child reaching a plain :final? leaf forms a :status :ok reply, drives :on-done with (:value reply), and rides the reply facts on :rf.machine/done"
    (let [traces (capture-traces ::done-ok)]
      (try
        (rf/reg-machine :rl/child
          {:initial :running
           :data    {}
           :states  {:running {:on {:finish {:target :done
                                             :action (fn [{data :data ev :event}]
                                                       {:data (assoc data :token (second ev))})}}}
                     :done    {:final? true :output-key :token}}})
        (rf/reg-machine :rl/parent
          {:initial :idle
           :data    {:token-from-child nil}
           :states  {:idle {:on {:go :working}}
                     :working
                     {:spawn {:machine-id :rl/child
                              :on-done    (fn [{data :data result :result}]
                                            (assoc data :token-from-child result))}}}})
        (rf/dispatch-sync [:rl/parent [:go]])
        ;; The child was spawned as :rl/child#1 under [:working].
        (rf/dispatch-sync [:rl/child#1 [:finish :secret-token]])
        ;; Public :on-done semantics preserved — the parent's :data was
        ;; updated with the child's :output-key result.
        (is (= :secret-token (get-in (snapshot :rl/parent) [:data :token-from-child]))
            ":on-done ran with the canonical reply's :value")
        (is (nil? (snapshot :rl/child#1)) "child auto-destroyed on :final?")
        ;; Reply-envelope facts ride the :rf.machine/done trace.
        (let [done (->> @traces
                        (filter #(= :rf.machine/done (:operation %)))
                        first)]
          (is (some? done) ":rf.machine/done trace fired")
          (let [tags (:tags done)]
            ;; public shape preserved
            (is (= :rl/child#1 (:machine-id tags)))
            (is (= :secret-token (:output tags)))
            (is (false? (:error? tags)))
            ;; reply-envelope vocabulary
            (is (= :ok (:rf.reply/status tags)))
            (is (= :completed (:rf.reply/work-status tags)))
            (is (= [:rf.work/machine :rl/child#1 [:working] 1]
                   (:rf.reply/work-id tags))
                "canonical machine work-id")))
        (finally (trace/unregister-listener! ::done-ok))))))

(deftest spawned-error-drives-on-error-transition
  (testing "a child reaching an :error? terminal forms a :status :error reply and drives the parent's :on-error TRANSITION (raw payload on :event preserved)"
    (let [traces (capture-traces ::done-err)]
      (try
        (rf/reg-machine :rl/echild
          {:initial :running
           :data    {}
           :states  {:running {:on {:fail {:target :failed
                                          :action (fn [{data :data ev :event}]
                                                    {:data (assoc data :reason (second ev))})}}}
                     :failed  {:final? true :error? true :output-key :reason}}})
        (rf/reg-machine :rl/eparent
          {:initial :idle
           :data    {:err nil}
           :states  {:idle {:on {:go :working}}
                     :working
                     {:spawn {:machine-id :rl/echild
                              :on-done    (fn [{data :data}] data)
                              ;; `:on-error` is a TRANSITION action — it returns
                              ;; the `{:data …}` effect map (not a bare data
                              ;; map, which is the `:on-done` callback's shape).
                              :on-error   {:target :error
                                           :action (fn [{data :data ev :event}]
                                                     ;; ev = [:rf.machine.spawn/error <invoke-id> <error>]
                                                     {:data (assoc data :err (nth ev 2))})}}}
                     :error {}}})
        (rf/dispatch-sync [:rl/eparent [:go]])
        (rf/dispatch-sync [:rl/echild#1 [:fail :bad-creds]])
        ;; Public :on-error semantics preserved — the parent transitioned
        ;; to :error and the RAW error payload reached the transition's
        ;; :event (NOT the reply-map's wrapped :error).
        (is (= :error (:state (snapshot :rl/eparent)))
            ":on-error transition fired (control flow)")
        (is (= :bad-creds (get-in (snapshot :rl/eparent) [:data :err]))
            "the raw error payload reached the parent transition's :event")
        (is (nil? (snapshot :rl/echild#1)) "error-terminal child auto-destroyed")
        ;; Reply-envelope facts on :rf.machine/done classify it :error.
        (let [done (->> @traces
                        (filter #(= :rf.machine/done (:operation %)))
                        first)]
          (is (some? done))
          (let [tags (:tags done)]
            (is (true? (:error? tags)))
            (is (= :error (:rf.reply/status tags)))
            (is (= :failed (:rf.reply/work-status tags)))))
        (finally (trace/unregister-listener! ::done-err))))))
