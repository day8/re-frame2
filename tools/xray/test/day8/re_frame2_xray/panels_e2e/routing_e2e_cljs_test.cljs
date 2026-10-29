(ns day8.re-frame2-xray.panels-e2e.routing-e2e-cljs-test
  "Multi-frame e2e coverage for the Routing panel (spec/017 —
  Routes row).

  The Routing panel reads:
    - `:rf.xray/registered-routes` — every route registered via the
      framework's `re-frame.routing` artefact.
    - `:rf.xray/current-route-slice` — the active route slice on
      the focused frame's app-db.
    - `:rf.xray/routing-tab-data` — the composite the view consumes.

  The host registers a real route with `rf/reg-route` and navigates to
  it through the router's own URL-change door, so the slice under test
  is the one the router writes. Route-table emptiness is not asserted:
  other namespaces on the node-test classpath register routes at load
  time."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- install-host! []
  (counter/install-and-init!)
  (rf/reg-route ::article {} "/routing-e2e/article/:slug"))

(deftest routing-tab-data-reads-a-real-navigation
  (testing "a navigated host route lists in the topology, is the current
            route with its params, and is the focused navigation's TO"
    (e2e/with-host-and-xray-frames
      {:install-host install-host!}
      (fn []
        (e2e/dispatch-host [:rf.route/handle-url-change "/routing-e2e/article/hello"
                            {:rf.route/cause :link}])
        (let [data (e2e/sub-xray [:rf.xray/routing-tab-data])]
          (is (some #(= ::article (get-in % [:row :route-id])) (:topology data)))
          (is (= ::article (get-in data [:current :route-id])))
          (is (= {:slug "hello"} (get-in data [:current :params])))
          (is (= [true ::article :on-match]
                 ((juxt :navigated? :to-id (comp :phase :activity)) data))))))))

(deftest routing-tab-data-is-quiet-for-a-non-route-event
  (testing "CONTROL — a focused non-route event navigated nowhere: no
            navigation, no activity, and the current route is unchanged"
    (e2e/with-host-and-xray-frames
      {:install-host install-host!}
      (fn []
        (e2e/dispatch-host [:rf.route/handle-url-change "/routing-e2e/article/hello"
                            {:rf.route/cause :link}])
        (e2e/dispatch-host [:counter/inc])
        (let [data (e2e/sub-xray [:rf.xray/routing-tab-data])]
          (is (= [false nil] ((juxt :navigated? :activity) data)))
          (is (= ::article (get-in data [:current :route-id]))))))))
