(ns day8.re-frame2-xray.filters.error-override-wiring-cljs-test
  "Sub-level wiring for the error-override filter bypass (rf2-jqqsh9).

  The pure algebra lives in `error_override_cljs_test.cljc`; this drives the
  PRODUCTION `:rf.xray/filtered-event-bundles` sub end-to-end so the
  integration is proven: the config plumbs through `configure!` → the
  `:rf.xray/filters-auto-hide-error-overrides?` sub → the filtered-event-bundle
  chain, and the error classifier reaches the bundle through `group-by-event`
  (the errored trace lands in the bundle's `:other` bucket).

  spec/018-Event-Spine.md §7 Error overrides: an errored event a filter would
  hide is surfaced anyway (default `true`); with the bypass off, filters hide
  it too."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  ;; `:post-reset` runs during fixture setup (before each test body), so it
  ;; restores the error-override config to its default (`true`) — a prior
  ;; test that flipped it off can't leak.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn [] (config/set-filters-auto-hide-error-overrides! nil))}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default])))

(defn- dispatch-trace-ev
  "A minimal `:rf.event/dispatched` trace event → one focusable bundle."
  [id event-v]
  {:id        id
   :op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      {:rf.trace/dispatch-id id
               :frame                :rf/default
               :rf.event/v           event-v}})

(defn- error-trace-ev
  "An `:rf.error/*` trace event (`:op-type :error`) carrying the SAME
  dispatch-id so `group-by-event` buckets it into that cascade's `:other`
  slot — the canonical 'this event errored' signal (mirrors
  `shell_cljs_test/error-trace-ev`)."
  [id]
  {:id        (+ id 2000)
   :op-type   :error
   :operation :rf.error/handler-exception
   :tags      {:frame :rf/default :rf.trace/dispatch-id id}})

(defn- out-pill! [& event-ids]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/hydrate-filters
                       {:in [] :out (mapv (fn [id] {:pattern id}) event-ids)}])))

(defn- filtered-bundles []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/filtered-event-bundles])))

(deftest errored-event-survives-an-out-pill-that-would-hide-it
  (testing "rf2-jqqsh9 — with the default bypass ON, an errored event an OUT
            pill would hide is surfaced anyway (spec/018 §7); a CLEAN event the
            same pill matches IS hidden (the pill still works)"
    (setup!)
    ;; cascade 1 — clean :cart/add; cascade 2 — errored :auth/login.
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:cart/add]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:auth/login]))
    (trace-collector/seed-trace-for-test! (error-trace-ev 2))
    (is (= #{1 2} (set (map :dispatch-id (filtered-bundles))))
        "sanity: both cascades visible with no filter")
    ;; OUT-pill BOTH event-ids. The clean one drops; the errored one survives.
    (out-pill! :cart/add :auth/login)
    (let [bundles (filtered-bundles)]
      (is (= [2] (mapv :dispatch-id bundles))
          "the clean OUT-matched cascade is hidden; the errored OUT-matched
           cascade is surfaced anyway")
      (is (true? (:rf.xray/filter-bypassed? (first bundles)))
          "the surfaced errored bundle is tagged for the filter-bypass cue"))))

(deftest disabled-config-lets-filters-hide-errored-events
  (testing "rf2-jqqsh9 — with the bypass explicitly OFF
            (:rf.xray/filters-auto-hide-error-overrides? false) an OUT pill
            hides the errored event too (opt-out honoured through the sub)"
    ;; Set BEFORE the first sub read so the config sub computes against false.
    (config/set-filters-auto-hide-error-overrides! false)
    (setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:auth/login]))
    (trace-collector/seed-trace-for-test! (error-trace-ev 2))
    (out-pill! :auth/login)
    (is (empty? (filtered-bundles))
        "the errored event is hidden when the bypass is disabled")))

(deftest configure-plumbs-the-error-override-flag
  (testing "rf2-jqqsh9 — configure! round-trips the config key + resets on nil"
    (config/configure! {:rf.xray/filters-auto-hide-error-overrides? false})
    (is (false? (config/error-override-bypass-enabled?)))
    (config/configure! {:rf.xray/filters-auto-hide-error-overrides? nil})
    (is (true? (config/error-override-bypass-enabled?))
        "nil resets to the default (true)")))
