(ns re-frame.final-state-cljs-test
  "Verifies the `:final?` / `:on-done` / `:output-key` contract for
  state-machine final states. Ten locked decisions (D1-D10) are exercised
  here under both JVM and CLJS runtimes.

   D1 — `:final?` is a first-class key on the state node (NOT under
        `:meta`).
   D2 — `:on-done` on the parent's `:spawn` map is the parent-
        notification hook; signature `(fn [data result] new-data)`.
   D3 — `:output-key` on the child's `:final?` state designates which
        `:data` slot is reported back.
   D4 — Auto-destroy is synchronous on entry to a `:final?` state.
   D5 — Dispatch to a destroyed actor reuses the existing destroyed-
        frame trace path (`:rf.error/no-such-handler`).
   D6 — `:rf.machine/done` event fires with `:actor-id`, `:output`,
        `:parent-id`; `:rf.machine/destroyed` is enriched with `:reason`.
   D7 — Singleton symmetry — a non-spawned machine reaching `:final?`
        also auto-destroys.
   D8 — `:system-id` reverse-index clears AFTER `:on-done` ran.
   D9 — Implemented now (not deferred).
   D10 — `:fsm/final-states` capability axis.

  The file is named `*-cljs-test.cljc` so it's discovered by both
  cognitect.test-runner (JVM) and shadow-cljs (CLJS). The JVM path
  initialises the plain-atom substrate via `rf/init!`; the CLJS path
  attaches the Reagent substrate via `make-reset-runtime-fixture`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; The listener surface lives in `re-frame.trace.tooling`
   ;; (production-DCE split). On JVM the convenience aliases in
   ;; re-frame.core preserve the `rf/<name>` shape, but on CLJS the
   ;; tooling sibling must be referenced directly.
   [re-frame.trace.tooling :as trace-tooling]
   [re-frame.machines :as machines]
   [re-frame.machines.test-support :as mtest]
   [re-frame.registrar :as registrar]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

(defn- traces-for
  [traces operation]
  (filter #(= operation (:operation %)) @traces))

(defn- record-traces!
  [k]
  (let [a (atom [])]
    (trace-tooling/register-listener! k (fn [ev] (swap! a conj ev)))
    a))

;; ---- (a) entering :final? triggers :on-done with the right output ---------

(deftest child-final-state-fires-on-done-with-output
  (testing "child entering :final? fires parent's :on-done with the :output-key slot"
    (rf/reg-machine :rf2-gn80/child
      {:initial :running
       :data    {}
       :states
       {:running {:on {:finish {:target :done
                                :action (fn [{data :data ev :event}]
                                          {:data (assoc data :token (second ev))})}}}
        :done    {:final?     true
                  :output-key :token}}})
    (rf/reg-machine :rf2-gn80/parent
      {:initial :idle
       :data    {}
       :states
       {:idle
        {:on {:start :working}}

        :working
        {:spawn {:machine-id :rf2-gn80/child
                  :on-done (fn [{data :data result :result}] (assoc data :token-from-child result))}}}})
    (rf/dispatch-sync [:rf2-gn80/parent [:start]])
    ;; Now the child is spawned. Drive its :finish to enter :final?.
    (let [spawned-id (-> (rf/runtime-db-value :rf/default)
                         (get-in [:rf.runtime/machines :spawned :rf2-gn80/parent [:working]]))]
      (is (some? spawned-id)
          "child was spawned and bound in the registry")
      (rf/dispatch-sync [spawned-id [:finish :auth/secret-token]])
      (is (= :auth/secret-token
             (get-in (snapshot :rf2-gn80/parent) [:data :token-from-child]))
          "the parent's :on-done ran against the child's :output-key slot")
      (is (nil? (snapshot spawned-id))
          "the child's snapshot was synchronously dissoc'd (D4 auto-destroy)")
      (is (nil? (get-in (rf/runtime-db-value :rf/default)
                        [:rf.runtime/machines :spawned :rf2-gn80/parent [:working]]))
          "the [:rf.runtime/machines :spawned <parent> <invoke-id>] slot was cleared"))))

;; ---- (b) auto-destroy fires synchronously ----------------------------------

(deftest auto-destroy-synchronous-on-final
  (testing "auto-destroy runs synchronously — actor handler unregistered before dispatch returns"
    (rf/reg-machine :rf2-gn80/standalone
      {:initial :running
       :data    {}
       :states
       {:running {:on {:end :done}}
        :done    {:final? true}}})
    (rf/dispatch-sync [:rf2-gn80/standalone [:end]])
    (is (nil? (snapshot :rf2-gn80/standalone))
        "snapshot was synchronously cleared (D4 + D7 singleton)")
    (is (nil? (registrar/lookup :event :rf2-gn80/standalone))
        "event handler was synchronously unregistered (D4)")))

;; ---- (c) :rf.machine/done trace emitted with the right payload -----------

(deftest done-trace-fires-with-actor-id-output-parent-id
  (testing ":rf.machine/done trace carries :actor-id, :output, :parent-id (D6)"
    (let [traces (record-traces! ::done-trace)]
      (rf/reg-machine :rf2-gn80/child2
        {:initial :running
         :data    {}
         :states
         {:running {:on {:finish {:target :done
                                  :action (fn [{data :data ev :event}]
                                            {:data (assoc data :result (second ev))})}}}
          :done    {:final?     true
                    :output-key :result}}})
      (rf/reg-machine :rf2-gn80/parent2
        {:initial :working
         :states
         {:working
          {:spawn {:machine-id :rf2-gn80/child2
                    :on-done (fn [{d :data r :result}] (assoc d :reported r))}}}})
      (rf/dispatch-sync [:rf2-gn80/parent2 [:rf.machine.spawn/spawned]])
      (let [spawned-id (get-in (rf/runtime-db-value :rf/default)
                               [:rf.runtime/machines :spawned :rf2-gn80/parent2 [:working]])]
        (rf/dispatch-sync [spawned-id [:finish 42]])
        (let [dones (traces-for traces :rf.machine/done)]
          (is (= 1 (count dones))
              "exactly one :rf.machine/done trace fired")
          (let [t (first dones)]
            (is (= spawned-id            (-> t :tags :actor-id)))
            (is (= 42                    (-> t :tags :output)))
            (is (= :rf2-gn80/parent2     (-> t :tags :parent-id)))))))))

;; ---- (d) :rf.machine/destroyed carries :reason :rf.machine/finished -----

(deftest destroyed-trace-carries-reason-finished
  (testing ":rf.machine/destroyed trace carries :reason :rf.machine/finished (D6 enrichment)"
    (let [traces (record-traces! ::dest-trace)]
      (rf/reg-machine :rf2-gn80/child3
        {:initial :running
         :data    {}
         :states
         {:running {:on {:done :final}}
          :final   {:final? true}}})
      (rf/reg-machine :rf2-gn80/parent3
        {:initial :working
         :states
         {:working
          {:spawn {:machine-id :rf2-gn80/child3}}}})
      (rf/dispatch-sync [:rf2-gn80/parent3 [:rf.machine.spawn/spawned]])
      (let [spawned-id (get-in (rf/runtime-db-value :rf/default)
                               [:rf.runtime/machines :spawned :rf2-gn80/parent3 [:working]])]
        (rf/dispatch-sync [spawned-id [:done]])
        (let [dests (traces-for traces :rf.machine/destroyed)
              finish-trace (some #(when (= :rf.machine/finished (-> % :tags :reason)) %)
                                 dests)]
          (is (some? finish-trace)
              "a :rf.machine/destroyed trace with :reason :rf.machine/finished fired"))))))

;; ---- (e) singleton symmetry: standalone reaches :final? auto-destroys -----

(deftest singleton-reaches-final-auto-destroys
  (testing "D7: a singleton machine (no :spawn parent) reaching :final? auto-destroys"
    (let [traces (record-traces! ::sym-trace)]
      (rf/reg-machine :rf2-gn80/sing
        {:initial :running
         :states
         {:running {:on {:end :done}}
          :done    {:final?     true
                    :output-key :result}}})
      (rf/dispatch-sync [:rf2-gn80/sing [:end]])
      (is (nil? (snapshot :rf2-gn80/sing))
          "singleton snapshot was cleared on :final? entry")
      (is (nil? (registrar/lookup :event :rf2-gn80/sing))
          "singleton handler was unregistered")
      (let [dones (traces-for traces :rf.machine/done)]
        (is (= 1 (count dones))
            "one :rf.machine/done fired even with no parent")
        (is (nil? (-> (first dones) :tags :parent-id))
            ":parent-id is nil for singletons (D7)")))))

;; ---- (f) [:rf.runtime/machines :system-ids ...] reverse-index clears after :on-done -----

(deftest system-id-clears-after-on-done
  (testing "D8: [:rf.runtime/machines :system-ids <sid>] reverse-index entry clears AFTER :on-done fires"
    (let [on-done-saw-sid (atom nil)]
      (rf/reg-machine :rf2-gn80/sid-child
        {:initial :running
         :data    {}
         :states
         {:running {:on {:fin :done}}
          :done    {:final?     true
                    :output-key :payload}}})
      (rf/reg-machine :rf2-gn80/sid-parent
        {:initial :working
         :states
         {:working
          {:spawn {:machine-id :rf2-gn80/sid-child
                    :system-id  :auth-actor
                    :on-done (fn [{d :data r :result}]
                                  ;; D8: during :on-done, the system-id
                                  ;; binding MUST still resolve — only
                                  ;; cleared after the hook returns.
                                  (reset! on-done-saw-sid
                                          (machines/machine-by-system-id :auth-actor))
                                  (assoc d :result r))}}}})
      (rf/dispatch-sync [:rf2-gn80/sid-parent [:rf.machine.spawn/spawned]])
      (let [spawned-id (get-in (rf/runtime-db-value :rf/default)
                               [:rf.runtime/machines :spawned :rf2-gn80/sid-parent [:working]])]
        (is (= spawned-id (machines/machine-by-system-id :auth-actor))
            ":system-id is bound while the child is running")
        (rf/dispatch-sync [spawned-id [:fin]])
        (is (= spawned-id @on-done-saw-sid)
            ":on-done saw the :system-id binding still live (D8)")
        (is (nil? (machines/machine-by-system-id :auth-actor))
            ":system-id binding was cleared AFTER :on-done ran (D8)")))))

;; ---- (g) dispatch to done-then-destroyed actor reuses destroyed-frame path

(deftest dispatch-to-done-actor-reuses-no-such-handler
  (testing "D5: dispatching to a done-then-destroyed actor surfaces :rf.error/no-such-handler"
    (let [traces (record-traces! ::no-handler)]
      (rf/reg-machine :rf2-gn80/finalised
        {:initial :running
         :states
         {:running {:on {:fin :done}}
          :done    {:final? true}}})
      (rf/dispatch-sync [:rf2-gn80/finalised [:fin]])
      ;; Now the actor's handler is unregistered. A further dispatch
      ;; should hit the standard no-such-handler trace path — NOT a new
      ;; :rf.machine/dispatched-while-done half-state (D5).
      (rf/dispatch-sync [:rf2-gn80/finalised [:something]])
      (is (some #(= :rf.error/no-such-handler (:operation %)) @traces)
          "the existing no-such-handler trace path fired (D5 — no new half-state)")
      (is (not-any? #(= :rf.machine/dispatched-while-done (:operation %)) @traces)
          "no new :rf.machine/dispatched-while-done trace event introduced (D5)"))))

;; ---- :output-key absent on final state — :on-done receives nil ----------

(deftest final-without-output-key-passes-nil-to-on-done
  (testing "a :final? state without :output-key passes nil as the :on-done result"
    (let [seen-result (atom :unset)]
      (rf/reg-machine :rf2-gn80/no-output
        {:initial :running
         :states
         {:running {:on {:fin :done}}
          :done    {:final? true}}})
      (rf/reg-machine :rf2-gn80/observer
        {:initial :working
         :states
         {:working
          {:spawn {:machine-id :rf2-gn80/no-output
                    :on-done (fn [{d :data r :result}]
                                  (reset! seen-result r)
                                  d)}}}})
      (rf/dispatch-sync [:rf2-gn80/observer [:rf.machine.spawn/spawned]])
      (let [spawned-id (get-in (rf/runtime-db-value :rf/default)
                               [:rf.runtime/machines :spawned :rf2-gn80/observer [:working]])]
        (rf/dispatch-sync [spawned-id [:fin]])
        (is (nil? @seen-result)
            "with no :output-key, :on-done received nil (per D3)")))))

;; ---- :on-done is OPTIONAL — destroy still fires --------------------------

(deftest on-done-optional-destroy-still-fires
  (testing "a :spawn without :on-done still auto-destroys the child on :final?"
    (rf/reg-machine :rf2-gn80/just-final
      {:initial :running
       :states
       {:running {:on {:fin :done}}
        :done    {:final? true}}})
    (rf/reg-machine :rf2-gn80/silent-parent
      {:initial :working
       :states
       {:working
        {:spawn {:machine-id :rf2-gn80/just-final}}}})
    (rf/dispatch-sync [:rf2-gn80/silent-parent [:rf.machine.spawn/spawned]])
    (let [spawned-id (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/machines :spawned :rf2-gn80/silent-parent [:working]])]
      (rf/dispatch-sync [spawned-id [:fin]])
      (is (nil? (snapshot spawned-id))
          "child cleaned up even without :on-done"))))

;; ---- parallel: all-regions-final triggers auto-destroy -------------------

(deftest parallel-all-regions-final-auto-destroys
  (testing "a parallel-region machine where EVERY region's leaf is :final? auto-destroys (D7 + parallel composition)"
    (rf/reg-machine :rf2-gn80/par
      {:type    :parallel
       :regions {:left  {:initial :a
                         :states  {:a {:on {:end :z}}
                                   :z {:final? true}}}
                 :right {:initial :a
                         :states  {:a {:on {:end :z}}
                                   :z {:final? true}}}}})
    (rf/dispatch-sync [:rf2-gn80/par [:end]])
    ;; Both regions transition to :z on the broadcast :end event; once
    ;; every region's leaf is :final?, the parallel machine itself is
    ;; final and the auto-destroy fires synchronously.
    (is (nil? (snapshot :rf2-gn80/par))
        "snapshot cleared once every region reached :final?")
    (is (nil? (registrar/lookup :event :rf2-gn80/par))
        "parallel machine handler unregistered once every region reached :final?")))

(deftest parallel-one-region-final-stays-alive
  (testing "a parallel-region machine with one region still non-final stays alive (per spec composition rule)"
    (rf/reg-machine :rf2-gn80/par-partial
      {:type    :parallel
       :regions {:left  {:initial :a
                         :states  {:a {:on {:end-left :z}}
                                   :z {:final? true}}}
                 :right {:initial :a
                         :states  {:a {:on {:end-right :z}}
                                   :z {:final? true}}}}})
    (rf/dispatch-sync [:rf2-gn80/par-partial [:end-left]])
    (is (= {:left :z :right :a} (:state (snapshot :rf2-gn80/par-partial)))
        "only the :left region reached :final?")
    (is (some? (registrar/lookup :event :rf2-gn80/par-partial))
        "machine handler is still live — :right hasn't reached :final? yet")))

;; ---- (C2) :output-key on a NON-FIRST region's terminal leaf ----
;; A spawned parallel child whose :output-key lives on a region OTHER than the
;; first (state-map order) still reports that slot back through the parent's
;; :spawn :on-done — finalize scans every region's terminal leaf for the
;; :output-key, not just the first.

(deftest parallel-output-key-on-non-first-region-reported
  (testing "C2: a spawned parallel child reports :output-key from a NON-FIRST region"
    (let [seen-result (atom :unset)]
      (rf/reg-machine :rf2-gn80/par-child
        {:type    :parallel
         ;; :alpha is the FIRST region and carries NO :output-key; :beta (a
         ;; later region) is the one declaring :output-key — finalize must
         ;; scan past the first region's leaf to find it.
         :regions {:alpha {:initial :run
                           :states  {:run  {:on {:fin :done}}
                                     :done {:final? true}}}
                   :beta  {:initial :run
                           :states  {:run  {:on {:fin {:target :done
                                                       :action (fn [{data :data}]
                                                                 {:data (assoc data :payload :beta/value)})}}}
                                     :done {:final?     true
                                            :output-key :payload}}}}})
      (rf/reg-machine :rf2-gn80/par-observer
        {:initial :working
         :states
         {:working
          {:spawn {:machine-id :rf2-gn80/par-child
                    :on-done (fn [{d :data r :result}]
                               (reset! seen-result r)
                               d)}}}})
      (rf/dispatch-sync [:rf2-gn80/par-observer [:rf.machine.spawn/spawned]])
      (let [spawned-id (get-in (rf/runtime-db-value :rf/default)
                               [:rf.runtime/machines :spawned :rf2-gn80/par-observer [:working]])]
        (is (some? spawned-id) "parallel child was spawned")
        ;; :fin is broadcast to both regions; both reach :final? → the child
        ;; finishes and the parent's :on-done fires.
        (rf/dispatch-sync [spawned-id [:fin]])
        (is (= :beta/value @seen-result)
            ":on-done received the :output-key slot from the NON-FIRST region (C2)")
        (is (nil? (snapshot spawned-id))
            "the parallel child auto-destroyed on all-regions-final")))))

(deftest parallel-output-key-conflict-emits-error-and-first-region-wins
  (testing "C2: two regions declaring DIFFERENT :output-keys emit an error trace; the first region wins"
    (let [traces      (record-traces! ::okey-conflict)
          seen-result (atom :unset)]
      (rf/reg-machine :rf2-gn80/par-conflict-child
        {:type    :parallel
         :regions {:alpha {:initial :run
                           :states  {:run  {:on {:fin {:target :done
                                                       :action (fn [{data :data}]
                                                                 {:data (assoc data :a-out :alpha/value)})}}}
                                     :done {:final?     true
                                            :output-key :a-out}}}
                   :beta  {:initial :run
                           :states  {:run  {:on {:fin {:target :done
                                                       :action (fn [{data :data}]
                                                                 {:data (assoc data :b-out :beta/value)})}}}
                                     :done {:final?     true
                                            :output-key :b-out}}}}})
      (rf/reg-machine :rf2-gn80/par-conflict-observer
        {:initial :working
         :states
         {:working
          {:spawn {:machine-id :rf2-gn80/par-conflict-child
                    :on-done (fn [{d :data r :result}]
                               (reset! seen-result r)
                               d)}}}})
      (rf/dispatch-sync [:rf2-gn80/par-conflict-observer [:rf.machine.spawn/spawned]])
      (let [spawned-id (get-in (rf/runtime-db-value :rf/default)
                               [:rf.runtime/machines :spawned :rf2-gn80/par-conflict-observer [:working]])]
        (rf/dispatch-sync [spawned-id [:fin]])
        (is (= :alpha/value @seen-result)
            "the FIRST region's :output-key wins the conflict (deterministic tiebreak)")
        (is (seq (traces-for traces :rf.error/machine-parallel-output-key-conflict))
            "a :rf.error/machine-parallel-output-key-conflict trace was emitted")))))

;; ---- :error? final on a NON-FIRST region routes as ERROR ------
;; A spawned PARALLEL child whose FIRST region (state-map order) reaches a
;; plain final but a NON-FIRST region reaches `{:final? true :error? true}`
;; routes to the spawning parent's `:spawn :on-error` (control flow) — NOT
;; `:on-done` — and the `:rf.machine/done` trace carries `:error? true`.
;; Finalize scans every region's terminal leaf for `:error?` (the same
;; all-regions scan the C2 `:output-key` case above relies on), so an error
;; final in any region is classified as an error finish.

(deftest parallel-error-final-on-non-first-region-routes-as-error
  (testing "rf2-encnvn: a spawned parallel child whose NON-FIRST region reaches an :error? final routes to :on-error, not :on-done"
    (let [traces       (record-traces! ::par-err-non-first)
          on-done-ran? (atom false)]
      (rf/reg-machine :rf2-encnvn/par-err-child
        {:type    :parallel
         ;; :alpha is the FIRST region and reaches a PLAIN final (no :error?).
         ;; :beta (a later region) is the one reaching the ERROR terminal —
         ;; finalize classifies the finish off every region, so :beta's
         ;; error final wins.
         :regions {:alpha {:initial :run
                           :states  {:run  {:on {:fin :done}}
                                     :done {:final? true}}}
                   :beta  {:initial :run
                           :states  {:run  {:on {:fin {:target :failed
                                                       :action (fn [{data :data}]
                                                                 {:data (assoc data :err :beta/boom)})}}}
                                     :failed {:final?     true
                                              :error?     true
                                              :output-key :err}}}}})
      (rf/reg-machine :rf2-encnvn/par-err-parent
        {:initial :working
         :data    {}
         :states
         {:working
          {:spawn {:machine-id :rf2-encnvn/par-err-child
                    ;; If finalize mis-classifies the finish as success, :on-done
                    ;; fires (flipping the flag) and :on-error never runs.
                    :on-done  (fn [{d :data}] (reset! on-done-ran? true) d)
                    :on-error {:target :errored                  ;; ← sibling of :working
                               :action (fn [{data :data ev :event}]
                                         ;; ev = [:rf.machine.spawn/error <invoke-id> <error>]
                                         {:data (assoc data :captured (nth ev 2))})}}}
          :errored {}}})
      (rf/dispatch-sync [:rf2-encnvn/par-err-parent [:rf.machine.spawn/spawned]])
      (let [spawned-id (get-in (rf/runtime-db-value :rf/default)
                               [:rf.runtime/machines :spawned :rf2-encnvn/par-err-parent [:working]])]
        (is (some? spawned-id) "parallel child was spawned")
        ;; :fin is broadcast to both regions; :alpha reaches its plain final and
        ;; :beta reaches its :error? final → all-regions-final → the child
        ;; finishes via an ERROR terminal in a NON-FIRST region.
        (rf/dispatch-sync [spawned-id [:fin]])
        (is (= :errored (:state (snapshot :rf2-encnvn/par-err-parent)))
            "the parent moved to :errored — its :spawn :on-error fired (NOT :on-done)")
        (is (= :beta/boom (get-in (snapshot :rf2-encnvn/par-err-parent) [:data :captured]))
            "the error payload (the error region's :output-key slot) rode into the :on-error transition's :event")
        (is (false? @on-done-ran?)
            ":on-done was NOT called — error finish skips the success callback")
        (is (nil? (snapshot spawned-id))
            "the parallel child auto-destroyed on all-regions-final")
        (let [dones (traces-for traces :rf.machine/done)]
          (is (= 1 (count dones)) "exactly one :rf.machine/done trace fired")
          (let [t (first dones)]
            (is (true? (-> t :tags :error?))
                "the :rf.machine/done trace classifies the completion as :error? true")
            (is (= :error (-> t :tags :rf.reply/status))
                "the reply-envelope status is :error, not :ok")))))))

;; ---- registration-time validation -----------------------------------------

(deftest final-state-validations
  (testing "compound :final? state is rejected"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error) #":rf.error/machine-final-state-compound"
          (rf/reg-machine :rf2-gn80/bad
            {:initial :a
             :states  {:a {:final? true
                           :states  {:b {}}
                           :initial :b}}}))))
  (testing ":on / :always / :after / :spawn / :spawn-all on a :final? state is rejected"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error) #":rf.error/machine-final-state-has-transitions"
          (rf/reg-machine :rf2-gn80/bad2
            {:initial :a
             :states  {:a {:final? true
                           :on     {:go :a}}}}))))
  (testing ":output-key on a non-final state is rejected"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error) #":rf.error/machine-output-key-without-final"
          (rf/reg-machine :rf2-gn80/bad3
            {:initial :a
             :states  {:a {:output-key :foo
                           :on         {:go :b}}
                       :b {}}})))))
