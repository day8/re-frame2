(ns realworld.auth
  "Authentication for the RealWorld (Conduit) example.

   The auth flow is implemented as a re-frame2 state machine. Login,
   register, session restore, and logout are all sub-events routed through
   one handler:

     (rf/dispatch [:auth/flow [:auth/login creds]])

   Pattern-Forms owns the login/register draft slices under [:auth ...];
   the machine snapshot itself lives at [:rf/machines :auth/flow]."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [realworld.schema]
            [realworld.http]
            [realworld.routing :as routing])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; ============================================================================
;; FX / COFX
;; ============================================================================

(rf/reg-fx :auth.session/store
  {:doc       "Persist the JWT in localStorage."
   :platforms #{:client}}
  (fn fx-auth-session-store [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (.setItem ls "conduit/jwt" token))))

(rf/reg-fx :auth.session/clear
  {:doc       "Clear the JWT from localStorage."
   :platforms #{:client}}
  (fn fx-auth-session-clear [_m _]
    (when-let [ls (.-localStorage js/globalThis)]
      (.removeItem ls "conduit/jwt"))))

(rf/reg-cofx :auth.session/token
  {:doc "Inject the saved token (or nil) from localStorage into coeffects."}
  (fn cofx-auth-session-token [coeffects _]
    (assoc coeffects :auth.session/token
           (some-> (.-localStorage js/globalThis)
                   (.getItem "conduit/jwt")))))

;; ============================================================================
;; SUPPORT EVENTS
;; ============================================================================

(rf/reg-event-db :auth/store-session
  (fn [db [_ user]]
    (-> db
        (assoc-in [:auth :user] user)
        (assoc-in [:auth :token] (:token user)))))

(rf/reg-event-db :auth/clear-session
  (fn [db _]
    (-> db
        (assoc-in [:auth :user] nil)
        (assoc-in [:auth :token] nil))))

;; ============================================================================
;; AUTH STATE MACHINE
;; ============================================================================

(rf/reg-event-fx :auth/flow
  {:doc "The auth flow: idle → submitting/restoring → authed | error."}
  (rf/create-machine-handler
    ;; Per Spec 005 §Where snapshots live: spec map does NOT carry :id;
    ;; the id is the surrounding reg-event-fx id.
    {:initial :idle
     :data    {:error nil}
     :guards
     {:has-token?
      (fn [_snapshot [_ token]]
        (not (str/blank? token)))}
     :actions
     {:clear-error
      (fn [_ _]
        {:data {:error nil}})

      :begin-login
      (fn [_ [_ {:keys [email password]}]]
        {:data {:error nil}
         :fx [[:http {:method     :post
                      :url        "/users/login"
                      :auth?      false
                      :body       {:user {:email email :password password}}
                      :on-success [:auth/flow [:auth/success]]
                      :on-error   [:auth/flow [:auth/failure]]}]]})

      :begin-register
      (fn [_ [_ {:keys [username email password]}]]
        {:data {:error nil}
         :fx [[:http {:method     :post
                      :url        "/users"
                      :auth?      false
                      :body       {:user {:username username
                                          :email email
                                          :password password}}
                      :on-success [:auth/flow [:auth/success]]
                      :on-error   [:auth/flow [:auth/failure]]}]]})

      :begin-restore
      (fn [_ _]
        {:data {:error nil}
         :fx [[:http {:method     :get
                      :url        "/user"
                      :on-success [:auth/flow [:auth/success]]
                      :on-error   [:auth/flow [:auth/restore-failed]]}]]})

      :store-session
      (fn [_ [_ resp]]
        (let [user (:user resp)]
          {:data {:error nil}
           :fx [[:dispatch [:auth/store-session user]]
                [:auth.session/store {:token (:token user)}]
                [:dispatch [:rf.route/navigate :route/home]]]}))

      :record-error
      (fn [_ [_ err]]
        {:data {:error (or (some-> err :errors first)
                           (:message err)
                           "Authentication failed.")}})

      :clear-session
      (fn [_ _]
        {:data {:error nil}
         :fx [[:dispatch [:auth/clear-session]]
              [:auth.session/clear nil]
              [:dispatch [:rf.route/navigate :route/home]]]})}
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
      {:on {:auth/success        {:target :authed :action :store-session}
            :auth/restore-failed {:target :idle   :action :clear-session}}}

      :authed
      {:on {:auth/logout {:target :idle :action :clear-session}
            :auth/login  {:target :submitting :action :begin-login}}}

      :error
      {:on {:auth/login    {:target :submitting :action :begin-login}
            :auth/register {:target :submitting :action :begin-register}
            :auth/dismiss  {:target :idle       :action :clear-error}}}}}))

;; ============================================================================
;; INITIALISATION + SESSION RESTORE
;; ============================================================================

(rf/reg-event-fx :auth/initialise
  [(rf/inject-cofx :auth.session/token)]
  (fn handler-auth-initialise [{:keys [db auth.session/token]} _]
    {:db (assoc db :auth {:user nil
                          :token token})
     :fx [[:dispatch [:auth/flow [:auth/restore token]]]]}))

;; ============================================================================
;; FORMS
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
        (assoc-in [:auth :login-form :draft field] value)
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
        (assoc-in [:auth :register-form :draft field] value)
        (update-in [:auth :register-form :touched] (fnil conj #{}) field))))

(rf/reg-event-fx :auth.register-form/submit
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:auth :register-form :draft])]
      {:db (assoc-in db [:auth :register-form :status] :submitting)
       :fx [[:dispatch [:auth/flow [:auth/register draft]]]]})))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :auth/user
  (fn [db _] (get-in db [:auth :user])))

(rf/reg-sub :auth/token
  (fn [db _] (get-in db [:auth :token])))

(rf/reg-sub :auth/flow-state
  {:doc "Current state of the auth machine snapshot."}
  (fn [db _]
    (get-in db [:rf/machines :auth/flow])))

(rf/reg-sub :auth/state
  :<- [:auth/flow-state]
  (fn [snapshot _]
    (:state snapshot)))

(rf/reg-sub :auth/error
  :<- [:auth/flow-state]
  (fn [snapshot _]
    (get-in snapshot [:data :error])))

(rf/reg-sub :auth/authenticated?
  :<- [:auth/state]
  (fn [state _]
    (= state :authed)))

(rf/reg-sub :auth/submitting?
  :<- [:auth/state]
  (fn [state _]
    (or (= state :submitting) (= state :restoring))))

(rf/reg-sub :auth.login-form/draft
  (fn [db _] (get-in db [:auth :login-form :draft])))

(rf/reg-sub :auth.register-form/draft
  (fn [db _] (get-in db [:auth :register-form :draft])))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "Login page."}
          login-page []
  (let [draft       @(subscribe [:auth.login-form/draft])
        submitting? @(subscribe [:auth/submitting?])
        err         @(subscribe [:auth/error])]
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
        (if submitting? "Signing in…" "Sign in")]]]]))

(reg-view ^{:doc "Register page."}
          register-page []
  (let [draft       @(subscribe [:auth.register-form/draft])
        submitting? @(subscribe [:auth/submitting?])
        err         @(subscribe [:auth/error])]
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
        (if submitting? "Signing up…" "Sign up")]]]]))

