(ns re-frame.jvm-prod-gate-integration-test
  "rf2-f7qj4 — READ THIS FIRST. Despite the namespace's name, this suite is
  NOT THE LOAD-TIME GATE.

  `re-frame.interop/debug-enabled?` is a `def` read ONCE, at namespace-load
  time, from `-Dre-frame.debug` / `RE_FRAME_DEBUG`. Every assertion below
  reaches it with `with-redefs`, which runs AFTER the framework has loaded and
  therefore cannot change one thing the gate decided at load. What this suite
  actually pins is the REBINDABLE VAR: that the gated dev surfaces re-read
  `interop/debug-enabled?` at CALL time rather than caching it, so a rebind
  silences them. That is a real and useful contract. It is not the production
  posture, and it must never be counted as coverage of one.

  The lanes that DO reach the load-time gate:

    * `jvm-core-prod-gate` / `sh scripts/test-core-prod-gate.sh` — the core
      suite run with `-Dre-frame.debug=false` genuinely on the JVM command
      line (via the `:prod-gate` alias's `:jvm-opts`).
    * `re-frame.prod-gate-lane-pin-test` — asserts, unconditionally, that the
      property reached that lane's JVM and that the framework honoured it.
    * `re-frame.prod-gate-dispatch-jvm-test` — the child-JVM pattern: relaunch
      a fresh JVM with the property on the command line, for a defect that
      only reproduces at load time.

  Why the distinction is load-bearing: rf2-9c2jf was a TOTAL `dispatch-sync`
  failure under the documented production gate — handler run ZERO times — and
  it stayed green for as long as it existed. Part of why nobody caught it is
  that the roster of suites calling themselves \"production gate\" tests looked
  full, and a reviewer reading the file list had no way to see that not one of
  them ran under the gate.

  ## What this suite pins

  Per rf2-vnjfg (MEDIUM finding): with `interop/debug-enabled?` REBOUND to
  `false`, the dev surfaces (trace ring buffer, trace listener fan-out,
  registry trace emits) drop to their no-op floor — the call-time-read
  equivalent of what CLJS `:advanced` + `goog.DEBUG=false` gets from Closure
  DCE.

  The companion epoch suite (`re-frame.epoch.jvm-prod-gate-test`,
  rf2-0la4f) rebinds the same Var for the epoch artefact and carries the same
  caveat.

  The unit-level vocabulary semantics live in
  `re-frame.interop-debug-gate-test`; this suite is the end-to-end
  integration story."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]))

(def ^:private build-envelope
  "Pull the private envelope builder — the dispatch envelope is not
  exposed to user handlers, so the EP-0017 `:rf.cofx` recordable-coeffect
  stamping (rf2-s9ss0t) is asserted directly against `build-envelope`'s output."
  #'router/build-envelope)

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

(deftest trace-buffer-inert-when-debug-disabled
  (testing "Per rf2-vnjfg: when the JVM debug gate is off (the SSR
            production posture), trace events stop landing in the
            retain-N ring buffer. The buffer surface becomes
            inert — no allocation, no append, no storage."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-event :prod-gate/inc
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod-gate/inc])
      (is (empty? (trace/trace-buffer :rf/default))
          "trace buffer is empty under disabled gate — no event
           landed despite dispatch firing"))))

(deftest trace-listener-silent-when-debug-disabled
  (testing "Per rf2-vnjfg: a registered trace listener does NOT fire
            when the JVM debug gate is off. The dev observability
            surface drops to no-op so the SSR process does not
            retain in-heap traces of user input."
    (with-redefs [interop/debug-enabled? false]
      (let [seen (atom [])]
        (rf/register-listener! :trace
          :prod-gate/recorder
          (fn [event] (swap! seen conj event)))
        (rf/reg-event :prod-gate/silent
                         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
        (rf/dispatch-sync [:prod-gate/silent])
        (rf/unregister-listener! :trace :prod-gate/recorder)
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
        (rf/register-listener! :events
          :prod-gate/event-rec
          (fn [record] (swap! seen conj record)))
        (rf/reg-event :prod-gate/observable
                         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
        (rf/dispatch-sync [:prod-gate/observable])
        (is (= 1 (count @seen))
            "event-emit substrate fired under disabled debug gate
             — always-on means always-on")))))

(deftest dispatched-at-retired-cofx-stamped-regardless-of-gate
  (testing "Per EP-0010 rider b (rf2-s9ss0t): `:dispatched-at` is RETIRED
            in the same change that lands the envelope stamp — no
            coexistence window. Its diagnostic dispatch-time need is the
            trace event `:time` stamp (Spec 009), not a second envelope
            field. The replacement causal-time fact is
            `(:rf/time-ms (:rf.cofx envelope))`, which — unlike the
            dev-gated `:dispatch-id` — is stamped UNCONDITIONALLY because
            recordable coeffects are DURABLE causal data that durable writes
            fold, not a diagnostic."
    (rf/make-frame {:id :rf/default})
    (testing ":dispatched-at is gone under BOTH gate states"
      (with-redefs [interop/debug-enabled? true]
        (is (not (contains? (build-envelope [:noop] {}) :dispatched-at))
            "no :dispatched-at even with the dev gate ON"))
      (with-redefs [interop/debug-enabled? false]
        (is (not (contains? (build-envelope [:noop] {}) :dispatched-at))
            "no :dispatched-at with the dev gate OFF")))
    (testing ":rf.cofx with :rf/time-ms is stamped REGARDLESS of the gate"
      (with-redefs [interop/debug-enabled? true]
        (let [cofx (:rf.cofx (build-envelope [:noop] {}))]
          (is (number? (:rf/time-ms cofx))
              ":rf/time-ms present + numeric under the dev gate ON")))
      (with-redefs [interop/debug-enabled? false]
        (let [cofx (:rf.cofx (build-envelope [:noop] {}))]
          (is (number? (:rf/time-ms cofx))
              ":rf/time-ms present + numeric under the prod gate OFF — durable, not dev-gated"))))))

(deftest always-on-error-emit-still-fires-when-debug-disabled
  (testing "Per Spec 009 §Error-emit + rf2-bacs4: the always-on
            error-emit substrate fires REGARDLESS of the debug gate.
            The corpus-wide listener path survives the SSR production
            posture — error observability is not a dev-only concern."
    (with-redefs [interop/debug-enabled? false]
      (let [listener-saw (atom nil)]
        (rf/register-listener! :errors
          :prod-gate/err-rec
          (fn [record] (reset! listener-saw record)))
        (rf/reg-event :prod-gate/throws
                         (fn [{:keys [db]} _] {:db (throw (ex-info "boom" {}))}))
        (rf/dispatch-sync [:prod-gate/throws])
        (is (some? @listener-saw)
            "error-emit listener fired under disabled debug gate")))))
