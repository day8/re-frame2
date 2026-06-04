(ns re-frame.machines-on-error-cljs-test
  "Per rf2-5hlsh — first-class `:spawn :on-error` (XState v5 invoke `onError`).

  When a `:spawn`-spawned child FAILS, the runtime routes the failure to the
  spawning parent's `:spawn :on-error` TRANSITION (control flow — a declarative
  parent state change), SYMMETRIC with the existing `:spawn :on-done` teardown
  hook. Two triggers:

    (1) the child reaches a designated ERROR `:final?` leaf (`:error? true`) —
        the error payload (`:output-key` slot) rides into the `:on-error`
        transition's `:event`;
    (2) an uncaught child action exception
        (`:rf.error/machine-action-exception`) — the exception envelope rides
        into the `:on-error` transition's `:event` (this was formerly
        observability-only).

  `:on-error` is ADDITIVE: the trace emission (observability) and the explicit
  dispatch-back-to-parent escape hatch both keep working; `:on-error` is the
  declarative invoke-site control-flow form.

  Tests:
    (a) child reaches error `:final?` leaf → parent `:on-error :target` fires
        + error payload in ctx;
    (b) uncaught child action exception → parent `:on-error` fires (control
        flow);
    (c) `:on-error` with `:guard` + `:action`;
    (d) child SUCCESS → `:on-done` fires, `:on-error` does NOT;
    (e) no `:on-error` declared → existing behaviour (trace + escape-hatch)
        unchanged (regression);
    (f) malformed `:on-error` / `:error?`-without-`:final?` rejected at
        registration.

  Named `*-cljs-test.cljc` so it runs under both cognitect.test-runner (JVM)
  and shadow-cljs (CLJS), matching `final_state_cljs_test.cljc`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Loading `re-frame.machines` installs the late-bind hooks `reg-machine`
   ;; resolves through (without it, `rf/reg-machine` throws
   ;; `:rf.error/machines-artefact-missing`).
   [re-frame.machines]
   [re-frame.trace.tooling :as trace-tooling]
   [re-frame.test-support :as test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

(defn- snapshot
  [machine-id]
  (get-in (rf/app-db-value :rf/default) [:rf/runtime :machines :snapshots machine-id]))

(defn- spawned-id-for
  [parent-id invoke-id]
  (get-in (rf/app-db-value :rf/default)
          [:rf/runtime :machines :spawned parent-id invoke-id]))

(defn- traces-for
  [traces operation]
  (filter #(= operation (:operation %)) @traces))

(defn- record-traces!
  [k]
  (let [a (atom [])]
    (trace-tooling/register-listener! k (fn [ev] (swap! a conj ev)))
    a))

;; ---- (a) child reaches error :final? leaf → parent :on-error :target fires ----

(deftest child-error-final-leaf-fires-parent-on-error-transition
  (testing "child reaching an :error? :final? leaf drives the parent's :on-error :target (with the error payload)"
    (rf/reg-machine :rf2-5hlsh-a/child
      {:initial :running
       :data    {}
       :states
       {:running {:on {:boom {:target :failed
                              :action (fn [{data :data ev :event}]
                                        {:data (assoc data :err (second ev))})}}}
        ;; designated ERROR terminal — carries the error via :output-key
        :failed  {:final?     true
                  :error?     true
                  :output-key :err}}})
    (rf/reg-machine :rf2-5hlsh-a/parent
      {:initial :idle
       :data    {}
       :states
       {:idle    {:on {:start :working}}
        :working {:spawn {:machine-id :rf2-5hlsh-a/child
                          ;; :on-error is an :on-shaped TRANSITION resolved at
                          ;; :working's level — :errored is a sibling.
                          :on-error {:target :errored
                                     :action (fn [{data :data ev :event}]
                                               ;; ev = [:rf.machine.spawn/error <invoke-id> <error>]
                                               {:data (assoc data :captured (nth ev 2))})}}
                  :on    {:done :idle}}
        :errored {}}})
    (rf/dispatch-sync [:rf2-5hlsh-a/parent [:start]])
    (let [child (spawned-id-for :rf2-5hlsh-a/parent [:working])]
      (is (some? child) "child spawned")
      (rf/dispatch-sync [child [:boom :network-down]])
      (is (= :errored (:state (snapshot :rf2-5hlsh-a/parent)))
          "parent's :on-error :target fired — it moved to :errored")
      (is (= :network-down (get-in (snapshot :rf2-5hlsh-a/parent) [:data :captured]))
          "the error payload (child's :output-key slot) rode into the :on-error transition's :event")
      (is (nil? (snapshot child))
          "the failed child auto-destroyed (it reached a :final? leaf)"))))

;; ---- (b) uncaught child action exception → parent :on-error fires ----------

(deftest child-action-exception-fires-parent-on-error-transition
  (testing "an uncaught child action exception routes to the parent's :on-error transition (control flow, was observability-only)"
    (let [traces (record-traces! ::action-exc)]
      (rf/reg-machine :rf2-5hlsh-b/child
        {:initial :running
         :data    {}
         :states
         {:running {:on {:go {:target :next
                              :action (fn [_] (throw (ex-info "kaboom" {:why :test})))}}}
          :next    {}}})
      (rf/reg-machine :rf2-5hlsh-b/parent
        {:initial :working
         :data    {}
         :states
         {:working {:spawn {:machine-id :rf2-5hlsh-b/child
                            :on-error {:target :errored}}}
          :errored {}}})
      (rf/dispatch-sync [:rf2-5hlsh-b/parent [:rf.machine.spawn/spawned]])
      (let [child (spawned-id-for :rf2-5hlsh-b/parent [:working])]
        (rf/dispatch-sync [child [:go]])
        (is (some #(= :rf.error/machine-action-exception (:operation %)) @traces)
            "the action-exception trace STILL fired (observability unchanged — additive)")
        (is (= :errored (:state (snapshot :rf2-5hlsh-b/parent)))
            "the uncaught exception drove the parent's :on-error :target")))))

;; ---- (c) :on-error with :guard + :action -----------------------------------

(deftest on-error-honours-guard-and-action
  (testing ":on-error candidate-vector resolves first-guard-pass-wins, runs the chosen :action"
    (rf/reg-machine :rf2-5hlsh-c/child
      {:initial :running
       :data    {}
       :states
       {:running {:on {:fail {:target :failed
                              :action (fn [{data :data ev :event}]
                                        {:data (assoc data :code (second ev))})}}}
        :failed  {:final?     true
                  :error?     true
                  :output-key :code}}})
    (rf/reg-machine :rf2-5hlsh-c/parent
      {:initial :working
       :data    {}
       :states
       {:working {:spawn {:machine-id :rf2-5hlsh-c/child
                          ;; guarded candidate vector: a 503 retries, anything
                          ;; else gives up. First guard-pass wins.
                          :on-error [{:guard  (fn [{ev :event}] (= 503 (nth ev 2)))
                                      :target :retrying
                                      :action (fn [{data :data}] {:data (assoc data :route :retry)})}
                                     {:target :gave-up
                                      :action (fn [{data :data}] {:data (assoc data :route :give-up)})}]}}
        :retrying {}
        :gave-up  {}}})
    (rf/dispatch-sync [:rf2-5hlsh-c/parent [:rf.machine.spawn/spawned]])
    (let [child (spawned-id-for :rf2-5hlsh-c/parent [:working])]
      (rf/dispatch-sync [child [:fail 503]])
      (is (= :retrying (:state (snapshot :rf2-5hlsh-c/parent)))
          "the 503 guard passed → :retrying")
      (is (= :retry (get-in (snapshot :rf2-5hlsh-c/parent) [:data :route]))
          "the guarded candidate's :action ran"))))

(deftest on-error-guard-fallthrough-to-unguarded
  (testing ":on-error guarded candidate guard-fails → falls through to the unguarded fallback"
    (rf/reg-machine :rf2-5hlsh-c2/child
      {:initial :running
       :data    {}
       :states
       {:running {:on {:fail {:target :failed
                              :action (fn [{data :data ev :event}]
                                        {:data (assoc data :code (second ev))})}}}
        :failed  {:final?     true
                  :error?     true
                  :output-key :code}}})
    (rf/reg-machine :rf2-5hlsh-c2/parent
      {:initial :working
       :data    {}
       :states
       {:working {:spawn {:machine-id :rf2-5hlsh-c2/child
                          :on-error [{:guard  (fn [{ev :event}] (= 503 (nth ev 2)))
                                      :target :retrying}
                                     {:target :gave-up}]}}
        :retrying {}
        :gave-up  {}}})
    (rf/dispatch-sync [:rf2-5hlsh-c2/parent [:rf.machine.spawn/spawned]])
    (let [child (spawned-id-for :rf2-5hlsh-c2/parent [:working])]
      (rf/dispatch-sync [child [:fail 404]])
      (is (= :gave-up (:state (snapshot :rf2-5hlsh-c2/parent)))
          "404 failed the 503 guard → unguarded fallback :gave-up fired"))))

;; ---- (d) child SUCCESS → :on-done fires, :on-error does NOT -----------------

(deftest success-leaf-fires-on-done-not-on-error
  (testing "a plain (non-:error?) :final? leaf fires :on-done; :on-error does NOT fire"
    (rf/reg-machine :rf2-5hlsh-d/child
      {:initial :running
       :data    {}
       :states
       {:running {:on {:ok {:target :done
                            :action (fn [{data :data ev :event}]
                                      {:data (assoc data :tok (second ev))})}}}
        :done    {:final?     true
                  :output-key :tok}}})
    (rf/reg-machine :rf2-5hlsh-d/parent
      {:initial :working
       :data    {}
       :states
       {:working {:spawn {:machine-id :rf2-5hlsh-d/child
                          :on-done  (fn [{data :data result :result}]
                                      (assoc data :got result))
                          :on-error {:target :errored}}}
        :errored {}}})
    (rf/dispatch-sync [:rf2-5hlsh-d/parent [:rf.machine.spawn/spawned]])
    (let [child (spawned-id-for :rf2-5hlsh-d/parent [:working])]
      (rf/dispatch-sync [child [:ok :the-token]])
      (is (= :the-token (get-in (snapshot :rf2-5hlsh-d/parent) [:data :got]))
          ":on-done ran against the success result")
      (is (= :working (:state (snapshot :rf2-5hlsh-d/parent)))
          "the parent did NOT move to :errored — :on-error did not fire on success"))))

;; ---- (e) no :on-error declared → existing behaviour unchanged (regression) ----

(deftest no-on-error-keeps-trace-and-escape-hatch
  (testing "without :on-error, an error leaf still fires the :rf.machine/done trace + auto-destroy (regression)"
    (let [traces (record-traces! ::no-on-error)]
      (rf/reg-machine :rf2-5hlsh-e/child
        {:initial :running
         :data    {}
         :states
         {:running {:on {:boom :failed}}
          :failed  {:final? true :error? true}}})
      ;; parent declares NO :on-error — error leaf behaves like any :final?:
      ;; the child auto-destroys, the :rf.machine/done trace fires, the parent
      ;; is unmoved. The explicit dispatch-back escape hatch (if the child
      ;; chose it) would still work — exercised by the action emitting a
      ;; dispatch; here we assert the framework adds NO transition itself.
      (rf/reg-machine :rf2-5hlsh-e/parent
        {:initial :working
         :data    {}
         :states
         {:working {:spawn {:machine-id :rf2-5hlsh-e/child}}}})
      (rf/dispatch-sync [:rf2-5hlsh-e/parent [:rf.machine.spawn/spawned]])
      (let [child (spawned-id-for :rf2-5hlsh-e/parent [:working])]
        (rf/dispatch-sync [child [:boom]])
        (is (nil? (snapshot child))
            "child auto-destroyed on its error leaf (no :on-error needed)")
        (is (= :working (:state (snapshot :rf2-5hlsh-e/parent)))
            "parent unmoved — no :on-error means no framework-driven transition")
        (let [dones (traces-for traces :rf.machine/done)]
          (is (= 1 (count dones)) "the :rf.machine/done actor-finality trace still fired")
          (is (true? (-> (first dones) :tags :error?))
              ":rf.machine/done carries :error? true for an error leaf"))))))

(deftest escape-hatch-explicit-dispatch-still-works
  (testing "the lower-level escape hatch ([:fx [[:dispatch [parent [:failed]]]]]) keeps working alongside :on-error"
    (rf/reg-machine :rf2-5hlsh-e2/child
      {:initial :running
       :data    {}
       :states
       ;; The child explicitly dispatches a failure event back to its parent
       ;; from a transition action — the documented lower-level form.
       {:running {:on {:boom {:target :failed
                              :action (fn [{data :data}]
                                        {:data data
                                         :fx   [[:dispatch [:rf2-5hlsh-e2/parent [:child-failed]]]]})}}}
        :failed  {:final? true}}})
    (rf/reg-machine :rf2-5hlsh-e2/parent
      {:initial :working
       :data    {}
       :states
       {:working {:spawn {:machine-id :rf2-5hlsh-e2/child}
                  :on    {:child-failed :errored}}
        :errored {}}})
    (rf/dispatch-sync [:rf2-5hlsh-e2/parent [:rf.machine.spawn/spawned]])
    (let [child (spawned-id-for :rf2-5hlsh-e2/parent [:working])]
      (rf/dispatch-sync [child [:boom]])
      (is (= :errored (:state (snapshot :rf2-5hlsh-e2/parent)))
          "the explicit dispatch-back-to-parent escape hatch drove the parent transition"))))

;; ---- (f) malformed :on-error / :error?-without-:final? rejected ------------

(deftest registration-validations
  (testing "a malformed :spawn :on-error is rejected at registration"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error) #":rf.error/machine-bad-on-error-clause"
          (rf/reg-machine :rf2-5hlsh-f/bad-on-error
            {:initial :working
             :states  {:working {:spawn {:machine-id :whatever
                                         :on-error   42}}}}))))     ;; not a transition spec
  (testing ":error? on a NON-final state is rejected"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error) #":rf.error/machine-error-flag-without-final"
          (rf/reg-machine :rf2-5hlsh-f/bad-error-flag
            {:initial :a
             :states  {:a {:error? true
                           :on     {:go :b}}
                       :b {}}}))))
  (testing "a dangling :on-error action ref is rejected at registration"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error) #":rf.error/machine-unresolved-action"
          (rf/reg-machine :rf2-5hlsh-f/dangling-action
            {:initial :working
             :states  {:working {:spawn {:machine-id :whatever
                                         :on-error   {:target :errored
                                                      :action :no-such-action}}}
                       :errored {}}}))))
  (testing "a well-formed :error? :final? leaf + :on-error validates silently"
    (is (some? (rf/reg-machine :rf2-5hlsh-f/ok
                 {:initial :working
                  :states  {:working {:spawn {:machine-id :whatever
                                              :on-error {:target :errored}}}
                            :errored {:final? true :error? true}}})))))

;; NOTE — parallel-PARENT region :on-error. The `pick-spawn-error-transition`
;; resolver strips the region-name prefix off the invoke-id so a `:spawn`
;; declared inside a parallel REGION resolves its `:on-error` region-scoped
;; (mirroring `pick-after-transition`). The end-to-end parallel-region path is
;; NOT exercised here because a parallel region's DECLARATIVE-`:spawn` child
;; currently keys its `[:rf/runtime :machines :spawned …]` slot under the
;; `:rf/transition-pure` parent-id fallback (the region-spec's `:rf/parent-id`
;; is unset at the spawn reducer) — a PRE-EXISTING parallel-region-spawn quirk
;; that affects `:on-done` identically, orthogonal to `:on-error`. Filed as a
;; follow-up. The resolver code is defensively correct for the day that quirk
;; is fixed; a flat/compound parent (the dominant case, every test above)
;; exercises the full path.
