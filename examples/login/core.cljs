(ns login.core
  "Worked end-to-end example: a login feature using the imagined re-frame2 API.

   Demonstrates:
   - Feature scaffold (CP-6)              — the :auth.login/* registry slice
   - Schema attachment (CP-8)              — Malli schemas for the slice and events
   - Event handlers (CP-1)                 — pure (state, event) → effects
   - Subscriptions (CP-2)                  — pure derivations, including a :<- chain
   - Registered fx (CP-3)                  — :http with :platforms metadata
   - Registered view (CP-4)                — Var reference (canonical), Form-1 only
   - State machine (CP-5)                  — login flow as a transition table
   - Open-map idiom                        — every shape on the wire is an open map
   - Headless test                         — JVM-runnable smoke test

   In a real codebase, this single file would be split per CP-6 conventions:
     login/schema.cljc | events.cljs | subs.cljs | views.cljs |
     machines.cljs | events_test.cljs

   Kept as a single file here for brevity."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]))

;; ============================================================================
;; SCHEMAS  (CP-8)
;; ============================================================================
;;
;; Open by default. The slice schema describes the shape of [:auth.login] in
;; app-db; event schemas describe the shape of dispatched event vectors. None
;; carry :closed true — this isn't a system boundary.

(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

(def AuthLoginState
  [:map
   [:state    [:enum :idle :submitting :error-shown :authed :locked-out]]
   [:context  [:map
               [:attempts {:default 0} :int]
               [:error    [:maybe :string]]]]
   [:user     [:maybe [:map [:id :uuid] [:email :string]]]]])

(def SubmitEvent
  [:tuple [:= :auth.login/submit] Credentials])

(rf/reg-app-schema [:auth :login] AuthLoginState)

;; ============================================================================
;; FX  (CP-3)
;; ============================================================================
;;
;; :http is server-and-client; the :platforms metadata gates SSR per [011].
;; :auth.session/store is client-only — localStorage is a browser API.

(rf/reg-fx :http
  {:doc       "Issue an HTTP request. On completion, dispatch :on-success or :on-error."
   :platforms #{:server :client}}
  (fn fx-http [m {:keys [method url body on-success on-error]}]
    (let [frame-id (:frame m)]
      (-> (perform-http-request method url body)
          (.then  (fn [resp] (when on-success (rf/dispatch (conj on-success resp) {:frame frame-id}))))
          (.catch (fn [err]  (when on-error  (rf/dispatch (conj on-error err)   {:frame frame-id}))))))))

(rf/reg-fx :auth.session/store
  {:doc       "Persist the session token in localStorage. Client only."
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    (.setItem js/localStorage "auth/token" token)))

;; Test stub — id-valued override, the canonical pattern-level form.
(rf/reg-fx :http.canned-success
  {:doc       "Test stub: every :http call resolves to a canned success response."
   :platforms #{:client :server}}
  (fn fx-http-canned-success [_m {:keys [on-success]}]
    (when on-success
      (rf/dispatch (conj on-success {:user {:id (random-uuid) :email "test@example.com"}
                                     :token "test-token-123"})))))

;; ============================================================================
;; STATE MACHINE  (CP-5)
;; ============================================================================
;;
;; The login flow is a finite state machine. Five states, named events, named
;; guards and actions. All registered ids; nothing inline.

(def login-flow
  {:id      :auth.login/flow
   :initial :idle
   :context {:attempts 0 :error nil}
   :states
   {:idle
    {:on {:auth.login/submit {:target  :submitting
                              :actions [:auth.login/clear-error]}}}

    :submitting
    {:entry [:auth.login/issue-request]
     :on    {:auth.login/success {:target  :authed
                                  :actions [:auth.login/store-session]}
             :auth.login/failure [{:target  :error-shown
                                   :cond    :auth.login/under-retry-limit
                                   :actions [:auth.login/record-error]}
                                  {:target  :locked-out
                                   :actions [:auth.login/lock-account]}]}}

    :error-shown
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting}}}

    :authed
    {:meta {:terminal? true}}

    :locked-out
    {:meta {:terminal? true}}}})

;; Guards (registered, pure predicates).
(rf/reg-machine-guard :auth.login/under-retry-limit
  {:doc "True if the flow has had fewer than 3 prior failed attempts."}
  (fn guard-under-retry-limit [{:keys [context]} _event]
    (< (:attempts context) 3)))

;; Actions (registered; produce context updates and/or effects as data).
(rf/reg-machine-action :auth.login/clear-error
  {:doc "Reset error and prepare to submit."}
  (fn action-clear-error [{:keys [context]} _event]
    {:context (assoc context :error nil)}))

(rf/reg-machine-action :auth.login/issue-request
  {:doc "Issue the login HTTP request. Returns effects, not side-effects."}
  (fn action-issue-request [_snapshot [_ creds]]
    {:fx [[:http {:method     :post
                  :url        "/api/login"
                  :body       creds
                  :on-success [:auth.login/success]
                  :on-error   [:auth.login/failure]}]]}))

(rf/reg-machine-action :auth.login/record-error
  {:doc "Record the failure into context and bump attempt counter."}
  (fn action-record-error [{:keys [context]} [_ err]]
    {:context (-> context
                  (update :attempts inc)
                  (assoc :error (or (:message err) "Login failed.")))}))

(rf/reg-machine-action :auth.login/lock-account
  {:doc "Mark the account as locked after too many failed attempts."}
  (fn action-lock-account [_snapshot _event]
    {:fx [[:http {:method :post :url "/api/auth/lock"}]]}))

(rf/reg-machine-action :auth.login/store-session
  {:doc "Persist the session token returned by a successful login."}
  (fn action-store-session [_snapshot [_ {:keys [token]}]]
    {:fx [[:auth.session/store {:token token}]]}))

;; ============================================================================
;; EVENTS  (CP-1)
;; ============================================================================
;;
;; Three categories:
;;   1. :auth.login/initialise              — feature setup
;;   2. :auth.login/submit etc.             — machine-routed events
;;   3. :auth.login/success                 — the user-payload-bearing variant

(rf/reg-event-db :auth.login/initialise
  {:doc "Seed the auth.login slice."}
  (fn handler-auth-login-initialise [db _event]
    (assoc-in db [:auth :login]
              {:state   :idle
               :context {:attempts 0 :error nil}
               :user    nil})))

(rf/reg-event-fx :auth.login/event-handler
  {:doc          "All :auth.login/* events route through here, interpreted as a machine."
   :machine-path [:auth :login]}
  (rf/machine-handler [:auth :login] login-flow))

;; ============================================================================
;; SUBSCRIPTIONS  (CP-2)
;; ============================================================================

(rf/reg-sub :auth.login/state
  {:doc "Current state of the login flow."}
  (fn sub-auth-login-state [db _]
    (get-in db [:auth :login :state])))

(rf/reg-sub :auth.login/error
  {:doc "Current error message, if any."}
  (fn sub-auth-login-error [db _]
    (get-in db [:auth :login :context :error])))

(rf/reg-sub :auth.login/submitting?
  {:doc "Convenience: true when the flow is in :submitting."}
  :<- [:auth.login/state]
  (fn sub-auth-login-submitting? [state _]
    (= :submitting state)))

(rf/reg-sub :auth.login/authenticated?
  {:doc "Convenience: true when the flow has reached :authed."}
  :<- [:auth.login/state]
  (fn sub-auth-login-authenticated? [state _]
    (= :authed state)))

;; ============================================================================
;; VIEWS  (CP-4)
;; ============================================================================
;;
;; Var-reference style (canonical per [004 §How registered views are used in
;; hiccup]). Form-1 only. dispatch/subscribe inside a reg-view body are the
;; frame-bound locals injected by reg-view.

(def login-form
  (rf/reg-view :auth.login/form
    {:doc "The login form view: email + password + submit button + error display."}
    (fn render-auth-login-form []
      (let [submitting? @(subscribe [:auth.login/submitting?])
            err         @(subscribe [:auth.login/error])
            state       (atom {:email "" :password ""})]
        (fn []
          [:form.login-form
           {:on-submit (fn [e]
                         (.preventDefault e)
                         (dispatch [:auth.login/submit @state]))}
           [:input  {:type        "email"
                     :placeholder "Email"
                     :disabled    submitting?
                     :on-change   #(swap! state assoc :email (.. % -target -value))}]
           [:input  {:type        "password"
                     :placeholder "Password"
                     :disabled    submitting?
                     :on-change   #(swap! state assoc :password (.. % -target -value))}]
           [:button {:type "submit" :disabled submitting?}
            (if submitting? "Signing in…" "Sign in")]
           (when err [:p.error err])])))))

(def login-banner
  (rf/reg-view :auth.login/banner
    {:doc "Shows the user's logged-in state and a sign-out button."}
    (fn render-auth-login-banner []
      (let [authed? @(subscribe [:auth.login/authenticated?])]
        [:div.banner
         (if authed?
           [:span "Welcome!"]
           [login-form])]))))

(def root-view
  (rf/reg-view :auth.login/root
    (fn render-auth-login-root []
      [:div.app
       [:h1 "Sign in"]
       [login-banner]])))

;; ============================================================================
;; HEADLESS TEST  (smoke test for the feature)
;; ============================================================================
;;
;; Runs JVM-side. No browser, no React. Drives the machine via dispatch-sync
;; and asserts on app-db. Uses the id-valued override seam (:http →
;; :http.canned-success) so the test doesn't issue real network requests.

(defn login-feature-happy-path-test []
  (rf/with-frame [f (rf/make-frame {:on-create     [:auth.login/initialise]
                                    :fx-overrides  {:http :http.canned-success}})]
    ;; Initial state.
    (assert (= :idle (rf/compute-sub [:auth.login/state] @(rf/get-frame-db f))))

    ;; Submit credentials. Dispatches synchronously; drain settles before return.
    (rf/dispatch-sync [:auth.login/submit {:email "user@example.com" :password "correct-horse"}]
                      {:frame f})

    ;; After drain: machine has transitioned :idle → :submitting → :authed.
    (assert (= :authed (rf/compute-sub [:auth.login/state] @(rf/get-frame-db f))))
    (assert @(rf/compute-sub [:auth.login/authenticated?] @(rf/get-frame-db f)))))

(defn login-feature-retry-then-lockout-test []
  (rf/reg-fx :http.canned-failure
    {:platforms #{:client :server}}
    (fn [_m {:keys [on-error]}] (rf/dispatch (conj on-error {:message "bad creds"}))))

  (rf/with-frame [f (rf/make-frame {:on-create    [:auth.login/initialise]
                                    :fx-overrides {:http :http.canned-failure}})]
    ;; Three failures → locked-out.
    (dotimes [_ 3]
      (rf/dispatch-sync [:auth.login/submit {:email "x@y.z" :password "wrongpass"}]
                        {:frame f})
      (rf/dispatch-sync [:auth.login/dismiss] {:frame f}))

    (rf/dispatch-sync [:auth.login/submit {:email "x@y.z" :password "wrongpass"}]
                      {:frame f})
    (assert (= :locked-out (rf/compute-sub [:auth.login/state] @(rf/get-frame-db f))))))

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rdc/render root [root-view]))
