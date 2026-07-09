(ns re-frame.machines-reply-cljs-test
  "Pure unit tests for `re-frame.machines.reply` — the machine family's
  slice of the uniform reply envelope (EP-0011 §Machine Completion /
  §Timer Reply; Managed-Effects §The uniform reply envelope).

  These exercise the PURE reply-shaping helpers directly: the
  `:rf.work/machine` work-id construction (generation parsed off the
  `<type>#<n>` instance id), the canonical `:status :ok` / `:status
  :error` spawned-actor reply maps, the late-completion `:status :stale`
  reply, and the `:after` timer suppression gate + stale reply. Every
  reply built here is validated against the shared
  `re-frame.reply/validate-reply` contract so the closed-status taxonomy +
  value/error conventions + data-only invariant hold uniformly."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
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
      (is (= wid (edn/read-string (pr-str wid)))))))

;; ---- cross-platform actor-generation determinism --------------------------
;; A `:fixed-actor-id` carrying a `#` followed by a non-fully-numeric suffix
;; defaults to generation 1 on BOTH platforms. CLJS `js/parseInt "3abc" 10`
;; leniently yields 3 while CLJ `Long/parseLong "3abc"` THROWS, so the CLJS
;; branch tests `#"\d+"` (fully numeric) before parseInt — both platforms
;; agree on generation 1 for a malformed suffix (no determinism break).

(deftest actor-generation-numeric-suffix
  (testing "a fully-numeric #n suffix parses to n on both platforms"
    (is (= 1  (m-reply/actor-generation :auth/flow#1)))
    (is (= 7  (m-reply/actor-generation :auth/flow#7)))
    (is (= 12 (m-reply/actor-generation :app/child#12)))))

(deftest actor-generation-no-suffix-is-one
  (testing "an id with no #n suffix is generation 1 (explicit :fixed-actor-id)"
    (is (= 1 (m-reply/actor-generation :explicit/actor)))))

(deftest actor-generation-malformed-suffix-is-one-cross-platform
  (testing "C4: a # followed by a NON-fully-numeric suffix is generation 1 on
            BOTH CLJ and CLJS (no lenient js/parseInt divergence)"
    ;; partially-numeric: a lenient CLJS js/parseInt would yield 3 here, CLJ
    ;; would throw — the `#"\d+"` pre-check defaults both to 1
    (is (= 1 (m-reply/actor-generation :weird/actor#3abc))
        "partially-numeric suffix defaults to generation 1 on both platforms")
    ;; non-numeric suffix: NaN (CLJS) / throw (CLJ) — both already → 1
    (is (= 1 (m-reply/actor-generation :weird/actor#abc)))
    ;; empty suffix (trailing #): both → 1
    (is (= 1 (m-reply/actor-generation :weird/actor#)))
    ;; whitespace / sign-prefixed: js/parseInt is lenient about leading
    ;; whitespace; CLJ Long/parseLong is not — both must agree on 1.
    (is (= 1 (m-reply/actor-generation (keyword "weird" "actor# 4"))))))

(deftest actor-generation-nil-safe
  (testing "nil id (no live counterpart) → nil"
    (is (nil? (m-reply/actor-generation nil)))))

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
      (is (= :completed (:rf.reply/work-status r)))
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
      (is (= :failed (:rf.reply/work-status r)))
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
      (is (= :rf.machine/actor-not-live (:rf.reply/stale-reason r)))
      (is (= :suppressed (:rf.reply/work-status r)))
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
      ;; the canonical :work/id joins the uniform work/reply rows;
      ;; the SCHEDULED epoch (the timer's attempt identity) keys it.
      (is (= [:rf.work/timer [:a/multi :loading] 1] (:work/id r)))
      (is (= :suppressed (:rf.reply/work-status r)))
      (is (= :rf.machine.timer/after-epoch-mismatch (:rf.reply/stale-reason r)))
      (is (not (contains? r :value)))
      (is (= {:path [:loading] :rf/after-epoch 1} (-> r :correlation :carried)))
      (is (= {:path [:loading] :rf/after-epoch 2} (-> r :correlation :current)))
      (is (reply/valid-reply? r)))))

(deftest after-fired-reply-is-canonical
  (testing "rf2-niarhz — a FIRED (live) :after timer is a closed :status :ok / :rf.reply/work-status :completed completion carrying the canonical :work/id"
    (let [r (m-reply/after-fired-reply
              {:actor-id   :a/multi
               :state      :loading
               :delay      30000
               :decl-path  [:loading]
               :epoch      2
               :frame      :rf/default})]
      (is (= :ok (:status r)))
      (is (= :timer (:work/kind r)))
      (is (= :completed (:rf.reply/work-status r)))
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
        (is (= :completed (:rf.reply/work-status r)))
        (is (true? (-> r :correlation :guard-suppressed?)))
        (is (reply/valid-reply? r))))))

;; ---- terminal cancellation replies ----------------------------------------

(deftest cancelled-timer-reply-is-canonical
  (testing "rf2-sfunt8 — a cancelled :after timer is :status :cancelled DATA"
    (let [r (m-reply/cancelled-timer-reply
              {:actor-id :a/multi :state :loading :delay 30000
               :decl-path [:loading] :epoch 1 :frame :rf/default
               :reason :on-exit})]
      (is (= :cancelled (:status r)))
      (is (true? (:cancelled? r)) "cancellation is a positive fact")
      (is (= :on-exit (:rf.reply/cancel-reason r)))
      (is (= :cancelled (:rf.reply/work-status r)))
      (is (= :timer (:work/kind r)))
      ;; matches the fired / stale reply's work-id (same scheduling attempt row)
      (is (= [:rf.work/timer [:a/multi :loading] 1] (:work/id r)))
      (is (not (contains? r :value)) "a cancelled timer never fired — no :value")
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))))
  (testing "every closed cancel reason produces a valid reply"
    (doseq [reason m-reply/timer-cancel-reasons]
      (let [r (m-reply/cancelled-timer-reply
                {:actor-id :a/m :state :s :delay 100
                 :decl-path [:s] :epoch 1 :frame :rf/default :reason reason})]
        (is (= reason (:rf.reply/cancel-reason r)))
        (is (reply/valid-reply? r) (str reason " ⇒ " (reply/validate-reply r)))))))

(deftest on-restore-cancel-reason-in-closed-vocab
  (testing "rf2-e3ryis — :on-restore (epoch-restore host-timer cleanup) is a
            member of the closed timer-cancel-reasons vocab and produces a valid
            cancelled-timer reply. `timer/cancel-frame-timers-on-restore!` emits
            :reason :on-restore, so the closed set MUST sanction it — otherwise a
            downstream reply/trace-schema validator or a consumer branching on
            the vocab (exhaustive case / filter-pill enum) rejects or
            misclassifies the epoch-restore cancellation."
    (is (contains? m-reply/timer-cancel-reasons :on-restore)
        ":on-restore is a member of the closed cancel-reason vocabulary")
    (let [r (m-reply/cancelled-timer-reply
              {:actor-id :a/m :state :waiting :delay 5000
               :decl-path [:waiting] :epoch 2 :frame :rf/default
               :reason :on-restore})]
      (is (= :on-restore (:rf.reply/cancel-reason r)))
      (is (reply/valid-reply? r) (str "on-restore ⇒ " (reply/validate-reply r))))))

(deftest cancelled-actor-reply-is-canonical
  (testing "rf2-sfunt8 — a cancelled (destroyed) spawned actor is :status :cancelled"
    (let [r (m-reply/cancelled-actor-reply
              {:actor-id :auth/flow#1 :parent-id :auth/main
               :work-bearing-path [:authenticating] :frame :rf/default
               :reason :explicit})]
      (is (= :cancelled (:status r)))
      (is (true? (:cancelled? r)))
      (is (= :explicit (:rf.reply/cancel-reason r)))
      (is (= :cancelled (:rf.reply/work-status r)))
      (is (= :machine (:work/kind r)))
      (is (= [:rf.work/machine :auth/flow#1 [:authenticating] 1] (:work/id r))
          "reuses the machine work-id so the cancel joins the spawn's row")
      (is (not (contains? r :value)) "the actor never produced an :output-key result")
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))))
  (testing "join-survivor cancel reason rides as :rf.reply/cancel-reason"
    (let [r (m-reply/cancelled-actor-reply
              {:actor-id :child/c#2 :parent-id :sup/all
               :work-bearing-path [:hydrating] :frame :rf/default
               :reason :on-join-resolution})]
      (is (= :on-join-resolution (:rf.reply/cancel-reason r)))
      (is (reply/valid-reply? r)))))
