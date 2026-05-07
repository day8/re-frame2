(ns example.realworld.auth
  "Authentication for the RealWorld (Conduit) example.

   The auth flow is implemented as a re-frame2 state machine, demonstrating
   how a multi-step, async-flavoured user interaction collapses into a
   single handler with explicit state. The same machine handles login,
   register, session restore, and logout — each of these is just a named
   transition into / out of the machine.

   Per Spec 005 (state machines as event handlers), the machine is
   registered via `(reg-event-fx :auth/flow (create-machine-handler ...))`.
   Sub-events route in via `(rf/dispatch [:auth/flow [:auth/login creds]])`.

   The :guards / :actions maps are *machine-scoped* per the recent locked
   semantics — guard / action keywords resolve inside this machine's maps
   only. There is no global registry fallback: if a keyword isn't in this
   map, it's an error (not a silent global lookup). This keeps each
   machine's transition table self-contained.

   States:
     :idle        — no auth in flight; user may or may not be logged-in.
     :restoring   — checking localStorage / cookie for a saved token.
     :submitting  — login/register HTTP request in flight.
     :authed      — user is logged in. (terminal — observable; reset via :logout.)
     :error       — last attempt failed; error visible. (terminal — re-enter via :submit.)

   Pattern-Forms convention: the per-form draft, errors, touched, etc. all
   live in [:auth :login-form] and [:auth :register-form]. Form events
   (:auth.login-form/edit-field, :auth.register-form/submit) are separate
   from the auth flow itself; submit dispatches into the flow."
  (:require [re-frame.core :as rf]
            [example.realworld.schema]
            [example.realworld.http]
            [example.realworld.routing :as routing]))

;; ============================================================================
;; FX  (session storage; client-only)
;; ============================================================================

(rf/reg-fx :auth.session/store
  {:doc       "Persist the JWT in localStorage."
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    (.setItem js/localStorage "conduit/jwt" token)))

(rf/reg-fx :auth.session/clear
  {:doc       "Clear the JWT from localStorage."
   :platforms #{:client}}
  (fn fx-auth-session-clear [_m _]
    (.removeItem js/localStorage "conduit/jwt")))

(rf/reg-cofx :auth.session/token
  {:doc "Inject the saved token (or nil) from localStorage into cofx."}
  (fn cofx-auth-session-token [coeffects _]
    (assoc coeffects :auth.session/token
           (.getItem js/localStorage "conduit/jwt"))))

;; ============================================================================
;; AUTH STATE MACHINE  (CP-5 / Spec 005)
;; ============================================================================

(rf/reg-event-fx :auth/flow
  {:doc "The auth flow: idle → submitting → authed | error,
         plus :restoring on app boot to rehydrate from localStorage."}
  (rf/create-machine-handler
    {:initial :idle
     :data    {:error nil}

     ;; ------------------------------------------------------------------
     ;; GUARDS — machine-scoped; resolve from this map by keyword.
     ;; ------------------------------------------------------------------
     :guards
     {:has-token?
      ;; The cofx-derived token is non-nil. Used by :restore-session to
      ;; decide whether there's anything to restore at all.
      (fn has-token? [_snapshot [_event-id _ token]]
        (not (clojure.string/blank? token)))}

     ;; ------------------------------------------------------------------
     ;; ACTIONS — machine-scoped; resolve from this map by keyword.
     ;; ------------------------------------------------------------------
     :actions
     {:clear-error
      (fn clear-error [_ _event]
        {:data {:error nil}})

      :issue-login-request
      (fn issue-login-request [_snapshot [_ {:keys [email password]}]]
        {:fx [[:http {:method     :post
                      :url        "/users/login"
                      :auth?      false
                      :body       {:user {:email email :password password}}
                      :on-success [:auth/flow [:auth/success]]
                      :on-error   [:auth/flow [:auth/failure]]}]]})

      :issue-register-request
      (fn issue-register-request [_snapshot [_ {:keys [username email password]}]]
        {:fx [[:http {:method     :post
                      :url        "/users"
                      :auth?      false
                      :body       {:user {:username username :email email :password password}}
                      :on-success [:auth/flow [:auth/success]]
                      :on-error   [:auth/flow [:auth/failure]]}]]})

      :issue-restore-request
      (fn issue-restore-request [_snapshot _event]
        ;; The token is already on app-db (the :restore action ran first);
        ;; the :http fx will inject it as the Bearer header.
        {:fx [[:http {:method     :get
                      :url        "/user"
                      :on-success [:auth/flow [:auth/success]]
                      :on-error   [:auth/flow [:auth/restore-failed]]}]]})

      :seed-token
      ;; Write the saved token into app-db so :http can read it for the
      ;; restore call. Triggered by the :auth/restore event before
      ;; transitioning to :restoring.
      (fn seed-token [_snapshot [_ token]]
        {:db-update (fn [db] (assoc-in db [:auth :token] token))})

      :store-session
      ;; Successful login/register: persist the user payload, JWT, and
      ;; flip the auth slice. Then redirect to the home page.
      (fn store-session [_snapshot [_ resp]]
        (let [user (:user resp)]
          {:db-update (fn [db]
                        (-> db
                            (assoc-in [:auth :user]  user)
                            (assoc-in [:auth :token] (:token user))))
           :fx [[:auth.session/store {:token (:token user)}]
                [:dispatch [:route/navigate :route/home]]]}))

      :record-error
      (fn record-error [_snapshot [_ err]]
        {:data {:error (or (-> err :errors first) "Authentication failed.")}})

      :clear-session
      (fn clear-session [_snapshot _event]
        {:db-update (fn [db]
                      (-> db
                          (assoc-in [:auth :user]  nil)
                          (assoc-in [:auth :token] nil)))
         :fx [[:auth.session/clear nil]
              [:dispatch [:route/navigate :route/home]]]})}

     ;; ------------------------------------------------------------------
     ;; STATES — the transition table.
     ;; ------------------------------------------------------------------
     :states
     {:idle
      {:on {:auth/login    {:target :submitting :action [:clear-error :issue-login-request]}
            :auth/register {:target :submitting :action [:clear-error :issue-register-request]}
            :auth/restore  [{:target :restoring
                             :guard  :has-token?
                             :action [:seed-token :issue-restore-request]}
                            ;; No token → stay :idle; nothing to restore.
                            {:target :idle}]}}

      :submitting
      {:on {:auth/success {:target :authed :action :store-session}
            :auth/failure {:target :error  :action :record-error}}}

      :restoring
      {:on {:auth/success         {:target :authed :action :store-session}
            :auth/restore-failed  {:target :idle   :action :clear-session}}}

      :authed
      {:on {:auth/logout   {:target :idle :action :clear-session}
            ;; Allow re-login (e.g. for "switch user").
            :auth/login    {:target :submitting :action [:clear-error :issue-login-request]}}}

      :error
      {:on {:auth/login    {:target :submitting :action [:clear-error :issue-login-request]}
            :auth/register {:target :submitting :action [:clear-error :issue-register-request]}
            :auth/dismiss  {:target :idle       :action :clear-error}}}}}))

;; ============================================================================
;; INITIALISATION + SESSION RESTORE
;; ============================================================================

(rf/reg-event-fx :auth/initialise
  {:doc      "Seed the auth slice and kick off session restore."
   :cofx     [:auth.session/token]}
  (fn handler-auth-initialise [{:keys [db auth.session/token]} _]
    {:db (assoc db :auth {:user  nil
                          :token nil
                          :flow  {:state :idle :context {:error nil}}})
     :fx [[:dispatch [:auth/flow [:auth/restore token]]]]}))

;; ============================================================================
;; FORMS  (login + register, per Pattern-Forms)
;; ============================================================================

(def login-form-defaults    {:email "" :password ""})
(def register-form-defaults {:username "" :email "" :password ""})

(rf/reg-event-db :auth.login-form/initialise
  (fn [db _]
    (assoc-in db [:auth :login-form]
              {:draft        login-form-defaults
               :submitted    nil
               :status       :idle
               :errors       {}
               :touched      #{}
               :submit-error nil})))

(rf/reg-event-db :auth.login-form/edit-field
  {:spec [:cat [:= :auth.login-form/edit-field] :keyword :string]}
  (fn [db [_ field value]]
    (-> db
        (assoc-in  [:auth :login-form :draft field] value)
        (update-in [:auth :login-form :touched] (fnil conj #{}) field))))

(rf/reg-event-fx :auth.login-form/submit
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:auth :login-form :draft])]
      {:db (assoc-in db [:auth :login-form :status] :submitting)
       :fx [[:dispatch [:auth/flow [:auth/login draft]]]]})))

(rf/reg-event-db :auth.register-form/initialise
  (fn [db _]
    (assoc-in db [:auth :register-form]
              {:draft        register-form-defaults
               :submitted    nil
               :status       :idle
               :errors       {}
               :touched      #{}
               :submit-error nil})))

(rf/reg-event-db :auth.register-form/edit-field
  (fn [db [_ field value]]
    (-> db
        (assoc-in  [:auth :register-form :draft field] value)
        (update-in [:auth :register-form :touched] (fnil conj #{}) field))))

(rf/reg-event-fx :auth.register-form/submit
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:auth :register-form :draft])]
      {:db (assoc-in db [:auth :register-form :status] :submitting)
       :fx [[:dispatch [:auth/flow [:auth/register draft]]]]})))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :auth/user      (fn [db _] (get-in db [:auth :user])))
(rf/reg-sub :auth/token     (fn [db _] (get-in db [:auth :token])))

(rf/reg-sub :auth/flow-state
  {:doc "Current state of the auth machine. Read via the spec's machine sub."}
  (fn [_ _]
    ;; Snapshot at [:rf/machines :auth/flow] per the locked semantics; the
    ;; framework's `sub-machine` returns the {:state :data} pair.
    (rf/sub-machine :auth/flow)))

(rf/reg-sub :auth/state
  :<- [:auth/flow-state]
  (fn [{:keys [state]} _] state))

(rf/reg-sub :auth/error
  :<- [:auth/flow-state]
  (fn [{:keys [data]} _] (:error data)))

(rf/reg-sub :auth/authenticated?
  :<- [:auth/state]
  (fn [s _] (= s :authed)))

(rf/reg-sub :auth/submitting?
  :<- [:auth/state]
  (fn [s _] (= s :submitting)))

(rf/reg-sub :auth.login-form/draft  (fn [db _] (get-in db [:auth :login-form :draft])))
(rf/reg-sub :auth.register-form/draft
  (fn [db _] (get-in db [:auth :register-form :draft])))

;; ============================================================================
;; VIEWS
;; ============================================================================

(def login-page
  (rf/reg-view :pages/login
    {:doc "Login page."}
    (fn render-login []
      (let [draft        @(subscribe [:auth.login-form/draft])
            submitting?  @(subscribe [:auth/submitting?])
            err          @(subscribe [:auth/error])]
        [:div.auth-page
         [:h1 "Sign in"]
         [routing/route-link {:to :route/register} "Need an account?"]
         (when err [:ul.error-messages [:li err]])
         [:form
          {:on-submit (fn [e]
                        (.preventDefault e)
                        (dispatch [:auth.login-form/submit]))}
          [:fieldset
           [:fieldset.form-group
            [:input {:type        "email"
                     :placeholder "Email"
                     :value       (:email draft)
                     :disabled    submitting?
                     :on-change   #(dispatch [:auth.login-form/edit-field :email (.. % -target -value)])}]]
           [:fieldset.form-group
            [:input {:type        "password"
                     :placeholder "Password"
                     :value       (:password draft)
                     :disabled    submitting?
                     :on-change   #(dispatch [:auth.login-form/edit-field :password (.. % -target -value)])}]]
           [:button {:type "submit" :disabled submitting?}
            (if submitting? "Signing in…" "Sign in")]]]]))))

(def register-page
  (rf/reg-view :pages/register
    {:doc "Register page."}
    (fn render-register []
      (let [draft        @(subscribe [:auth.register-form/draft])
            submitting?  @(subscribe [:auth/submitting?])
            err          @(subscribe [:auth/error])]
        [:div.auth-page
         [:h1 "Sign up"]
         [routing/route-link {:to :route/login} "Have an account?"]
         (when err [:ul.error-messages [:li err]])
         [:form
          {:on-submit (fn [e]
                        (.preventDefault e)
                        (dispatch [:auth.register-form/submit]))}
          [:fieldset
           [:fieldset.form-group
            [:input {:type        "text"
                     :placeholder "Username"
                     :value       (:username draft)
                     :disabled    submitting?
                     :on-change   #(dispatch [:auth.register-form/edit-field :username (.. % -target -value)])}]]
           [:fieldset.form-group
            [:input {:type        "email"
                     :placeholder "Email"
                     :value       (:email draft)
                     :disabled    submitting?
                     :on-change   #(dispatch [:auth.register-form/edit-field :email (.. % -target -value)])}]]
           [:fieldset.form-group
            [:input {:type        "password"
                     :placeholder "Password"
                     :value       (:password draft)
                     :disabled    submitting?
                     :on-change   #(dispatch [:auth.register-form/edit-field :password (.. % -target -value)])}]]
           [:button {:type "submit" :disabled submitting?}
            (if submitting? "Signing up…" "Sign up")]]]]))))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn login-happy-path-test []
  (rf/reg-fx :http.canned-login-success
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-success]}]
      (when on-success
        (rf/dispatch (conj on-success {:user {:email    "alice@example.com"
                                              :username "alice"
                                              :token    "jwt-abc"
                                              :bio      nil
                                              :image    nil}})
                     {:frame frame}))))

  (rf/with-frame [f (rf/make-frame {:on-create    [:auth/initialise]
                                    :fx-overrides {:http :http.canned-login-success
                                                   :auth.session/store :rf/no-op
                                                   :auth.session/clear :rf/no-op}})]
    ;; Start :idle (no token in localStorage stub).
    (assert (= :idle (rf/compute-sub [:auth/state] @(rf/get-frame-db f))))

    ;; Submit credentials -> machine cycles :idle -> :submitting -> :authed.
    (rf/dispatch-sync [:auth/flow [:auth/login {:email "alice@example.com"
                                                :password "correct-horse"}]]
                      {:frame f})
    (assert (= :authed (rf/compute-sub [:auth/state] @(rf/get-frame-db f))))
    (assert (= "alice" (:username (rf/compute-sub [:auth/user] @(rf/get-frame-db f)))))

    ;; Logout -> :idle, user/token nil.
    (rf/dispatch-sync [:auth/flow [:auth/logout]] {:frame f})
    (assert (= :idle (rf/compute-sub [:auth/state] @(rf/get-frame-db f))))
    (assert (nil? (rf/compute-sub [:auth/user] @(rf/get-frame-db f))))))

(defn login-failure-test []
  (rf/reg-fx :http.canned-failure
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-error]}]
      (when on-error
        (rf/dispatch (conj on-error {:errors ["email or password is invalid"]})
                     {:frame frame}))))

  (rf/with-frame [f (rf/make-frame {:on-create    [:auth/initialise]
                                    :fx-overrides {:http :http.canned-failure}})]
    (rf/dispatch-sync [:auth/flow [:auth/login {:email "x@y.z" :password "wrong"}]]
                      {:frame f})
    (assert (= :error (rf/compute-sub [:auth/state] @(rf/get-frame-db f))))
    (assert (some? (rf/compute-sub [:auth/error] @(rf/get-frame-db f))))

    ;; :dismiss -> :idle.
    (rf/dispatch-sync [:auth/flow [:auth/dismiss]] {:frame f})
    (assert (= :idle (rf/compute-sub [:auth/state] @(rf/get-frame-db f))))))
