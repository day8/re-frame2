(ns re-frame.routing-cljs-test
  "CLJS-side routing tests. Verifies the routing pipeline runs under the
  Reagent reactive substrate and locks the multi-frame routing contract.

  - routing-handle-url-change-cljs       — :rf/url-changed / handle-url-change
                                           drive the slice under the Reagent
                                           adapter; subscriptions resolve.
  - routing-frame-provider-routing-cljs  — multi-frame routing: each frame's
                                           :rf/route slice is independent, the
                                           registry is shared, subscriptions
                                           resolve per-frame.

  Note on test isolation: routing.cljc registers framework events
  (:rf/url-changed, :rf.route/navigate, etc.) at namespace-load time.
  CLJS has no runtime `(require :reload)`, so the JVM-side trick of
  reloading the routing ns to resurrect cleared registrations does not
  work here. These tests use frame creation (not registrar reset) for
  isolation: each test creates fresh frames and the framework events
  remain registered from the initial CLJS load.

  Per Spec 012 §URL changes are events, §Reading the route is a sub,
  §Multi-frame routing."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

;; Snapshot/restore the registrar around each test (rf2-am9d). We do NOT
;; call (registrar/clear-all!): it would wipe routing's framework events
;; (:rf.route/navigate, :rf/url-changed, …) registered at routing.cljc's
;; ns-load, and CLJS cannot re-load namespaces at runtime to restore
;; them. routing/reset-counters! runs in :init-fn so per-test counter
;; sequences (nav-token, pending-nav, …) start from zero.
(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn routing/reset-counters!}))

;; ---- Spec 012 §URL changes are events / §Reading the route is a sub -----

(deftest routing-handle-url-change-cljs
  (testing ":rf/url-changed drives the slice on CLJS"
    ;; Per Spec 012 §URL changes are events: the runtime's URL-driven
    ;; entry point is :rf/url-changed (or :rf.route/handle-url-change for
    ;; SSR-equivalent code paths). Both write the :rf/route slice from the
    ;; URL and dispatch :on-match events. Subscriptions over the slice
    ;; resolve under the Reagent adapter.
    ;;
    ;; Test isolation: a fresh frame so prior tests' :rf/route slices don't
    ;; leak in.
    (let [f (rf/make-frame {:doc "isolated frame for this test"})]
      (rf/reg-route :route.cljs/home
                    {:path "/cljs/home"})
      (rf/reg-route :route.cljs/article
                    {:path     "/cljs/articles/:id"
                     :params   [:map [:id :string]]
                     :on-match [[:cljs/article-load]]})
      (rf/reg-event-db :cljs/article-load
                       (fn [db _] (assoc db :article-loaded? true)))
      (rf/reg-sub :rf.cljs.route/id
                  (fn [db _] (get-in db [:rf/route :id])))
      (rf/reg-sub :rf.cljs.route/params
                  (fn [db _] (get-in db [:rf/route :params])))

      ;; URL-driven nav. The slice is set; :on-match dispatches.
      (rf/dispatch-sync [:rf/url-changed "/cljs/articles/intro"] {:frame f})
      (is (= :route.cljs/article
             (rf/subscribe-value f [:rf.cljs.route/id]))
          ":rf.route/id sub resolves under the Reagent adapter")
      (is (= {:id "intro"}
             (rf/subscribe-value f [:rf.cljs.route/params]))
          ":rf.route/params sub resolves under the Reagent adapter")
      (is (true? (:article-loaded? (rf/get-frame-db f)))
          ":on-match's [:cljs/article-load] dispatched and ran")

      ;; A second navigation through the same path with new params re-fires.
      (rf/dispatch-sync [:rf/url-changed "/cljs/articles/welcome"] {:frame f})
      (is (= {:id "welcome"}
             (rf/subscribe-value f [:rf.cljs.route/params]))
          "new params land in the slice on subsequent navigation")
      (is (some? (get-in (rf/get-frame-db f) [:rf/route :nav-token]))
          "fresh nav-token allocated on each full navigation"))))

;; ---- Spec 012 §Multi-frame routing ---------------------------------------

(deftest routing-frame-provider-routing-cljs
  (testing "two frames carry independent :rf/route slices over a shared registry"
    ;; Per Spec 012 §Multi-frame routing: each frame's :rf/route slice is
    ;; independent — same registered routes, different active route per
    ;; frame. Subscriptions resolve per-frame. This is the contract React
    ;; context-aware routing components rely on (story-variant frames,
    ;; devcards, per-test fixtures).
    (rf/reg-route :route.cljs2/home          {:path "/cljs2/"})
    (rf/reg-route :route.cljs2/articles      {:path "/cljs2/articles"})
    (rf/reg-route :route.cljs2/article       {:path   "/cljs2/articles/:id"
                                              :params [:map [:id :string]]})
    (rf/reg-sub :rf.cljs2/route (fn [db _] (:rf/route db)))

    (let [left  (rf/make-frame {:doc "left tab frame"})
          right (rf/make-frame {:doc "right tab frame"})]

      ;; Each frame navigates independently.
      (rf/dispatch-sync [:rf/url-changed "/cljs2/articles"]
                        {:frame left})
      (rf/dispatch-sync [:rf/url-changed "/cljs2/articles/intro"]
                        {:frame right})

      (let [left-route  (rf/subscribe-value left  [:rf.cljs2/route])
            right-route (rf/subscribe-value right [:rf.cljs2/route])]
        (is (= :route.cljs2/articles (:id left-route))
            "left frame's :rf/route is :route.cljs2/articles")
        (is (= :route.cljs2/article  (:id right-route))
            "right frame's :rf/route is :route.cljs2/article")
        (is (= {} (:params left-route))
            "left frame has no :params (collection route)")
        (is (= {:id "intro"} (:params right-route))
            "right frame has the article id"))

      ;; Re-navigate on the left only — right is unaffected.
      (rf/dispatch-sync [:rf/url-changed "/cljs2/"] {:frame left})
      (is (= :route.cljs2/home
             (:id (rf/subscribe-value left [:rf.cljs2/route])))
          "left re-navigated to :route.cljs2/home")
      (is (= :route.cljs2/article
             (:id (rf/subscribe-value right [:rf.cljs2/route])))
          "right is unaffected by left's navigation"))))
