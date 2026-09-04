(ns day8.re-frame2-xray.panels.machine-destroy-channels-cljs-test
  "rf2-3uixf4 — actual-consumer counterfixture for the DISJOINT
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
  reason to the other channel — or Xray reverts to validating channel and
  reason independently — these tests go red.

  The complementary negative cross-products (the tuples the matrix forbids)
  are asserted in `cancellation-cascade-helpers-cljs-test`, which can author
  the impossible events the runtime cannot produce.

  The file is named `*-cljs-test.cljc` so both cognitect.test-runner (JVM)
  and shadow-cljs (`cljs-test$` ns-regexp) discover it."
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

        (testing "Xray's production projections accept the REAL event"
          (is (true? (cascade/destroy-event? ev)))
          (is (true? (cascade/emittable-destroy? ev))
              "the real (fx, :explicit) tuple is one the matrix admits")
          (is (true? (cascade/cancellation-anchor? ev))
              "a pre-final teardown is a cancellation cascade anchor"))

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

      (let [lifecycle-destroys (destroys-on traces
                                            :rf.machine.lifecycle/destroyed)
            ev                 (first lifecycle-destroys)]
        (is (seq lifecycle-destroys)
            "frame-exit reaped the live actor on the registrar channel")
        (is (= :parent-frame-destroyed (get-in ev [:tags :reason]))
            "the frame-exit cause is the channel's sole reason")

        (testing "Xray's production projections accept the REAL event"
          (is (true? (cascade/destroy-event? ev)))
          (is (true? (cascade/emittable-destroy? ev))
              "the real (lifecycle, :parent-frame-destroyed) tuple is admitted")
          (is (true? (cascade/cancellation-anchor? ev))
              "the actor was reaped before finishing — a cancellation"))

        (testing "the managed-fx surface collects the real frame-exit reap"
          (is (contains? managed-fx/machine-invoke-trace-operations
                         (:operation ev))))))))

;; ===========================================================================
;; 3. every REAL destroy the runtime emits satisfies the matrix
;; ===========================================================================

(deftest every-real-destroy-satisfies-the-matrix
  (testing "across a natural finish AND a cancellation, no emitted destroy
            violates the (channel, reason) matrix Xray models"
    (let [traces (record-traces! ::matrix-sweep)]
      ;; (a) a natural `:final?` termination → auto-destroy.
      (rf/reg-machine :rf2-3uixf4.sweep/finisher
        {:initial :running
         :data    {}
         :states  {:running {:on {:fin :done}}
                   :done    {:final? true}}})
      (rf/dispatch-sync [:rf2-3uixf4.sweep/finisher [:fin]])
      ;; (b) a cancellation of a live actor.
      (rf/reg-machine :rf2-3uixf4.sweep/live live-child)
      (rf/dispatch-sync [:rf2-3uixf4.sweep/live [:rf.machine/noop]])
      (rf/reg-event ::sweep-destroy
        (fn [_ _] {:fx [[:rf.machine/destroy :rf2-3uixf4.sweep/live]]}))
      (rf/dispatch-sync [::sweep-destroy])

      (let [all-destroys (filter cascade/destroy-event? @traces)]
        (is (<= 2 (count all-destroys))
            "the sweep produced both a finish and a cancellation destroy")
        (doseq [ev all-destroys]
          (is (true? (cascade/emittable-destroy? ev))
              (str "real emitted destroy violates the matrix Xray models: "
                   (:operation ev) " + " (get-in ev [:tags :reason]))))

        (testing "the natural finish is emittable but NOT a cancellation"
          (let [finish (first (filter #(= :rf.machine/finished
                                          (get-in % [:tags :reason]))
                                      all-destroys))]
            (is (some? finish) "the finisher auto-destroyed")
            (is (true? (cascade/emittable-destroy? finish)))
            (is (false? (cascade/cancellation-anchor? finish))
                "a :final? termination must never anchor a cancellation
                 cascade")))))))
