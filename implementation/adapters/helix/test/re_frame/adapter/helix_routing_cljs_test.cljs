(ns re-frame.adapter.helix-routing-cljs-test
  "Adapter-parity port of `re-frame.routing-cljs-test` to the Helix
  adapter (rf2-ta4b5).

  Verifies the routing pipeline runs under the Helix reactive substrate
  and locks the multi-frame routing contract.

  - routing-handle-url-change-helix       — :rf/url-changed /
                                            handle-url-change drive the
                                            :rf/route slice under the
                                            Helix adapter; subscriptions
                                            resolve.
  - routing-frame-provider-routing-helix  — multi-frame routing: each
                                            frame's :rf/route slice is
                                            independent, the registry is
                                            shared, subscriptions resolve
                                            per-frame.

  Per Spec 012 §URL changes are events, §Reading the route is a sub,
  §Multi-frame routing. Parallel to:
    - implementation/adapters/reagent/test/re_frame/routing_cljs_test.cljs

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter helix-adapter/adapter
     :init-fn routing/reset-counters!}))

;; ---- Spec 012 §URL changes are events / §Reading the route is a sub -----

(deftest routing-handle-url-change-helix
  (testing ":rf/url-changed drives the slice on Helix"
    (let [f (rf/make-frame {:doc "isolated frame for this test"})]
      (rf/reg-route :route.helix/home
                    {:path "/helix/home"})
      (rf/reg-route :route.helix/article
                    {:path     "/helix/articles/:id"
                     :params   [:map [:id :string]]
                     :on-match [[:helix/article-load]]})
      (rf/reg-event-db :helix/article-load
                       (fn [db _] (assoc db :article-loaded? true)))
      (rf/reg-sub :rf.helix.route/id
                  (fn [db _] (get-in db [:rf/route :id])))
      (rf/reg-sub :rf.helix.route/params
                  (fn [db _] (get-in db [:rf/route :params])))

      (rf/dispatch-sync [:rf/url-changed "/helix/articles/intro"] {:frame f})
      (is (= :route.helix/article
             (rf/subscribe-value f [:rf.helix.route/id]))
          ":rf.route/id sub resolves under the Helix adapter")
      (is (= {:id "intro"}
             (rf/subscribe-value f [:rf.helix.route/params]))
          ":rf.route/params sub resolves under the Helix adapter")
      (is (true? (:article-loaded? (rf/get-frame-db f)))
          ":on-match's [:helix/article-load] dispatched and ran")

      (rf/dispatch-sync [:rf/url-changed "/helix/articles/welcome"] {:frame f})
      (is (= {:id "welcome"}
             (rf/subscribe-value f [:rf.helix.route/params]))
          "new params land in the slice on subsequent navigation")
      (is (some? (get-in (rf/get-frame-db f) [:rf/route :nav-token]))
          "fresh nav-token allocated on each full navigation"))))

;; ---- Spec 012 §Multi-frame routing ---------------------------------------

(deftest routing-frame-provider-routing-helix
  (testing "two frames carry independent :rf/route slices over a shared registry under Helix"
    (rf/reg-route :route.helix2/home          {:path "/helix2/"})
    (rf/reg-route :route.helix2/articles      {:path "/helix2/articles"})
    (rf/reg-route :route.helix2/article       {:path   "/helix2/articles/:id"
                                               :params [:map [:id :string]]})
    (rf/reg-sub :rf.helix2/route (fn [db _] (:rf/route db)))

    (let [left  (rf/make-frame {:doc "left tab frame"})
          right (rf/make-frame {:doc "right tab frame"})]

      (rf/dispatch-sync [:rf/url-changed "/helix2/articles"]
                        {:frame left})
      (rf/dispatch-sync [:rf/url-changed "/helix2/articles/intro"]
                        {:frame right})

      (let [left-route  (rf/subscribe-value left  [:rf.helix2/route])
            right-route (rf/subscribe-value right [:rf.helix2/route])]
        (is (= :route.helix2/articles (:id left-route))
            "left frame's :rf/route is :route.helix2/articles")
        (is (= :route.helix2/article  (:id right-route))
            "right frame's :rf/route is :route.helix2/article")
        (is (= {} (:params left-route))
            "left frame has no :params (collection route)")
        (is (= {:id "intro"} (:params right-route))
            "right frame has the article id"))

      (rf/dispatch-sync [:rf/url-changed "/helix2/"] {:frame left})
      (is (= :route.helix2/home
             (:id (rf/subscribe-value left [:rf.helix2/route])))
          "left re-navigated to :route.helix2/home")
      (is (= :route.helix2/article
             (:id (rf/subscribe-value right [:rf.helix2/route])))
          "right is unaffected by left's navigation"))))
