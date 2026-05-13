(ns re-frame.event-emit-elision-prod-test
  "Per rf2-rirbq — the always-on event-emit substrate is a production
  observability surface and MUST fire even when the CLJS trace surface
  is compile-time elided in production builds (`:advanced` +
  `goog.DEBUG=false`).

  This file is the prod-mode companion to `re-frame.event-emit-test`
  (default JVM / Node runners). It mirrors the rf2-hqbeh shape used by
  `re-frame.on-error-elision-prod-test`: the shared runner is
  `re-frame.prod-elision-runner`; the shadow-cljs build is
  `:browser-test-prod-elision` (`:advanced` + `{goog.DEBUG false}`).

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. The
  default `:browser-test` / `:node-test` runners use regexes that do
  NOT match this suffix, so these tests run only under prod-mode
  compilation. This is the suite that exercises the bug class
  rf2-rirbq exists to prevent: a Datadog forwarder registered through
  the per-frame `:trace-cb` surface goes silent under `goog.DEBUG=
  false`; the same forwarder registered through this substrate
  survives."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- event-emit fires under prod build -----------------------------------

(deftest event-emit-fires-under-prod
  (testing "Per rf2-rirbq: under `:advanced` + `goog.DEBUG=false`, a
            registered event-emit listener MUST fire for every
            processed event — the trace surface is gone, but the
            always-on event-emit substrate delivers the tight record
            so production-monitoring integrations (Datadog, Honeycomb,
            ...) still observe every dispatched event."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        :prod/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :prod/inc
                       (fn [db _] (update db :n (fnil inc 0))))
      (rf/dispatch-sync [:prod/inc])
      (is (= 1 (count @seen))
          "listener fired exactly once for the dispatched event — prod-elision contract holds")
      (let [r (first @seen)]
        (is (= [:prod/inc]   (:event r)))
        (is (= :prod/inc     (:event-id r)))
        (is (= :rf/default   (:frame r)))
        (is (= :ok           (:outcome r)))
        (is (number? (:time r)))
        (is (integer? (:elapsed-ms r)))))))

(deftest event-emit-records-error-outcome-under-prod
  (testing "When a handler throws under `:advanced` + `goog.DEBUG=false`,
            the listener record's `:outcome` is `:error`. The trace
            surface's `:rf.error/handler-exception` event has been
            elided, but the tight event-emit record retains the
            success/failure discriminator so monitoring pipelines can
            distinguish the two without needing the trace surface."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        :prod/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :prod/throw
                       (fn [_db _]
                         (throw (ex-info "kaboom" {}))))
      (rf/dispatch-sync [:prod/throw])
      (is (= 1 (count @seen)))
      (is (= :error (:outcome (first @seen)))
          ":outcome :error is preserved when the handler throws under prod"))))

(deftest event-emit-listener-exception-is-swallowed-under-prod
  (testing "A buggy listener cannot break the cascade under prod. The
            substrate catches listener throws silently — the dispatch
            settles and sibling listeners still run. Mirrors the dev-
            mode contract from `re-frame.event-emit-test`; pinned here
            so the prod-build behaviour is locked too."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        :prod/throws
        (fn [_record]
          (throw (ex-info "listener went boom" {}))))
      (rf/register-event-emit-listener!
        :prod/sibling
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :prod/quiet (fn [db _] db))
      (is (nil? (rf/dispatch-sync [:prod/quiet]))
          "dispatch-sync returned nil despite the listener throw")
      (is (= 1 (count @seen))
          "the sibling listener still received the record under prod"))))

;; ---- handler-meta :sensitive? short-circuit under prod (rf2-6hklf) -------

(deftest event-emit-sensitive-handler-meta-drops-record-under-prod
  (testing "Per rf2-6hklf: handler-meta `:sensitive? true` MUST keep
            records out of the always-on substrate under `:advanced` +
            `goog.DEBUG=false`. This is the production-build contract
            the bug report exists to lock — a Datadog forwarder
            attached at boot still sees normal events but never sees
            a sensitive-handler's records."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        :prod/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :prod/sensitive
                       {:sensitive? true}
                       (fn [db _] (assoc db :touched true)))
      (rf/dispatch-sync [:prod/sensitive "secret-payload"])
      (is (= 0 (count @seen))
          "no listener invocation for a :sensitive? handler under prod — record dropped at the boundary"))))

(deftest event-emit-non-sensitive-handler-still-fires-under-prod
  (testing "Companion lock to the :sensitive? prod-drop test: a handler
            registered WITHOUT `:sensitive?` continues to deliver
            records to listeners under prod as before. Guards against
            the rf2-6hklf fix accidentally regressing the default path."
    (let [seen (atom [])]
      (rf/register-event-emit-listener!
        :prod/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :prod/normal
                       (fn [db _] (assoc db :touched true)))
      (rf/dispatch-sync [:prod/normal "payload"])
      (is (= 1 (count @seen))
          "non-sensitive handler still fans out under prod")
      (is (= [:prod/normal "payload"] (:event (first @seen)))
          "elided event payload reaches the listener under prod"))))
