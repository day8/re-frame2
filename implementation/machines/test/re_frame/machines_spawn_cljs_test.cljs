(ns re-frame.machines-spawn-cljs-test
  "CLJS-side coverage for declarative `:spawn` (child-machine spawning)
  under the Reagent reactive substrate.

  Mirrors the conformance fixture
  ../spec/conformance/fixtures/spawn-on-entry-destroy-on-exit.edn —
  entering a state with `:spawn` emits a `:rf.machine/spawn` fx (observable
  as `:rf.machine.spawn/spawned` trace); exiting emits `:rf.machine/destroy`
  (observable as `:rf.machine/destroyed` trace).

  Concerns covered:
    - `:spawn` spawns child on entry and destroys it on exit; the
      deterministic actor id is tracked in the runtime spawn-registry slot
      (the on-spawn callback is advisory — its return is dropped).
    - `:spawn :data` fn-form materialised at spawn: the spawned
      child receives the result map, not the fn; fn sees the post-action
      snapshot.
    - State-level `:after` on a `:spawn`-bearing state:
      synthetic timer-elapsed cancels the child via the standard exit
      cascade and transitions the parent.
    - `:timeout-ms` on `:spawn` / `:spawn-all` is rejected at registration
      with `:rf.error/spawn-timeout-ms-removed`.

  The on-spawn callback fires inline during `apply-transition-once` (advisory
  — its return is dropped); the deterministic child id is read back from the
  runtime spawn-registry slot at
  `[:rf.runtime/machines :spawned <parent> <invoke-id>]`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path. The per-section
;; trace captures below keep their raw trace.tooling register/unregister: they
;; reset and re-scope the capture mid-test (interleaved with assertions), which
;; the scope-macro / :each-fixture forms cannot express.
(def ^:private snapshot mtest/snapshot)

(deftest machine-spawn-cljs
  (testing ":spawn spawns child on entry and destroys it on exit"
    (let [machine
          {:initial :idle
           :data    {:credentials {:user "alice" :pass "secret"}}
           :on-spawn-actions
           ;; Per Spec 005 §Declarative :spawn: on-spawn callback takes a
           ;; single context-map. The callback is advisory — its return is
           ;; DROPPED and the runtime tracks the spawned id at
           ;; [:rf.runtime/machines :spawned <parent> <invoke-id>]
           ;; regardless. Returns nil (observational; a non-nil dropped
           ;; return warns).
           {:auth/record-actor (fn [{:keys [id]}]
                                 (js/console.debug "spawned" id) nil)}
           :states
           {:idle
            {:on {:submit :authenticating}}

            :authenticating
            {:spawn {:machine-id :http/post
                      :data       {:url "/api/login"
                                   :body {:user "alice" :pass "secret"}}
                      :on-spawn   :auth/record-actor
                      :start      [:begin]}
             :on    {:auth/succeeded :authenticated
                     :auth/failed    :idle}}

            :authenticated {}}}
          ;; The spawned child TYPE must be REGISTERED before it is spawned:
          ;; an unregistered `:machine-id` rejects fail-closed with
          ;; `:rf.error/machine-spawn-unregistered-type`. Register a minimal
          ;; `:http/post` child so the spawn is accepted — matching the JVM
          ;; `spawn_registry_test`'s `(reg-machine :http/post …)`.
          child  {:initial :running :data {} :states {:running {}}}
          traces (atom [])]
      (rf/reg-machine :http/post  child)
      (rf/reg-machine :auth3/flow machine)
      ;; Initial state :idle with the credentials fixture data is
      ;; synthesised on first dispatch; no seed required.
      ;; Entering :authenticating: :rf.machine/spawn fx fires
      ;; (→ :rf.machine.spawn/spawned trace), :on-spawn callback records the
      ;; deterministic actor id into :data.:pending.
      (trace-tooling/register-listener! ::inv (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:auth3/flow [:submit]])
      (let [s (snapshot :auth3/flow)]
        (is (= :authenticating (:state s)))
        ;; `:on-spawn` is purely advisory — the runtime tracks the spawned
        ;; id at [:rf.runtime/machines :spawned <parent> <invoke-id>] instead
        ;; of relying on the user-supplied callback to write into `:data`.
        (is (= :http/post#1
               (get-in (rf/runtime-db-value :rf/default)
                       [:rf.runtime/machines :spawned :auth3/flow [:authenticating]]))
            "runtime-tracked spawn slot binds the deterministic actor id"))
      (is (some (fn [ev]
                  (and (= :rf.machine.spawn/spawned (:operation ev))
                       (= :http/post (:machine-id (:tags ev)))))
                @traces)
          "expected :rf.machine.spawn/spawned trace from the :rf.machine/spawn fx")
      ;; Exiting :authenticating via :auth/failed: :rf.machine/destroy fx
      ;; fires targeting the recorded actor id.
      (reset! traces [])
      (rf/dispatch-sync [:auth3/flow [:auth/failed]])
      (trace-tooling/unregister-listener! ::inv)
      (is (= :idle (:state (snapshot :auth3/flow))))
      (is (some (fn [ev]
                  (and (= :rf.machine/destroyed (:operation ev))
                       (= :http/post#1 (:actor-id (:tags ev)))))
                @traces)
          "expected :rf.machine/destroyed trace targeting :http/post#1"))))

;; ---- two-axis spawn observation -----------------------------------------
;; A spawn emits TWO traces: the fx-substrate observation
;; `:rf.machine.spawn/spawned` (the spawn fx ran) AND the registrar-substrate
;; observation `:rf.machine.lifecycle/spawned` (the actor's snapshot landed in
;; the registrar). The latter is the symmetric `spawned` half of the
;; created/spawned/destroyed lifecycle triple and carries `:spawned-id` +
;; `:state` (initial state) so observers + the Xray managed-fx INVOKE adapter
;; can render the actor without re-reading app-db. Per 009 §Two-axis machine
;; observation.

(deftest machine-spawn-two-axis-cljs
  (testing "a spawn emits BOTH :rf.machine.spawn/spawned (fx) and :rf.machine.lifecycle/spawned (registrar)"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :data    {}
                  :states  {:idle    {:on {:go :working}}
                            :working {:spawn {:machine-id :qpuk4/worker}}}}
          traces (atom [])]
      (rf/reg-machine :qpuk4/worker child)
      (rf/reg-machine :qpuk4/sup    parent)
      (trace-tooling/register-listener! ::two-axis (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:qpuk4/sup [:go]])
      (trace-tooling/unregister-listener! ::two-axis)
      ;; fx-substrate axis
      (is (some (fn [ev]
                  (and (= :rf.machine.spawn/spawned (:operation ev))
                       (= :qpuk4/worker (:machine-id (:tags ev)))))
                @traces)
          "fx-substrate axis: expected :rf.machine.spawn/spawned")
      ;; registrar-substrate axis — the round-trip the Xray consumer keys on
      (is (some (fn [ev]
                  (and (= :rf.machine.lifecycle/spawned (:operation ev))
                       (= :qpuk4/worker   (:machine-id (:tags ev)))
                       (= :qpuk4/worker#1 (:spawned-id (:tags ev)))
                       (= :running        (:state (:tags ev)))))
                @traces)
          "registrar-substrate axis: expected :rf.machine.lifecycle/spawned carrying :spawned-id + initial :state"))))

;; ---- :spawn :data fn-form materialised at spawn -------------------------
;; Per Spec 005 §Spec-spec keys (line 1503/1511): `:data` admits a function
;; form `(fn [snap ev] data)` so the spawned child's initial data can be
;; derived from the parent's post-action snapshot + the triggering event.
;; The runtime materialises the fn before passing the value to the
;; spawn-fx (which expects a literal map).

(deftest machine-spawn-data-fn-form-cljs
  (testing "fn-form `:data` is materialised — spawned child receives the result map, NOT the fn"
    (let [child   {:initial :running :data {} :states {:running {}}}
          parent  {:initial :idle
                   :data    {:endpoint "/api/login"}
                   :states
                   {:idle    {:on {:start :working}}
                    :working {:spawn {:machine-id :h131/worker
                                       :data       (fn [{snap :snapshot}]
                                                     {:url    (-> snap :data :endpoint)
                                                      :method :post})}}}}]
      (rf/reg-machine :h131/worker child)
      (rf/reg-machine :h131/sup parent)
      (rf/dispatch-sync [:h131/sup [:start]])
      (let [child-data (:data (snapshot :h131/worker#1))]
        (is (map? child-data)
            "spawned child's :data is a literal map, not the fn")
        (is (= "/api/login" (:url child-data))
            "fn-form derived :url from the parent's :data.:endpoint")
        (is (= :post (:method child-data))
            "fn-form-derived :method survived the spawn"))))
  (testing "fn-form `:data` sees the post-action snapshot (Spec 005:1511)"
    (let [child   {:initial :running :data {} :states {:running {}}}
          parent  {:initial :idle
                   :data    {:base "https://api.example.com"}
                   :actions {:assemble (fn [{data :data}]
                                         {:data (assoc data :endpoint
                                                       (str (:base data) "/v1/me"))})}
                   :states
                   {:idle    {:on {:go {:target :working :action :assemble}}}
                    :working {:spawn {:machine-id :h131b/worker
                                       :data       (fn [{snap :snapshot}]
                                                     {:url (-> snap :data :endpoint)})}}}}]
      (rf/reg-machine :h131b/worker child)
      (rf/reg-machine :h131b/sup parent)
      (rf/dispatch-sync [:h131b/sup [:go]])
      (is (= "https://api.example.com/v1/me"
             (:url (:data (snapshot :h131b/worker#1))))
          "fn-form saw the :data writes the transition's :action made"))))

;; ---- state-level :after on :spawn-bearing state -------------------------
;; Per Spec 005 §Wall-clock timeouts on :spawn — use parent state's :after.
;; Wall-clock guards on a spawn are expressed via :after on the
;; :spawn-bearing state itself (:spawn / :spawn-all carry no :timeout-ms
;; slot). When :after fires, the standard exit cascade tears down the
;; spawned child via :rf.machine/destroy.

(deftest machine-after-on-invoke-cljs
  (testing ":after on a :spawn-bearing state — synthetic timer-elapsed cancels child + transitions"
    (let [child  {:initial :running
                  :states  {:running {:on {:never-fires :done}}
                            :done    {}}}
          parent {:initial :idle
                  :data    {}
                  :on-spawn-actions
                  ;; Advisory observation hook — returns nil.
                  {:record (fn [{:keys [id]}] (js/console.debug "spawned" id) nil)}
                  :states
                  {:idle {:on {:go :authenticating}}
                   :authenticating
                   {:spawn {:machine-id :child/auth-after
                             :on-spawn   :record}
                    :after  {30000 :timed-out}
                    :on    {:auth/succeeded :authenticated}}
                   :authenticated {}
                   :timed-out     {}}}
          traces (atom [])]
      (rf/reg-machine :child/auth-after child)
      (rf/reg-machine :sup/auth-after  parent)
      (trace-tooling/register-listener! ::ato (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:sup/auth-after [:go]])
      (is (= :authenticating (:state (snapshot :sup/auth-after)))
          "parent transitioned :idle → :authenticating")
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/scheduled (:operation ev))
                       (= 30000   (:delay (:tags ev)))
                       (= :literal (:delay-source (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/scheduled with :delay-source :literal")
      (let [child-id (get-in (rf/runtime-db-value :rf/default)
                             [:rf.runtime/machines :spawned :sup/auth-after [:authenticating]])
            epoch    (get-in (snapshot :sup/auth-after) [:data :rf/after-epoch [:authenticating]])]
        (is (some? child-id) "spawn slot bound to the spawned child id")
        (reset! traces [])
        ;; Synthetically dispatch the :after-elapsed timer event with the
        ;; current epoch + decl-path — mirrors the wall-clock setTimeout firing.
        (rf/dispatch-sync [:sup/auth-after [:rf.machine.timer/after-elapsed 30000 epoch [:authenticating]]])
        (is (= :timed-out (:state (snapshot :sup/auth-after)))
            "parent transitioned :authenticating → :timed-out via :after firing")
        (is (nil? (get-in (rf/runtime-db-value :rf/default)
                          [:rf.runtime/machines :snapshots child-id]))
            "child machine snapshot torn down by the standard exit cascade"))
      (trace-tooling/unregister-listener! ::ato))))

;; ---- :timeout-ms on :spawn / :spawn-all is rejected -------------------

(deftest machine-spawn-timeout-ms-removed-cljs
  (testing ":timeout-ms on :spawn is rejected with :rf.error/spawn-timeout-ms-removed"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :running}}
                         :running {:spawn {:machine-id :stub
                                            :timeout-ms 1000
                                            :on-timeout [:never]}}}}]
      (is (thrown-with-msg? js/Error
                            #"spawn-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad-invoke bad)))))
  (testing ":on-timeout alone on :spawn is also rejected"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :running}}
                         :running {:spawn {:machine-id :stub
                                            :on-timeout [:never]}}}}]
      (is (thrown-with-msg? js/Error
                            #"spawn-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad-on-to bad)))))
  (testing ":timeout-ms on :spawn-all is rejected"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :h}}
                         :h    {:spawn-all
                                {:children
                                 [{:id :a :machine-id :stub}]
                                 :join             :all
                                 :on-child-done    :done
                                 :on-child-error   :failed
                                 :on-all-complete  [:done!]
                                 :timeout-ms       5000
                                 :on-timeout       [:to]}}}}]
      (is (thrown-with-msg? js/Error
                            #"spawn-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad-invoke-all bad))))))
