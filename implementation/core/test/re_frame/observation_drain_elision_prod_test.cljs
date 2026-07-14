(ns re-frame.observation-drain-elision-prod-test
  "rf2-xakb4p — the advanced-production proof for the observation port's
  disposal-notification callback-failure contract (Spec 006 §Disposal-
  notification callback failures; Spec 009 `:rf.error/observation-on-change-
  failed`).

  The dev-mode `re-frame.observation-port-cljs-test` legs assert the wrapper
  fans on BOTH channels (always-on record + dev diagnostic-trace event) — but
  they run with the trace surface LIVE, so they cannot prove the central
  claim: under `:advanced` + `goog.DEBUG=false` the dev-trace leg is DCE'd
  while the always-on record SURVIVES. This file pins exactly that — EXACTLY
  ONE always-on `:rf.error/observation-on-change-failed` record and ZERO
  diagnostic trace events for a real HMR/disposal callback failure — plus the
  contained sibling drain and the direct-caller first-escape rethrow are
  unchanged in production. Sibling of `re-frame.teardown-always-on-elision-
  prod-test` / `re-frame.on-error-elision-prod-test`.

  Naming convention: files ending in `-elision-prod-test.cljs` are picked up
  ONLY by the `:browser-test-prod-elision` build (`:advanced` +
  `{goog.DEBUG false}`, `:ns-regexp \"-elision-prod-test$\"`, runner
  `re-frame.prod-elision-runner`). The default `:browser-test` / `:node-test`
  runners use regexes that do NOT match this suffix, so these tests run only
  under prod-mode compilation."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.substrate.observation :as obs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                ;; Clear the always-on listener registry between tests — the
                ;; `defonce` atom would otherwise leak a listener.
                (error-emit/clear-error-listeners!))}))

(def ^:private fid :rf/default)

(defn- force-dispose-node!
  "Evict the cache entry then dispose the reaction — mirrors every real
  disposal path (the dispose hook enqueues the node-disposed notification)."
  [query-v]
  (let [cache    (:sub-cache (frame/frame fid))
        reaction (:reaction (get @cache query-v))]
    (swap! cache dissoc query-v)
    (interop/dispose! reaction)))

(deftest observation-callback-failure-survives-prod-one-record-zero-trace
  (testing "Per rf2-xakb4p / Spec 006 §Disposal-notification callback failures:
            under `:advanced` + `goog.DEBUG=false`, a former-owner `on-change`
            that throws during the `:disposed` drain fans EXACTLY ONE always-on
            `:rf.error/observation-on-change-failed` record out through the
            corpus-wide `register-error-listener!` substrate (the axis is NOT
            gated by `interop/debug-enabled?`) while the dev diagnostic-trace
            event is DCE'd — one always-on record, zero trace events. The
            contained sibling drain (a healthy owner is still notified) and the
            direct-caller first-escape rethrow are unchanged in production."
    (let [records (atom [])
          traces  (atom [])]
      (error-emit/register-error-listener! ::records (fn [r] (swap! records conj r)))
      (rf/register-listener! :trace ::traces (fn [ev] (swap! traces conj ev)))
      (try
        (rf/reg-sub :obs/items (fn [db _] (:items db)))
        (frame/replace-app-db! fid {:items [:a]})
        (with-redefs [interop/next-tick (fn [_f] nil)]
          (let [boom   (ex-info "untyped on-change boom" {::boom true})
                notes  (atom [])
                target (obs/resolve-target {:frame fid :query-v [:obs/items]})
                ;; two owners of the SAME node; disposal notifies BOTH.
                la     (obs/acquire! target (fn [_n] (throw boom)))
                lb     (obs/acquire! target (fn [n] (swap! notes conj n)))]
            (force-dispose-node! [:obs/items])
            (let [thrown (try (obs/drain-pending-disposals! :disposed) nil
                              (catch :default e e))]
              (testing "full sibling drain — the healthy owner is still notified"
                (is (= 1 (count @notes))))
              (testing "the first escape is rethrown to the direct caller, intact"
                (is (identical? boom thrown)))
              (testing "EXACTLY ONE always-on record survives prod"
                (let [wrapped (filterv #(= :rf.error/observation-on-change-failed
                                           (:error %))
                                       @records)]
                  (is (= 1 (count wrapped)))
                  (is (identical? boom (:exception (first wrapped))))
                  (is (= :obs/items (:event-id (first wrapped))))))
              (testing "ZERO diagnostic trace events under goog.DEBUG=false —
                        the dev-trace leg is DCE'd"
                (is (empty? (filterv #(= :rf.error/observation-on-change-failed
                                         (:operation %))
                                     @traces)))))
            (obs/release! la)
            (obs/release! lb)))
        (finally
          (rf/unregister-listener! :trace ::traces)
          (error-emit/unregister-error-listener! ::records))))))
