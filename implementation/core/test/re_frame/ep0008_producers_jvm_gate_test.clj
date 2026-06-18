(ns re-frame.ep0008-producers-jvm-gate-test
  "EP-0008 / rf2-ntv9i9.3 — the JVM production-gate + raw-payload regressions
  for the REAL promoted producers.

  ## Why this suite exists (rf2-ntv9i9.3 finding #1)

  EP-0008 says the always-on axis survives BOTH CLJS production elision AND
  JVM `re-frame.debug` / `RE_FRAME_DEBUG` gating. The generic JVM gate
  (`re-frame.jvm-prod-gate-integration-test`) proves a generic handler
  exception reaches `register-error-listener!` with debug disabled — but the
  REAL EP-0008 producers (`:rf.error/frame-teardown-failed`,
  `:rf.error/write-after-destroy`, `:rf.error/on-destroy-handler-exception`)
  were pinned mainly through the CLJS production-elision probe. This suite
  exercises each REAL producer end-to-end under `debug-enabled? = false` — the
  SSR-production JVM posture — and asserts:

    (a) each promoted producer's always-on record STILL fires with the dev
        gate off (the producer survives the JVM prod gate);
    (b) the dev-only companion trace (the per-hook
        `:rf.warning/teardown-hook-exception`, the dev error trace) is ELIDED
        under the same gate (the diagnostic channel is gated; the always-on
        axis is not) — the negative assertion rf2-ntv9i9.3 #2 names.

  ## Why JVM-only (`.clj`, not `.cljc`)

  The gate this suite exercises is the JVM `interop/debug-enabled?` var
  (`with-redefs`-rebindable here; CLJS uses Closure DCE, exercised by the
  `prod_elision_runner` probe instead). Naming this `.clj` keeps it on the
  JVM `clojure -M:test` runner only.

  ## Raw-payload regressions (rf2-ntv9i9.3 #2)

  The corpus-wide listener carries the RAW exception (the off-box-shipper API
  — Sentry needs the stack). That is the documented advanced-integration
  posture (Spec 009 §What IS available in production — the `:exception` slot
  exception to 'no raw values'). The frame-owned `:observability :errors` sink
  route PROJECTS the record (rf2-ntv9i9.1). This suite pins both halves with a
  sensitive event payload + exception ex-data."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.event-emit :as event-emit]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.observability :as observability]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (event-emit/clear-event-listeners!)
                (error-emit/clear-error-listeners!)
                (observability/clear-observability-sinks!))}))

(defn- records-of [seen error-kw]
  (filter #(= error-kw (:error %)) @seen))

;; ===========================================================================
;; (a) Each promoted producer's always-on record survives debug-enabled? = false
;; ===========================================================================

(deftest frame-teardown-failed-survives-jvm-prod-gate
  (testing "rf2-ntv9i9.3 — with the JVM debug gate OFF (the SSR production
            posture), a frame destroy whose cleanup hooks throw STILL fans the
            always-on `:rf.error/frame-teardown-failed` report out through
            `register-error-listener!` (the axis is not debug-gated)."
    (with-redefs [interop/debug-enabled? false]
      (let [seen (atom [])]
        (rf/register-listener! :errors :test/recorder
                                     (fn [r] (swap! seen conj r)))
        (rf/reg-frame :gate/teardown {})
        ;; Install a throwing cleanup hook for the duration of the destroy.
        (let [orig (late-bind/get-fn :ssr/on-frame-destroyed)]
          (try
            (late-bind/set-fn! :ssr/on-frame-destroyed
                                        (fn [& _] (throw (ex-info "hook threw" {}))))
            (rf/destroy-frame! :gate/teardown)
            (finally
              (late-bind/set-fn! :ssr/on-frame-destroyed orig))))
        (let [reports (records-of seen :rf.error/frame-teardown-failed)]
          (is (= 1 (count reports))
              "the teardown report survived the debug-off JVM gate")
          (is (= :gate/teardown (:frame (first reports))))
          (is (= 1 (count (:hook-failures (first reports))))))))))

(deftest write-after-destroy-survives-jvm-prod-gate
  (testing "rf2-ntv9i9.3 — with the JVM debug gate OFF, a nil-container
            `replace-container!` (the scheduled-drain-vs-destroy race) STILL
            fans the always-on `:rf.error/write-after-destroy` record out."
    (with-redefs [interop/debug-enabled? false]
      (let [seen (atom [])]
        (rf/register-listener! :errors :test/recorder
                                     (fn [r] (swap! seen conj r)))
        (adapter/replace-container! nil {:dropped :write})
        (let [reports (records-of seen :rf.error/write-after-destroy)]
          (is (= 1 (count reports))
              "the suppressed-write record survived the debug-off JVM gate")
          (let [r (first reports)]
            ;; The always-on suppressed-write record is structured-only (no
            ;; event, no frame — the frame is gone; no exception — a dropped
            ;; write, not a throw), mirroring the cljc producer test's shape.
            (is (nil? (:event r)) "no event vector on the dropped-write record")
            (is (nil? (:frame r)) "no frame — it was destroyed")
            (is (nil? (:exception r)) "no exception — a suppressed write")))))))

(deftest on-destroy-handler-exception-survives-jvm-prod-gate
  (testing "rf2-ntv9i9.3 — with the JVM debug gate OFF, a throwing
            `:on-destroy` STILL fans the dedicated always-on
            `:rf.error/on-destroy-handler-exception` discriminator out (the
            production source of the teardown discriminator that the dev trace
            elides here)."
    (with-redefs [interop/debug-enabled? false]
      (let [seen (atom [])]
        (rf/register-listener! :errors :test/recorder
                                     (fn [r] (swap! seen conj r)))
        (rf/reg-event :gate/blow-up (fn [{:keys [db]} _] {:db (throw (ex-info "boom" {}))}))
        (rf/reg-frame :gate/ondestroy {:on-destroy [:gate/blow-up]})
        (rf/destroy-frame! :gate/ondestroy)
        (let [reports (records-of seen :rf.error/on-destroy-handler-exception)]
          (is (= 1 (count reports))
              "the dedicated discriminator survived the debug-off JVM gate")
          (is (= :gate/ondestroy (:frame (first reports)))))))))

;; ===========================================================================
;; (b) The dev-only companion trace is ELIDED under the same gate (the
;; diagnostic channel is debug-gated; the always-on axis is not). The negative
;; assertion rf2-ntv9i9.3 #2 names.
;; ===========================================================================

(deftest dev-per-hook-teardown-warning-elided-while-report-survives
  (testing "rf2-ntv9i9.3 — under the debug-off JVM gate the dev per-hook
            `:rf.warning/teardown-hook-exception` trace is ELIDED, while the
            always-on `:rf.error/frame-teardown-failed` report SURVIVES — the
            two channels diverge exactly at the gate."
    (with-redefs [interop/debug-enabled? false]
      (let [traces  (atom [])
            reports (atom [])]
        (rf/register-listener! :trace ::trace-rec (fn [ev] (swap! traces conj ev)))
        (rf/register-listener! :errors :test/err-rec
                                     (fn [r] (swap! reports conj r)))
        (rf/reg-frame :gate/divergence {})
        (let [orig (late-bind/get-fn :ssr/on-frame-destroyed)]
          (try
            (late-bind/set-fn! :ssr/on-frame-destroyed
                                        (fn [& _] (throw (ex-info "hook threw" {}))))
            (rf/destroy-frame! :gate/divergence)
            (finally
              (late-bind/set-fn! :ssr/on-frame-destroyed orig)
              (rf/unregister-listener! :trace ::trace-rec))))
        ;; Diagnostic channel: ELIDED under the debug-off gate.
        (is (empty? (filter #(= :rf.warning/teardown-hook-exception (:operation %))
                            @traces))
            "the per-hook dev warning is elided under debug-off (diagnostic channel gated)")
        ;; Always-on channel: SURVIVES.
        (is (= 1 (count (records-of reports :rf.error/frame-teardown-failed)))
            "the always-on report still fired under debug-off (axis not gated)")))))

;; ===========================================================================
;; Raw-payload regressions (rf2-ntv9i9.3 #2) — corpus listener carries the raw
;; payload; the frame-owned sink route projects it (under the debug-off gate,
;; so both routes are exercised in the production posture).
;; ===========================================================================

(def ^:private secret "S3CR3T-rf2-ntv9i9-3-DO-NOT-LEAK")

(deftest corpus-listener-carries-raw-frame-sink-projects-under-prod-gate
  (testing "rf2-ntv9i9.3 #2 — under the debug-off JVM gate, a teardown report
            whose `:hook-failures` entry carries sensitive exception ex-data
            reaches the corpus-wide listener RAW (the off-box-shipper API) AND
            the frame-owned `:errors` sink PROJECTED (sensitive path redacted)."
    (with-redefs [interop/debug-enabled? false]
      (let [listener-seen (atom [])
            sink-seen     (atom [])]
        (rf/register-observability-sink! :test.sinks/sentry
                                    (fn [r] (swap! sink-seen conj r)))
        (rf/register-listener! :errors :test/listener
                                     (fn [r] (swap! listener-seen conj r)))
        ;; index-free decl matches the runtime [:hook-failures 0 :exception-data
        ;; :token] (the vector index is ridden index-free by the wire-walker).
        (rf/reg-frame :gate/raw
          {:observability
           {:errors [{:sink :test.sinks/sentry
                      :rf.egress/profile :rf.egress/off-box-observability}]}
           :sensitive {:app-db [[:hook-failures :exception-data :token]]}})
        (error-emit/dispatch-frame-teardown-report!
          :gate/raw
          [{:hook           :flows/teardown-on-frame-destroy!
            :exception      (ex-info "boom" {})
            :exception-data {:token secret}
            :where          :safe-call-hook!}]
          7)
        ;; corpus-wide listener: RAW (Sentry needs the stack / payload).
        (is (= 1 (count @listener-seen)) "the corpus listener fired under prod gate")
        (is (.contains ^String (pr-str (first @listener-seen)) secret)
            "the corpus-wide listener carries the raw payload (documented posture)")
        ;; frame-owned sink: PROJECTED (sensitive path redacted).
        (is (= 1 (count @sink-seen)) "the frame sink fired under prod gate")
        (let [projected (first @sink-seen)]
          (is (= :rf/redacted
                 (get-in projected [:tags :hook-failures 0 :exception-data :token]))
              "the sensitive token is redacted in the frame-sink projection")
          (is (not (.contains ^String (pr-str projected) secret))
              "the secret never appears anywhere in the projected sink record"))))))

(deftest sensitive-event-payload-redacted-on-frame-error-sink-prod-gate
  (testing "rf2-ntv9i9.3 #2 — under the debug-off JVM gate, an EVENT-centric
            error whose event carries a sensitive payload reaches the frame's
            `:errors` sink with the sensitive slot REDACTED (the event-centric
            companion to the non-event raw-payload pin — both route through
            project-egress)."
    (with-redefs [interop/debug-enabled? false]
      (let [sink-seen (atom [])]
        (rf/register-observability-sink! :test.sinks/sentry
                                    (fn [r] (swap! sink-seen conj r)))
        (rf/reg-frame :gate/evt
          {:observability
           {:errors [{:sink :test.sinks/sentry
                      :rf.egress/profile :rf.egress/off-box-observability}]}
           :sensitive {:app-db [[:auth :token]]}})
        (rf/reg-event :gate/login {:frame :gate/evt}
                         (fn [{:keys [db]} _] {:db (throw (ex-info "kaboom" {}))}))
        (rf/dispatch-sync [:gate/login {:auth {:token secret}}] {:frame :gate/evt})
        (is (= 1 (count @sink-seen)) "the frame error sink fired under prod gate")
        (let [r (first @sink-seen)]
          (is (= :rf/redacted (get-in (:event r) [1 :auth :token]))
              "the sensitive token inside the event is redacted on the sink")
          (is (not (.contains ^String (pr-str r) secret))
              "the secret never appears in the projected error record"))))))
