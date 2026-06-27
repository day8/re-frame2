(ns realworld-resources.auth
  "Authentication for the RealWorld-on-resources example.

   First, a modelling decision worth dwelling on: auth is a command plus a state
   machine, not a cached read. It's tempting to bend login into a read-resource
   so everything's uniform, but resist — login is a one-shot action with a
   lifecycle, not a value you cache and re-read. The POST/GET for login,
   register, and session-restore go through `:rf.http/managed` from the machine's
   actions, and the machine owns the `:idle → :submitting → :authed | :error`
   lifecycle. See the machines guide: ../../../docs/machines/concepts.md. (The one
   auth-adjacent write that IS a mutation is the settings update — it invalidates
   the profile read — and it lives over in realworld-resources.mutations.)

   On logout the machine does two things: clears the session, and clears the
   session-scoped resource cache. The personalised feed is cached under
   `[:rf.scope/session {:username …}]`, and the next user (or no user) must never
   lay eyes on the last one's feed. `:rf.resource/clear-scope` is the causal
   operation for that. The concrete old scope is resolved with the
   `rf/resolve-resource-scope` helper against the handler's COEFFECT db — the
   pre-transition value, which still remembers who's logging out — using the same
   named `:realworld/session` resolver every other site references. One way to
   resolve a scope, teardown included. The public `:rf.scope/global` reads
   (article lists, detail, profiles, tags) are untouched: they're the same for
   everyone, so logout has no reason to disturb them.

   Cold-boot session restore needs one extra nudge, and it's a genuinely subtle
   one. The machine also re-ensures the feed under the freshly-restored principal
   (`:auth/ensure-session-feed`). A `{:from-db :realworld/session}` subscription
   re-keys reactively when the principal changes, but it's passive — re-keying
   doesn't fetch. Restore is the lone principal switch with no accompanying route
   change (the home route was entered while logged out, so the feed was never
   planned), so without an explicit re-ensure the feed would just sit there at
   :idle, quietly doing nothing. The interactive login / logout paths re-enter the
   route, so they don't need this — see the `:auth/ensure-session-feed` event
   below for the full story."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; The state-machine ns. Loading it registers the hooks, so
            ;; rf/reg-machine (below) and the `:rf/machine` framework subs have
            ;; something to resolve against.
            [re-frame.machines]
            [re-frame.resources]
            [realworld-resources.http :as rh]
            [realworld-resources.schema :as schema]
            ;; Pulled in for its side effect: loading it registers the
            ;; `:realworld/session` resource-scope resolver that
            ;; `:auth/clear-session` reaches for via `rf/resolve-resource-scope`
            ;; below.
            [realworld-resources.scope])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; SESSION PERSISTENCE SEAM
;; ============================================================================

;; Another conformance surface. The official RealWorld browser/E2E suite reads the
;; session from `localStorage["jwtToken"]` — that exact key IS the contract, so we
;; use it verbatim rather than namespacing it under `conduit-resources/…` as we
;; otherwise would. One caveat, since it can bite you locally: the contract
;; assumes one RealWorld app per origin. The repo's dev orchestrator serves both
;; variants from a single origin (`/realworld/` and `/realworld-resources/`), so
;; the two conforming apps share — and cheerfully clobber — each other's
;; `jwtToken`. That's a dev-mode artifact, not a contract violation: conformance
;; is validated against standalone serving (one app per origin), which is what the
;; external suite actually does. See the README §RealWorld contract conformance.
(rf/reg-fx :realworld-resources.session/persist
  {:doc       "Persist (or clear) the JWT in localStorage under the official
               contract key `jwtToken`. `{:token t}` writes a truthy token; a
               nil token removes the key."
   :platforms #{:client}}
  (fn [_m {:keys [token]}]
    (when-let [ls (.-localStorage js/globalThis)]
      (if token
        (.setItem ls "jwtToken" token)
        (.removeItem ls "jwtToken")))))

;; The saved JWT is a fact from the storage world, and `:auth/initialise` folds it
;; into durable app-db (which is what drives session restore). Here's the rule
;; that shapes the next few forms: a durable write has to fold a RECORDED fact,
;; never an ambient `localStorage` read taken at the write site — because replay
;; or epoch-restore could never reproduce that ambient read. So the JWT read is a
;; recordable generator coeffect. `:realworld-resources.session/token` is a
;; `:recordable? true` registration whose supplier reads localStorage; the
;; generator runs at processing-start on the boot dispatch, its value is recorded
;; onto the causal token, and replay / epoch-restore later re-presents that exact
;; captured token rather than re-reading whatever localStorage happens to hold
;; now. See recordable vs ambient coeffects:
;; ../../../docs/guide/glossary.md#recordable-vs-ambient-coeffects.
(defn read-jwt-from-storage
  "Read the saved JWT from localStorage, or nil — the supplier body behind the
   `:realworld-resources.session/token` recordable generator. nil when the token
   is absent or storage is unavailable."
  []
  (some-> (.-localStorage js/globalThis)
          (.getItem "jwtToken")))

(rf/reg-cofx :realworld-resources.session/token
  {:recordable? true
   :doc "Recordable generator coeffect: the saved JWT (or nil), read from
         localStorage. The registered supplier runs at processing-start on the
         boot dispatch; its value is recorded onto the causal token and
         re-presented verbatim under replay / epoch-restore. So the durable write
         that folds it (`:auth/initialise` → [:auth :token]) replays the captured
         token, never a fresh localStorage read. A handler folds it by declaring
         `:rf.cofx/requires [:realworld-resources.session/token]` and reading it
         flat. A production dispatch carries no cofx at all — the generator is the
         source; tests pin an exact value through the dispatch-site `:rf.cofx`
         stub seam (`{:rf.cofx {:realworld-resources.session/token \"…\"}}`)."}
  (fn [] (read-jwt-from-storage)))

;; ============================================================================
;; SESSION SUPPORT EVENTS
;; ============================================================================

(rf/reg-event :auth/store-session
  {:doc "Store the authenticated session. The JWT gets exactly one durable home:
         the classified `[:auth :token]` path (declared by `:auth/classify-token`
         in core.cljs). The User payload lands at `[:auth :user]` with its `:token`
         field stripped off (`dissoc`), so the JWT is not quietly duplicated into
         the unclassified `[:auth :user :token]` slot. That matters: a second
         durable copy there would ship raw to every off-box record, because
         classification doesn't propagate — each path declares its own. Views and
         subs read `:auth/user` for identity (username, bio, image) and none of
         them want the token anyway; the bearer-auth interceptor reads it from the
         classified `[:auth :token]` path."}
  (fn [{:keys [db]} [_ user]]
    {:db (-> db
        (assoc-in [:auth :user] (dissoc user :token))
        (assoc-in [:auth :token] (:token user)))}))

;; ----------------------------------------------------------------------------
;; PRINCIPAL-SWITCH RE-ENSURE  (a subtle footgun, defused)
;; ----------------------------------------------------------------------------
;;
;; Here's a trap worth understanding, because it's the kind of thing that looks
;; like a bug at 2am. A `{:from-db :realworld/session}` resource subscription
;; re-keys reactively when its resolver input (the authenticated `:username`)
;; changes. But re-keying does NOT fetch — subscriptions are passive. The new
;; scope's data only loads when a CAUSE ensures it: a route entry, an event-side
;; `:rf.resource/ensure`, a clear-scope. So if you switch principal by an app-db
;; write alone, with no route change, the feed sub dutifully re-keys to the new
;; user and then just... waits, at :idle, forever. Safe and fail-closed, but
;; mystifying if you didn't expect it.
;;
;; There's exactly one place this app switches principal without a route change:
;; cold-boot session restore. The home route is entered while logged out (the
;; `{:from-db :realworld/session}` feed resolves nil and isn't planned), then the
;; async `GET /user` lands and `:restore-session` writes the principal — on
;; purpose without navigating, because a deep link has to survive a refresh. The
;; interactive login / logout paths DO navigate, so the route plan re-ensures the
;; feed for them. Restore is the one gap, so restore patches it.
;;
;; It patches it with an explicit `:rf.resource/ensure` of the feed under the new
;; session scope (the `{:from-db :realworld/session}` resolves itself against the
;; post-restore app-db). The ensure rides an app-minted lease, `session-feed-owner`,
;; so the feed stays alive for as long as you're signed in — the logged-out route
;; entry left no route owner to lean on. Logout then releases that lease alongside
;; its `clear-scope`, because an event-created owner MUST have a matching release
;; path (see owner & cause, ../../../docs/resources/glossary.md#owner--cause). The
;; `:page` is read from the live route slice so the ensure lands on the SAME cache
;; key the home route and feed subscription use. And if you're logged out (a
;; restore that failed), the reference resolves nil and the runtime fail-closes —
;; no feed to load, nothing to do.

(def session-feed-owner
  "The app-minted liveness lease the principal-switch re-ensure pins on the
   session feed (a `[:lease …]` owner). Stable across switches, released on
   logout. See owner & cause: ../../../docs/resources/glossary.md#owner--cause."
  [:lease :auth/session-feed])

(rf/reg-event :auth/ensure-session-feed
  {:doc "Re-ensure the session feed under the current principal, so a principal
         switch with no route change (cold-boot session restore) actually loads
         it — the `{:from-db :realworld/session}` re-key on its own is passive and
         won't fetch. It resolves the scope from the post-restore app-db via the
         resource's own `{:from-db :realworld/session}` policy, reads `:page` from
         the live route slice so the ensure hits the SAME cache key the home route
         and feed subscription use, and pins the stable `session-feed-owner` lease
         (released later by `:auth/clear-session`). If the route already ensured a
         fresh `:loaded` entry, this is a cache-hit; logged out, it fail-closes."}
  (fn [{rt :rf.db/runtime} _]
    ;; Default to page 1, so the ensure hits the SAME `{:page 1}` key the home
    ;; route and feed subscription use on the canonical no-`?page=` URL. A raw nil
    ;; would mint an orphan `{:page nil}` entry that nobody reads.
    (let [page (or (get-in rt [:rf.runtime/routing :current :query :page]) 1)]
      {:fx [[:dispatch [:rf.resource/ensure
                        {:resource :realworld/feed
                         :params   {:page page}
                         :owner    session-feed-owner
                         :cause    [:principal-switch :realworld/feed]}]]]})))

(rf/reg-event :auth/clear-session
  {:doc "Clear the auth slice AND drop the session-scoped resource cache. The
         personalised feed lives under the session scope, and logout has to clear
         it (`:rf.resource/clear-scope`) so the next user can't read it. The
         concrete old scope is resolved with the pure `rf/resolve-resource-scope`
         helper against the COEFFECT db — the pre-transition value, which by
         definition still carries the user who's logging out — using the named
         `:realworld/session` resolver every site shares. It resolves nil when
         there was no user (nothing to clear). The public `:rf.scope/global` reads
         are left alone.

         It also releases the `session-feed-owner` lease that the principal-switch
         re-ensure (`:auth/ensure-session-feed`) may have pinned, because an
         event-created owner MUST have a matching release path. The `clear-scope`
         already removes the entry the lease sat on, so the release is mostly about
         tidiness — it keeps the lease lifecycle a readable attach/release pair,
         with no dangling owner left in the index."}
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
  {:doc "Bounce a user who just interactively logged in or registered to wherever
         the auth guard intercepted them (`[:auth :return-to]`), or home if there's
         nothing stashed. Dispatched only by the `:store-session` action (the
         interactive `:submitting → :authed` transition) — never by
         `:restore-session`, since cold-boot restore keeps you where you are and
         must not force a navigation. Machine actions can't navigate or read `:db`
         directly, so this is an ordinary event. It reads and clears the slot in
         one move, so a later login can't accidentally bounce you again."}
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
  {:doc "The auth flow: idle → submitting/restoring → authed | error. HTTP goes
         through :rf.http/managed. Login, register, and restore don't retry —
         one submission per click, by design."
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
      ;; Interactive login / register success: store the session, persist the JWT,
      ;; and bounce to the guard-stashed `:return-to` (or home). The bounce is the
      ;; right move only for an interactive submit — the user just clicked Sign-in
      ;; and is expecting to land somewhere. Machine actions neither see nor emit
      ;; `:db`, so the session store and the bounce-back both run as ordinary
      ;; events.
      (let [user (:user value)]
        {:data {:error nil}
         :fx [[:dispatch [:auth/store-session user]]
              [:realworld-resources.session/persist {:token (:token user)}]
              [:dispatch [:auth/post-login-redirect]]]}))

    :restore-session
    (fn [{[_ {:keys [value]}] :event}]
      ;; Session-restore success (cold boot with a saved JWT): store the session,
      ;; but do NOT navigate. Someone cold-booting on a deep link (`/article/x`)
      ;; needs to stay put — restore re-hydrates the session and nothing more; it
      ;; must never yank them home. Only an interactive login/register bounces
      ;; (`:store-session`). The token is already in app-db and localStorage from
      ;; `:auth/initialise`; we re-persist it defensively, just in case the server
      ;; rotated it on `GET /user`.
      ;;
      ;; This is the one principal switch with no route change. The home route was
      ;; entered while logged out (the `{:from-db :realworld/session}` feed
      ;; resolved nil and wasn't planned), so storing the principal now re-keys the
      ;; feed sub — but, being passive, it won't fetch. So we re-ensure the feed
      ;; under the freshly-stored session scope, which is what makes 'Your Feed'
      ;; actually load. It's a harmless cache-hit when you're not on home, or when
      ;; the route already ensured it. The interactive login / logout paths re-enter
      ;; the route, so they need no explicit ensure.
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
    ;; Fire `:auth/restore` unconditionally — even with a nil token — so the
    ;; machine snapshot spawns at `:idle` from a cold boot. The `:has-token?`
    ;; guard then quietly routes a blank token to the no-op branch.
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
