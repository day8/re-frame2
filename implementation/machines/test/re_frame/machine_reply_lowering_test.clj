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
  (let [epoch (or (get-in (snapshot :rl/after) [:rf/runtime :after-epoch [:loading]]) 0)]
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
              :rf.reply/work-id      [:rf.work/timer [:rl/after :loading 30000] epoch]}
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

(deftest fixed-id-spawn-mints-one-work-id-per-attempt
  ;; A `:fixed-actor-id` names the same address on every attempt, so the
  ;; spawn's attempt is the generation that keeps two attempts' work ids apart.
  (rf/reg-machine :rl/uploader
    {:initial :running
     :states  {:running {:on {:finish :done}}
               :done    {:final? true}}})
  (rf/reg-machine :rl/uparent
    {:initial :idle
     :states  {:idle    {:on {:go :loading}}
               :loading {:spawn {:machine-id     :rl/uploader
                                 :fixed-actor-id :rl/the-uploader
                                 :on-done        :idle}}}})
  (dotimes [_ 2]
    (rf/dispatch-sync [:rl/uparent [:go]])
    (rf/dispatch-sync [:rl/the-uploader [:finish]]))
  (is (= :idle (:state (snapshot :rl/uparent))) "each attempt completed back to :idle")
  (is (= [[:rf.work/machine :rl/the-uploader [:loading] 1]
          [:rf.work/machine :rl/the-uploader [:loading] 2]]
         (mapv #(:rf.reply/work-id (:tags %))
               (rf.machines.test-support/events-of :rf.machine/done)))
      "two attempts at one fixed address carry two work ids"))

(deftest fixed-id-spawn-cancel-row-carries-its-attempt-work-id
  ;; An `:explicit` destroy closes the attempt it tears down, so its cancel row
  ;; carries that attempt's work id, the one its done or error reply would.
  (rf/reg-machine :rl/canceller
    {:initial :running
     :states  {:running {:on {:finish :done}}
               :done    {:final? true}}})
  (rf/reg-machine :rl/cparent
    {:initial :idle
     :states  {:idle    {:on {:go :loading}}
               :loading {:on    {:cancel :idle}
                         :spawn {:machine-id     :rl/canceller
                                 :fixed-actor-id :rl/the-canceller
                                 :on-done        :idle}}}})
  (rf/dispatch-sync [:rl/cparent [:go]])
  (rf/dispatch-sync [:rl/the-canceller [:finish]])
  (rf/dispatch-sync [:rl/cparent [:go]])
  (is (= 2 (get-in (snapshot :rl/the-canceller) [:rf/runtime :invoke-attempt]))
      "the second attempt is live")
  (rf/dispatch-sync [:rl/cparent [:cancel]])
  (is (nil? (snapshot :rl/the-canceller)) "leaving :loading destroyed the second attempt")
  (is (= [[:rf.work/machine :rl/the-canceller [:loading] 1]]
         (mapv #(:rf.reply/work-id (:tags %))
               (rf.machines.test-support/events-of :rf.machine/done)))
      "the first attempt completed")
  (is (= [[:rf.work/machine :rl/the-canceller [:loading] 2]]
         (->> (rf.machines.test-support/events-of :rf.machine/destroyed)
              (filter #(= :explicit (:reason (:tags %))))
              (mapv #(:rf.reply/work-id (:tags %)))))
      "the cancel row closes the second attempt's work id"))

(deftest suffix-shaped-fixed-id-spawn-takes-its-attempt-as-generation
  ;; Fixed-vs-generated is the spawn's own provenance, never the keyword's
  ;; spelling: a fixed literal ending in `#<digits>` still takes its attempt,
  ;; and its reply and cancel row agree on it.
  (rf/reg-machine :rl/suffixed
    {:initial :running
     :states  {:running {:on {:finish :done}}
               :done    {:final? true}}})
  (rf/reg-machine :rl/sparent
    {:initial :idle
     :states  {:idle    {:on {:go :loading}}
               :loading {:on    {:cancel :idle}
                         :spawn {:machine-id     :rl/suffixed
                                 :fixed-actor-id :rl/the-uploader#3
                                 :on-done        :idle}}}})
  (rf/dispatch-sync [:rl/sparent [:go]])
  (rf/dispatch-sync [:rl/the-uploader#3 [:finish]])
  (rf/dispatch-sync [:rl/sparent [:go]])
  (rf/dispatch-sync [:rl/sparent [:cancel]])
  (is (= [[:rf.work/machine :rl/the-uploader#3 [:loading] 1]]
         (mapv #(:rf.reply/work-id (:tags %))
               (rf.machines.test-support/events-of :rf.machine/done)))
      "the first attempt's reply carries attempt 1, not the literal's suffix")
  (is (= [[:rf.work/machine :rl/the-uploader#3 [:loading] 2]]
         (->> (rf.machines.test-support/events-of :rf.machine/destroyed)
              (filter #(= :explicit (:reason (:tags %))))
              (mapv #(:rf.reply/work-id (:tags %)))))
      "the second attempt's cancel row carries attempt 2"))

(deftest generated-spawn-keeps-its-allocator-generation
  ;; A generated actor's generation is its `<type>#<n>` counter, which here
  ;; differs from its spawn's attempt (attempt 1 of the `:b` invoke, counter 2).
  (rf/reg-machine :rl/gen
    {:initial :running
     :states  {:running {:on {:finish :done}}
               :done    {:final? true}}})
  (rf/reg-machine :rl/gparent
    {:initial :a
     :states  {:a {:spawn {:machine-id :rl/gen :on-done :b}}
               :b {:spawn {:machine-id :rl/gen :on-done :c}}
               :c {}}})
  (rf/dispatch-sync [:rl/gparent [:rf.machine.spawn/spawned]])
  (rf/dispatch-sync [:rl/gen#1 [:finish]])
  (rf/dispatch-sync [:rl/gen#2 [:finish]])
  (is (= :c (:state (snapshot :rl/gparent))))
  (is (= [[:rf.work/machine :rl/gen#1 [:a] 1]
          [:rf.work/machine :rl/gen#2 [:b] 2]]
         (mapv #(:rf.reply/work-id (:tags %))
               (rf.machines.test-support/events-of :rf.machine/done)))))

(deftest keyword-form-destroy-cancel-row-carries-its-spawn-work-id
  ;; `[:rf.machine/destroy <id>]` names only the actor; a declarative child's
  ;; own stamps name its spawn, so the cancel row closes the work id the
  ;; attempt's done or error reply would carry.
  (rf/reg-machine :rl/kchild
    {:initial :running
     :states  {:running {:on {:finish :done}}
               :done    {:final? true}}})
  (rf/reg-machine :rl/kparent
    {:initial :idle
     :states  {:idle    {:on {:go :loading}}
               :loading {:on    {:kill {:action (fn [{:keys [spawned]}]
                                                  {:fx [[:rf.machine/destroy
                                                         (get spawned [:loading])]]})}}
                         :spawn {:machine-id :rl/kchild
                                 :on-done    :idle}}}})
  (rf/dispatch-sync [:rl/kparent [:go]])
  (rf/dispatch-sync [:rl/kchild#1 [:finish]])
  (rf/dispatch-sync [:rl/kparent [:go]])
  (rf/dispatch-sync [:rl/kparent [:kill]])
  (is (nil? (snapshot :rl/kchild#2)) "the keyword form destroyed the second attempt")
  (is (= [[:rf.work/machine :rl/kchild#1 [:loading] 1]]
         (mapv #(:rf.reply/work-id (:tags %))
               (rf.machines.test-support/events-of :rf.machine/done)))
      "the first attempt completed")
  (is (= [{:parent-id        :rl/kparent
           :invoke-id        [:loading]
           :rf.reply/work-id [:rf.work/machine :rl/kchild#2 [:loading] 2]}]
         (->> (rf.machines.test-support/events-of :rf.machine/destroyed)
              (filter #(= :explicit (:reason (:tags %))))
              (mapv #(select-keys (:tags %) [:parent-id :invoke-id :rf.reply/work-id]))))
      "the cancel row carries the second attempt's spawn path and work id"))

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
;; child's result, so it is dropped as stale (Spec 005 §Async completions
;; §Stale suppression). Failure carriers pass the same gate; their cases are
;; spawn-carrier-currency-cljs-test's.

(defn- reg-pair!
  "A child that finishes on `:finish`, under a parent that spawns it on `:go`,
  folds its result with `:on-done`, and destroys ITSELF on `:drop`."
  [child-id parent-id]
  (rf/reg-machine child-id
    {:initial :running
     :data    {}
     :states  {:running {:on {:finish :done}}
               :done    {:final? true}}})
  (rf/reg-machine parent-id
    {:initial :idle
     :data    {}
     :states  {:idle    {:on {:go :working}}
               :working {:on    {:drop {:action (fn [_] {:fx [[:rf.machine/destroy parent-id]]})}}
                         :spawn {:machine-id child-id
                                 :on-done    (fn [{data :data}] (assoc data :folded? true))}}}}))

(deftest stale-carrier-does-not-recreate-a-destroyed-parent
  (rf/reg-event ::kick (fn [_ [_ events]] {:fx (mapv (fn [e] [:dispatch e]) events)}))
  (reg-pair! :rl/schild :rl/sparent)
  (rf/dispatch-sync [:rl/sparent [:go]])
  (rf.machines.test-support/reset-captured!)
  ;; The child finishes while its parent is live, so the carrier is minted and
  ;; queued BEHIND the parent's own :drop.
  (rf/dispatch-sync [::kick [[:rl/schild#1 [:finish]] [:rl/sparent [:drop]]]])
  (is (nil? (snapshot :rl/sparent)) "the destroyed parent was not re-created")
  (is (= [{:actor-id              :rl/sparent
           :invoke-id             [:working]
           :kind                  :done
           :rf.reply/status       :stale
           :rf.reply/work-status  :suppressed
           :rf.reply/stale-reason :rf.machine.spawn/state-exited}]
         (mapv #(select-keys (:tags %) [:actor-id :invoke-id :kind :rf.reply/status
                                        :rf.reply/work-status :rf.reply/stale-reason])
               (rf.machines.test-support/events-of :rf.machine.spawn/stale-completion)))
      "the carrier is dropped with exactly one stale trace"))

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
