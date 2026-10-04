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
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.routing]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; ---- fixture --------------------------------------------------------------

;; The canonical fixture fires the routing reset hooks in its post-dispose
;; phase: `:routing/reset-counters!` (deterministic reg-index) and
;; `:routing/reset-nav-counters!` (host-side nav-token /
;; pending-nav counters the `frames` reset does not clear, keeping "nav-1" /
;; "nav-2" stable across tests). It also clears trace listeners and
;; the per-frame HTTP interceptor chain.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers --------------------------------------------------------------

(defn- record!
  [id]
  (let [a (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! a conj ev)))
    [a #(rf/unregister-listener! :trace id)]))

(defn- stale-suppressed-traces [recorded]
  (filter #(= :rf.route.nav-token/stale-suppressed (:operation %)) @recorded))

;; ---------------------------------------------------------------------------
;; 1. Pending-navigation cancel leaves in-flight managed HTTP from the
;;    active route untouched
;;
;; Active route :route/editor declares :can-leave returning false; user
;; issues :rf.route/url-requested to a sibling URL; pending-nav slot
;; populates. While pending, the active route has a managed HTTP request
;; in flight (seeded into the in-flight registry). User cancels via
;; :rf.route/cancel. The pending-nav slot clears; the in-flight registry
;; is NOT pre-emptively cleared by the cancel (the request belongs to the
;; route the user is staying on); a subsequent abort or natural completion
;; is the only cleanup trigger.
;; ---------------------------------------------------------------------------

(deftest pending-navigation-cancel-clears-pending-without-touching-in-flight
  (testing ":rf.route/cancel clears the pending-nav slot; in-flight
            managed HTTP from the active route is NOT torn down by cancel"
    ;; Active route :route/editor: :can-leave returns false → blocks the
    ;; navigation.
    (rf/reg-sub :editor/blocked? (fn [_ _] false)) ;; false = "cannot leave"
    (rf/reg-route :route/editor
                  {:params    [:map [:id :string]]
                   :can-leave :editor/blocked?} "/editor/:id")
    (rf/reg-route :route/home {} "/")
    (rf.fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))

    ;; Land on /editor/draft.
    (rf/dispatch-sync [:rf.route/handle-url-change "/editor/draft" {:rf.route/cause :link}])
    (is (= :route/editor (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                 [:rf.runtime/routing :current :route-id]))
        "precondition: landed on :route/editor")

    ;; To pin "cancel does not touch in-flight bookkeeping" we seed an
    ;; in-flight entry through the registry's test helper — that mirrors
    ;; what http-handlers/managed-handler does at issue time (preserving
    ;; both-index invariants) without requiring an unsettled real network
    ;; or a brittle stub.
    (rf.http.managed/seed-in-flight-for-test!
      {:request-id :editor.save/draft
       :url        "/api/editor/draft"
       :abort-fn   (fn [_] nil)})
    (is (contains? (rf.http.managed/in-flight-snapshot) :editor.save/draft)
        "precondition: in-flight registry holds the active-route request")

    ;; User requests navigation to /home — leave guard blocks; pending-nav populates.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/home"}])
    (let [pending (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :pending-navigation])]
      (is (some? pending) "precondition: pending-nav slot populated")
      ;; `/home` matches no registered route here (`:route/home` is "/"), so
      ;; the replay destination stays the RAW `{:url …}` escape rather than
      ;; being rewritten as the not-found route's address — continuing would
      ;; otherwise change the requested URL.
      (is (= {:url "/home"} (:destination pending))
          "the leave-only pending value carries the raw replayable destination")

      ;; User cancels.
      (rf/dispatch-sync [:rf.route/cancel (:id pending)])

      ;; The pending-nav slot is cleared.
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :pending-navigation]))
          "[:rf.runtime/routing :pending-navigation] slot is cleared post-cancel")

      ;; The in-flight HTTP request from the active route is UNTOUCHED —
      ;; cancel of the navigation does not abort requests issued by the
      ;; route the user is staying on. Cleanup is the user's
      ;; responsibility (or the request's natural completion / abort).
      (is (contains? (rf.http.managed/in-flight-snapshot) :editor.save/draft)
          "post-cancel: in-flight registry still holds the active-route's request — cancel does NOT abort active-route HTTP")

      ;; Slice still reflects the active route (no nav happened).
      (is (= :route/editor (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                   [:rf.runtime/routing :current :route-id]))
          "post-cancel: current route slice still on the active editor route"))))

;; ---------------------------------------------------------------------------
;; 2. Composed stale HTTP reply DURING a pending-navigation cycle is
;;    suppressed when it arrives post-resume
;;
;; User on /articles/A; an on-match handler issues a managed HTTP with
;; an :on-success that wraps in :rf.route/with-nav-token. While the
;; request is in flight (nav-token = "nav-1"), user navigates to
;; /articles/B but the leave guard blocks; user continues; navigation
;; B settles (nav-token bumps). Then A's stale reply arrives — it
;; carries "nav-1"; current is the post-continue value; suppression
;; fires. The composed test asserts the suppression survives across the
;; pending-nav cycle (does not get reset to "nav-1" by the continue).
;; ---------------------------------------------------------------------------

(deftest stale-http-reply-after-pending-nav-cycle-is-suppressed
  (testing "managed HTTP reply for a navigation that was superseded
            VIA a pending-nav cycle is suppressed by nav-token"
    ;; :can-leave/:editor/can-leave? — true = can-leave, false = block.
    ;; We start TRUE (does not block) so the initial landing works,
    ;; then re-bind to FALSE so the pending-nav fires.
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
        (rf.fx/reg-fx :rf.nav/push-url
                   {:platforms #{:server :client}}
                   (fn [_ url] (swap! pushed conj url)))

        ;; Land on /articles/A.
        (rf/dispatch-sync [:rf.route/handle-url-change "/articles/A" {:rf.route/cause :link}])
        (let [token-A (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                              [:rf.runtime/routing :current :nav-token])]
          (is (= "nav-1" token-A) "precondition: A's nav-token is nav-1")

          ;; Flip the sub so subsequent navigation requests block.
          (rf/reg-sub :editor/can-leave? (fn [_ _] false))
          (rf/dispatch-sync [:rf.route/url-requested {:url "/articles/B"}])
          (let [pending (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :pending-navigation])]
            (is (some? pending) "precondition: pending-nav slot populated")

            ;; Continue — nav-token bumps as part of the continued
            ;; :rf.route/handle-url-change dispatch.
            (rf/dispatch-sync [:rf.route/continue (:id pending)]))
          (let [token-after (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                    [:rf.runtime/routing :current :nav-token])]
            (is (not= token-A token-after)
                "nav-token advanced past the pending cycle")
            (is (some #{"/articles/B"} @pushed)
                "the continue re-issued the navigation: :rf.nav/push-url received /articles/B")

            ;; A's stale reply finally arrives, carrying token-A.
            (rf/dispatch-sync [:article/loaded-bridge
                               {:carried-token token-A
                                :id            "A"
                                :payload       "A-payload"}])

            ;; A's payload did NOT commit — suppression fired.
            (is (nil? (:article (rf/app-db-value :rf/default)))
                "A's stale payload did NOT commit on top of the post-continue route")
            (let [stale (stale-suppressed-traces recorded)]
              (is (= 1 (count stale))
                  "exactly one :rf.route.nav-token/stale-suppressed for A's late reply")
              (let [tags (:tags (first stale))]
                (is (= token-A (:carried-token tags))
                    "trace carries A's stale nav-token")
                (is (= token-after (:current-token tags))
                    "trace carries the post-continue current nav-token")))))
        (finally (unreg))))))
