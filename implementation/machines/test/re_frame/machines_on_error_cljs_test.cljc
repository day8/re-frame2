(ns re-frame.machines-on-error-cljs-test
  "First-class `:spawn :on-error` (XState v5 invoke `onError`).

  When a `:spawn`-spawned child FAILS, the runtime routes the failure to the
  spawning parent's `:spawn :on-error` TRANSITION (control flow — a declarative
  parent state change), SYMMETRIC with the `:spawn :on-done` teardown
  hook. Two triggers:

    (1) the child reaches a designated ERROR `:final?` leaf (`:error? true`) —
        the error payload (`:output-key` slot) rides into the `:on-error`
        transition's `:event`;
    (2) an uncaught child action exception
        (`:rf.error/machine-action-exception`) — the exception envelope rides
        into the `:on-error` transition's `:event`.

  `:on-error` works alongside both observability surfaces: the trace emission
  and the explicit dispatch-back-to-parent escape hatch both keep working;
  `:on-error` is the declarative invoke-site control-flow form.

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
        registration;
    (g) parallel-PARENT region `:spawn` — a `:spawn` declared
        inside a parallel REGION keys its `:spawned` slot under the REAL
        parent (not `:rf/transition-pure`), so BOTH the region's `:spawn
        :on-done` and `:spawn :on-error` resolve REGION-SCOPED end-to-end
        (error `:final?` leaf AND uncaught child-action exception), with the
        sibling region untouched.
    (h) parallel-region explicit `:on {:rf.machine.spawn/error …}` escape
        hatch is REGION-scoped — a sibling region's explicit
        handler must NOT catch another region's child failure.

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
   [re-frame.machines.test-support :as mtest]
   [re-frame.trace.tooling :as trace-tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

(defn- spawned-id-for
  [parent-id invoke-id]
  (get-in (rf/runtime-db-value :rf/default)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

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

;; ---- (g) parallel-PARENT region :spawn :on-done / :on-error -----
;;
;; A declarative `:spawn` declared INSIDE a parallel REGION keys its
;; `[:rf.runtime/machines :spawned <parent> <invoke-id>]` slot under the REAL
;; parent machine-id (the parallel machine itself), NOT the
;; `:rf/transition-pure` fallback. `parallel/reduce-regions` re-stamps the live
;; parent-id onto the synthetic region-spec, so both `:spawn :on-done` AND
;; `:spawn :on-error` resolve region-scoped end-to-end. The
;; resolvers (`pick-spawn-done-transition` / `pick-spawn-error-transition`)
;; strip the region-name prefix off the invoke-id so the hook fires at the
;; region's own state level — exactly as `pick-after-transition` does.
;;
;; The region's child carries its `:data :rf/parent-id` as the real parent so
;; both hooks resolve the parent from the child's finalize. These tests assert
;; the slot keys under the real parent and both hooks fire region-scoped.

(deftest parallel-region-spawn-keys-slot-under-real-parent
  (testing "a :spawn declared inside a parallel region keys its :spawned slot under the REAL parent id (not :rf/transition-pure) and the child's :data :rf/parent-id is the real parent (rf2-r09fc)"
    (rf/reg-machine :rf2-r09fc-g0/child
      {:initial :running
       :data    {}
       :states  {:running {:on {:ok :done}}
                 :done    {:final? true}}})
    (rf/reg-machine :rf2-r09fc-g0/parent
      {:type    :parallel
       :data    {}
       :regions {:loader {:initial :working
                          :states  {:working {:spawn {:machine-id :rf2-r09fc-g0/child}}}}
                 :other  {:initial :idle
                          :states  {:idle {}}}}})
    (rf/dispatch-sync [:rf2-r09fc-g0/parent [:rf.machine.spawn/spawned]])
    ;; The region prefixes the invoke-id with its region name → [:loader :working].
    (let [spawned-map (get-in (rf/runtime-db-value :rf/default)
                              [:rf.runtime/machines :spawned :rf2-r09fc-g0/parent])
          child       (spawned-id-for :rf2-r09fc-g0/parent [:loader :working])]
      (is (some? spawned-map)
          "the :spawned slot keys under the REAL parent :rf2-r09fc-g0/parent (NOT :rf/transition-pure)")
      (is (nil? (get-in (rf/runtime-db-value :rf/default)
                        [:rf.runtime/machines :spawned :rf/transition-pure]))
          "NOTHING keyed under the bogus :rf/transition-pure fallback")
      (is (some? child)
          "the region-prefixed invoke-id [:loader :working] addresses the spawned child")
      (is (= :rf2-r09fc-g0/parent (get-in (snapshot child) [:data :rf/parent-id]))
          "the child's :data :rf/parent-id is the real parent (was bogus :rf/transition-pure)"))))

(deftest parallel-region-spawn-on-done-fires-region-scoped
  (testing "a region's :spawn :on-done fires region-scoped when the child reaches a success :final? leaf (rf2-r09fc)"
    (rf/reg-machine :rf2-r09fc-g1/child
      {:initial :running
       :data    {}
       :states  {:running {:on {:ok {:target :done
                                     :action (fn [{data :data ev :event}]
                                               {:data (assoc data :tok (second ev))})}}}
                 :done    {:final?     true
                           :output-key :tok}}})
    (rf/reg-machine :rf2-r09fc-g1/parent
      {:type    :parallel
       :data    {}
       :regions {:loader {:initial :working
                          :states  {:working {:spawn {:machine-id :rf2-r09fc-g1/child
                                                      ;; :on-done runs against the success
                                                      ;; result; record it into shared :data.
                                                      :on-done (fn [{data :data result :result}]
                                                                 (assoc data :got result))}
                                              ;; the region transitions when the
                                              ;; spawn-done escape event arrives.
                                              :on    {:loaded :ready}}
                                    :ready   {}}}
                 :other  {:initial :idle
                          :states  {:idle {}}}}})
    (rf/dispatch-sync [:rf2-r09fc-g1/parent [:rf.machine.spawn/spawned]])
    (let [child (spawned-id-for :rf2-r09fc-g1/parent [:loader :working])]
      (is (some? child) "child spawned under the real parent")
      (rf/dispatch-sync [child [:ok :the-token]])
      (is (= :the-token (get-in (snapshot :rf2-r09fc-g1/parent) [:data :got]))
          "the region's :spawn :on-done ran against the child's success result — region-scoped")
      (is (nil? (snapshot child))
          "the finished child auto-destroyed (reached its success :final? leaf)"))))

(deftest parallel-region-spawn-on-error-fires-region-scoped-error-leaf
  (testing "a region's :spawn :on-error fires region-scoped when the child reaches an :error? :final? leaf (rf2-r09fc)"
    (rf/reg-machine :rf2-r09fc-g2/child
      {:initial :running
       :data    {}
       :states  {:running {:on {:boom {:target :failed
                                       :action (fn [{data :data ev :event}]
                                                 {:data (assoc data :err (second ev))})}}}
                 :failed  {:final?     true
                           :error?     true
                           :output-key :err}}})
    (rf/reg-machine :rf2-r09fc-g2/parent
      {:type    :parallel
       :data    {}
       :regions {:loader {:initial :working
                          :states  {:working {:spawn {:machine-id :rf2-r09fc-g2/child
                                                      ;; :on-error is a region-scoped transition
                                                      ;; resolved at :working's level — :errored is
                                                      ;; a sibling leaf WITHIN this region.
                                                      :on-error {:target :errored
                                                                 :action (fn [{data :data ev :event}]
                                                                           {:data (assoc data :captured (nth ev 2))})}}}
                                    :errored {}}}
                 :other  {:initial :idle
                          :states  {:idle {}}}}})
    (rf/dispatch-sync [:rf2-r09fc-g2/parent [:rf.machine.spawn/spawned]])
    (let [child (spawned-id-for :rf2-r09fc-g2/parent [:loader :working])]
      (is (some? child) "child spawned under the real parent")
      (rf/dispatch-sync [child [:boom :network-down]])
      (is (= :errored (get-in (snapshot :rf2-r09fc-g2/parent) [:state :loader]))
          "the :loader region moved to :errored — its :spawn :on-error fired region-scoped")
      (is (= :idle (get-in (snapshot :rf2-r09fc-g2/parent) [:state :other]))
          "the sibling :other region is untouched — :on-error is region-local")
      (is (= :network-down (get-in (snapshot :rf2-r09fc-g2/parent) [:data :captured]))
          "the error payload (child's :output-key slot) rode into the :on-error transition's :event")
      (is (nil? (snapshot child))
          "the failed child auto-destroyed (reached its :error? :final? leaf)"))))

(deftest parallel-region-spawn-on-error-fires-on-uncaught-child-action-exception
  (testing "an uncaught child action exception drives the region's :spawn :on-error region-scoped (rf2-r09fc)"
    (rf/reg-machine :rf2-r09fc-g3/child
      {:initial :running
       :data    {}
       :states  {:running {:on {:go {:target :next
                                     :action (fn [_] (throw (ex-info "kaboom" {:why :test})))}}}
                 :next    {}}})
    (rf/reg-machine :rf2-r09fc-g3/parent
      {:type    :parallel
       :data    {}
       :regions {:loader {:initial :working
                          :states  {:working {:spawn {:machine-id :rf2-r09fc-g3/child
                                                      :on-error {:target :errored}}}
                                    :errored {}}}
                 :other  {:initial :idle
                          :states  {:idle {}}}}})
    (rf/dispatch-sync [:rf2-r09fc-g3/parent [:rf.machine.spawn/spawned]])
    (let [child (spawned-id-for :rf2-r09fc-g3/parent [:loader :working])]
      (is (some? child) "child spawned under the real parent")
      (rf/dispatch-sync [child [:go]])
      (is (= :errored (get-in (snapshot :rf2-r09fc-g3/parent) [:state :loader]))
          "the uncaught child action exception drove the :loader region's :spawn :on-error :target")
      (is (= :idle (get-in (snapshot :rf2-r09fc-g3/parent) [:state :other]))
          "the sibling :other region is untouched — :on-error is region-local"))))

;; ---- (h) explicit :on {:rf.machine.spawn/error …} escape hatch is REGION-scoped ----
;;
;; The spawn-error broadcast reaches EVERY region's resolver
;; (`drain-parent-queue`), so the explicit `:on {:rf.machine.spawn/error …}`
;; escape-hatch arm of `pick-spawn-error-transition` declines outright in a
;; FOREIGN region (a region whose name does not match the invoke-id head),
;; SYMMETRIC with the `:spawn :on-error` arm and with `pick-done-transition`'s
;; region-identity `decline-region?` gate. A foreign region nils its invoke-id
;; (disabling the `:spawn :on-error` arm) AND declines the explicit-`:on` arm,
;; so a SIBLING region's explicit handler never catches another region's child
;; failure — upholding XState v5 `invoke onError` region scoping.

(deftest parallel-region-explicit-on-spawn-error-is-region-scoped
  (testing "an explicit :on {:rf.machine.spawn/error …} in a sibling region does NOT catch another region's child failure (rf2-w84jv)"
    (rf/reg-machine :rf2-w84jv-h/child
      {:initial :running
       :data    {}
       :states  {:running {:on {:boom {:target :failed}}}
                 :failed  {:final?     true
                           :error?     true
                           :output-key :err}}})
    ;; The synthetic spawn-error event is only DISPATCHED when the spawning
    ;; parent declares `:spawn :on-error` (finalize / registration gate on its
    ;; presence). To reach the explicit-`:on` escape-hatch arm we give :loader
    ;; a `:spawn :on-error` whose GUARD fails (so the headline arm misses and
    ;; the event falls through to the explicit-`:on` walk). :loader's own
    ;; explicit `:on {:rf.machine.spawn/error :handled}` then catches it
    ;; in-region; the sibling :other declares a DECOY explicit handler that
    ;; must NEVER fire — the failure belongs to :loader's region, and the
    ;; explicit escape hatch is region-scoped.
    (rf/reg-machine :rf2-w84jv-h/parent
      {:type    :parallel
       :data    {}
       :guards  {:never (fn [_] false)}
       :regions {:loader {:initial :working
                          :states  {:working {:spawn {:machine-id :rf2-w84jv-h/child
                                                      :on-error {:target :unreached
                                                                 :guard  :never}}
                                              :on    {:rf.machine.spawn/error :handled}}
                                    :unreached {}
                                    :handled   {}}}
                 :other  {:initial :idle
                          :states  {:idle {:on {:rf.machine.spawn/error :bad}}
                                    :bad  {}}}}})
    (rf/dispatch-sync [:rf2-w84jv-h/parent [:rf.machine.spawn/spawned]])
    (let [child (spawned-id-for :rf2-w84jv-h/parent [:loader :working])]
      (is (some? child) "child spawned under the real parent in the :loader region")
      (rf/dispatch-sync [child [:boom]])
      (is (= :handled (get-in (snapshot :rf2-w84jv-h/parent) [:state :loader]))
          "the :loader region's own explicit :on {:rf.machine.spawn/error …} caught its child's failure (its guarded :on-error missed → fell through to the in-region explicit :on)")
      (is (= :idle (get-in (snapshot :rf2-w84jv-h/parent) [:state :other]))
          "the sibling :other region's DECOY explicit :on handler did NOT fire — the explicit escape hatch is region-scoped"))))
