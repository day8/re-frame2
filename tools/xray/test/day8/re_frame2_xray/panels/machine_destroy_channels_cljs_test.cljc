(ns day8.re-frame2-xray.panels.machine-destroy-channels-cljs-test
  "Actual-consumer counterfixture for the DISJOINT
  machine-destroy channels.

  Spec 009 §`:op-type` vocabulary freezes a channel/reason MATRIX: each
  teardown cause rides exactly ONE of the two destroy channels.

    - `:rf.machine.lifecycle/destroyed` (registrar-substrate) — frame-exit
      reaping only, always `:reason :parent-frame-destroyed`.
    - `:rf.machine/destroyed` (fx-substrate) — every other teardown.

  The machines artefact pins that matrix against its own emit sites
  (`re-frame.destroyed-reason-channel-conformance-test`). This fixture pins
  the CONSUMER end: the machines runtime is DRIVEN, and the trace events it
  really emits are fed into Xray's production projections. No hand-authored
  destroy event sits between producer and consumer, so if an emitter moves a
  reason to the other channel — or Xray validates channel and reason
  independently — these tests go red.

  The complementary negative cross-products (the tuples the matrix forbids)
  are asserted in `cancellation-cascade-helpers-cljs-test`, which can author
  the impossible events the runtime cannot produce."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [day8.re-frame2-xray.panels.cancellation-cascade-helpers :as cascade]
   [day8.re-frame2-xray.panels.managed-fx-helpers :as managed-fx]
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- record-traces!
  "Register a raw trace listener accumulating every emitted event."
  [k]
  (let [a (atom [])]
    (rf.trace.tooling/register-listener! k (fn [ev] (swap! a conj ev)))
    a))

(defn- destroys-on
  "The captured destroy events for `operation`."
  [traces operation]
  (filter #(= operation (:operation %)) @traces))

(def ^:private live-child
  "A child whose states are never `:final?` — so a teardown is always a
  CANCELLATION of in-progress work, never a natural finish."
  {:initial :running
   :data    {}
   :states  {:running {}}})

;; ===========================================================================
;; 1. fx channel — a real `:explicit` destruction
;; ===========================================================================

(deftest xray-observes-real-fx-channel-explicit-destroy
  (testing "an imperative [:rf.machine/destroy <id>] on a live actor emits
            (:rf.machine/destroyed, :explicit) — and Xray anchors on it"
    (let [traces (record-traces! ::fx-explicit)]
      (rf/reg-machine :rf2-3uixf4.fx/live live-child)
      ;; Start the singleton, then tear it down before it can finish.
      (rf/dispatch-sync [:rf2-3uixf4.fx/live [:rf.machine/noop]])
      (rf/reg-event ::destroy-it
        (fn [_ _] {:fx [[:rf.machine/destroy :rf2-3uixf4.fx/live]]}))
      (rf/dispatch-sync [::destroy-it])

      (let [fx-destroys (destroys-on traces :rf.machine/destroyed)
            ev          (first fx-destroys)]
        (is (= 1 (count fx-destroys))
            "exactly one fx-substrate destroy fired")
        (is (= :explicit (get-in ev [:tags :reason]))
            "the runtime stamped the cancellation reason")

        (is (true? (cascade/cancellation-anchor? ev))
            "Xray admits the real (fx, :explicit) tuple and anchors a
             cancellation cascade on it")

        (testing "the managed-fx surface collects the real fx terminal"
          (is (contains? managed-fx/machine-invoke-trace-operations
                         (:operation ev))
              "normal machine destruction must not be dropped on the floor"))

        (testing "no lifecycle-channel event rode along"
          (is (empty? (destroys-on traces :rf.machine.lifecycle/destroyed))
              "an fx teardown is fx-substrate ONLY — the channels are
               disjoint, not symmetric"))))))

;; ===========================================================================
;; 2. lifecycle channel — a real `:parent-frame-destroyed` destruction
;; ===========================================================================

(deftest xray-observes-real-lifecycle-channel-frame-destroy
  (testing "destroying a frame that owns a live actor emits
            (:rf.machine.lifecycle/destroyed, :parent-frame-destroyed)
            — and Xray anchors on it"
    (let [traces (record-traces! ::lifecycle-frame-destroy)]
      (rf/make-frame {:id  :rf2-3uixf4/scratch
                      :doc "destroy-channel scratch frame"})
      (rf/reg-machine :rf2-3uixf4.lifecycle/live live-child)
      ;; Start the singleton INSIDE the scratch frame so the frame owns it.
      (rf/dispatch-sync [:rf2-3uixf4.lifecycle/live [:rf.machine/noop]]
                        {:frame :rf2-3uixf4/scratch})
      (rf/destroy-frame! :rf2-3uixf4/scratch)

      (let [ev (first (destroys-on traces :rf.machine.lifecycle/destroyed))]
        (is (true? (cascade/cancellation-anchor? ev))
            "frame-exit reaped the live actor on the registrar channel, and
             Xray anchors a cancellation cascade on that (lifecycle,
             :parent-frame-destroyed) tuple")

        (testing "the managed-fx surface collects the real frame-exit reap"
          (is (contains? managed-fx/machine-invoke-trace-operations
                         (:operation ev))))))))

;; ===========================================================================
;; 3. fx channel — a real natural `:final?` finish
;; ===========================================================================

(deftest natural-finish-destroy-is-emittable-but-not-a-cancellation
  (testing "a natural `:final?` termination auto-destroys with a tuple the
            matrix admits, and never anchors a cancellation cascade"
    (let [traces (record-traces! ::natural-finish)]
      (rf/reg-machine :rf2-3uixf4.sweep/finisher
        {:initial :running
         :data    {}
         :states  {:running {:on {:fin :done}}
                   :done    {:final? true}}})
      (rf/dispatch-sync [:rf2-3uixf4.sweep/finisher [:fin]])
      (let [finish (first (filter #(= :rf.machine/finished (get-in % [:tags :reason]))
                                  (filter cascade/destroy-event? @traces)))]
        (is (true? (cascade/emittable-destroy? finish)))
        (is (false? (cascade/cancellation-anchor? finish)))))))
