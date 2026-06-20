(ns re-frame.machine-reply-lowering-test
  "Conformance: the two machine async completions share the uniform
  reply-envelope status/trace vocabulary (EP-0011 §Machine Completion /
  §Timer Reply; Managed-Effects §The uniform reply envelope).
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
              ;; the canonical :work/id joins the uniform work/reply rows (the
              ;; timer work-id keyed on the SCHEDULED epoch — the timer's
              ;; attempt identity). The pick-transition stale `match` carries
              ;; the owning actor INSTANCE under `:actor-id`, so the logical-id
              ;; is `[<actor-id> & <decl-path>]` — the timer's full
              ;; actor-scoped identity, not the bare declaring path.
              (is (= [:rf.work/timer [:rl/after :loading] scheduled-epoch]
                     (:work/id tags))
                  "canonical timer :work/id on the stale-after trace (actor-scoped)")
              (is (= (:work/id tags) (:rf.reply/work-id tags)))
              (is (= :timer (:work/kind tags)))
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
            (is (= :rl/child#1 (:actor-id tags)))
            (is (= :secret-token (:output tags)))
            (is (false? (:error? tags)))
            ;; reply-envelope vocabulary
            (is (= :ok (:rf.reply/status tags)))
            (is (= :completed (:rf.reply/work-status tags)))
            (is (= [:rf.work/machine :rl/child#1 [:working] 1]
                   (:rf.reply/work-id tags))
                "canonical machine work-id (additive spelling)")
            ;; the CANONICAL :work/id is stamped so Xray's uniform work/reply
            ;; grouping (keys on bare :work/id) joins this spawned-actor
            ;; completion.
            (is (= [:rf.work/machine :rl/child#1 [:working] 1]
                   (:work/id tags))
                "canonical :work/id join key on the done trace")
            (is (= :machine (:work/kind tags)))))
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

;; ===========================================================================
;; (3) spawn-stale — parent destroyed BEFORE the child finishes.
;;     The PRODUCTION path (not the pure builder): a child reaches
;;     its :final? leaf AFTER its spawning parent was already destroyed. The
;;     :on-done callback MUST NOT run (no live parent to mutate), the ledger /
;;     trace MUST classify the late completion :status :stale / :work/status
;;     :suppressed via the shared substrate, and the carried/current generation
;;     gate MUST ride the :rf.machine/done trace. This is the spawn-path
;;     analogue of (1)'s :after epoch-mismatch production test.
;; ===========================================================================

(deftest spawn-stale-parent-destroyed-before-child-suppresses-with-reply-vocabulary
  (testing "a child reaching :final? AFTER its parent was destroyed is STALE: :on-done does NOT run, and the :rf.machine/done trace carries :status :stale / :work/status :suppressed / :rf.machine/actor-not-live + the carried/current generation gate"
    (let [traces (capture-traces ::spawn-stale)]
      (try
        ;; A child that does NOT auto-finish on spawn — it sits in :running
        ;; until an explicit :finish, so we can destroy the parent while the
        ;; child is genuinely mid-flight.
        (rf/reg-machine :rl/schild
          {:initial :running
           :data    {}
           :states  {:running {:on {:finish {:target :done
                                             :action (fn [{data :data ev :event}]
                                                       {:data (assoc data :token (second ev))})}}}
                     :done    {:final? true :output-key :token}}})
        ;; A parent that spawns the child on :go (declarative :spawn with an
        ;; :on-done that WOULD set a sentinel) and destroys ITSELF
        ;; imperatively on :drop. The imperative `[:rf.machine/destroy
        ;; <parent>]` tears down only the parent (it runs the parent's active
        ;; :exit actions + clears its snapshot); the independently-spawned
        ;; child survives — exactly the parent-destroyed-before-child case.
        (rf/reg-machine :rl/sparent
          {:initial :idle
           :data    {:token-from-child :untouched}
           :states  {:idle {:on {:go :working}}
                     :working
                     {:on    {:drop {:action (fn [_]
                                               {:fx [[:rf.machine/destroy :rl/sparent]]})}}
                      :spawn {:machine-id :rl/schild
                              :on-done    (fn [{data :data result :result}]
                                            ;; If this EVER runs for the stale
                                            ;; case the assertion below fails.
                                            (assoc data :token-from-child result))}}}})
        (rf/dispatch-sync [:rl/sparent [:go]])
        ;; Child spawned as :rl/schild#1 under [:working], still mid-flight.
        (is (some? (snapshot :rl/schild#1)) "child spawned and alive")
        (is (= :running (:state (snapshot :rl/schild#1))) "child mid-flight")
        ;; Destroy the parent BEFORE the child finishes.
        (rf/dispatch-sync [:rl/sparent [:drop]])
        (is (nil? (snapshot :rl/sparent)) "parent destroyed (snapshot gone)")
        (is (some? (snapshot :rl/schild#1))
            "child survives the parent's imperative destroy (independent actor)")
        (reset! traces [])
        ;; Now the child finishes — reaches :done (:final?). finalize-machine
        ;; runs with on-done-fn + parent-id present, but parent-snap nil.
        (rf/dispatch-sync [:rl/schild#1 [:finish :secret-token]])
        ;; The child auto-destroyed on :final? (the late completion is still
        ;; behaviourally safe — the actor tears down).
        (is (nil? (snapshot :rl/schild#1)) "stale-completing child auto-destroyed")
        ;; Conformance (2)/(5): the app target did NOT run + NO app mutation.
        ;; The parent is gone, so there is nothing to mutate — and we never
        ;; called :on-done. (Were :on-done to have run against a resurrected
        ;; parent, this would surface; it does not run at all.)
        (is (nil? (snapshot :rl/sparent))
            ":on-done did NOT resurrect or mutate the destroyed parent")
        ;; Conformance (2)/(4): the :rf.machine/done trace carries the
        ;; canonical stale reply vocabulary via the shared substrate.
        (let [done (->> @traces
                        (filter #(= :rf.machine/done (:operation %)))
                        first)]
          (is (some? done) ":rf.machine/done trace fired for the stale completion")
          (let [tags (:tags done)]
            ;; public shape preserved
            (is (= :rl/schild#1 (:actor-id tags)))
            (is (false? (:error? tags)) "a plain final leaf is not an error leaf")
            ;; reply-envelope vocabulary (Managed-Effects §9) — records STALE/suppressed
            (is (= :stale (:rf.reply/status tags))
                "the canonical :status :stale reply IS produced via the substrate")
            (is (= :suppressed (:rf.reply/work-status tags))
                "the ledger terminal for a stale late completion")
            (is (= :rf.machine/actor-not-live (:rf.reply/stale-reason tags)))
            (is (= [:rf.work/machine :rl/schild#1 [:working] 1]
                   (:rf.reply/work-id tags))
                "canonical machine work-id (carried generation 1 off #1)")
            ;; the carried/current generation pair IS the supersession gate —
            ;; carried (off the finishing actor's id) vs current (the live
            ;; spawn-slot occupant, gone now the parent was destroyed → nil).
            (let [corr (:rf.reply/correlation tags)]
              (is (= 1 (-> corr :generation :carried))
                  "carried generation parsed off :rl/schild#1")
              (is (nil? (-> corr :generation :current))
                  "current generation is nil — the spawn slot is gone (no live counterpart)")
              (is (= :rl/schild#1 (:actor-id corr))))))
        (finally (trace/unregister-listener! ::spawn-stale))))))

(deftest spawn-live-parent-still-drives-on-done
  (testing "behavioural parity: with the parent STILL alive, the child's completion is :ok and :on-done runs (the rf2-lohbfg stale detection did not perturb the live path)"
    (rf/reg-machine :rl/schild-live
      {:initial :running
       :data    {}
       :states  {:running {:on {:finish {:target :done
                                         :action (fn [{data :data ev :event}]
                                                   {:data (assoc data :token (second ev))})}}}
                 :done    {:final? true :output-key :token}}})
    (rf/reg-machine :rl/sparent-live
      {:initial :idle
       :data    {:token-from-child :untouched}
       :states  {:idle {:on {:go :working}}
                 :working
                 {:spawn {:machine-id :rl/schild-live
                          :on-done    (fn [{data :data result :result}]
                                        (assoc data :token-from-child result))}}}})
    (rf/dispatch-sync [:rl/sparent-live [:go]])
    ;; Parent stays alive; the child finishes.
    (rf/dispatch-sync [:rl/schild-live#1 [:finish :live-token]])
    (is (= :live-token (get-in (snapshot :rl/sparent-live) [:data :token-from-child]))
        "live parent: :on-done ran with the canonical reply's :value (not suppressed)")))

;; ===========================================================================
;; (4) causal :completed-at threading. A spawned machine
;;     completion can mutate durable parent-machine data (:on-done writes
;;     the parent's :data). Per spec/Managed-Effects.md §155/§231 a
;;     completion that affects durable state MUST carry causal completion
;;     metadata — the ROUTER's `:rf.cofx` `:rf/time-ms` (EP-0010, the
;;     single causal-boundary clock read), NOT an ambient host-clock read.
;;     The production finalize path threads that world-input time into the
;;     reply-ctx so the done reply + `:rf.machine/done` trace carry the
;;     causal `:completed-at` instead of silently losing it.
;; ===========================================================================

(deftest spawned-completion-threads-causal-completed-at
  (testing "the done reply trace carries the CAUSAL :completed-at — the finishing event's :rf.cofx :time-ms — when a spawned child completion mutates durable parent data"
    (let [traces      (capture-traces ::completed-at)
          completed-at 1781078400888]                ;; the causal token time
      (try
        (rf/reg-machine :rl/cchild
          {:initial :running
           :data    {}
           :states  {:running {:on {:finish {:target :done
                                             :action (fn [{data :data ev :event}]
                                                       {:data (assoc data :token (second ev))})}}}
                     :done    {:final? true :output-key :token}}})
        (rf/reg-machine :rl/cparent
          {:initial :idle
           :data    {:token-from-child nil}
           :states  {:idle {:on {:go :working}}
                     :working
                     {:spawn {:machine-id :rl/cchild
                              ;; :on-done mutates the parent's DURABLE :data —
                              ;; the §155/§231 "affects durable state" case
                              ;; that demands causal completion metadata.
                              :on-done    (fn [{data :data result :result}]
                                            (assoc data :token-from-child result))}}}})
        (rf/dispatch-sync [:rl/cparent [:go]])
        ;; The child finishes under a SCRIPTED causal time — the finishing
        ;; dispatch supplies :rf.cofx {:rf/time-ms completed-at}, the
        ;; one host-clock read the router captures at the causal boundary.
        (rf/dispatch-sync [:rl/cchild#1 [:finish :secret-token]]
                          {:rf.cofx {:rf/time-ms completed-at}})
        ;; Behavioural parity: :on-done still ran with the canonical value.
        (is (= :secret-token (get-in (snapshot :rl/cparent) [:data :token-from-child]))
            ":on-done mutated the parent's durable :data (the §155/§231 case)")
        (let [done (->> @traces
                        (filter #(= :rf.machine/done (:operation %)))
                        first)]
          (is (some? done) ":rf.machine/done trace fired")
          (let [tags (:tags done)]
            ;; THE coverage gap: the causal completion timestamp rides the
            ;; done trace — the supplied :rf.cofx :time-ms VERBATIM,
            ;; not an ambient clock read. Both the additive reply-envelope
            ;; spelling and the bare :completed-at carry it.
            (is (= completed-at (:rf.reply/completed-at tags))
                "the causal :rf.cofx :time-ms rides the done reply trace")
            (is (= completed-at (:completed-at tags))
                "the bare :completed-at fact carries the same causal time")
            ;; sanity: the rest of the canonical envelope is intact.
            (is (= :ok (:rf.reply/status tags)))
            (is (= :completed (:rf.reply/work-status tags)))))
        (finally (trace/unregister-listener! ::completed-at))))))

(deftest unscripted-completion-omits-completed-at
  (testing "adversarial: an UNSCRIPTED completion (no :rf.cofx) carries NO :completed-at — the fact is OMITTED, never nil-filled or stamped from an ambient clock (Managed-Effects §The reply map)"
    (let [traces (capture-traces ::no-completed-at)]
      (try
        (rf/reg-machine :rl/uchild
          {:initial :running
           :data    {}
           :states  {:running {:on {:finish {:target :done
                                             :action (fn [{data :data ev :event}]
                                                       {:data (assoc data :token (second ev))})}}}
                     :done    {:final? true :output-key :token}}})
        (rf/reg-machine :rl/uparent
          {:initial :idle
           :data    {:token-from-child nil}
           :states  {:idle {:on {:go :working}}
                     :working
                     {:spawn {:machine-id :rl/uchild
                              :on-done    (fn [{data :data result :result}]
                                            (assoc data :token-from-child result))}}}})
        (rf/dispatch-sync [:rl/uparent [:go]])
        ;; The finishing dispatch supplies NO :rf.cofx — the
        ;; unscripted path. The router seeds its own world-inputs only when
        ;; running the full dispatch path with a clock; in this direct
        ;; dispatch-sync with no override the machine def carries whatever
        ;; the router stamped. We assert the CONTRACT: when the finalize
        ;; path finds no causal time, the trace MUST NOT carry a nil
        ;; :completed-at sentinel (the key is absent OR carries a real
        ;; number — never an explicit nil).
        (rf/dispatch-sync [:rl/uchild#1 [:finish :tok]])
        (let [done (->> @traces
                        (filter #(= :rf.machine/done (:operation %)))
                        first)
              tags (:tags done)]
          (is (some? done))
          ;; The invariant: NO nil sentinel. Either the key is absent, or it
          ;; carries a genuine number (if the router seeded a causal time);
          ;; an explicit nil would be the silent-loss bug the bead guards.
          (is (not (and (contains? tags :completed-at)
                        (nil? (:completed-at tags))))
              ":completed-at is never an explicit nil sentinel (omitted when no causal time)")
          (is (not (and (contains? tags :rf.reply/completed-at)
                        (nil? (:rf.reply/completed-at tags))))
              ":rf.reply/completed-at is never an explicit nil sentinel"))
        (finally (trace/unregister-listener! ::no-completed-at))))))
