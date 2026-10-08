(ns re-frame.machines-reply-cljs-test
  "`re-frame.machines.reply` builds canonical reply-envelope maps for spawned
  actors and `:after` timers, each valid under `re-frame.reply/validate-reply`."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.reply :as rf.reply]))

(deftest actor-generation-reads-the-numeric-suffix
  ;; `:weird/actor#3abc` is 1 on BOTH hosts: a lenient CLJS `js/parseInt`
  ;; would read 3 where the JVM rejects it.
  (doseq [[id gen] [[:app/child#12      12]
                    [:explicit/actor    1]
                    [nil                nil]
                    [:weird/actor#3abc  1]]]
    (is (= gen (rf.machines.reply/actor-generation id))
        (str "actor-generation of " (pr-str id)))))

(deftest success-reply-is-canonical
  (let [full (rf.machines.reply/success-reply
               {:actor-id          :auth/flow#1
                :parent-id         :auth/main
                :work-bearing-path [:authenticating]
                :frame             :app/main
                :completed-at      1781078400888}
               {:user-id "u-42"})
        bare (rf.machines.reply/success-reply {:actor-id :a/b#1 :work-bearing-path [:s]} nil)]
    (is (= {:status               :ok
            :rf.reply/work-status :completed
            :rf.reply/work-kind   :machine
            :rf.reply/work-id     [:rf.work/machine :auth/flow#1 [:authenticating] 1]
            :value                {:user-id "u-42"}
            :rf.frame/id          :app/main
            :completed-at         1781078400888
            :correlation          {:actor-id :auth/flow#1 :parent-id :auth/main
                                   :invoke-id [:authenticating]}}
           full))
    (is (= {:status               :ok
            :rf.reply/work-status :completed
            :rf.reply/work-kind   :machine
            :rf.reply/work-id     [:rf.work/machine :a/b#1 [:s] 1]
            :value                nil
            :correlation          {:actor-id :a/b#1 :invoke-id [:s]}}
           bare)
        "no :output-key ⇒ an explicit nil :value; absent facts are omitted, not nil-filled")
    (is (= [nil nil] (map rf.reply/validate-reply [full bare])))))

(deftest error-reply-is-canonical
  (let [wrapped (rf.machines.reply/error-reply
                  {:actor-id :auth/flow#2 :parent-id :auth/main :work-bearing-path [:authenticating]}
                  {:reason :bad-creds})
        err     {:kind :app/custom :detail 99}
        kinded  (rf.machines.reply/error-reply {:actor-id :a/b#1 :work-bearing-path [:s]} err)]
    (is (= {:status               :error
            :rf.reply/work-status :failed
            :rf.reply/work-kind   :machine
            :rf.reply/work-id     [:rf.work/machine :auth/flow#2 [:authenticating] 2]
            :error                {:kind :rf.machine/spawn-error :value {:reason :bad-creds}}
            :correlation          {:actor-id :auth/flow#2 :parent-id :auth/main
                                   :invoke-id [:authenticating]}}
           wrapped)
        "a payload without a :kind is wrapped with the family :kind")
    (is (= err (:error kinded)) "an error map already carrying :kind rides verbatim")
    (is (= [nil nil] (map rf.reply/validate-reply [wrapped kinded])))))

(deftest stale-spawn-reply-suppresses
  (let [r (rf.machines.reply/stale-spawn-reply
            {:actor-id :auth/flow#1 :parent-id :auth/main :work-bearing-path [:authenticating]})]
    (is (= {:status                :stale
            :stale?                true
            :rf.reply/stale-reason :rf.machine/actor-not-live
            :rf.reply/work-status  :suppressed
            :rf.reply/work-kind    :machine
            :rf.reply/work-id      [:rf.work/machine :auth/flow#1 [:authenticating] 1]
            :correlation           {:actor-id   :auth/flow#1
                                    :parent-id  :auth/main
                                    :invoke-id  [:authenticating]
                                    :generation {:carried 1 :current nil}}}
           r)
        "a late completion carries no :value")
    (is (nil? (rf.reply/validate-reply r)))))

(deftest after-stale-reply-is-canonical
  (let [r (rf.machines.reply/after-stale-reply
            {:actor-id        :a/multi
             :state           :loading
             :delay           30000
             :decl-path       [:loading]
             :scheduled-epoch 1
             :current-epoch   2
             :frame           :rf/default})]
    (is (= {:status                :stale
            :stale?                true
            :rf.reply/stale-reason :rf.machine.timer/after-epoch-mismatch
            :rf.reply/work-status  :suppressed
            :rf.reply/work-kind    :timer
            :rf.reply/work-id      [:rf.work/timer [:a/multi :loading] 1]
            :rf.frame/id           :rf/default
            :correlation           {:actor-id :a/multi
                                    :state    :loading
                                    :delay    30000
                                    :carried  {:path [:loading] :rf/after-epoch 1}
                                    :current  {:path [:loading] :rf/after-epoch 2}}}
           r)
        "the SCHEDULED epoch keys the work-id; no :value")
    (is (nil? (rf.reply/validate-reply r)))))

(deftest after-fired-reply-is-canonical
  (let [ctx        {:actor-id :a/multi :state :loading :delay 30000
                    :decl-path [:loading] :epoch 2 :frame :rf/default}
        live       (rf.machines.reply/after-fired-reply ctx)
        suppressed (rf.machines.reply/after-fired-reply (assoc ctx :guard-suppressed? true))]
    (is (= {:status               :ok
            :rf.reply/work-status :completed
            :rf.reply/work-kind   :timer
            :rf.reply/work-id     [:rf.work/timer [:a/multi :loading] 2]
            :value                nil
            :rf.frame/id          :rf/default
            :correlation          {:actor-id :a/multi
                                   :state    :loading
                                   :delay    30000
                                   :gate     {:path [:loading] :rf/after-epoch 2}}}
           live))
    (is (= (assoc-in live [:correlation :guard-suppressed?] true) suppressed)
        "a guard-suppressed fire is still :ok/:completed, never stale")
    (is (= [nil nil] (map rf.reply/validate-reply [live suppressed])))))

(deftest cancelled-timer-reply-is-canonical
  (let [r (rf.machines.reply/cancelled-timer-reply
            {:actor-id :a/multi :state :loading :delay 30000
             :decl-path [:loading] :epoch 1 :frame :rf/default
             :reason :on-exit})]
    (is (= {:status                 :cancelled
            :cancelled?             true
            :rf.reply/cancel-reason :on-exit
            :rf.reply/work-status   :cancelled
            :rf.reply/work-kind     :timer
            :rf.reply/work-id       [:rf.work/timer [:a/multi :loading] 1]
            :rf.frame/id            :rf/default
            :correlation            {:actor-id :a/multi
                                     :state    :loading
                                     :delay    30000
                                     :gate     {:path [:loading] :rf/after-epoch 1}}}
           r)
        "the work-id matches the fired / stale replies' row; no :value")
    (is (nil? (rf.reply/validate-reply r)))))

(deftest cancelled-actor-reply-is-canonical
  (let [r (rf.machines.reply/cancelled-actor-reply
            {:actor-id :auth/flow#1 :parent-id :auth/main
             :work-bearing-path [:authenticating] :frame :rf/default
             :reason :explicit})]
    (is (= {:status                 :cancelled
            :cancelled?             true
            :rf.reply/cancel-reason :explicit
            :rf.reply/work-status   :cancelled
            :rf.reply/work-kind     :machine
            :rf.reply/work-id       [:rf.work/machine :auth/flow#1 [:authenticating] 1]
            :rf.frame/id            :rf/default
            :correlation            {:actor-id :auth/flow#1 :parent-id :auth/main
                                     :invoke-id [:authenticating]}}
           r)
        "the cancel joins the spawn's work-id row; no :value")
    (is (nil? (rf.reply/validate-reply r)))))
