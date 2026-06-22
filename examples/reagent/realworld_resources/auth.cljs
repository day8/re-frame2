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
   016 §clear-scope is causal). The concrete old scope is resolved with the
   `rf/resolve-resource-scope` helper against the handler's COEFFECT db
   (the pre-transition causal input) and the SAME named `:realworld/session`
   resolver every resource site references (EP-0016 D3) — one scope-resolution
   currency, including teardown. The public `:rf.scope/global` reads (article
   lists, detail, profiles, tags) are unaffected — they are the same for
   everyone, so logout leaves them alone.

   On COLD-BOOT SESSION RESTORE the machine ALSO re-ensures the feed under the
   freshly-restored principal (`:auth/ensure-session-feed`). A
   `{:from-db :realworld/session}` subscription re-keys reactively when the
   principal changes but, being passive, does NOT fetch (Spec 016 §A `{:from-db
   …}` subscription re-keys); restore is the one principal switch with NO route
   change (the home route was entered logged-out, so the feed was not planned),
   so without an explicit re-ensure the feed would sit stuck at :idle. The
   interactive login / logout paths re-enter the route and so need no explicit
   ensure — see the `:auth/ensure-session-feed` event below for the full
   rationale."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; The Spec 005 state-machine ns lives in day8/re-frame2-machines.
            ;; Loading the ns registers its late-bind hooks so rf/reg-machine
            ;; (below) and the `:rf/machine` framework subs resolve.
            [re-frame.machines]
            [re-frame.resources]
            [realworld-resources.http :as rh]
            [realworld-resources.schema :as schema]
            ;; Required for its side effect: loading it registers the
            ;; `:realworld/session` resource-scope resolver `:auth/clear-session`
            ;; resolves via `rf/resolve-resource-scope` below.
            [realworld-resources.scope])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; SESSION PERSISTENCE SEAM
;; ============================================================================

;; CONFORMANCE-CONTRACT SURFACE. The official RealWorld
;; browser/E2E suite reads the session from `localStorage["jwtToken"]` — that
;; exact key is the contract, so this seam uses it verbatim (NOT namespaced
;; under `conduit-resources/…`). SAME-ORIGIN CAVEAT: the contract assumes one
;; RealWorld app per origin. The repo's dev orchestrator serves BOTH variants
;; from one origin (`/realworld/` + `/realworld-resources/`), so the two
;; conforming apps share — and clobber — each other's `jwtToken` there. A known
;; dev-mode artifact, NOT a contract violation: conformance is validated
;; against STANDALONE serving (one app per origin), which the external suite
;; does. See the README §RealWorld contract conformance.
(rf/reg-fx :realworld-resources.session/persist
  {:doc       "Persist (or clear) the JWT in localStorage under the official
               contract key `jwtToken`. `{:token t}` writes when
               truthy; nil removes the key."
   :platforms #{:client}}
  (fn [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (if token
        (.setItem ls "jwtToken" token)
        (.removeItem ls "jwtToken")))))

;; EP-0017 (recordable coeffects): the saved JWT is a STORAGE world
;; fact that `:auth/initialise` folds into durable app-db (and that drives
;; session restore). A durable write must be a function of prior frame-state
;; plus the causal token — not of an ambient `localStorage` read at the write
;; site, which replay/epoch-restore could not reproduce. So
;; `:realworld-resources.session/token` is registered RECORDABLE + PROVIDED
;; (EP-0017 §2, the `:rf/time-ms` shape): it carries NO generator — its value is
;; STAMPED onto the boot dispatch token by its owner
;; (`realworld-resources.core/run`, reading the host once at the boundary),
;; recorded with the token, and re-presented verbatim under replay /
;; epoch-restore. This closes the determinism hole the ambient grade left open:
;; replay re-folds the captured token, never a live re-read of localStorage.
(defn read-jwt-from-storage
  "Host-boundary read of the saved JWT from localStorage (or nil). Called from
   `realworld-resources.core/run` so the value is STAMPED onto the boot dispatch
   token's `:rf.cofx` as a recordable causal fact rather than being read
   ambiently in a durable handler. nil when absent / unavailable."
  []
  (some-> (.-localStorage js/globalThis)
          (.getItem "jwtToken")))

(rf/reg-cofx :realworld-resources.session/token
  {:recordable? true
   :provided?   true
   :doc "Recordable, PROVIDED coeffect (EP-0017 §2): the saved JWT (or nil)
         read from localStorage. It has NO generator — its value is stamped
         onto the boot dispatch token by `realworld-resources.core/run` (the
         host read happens ONCE there), recorded with the token, and
         re-presented verbatim under replay / epoch-restore. A handler that
         folds it into durable app-db declares
         `:rf.cofx/requires [:realworld-resources.session/token]` and reads it
         flat; absent from the token it is `:rf.error/missing-required-cofx`
         (the boot dispatch always supplies it). Tests / replay supply the value
         directly on the dispatch token —
         `{:rf.cofx {:realworld-resources.session/token \"…\"}}` — never
         re-register a supplier."})

;; ============================================================================
;; SESSION SUPPORT EVENTS
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

;; ----------------------------------------------------------------------------
;; PRINCIPAL-SWITCH RE-ENSURE  (the principal-switch footgun)
;; ----------------------------------------------------------------------------
;;
;; A `{:from-db :realworld/session}` resource SUBSCRIPTION re-keys reactively
;; when the resolver's app-db input (the authenticated `:username`) changes
;; (Spec 016 §A `{:from-db …}` subscription re-keys). BY DESIGN that re-key does
;; NOT fetch — subscriptions are passive; the NEW scope's data loads only when a
;; CAUSE ensures it (route entry / an event-side `:rf.resource/ensure` /
;; clear-scope). So a principal switch by an app-db WRITE ALONE — no route
;; change — re-keys the feed sub but never loads it: the feed sits at :idle
;; indefinitely (fail-closed and safe, but a surprising "feed stuck loading"
;; DX trap).
;;
;; The one place this app switches principal WITHOUT a route change is
;; COLD-BOOT SESSION RESTORE: the home route is entered logged-out (the
;; `{:from-db :realworld/session}` feed resolves nil and is NOT planned), THEN
;; the async `GET /user` lands and `:restore-session` writes the principal — by
;; design WITHOUT navigating (a deep link must be preserved). The interactive
;; login / logout paths DO navigate (`:auth/post-login-redirect` / logout's
;; `:rf.route/navigate :realworld/home`), so the route plan re-ensures the feed
;; for them; restore is the lone gap.
;;
;; The fix is the bead's recommended shape: an explicit `:rf.resource/ensure`
;; of the feed under the NEW session scope (the `{:from-db :realworld/session}`
;; resolves itself against the post-restore app-db). It rides an app-minted
;; lease `session-feed-owner` so the feed stays active while signed in (the
;; logged-out route entry attached no route owner to release later); logout
;; releases the lease alongside its `clear-scope` (Spec 016 §Active owners —
;; an event-created owner MUST have a matching release path). The `:page` is
;; read from the live route slice so the ensure hits the SAME cache key the
;; home route / feed subscription use. Logged out (post-restore-failure) the
;; reference resolves nil and the runtime fail-closes (no feed to load).

(def session-feed-owner
  "The app-minted liveness lease the principal-switch re-ensure attaches to
   the session feed (a `[:lease …]` owner, Spec 016 §Active owners). Stable
   across switches; released on logout."
  [:lease :auth/session-feed])

(rf/reg-event :auth/ensure-session-feed
  {:doc "Re-ensure the session feed under the CURRENT principal so a principal
         switch with no route change (cold-boot session restore) actually
         loads it — the `{:from-db :realworld/session}` re-key alone is passive
         and does not fetch (Spec 016 §A `{:from-db …}` subscription re-keys /
         the principal-switch footgun). Resolves the scope from the post-restore
         app-db via the resource's own `{:from-db :realworld/session}` policy,
         reads `:page` from the live route slice so the ensure hits the SAME
         cache key the home route + feed subscription use, and attaches the
         stable `session-feed-owner` lease (released by `:auth/clear-session`).
         A fresh `:loaded` entry the route already ensured is a cache-hit; a
         logged-out resolution fail-closes."}
  (fn [{rt :rf.db/runtime} _]
    ;; Default to page 1 so the ensure hits the SAME `{:page 1}` key the home
    ;; route + feed subscription use on the canonical no-`?page=` URL — a raw
    ;; nil would ensure an orphan `{:page nil}` entry.
    (let [page (or (get-in rt [:rf.runtime/routing :current :query :page]) 1)]
      {:fx [[:dispatch [:rf.resource/ensure
                        {:resource :realworld/feed
                         :params   {:page page}
                         :owner    session-feed-owner
                         :cause    [:principal-switch :realworld/feed]}]]]})))

(rf/reg-event :auth/clear-session
  {:doc "Clear the auth slice AND drop the session-scoped resource cache. The
         personalised feed is cached under the session scope (Spec 016
         §Scope); logout MUST clear it (`:rf.resource/clear-scope`) so the next
         user never reads it. The concrete old scope is resolved with the pure
         `rf/resolve-resource-scope` helper against the COEFFECT db (the
         pre-transition value, by definition still carrying the logging-out
         user) and the named `:realworld/session` resolver (EP-0016 D3) — the
         same resolver every resource site references. Resolves nil when no user
         was present (nothing to clear). Public `:rf.scope/global` reads are
         untouched.

         ALSO releases the `session-feed-owner` lease the principal-switch
         re-ensure (`:auth/ensure-session-feed`) may have attached
         — an event-created owner MUST have a matching release path (Spec 016
         §Active owners). The `clear-scope` removes the entry the lease was on,
         so the release is belt-and-braces, but it keeps the owner-lease
         lifecycle a readable attach/release pair (no dangling lease in the
         owner index)."}
  (fn [{:keys [db]} _]
    (let [old-scope (rf/resolve-resource-scope db :realworld/session)]
      {:db (-> db
               (assoc-in [:auth :user] nil)
               (assoc-in [:auth :token] nil))
       :fx (cond-> [[:dispatch [:rf.resource/release-owner
                                {:owner session-feed-owner}]]]
             old-scope (conj [:dispatch [:rf.resource/clear-scope
                                         {:scope old-scope :cause :logout}]]))})))

(rf/reg-event :auth/post-login-redirect
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
   :schemas {:data schema/AuthFlowData}
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
      ;;
      ;; This is the one PRINCIPAL SWITCH with no route change — the
      ;; home route was already entered logged-out (the `{:from-db
      ;; :realworld/session}` feed resolved nil and was not planned), so storing
      ;; the principal now RE-KEYS the feed sub but, being passive, does not
      ;; fetch. Re-ensure the feed under the freshly-stored session scope so
      ;; "Your Feed" actually loads (a no-op cache-hit when the user is not on
      ;; home / when the route already ensured it). The interactive login /
      ;; logout paths re-enter the route and need no explicit ensure.
      (let [user (:user value)]
        {:data {:error nil}
         :fx [[:dispatch [:auth/store-session user]]
              [:realworld-resources.session/persist {:token (:token user)}]
              [:dispatch [:auth/ensure-session-feed]]]}))

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

(rf/reg-event :auth/initialise
  {:rf.cofx/requires [:realworld-resources.session/token]}
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

(rf/reg-event :auth.login-form/initialise
  (fn [{:keys [db]} _] {:db (assoc-in db [:auth :login-form] {:draft login-form-defaults :touched #{}})}))

(rf/reg-event :auth.login-form/edit-field
  {:schema [:cat [:= :auth.login-form/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:auth :login-form :draft field] value)
        (update-in [:auth :login-form :touched] (fnil conj #{}) field))}))

(rf/reg-event :auth.login-form/submit
  (fn [{:keys [db]} _]
    {:fx [[:dispatch [:auth/flow [:auth/login (get-in db [:auth :login-form :draft])]]]]}))

(rf/reg-event :auth.register-form/initialise
  (fn [{:keys [db]} _] {:db (assoc-in db [:auth :register-form] {:draft register-form-defaults :touched #{}})}))

(rf/reg-event :auth.register-form/edit-field
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:auth :register-form :draft field] value)
        (update-in [:auth :register-form :touched] (fnil conj #{}) field))}))

(rf/reg-event :auth.register-form/submit
  (fn [{:keys [db]} _]
    {:fx [[:dispatch [:auth/flow [:auth/register (get-in db [:auth :register-form :draft])]]]]}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :auth/user  (fn [db _] (get-in db [:auth :user])))

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
        [:input {:type "email" :name "email" :data-testid "login-email" :placeholder "Email"
                 :value (:email draft) :disabled submitting?
                 :on-change #(dispatch [:auth.login-form/edit-field :email (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type "password" :name "password" :data-testid "login-password" :placeholder "Password"
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
        [:input {:type "text" :name "username" :placeholder "Username" :value (:username draft) :disabled submitting?
                 :on-change #(dispatch [:auth.register-form/edit-field :username (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type "email" :name "email" :placeholder "Email" :value (:email draft) :disabled submitting?
                 :on-change #(dispatch [:auth.register-form/edit-field :email (.. % -target -value)])}]]
       [:fieldset.form-group
        [:input {:type "password" :name "password" :placeholder "Password" :value (:password draft) :disabled submitting?
                 :on-change #(dispatch [:auth.register-form/edit-field :password (.. % -target -value)])}]]
       [:button {:type "submit" :disabled submitting?}
        (if submitting? "Signing up…" "Sign up")]]]]))
