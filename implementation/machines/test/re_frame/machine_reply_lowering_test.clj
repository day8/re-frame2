(ns re-frame.machine-reply-lowering-test
  "Conformance: the two machine async completions share the uniform
  reply-envelope status/trace vocabulary (EP-0011 §Machine Completion /
  §Timer Reply; Managed-Effects §The uniform reply envelope).
  An INTERNAL LOWERING beneath the public statechart API (`:on-done` /
  `:on-error` / `:after` / actor-destroy).

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
            [re-frame.frame :as rf.frame]
            ;; Loading `re-frame.machines` installs the late-bind hooks
            ;; (`reg-machine`, the `:rf.machine/spawn` / `:rf.machine/destroy`
            ;; / `:rf.machine/after-*` reserved fxs) the runtime needs.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- capture-traces [id]
  (let [a (atom [])]
    (rf.trace.tooling/register-listener! id (fn [ev] (swap! a conj ev)))
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
            ;; `:recovery` is hoisted to the top-level event by `rf.trace/emit!`
            ;; (it is not a `:tags` key) — assert it there.
            (is (= :replaced-with-default (:recovery stale)))
            (let [tags (:tags stale)]
              ;; public trace shape
              (is (= 30000 (:delay tags)))
              (is (= scheduled-epoch (:scheduled-epoch tags)))
              ;; reply-envelope vocabulary (Managed-Effects §9) — records suppressed
              (is (= :stale (:rf.reply/status tags)))
              (is (= :suppressed (:rf.reply/work-status tags)))
              (is (= :rf.machine.timer/after-epoch-mismatch (:rf.reply/stale-reason tags)))
              ;; the canonical :rf.reply/work-id joins the uniform work/reply
              ;; rows (the timer work-id keyed on the SCHEDULED epoch — the
              ;; timer's attempt identity). The pick-transition stale `match`
              ;; carries the owning actor INSTANCE under `:actor-id`, so the
              ;; logical-id is `[<actor-id> & <decl-path>]` — the timer's full
              ;; actor-scoped identity, not the bare declaring path.
              ;; The reply-envelope work identity rides ONLY as
              ;; :rf.reply/work-id; there is no bare :work/id duplicate.
              (is (= [:rf.work/timer [:rl/after :loading] scheduled-epoch]
                     (:rf.reply/work-id tags))
                  "canonical timer :rf.reply/work-id on the stale-after trace (actor-scoped)")
              (is (not (contains? tags :work/id))
                  "no bare :work/id duplicate on the reply-envelope row")
              (is (= :timer (:rf.reply/work-kind tags)))
              ;; the declaring path + epoch ARE the data-only suppression gate
              (let [corr (:rf.reply/correlation tags)]
                (is (= {:path [:loading] :rf/after-epoch scheduled-epoch}
                       (:carried corr))
                    "carried gate = declaring path + scheduled epoch")
                (is (= [:loading] (-> corr :current :path)))
                (is (not= scheduled-epoch (-> corr :current :rf/after-epoch))
                    "current epoch advanced — the gate mismatch")))))
        (finally (rf.trace.tooling/unregister-listener! ::after-stale))))))

(deftest after-live-still-fires
  (testing "control: a LIVE :after timer fires its transition (the stale gate leaves the live path alone)"
    (rf/reg-machine :rl/after-live
      {:initial :loading
       :states  {:loading {:after {30000 :timed-out}}
                 :timed-out {}}})
    (rf/dispatch-sync [:rl/after-live [:rf.machine/start]])
    (let [epoch (or (get-in (snapshot :rl/after-live) [:data :rf/after-epoch [:loading]]) 0)]
      (rf/dispatch-sync [:rl/after-live
                         [:rf.machine.timer/after-elapsed 30000 epoch [:loading]]])
      (is (= :timed-out (:state (snapshot :rl/after-live)))
          "the matching (live) epoch drives the transition"))))

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
        ;; Public :on-done semantics — the parent's :data is updated with
        ;; the child's :output-key result.
        (is (= :secret-token (get-in (snapshot :rl/parent) [:data :token-from-child]))
            ":on-done ran with the canonical reply's :value")
        (is (nil? (snapshot :rl/child#1)) "child auto-destroyed on :final?")
        ;; Reply-envelope facts ride the :rf.machine/done trace.
        (let [done (->> @traces
                        (filter #(= :rf.machine/done (:operation %)))
                        first)]
          (is (some? done) ":rf.machine/done trace fired")
          (let [tags (:tags done)]
            ;; public shape
            (is (= :rl/child#1 (:actor-id tags)))
            (is (= :secret-token (:output tags)))
            (is (false? (:error? tags)))
            ;; reply-envelope vocabulary
            (is (= :ok (:rf.reply/status tags)))
            (is (= :completed (:rf.reply/work-status tags)))
            ;; the CANONICAL :rf.reply/work-id is stamped so Xray's uniform
            ;; work/reply grouping joins this spawned-actor completion.
            ;; There is no bare :work/id duplicate.
            (is (= [:rf.work/machine :rl/child#1 [:working] 1]
                   (:rf.reply/work-id tags))
                "canonical machine :rf.reply/work-id join key on the done trace")
            (is (not (contains? tags :work/id))
                "no bare :work/id duplicate on the reply-envelope done trace")
            (is (= :machine (:rf.reply/work-kind tags)))))
        (finally (rf.trace.tooling/unregister-listener! ::done-ok))))))

(deftest spawned-error-drives-on-error-transition
  (testing "a child reaching an :error? terminal forms a :status :error reply and drives the parent's :on-error TRANSITION (the raw payload reaches :event)"
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
        ;; Public :on-error semantics — the parent transitioned
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
        (finally (rf.trace.tooling/unregister-listener! ::done-err))))))

;; ===========================================================================
;; (3) spawn-stale — the child's completion reaches a DESTROYED parent.
;;     The PRODUCTION path (not the pure builder): the child reaches its
;;     :final? leaf while its parent is live, so its carrier is minted and
;;     queued, and the parent is destroyed before the carrier is delivered.
;;     The :on-done callback MUST NOT run, the parent MUST NOT be re-created
;;     from its surviving definition, and the parent's boundary drops the
;;     carrier with the reply-envelope stale vocabulary. This is the
;;     spawn-path analogue of (1)'s :after epoch-mismatch production test.
;;
;;     A declarative child cannot itself reach :final? after its parent is
;;     destroyed, because the parent's destroy ends it. The finality-time
;;     stale classification is pinned against a frame value that holds a
;;     child whose parent is not live, as installing such a restored value
;;     would.
;; ===========================================================================

(defn- reg-kick!
  "A plain handler that queues `events` at the back of the router queue, in
  order — the producer for the carrier races below."
  []
  (rf/reg-event ::kick (fn [_ [_ events]] {:fx (mapv (fn [e] [:dispatch e]) events)})))

(defn- reg-token-pair!
  "A child that finishes on `[:finish <token>]` with `<token>` as its output,
  under a parent that spawns it on `:go`, folds its result with `:on-done`,
  and destroys ITSELF on `:drop`."
  [child-id parent-id]
  (rf/reg-machine child-id
    {:initial :running
     :data    {}
     :states  {:running {:on {:finish {:target :done
                                       :action (fn [{data :data ev :event}]
                                                 {:data (assoc data :token (second ev))})}}}
               :done    {:final? true :output-key :token}}})
  (rf/reg-machine parent-id
    {:initial :idle
     :data    {:token-from-child :untouched}
     :states  {:idle {:on {:go :working}}
               :working
               {:on    {:drop {:action (fn [_]
                                         {:fx [[:rf.machine/destroy parent-id]]})}}
                :spawn {:machine-id child-id
                        :on-done    (fn [{data :data result :result}]
                                      ;; If this EVER runs for the stale
                                      ;; case the assertions below fail.
                                      (assoc data :token-from-child result))}}}}))

(defn- stale-completions [traces]
  (->> traces
       (filter #(= :rf.machine.spawn/stale-completion (:operation %)))
       (mapv :tags)))

(defn- drop-instance!
  "Remove `actor-id`'s snapshot from the frame value and nothing else, as
  installing a restored value that holds its children but not it would."
  [actor-id]
  (rf.frame/swap-runtime-db! :rf/default
                             #(update-in % [:rf.runtime/machines :snapshots] dissoc actor-id)))

(deftest spawn-stale-parent-destroyed-before-child-suppresses-with-reply-vocabulary
  (testing "a child's done carrier that reaches its parent AFTER the parent was destroyed is STALE: :on-done does NOT run, the parent is NOT re-created, and the :rf.machine.spawn/stale-completion trace carries :status :stale / :rf.reply/work-status :suppressed"
    (let [traces (capture-traces ::spawn-stale)]
      (try
        (reg-kick!)
        (reg-token-pair! :rl/schild :rl/sparent)
        (rf/dispatch-sync [:rl/sparent [:go]])
        (is (= :running (:state (snapshot :rl/schild#1))) "child mid-flight")
        (reset! traces [])
        ;; The child finishes while the parent is live, so its carrier is
        ;; minted and queued BEHIND the parent's own :drop.
        (rf/dispatch-sync [::kick [[:rl/schild#1 [:finish :secret-token]]
                                   [:rl/sparent [:drop]]]])
        (is (nil? (snapshot :rl/schild#1)) "the finished child auto-destroyed")
        (is (nil? (snapshot :rl/sparent))
            ":on-done did NOT resurrect or mutate the destroyed parent")
        (let [stale (stale-completions @traces)
              tags  (first stale)]
          (is (= 1 (count stale)) "the carrier is dropped with exactly one stale trace")
          (is (= :rl/sparent (:actor-id tags)))
          (is (= [:working] (:invoke-id tags)))
          (is (= :done (:kind tags)))
          (is (= :stale (:rf.reply/status tags)))
          (is (= :suppressed (:rf.reply/work-status tags)))
          (is (= :rf.machine.spawn/state-exited (:rf.reply/stale-reason tags))))
        (finally (rf.trace.tooling/unregister-listener! ::spawn-stale))))))

(deftest spawn-child-finishing-with-no-live-parent-is-stale-at-finality
  (testing "a declarative child reaching :final? while its parent is not live is STALE at finality: no carrier is minted, and the :rf.machine/done trace carries :status :stale / :rf.reply/work-status :suppressed / :rf.machine/actor-not-live + the carried/current generation gate"
    (let [traces (capture-traces ::spawn-stale-finality)]
      (try
        (reg-token-pair! :rl/fchild :rl/fparent)
        (rf/dispatch-sync [:rl/fparent [:go]])
        (is (= :running (:state (snapshot :rl/fchild#1))) "child mid-flight")
        (drop-instance! :rl/fparent)
        (is (nil? (snapshot :rl/fparent)) "the parent is not live")
        (reset! traces [])
        (rf/dispatch-sync [:rl/fchild#1 [:finish :secret-token]])
        (is (nil? (snapshot :rl/fchild#1)) "stale-completing child auto-destroyed")
        (is (nil? (snapshot :rl/fparent))
            "no carrier re-created the parent from its surviving definition")
        (is (empty? (stale-completions @traces))
            "no carrier reached the parent's boundary to be dropped there")
        (let [done (->> @traces
                        (filter #(= :rf.machine/done (:operation %)))
                        first)]
          (is (some? done) ":rf.machine/done trace fired for the stale completion")
          (let [tags (:tags done)]
            ;; public shape
            (is (= :rl/fchild#1 (:actor-id tags)))
            (is (false? (:error? tags)) "a plain final leaf is not an error leaf")
            ;; reply-envelope vocabulary (Managed-Effects §9) — records STALE/suppressed
            (is (= :stale (:rf.reply/status tags))
                "the canonical :status :stale reply IS produced via the substrate")
            (is (= :suppressed (:rf.reply/work-status tags))
                "the ledger terminal for a stale late completion")
            (is (= :rf.machine/actor-not-live (:rf.reply/stale-reason tags)))
            (is (= [:rf.work/machine :rl/fchild#1 [:working] 1]
                   (:rf.reply/work-id tags))
                "canonical machine work-id (carried generation 1 off #1)")
            ;; the carried/current generation pair IS the supersession gate —
            ;; carried (off the finishing actor's id) vs current (the live
            ;; spawn-slot occupant, which a non-live parent has none of → nil).
            (let [corr (:rf.reply/correlation tags)]
              (is (= 1 (-> corr :generation :carried))
                  "carried generation parsed off :rl/fchild#1")
              (is (nil? (-> corr :generation :current))
                  "current generation is nil — no live parent, no live counterpart")
              (is (= :rl/fchild#1 (:actor-id corr))))))
        (finally (rf.trace.tooling/unregister-listener! ::spawn-stale-finality))))))

(deftest spawn-live-parent-still-drives-on-done
  (testing "control: with the parent STILL alive, the child's completion is :ok and :on-done runs (stale detection leaves the live path alone)"
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
;; (3b) the FAILURE half of (3).
;;
;;      (3) pins the SUCCESS carrier. The two FAILURE carriers — the `:error?`
;;      `:final?` leaf and an uncaught child ACTION EXCEPTION (Spec 005
;;      §`:on-error` "Two failure triggers") — both mint the reserved
;;      `[:rf.machine.spawn/error …]` event into the parent. A destroyed
;;      singleton keeps its `reg-machine` DEFINITION, so such a carrier
;;      arriving at its address RESOLVES there, and D5 lazy re-creation would
;;      synthesise a fresh initial snapshot — RESURRECTING an actor the app
;;      destroyed, to hand it a dead child's failure. Spec 005 §Async
;;      completions §Stale suppression says the `:on-done` / `:on-error`
;;      routing MUST NOT run for such a late completion.
;;
;;      Both cases are driven through PUBLIC `reg-machine` /
;;      `dispatch-sync` only — no registry surgery, no mocked lifecycle.
;;      The fence does not narrow D5: it covers FRAMEWORK-OWNED failure
;;      delivery, never an ordinary authored event, which re-creates the
;;      address (pinned in machine_definition_survives_teardown_cljs_test).
;; ===========================================================================

(defn- reg-xjee-pair!
  "A child that can fail two ways (`:fail` → an `:error?` `:final?` leaf;
  `:throw` → an action that throws) under a parent that declares
  `:spawn :on-error` and can destroy ITSELF on `:drop`."
  [child-id parent-id]
  (rf/reg-machine child-id
    {:initial :running
     :data    {}
     :states  {:running {:on {:fail  {:target :failed}
                              :throw {:action (fn [_]
                                                (throw (ex-info "child failure" {})))}}}
               :failed  {:final? true :error? true}}})
  (rf/reg-machine parent-id
    {:initial :idle
     :data    {}
     :states  {:idle {:on {:go :working}}
               :working
               {:on    {:drop {:action (fn [_]
                                         {:fx [[:rf.machine/destroy parent-id]]})}}
                :spawn {:machine-id child-id :on-error {:target :error}}}
               :error {}}}))

(deftest stale-error-final-leaf-does-not-recreate-a-destroyed-parent
  (testing "a child's :error? :final? leaf carrier that reaches its parent AFTER the parent was destroyed is STALE: :on-error does NOT run, the parent is NOT resurrected from its initial snapshot, and the carrier is dropped with the stale completion vocabulary"
    (let [traces (capture-traces ::xjee-error-final)]
      (try
        (reg-kick!)
        (reg-xjee-pair! :rl/xchild :rl/xparent)
        (rf/dispatch-sync [:rl/xparent [:go]])
        (is (= :working (:state (snapshot :rl/xparent))) "parent spawned the child")
        (is (some? (snapshot :rl/xchild#1)) "child alive and mid-flight")
        (reset! traces [])
        ;; The child fails while the parent is live, so its error carrier is
        ;; minted and queued BEHIND the parent's own :drop.
        (rf/dispatch-sync [::kick [[:rl/xchild#1 [:fail]] [:rl/xparent [:drop]]]])
        (is (nil? (snapshot :rl/xchild#1)) "the error-terminal child auto-destroyed")
        (is (nil? (snapshot :rl/xparent))
            "the stale failure did NOT resurrect the destroyed parent")
        (let [stale (stale-completions @traces)
              tags  (first stale)]
          (is (= 1 (count stale)) "the carrier is dropped with exactly one stale trace")
          (is (= :rl/xparent (:actor-id tags)))
          (is (= :error (:kind tags)) "it is the failure carrier that was dropped")
          (is (= :stale (:rf.reply/status tags)))
          (is (= :suppressed (:rf.reply/work-status tags))))
        (finally (rf.trace.tooling/unregister-listener! ::xjee-error-final))))))

(deftest stale-child-action-exception-does-not-recreate-a-destroyed-parent
  (testing "an uncaught child ACTION EXCEPTION whose error carrier reaches its parent AFTER the parent was destroyed routes nowhere: the parent is NOT resurrected and the carrier is dropped as stale"
    (let [traces (capture-traces ::xjee-action-throw)]
      (try
        (reg-kick!)
        (reg-xjee-pair! :rl/tchild :rl/tparent)
        (rf/dispatch-sync [:rl/tparent [:go]])
        (is (some? (snapshot :rl/tchild#1)) "child alive and mid-flight")
        (reset! traces [])
        ;; The child's action throws while the parent is live: the macrostep
        ;; aborts atomically (the child keeps its pre-event snapshot) and the
        ;; error carrier is queued BEHIND the parent's own :drop, which then
        ;; ends the still-live child with it.
        (rf/dispatch-sync [::kick [[:rl/tchild#1 [:throw]] [:rl/tparent [:drop]]]])
        (is (nil? (snapshot :rl/tparent))
            "the stale action exception did NOT resurrect the destroyed parent")
        (is (= [[:rl/tchild#1 :explicit :rl/tparent]]
               (->> @traces
                    (filter #(= :rf.machine/destroyed (:operation %)))
                    (map :tags)
                    (filter #(= :rl/tchild#1 (:actor-id %)))
                    (mapv (juxt :actor-id :reason :parent-id))))
            "the throwing child rolled back rather than being torn down, and ended with its parent")
        (let [stale (stale-completions @traces)]
          (is (= 1 (count stale)) "the carrier is dropped with exactly one stale trace")
          (is (= :error (:kind (first stale)))))
        (finally (rf.trace.tooling/unregister-listener! ::xjee-action-throw))))))

(deftest live-parent-still-takes-on-error-from-both-failure-triggers
  (testing "the positive control for BOTH fences: with the parent ALIVE, an :error? leaf AND an uncaught action exception each drive the :on-error transition"
    (reg-xjee-pair! :rl/lchild :rl/lparent)
    (rf/dispatch-sync [:rl/lparent [:go]])
    (rf/dispatch-sync [:rl/lchild#1 [:fail]])
    (is (= :error (:state (snapshot :rl/lparent)))
        "live parent: the error leaf fired :on-error")
    (reg-xjee-pair! :rl/l2child :rl/l2parent)
    (rf/dispatch-sync [:rl/l2parent [:go]])
    (rf/dispatch-sync [:rl/l2child#1 [:throw]])
    (is (= :error (:state (snapshot :rl/l2parent)))
        "live parent: the action exception fired :on-error")))

;; ===========================================================================
;; (4) causal :completed-at threading. A spawned machine
;;     completion can mutate durable parent-machine data (:on-done writes
;;     the parent's :data). Per spec/Managed-Effects.md §155/§231 a
;;     completion that affects durable state MUST carry causal completion
;;     metadata — the ROUTER's `:rf.cofx` `:rf/time-ms` (EP-0010, the
;;     single causal-boundary clock read), NOT an ambient host-clock read.
;;     The production finalize path threads that world-input time into the
;;     reply-ctx so the done reply + `:rf.machine/done` trace carry the
;;     causal `:completed-at`.
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
        ;; :on-done ran with the canonical value.
        (is (= :secret-token (get-in (snapshot :rl/cparent) [:data :token-from-child]))
            ":on-done mutated the parent's durable :data (the §155/§231 case)")
        (let [done (->> @traces
                        (filter #(= :rf.machine/done (:operation %)))
                        first)]
          (is (some? done) ":rf.machine/done trace fired")
          (let [tags (:tags done)]
            ;; The causal completion timestamp rides the
            ;; done trace — the supplied :rf.cofx :time-ms VERBATIM,
            ;; not an ambient clock read. It rides ONLY as the
            ;; reply-envelope :rf.reply/completed-at; there is no bare
            ;; :completed-at duplicate.
            (is (= completed-at (:rf.reply/completed-at tags))
                "the causal :rf.cofx :time-ms rides the done reply trace")
            (is (not (contains? tags :completed-at))
                "no bare :completed-at duplicate on the reply-envelope done trace")
            ;; sanity: the rest of the canonical envelope is intact.
            (is (= :ok (:rf.reply/status tags)))
            (is (= :completed (:rf.reply/work-status tags)))))
        (finally (rf.trace.tooling/unregister-listener! ::completed-at))))))

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
          ;; an explicit nil would silently lose the fact. The bare
          ;; :completed-at NEVER rides the reply-envelope row; the fact lives
          ;; only under :rf.reply/completed-at (omitted when no causal time,
          ;; never nil).
          (is (not (contains? tags :completed-at))
              "no bare :completed-at duplicate on the reply-envelope done trace")
          (is (not (and (contains? tags :rf.reply/completed-at)
                        (nil? (:rf.reply/completed-at tags))))
              ":rf.reply/completed-at is never an explicit nil sentinel"))
        (finally (rf.trace.tooling/unregister-listener! ::no-completed-at))))))
