(ns re-frame.machines-invoke-cljs-test
  "CLJS-side coverage for declarative `:invoke` (child-machine spawning)
  under the Reagent reactive substrate.

  Mirrors the conformance fixture
  ../spec/conformance/fixtures/invoke-spawn-on-entry-destroy-on-exit.edn —
  entering a state with `:invoke` emits a `:rf.machine/spawn` fx (observable
  as `:rf.machine/spawned` trace); exiting emits `:rf.machine/destroy`
  (observable as `:rf.machine/destroyed` trace).

  Concerns covered:
    - `:invoke` spawns child on entry and destroys it on exit; on-spawn
      callback records the deterministic actor id into parent's `:data`.
    - `:invoke :data` fn-form materialised at spawn (rf2-h131): the spawned
      child receives the result map, not the fn; fn sees the post-action
      snapshot.
    - State-level `:after` on an `:invoke`-bearing state (rf2-3y3y):
      synthetic timer-elapsed cancels the child via the standard exit
      cascade and transitions the parent.
    - `:timeout-ms` on `:invoke` / `:invoke-all` is rejected at registration
      with `:rf.error/invoke-timeout-ms-removed` (rf2-3y3y).

  The on-spawn callback fires inline during `apply-transition-once` so the
  child id can be recorded into the parent machine's `:data` — we assert via
  the snapshot's `:pending` key.

  Split out of `machines_cljs_test.cljs` (rf2-3vps4)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- snapshot
  "Read the snapshot for `machine-id` from the default frame's app-db."
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

(deftest machine-invoke-cljs
  (testing ":invoke spawns child on entry and destroys it on exit"
    (let [machine
          {:initial :idle
           :data    {:credentials {:user "alice" :pass "secret"}}
           :on-spawn-actions
           ;; Per Spec 005 §Declarative :invoke (rf2-een2 / rf2-smba):
           ;; on-spawn callback signature is (fn [data spawned-id] new-data).
           ;; The runtime patches the returned data back into the snapshot.
           {:auth/record-actor (fn [data actor-id]
                                 (assoc data :pending actor-id))}
           :states
           {:idle
            {:on {:submit :authenticating}}

            :authenticating
            {:invoke {:machine-id :http/post
                      :data       {:url "/api/login"
                                   :body {:user "alice" :pass "secret"}}
                      :on-spawn   :auth/record-actor
                      :start      [:begin]}
             :on    {:auth/succeeded :authenticated
                     :auth/failed    :idle}}

            :authenticated {}}}
          traces (atom [])]
      (rf/reg-machine :auth3/flow machine)
      ;; Initial state :idle with the credentials fixture data is
      ;; synthesised on first dispatch; no seed required.
      ;; Entering :authenticating: :rf.machine/spawn fx fires
      ;; (→ :rf.machine/spawned trace), :on-spawn callback records the
      ;; deterministic actor id into :data.:pending.
      (trace-tooling/register-trace-listener! ::inv (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:auth3/flow [:submit]])
      (let [s (snapshot :auth3/flow)]
        (is (= :authenticating (:state s)))
        (is (= :http/post#1 (get-in s [:data :pending]))
            "on-spawn callback recorded the deterministic actor id"))
      (is (some (fn [ev]
                  (and (= :rf.machine/spawned (:operation ev))
                       (= :http/post (:machine-id (:tags ev)))))
                @traces)
          "expected :rf.machine/spawned trace from the :rf.machine/spawn fx")
      ;; Exiting :authenticating via :auth/failed: :rf.machine/destroy fx
      ;; fires targeting the recorded actor id.
      (reset! traces [])
      (rf/dispatch-sync [:auth3/flow [:auth/failed]])
      (trace-tooling/unregister-trace-listener! ::inv)
      (is (= :idle (:state (snapshot :auth3/flow))))
      (is (some (fn [ev]
                  (and (= :rf.machine/destroyed (:operation ev))
                       (= :http/post#1 (:actor-id (:tags ev)))))
                @traces)
          "expected :rf.machine/destroyed trace targeting :http/post#1"))))

;; ---- :invoke :data fn-form materialised at spawn (rf2-h131) --------------
;; Per Spec 005 §Spec-spec keys (line 1503/1511): `:data` admits a function
;; form `(fn [snap ev] data)` so the spawned child's initial data can be
;; derived from the parent's post-action snapshot + the triggering event.
;; The runtime materialises the fn before passing the value to the
;; spawn-fx (which expects a literal map).

(deftest machine-invoke-data-fn-form-cljs
  (testing "fn-form `:data` is materialised — spawned child receives the result map, NOT the fn"
    (let [child   {:initial :running :data {} :states {:running {}}}
          parent  {:initial :idle
                   :data    {:endpoint "/api/login"}
                   :states
                   {:idle    {:on {:start :working}}
                    :working {:invoke {:machine-id :h131/worker
                                       :data       (fn [snap _]
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
                   :actions {:assemble (fn [data _]
                                         {:data (assoc data :endpoint
                                                       (str (:base data) "/v1/me"))})}
                   :states
                   {:idle    {:on {:go {:target :working :action :assemble}}}
                    :working {:invoke {:machine-id :h131b/worker
                                       :data       (fn [snap _]
                                                     {:url (-> snap :data :endpoint)})}}}}]
      (rf/reg-machine :h131b/worker child)
      (rf/reg-machine :h131b/sup parent)
      (rf/dispatch-sync [:h131b/sup [:go]])
      (is (= "https://api.example.com/v1/me"
             (:url (:data (snapshot :h131b/worker#1))))
          "fn-form saw the :data writes the transition's :action made"))))

;; ---- state-level :after on :invoke-bearing state (rf2-3y3y) --------------
;; Per Spec 005 §Wall-clock timeouts on :invoke — use parent state's :after.
;; The pre-rf2-3y3y :timeout-ms slot on :invoke / :invoke-all is dropped;
;; wall-clock guards are expressed via :after on the :invoke-bearing state
;; itself. When :after fires, the standard exit cascade tears down the
;; spawned child via :rf.machine/destroy.

(deftest machine-after-on-invoke-cljs
  (testing ":after on an :invoke-bearing state — synthetic timer-elapsed cancels child + transitions"
    (let [child  {:initial :running
                  :states  {:running {:on {:never-fires :done}}
                            :done    {}}}
          parent {:initial :idle
                  :data    {:rf/after-epoch 0}
                  :on-spawn-actions
                  {:record (fn [data id] (assoc data :pending id))}
                  :states
                  {:idle {:on {:go :authenticating}}
                   :authenticating
                   {:invoke {:machine-id :child/auth-after
                             :on-spawn   :record}
                    :after  {30000 :timed-out}
                    :on    {:auth/succeeded :authenticated}}
                   :authenticated {}
                   :timed-out     {}}}
          traces (atom [])]
      (rf/reg-machine :child/auth-after child)
      (rf/reg-machine :sup/auth-after  parent)
      (trace-tooling/register-trace-listener! ::ato (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:sup/auth-after [:go]])
      (is (= :authenticating (:state (snapshot :sup/auth-after)))
          "parent transitioned :idle → :authenticating")
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/scheduled (:operation ev))
                       (= 30000   (:delay (:tags ev)))
                       (= :literal (:delay-source (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/scheduled with :delay-source :literal")
      (let [child-id (get-in (rf/get-frame-db :rf/default)
                             [:rf/spawned :sup/auth-after [:authenticating]])
            epoch    (get-in (snapshot :sup/auth-after) [:data :rf/after-epoch])]
        (is (some? child-id) "spawn slot bound to the spawned child id")
        (reset! traces [])
        ;; Synthetically dispatch the :after-elapsed timer event with the
        ;; current epoch — mirrors the wall-clock setTimeout firing.
        (rf/dispatch-sync [:sup/auth-after [:rf.machine.timer/after-elapsed 30000 epoch]])
        (is (= :timed-out (:state (snapshot :sup/auth-after)))
            "parent transitioned :authenticating → :timed-out via :after firing")
        (is (nil? (get-in (rf/get-frame-db :rf/default)
                          [:rf/machines child-id]))
            "child machine snapshot torn down by the standard exit cascade"))
      (trace-tooling/unregister-trace-listener! ::ato))))

;; ---- :timeout-ms on :invoke / :invoke-all is rejected (rf2-3y3y) ---------

(deftest machine-invoke-timeout-ms-removed-cljs
  (testing ":timeout-ms on :invoke is rejected with :rf.error/invoke-timeout-ms-removed"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :running}}
                         :running {:invoke {:machine-id :stub
                                            :timeout-ms 1000
                                            :on-timeout [:never]}}}}]
      (is (thrown-with-msg? js/Error
                            #"invoke-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad-invoke bad)))))
  (testing ":on-timeout alone on :invoke is also rejected"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :running}}
                         :running {:invoke {:machine-id :stub
                                            :on-timeout [:never]}}}}]
      (is (thrown-with-msg? js/Error
                            #"invoke-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad-on-to bad)))))
  (testing ":timeout-ms on :invoke-all is rejected"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :h}}
                         :h    {:invoke-all
                                {:children
                                 [{:id :a :machine-id :stub}]
                                 :join             :all
                                 :on-child-done    :done
                                 :on-child-error   :failed
                                 :on-all-complete  [:done!]
                                 :timeout-ms       5000
                                 :on-timeout       [:to]}}}}]
      (is (thrown-with-msg? js/Error
                            #"invoke-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad-invoke-all bad))))))
