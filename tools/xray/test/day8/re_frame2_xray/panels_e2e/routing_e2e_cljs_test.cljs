(ns day8.re-frame2-xray.panels-e2e.routing-e2e-cljs-test
  "Multi-frame e2e coverage for the Routing panel (rf2-7icrs,
  spec/017 — Routes row).

  The Routing panel reads:
    - `:rf.xray/registered-routes` — every route registered via the
      framework's `re-frame.routing` artefact.
    - `:rf.xray/current-route-slice` — the active route slice on
      the focused frame's app-db.
    - `:rf.xray/routing-tab-data` — the composite the view consumes.

  At the e2e level we assert the composite resolves cleanly even
  when the host has zero routes registered (the empty-route empty-
  state shape MUST survive). A separate test with at least one
  registered route would add coverage but requires the routing
  artefact's `reg-route` macro which lives outside `core/`; deferred
  to a follow-on bead to keep the e2e helper's surface minimal."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest xray-registered-routes-resolves
  (testing ":rf.xray/registered-routes returns a coll without throwing"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        ;; `:rf.xray/registered-routes` reads the global route registry
        ;; via `rf/registrations :route` so its value depends on every
        ;; namespace that ran `reg-route` at load time. We assert
        ;; resolution, not emptiness — emptiness is too noisy across
        ;; the cross-tree node-test classpath.
        (let [routes (e2e/sub-xray [:rf.xray/registered-routes])]
          (is (or (nil? routes) (coll? routes) (map? routes))
              ":rf.xray/registered-routes returned non-coll non-nil"))))))

(deftest xray-routing-tab-data-resolves-shape-empty
  (testing ":rf.xray/routing-tab-data composes even with no routes"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (let [data (e2e/sub-xray [:rf.xray/routing-tab-data])]
          (is (map? data)
              ":rf.xray/routing-tab-data did not return a map with no routes registered"))))))

(deftest xray-routing-tab-data-resolves-shape-after-host-dispatch
  (testing "routing-tab-data still resolves after a non-route host event"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [data (e2e/sub-xray [:rf.xray/routing-tab-data])]
          (is (map? data)
              ":rf.xray/routing-tab-data did not return a map after host dispatch"))))))
