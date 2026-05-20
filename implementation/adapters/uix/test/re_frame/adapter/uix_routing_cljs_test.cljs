(ns re-frame.adapter.uix-routing-cljs-test
  "Adapter-parity port of `re-frame.routing-cljs-test` to the UIx
  adapter (rf2-ta4b5).

  Verifies the routing pipeline runs under the UIx reactive substrate
  and locks the multi-frame routing contract.

  - routing-handle-url-change-uix       — :rf/url-changed /
                                          handle-url-change drive the
                                          :rf/route slice under the
                                          UIx adapter; subscriptions
                                          resolve.
  - routing-frame-provider-routing-uix  — multi-frame routing: each
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
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter uix-adapter/adapter
     :init-fn routing/reset-counters!}))

;; ---- Spec 012 §URL changes are events / §Reading the route is a sub -----

(deftest routing-handle-url-change-uix
  (testing ":rf/url-changed drives the slice on UIx"
    (let [f (rf/make-frame {:doc "isolated frame for this test"})]
      (rf/reg-route :route.uix/home
                    {:path "/uix/home"})
      (rf/reg-route :route.uix/article
                    {:path     "/uix/articles/:id"
                     :params   [:map [:id :string]]
                     :on-match [[:uix/article-load]]})
      (rf/reg-event-db :uix/article-load
                       (fn [db _] (assoc db :article-loaded? true)))
      (rf/reg-sub :rf.uix.route/id
                  (fn [db _] (get-in db [:rf/route :id])))
      (rf/reg-sub :rf.uix.route/params
                  (fn [db _] (get-in db [:rf/route :params])))

      (rf/dispatch-sync [:rf/url-changed "/uix/articles/intro"] {:frame f})
      (is (= :route.uix/article
             (rf/subscribe-once f [:rf.uix.route/id]))
          ":rf.route/id sub resolves under the UIx adapter")
      (is (= {:id "intro"}
             (rf/subscribe-once f [:rf.uix.route/params]))
          ":rf.route/params sub resolves under the UIx adapter")
      (is (true? (:article-loaded? (rf/get-frame-db f)))
          ":on-match's [:uix/article-load] dispatched and ran")

      (rf/dispatch-sync [:rf/url-changed "/uix/articles/welcome"] {:frame f})
      (is (= {:id "welcome"}
             (rf/subscribe-once f [:rf.uix.route/params]))
          "new params land in the slice on subsequent navigation")
      (is (some? (get-in (rf/get-frame-db f) [:rf/route :nav-token]))
          "fresh nav-token allocated on each full navigation"))))

;; ---- Spec 012 §Multi-frame routing ---------------------------------------

(deftest routing-frame-provider-routing-uix
  (testing "two frames carry independent :rf/route slices over a shared registry under UIx"
    (rf/reg-route :route.uix2/home          {:path "/uix2/"})
    (rf/reg-route :route.uix2/articles      {:path "/uix2/articles"})
    (rf/reg-route :route.uix2/article       {:path   "/uix2/articles/:id"
                                             :params [:map [:id :string]]})
    (rf/reg-sub :rf.uix2/route (fn [db _] (:rf/route db)))

    (let [left  (rf/make-frame {:doc "left tab frame"})
          right (rf/make-frame {:doc "right tab frame"})]

      (rf/dispatch-sync [:rf/url-changed "/uix2/articles"]
                        {:frame left})
      (rf/dispatch-sync [:rf/url-changed "/uix2/articles/intro"]
                        {:frame right})

      (let [left-route  (rf/subscribe-once left  [:rf.uix2/route])
            right-route (rf/subscribe-once right [:rf.uix2/route])]
        (is (= :route.uix2/articles (:id left-route))
            "left frame's :rf/route is :route.uix2/articles")
        (is (= :route.uix2/article  (:id right-route))
            "right frame's :rf/route is :route.uix2/article")
        (is (= {} (:params left-route))
            "left frame has no :params (collection route)")
        (is (= {:id "intro"} (:params right-route))
            "right frame has the article id"))

      (rf/dispatch-sync [:rf/url-changed "/uix2/"] {:frame left})
      (is (= :route.uix2/home
             (:id (rf/subscribe-once left [:rf.uix2/route])))
          "left re-navigated to :route.uix2/home")
      (is (= :route.uix2/article
             (:id (rf/subscribe-once right [:rf.uix2/route])))
          "right is unaffected by left's navigation"))))
