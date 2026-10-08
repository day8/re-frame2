(ns re-frame.routing-http-composed-corners-test
  "Composes routing's pending navigation and nav-token suppression with
  managed HTTP.

  Routing nav-token staleness
  (routing_nav_token_test.clj::routing-nav-token-staleness +
  with-nav-token-fx-suppresses-stale-reply-to-and-commits-fresh) and managed
  HTTP success/failure/abort are each covered in isolation. This file pins the
  CROSS-FEATURE composition:
    - a pending-navigation cancel leaves the active route's in-flight
      managed request registered;
    - a pending-navigation continue re-issues the navigation and advances
      the nav-token, so a managed HTTP reply belonging to the navigation it
      superseded is suppressed by the nav-token guard.

  Abort-vs-decode-failure precedence is pinned in
  `re-frame.http-abort-precedence-test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.routing]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- record!
  [id]
  (let [a (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! a conj ev)))
    [a #(rf/unregister-listener! :trace id)]))

(defn- stale-suppressed-traces [recorded]
  (filter #(= :rf.route.nav-token/stale-suppressed (:operation %)) @recorded))

(defn- routing-runtime []
  (:rf.runtime/routing (:rf.db/runtime (rf/frame-state-value :rf/default))))

(deftest pending-navigation-cancel-clears-pending-without-touching-in-flight
  ;; The request belongs to the route the user stays on, so cancelling the
  ;; pending navigation must not abort it.
  (rf/reg-sub :editor/blocked? (fn [_ _] false))
  (rf/reg-route :route/editor
                {:params    [:map [:id :string]]
                 :can-leave :editor/blocked?} "/editor/:id")
  (rf/reg-route :route/home {} "/")
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/dispatch-sync [:rf.route/handle-url-change "/editor/draft" {:rf.route/cause :link}])
  ;; Seeded as managed-handler records a request at issue time.
  (rf.http.managed/seed-in-flight-for-test!
    {:request-id :editor.save/draft
     :url        "/api/editor/draft"
     :abort-fn   (fn [_] nil)})
  (rf/dispatch-sync [:rf.route/url-requested {:url "/home"}])
  (let [pending (:pending-navigation (routing-runtime))]
    ;; `/home` matches no registered route, so the destination stays the raw
    ;; {:url …} escape; continuing must not change the requested URL.
    (is (= {:url "/home"} (:destination pending)))
    (rf/dispatch-sync [:rf.route/cancel (:id pending)])
    (is (= [nil :route/editor true]
           [(:pending-navigation (routing-runtime))
            (get-in (routing-runtime) [:current :route-id])
            (contains? (rf.http.managed/in-flight-snapshot) :editor.save/draft)])
        "the pending slot clears, the route stays, and the active route's request is still in flight")))

(deftest stale-http-reply-after-pending-nav-cycle-is-suppressed
  ;; A reply wrapped in :rf.route/with-nav-token for a navigation superseded
  ;; through a pending-nav cycle stays suppressed: the continue advances the
  ;; nav-token rather than resetting it.
  (rf/reg-sub :editor/can-leave? (fn [_ _] true))
  (rf/reg-route :route/article
                {:params    [:map [:id :string]]
                 :can-leave :editor/can-leave?} "/articles/:id")
  (rf/reg-event :article/loaded
    (fn [{:keys [db]} [_ id payload]]
      {:db (assoc db :article {:id id :payload payload})}))
  (rf/reg-event :article/loaded-bridge
    (fn [_ [_ {:keys [carried-token id payload]}]]
      {:fx [[:rf.route/with-nav-token
             {:rf/reply-to [:article/loaded id payload]
              :nav-token   carried-token}]]}))
  (let [[recorded unreg] (record! ::stale-cycle)
        pushed           (atom [])]
    (try
      (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles/A" {:rf.route/cause :link}])
      (let [token-A (get-in (routing-runtime) [:current :nav-token])]
        (rf/reg-sub :editor/can-leave? (fn [_ _] false))
        (rf/dispatch-sync [:rf.route/url-requested {:url "/articles/B"}])
        (rf/dispatch-sync [:rf.route/continue (:id (:pending-navigation (routing-runtime)))])
        (let [token-after (get-in (routing-runtime) [:current :nav-token])]
          (is (not= token-A token-after) "the nav-token advanced past the pending cycle")
          (is (some #{"/articles/B"} @pushed) "the continue re-issued the navigation")
          (rf/dispatch-sync [:article/loaded-bridge {:carried-token token-A :id "A" :payload "A-payload"}])
          (is (nil? (:article (rf/app-db-value :rf/default))) "A's stale payload did not commit")
          (is (= [{:carried-token token-A :current-token token-after}]
                 (mapv #(select-keys (:tags %) [:carried-token :current-token])
                       (stale-suppressed-traces recorded))))))
      (finally (unreg)))))
