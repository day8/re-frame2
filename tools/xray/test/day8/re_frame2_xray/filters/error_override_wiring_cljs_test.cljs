(ns day8.re-frame2-xray.filters.error-override-wiring-cljs-test
  "Sub-level wiring for the error-override filter bypass (spec/018 §7):
  the config flag reaches the production `:rf.xray/filtered-event-bundles`
  sub, and the errored trace reaches the bundle through `group-by-event`.
  The pure algebra is in `error_override_cljs_test.cljc`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  ;; `:post-reset` runs before each test body, restoring the flag's default
  ;; so a test that turned it off cannot leak.
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
  "An error trace on dispatch-id `id`, which `group-by-event` buckets into
  that bundle's `:other` slot."
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
  (testing "with the default bypass ON, an errored event an OUT pill matches
            is surfaced anyway and tagged; a clean event the same pill
            matches IS hidden"
    (setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:cart/add]))
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:auth/login]))
    (trace-collector/seed-trace-for-test! (error-trace-ev 2))
    (out-pill! :cart/add :auth/login)
    (let [bundles (filtered-bundles)]
      (is (= [2] (mapv :dispatch-id bundles)))
      (is (true? (:rf.xray/filter-bypassed? (first bundles)))))))

(deftest disabled-config-lets-filters-hide-errored-events
  (testing "with the bypass OFF an OUT pill hides the errored event too"
    ;; Set before the first sub read so the config sub computes against false.
    (config/set-filters-auto-hide-error-overrides! false)
    (setup!)
    (trace-collector/seed-trace-for-test! (dispatch-trace-ev 2 [:auth/login]))
    (trace-collector/seed-trace-for-test! (error-trace-ev 2))
    (out-pill! :auth/login)
    (is (empty? (filtered-bundles)))))

(deftest configure-plumbs-the-error-override-flag
  (testing "configure! round-trips the config key + resets on nil"
    (config/configure! {:rf.xray/filters-auto-hide-error-overrides? false})
    (is (false? (config/error-override-bypass-enabled?)))
    (config/configure! {:rf.xray/filters-auto-hide-error-overrides? nil})
    (is (true? (config/error-override-bypass-enabled?))
        "nil resets to the default (true)")))
