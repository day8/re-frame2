(ns re-frame.jvm-prod-gate-integration-test
  "Per rf2-vnjfg (MEDIUM finding): JVM/SSR/headless production gate
  end-to-end. Pins that when `interop/debug-enabled?` reads `false`
  on the JVM, the dev surfaces (trace ring buffer, trace listener
  fan-out, registry trace emits) drop to their no-op floor — the
  same DCE-equivalent surface CLJS `:advanced` + `goog.DEBUG=false`
  builds get for free.

  The companion epoch suite (`re-frame.epoch.jvm-prod-gate-test`,
  rf2-0la4f) pins the same contract for the epoch artefact.

  The unit-level vocabulary semantics live in
  `re-frame.interop-debug-gate-test`; this suite is the end-to-end
  integration story."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

(deftest trace-buffer-inert-when-debug-disabled
  (testing "Per rf2-vnjfg: when the JVM debug gate is off (the SSR
            production posture), trace events stop landing in the
            retain-N ring buffer. The buffer surface becomes
            inert — no allocation, no append, no storage."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-event-db :prod-gate/inc
                       (fn [db _] (update db :n (fnil inc 0))))
      (rf/dispatch-sync [:prod-gate/inc])
      (is (empty? (trace/trace-buffer))
          "trace buffer is empty under disabled gate — no event
           landed despite dispatch firing"))))

(deftest trace-listener-silent-when-debug-disabled
  (testing "Per rf2-vnjfg: a registered trace listener does NOT fire
            when the JVM debug gate is off. The dev observability
            surface drops to no-op so the SSR process does not
            retain in-heap traces of user input."
    (with-redefs [interop/debug-enabled? false]
      (let [seen (atom [])]
        (rf/register-listener!
          :prod-gate/recorder
          (fn [event] (swap! seen conj event)))
        (rf/reg-event-db :prod-gate/silent
                         (fn [db _] (update db :n (fnil inc 0))))
        (rf/dispatch-sync [:prod-gate/silent])
        (rf/unregister-listener! :prod-gate/recorder)
        (is (empty? @seen)
            "trace listener saw zero events under disabled gate")))))

(deftest always-on-event-emit-still-fires-when-debug-disabled
  (testing "Per Spec 009 §Event-emit + rf2-rirbq: the always-on
            event-emit substrate fires REGARDLESS of the debug
            gate. Production observability (Datadog, Honeycomb,
            ...) must survive the SSR production posture — that's
            why it's the always-on surface, parallel to (not a
            fallback for) the dev trace surface."
    (with-redefs [interop/debug-enabled? false]
      (let [seen (atom [])]
        (rf/register-event-listener!
          :prod-gate/event-rec
          (fn [record] (swap! seen conj record)))
        (rf/reg-event-db :prod-gate/observable
                         (fn [db _] (update db :n (fnil inc 0))))
        (rf/dispatch-sync [:prod-gate/observable])
        (is (= 1 (count @seen))
            "event-emit substrate fired under disabled debug gate
             — always-on means always-on")))))

(deftest always-on-error-emit-still-fires-when-debug-disabled
  (testing "Per Spec 009 §Error-emit + rf2-bacs4 / rf2-hqbeh: the
            always-on error-emit substrate fires REGARDLESS of the
            debug gate. Both the corpus-wide listener path AND the
            per-frame `:on-error` policy fn survive the SSR
            production posture — error recovery is not a dev-only
            concern."
    (with-redefs [interop/debug-enabled? false]
      (let [listener-saw (atom nil)
            policy-saw   (atom nil)]
        (rf/reg-frame :rf/default
                      {:on-error (fn [ev] (reset! policy-saw ev) nil)})
        (rf/register-error-listener!
          :prod-gate/err-rec
          (fn [record] (reset! listener-saw record)))
        (rf/reg-event-db :prod-gate/throws
                         (fn [_db _] (throw (ex-info "boom" {}))))
        (rf/dispatch-sync [:prod-gate/throws])
        (is (some? @listener-saw)
            "error-emit listener fired under disabled debug gate")
        (is (some? @policy-saw)
            ":on-error policy fn fired under disabled debug gate")))))
