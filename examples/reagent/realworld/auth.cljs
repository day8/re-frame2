(ns realworld.auth
  "Authentication for the RealWorld (Conduit) example.

   The auth flow is implemented as a re-frame2 state machine. Login,
   register, session restore, and logout are all sub-events routed through
   one handler:

     (rf/dispatch [:auth/flow [:auth/login creds]])

   Pattern-Forms owns the login/register draft slices under [:auth ...];
   the machine snapshot itself lives in runtime-db at [:rf.runtime/machines :snapshots :auth/flow]."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; The Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/reg-machine
            ;; (called below at ns-load) and the `:rf/machine` framework
            ;; sub resolve.
            [re-frame.machines]
            [realworld.schema :as schema]
            [realworld.http :as rh])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; FX / COFX
;; ============================================================================
;;
;; One fx for the localStorage seam. Arg shape is `{:token <token-or-nil>}`
;; — write on truthy, remove on nil. Halving the registrar entries keeps
;; the localStorage edge a single seam: one fx to mock in tests, one fx
;; for the machine's `:store-session` and `:clear-session` actions to
;; call with different args.
;;
;; CONFORMANCE-CONTRACT SURFACE. The official RealWorld
;; browser/E2E suite reads the session from `localStorage["jwtToken"]` — that
;; exact key is the contract, so this seam uses it verbatim (it is NOT
;; namespaced under `conduit/…`). SAME-ORIGIN CAVEAT: the contract assumes one
;; RealWorld app per origin. The repo's dev orchestrator serves BOTH the
;; managed-HTTP and the resources variant from a single origin (at
;; `/realworld/` and `/realworld-resources/`), so the two conforming apps share
;; — and clobber — each other's `jwtToken` there. That is a known dev-mode
;; artifact, NOT a contract violation: conformance is validated against
;; STANDALONE serving (one app per origin), which the external suite does
;; anyway. See the README §RealWorld contract conformance.

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

;; EP-0017: the saved JWT is read from localStorage at boot and
;; `:auth/initialise` folds it into durable app-db ([:auth :token]). A durable
;; write must be a function of prior frame-state plus the causal token — not of
;; an ambient `localStorage` read at the write site (which replay/epoch-restore
;; could not reproduce). So `:auth.session/token` is registered RECORDABLE +
;; PROVIDED (EP-0017 §2, the `:rf/time-ms` shape): it carries NO generator — its
;; value is STAMPED onto the boot dispatch token by its owner
;; (`realworld.core/run`, reading the host once at the boundary), recorded with
;; the token, and re-presented verbatim under replay / epoch-restore. This
;; closes the determinism hole the ambient grade left open: replay re-folds the
;; captured token, never a live re-read of localStorage.
;;
;; This is also why `ssr.cljc` may redact [:auth :token] from the hydration
;; payload — the client re-derives the durable token slot through this RECORDED
;; boot coeffect, not an ambient write-site read.
(defn read-jwt-from-storage
  "Host-boundary read of the saved JWT from localStorage (or nil). Called from
   `realworld.core/run` so the value is STAMPED onto the boot dispatch token's
   `:rf.cofx` as a recordable causal fact rather than being read ambiently in a
   durable handler. nil when absent / unavailable (e.g. node, logged-out first
   run)."
  []
  (some-> (.-localStorage js/globalThis)
          (.getItem "jwtToken")))

(rf/reg-cofx :auth.session/token
  {:recordable? true
   :provided?   true
   :doc "Recordable, PROVIDED coeffect (EP-0017 §2): the saved JWT (or nil)
         read from localStorage. It has NO generator — its value is stamped
         onto the boot dispatch token by `realworld.core/run` (the host read
         happens ONCE there), recorded with the token, and re-presented verbatim
         under replay / epoch-restore. A handler that folds it into durable
         app-db declares `:rf.cofx/requires [:auth.session/token]` and reads it
         flat; absent from the token it is `:rf.error/missing-required-cofx`
         (the boot dispatch always supplies it). Tests / replay supply the value
         directly on the dispatch token —
         `{:rf.cofx {:auth.session/token \"…\"}}` — never re-register a supplier."})

;; ============================================================================
;; SUPPORT EVENTS
;; ============================================================================

(rf/reg-event :auth/store-session
  {:doc "Store the authenticated session. The JWT has ONE durable home — the
         classified `[:auth :token]` path (EP-0025 commit-plane `:sensitive`
         effect, declared by `:auth/classify-token` in core.cljs). The User
         payload is stored at `[:auth :user]` with its `:token` field stripped
         off (`dissoc`), so the JWT is NOT duplicated into the UNCLASSIFIED
         `[:auth :user :token]` slot — a second durable copy there would ship
         RAW to every off-box record (classification does not propagate; each
         path is its own declaration). Views / subs read `:auth/user` for
         identity (username, bio, image); none of them need the token, which
         the bearer-auth interceptor reads from the classified `[:auth :token]`
         path instead."}
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
         `:store-session` action — machine actions can't navigate or read
         `:db` directly (Spec 005 §Hard-disallow `:db`), so the bounce is an
         ordinary event. Reads AND clears the slot in one step so a later
         plain login can't re-bounce to a stale target."}
  (fn [{:keys [db]} _]
    (let [return-to (get-in db [:auth :return-to])]
      {:db (update db :auth dissoc :return-to)
       :fx [[:dispatch (if return-to
                         [:rf.route/navigate (:id return-to) (:params return-to)]
                         [:rf.route/navigate :realworld/home])]]})))

;; ============================================================================
;; AUTH STATE MACHINE
;; ============================================================================

;; `reg-machine` is the single registration home.
;; The auth machine carries a `[:schemas :data]` (validating the snapshot's
;; `:data` slot); registering it here stamps the `:rf/machine?` /
;; `:rf/machine` metadata that makes the `[:schemas :data]` LIVE (`(machine-meta
;; :auth/flow)` reads it, the `:where :machine-data` walker resolves it) AND
;; bridges its redaction marks — both in one place. The metadata is what makes
;; the `[:schemas :data]` enforced rather than inert. This
;; machine validates only its `:data` (no outer event-vector `:schema`), so
;; the opts map carries just `:doc` + `:rf.http/decode-schemas`.
(rf/reg-machine :auth/flow
  {:doc "The auth flow: idle → submitting/restoring → authed | error.
         HTTP requests go via :rf.http/managed (Spec 014). Login /
         register / restore deliberately do NOT retry — the user's
         intent is one submission per click; a transient error surfaces
         in `:error` and the user retries themselves."
   :rf.http/decode-schemas [schema/UserResponse]}
  ;; Per Spec 005 §Where snapshots live: spec map does NOT carry :id;
  ;; the id is the surrounding reg-machine id.
  {:initial :idle
   :data    {:error nil}
   ;; Snapshot :data validation. The snapshot lives in runtime-db
   ;; ([:rf.runtime/machines :snapshots :auth/flow]), so its :data shape is
   ;; validated here via [:schemas :data] — not via an app-schema (EP-0001,
   ;; Mike ruling #11: app schemas validate the app-db partition only).
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
      ;; Per Spec 005 §Hard-disallow `:db`, a machine action sees no
      ;; `:db` and emits no `:db`; the post-login bounce-back therefore
      ;; runs as an ordinary event (`:auth/post-login-redirect`) that
      ;; reads the guard-stashed `:return-to` slot, navigates there (or
      ;; home), and clears it. See routing.cljs for where the slot is set.
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
    ;; ITEM 8: we dispatch `:auth/flow [:auth/restore token]`
    ;; UNCONDITIONALLY — even when `token` is nil — on purpose. This first
    ;; delivery is what spawns the auth machine's snapshot (the machine
    ;; materialises at `:idle` on its first event), so the navbar's
    ;; `:auth/state` sub reads `:idle` rather than `nil` from cold boot.
    ;; The do-we-have-a-token? decision is then made by the machine's
    ;; `:idle` `:has-token?` guard: a blank token routes to the no-op
    ;; `{:target :idle}` branch, a real one kicks `:begin-restore`. Guarding
    ;; the dispatch here would skip the machine spawn on a no-token boot —
    ;; the guard stays where it belongs (one source of truth for the
    ;; token decision, in the machine).
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
  ;; EP-0001: machine snapshots are durable runtime-db state —
  ;; read them through the framework `:rf/machine` sub (the public surface)
  ;; rather than a raw db path.
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
  {:doc "Per-field validation error for the login form. Per
         Pattern-Forms §Error visibility: reveal every error after the
         first submit click, OR once a field is :touched."}
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
  {:doc "Per-field validation error for the register form. Per
         Pattern-Forms §Error visibility: reveal every error after the
         first submit click, OR once a field is :touched."}
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

