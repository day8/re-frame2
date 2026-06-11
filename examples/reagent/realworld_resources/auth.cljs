(ns realworld-resources.auth
  "Authentication for the RealWorld-on-resources example.

   Auth is a COMMAND + state machine, not a cached read (Spec 016 §Scope: do
   not contort login into a read-resource) — the bead's recommended shape. The
   POST/GET for login / register / session-restore go via `:rf.http/managed`
   from the machine's actions; the machine owns the `:idle → :submitting →
   :authed | :error` lifecycle. (Settings UPDATE is the one auth-adjacent WRITE
   that IS a mutation — it invalidates the profile read — and lives in
   realworld-resources.mutations.)

   On LOGOUT the machine clears the session AND clears the session-scoped
   resource cache: the user's personalised feed is cached under `[:rf.scope/
   session {:username …}]`, and a logged-out (or next) user must never see it.
   `:rf.resource/clear-scope` is the causal operation for exactly that (Spec
   016 §clear-scope is causal). The public `:rf.scope/global` reads (article
   lists, detail, profiles, tags) are unaffected — they are the same for
   everyone, so logout leaves them alone."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; The Spec 005 state-machine ns lives in day8/re-frame2-machines.
            ;; Loading the ns registers its late-bind hooks so rf/reg-machine
            ;; (below) and the `:rf/machine` framework subs resolve.
            [re-frame.machines]
            [re-frame.resources]
            [realworld-resources.http :as rh]
            [realworld-resources.schema :as schema]
            [realworld-resources.scope :as scope])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; SESSION PERSISTENCE SEAM
;; ============================================================================

(rf/reg-fx :realworld-resources.session/persist
  {:doc       "Persist (or clear) the JWT in localStorage. `{:token t}` writes
               when truthy; nil removes the key."
   :platforms #{:client}}
  (fn [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (if token
        (.setItem ls "conduit-resources/jwt" token)
        (.removeItem ls "conduit-resources/jwt")))))

(rf/reg-cofx :realworld-resources.session/token
  {:doc "Inject the saved token (or nil) from localStorage into coeffects."}
  (fn [ctx _]
    (rf/assoc-coeffect ctx :realworld-resources.session/token
                       (some-> (.-localStorage js/globalThis)
                               (.getItem "conduit-resources/jwt")))))

;; ============================================================================
;; SESSION SUPPORT EVENTS
;; ============================================================================

(rf/reg-event-db :auth/store-session
  (fn [db [_ user]]
    (-> db
        (assoc-in [:auth :user] user)
        (assoc-in [:auth :token] (:token user)))))

(rf/reg-event-fx :auth/clear-session
  {:doc "Clear the auth slice AND drop the session-scoped resource cache. The
         personalised feed is cached under the session scope (Spec 016
         §Scope); logout MUST clear it (`:rf.resource/clear-scope`) so the next
         user never reads it. Public `:rf.scope/global` reads are untouched."}
  (fn [{:keys [db]} _]
    (let [old-scope (scope/session-scope (get-in db [:auth :user]))]
      {:db (-> db
               (assoc-in [:auth :user] nil)
               (assoc-in [:auth :token] nil))
       :fx (cond-> []
             old-scope (conj [:dispatch [:rf.resource/clear-scope
                                         {:scope old-scope :cause :logout}]]))})))

(rf/reg-event-fx :auth/post-login-redirect
  {:doc "Bounce the user who INTERACTIVELY logged in / registered to the route
         the auth guard intercepted (`[:auth :return-to]`), or home. Dispatched
         ONLY by the `:store-session` action (the interactive `:submitting →
         :authed` transition) — NOT by `:restore-session` (cold-boot
         session-restore preserves the restored route, never force-navigates).
         Machine actions can't navigate or read `:db` directly (Spec 005), so
         this is an ordinary event. Reads AND clears the slot so a later login
         can't re-bounce."}
  (fn [{:keys [db]} _]
    (let [return-to (get-in db [:auth :return-to])]
      {:db (update db :auth dissoc :return-to)
       :fx [[:dispatch (if return-to
                         [:rf.route/navigate (:id return-to) (:params return-to)]
                         [:rf.route/navigate :realworld/home])]]})))

;; ============================================================================
;; AUTH STATE MACHINE — :auth/flow
;; ============================================================================

(rf/reg-machine :auth/flow
  {:doc "The auth flow: idle → submitting/restoring → authed | error. HTTP via
         :rf.http/managed (Spec 014). Login / register / restore do NOT retry —
         one submission per click."
   :rf.http/decode-schemas [schema/UserResponse]}
  {:initial :idle
   :data    {:error nil}
   :data-schema schema/AuthFlowData
   :guards
   {:has-token? (fn [{[_ token] :event}] (not (str/blank? token)))}
   :actions
   {:clear-error (fn [_] {:data {:error nil}})

    :begin-login
    (fn [{[_ {:keys [email password]}] :event}]
      {:data {:error nil}
       :fx [[:rf.http/managed
             {:request {:method :post :url (rh/full-url "/users/login")
                        :body {:user {:email email :password password}}}
              :decode schema/UserResponse
              :on-success [:auth/flow [:auth/success]]
              :on-failure [:auth/flow [:auth/failure]]}]]})

    :begin-register
    (fn [{[_ {:keys [username email password]}] :event}]
      {:data {:error nil}
       :fx [[:rf.http/managed
             {:request {:method :post :url (rh/full-url "/users")
                        :body {:user {:username username :email email :password password}}}
              :decode schema/UserResponse
              :on-success [:auth/flow [:auth/success]]
              :on-failure [:auth/flow [:auth/failure]]}]]})

    :begin-restore
    (fn [_]
      {:data {:error nil}
       :fx [[:rf.http/managed
             {:request {:method :get :url (rh/full-url "/user")}
              :decode schema/UserResponse
              :on-success [:auth/flow [:auth/success]]
              :on-failure [:auth/flow [:auth/restore-failed]]}]]})

    :store-session
    (fn [{[_ {:keys [value]}] :event}]
      ;; INTERACTIVE login / register success: store the session, persist the
      ;; JWT, AND bounce to the guard-stashed `:return-to` (or home). The
      ;; bounce is the right behaviour ONLY for an interactive submit — the
      ;; user just clicked Sign-in from the login page and expects to land
      ;; somewhere. Machine actions see no `:db` and emit no `:db` (Spec 005),
      ;; so the session store + bounce-back run as ordinary events.
      (let [user (:user value)]
        {:data {:error nil}
         :fx [[:dispatch [:auth/store-session user]]
              [:realworld-resources.session/persist {:token (:token user)}]
              [:dispatch [:auth/post-login-redirect]]]}))

    :restore-session
    (fn [{[_ {:keys [value]}] :event}]
      ;; SESSION-RESTORE success (cold boot with a saved JWT): store the
      ;; session WITHOUT navigating. A logged-in user cold-booting on a deep
      ;; link (`/article/x`) must stay on that route — restore only re-hydrates
      ;; the session; it must NOT force-navigate home (the bug this action
      ;; fixes). Only an INTERACTIVE login/register bounces (`:store-session`).
      ;; The token is already in app-db + localStorage from `:auth/initialise`;
      ;; we re-persist defensively in case the server rotated it on `GET /user`.
      (let [user (:user value)]
        {:data {:error nil}
         :fx [[:dispatch [:auth/store-session user]]
              [:realworld-resources.session/persist {:token (:token user)}]]}))

    :record-error
    (fn [{[_ {:keys [failure]}] :event}]
      {:data {:error (rh/failure->message failure)}})

    :clear-session
    (fn [_]
      {:data {:error nil}
       :fx [[:dispatch [:auth/clear-session]]
            [:realworld-resources.session/persist {:token nil}]
            [:dispatch [:rf.route/navigate :realworld/home]]]})}
   :states
   {:idle
    {:on {:auth/login    {:target :submitting :action :begin-login}
          :auth/register {:target :submitting :action :begin-register}
          :auth/restore  [{:target :restoring :guard :has-token? :action :begin-restore}
                          {:target :idle}]}}
    :submitting
    {:on {:auth/success {:target :authed :action :store-session}
          :auth/failure {:target :error  :action :record-error}}}
    :restoring
    {:on {:auth/success        {:target :authed :action :restore-session}
          :auth/restore-failed {:target :idle   :action :clear-session}}}
    :authed
    {:on {:auth/logout {:target :idle :action :clear-session}
          :auth/login  {:target :submitting :action :begin-login}}}
    :error
    {:on {:auth/login    {:target :submitting :action :begin-login}
          :auth/register {:target :submitting :action :begin-register}
          :auth/dismiss  {:target :idle       :action :clear-error}}}}})

;; ============================================================================
;; INITIALISATION + SESSION RESTORE
;; ============================================================================

(rf/reg-event-fx :auth/initialise
  [(rf/inject-cofx :realworld-resources.session/token)]
  (fn [{:keys [db realworld-resources.session/token]} _]
    ;; Dispatch `:auth/restore` UNCONDITIONALLY (even with a nil token) so the
    ;; machine snapshot spawns at `:idle` from cold boot; the `:has-token?`
    ;; guard then routes a blank token to the no-op branch.
    {:db (assoc db :auth {:user nil :token token})
     :fx [[:dispatch [:auth/flow [:auth/restore token]]]]}))

;; ============================================================================
;; LOGIN / REGISTER FORM DRAFTS  (app-db; submission is the machine)
;; ============================================================================

(def login-form-defaults    {:email "" :password ""})
(def register-form-defaults  {:username "" :email "" :password ""})

(rf/reg-event-db :auth.login-form/initialise
  (fn [db _] (assoc-in db [:auth :login-form] {:draft login-form-defaults :touched #{}})))

(rf/reg-event-db :auth.login-form/edit-field
  {:schema [:cat [:= :auth.login-form/edit-field] :keyword :string]}
  (fn [db [_ field value]]
    (-> db
        (assoc-in [:auth :login-form :draft field] value)
        (update-in [:auth :login-form :touched] (fnil conj #{}) field))))

(rf/reg-event-fx :auth.login-form/submit
  (fn [{:keys [db]} _]
    {:fx [[:dispatch [:auth/flow [:auth/login (get-in db [:auth :login-form :draft])]]]]}))

(rf/reg-event-db :auth.register-form/initialise
  (fn [db _] (assoc-in db [:auth :register-form] {:draft register-form-defaults :touched #{}})))

(rf/reg-event-db :auth.register-form/edit-field
  (fn [db [_ field value]]
    (-> db
        (assoc-in [:auth :register-form :draft field] value)
        (update-in [:auth :register-form :touched] (fnil conj #{}) field))))

(rf/reg-event-fx :auth.register-form/submit
  (fn [{:keys [db]} _]
    {:fx [[:dispatch [:auth/flow [:auth/register (get-in db [:auth :register-form :draft])]]]]}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :auth/user  (fn [db _] (get-in db [:auth :user])))
(rf/reg-sub :auth/token (fn [db _] (get-in db [:auth :token])))

(rf/reg-sub :auth/flow-state
  :<- [:rf/machine :auth/flow]
  (fn [snapshot _] snapshot))

(rf/reg-sub :auth/state
  :<- [:auth/flow-state]
  (fn [snapshot _] (:state snapshot)))

(rf/reg-sub :auth/error
  :<- [:auth/flow-state]
  (fn [snapshot _] (get-in snapshot [:data :error])))

(rf/reg-sub :auth/authenticated?
  :<- [:auth/state]
  (fn [state _] (= state :authed)))

(rf/reg-sub :auth/submitting?
  :<- [:auth/state]
  (fn [state _] (or (= state :submitting) (= state :restoring))))

(rf/reg-sub :auth.login-form/draft    (fn [db _] (get-in db [:auth :login-form :draft])))
(rf/reg-sub :auth.register-form/draft (fn [db _] (get-in db [:auth :register-form :draft])))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "Login page."} login-page []
  (let [draft       @(subscribe [:auth.login-form/draft])
        submitting? @(subscribe [:auth/submitting?])
        err         @(subscribe [:auth/error])]
    [:div.auth-page {:data-testid "login-page"}
     [:h1 "Sign in"]
     [rf/route-link {:to :realworld.auth/register} "Need an account?"]
     (when err [:ul.error-messages [:li err]])
     [:form
      {:data-testid "login-form"
       :on-submit (fn [e] (.preventDefault e) (dispatch [:auth.login-form/submit]))}
      [:fieldset
       [:fieldset.form-group
        [:input {:type "email" :data-testid "login-email" :placeholder "Email"
                 :value (:email draft) :disabled submitting?
                 :on-change #(dispatch [:auth.login-form/edit-field :email (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type "password" :data-testid "login-password" :placeholder "Password"
                 :value (:password draft) :disabled submitting?
                 :on-change #(dispatch [:auth.login-form/edit-field :password (.. % -target -value)])}]]
       [:button {:type "submit" :data-testid "login-submit" :disabled submitting?}
        (if submitting? "Signing in…" "Sign in")]]]]))

(reg-view ^{:doc "Register page."} register-page []
  (let [draft       @(subscribe [:auth.register-form/draft])
        submitting? @(subscribe [:auth/submitting?])
        err         @(subscribe [:auth/error])]
    [:div.auth-page
     [:h1 "Sign up"]
     [rf/route-link {:to :realworld.auth/login} "Have an account?"]
     (when err [:ul.error-messages [:li err]])
     [:form
      {:on-submit (fn [e] (.preventDefault e) (dispatch [:auth.register-form/submit]))}
      [:fieldset
       [:fieldset.form-group
        [:input {:type "text" :placeholder "Username" :value (:username draft) :disabled submitting?
                 :on-change #(dispatch [:auth.register-form/edit-field :username (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type "email" :placeholder "Email" :value (:email draft) :disabled submitting?
                 :on-change #(dispatch [:auth.register-form/edit-field :email (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type "password" :placeholder "Password" :value (:password draft) :disabled submitting?
                 :on-change #(dispatch [:auth.register-form/edit-field :password (.. % -target -value)])}]]
       [:button {:type "submit" :disabled submitting?}
        (if submitting? "Signing up…" "Sign up")]]]]))
