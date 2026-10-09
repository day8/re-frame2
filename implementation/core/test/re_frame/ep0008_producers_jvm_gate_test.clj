(ns re-frame.ep0008-producers-jvm-gate-test
  "NOT THE LOAD-TIME GATE. `re-frame.interop/debug-enabled?` is read once, at
  namespace load, from `-Dre-frame.debug` / `RE_FRAME_DEBUG`. These tests rebind
  the Var with `with-redefs` after the framework has loaded, so they pin only
  that the producers below consult it at CALL time — the dev-only companion
  trace does, the always-on record does not. The documented production posture
  is exercised by `scripts/test-core-prod-gate.sh`, which runs the core suite
  with `-Dre-frame.debug=false` on the JVM command line; never count this
  namespace as coverage of it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.observability :as rf.observability]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.event-emit/clear-event-listeners!)
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))}))

(deftest dev-per-hook-teardown-warning-elided-while-report-survives
  (testing "with the debug Var off, the dev per-hook
            `:rf.warning/teardown-hook-exception` trace is elided while the
            always-on `:rf.error/frame-teardown-failed` report still fires"
    (with-redefs [rf.interop/debug-enabled? false]
      (let [traces  (atom [])
            reports (atom [])]
        (rf/register-listener! :trace ::trace-rec (fn [ev] (swap! traces conj ev)))
        (rf.error-emit/register-error-listener! :test/err-rec
                                                (fn [r] (swap! reports conj r)))
        (rf/make-frame {:id :gate/divergence})
        (let [orig (rf.late-bind/get-fn :ssr/on-frame-destroyed)]
          (try
            (rf.late-bind/set-fn! :ssr/on-frame-destroyed
                                  (fn [& _] (throw (ex-info "hook threw" {}))))
            (rf/destroy-frame! :gate/divergence)
            (finally
              (rf.late-bind/set-fn! :ssr/on-frame-destroyed orig)
              (rf/unregister-listener! :trace ::trace-rec))))
        (is (empty? (filter #(= :rf.warning/teardown-hook-exception (:operation %))
                            @traces))
            "the dev per-hook warning is elided")
        (is (= 1 (count (filter #(= :rf.error/frame-teardown-failed (:error %))
                                @reports)))
            "the always-on report still fired")))))

(def ^:private secret "S3CR3T-rf2-ntv9i9-3-DO-NOT-LEAK")

(deftest sensitive-event-payload-redacted-on-frame-error-sink-prod-gate
  (testing "with the debug Var off, an event error whose event carries a
            sensitive payload reaches the frame's `:errors` sink with the
            secret redacted, and nowhere else in the projected record"
    (with-redefs [rf.interop/debug-enabled? false]
      (let [sink-seen (atom [])]
        (rf/register-observability-sink! :test.sinks/sentry
                                         (fn [r] (swap! sink-seen conj r)))
        (rf/make-frame {:id :gate/evt :observability
                        {:errors [{:sink :test.sinks/sentry
                                   :rf.egress/profile :rf.egress/off-box-observability}]}})
        (rf.frame/swap-runtime-db! :gate/evt
          (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:auth :token]]})))
        (rf/reg-event :gate/login {:frame :gate/evt}
                      (fn [_ _] {:db (throw (ex-info "kaboom" {}))}))
        (rf/dispatch-sync [:gate/login {:auth {:token secret}}] {:frame :gate/evt})
        (is (= 1 (count @sink-seen)) "the frame error sink fired")
        (let [r (first @sink-seen)]
          (is (= :rf/redacted (get-in (:event r) [1 :auth :token])))
          (is (not (.contains ^String (pr-str r) secret))
              "the secret never appears in the projected error record"))))))
