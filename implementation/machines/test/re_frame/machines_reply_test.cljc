(ns re-frame.machines-reply-test
  "Pure unit tests for `re-frame.machines.reply` — the machine family's
  slice of the uniform reply envelope (EP-0011 §Machine Completion /
  §Timer Reply; Managed-Effects §The uniform reply envelope, rf2-zqefg3.4).

  These exercise the PURE reply-shaping helpers directly: the
  `:rf.work/machine` work-id construction (generation parsed off the
  `<type>#<n>` instance id), the canonical `:status :ok` / `:status
  :error` spawned-actor reply maps, the late-completion `:status :stale`
  reply, and the `:after` timer suppression gate + stale reply. Every
  reply built here is validated against the shared
  `re-frame.reply/validate-reply` contract so the closed-status taxonomy +
  value/error conventions + data-only invariant hold uniformly."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines.reply :as m-reply]
            [re-frame.reply :as reply]))

;; ---- work-id correlation --------------------------------------------------

(deftest spawn-work-id-shape
  (testing "machine work-id head [:rf.work/machine actor-id work-bearing-path generation]"
    (is (= [:rf.work/machine :auth/flow#1 [:authenticating] 1]
           (m-reply/spawn-work-id :auth/flow#1 [:authenticating]))
        "EP-0011 §Work-id correlation example shape")
    (is (= [:rf.work/machine :auth/flow#7 [:authenticating] 7]
           (m-reply/spawn-work-id :auth/flow#7 [:authenticating]))
        "generation parsed off the <type>#<n> instance-id suffix")
    (is (= [:rf.work/machine :app/child#12 [:p :working] 12]
           (m-reply/spawn-work-id :app/child#12 [:p :working]))
        "multi-digit generation + nested declaring path"))
  (testing "explicit per-state-singleton :fixed-actor-id (no #n suffix) → generation 1"
    (is (= [:rf.work/machine :explicit/actor [:s] 1]
           (m-reply/spawn-work-id :explicit/actor [:s]))
        "one attempt, generation 1 (EP-0007)"))
  (testing "work-id is =-comparable and EDN-serializable"
    (let [wid (m-reply/spawn-work-id :a/b#3 [:x])]
      (is (= wid (m-reply/spawn-work-id :a/b#3 [:x])))
      (is (= wid (read-string (pr-str wid)))))))

;; ---- canonical spawned-actor reply maps -----------------------------------

(deftest success-reply-is-canonical
  (testing ":status :ok reply for a plain :final? leaf"
    (let [r (m-reply/success-reply
              {:actor-id          :auth/flow#1
               :parent-id         :auth/main
               :work-bearing-path [:authenticating]
               :frame             :app/main
               :completed-at      1781078400888}
              {:user-id "u-42"})]
      (is (= :ok (:status r)))
      (is (= :machine (:work/kind r)))
      (is (= :completed (:work/status r)))
      (is (= {:user-id "u-42"} (:value r)))
      (is (= [:rf.work/machine :auth/flow#1 [:authenticating] 1] (:work/id r)))
      (is (= :app/main (:rf.frame/id r)))
      (is (= 1781078400888 (:completed-at r)))
      (is (= {:actor-id :auth/flow#1 :parent-id :auth/main :invoke-id [:authenticating]}
             (:correlation r)))
      (is (nil? (:error r)) ":ok carries no :error")
      (is (reply/valid-reply? r) "conforms to the shared reply-map contract")))
  (testing "no :output-key ⇒ :value nil, still valid :ok"
    (let [r (m-reply/success-reply {:actor-id :a/b#1 :work-bearing-path [:s]} nil)]
      (is (= :ok (:status r)))
      (is (contains? r :value))
      (is (nil? (:value r)))
      (is (reply/valid-reply? r))))
  (testing "optional facts omitted when absent (no nil sentinels)"
    (let [r (m-reply/success-reply {:actor-id :a/b#1 :work-bearing-path [:s]} :v)]
      (is (not (contains? r :rf.frame/id)))
      (is (not (contains? r :completed-at)))
      (is (= {:actor-id :a/b#1 :invoke-id [:s]} (:correlation r))
          "parent-id omitted from correlation when absent"))))

(deftest error-reply-is-canonical
  (testing ":status :error reply for an :error? terminal leaf"
    (let [r (m-reply/error-reply
              {:actor-id :auth/flow#2 :parent-id :auth/main :work-bearing-path [:authenticating]}
              {:reason :bad-creds})]
      (is (= :error (:status r)))
      (is (= :failed (:work/status r)))
      (is (= :machine (:work/kind r)))
      (is (some? (:error r)))
      (is (some? (:kind (:error r))) ":error carries a family :kind")
      (is (reply/valid-reply? r))))
  (testing "a raw (kind-less) error payload is wrapped with a family :kind"
    (let [r (m-reply/error-reply {:actor-id :a/b#1 :work-bearing-path [:s]} :boom)]
      (is (= {:kind :rf.machine/spawn-error :value :boom} (:error r)))
      (is (reply/valid-reply? r))))
  (testing "an error map already carrying :kind rides verbatim"
    (let [err {:kind :app/custom :detail 99}
          r   (m-reply/error-reply {:actor-id :a/b#1 :work-bearing-path [:s]} err)]
      (is (= err (:error r)) "no double-wrapping")
      (is (reply/valid-reply? r)))))

;; ---- late spawned-actor completion — stale suppression --------------------

(deftest stale-spawn-reply-suppresses
  (testing "a late child completion is :status :stale with no :value"
    (let [r (m-reply/stale-spawn-reply
              {:actor-id :auth/flow#1 :parent-id :auth/main :work-bearing-path [:authenticating]})]
      (is (= :stale (:status r)))
      (is (true? (:stale? r)))
      (is (= :rf.machine/actor-not-live (:stale/reason r)))
      (is (= :suppressed (:work/status r)))
      (is (not (contains? r :value)) ":stale MUST NOT carry :value (no app mutation)")
      (is (= [:rf.work/machine :auth/flow#1 [:authenticating] 1] (:work/id r)))
      (is (reply/valid-reply? r) "conforms to the shared reply-map contract"))))

;; ---- :after timer suppression gate ----------------------------------------

(deftest after-suppression-gate-shape
  (testing "the gate is {:path decl-path :rf/after-epoch epoch} (data-only)"
    (is (= {:path [:loading] :rf/after-epoch 3}
           (m-reply/after-suppression-gate [:loading] 3)))
    (is (= {:path nil :rf/after-epoch nil}
           (m-reply/after-suppression-gate nil nil))
        "exited node ⇒ nil path (no live counterpart)")))

(deftest after-suppression-gate-drives-reply-stale?
  (testing "carried vs current gate matched by re-frame.reply/stale?"
    (let [carried (m-reply/after-suppression-gate [:loading] 1)]
      (is (false? (reply/stale? carried (m-reply/after-suppression-gate [:loading] 1)))
          "same path + epoch ⇒ live")
      (is (true? (reply/stale? carried (m-reply/after-suppression-gate [:loading] 2)))
          "epoch advanced (re-entry) ⇒ stale")
      (is (true? (reply/stale? carried (m-reply/after-suppression-gate nil nil)))
          "node exited ⇒ stale"))))

(deftest timer-work-id-head
  (testing "rf2-niarhz — machine :after timer work-id [:rf.work/timer logical-id epoch]"
    ;; logical-id = [machine-id decl-path...] when both known
    (is (= [:rf.work/timer [:a/multi :loading] 3]
           (m-reply/timer-work-id :a/multi [:loading] 3)))
    ;; bare decl-path when no machine-id
    (is (= [:rf.work/timer [:loading] 3]
           (m-reply/timer-work-id nil [:loading] 3)))
    ;; the epoch discriminates a re-armed timer on node re-entry (distinct ids)
    (is (not= (m-reply/timer-work-id :a/multi [:loading] 1)
              (m-reply/timer-work-id :a/multi [:loading] 2)))
    ;; exited node (nil decl-path) is still a valid distinct id
    (is (= [:rf.work/timer nil 3] (m-reply/timer-work-id nil nil 3)))))

(deftest after-stale-reply-is-canonical
  (testing ":after stale reply carries the timer work-id + work-kind + suppression facts"
    (let [r (m-reply/after-stale-reply
              {:actor-id        :a/multi
               :state           :loading
               :delay           30000
               :decl-path       [:loading]
               :scheduled-epoch 1
               :current-epoch   2
               :frame           :rf/default})]
      (is (= :stale (:status r)))
      (is (= :timer (:work/kind r)) "machine :after is a specialized timer instance")
      ;; rf2-niarhz — the canonical :work/id joins the uniform work/reply rows;
      ;; the SCHEDULED epoch (the timer's attempt identity) keys it.
      (is (= [:rf.work/timer [:a/multi :loading] 1] (:work/id r)))
      (is (= :suppressed (:work/status r)))
      (is (= :rf.machine.timer/after-epoch-mismatch (:stale/reason r)))
      (is (not (contains? r :value)))
      (is (= {:path [:loading] :rf/after-epoch 1} (-> r :correlation :carried)))
      (is (= {:path [:loading] :rf/after-epoch 2} (-> r :correlation :current)))
      (is (reply/valid-reply? r)))))

(deftest after-fired-reply-is-canonical
  (testing "rf2-niarhz — a FIRED (live) :after timer is a closed :status :ok / :work/status :completed completion carrying the canonical :work/id"
    (let [r (m-reply/after-fired-reply
              {:actor-id   :a/multi
               :state      :loading
               :delay      30000
               :decl-path  [:loading]
               :epoch      2
               :frame      :rf/default})]
      (is (= :ok (:status r)))
      (is (= :timer (:work/kind r)))
      (is (= :completed (:work/status r)))
      (is (= [:rf.work/timer [:a/multi :loading] 2] (:work/id r)))
      (is (nil? (:value r)) "a timer carries no payload — :value is an explicit nil (completed-with-no-payload)")
      (is (reply/valid-reply? r) (str (reply/validate-reply r))))
    (testing "a guard-suppressed fired timer stays :ok/:completed (NOT stale) — the
              guard decision rides under :correlation, work-status stays closed"
      (let [r (m-reply/after-fired-reply
                {:actor-id :a/multi :state :loading :delay 30000
                 :decl-path [:loading] :epoch 2 :frame :rf/default
                 :guard-suppressed? true})]
        (is (= :ok (:status r)))
        (is (= :completed (:work/status r)))
        (is (true? (-> r :correlation :guard-suppressed?)))
        (is (reply/valid-reply? r))))))
