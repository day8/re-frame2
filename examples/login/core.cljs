(ns login.core
  "Worked end-to-end example: a login feature using the current re-frame2 API.

   Demonstrates:
   - Feature scaffold (CP-6)              — the :auth.login/* registry slice
   - Schema attachment (CP-8)              — Malli schema for the machine snapshot
   - Event handlers (CP-1)                 — pure (state, event) → effects
   - Subscriptions (CP-2)                  — pure derivations, including a :<- chain
   - Managed HTTP (Spec 014)                — :rf.http/managed plus a per-app
                                                demo stub that resolves the
                                                request locally so the example
                                                runs without a backend.
   - Registered view (CP-4)                — Var reference (canonical), Form-1 only
   - State machine (CP-5)                  — login flow as a transition table
                                              read via [:rf/machines :auth.login/flow]
   - Open-map idiom                        — every shape on the wire is an open map
   - Headless test                         — browserless smoke test (no DOM
                                               required; runs in any CLJS host
                                               that boots re-frame2. To run on
                                               the JVM, port the testable parts
                                               to .cljc.)

   In a real codebase, this single file would be split per CP-6 conventions:
     login/schema.cljc | events.cljs | subs.cljs | views.cljs |
     machines.cljs | events_test.cljs

   Kept as a single file here for brevity."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; Per rf2-p7va, the Spec 010 schema-attachment ns lives in
            ;; the day8/re-frame-2-schemas artefact. The require here
            ;; loads the ns so its late-bind hooks register before
            ;; `(rf/reg-app-schema ...)` runs below.
            [re-frame.schemas]
            ;; Per rf2-xbtj, the Spec 005 state-machine ns lives in the
            ;; day8/re-frame-2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/create-machine-handler
            ;; (called below at ns-load) and the `:rf/machine` framework
            ;; sub resolve.
            [re-frame.machines]
            [re-frame.substrate.reagent :as reagent-adapter])
  (:require-macros [re-frame.views-macros :refer [reg-view with-frame]]))

;; ============================================================================
;; SCHEMAS  (CP-8)
;; ============================================================================
;;
;; Open by default. The snapshot schema describes the shape of
;; [:rf/machines :auth.login/flow] in app-db; event schemas describe the shape
;; of dispatched event vectors. None carry :closed true — this isn't a system
;; boundary.

(def Credentials
  [:map
   [:email    [:re #".+@.+"]]
   [:password [:string {:min 8}]]])

;; The login flow's runtime state lives in the machine snapshot at
;; [:rf/machines :auth.login/flow] (per [005 §Where snapshots live]).
;; The snapshot shape is {:state <kw> :data <map>} per Spec 005.
(def AuthLoginSnapshot
  [:map
   [:state [:enum :idle :submitting :error-shown :authed :locked-out]]
   [:data  [:map
            [:attempts {:default 0} :int]
            [:error    [:maybe :string]]]]])

(def SubmitEvent
  [:tuple [:= :auth.login/submit] Credentials])

(rf/reg-app-schema [:rf/machines :auth.login/flow] AuthLoginSnapshot)

;; ============================================================================
;; FX  (Spec 014 + per-app demo stub)
;; ============================================================================
;;
;; HTTP requests go via the framework-shipped `:rf.http/managed` (Spec 014).
;; The example demo would normally hit `/api/login`, which we don't ship —
;; instead we register a per-app demo stub at `:rf.http/managed.login-demo`
;; and override `:rf.http/managed` to it on the default frame in `run`. The
;; stub inspects the request body's `:password` and synthesises either a
;; success or failure reply via the framework-shipped canned-success /
;; canned-failure fxs (Spec 014 §Testing) so the canonical reply shape is
;; preserved end-to-end.
;;
;; `:auth.session/store` is client-only — localStorage is a browser API.

(def good-password "correct-horse")

(rf/reg-fx :auth.session/store
  {:doc       "Persist the session token in localStorage. Client only."
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (.setItem ls "auth/token" token))))

(rf/reg-fx :rf.http/managed.login-demo
  {:doc       "Demo override for `:rf.http/managed`: routes by URL +
               request body to canned login responses so the example runs
               standalone without a backend.

               POST /api/login with `:password good-password` → success
                 with `{:user {...} :token \"demo-token-123\"}`.
               POST /api/login otherwise → 401 failure.
               Anything else (e.g. /api/auth/lock) → empty success.

               Delegates to the framework-shipped
               `:rf.http/managed-canned-success` /
               `:rf.http/managed-canned-failure` per Spec 014 §Testing,
               so the reply shape (`{:kind :success :value ...}` /
               `{:kind :failure :failure ...}`) lands at the inner
               `:auth.login/success` / `:auth.login/failure` sub-events
               via the explicit `:on-success` / `:on-failure` form."
   :platforms #{:server :client}}
  (fn fx-managed-login-demo [frame-ctx args-map]
    (let [{:keys [url body]} (:request args-map)
          login?    (= "/api/login" url)
          success?  (and login? (= good-password (:password body)))
          ok-stub   (registrar/handler :fx :rf.http/managed-canned-success)
          fail-stub (registrar/handler :fx :rf.http/managed-canned-failure)]
      ;; Simulate a small request latency so the :submitting state is
      ;; observable in the UI before the response lands.
      (js/setTimeout
        (fn []
          (cond
            success?
            (ok-stub frame-ctx (assoc args-map
                                      :value {:user  {:id    (random-uuid)
                                                      :email (:email body)}
                                              :token "demo-token-123"}))

            login?
            (fail-stub frame-ctx (assoc args-map
                                        :kind :rf.http/http-4xx
                                        :tags {:status 401
                                               :message "Invalid credentials."}))

            :else
            (ok-stub frame-ctx (assoc args-map :value {}))))
        50))))

;; ============================================================================
;; STATE MACHINE  (CP-5)
;; ============================================================================
;;
;; The login flow is a finite state machine. Five states, named events. All
;; non-trivial guards and actions live in the machine's :guards / :actions
;; maps and are referenced by keyword from the transition table; resolution
;; is machine-local (no global registry).

(rf/reg-event-fx :auth.login/flow
  {:doc "Login flow: idle → submitting → authed / error-shown / locked-out."}
  (rf/create-machine-handler
    ;; Per Spec 005 §Where snapshots live: the spec map does NOT carry
    ;; :id; the machine's id is the surrounding reg-event-fx id.
    {:initial :idle
     :data    {:attempts 0 :error nil}

     :guards
     {:under-retry-limit
      ;; True if the flow has had fewer than 3 prior failed attempts.
      (fn [data _event]
        (< (:attempts data) 3))}

     :actions
     {:clear-error
      ;; Reset error and prepare to submit.
      (fn [_data _event]
        {:data {:error nil}})

      :issue-request
      ;; Issue the login HTTP request. Returns effects, not side-effects.
      ;; Spec 014 reply: explicit :on-success / :on-failure events have the
      ;; reply payload (`{:kind :success :value ...}` / `{:kind :failure
      ;; :failure ...}`) appended as their last arg by the runtime.
      (fn [_data [_ creds]]
        {:fx [[:rf.http/managed
               {:request    {:method :post
                             :url    "/api/login"
                             :body   creds
                             :request-content-type :json}
                :decode     :json
                :on-success [:auth.login/flow [:auth.login/success]]
                :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

      :record-error
      ;; Record the failure into :data and bump the attempt counter.
      (fn [data [_ {:keys [failure]}]]
        {:data (-> data
                   (update :attempts inc)
                   (assoc :error (or (:message failure) "Login failed.")))})

      :lock-account
      ;; Mark the account as locked after too many failed attempts.
      (fn [_data _event]
        {:fx [[:rf.http/managed
               {:request {:method :post :url "/api/auth/lock"}}]]})

      :store-session
      ;; Persist the session token returned by a successful login.
      (fn [_data [_ {:keys [value]}]]
        {:fx [[:auth.session/store {:token (:token value)}]]})}

     :states
     {:idle
      {:on {:auth.login/submit {:target :submitting
                                :action :clear-error}}}

      :submitting
      {:entry :issue-request
       :on    {:auth.login/success {:target :authed
                                    :action :store-session}
               :auth.login/failure [{:target :error-shown
                                     :guard  :under-retry-limit
                                     :action :record-error}
                                    {:target :locked-out
                                     :action :lock-account}]}}

      :error-shown
      {:on {:auth.login/dismiss {:target :idle}
            :auth.login/submit  {:target :submitting}}}

      :authed
      {:meta {:terminal? true}}

      :locked-out
      {:meta {:terminal? true}}}}))

;; ============================================================================
;; EVENTS  (CP-1)
;; ============================================================================
;;
;; The machine handler (registered above as :auth.login/flow via reg-event-fx
;; + create-machine-handler) is self-initialising: its `:initial` state and
;; `:data` seed [:rf/machines :auth.login/flow] when the machine first runs.
;; No separate :initialise event is required (per [005 §Restore semantics]).
;;
;; Sub-events route in via:
;;   (rf/dispatch [:auth.login/flow [:auth.login/submit creds]])

;; ============================================================================
;; SUBSCRIPTIONS  (CP-2)
;; ============================================================================

;; The machine snapshot lives at [:rf/machines :auth.login/flow] (per
;; Spec 005). These named subs project out the convenient pieces.

(rf/reg-sub :auth.login/state
  {:doc "Current state of the login flow."}
  (fn sub-auth-login-state [db _]
    (get-in db [:rf/machines :auth.login/flow :state])))

(rf/reg-sub :auth.login/error
  {:doc "Current error message, if any."}
  (fn sub-auth-login-error [db _]
    (get-in db [:rf/machines :auth.login/flow :data :error])))

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
;; hiccup]). The current API uses `rf/dispatcher` / `rf/subscriber` for
;; frame-bound access inside views.

;; Form-2 view: the outer fn captures local component state in `state`
;; (a Reagent atom kept across renders); the inner fn is the actual
;; render fn. dispatch / subscribe are auto-injected and visible in
;; both the outer and inner fn bodies.
(reg-view ^{:doc "The login form view: email + password + submit button + error display."}
          login-form []
  (let [state (atom {:email "" :password ""})]
    (fn []
      (let [submitting? @(subscribe [:auth.login/submitting?])
            err         @(subscribe [:auth.login/error])]
        [:form.login-form
         {:on-submit (fn [e]
                       (.preventDefault e)
                       (dispatch [:auth.login/flow [:auth.login/submit @state]]))}
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
         (when err [:p.error err])]))))

(reg-view ^{:doc "Shows the user's logged-in state and a sign-out button."}
          login-banner []
  (let [authed? @(subscribe [:auth.login/authenticated?])]
    [:div.banner
     (if authed?
       [:span "Welcome!"]
       [login-form])]))

(reg-view root-view []
  [:div.app
   [:h1 "Sign in"]
   [login-banner]])

;; ============================================================================
;; HEADLESS TEST  (smoke test for the feature)
;; ============================================================================
;;
;; Browserless. No DOM, no React. Drives the machine via dispatch-sync and
;; asserts on app-db. Uses the id-valued override seam (`:rf.http/managed`
;; → per-test stub) so the test doesn't issue real network requests. The
;; per-test stubs delegate to the framework-shipped
;; `:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`
;; fxs (Spec 014 §Testing), so the canonical reply shape (`{:kind :success
;; :value ...}` / `{:kind :failure :failure ...}`) is preserved.
;;
;; Because this file is .cljs, the test runs in a CLJS host (Node,
;; browser without a DOM, shadow-cljs node-test target); to run it on
;; the JVM, lift the events / subs / machine into a .cljc namespace.

(rf/reg-fx :auth.login/test-canned-success
  {:doc "Test stub: every :rf.http/managed call resolves :success with a
         canned user/token payload."
   :platforms #{:client :server}}
  (fn [frame-ctx args-map]
    (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx
            (assoc args-map :value {:user  {:id    (random-uuid)
                                            :email "test@example.com"}
                                    :token "test-token-123"})))))

(rf/reg-fx :auth.login/test-canned-failure
  {:doc "Test stub: every :rf.http/managed call resolves :failure with a
         401 reply."
   :platforms #{:client :server}}
  (fn [frame-ctx args-map]
    (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
      (stub frame-ctx (assoc args-map
                             :kind :rf.http/http-4xx
                             :tags {:status 401 :message "bad creds"})))))

(defn login-feature-happy-path-test []
  ;; The machine self-initialises on first dispatch — no :on-create needed.
  (with-frame [f (rf/make-frame
                   {:fx-overrides {:rf.http/managed :auth.login/test-canned-success}})]
    ;; Submit credentials. Dispatches synchronously; drain settles before return.
    ;; Sub-events route via the machine id (per [005 §Registration]).
    (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                         {:email "user@example.com"
                                          :password "correct-horse"}]]
                      {:frame f})

    ;; After drain: machine has transitioned :idle → :submitting → :authed.
    (assert (= :authed (rf/compute-sub [:auth.login/state] (rf/get-frame-db f))))
    (assert (rf/compute-sub [:auth.login/authenticated?] (rf/get-frame-db f)))))

(defn login-feature-retry-then-lockout-test []
  (with-frame [f (rf/make-frame
                   {:fx-overrides {:rf.http/managed :auth.login/test-canned-failure}})]
    ;; Three failures → locked-out.
    (dotimes [_ 3]
      (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                           {:email "x@y.z" :password "wrongpass"}]]
                        {:frame f})
      (rf/dispatch-sync [:auth.login/flow [:auth.login/dismiss]] {:frame f}))

    (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                         {:email "x@y.z" :password "wrongpass"}]]
                      {:frame f})
    (assert (= :locked-out (rf/compute-sub [:auth.login/state] (rf/get-frame-db f))))))

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================

;; React root named `react-root` (not `root`) so it does NOT collide
;; with the `root-view` reg-view above (rf2-562e).
(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  ;; Install the demo override so `:rf.http/managed` calls route to the
  ;; in-process login stub above. The example runs standalone — no
  ;; backend required.
  (rf/reg-frame :rf/default
    {:doc          "Login demo frame."
     :fx-overrides {:rf.http/managed :rf.http/managed.login-demo}})
  (rdc/render react-root [root-view]))
