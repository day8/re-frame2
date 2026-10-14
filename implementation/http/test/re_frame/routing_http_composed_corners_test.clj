(ns re-frame.routing-http-composed-corners-test
  "Composes routing's pending navigation with nav-token reply suppression.

  Routing nav-token staleness
  (routing_nav_token_test.clj::routing-nav-token-staleness +
  with-nav-token-fx-suppresses-stale-reply-to-and-commits-fresh) and managed
  HTTP success/failure/abort are each covered in isolation. This file pins the
  CROSS-FEATURE composition: a pending-navigation continue re-issues the
  navigation and advances the nav-token, so a reply belonging to the
  navigation it superseded is suppressed by the nav-token guard.

  Abort-vs-decode-failure precedence is pinned in
  `re-frame.http-abort-precedence-test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.routing]
            ;; The routing nav-allocation cofx declares a :schema, which needs the
            ;; schemas artefact loaded.
            [re-frame.schemas]
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
