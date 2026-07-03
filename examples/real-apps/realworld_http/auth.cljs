(ns realworld-http.auth
  "Authentication for the RealWorld (Conduit) example.

   Auth is one of those things that looks like a pile of unrelated buttons —
   sign in, sign up, restore my session, log out — but is really a single
   state machine wearing four hats. So that's how it's modelled here: login,
   register, session restore, and logout are all sub-events fed into one
   machine through one handler:

     (rf/dispatch [:auth/flow [:auth/login creds]])

   The login and register draft forms live in app-db under [:auth ...]; the
   machine's own snapshot lives over in runtime-db at
   [:rf.runtime/machines :snapshots :auth/flow]."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; State machines live in their own artefact. We require it purely
            ;; for the side effect of loading it: that registers the hooks that
            ;; make `rf/reg-machine` (below) and the `:rf/machine` sub resolve
            ;; to something. No alias needed — we just want it in the room. See
            ;; the machines guide: ../../../docs/machines/index.md
            [re-frame.machines]
            [realworld-http.schema :as schema]
            [realworld-http.http :as rh])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; FX / COFX
;; ============================================================================
;;
;; Talking to localStorage is a side effect, so it gets its own fx — one
;; little doorway between our pure handlers and the browser's storage. The arg
;; is `{:token <token-or-nil>}`: a truthy token writes, nil removes. Keeping it
;; to a single seam means there's exactly one thing to stub in tests; the
;; machine's `:store-session` and `:clear-session` actions both call it, just
;; with different args.
;;
;; One detail that isn't ours to choose: the official RealWorld E2E suite reads
;; the session straight out of `localStorage["jwtToken"]`, so we use that exact
;; key, un-namespaced, verbatim. Conformance is checked against standalone
;; serving (one app per origin), so there's no collision to worry about.

(rf/reg-fx :auth.session/persist
  {:doc       "Save or clear the JWT in localStorage, under the contract key
               `jwtToken`. `{:token t}` writes a truthy token; nil removes the
               key entirely."
   :platforms #{:client}}
  (fn fx-auth-session-persist [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (if token
        (.setItem    ls "jwtToken" token)
        (.removeItem ls "jwtToken")))))

;; At boot we read the saved JWT out of localStorage and `:auth/initialise`
;; folds it into durable app-db at [:auth :token]. Here's the subtlety: a
;; durable write has to fold a RECORDED fact, not a live localStorage read
;; taken in the handler — otherwise replay and epoch-restore, which don't have
;; your localStorage, can't reproduce it. So the JWT arrives as a recordable
;; coeffect. `:auth.session/token` is a `:recordable? true` registration whose
;; supplier reads localStorage; that supplier runs once, its value is recorded
;; onto the causal token, and from then on replay hands back the captured token
;; verbatim. See the coeffects guide on the two grades:
;; ../../../docs/core/concepts/effects-and-coeffects.md#two-grades-ambient-and-recordable
;;
;; It's also why `ssr.cljc` can happily redact [:auth :token] from the
;; hydration payload: the client doesn't need the server to ship it the token,
;; it re-derives it through this recorded boot coeffect.
(defn read-jwt-from-storage
  "Read the saved JWT out of localStorage, or nil if there isn't one — the
   supplier behind the `:auth.session/token` recordable coeffect. nil when
   the key is absent or storage isn't there at all (node, or a logged-out
   first run)."
  []
  (some-> (.-localStorage js/globalThis)
          (.getItem "jwtToken")))

(rf/reg-cofx :auth.session/token
  {:recordable? true
   :doc "Recordable coeffect: the saved JWT (or nil), read from localStorage.
         The supplier fires once, at the start of the boot dispatch, and its
         value is recorded onto the causal token — so the durable write that
         folds it (`:auth/initialise` → [:auth :token]) replays the captured
         token rather than re-reading localStorage on every replay. A handler
         opts in by declaring `:rf.cofx/requires [:auth.session/token]` and
         reading it flat. Live dispatches carry no cofx — the supplier is the
         source of truth; tests pin an exact value through the dispatch-site
         `:rf.cofx` stub (`{:rf.cofx {:auth.session/token \"…\"}}`)."}
  (fn [] (read-jwt-from-storage)))

;; ============================================================================
;; SUPPORT EVENTS
;; ============================================================================

(rf/reg-event :auth/store-session
  {:doc "Stash the authenticated session. The JWT gets exactly one durable
         home: the classified `[:auth :token]` path (marked sensitive by
         `:auth/classify-token` in core.cljs). The User payload goes to
         `[:auth :user]` — but with its `:token` field `dissoc`'d off first,
         so the JWT doesn't end up quietly duplicated at the unclassified
         `[:auth :user :token]`. That second copy would ship raw off-box,
         because classification doesn't propagate down the tree — each path
         declares its own sensitivity, so two copies means two declarations,
         and we'd have forgotten one. Views and subs read `:auth/user` for
         who-you-are (username, bio, image); the bearer-auth interceptor reads
         the token from `[:auth :token]`. See the keep-secrets how-to:
         ../../../docs/core/how-to/keep-secrets-out-of-traces.md"}
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
  {:doc "Drop the freshly-signed-in user back where they were headed before
         the auth guard sent them to login (`[:auth :return-to]`, stashed in
         routing.cljs), or home if there's no such crumb. The auth machine's
         `:store-session` action dispatches this — a machine action can't see
         or emit `:db`, so the redirect rides as an ordinary event. It reads
         and clears the slot in the same step, so a later ordinary login can't
         get bounced to a stale target left lying around."}
  (fn [{:keys [db]} _]
    (let [return-to (get-in db [:auth :return-to])]
      {:db (update db :auth dissoc :return-to)
       :fx [[:dispatch (if return-to
                         [:rf.route/navigate (:id return-to) (:params return-to)]
                         [:rf.route/navigate :realworld/home])]]})))

;; ============================================================================
;; AUTH STATE MACHINE
;; ============================================================================

;; The machine carries a `[:schemas :data]` that validates its snapshot's
;; `:data` slot, and registering it here is what brings that schema to life
;; instead of leaving it as decoration. This one only validates its `:data`
;; (there's no outer event-vector `:schema`), so the opts map is short: just
;; `:doc` and `:rf.http/decode-schemas`.
(rf/reg-machine :auth/flow
  {:doc "The auth flow in one line: idle → submitting/restoring → authed |
         error. Requests go out via `:rf.http/managed`. Login, register, and
         restore don't retry — it's one submission per click; a transient
         failure parks a message in `:error` and the user takes another swing."
   :rf.http/decode-schemas [schema/UserResponse]}
  ;; No :id in the spec map — the id is just the `reg-machine` id above.
  {:initial :idle
   :data    {:error nil}
   ;; The snapshot lives in runtime-db (at
   ;; [:rf.runtime/machines :snapshots :auth/flow]), not app-db. App-schemas
   ;; only police the app-db partition, so the snapshot's :data shape is
   ;; validated right here via [:schemas :data] instead.
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
      ;; A machine action can't touch `:db`, so the post-login bounce-back is
      ;; farmed out to an ordinary event (`:auth/post-login-redirect`): it
      ;; reads the guard-stashed `:return-to` slot, sends you there (or home),
      ;; and clears it. routing.cljs is where that slot gets set.
      (let [user (:user value)]
        {:data {:error nil}
         :fx [[:dispatch [:auth/store-session user]]
              [:auth.session/persist {:token (:token user)}]
              [:dispatch [:auth/post-login-redirect]]]}))

    :record-error
    (fn [{[_ {:keys [error]}] :event}]
      {:data {:error (rh/failure->message error)}})

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
    ;; Always fire `:auth/flow [:auth/restore token]`, even when `token` is
    ;; nil. Why bother restoring nothing? Because that first event is also
    ;; what spawns the machine's snapshot (a machine materialises at `:idle`
    ;; on its very first event), so the navbar's `:auth/state` sub reads
    ;; `:idle` from cold boot instead of an awkward `nil`. The actual
    ;; do-we-have-a-token? question then belongs to one place — the `:idle`
    ;; state's `:has-token?` guard: a blank token takes the no-op
    ;; `{:target :idle}` branch, a real one kicks off `:begin-restore`.
    ;; Guarding the dispatch up here would skip the machine spawn on a
    ;; no-token boot AND split that one decision across two sites, which is
    ;; how subtle bugs are born.
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
  {:doc "The current auth machine snapshot."}
  ;; Snapshots live in runtime-db, but you don't go digging for them by path
  ;; — `:rf/machine` is the front door. Read through it.
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
  {:doc "The validation error for one login-form field — or nil while we're
         keeping quiet. The rule of politeness: don't nag about a field until
         the user has either touched it or hit submit at least once. See the
         forms how-to: ../../../docs/core/how-to/build-a-form.md"}
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
  {:doc "The validation error for one register-form field, or nil while we
         hold our tongue. Same rule as the login form: stay quiet until the
         field is touched or the user has tried to submit. See the forms
         how-to: ../../../docs/core/how-to/build-a-form.md"}
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

