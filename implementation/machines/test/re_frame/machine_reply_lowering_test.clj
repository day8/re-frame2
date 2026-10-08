(ns re-frame.machine-reply-lowering-test
  "Machine async completions — a stale `:after`, a spawned child's finish, and a
  late completion carrier — lower onto the reply-envelope trace vocabulary
  without changing the public `:on-done` / `:on-error` / `:after` semantics."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- first-event [operation]
  (first (rf.machines.test-support/events-of operation)))

(deftest after-epoch-mismatch-suppresses-with-reply-vocabulary
  (rf/reg-machine :rl/after
    {:initial :loading
     :states  {:loading {:after {30000 :timed-out}
                         :on    {:done :ready}}
               :ready     {}
               :timed-out {}}})
  (rf/dispatch-sync [:rl/after [:rf.machine/start]])
  (let [epoch (or (get-in (snapshot :rl/after) [:data :rf/after-epoch [:loading]]) 0)]
    ;; :done exits :loading, so the originally scheduled timer is now stale.
    (rf/dispatch-sync [:rl/after [:done]])
    (rf/dispatch-sync [:rl/after [:rf.machine.timer/after-elapsed 30000 epoch [:loading]]])
    (is (= :ready (:state (snapshot :rl/after))) "the stale timer's transition does not fire")
    (let [stale (first-event :rf.machine.timer/stale-after)
          tags  (:tags stale)
          corr  (:rf.reply/correlation tags)]
      (is (= :replaced-with-default (:recovery stale)))
      (is (= {:delay                 30000
              :scheduled-epoch       epoch
              :rf.reply/status       :stale
              :rf.reply/work-status  :suppressed
              :rf.reply/stale-reason :rf.machine.timer/after-epoch-mismatch
              :rf.reply/work-kind    :timer
              :rf.reply/work-id      [:rf.work/timer [:rl/after :loading] epoch]}
             (select-keys tags [:delay :scheduled-epoch :rf.reply/status :rf.reply/work-status
                                :rf.reply/stale-reason :rf.reply/work-kind :rf.reply/work-id])))
      (is (= {:path [:loading] :rf/after-epoch epoch} (:carried corr))
          "the carried gate is the declaring path + scheduled epoch")
      (is (= [:loading] (-> corr :current :path)))
      (is (not= epoch (-> corr :current :rf/after-epoch)) "the current epoch advanced"))))

(deftest spawned-success-drives-on-done-and-emits-reply-trace
  (let [completed-at 1781078400888]
    (rf/reg-machine :rl/child
      {:initial :running
       :data    {}
       :states  {:running {:on {:finish {:target :done
                                         :action (fn [{data :data ev :event}]
                                                   {:data (assoc data :token (second ev))})}}}
                 :done    {:final? true :output-key :token}}})
    (rf/reg-machine :rl/parent
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id :rl/child
                                   :on-done    (fn [{data :data result :result}]
                                                 (assoc data :token-from-child result))}}}})
    (rf/dispatch-sync [:rl/parent [:go]])
    ;; The finishing dispatch supplies the causal clock read the completion carries.
    (rf/dispatch-sync [:rl/child#1 [:finish :secret-token]] {:rf.cofx {:rf/time-ms completed-at}})
    (is (= :secret-token (get-in (snapshot :rl/parent) [:data :token-from-child])))
    (is (= {:actor-id               :rl/child#1
            :output                 :secret-token
            :error?                 false
            :rf.reply/status        :ok
            :rf.reply/work-status   :completed
            :rf.reply/work-kind     :machine
            :rf.reply/work-id       [:rf.work/machine :rl/child#1 [:working] 1]
            :rf.reply/completed-at  completed-at}
           (select-keys (:tags (first-event :rf.machine/done))
                        [:actor-id :output :error? :rf.reply/status :rf.reply/work-status
                         :rf.reply/work-kind :rf.reply/work-id :rf.reply/completed-at])))))

(deftest spawned-error-drives-on-error-transition
  (rf/reg-machine :rl/echild
    {:initial :running
     :states  {:running {:on {:fail :failed}}
               :failed  {:final? true :error? true}}})
  (rf/reg-machine :rl/eparent
    {:initial :idle
     :states  {:idle    {:on {:go :working}}
               :working {:spawn {:machine-id :rl/echild :on-error {:target :error}}}
               :error   {}}})
  (rf/dispatch-sync [:rl/eparent [:go]])
  (rf/dispatch-sync [:rl/echild#1 [:fail]])
  (is (= :error (:state (snapshot :rl/eparent))))
  (is (= {:error? true :rf.reply/status :error :rf.reply/work-status :failed}
         (select-keys (:tags (first-event :rf.machine/done))
                      [:error? :rf.reply/status :rf.reply/work-status]))))

;; ---- late completion carriers ----------------------------------------------
;;
;; A destroyed singleton keeps its `reg-machine` DEFINITION, so a completion
;; carrier queued before the destroy RESOLVES at its address. Delivering it
;; would lazily re-create the parent from its initial snapshot to fold a dead
;; child's result. Each carrier must instead be dropped as stale, for all
;; three ways a child finishes (Spec 005 §Async completions §Stale suppression).

(defn- reg-pair!
  "A child that finishes on `:finish` (plain final), `:fail` (error final) or
  `:throw` (an action exception), under a parent that spawns it on `:go`,
  declares `:on-done` and `:on-error`, and destroys ITSELF on `:drop`."
  [child-id parent-id]
  (rf/reg-machine child-id
    {:initial :running
     :data    {}
     :states  {:running {:on {:finish :done
                              :fail   :failed
                              :throw  {:action (fn [_] (throw (ex-info "child failure" {})))}}}
               :done    {:final? true}
               :failed  {:final? true :error? true}}})
  (rf/reg-machine parent-id
    {:initial :idle
     :data    {}
     :states  {:idle    {:on {:go :working}}
               :working {:on    {:drop {:action (fn [_] {:fx [[:rf.machine/destroy parent-id]]})}}
                         :spawn {:machine-id child-id
                                 :on-done    (fn [{data :data}] (assoc data :folded? true))
                                 :on-error   {:target :error}}}
               :error   {}}}))

(deftest stale-carrier-does-not-recreate-a-destroyed-parent
  (rf/reg-event ::kick (fn [_ [_ events]] {:fx (mapv (fn [e] [:dispatch e]) events)}))
  (doseq [[child parent trigger kind] [[:rl/schild :rl/sparent :finish :done]
                                       [:rl/xchild :rl/xparent :fail   :error]
                                       [:rl/tchild :rl/tparent :throw  :error]]
          :let [instance (keyword "rl" (str (name child) "#1"))]]
    (reg-pair! child parent)
    (rf/dispatch-sync [parent [:go]])
    (rf.machines.test-support/reset-captured!)
    ;; The child finishes while its parent is live, so the carrier is minted and
    ;; queued BEHIND the parent's own :drop.
    (rf/dispatch-sync [::kick [[instance [trigger]] [parent [:drop]]]])
    (is (nil? (snapshot parent)) (str trigger ": the destroyed parent was not re-created"))
    (is (= [{:actor-id              parent
             :invoke-id             [:working]
             :kind                  kind
             :rf.reply/status       :stale
             :rf.reply/work-status  :suppressed
             :rf.reply/stale-reason :rf.machine.spawn/state-exited}]
           (mapv #(select-keys (:tags %) [:actor-id :invoke-id :kind :rf.reply/status
                                          :rf.reply/work-status :rf.reply/stale-reason])
                 (rf.machines.test-support/events-of :rf.machine.spawn/stale-completion)))
        (str trigger ": the carrier is dropped with exactly one stale trace"))))

;; A declarative child cannot itself finish after its parent's destroy ends
;; it, so finality-time staleness is driven against a frame value that holds a
;; child whose parent is not live, as installing such a restored value would.
(deftest spawn-child-finishing-with-no-live-parent-is-stale-at-finality
  (reg-pair! :rl/fchild :rl/fparent)
  (rf/dispatch-sync [:rl/fparent [:go]])
  (rf.frame/swap-runtime-db! :rf/default
                             #(update-in % [:rf.runtime/machines :snapshots] dissoc :rl/fparent))
  (rf.machines.test-support/reset-captured!)
  (rf/dispatch-sync [:rl/fchild#1 [:finish]])
  (is (nil? (snapshot :rl/fchild#1)) "the stale-completing child still tears down")
  (is (nil? (snapshot :rl/fparent)) "no carrier re-created the parent")
  (is (empty? (rf.machines.test-support/events-of :rf.machine.spawn/stale-completion))
      "no carrier was minted")
  (let [tags (:tags (first-event :rf.machine/done))]
    (is (= {:actor-id              :rl/fchild#1
            :error?                false
            :rf.reply/status       :stale
            :rf.reply/work-status  :suppressed
            :rf.reply/stale-reason :rf.machine/actor-not-live
            :rf.reply/work-id      [:rf.work/machine :rl/fchild#1 [:working] 1]}
           (select-keys tags [:actor-id :error? :rf.reply/status :rf.reply/work-status
                              :rf.reply/stale-reason :rf.reply/work-id])))
    (is (= {:actor-id :rl/fchild#1 :generation {:carried 1 :current nil}}
           (select-keys (:rf.reply/correlation tags) [:actor-id :generation]))
        "the carried/current generation pair is the supersession gate")))
