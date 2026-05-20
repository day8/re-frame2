(ns re-frame.http-managed-machine-cljs-test
  "CLJS-side smoke for Spec 014 §Machine-shape wrapper (rf2-ijm7).

  The JVM test (re-frame.http-managed-machine-test) exercises the full
  end-to-end shape against the in-process JDK HTTP server. This file
  confirms that on CLJS:

  - The wrapper machine registration succeeds when `re-frame.machines`
    is on the classpath at http-managed load time.
  - A parent machine `:spawn`ing `:rf.http/managed` with the canned
    success stub fires the wrapper, transitions to `:succeeded`, and
    dispatches `[<parent-id> [:succeeded value]]` back to the parent.

  The Fetch transport itself is covered by the broader CLJS test
  suite; this smoke is scoped to the wrapper's machine-shape envelope
  plus the back-channel to the parent."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; re-frame.machines and re-frame.http-managed cross-publish their
            ;; registration hooks through `re-frame.late-bind` (machines
            ;; publishes `:machines/reg-machine`; http-managed publishes
            ;; `:http/register-managed-machine!`). Either ns can load first;
            ;; whichever loads second triggers the wrapper registration
            ;; (rf2-ijm7). Listing both here makes the dependency closure
            ;; explicit so the bundle includes both producers.
            [re-frame.machines :as machines]
            [re-frame.http-managed :as http-managed]
            ;; rf2-lwmgw — the stub macros / install fn live in
            ;; `re-frame.http-test-support` (alongside the canned-stub fx
            ;; registrations). This test calls `install-managed-request-stubs!`
            ;; directly, so it requires that ns.
            [re-frame.http-test-support :as http-test-support]
            [re-frame.registrar :as registrar]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn (fn []
                (machines/reset-timers!)
                (http-managed/clear-all-in-flight!))}))

(defn- snapshot [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

;; ---- (1) wrapper registration succeeds on classpath ---------------------

(deftest wrapper-is-registered-when-machines-is-present
  (testing "loading both re-frame.machines and re-frame.http-managed registers `:rf.http/managed` as a machine"
    (let [meta (registrar/lookup :event :rf.http/managed)]
      (is (some? meta) ":rf.http/managed is registered as an :event")
      (is (true? (:rf/machine? meta))
          ":rf/machine? metadata flag is set — it's an actual machine, not a vanilla event")
      (let [spec (:rf/machine meta)]
        (is (= :requesting (:initial spec)))
        (is (contains? (:states spec) :requesting))
        (is (contains? (:states spec) :succeeded))
        (is (contains? (:states spec) :failed))
        (is (true? (get-in spec [:states :succeeded :meta :terminal?]))
            ":succeeded carries :meta {:terminal? true} for tooling visibility")
        (is (true? (get-in spec [:states :failed :meta :terminal?])))))))

;; ---- (2) parent :spawn spawns wrapper, registry/snapshot wiring -------

(deftest invoke-spawns-wrapper-and-injects-framework-keys
  (testing "parent :spawn {:machine-id :rf.http/managed ...} spawns the wrapper actor and stamps :rf/parent-id / :rf/self-id / :rf/spawn-id into the wrapper's :data (rf2-ijm7)"
    ;; Install a stub that NEVER replies — gives us a stable
    ;; :requesting snapshot to inspect without racing against Fetch.
    (http-test-support/install-managed-request-stubs! {})
    (try
      (rf/reg-machine :cljs/auth2
        {:initial :idle
         :states
         {:idle {:on {:login :authenticating}}
          :authenticating
          {:spawn {:machine-id :rf.http/managed
                    :data       {:request {:url "/api/me" :method :get}}}
           :on     {:succeeded :authenticated
                    :failed    :idle}}
          :authenticated {}}})
      (rf/dispatch-sync
        [:cljs/auth2 [:login]]
        ;; Route the wrapper actor's outgoing :rf.http/managed fx to the
        ;; stub installed above, so it does NOT issue a real Fetch.
        ;; Per-call fx-overrides apply for the duration of this drain —
        ;; including the wrapper actor's child dispatch that emits the
        ;; underlying fx.
        {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
      (let [db (rf/get-frame-db :rf/default)]
        (is (= :rf.http/managed#1
               (get-in db [:rf/spawned :cljs/auth2 [:authenticating]]))
            "the wrapper actor is bound under the parent's spawn-registry slot")
        (let [wrapper-snap (snapshot :rf.http/managed#1)
              wrapper-data (:data wrapper-snap)]
          (is (= :requesting (:state wrapper-snap))
              "the wrapper is in :requesting awaiting the (stubbed-out) reply")
          (is (= :rf.http/managed#1 (:rf/self-id wrapper-data))
              ":rf/self-id is stamped to the wrapper's own id")
          (is (= :cljs/auth2 (:rf/parent-id wrapper-data))
              ":rf/parent-id is stamped to the parent machine's id (rf2-ijm7)")
          (is (= [:authenticating] (:rf/spawn-id wrapper-data))
              ":rf/spawn-id is stamped to the parent's :spawn-bearing state path")
          (is (= {:url "/api/me" :method :get} (:request wrapper-data))
              "the user's :request is preserved verbatim under :data")))
      (finally
        (http-test-support/uninstall-managed-request-stubs!)))))

;; ---- (3) parent state-exit destroys wrapper child + clears registry ----

(deftest parent-exit-destroys-wrapper-child
  (testing "transition out of the :spawn-bearing state destroys the wrapper actor — registry slot cleared, snapshot gone (rf2-ijm7 + rf2-wvkn destroy cascade)"
    (http-test-support/install-managed-request-stubs! {})  ;; no-match stub: synthesises a failure (which we will not observe)
    (try
      (rf/reg-machine :cljs/cancellable
        {:initial :idle
         :states
         {:idle {:on {:login :authenticating}}
          :authenticating
          {:spawn {:machine-id :rf.http/managed
                    :data       {:request {:url "/never-returns" :method :get}}}
           :on     {:cancel    :idle
                    :succeeded :authenticated
                    :failed    :idle}}
          :authenticated {}}})
      ;; The stub will synthesise a failure (no match), which will be
      ;; dispatched via the router. We cancel BEFORE that dispatch
      ;; drains so the wrapper is in :requesting at the moment of
      ;; cancel. The cancel's destroy cascade tears down the wrapper
      ;; synchronously inside this dispatch-sync.
      (rf/dispatch-sync
        [:cljs/cancellable [:login]]
        {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
      (rf/dispatch-sync [:cljs/cancellable [:cancel]])
      (let [db (rf/get-frame-db :rf/default)]
        (is (nil? (get-in db [:rf/spawned :cljs/cancellable [:authenticating]]))
            "spawn-registry slot cleared by the destroy cascade")
        (is (nil? (get-in db [:rf/machines :rf.http/managed#1]))
            "wrapper actor's snapshot is gone after the parent's cancel"))
      (finally
        (http-test-support/uninstall-managed-request-stubs!)))))
