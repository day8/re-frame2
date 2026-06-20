(ns re-frame.routing-cljs-test
  "CLJS-side routing tests. Verifies the routing pipeline runs under the
  Reagent reactive substrate and locks the multi-frame routing contract.

  - routing-handle-url-change-cljs       — :rf.route/transitioned / handle-url-change
                                           drive the slice under the Reagent
                                           adapter; subscriptions resolve.
  - routing-frame-provider-routing-cljs  — multi-frame routing: each frame's
                                           [:rf.db/runtime :rf.runtime/routing :current] slice
                                           is independent, the registry is
                                           shared, subscriptions resolve
                                           per-frame.

  Note on test isolation: routing.cljc registers framework events
  (:rf.route/transitioned, :rf.route/navigate, etc.) at namespace-load time.
  CLJS has no runtime `(require :reload)`, so the JVM-side trick of
  reloading the routing ns to resurrect cleared registrations does not
  work here. These tests use frame creation (not registrar reset) for
  isolation: each test creates fresh frames and the framework events
  remain registered from the initial CLJS load.

  Per Spec 012 §URL changes are events, §Reading the route is a sub,
  §Multi-frame routing."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.subs :as subs]
            [re-frame.routing :as routing]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

;; Snapshot/restore the registrar around each test (rf2-am9d). We do NOT
;; call (registrar/clear-all!): it would wipe routing's framework events
;; (:rf.route/navigate, :rf.route/transitioned, …) registered at routing.cljc's
;; ns-load, and CLJS cannot re-load namespaces at runtime to restore
;; them. routing/reset-counters! runs in :init-fn so per-test counter
;; sequences (nav-token, pending-nav, …) start from zero.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn routing/reset-counters!}))

;; ---- Spec 012 §URL changes are events / §Reading the route is a sub -----

(deftest routing-handle-url-change-cljs
  (testing ":rf.route/transitioned drives the slice on CLJS"
    ;; Per Spec 012 §URL changes are events: the runtime's URL-driven
    ;; entry point is :rf.route/transitioned (or :rf.route/handle-url-change for
    ;; SSR-equivalent code paths). Both write the [:rf.db/runtime :rf.runtime/routing :current]
    ;; slice from the URL and dispatch :on-match events. Subscriptions over
    ;; the slice resolve under the Reagent adapter.
    ;;
    ;; Test isolation: a fresh frame so prior tests' [:rf.db/runtime :rf.runtime/routing :current]
    ;; slices don't leak in.
    (let [f (frame/make-anon-frame-record! {:doc "isolated frame for this test"})]
      (rf/reg-route :route.cljs/home
                    {} "/cljs/home")
      (rf/reg-route :route.cljs/article
                    {:params   [:map [:id :string]]
                     :on-match [[:cljs/article-load]]} "/cljs/articles/:id")
      (rf/reg-event :cljs/article-load
                       (fn [{:keys [db]} _] {:db (assoc db :article-loaded? true)}))
      ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db
      ;; state — these custom subs read the runtime-db partition.
      (subs/reg-runtime-sub :rf.cljs.route/id
                  (fn [rt _] (get-in rt [:rf.runtime/routing :current :route-id])))
      (subs/reg-runtime-sub :rf.cljs.route/params
                  (fn [rt _] (get-in rt [:rf.runtime/routing :current :params])))

      ;; URL-driven nav. The slice is set; :on-match dispatches.
      (rf/dispatch-sync [:rf.route/transitioned "/cljs/articles/intro"] {:frame f})
      (is (= :route.cljs/article
             (rf/subscribe-once f [:rf.cljs.route/id]))
          ":rf.route/id sub resolves under the Reagent adapter")
      (is (= {:id "intro"}
             (rf/subscribe-once f [:rf.cljs.route/params]))
          ":rf.route/params sub resolves under the Reagent adapter")
      (is (true? (:article-loaded? (rf/app-db-value f)))
          ":on-match's [:cljs/article-load] dispatched and ran")

      ;; A second navigation through the same path with new params re-fires.
      (rf/dispatch-sync [:rf.route/transitioned "/cljs/articles/welcome"] {:frame f})
      (is (= {:id "welcome"}
             (rf/subscribe-once f [:rf.cljs.route/params]))
          "new params land in the slice on subsequent navigation")
      (is (some? (get-in (rf/frame-state-value f) [:rf.db/runtime :rf.runtime/routing :current :nav-token]))
          "fresh nav-token allocated on each full navigation"))))

;; ---- Spec 012 §Multi-frame routing ---------------------------------------

(deftest routing-frame-provider-routing-cljs
  (testing "two frames carry independent [:rf.db/runtime :rf.runtime/routing :current] slices over a shared registry"
    ;; Per Spec 012 §Multi-frame routing: each frame's [:rf.db/runtime :rf.runtime/routing :current]
    ;; slice is independent — same registered routes, different active route
    ;; per frame. Subscriptions resolve per-frame. This is the contract React
    ;; context-aware routing components rely on (story-variant frames,
    ;; devcards, per-test fixtures).
    (rf/reg-route :route.cljs2/home          {} "/cljs2/")
    (rf/reg-route :route.cljs2/articles      {} "/cljs2/articles")
    (rf/reg-route :route.cljs2/article       {:params [:map [:id :string]]} "/cljs2/articles/:id")
    (subs/reg-runtime-sub :rf.cljs2/route (fn [rt _] (get-in rt [:rf.runtime/routing :current])))

    (let [left  (frame/make-anon-frame-record! {:doc "left tab frame"})
          right (frame/make-anon-frame-record! {:doc "right tab frame"})]

      ;; Each frame navigates independently.
      (rf/dispatch-sync [:rf.route/transitioned "/cljs2/articles"]
                        {:frame left})
      (rf/dispatch-sync [:rf.route/transitioned "/cljs2/articles/intro"]
                        {:frame right})

      (let [left-route  (rf/subscribe-once left  [:rf.cljs2/route])
            right-route (rf/subscribe-once right [:rf.cljs2/route])]
        (is (= :route.cljs2/articles (:route-id left-route))
            "left frame's current route is :route.cljs2/articles")
        (is (= :route.cljs2/article  (:route-id right-route))
            "right frame's current route is :route.cljs2/article")
        (is (= {} (:params left-route))
            "left frame has no :params (collection route)")
        (is (= {:id "intro"} (:params right-route))
            "right frame has the article id"))

      ;; Re-navigate on the left only — right is unaffected.
      (rf/dispatch-sync [:rf.route/transitioned "/cljs2/"] {:frame left})
      (is (= :route.cljs2/home
             (:route-id (rf/subscribe-once left [:rf.cljs2/route])))
          "left re-navigated to :route.cljs2/home")
      (is (= :route.cljs2/article
             (:route-id (rf/subscribe-once right [:rf.cljs2/route])))
          "right is unaffected by left's navigation"))))
