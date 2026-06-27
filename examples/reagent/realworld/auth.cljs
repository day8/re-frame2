(ns realworld.auth
  "Authentication for the RealWorld (Conduit) example.

   The auth flow is a state machine. Login, register, session restore, and
   logout are all sub-events routed through one handler:

     (rf/dispatch [:auth/flow [:auth/login creds]])

   The login/register draft slices live under [:auth ...]; the machine
   snapshot lives in runtime-db at
   [:rf.runtime/machines :snapshots :auth/flow]."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; State machines ship in the re-frame2-machines artefact.
            ;; Requiring the ns registers its hooks so `rf/reg-machine`
            ;; (called below) and the `:rf/machine` sub resolve. See the
            ;; machines guide: ../../../docs/machines/index.md
            [re-frame.machines]
            [realworld.schema :as schema]
            [realworld.http :as rh])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; FX / COFX
;; ============================================================================
;;
;; One fx for the localStorage seam. Arg shape is `{:token <token-or-nil>}`
;; — write on truthy, remove on nil. One seam means one fx to mock in
;; tests, called by the machine's `:store-session` and `:clear-session`
;; actions with different args.
;;
;; Conformance-contract surface: the official RealWorld browser/E2E suite
;; reads the session from `localStorage["jwtToken"]`, so this seam uses
;; that exact key verbatim — it is NOT namespaced. Conformance is validated
;; against standalone serving (one app per origin).

(rf/reg-fx :auth.session/persist
  {:doc       "Persist (or clear) the JWT in localStorage under the official
               contract key `jwtToken`. Arg `{:token t}` writes
               the token when truthy; nil removes the key."
   :platforms #{:client}}
  (fn fx-auth-session-persist [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (if token
        (.setItem    ls "jwtToken" token)
        (.removeItem ls "jwtToken")))))

;; The saved JWT is read from localStorage at boot and `:auth/initialise`
;; folds it into durable app-db at [:auth :token]. A durable write must
;; fold a RECORDED fact, not an ambient `localStorage` read at the write
;; site — replay and epoch-restore could not reproduce the latter. So the
;; JWT comes from a recordable coeffect: `:auth.session/token` is a
;; `:recordable? true` registration whose supplier reads localStorage. The
;; supplier runs once, its value is recorded onto the causal token, and
;; replay re-presents the captured token verbatim. See the coeffects guide
;; on the two grades: ../../../docs/guide/concepts/effects-and-coeffects.md#two-grades-ambient-and-recordable
;;
;; This is also why `ssr.cljc` may redact [:auth :token] from the hydration
;; payload — the client re-derives the token through this recorded boot
;; coeffect, not an ambient write-site read.
(defn read-jwt-from-storage
  "Read the saved JWT from localStorage (or nil) — the supplier body for the
   `:auth.session/token` recordable coeffect. nil when absent or
   unavailable (e.g. node, logged-out first run)."
  []
  (some-> (.-localStorage js/globalThis)
          (.getItem "jwtToken")))

(rf/reg-cofx :auth.session/token
  {:recordable? true
   :doc "Recordable coeffect: the saved JWT (or nil), read from
         localStorage. The supplier runs at the start of the boot dispatch;
         its value is recorded onto the causal token and re-presented
         verbatim under replay and epoch-restore — so the durable write
         that folds it (`:auth/initialise` → [:auth :token]) replays the
         captured token, never a live localStorage re-read. A handler folds
         it by declaring `:rf.cofx/requires [:auth.session/token]` and
         reading it flat. A production dispatch carries no cofx — the
         supplier is the source; tests pin an exact value via the
         dispatch-site `:rf.cofx` stub (`{:rf.cofx {:auth.session/token \"…\"}}`)."}
  (fn [] (read-jwt-from-storage)))

;; ============================================================================
;; SUPPORT EVENTS
;; ============================================================================

(rf/reg-event :auth/store-session
  {:doc "Store the authenticated session. The JWT has one durable home: the
         classified `[:auth :token]` path (declared sensitive by
         `:auth/classify-token` in core.cljs). The User payload is stored at
         `[:auth :user]` with its `:token` field stripped off (`dissoc`), so
         the JWT is not duplicated into the unclassified
         `[:auth :user :token]` slot — a second copy there would ship raw
         off-box, because classification does not propagate; each path is
         its own declaration. Views and subs read `:auth/user` for identity
         (username, bio, image); the bearer-auth interceptor reads the token
         from `[:auth :token]`. See the keep-secrets how-to:
         ../../../docs/guide/how-to/keep-secrets-out-of-traces.md"}
  (fn [{:keys [db]} [_ user]]
    {:db (-> db
        (assoc-in [:auth :user] (dissoc user :token))
        (assoc-in [:auth :token] (:token user)))}))

(rf/reg-event :auth/clear-session
  (fn [{:keys [db]} _]
    {:db (-> db
        (assoc-in [:auth :user] nil)
        (assoc-in [:auth :token] nil))}))

(rf/reg-event :auth/post-login-redirect
  {:doc "Bounce the freshly-authenticated user back to the route the auth
         guard intercepted (`[:auth :return-to]`, set in routing.cljs), or
         home when there is none. Dispatched by the auth machine's
         `:store-session` action — a machine action sees no `:db` and emits
         no `:db`, so the bounce is an ordinary event. Reads and clears the
         slot in one step so a later plain login can't re-bounce to a stale
         target."}
  (fn [{:keys [db]} _]
    (let [return-to (get-in db [:auth :return-to])]
      {:db (update db :auth dissoc :return-to)
       :fx [[:dispatch (if return-to
                         [:rf.route/navigate (:id return-to) (:params return-to)]
                         [:rf.route/navigate :realworld/home])]]})))

;; ============================================================================
;; AUTH STATE MACHINE
;; ============================================================================

;; The auth machine carries a `[:schemas :data]` that validates the
;; snapshot's `:data` slot. Registering the machine here is what makes that
;; schema live rather than inert. This machine validates only its `:data`
;; (no outer event-vector `:schema`), so the opts map carries just `:doc` +
;; `:rf.http/decode-schemas`.
(rf/reg-machine :auth/flow
  {:doc "The auth flow: idle → submitting/restoring → authed | error.
         HTTP requests go via `:rf.http/managed`. Login / register /
         restore do not retry — one submission per click; a transient
         error surfaces in `:error` and the user retries."
   :rf.http/decode-schemas [schema/UserResponse]}
  ;; The spec map does not carry :id; the id is the surrounding
  ;; reg-machine id.
  {:initial :idle
   :data    {:error nil}
   ;; The snapshot lives in runtime-db at
   ;; [:rf.runtime/machines :snapshots :auth/flow], so its :data shape is
   ;; validated here via [:schemas :data], not via an app-schema (app
   ;; schemas validate the app-db partition only).
   :schemas {:data schema/AuthFlowData}
   :guards
   {:has-token?
    (fn [{[_ token] :event}]
      (not (str/blank? token)))}
   :actions
   {:clear-error
    (fn [_]
      {:data {:error nil}})

    :begin-login
    (fn [{[_ {:keys [email password]}] :event}]
      {:data {:error nil}
       :fx [[:rf.http/managed
             (rh/request {:method     :post
                          :path       "/users/login"
                          :body       {:user {:email email :password password}}
                          :decode     schema/UserResponse
                          :on-success [:auth/flow [:auth/success]]
                          :on-failure [:auth/flow [:auth/failure]]})]]})

    :begin-register
    (fn [{[_ {:keys [username email password]}] :event}]
      {:data {:error nil}
       :fx [[:rf.http/managed
             (rh/request {:method     :post
                          :path       "/users"
                          :body       {:user {:username username
                                              :email email
                                              :password password}}
                          :decode     schema/UserResponse
                          :on-success [:auth/flow [:auth/success]]
                          :on-failure [:auth/flow [:auth/failure]]})]]})

    :begin-restore
    (fn [_]
      {:data {:error nil}
       :fx [[:rf.http/managed
             (rh/request {:method     :get
                          :path       "/user"
                          :decode     schema/UserResponse
                          :on-success [:auth/flow [:auth/success]]
                          :on-failure [:auth/flow [:auth/restore-failed]]})]]})

    :store-session
    (fn [{[_ {:keys [value]}] :event}]
      ;; A machine action sees no `:db` and emits no `:db`, so the
      ;; post-login bounce-back runs as an ordinary event
      ;; (`:auth/post-login-redirect`) that reads the guard-stashed
      ;; `:return-to` slot, navigates there (or home), and clears it. See
      ;; routing.cljs for where the slot is set.
      (let [user (:user value)]
        {:data {:error nil}
         :fx [[:dispatch [:auth/store-session user]]
              [:auth.session/persist {:token (:token user)}]
              [:dispatch [:auth/post-login-redirect]]]}))

    :record-error
    (fn [{[_ {:keys [failure]}] :event}]
      {:data {:error (rh/failure->message failure)}})

    :clear-session
    (fn [_]
      {:data {:error nil}
       :fx [[:dispatch [:auth/clear-session]]
            [:auth.session/persist {:token nil}]
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
    {:on {:auth/success        {:target :authed :action :store-session}
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

(rf/reg-event :auth/initialise
  {:rf.cofx/requires [:auth.session/token]}
  (fn handler-auth-initialise [{:keys [db auth.session/token]} _]
    ;; Dispatch `:auth/flow [:auth/restore token]` unconditionally — even
    ;; when `token` is nil. This first delivery spawns the auth machine's
    ;; snapshot (the machine materialises at `:idle` on its first event), so
    ;; the navbar's `:auth/state` sub reads `:idle` rather than `nil` from
    ;; cold boot. The do-we-have-a-token? decision is then the machine's
    ;; `:idle` `:has-token?` guard: a blank token routes to the no-op
    ;; `{:target :idle}` branch, a real one kicks `:begin-restore`. Guarding
    ;; the dispatch here would skip the machine spawn on a no-token boot and
    ;; split the token decision across two sites.
    {:db (assoc db :auth {:user nil
                          :token token})
     :fx [[:dispatch [:auth/flow [:auth/restore token]]]]}))

;; ============================================================================
;; FORMS
;; ============================================================================

(def login-form-defaults    {:email "" :password ""})
(def register-form-defaults {:username "" :email "" :password ""})

(rf/reg-event :auth.login-form/initialise
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:auth :login-form]
              {:draft             login-form-defaults
               :submitted         nil
               :status            :idle
               :errors            {}
               :touched           #{}
               :submit-attempted? false
               :submit-error      nil})}))

(rf/reg-event :auth.login-form/edit-field
  {:schema [:cat [:= :auth.login-form/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:auth :login-form :draft field] value)
        (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))

(rf/reg-event :auth.login-form/submit
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:auth :login-form :draft])]
      {:db (-> db
               (assoc-in [:auth :login-form :submit-attempted?] true)
               (assoc-in [:auth :login-form :status] :submitting))
       :fx [[:dispatch [:auth/flow [:auth/login draft]]]]})))

(rf/reg-event :auth.register-form/initialise
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:auth :register-form]
              {:draft             register-form-defaults
               :submitted         nil
               :status            :idle
               :errors            {}
               :touched           #{}
               :submit-attempted? false
               :submit-error      nil})}))

(rf/reg-event :auth.register-form/edit-field
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:auth :register-form :draft field] value)
        (update-in [:auth :register-form :touched] (fnil conj #{}) field))}))

(rf/reg-event :auth.register-form/submit
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:auth :register-form :draft])]
      {:db (-> db
               (assoc-in [:auth :register-form :submit-attempted?] true)
               (assoc-in [:auth :register-form :status] :submitting))
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
  ;; Machine snapshots live in runtime-db — read them through the
  ;; `:rf/machine` sub (the public surface), not a raw db path.
  :<- [:rf/machine :auth/flow]
  (fn [snapshot _] snapshot))

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

(rf/reg-sub :auth.login-form/slice
  (fn [db _] (get-in db [:auth :login-form])))

(rf/reg-sub :auth.login-form/field-error
  {:doc "Per-field validation error for the login form. Reveal every error
         after the first submit click, or once a field is :touched. See the
         forms how-to: ../../../docs/guide/how-to/build-a-form.md"}
  :<- [:auth.login-form/slice]
  (fn [form [_ field]]
    (when (or (:submit-attempted? form)
              (contains? (:touched form) field))
      (get-in form [:errors field]))))

(rf/reg-sub :auth.register-form/draft
  (fn [db _] (get-in db [:auth :register-form :draft])))

(rf/reg-sub :auth.register-form/slice
  (fn [db _] (get-in db [:auth :register-form])))

(rf/reg-sub :auth.register-form/field-error
  {:doc "Per-field validation error for the register form. Reveal every
         error after the first submit click, or once a field is :touched.
         See the forms how-to: ../../../docs/guide/how-to/build-a-form.md"}
  :<- [:auth.register-form/slice]
  (fn [form [_ field]]
    (when (or (:submit-attempted? form)
              (contains? (:touched form) field))
      (get-in form [:errors field]))))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view ^{:doc "Login page."}
          login-page []
  (let [draft       @(subscribe [:auth.login-form/draft])
        submitting? @(subscribe [:auth/submitting?])
        err         @(subscribe [:auth/error])]
    [:div.auth-page {:data-testid "login-page"}
     [:h1 "Sign in"]
     [rf/route-link {:to :realworld.auth/register} "Need an account?"]
     (when err [:ul.error-messages [:li err]])
     [:form
      {:data-testid "login-form"
       :on-submit (fn [e]
                    (.preventDefault e)
                    (dispatch [:auth.login-form/submit]))}
      [:fieldset
       [:fieldset.form-group
        [:input {:type        "email"
                 :name        "email"
                 :data-testid "login-email"
                 :placeholder "Email"
                 :value       (:email draft)
                 :disabled    submitting?
                 :on-change   #(dispatch [:auth.login-form/edit-field :email (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type        "password"
                 :name        "password"
                 :data-testid "login-password"
                 :placeholder "Password"
                 :value       (:password draft)
                 :disabled    submitting?
                 :on-change   #(dispatch [:auth.login-form/edit-field :password (.. % -target -value)])}]]
       [:button {:type "submit"
                 :data-testid "login-submit"
                 :disabled submitting?}
        (if submitting? "Signing in…" "Sign in")]]]]))

(reg-view ^{:doc "Register page."}
          register-page []
  (let [draft       @(subscribe [:auth.register-form/draft])
        submitting? @(subscribe [:auth/submitting?])
        err         @(subscribe [:auth/error])]
    [:div.auth-page
     [:h1 "Sign up"]
     [rf/route-link {:to :realworld.auth/login} "Have an account?"]
     (when err [:ul.error-messages [:li err]])
     [:form
      {:on-submit (fn [e]
                    (.preventDefault e)
                    (dispatch [:auth.register-form/submit]))}
      [:fieldset
       [:fieldset.form-group
        [:input {:type        "text"
                 :name        "username"
                 :placeholder "Username"
                 :value       (:username draft)
                 :disabled    submitting?
                 :on-change   #(dispatch [:auth.register-form/edit-field :username (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type        "email"
                 :name        "email"
                 :placeholder "Email"
                 :value       (:email draft)
                 :disabled    submitting?
                 :on-change   #(dispatch [:auth.register-form/edit-field :email (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type        "password"
                 :name        "password"
                 :placeholder "Password"
                 :value       (:password draft)
                 :disabled    submitting?
                 :on-change   #(dispatch [:auth.register-form/edit-field :password (.. % -target -value)])}]]
       [:button {:type "submit" :disabled submitting?}
        (if submitting? "Signing up…" "Sign up")]]]]))

