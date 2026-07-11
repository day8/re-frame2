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
   principal-scoped resource caches. The departing user's personalised feed is
   cached under `[:rf.scope/session {:username …}]` and their optional-auth reads
   (articles / profiles / comments, carrying that user's `favorited` / `following`
   flags) under `[:rf.scope/viewer {:username …}]`; the next user (or no user)
   must never lay eyes on either. `:rf.resource/clear-scope` is the causal
   operation for both. The two concrete old scopes are resolved with the
   `rf/resolve-resource-scope` helper against the handler's COEFFECT db — the
   pre-transition value, which still remembers who's logging out — using the same
   named `:realworld/viewer` / `:realworld/session` resolvers every other site
   references. One way to resolve a scope, teardown included. The lone truly-
   invariant read (popular tags, `:rf.scope/global`) is untouched — the same for
   everyone, so logout has no reason to disturb it.

   Cold-boot session restore needs one extra nudge, and it's a genuinely subtle
   one. Restore is the lone principal switch with no accompanying route change —
   the route was entered while the viewer was still UNRESOLVED (a saved token was
   present but the user had not restored yet), so its `{:from-db …}` reads fail
   closed and are never planned. A `{:from-db …}` subscription re-keys reactively
   once the principal resolves, but re-keying is PASSIVE — it doesn't fetch. So on
   BOTH restore outcomes — success (now signed in) and failure (now confirmed
   anonymous) — the machine re-ensures the CURRENT route's reads under the freshly
   resolved viewer with `:auth/ensure-viewer-route`, WITHOUT navigating, so a deep
   link stays put and simply loads its reads under the right identity. The
   interactive login / logout paths re-enter a route, so they re-plan for free and
   don't need this — see `:auth/ensure-viewer-route` below for the full story."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; The state-machine ns. Loading it registers the hooks, so
            ;; rf/reg-machine (below) and the `:rf/machine` framework subs have
            ;; something to resolve against.
            [re-frame.machines]
            [re-frame.resources]
            [realworld-resources.http :as rh]
            ;; Wire contract (UserResponse) from the shared ns; the machine's
            ;; own snapshot `:data` schema (AuthFlowData) is app-local.
            [realworld-shared.schema :as schema]
            [realworld-resources.schema :as app-schema]
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
;; ../../../docs/core/glossary.md#recordable-vs-ambient-coeffects.
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
;; like a bug at 2am. A `{:from-db …}` resource subscription re-keys reactively
;; when its resolver input (the viewer) changes. But re-keying does NOT fetch —
;; subscriptions are passive. The new scope's data only loads when a CAUSE ensures
;; it: a route entry, an event-side `:rf.resource/ensure`, a clear-scope. So if
;; you switch principal by an app-db write alone, with no route change, every
;; viewer-scoped read dutifully re-keys to the new principal and then just...
;; waits, at :idle, forever. Safe and fail-closed, but mystifying if you didn't
;; expect it.
;;
;; There's exactly one place this app switches principal without a route change:
;; cold-boot session restore. The route is entered while the viewer is UNRESOLVED
;; (a saved token is present but the user hasn't restored — the `:realworld/viewer`
;; resolver returns nil, and the `:realworld/session` feed too), so those reads
;; fail closed at route entry and are never planned. Then the async `GET /user`
;; lands and `:restore-session` writes the principal — on purpose WITHOUT
;; navigating, because a deep link has to survive a refresh — or `:auth/restore-
;; failed` clears the token, confirming an anonymous viewer. Either way the viewer
;; is now resolved but nothing has fetched. The interactive login / logout paths
;; DO navigate, so the route plan re-ensures for them; restore is the one gap.
;;
;; So restore patches it by RE-RUNNING the current route's own resource plan. It
;; reads the live route slice + the route's declared `:resources` metadata (the
;; SAME declarative source `routing.cljs` planned on entry) and re-ensures each
;; entry under the current route owner `[:route route-id nav-token]` — so the
;; reads land on the exact cache keys and owner the route would have used, and the
;; eventual route-leave releases them with no app-minted lease to track. One
;; generalised re-ensure covers every viewer-scoped read AND the session feed on
;; whatever route the deep link landed on.

(rf/reg-event :auth/ensure-viewer-route
  {:doc "Re-ensure the CURRENT route's declared `:resources` under the freshly
         resolved viewer — the principal-switch re-plan for the ONE switch that
         carries no route change: cold-boot session restore (success OR failure).
         A `{:from-db …}` subscription re-keys reactively when the principal
         resolves, but re-keying is PASSIVE — it never fetches, so the reads that
         failed closed at route entry (viewer unresolved) would sit at :idle
         forever without this.

         It reads the live route slice + the route's own `:resources` metadata
         (`rf/handler-meta` — the same declarative source `routing.cljs` planned
         on entry), evaluates each entry's `:when` + `:params` against the route,
         and ensures it under the current route owner `[:route route-id nav-token]`
         — so the reads land on the SAME cache keys and owner the route used, and
         route-leave releases them with no app-minted lease. A resource with no
         route `:scope` resolves its spec policy ({:from-db :realworld/viewer} for
         the six optional-auth reads); the feed carries its own
         {:from-db :realworld/session} entry scope. A route with no `:resources`
         (login / register) is a clean no-op."}
  (fn [{rt :rf.db/runtime} _]
    (let [{:keys [route-id params query nav-token]} (get-in rt [:rf.runtime/routing :current])
          route     {:id route-id :params params :query query}
          resources (:resources (rf/handler-meta :route route-id))
          owner     [:route route-id nav-token]]
      {:fx (into []
                 (comp
                  ;; honour each entry's `:when` guard, exactly as route planning does.
                  (filter (fn [{when-fn :when}]
                            (or (nil? when-fn) (boolean (when-fn route nil)))))
                  (map (fn [{:keys [resource params scope]}]
                         [:dispatch [:rf.resource/ensure
                                     (cond-> {:resource resource
                                              :params   (if params (params route) {})
                                              :owner    owner
                                              :cause    [:principal-switch resource]}
                                       ;; feed carries an explicit entry :scope; the
                                       ;; viewer reads resolve their spec policy.
                                       scope (assoc :scope scope))]])))
                 resources)})))

(rf/reg-event :auth/clear-session
  {:doc "Clear the auth slice AND drop the departing principal's scoped resource
         caches. Both the personalised feed (session scope) and the optional-auth
         reads (viewer scope — articles / profiles / comments carrying this user's
         `favorited` / `following` flags) are keyed to the logging-out user, so
         logout clears BOTH (`:rf.resource/clear-scope`) — the next user can never
         read a stale entry of theirs even before GC reclaims it. The two concrete
         old scopes are resolved with the pure `rf/resolve-resource-scope` helper
         against the COEFFECT db — the pre-transition value, which by definition
         still carries the user who's logging out — using the named
         `:realworld/session` / `:realworld/viewer` resolvers every site shares.
         Each resolves nil when there is nothing to clear (no user, or an
         already-unresolved viewer), and the clear is skipped. The truly-invariant
         `:rf.scope/global` popular-tags read is left alone."}
  (fn [{:keys [db]} _]
    (let [old-session (rf/resolve-resource-scope db :realworld/session)
          old-viewer  (rf/resolve-resource-scope db :realworld/viewer)]
      {:db (-> db
               (assoc-in [:auth :user] nil)
               (assoc-in [:auth :token] nil))
       :fx (cond-> []
             old-session (conj [:dispatch [:rf.resource/clear-scope
                                           {:scope old-session :cause :logout}]])
             old-viewer  (conj [:dispatch [:rf.resource/clear-scope
                                           {:scope old-viewer :cause :logout}]]))})))

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
   :schemas {:data app-schema/AuthFlowData}
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
      ;; This is the one principal switch with no route change. The route was
      ;; entered while the viewer was UNRESOLVED (token present, user pending), so
      ;; every `{:from-db …}` read failed closed and wasn't planned. Storing the
      ;; principal now RESOLVES the viewer and re-keys those subs — but, being
      ;; passive, they won't fetch. So `:auth/ensure-viewer-route` re-plans the
      ;; current route's reads under the freshly-signed-in viewer, which is what
      ;; makes the page (and 'Your Feed') actually load. Harmless cache-hits when a
      ;; route already ensured a read. The interactive login / logout paths re-enter
      ;; the route, so they need no explicit ensure.
      (let [user (:user value)]
        {:data {:error nil}
         :fx [[:dispatch [:auth/store-session user]]
              [:realworld-resources.session/persist {:token (:token user)}]
              [:dispatch [:auth/ensure-viewer-route]]]}))

    :abandon-restore
    (fn [_]
      ;; Session-restore FAILURE (the saved JWT was rejected — expired / revoked):
      ;; clear the now-invalid session and persisted token, then STAY PUT. A
      ;; cold-boot deep link to a PUBLIC page (`/article/x`) should remain readable
      ;; as an anonymous viewer, not get yanked home — that's `:clear-session`'s
      ;; job on an interactive logout, not on a failed restore. Clearing the token
      ;; RESOLVES the viewer to `[:rf.scope/viewer :anonymous]`, so `:auth/ensure-
      ;; viewer-route` re-plans the current route's reads under the anonymous
      ;; viewer — the mirror of `:restore-session`, minus the sign-in.
      {:data {:error nil}
       :fx [[:dispatch [:auth/clear-session]]
            [:realworld-resources.session/persist {:token nil}]
            [:dispatch [:auth/ensure-viewer-route]]]})

    :record-error
    ;; The appended HTTP reply is the canonical envelope; the classified
    ;; `:rf.http/*` failure map rides under its `:error` key.
    (fn [{[_ {:keys [error]}] :event}]
      {:data {:error (rh/failure->message error)}})

    :clear-session
    ;; Interactive LOGOUT (from :authed). Clear the session + persisted token and
    ;; navigate home — the deliberate act of signing out lands you on the public
    ;; home page, whose route entry re-plans every read under the now-anonymous
    ;; viewer. (A FAILED restore uses `:abandon-restore` instead: it stays put.)
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
          :auth/restore-failed {:target :idle   :action :abandon-restore}}}
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

(rf/reg-sub :auth/viewer-resolving?
  {:doc "True during the ONE window where the viewer is genuinely unknown: a saved
         token is present but the durable user has not restored yet (cold-boot
         session restore in flight). While this holds, the `:realworld/viewer`
         scope resolver is fail-closed (nil), so a viewer-scoped read subscription
         would raise `scope unresolved`. The app shell (core.cljs) reads this to
         defer the route page and show a brief 'restoring session' state instead,
         until `:restore-session` / `:abandon-restore` resolve the viewer and
         re-ensure the route's reads. Mirrors exactly the resolver's nil branch."}
  (fn [db _]
    (and (nil? (get-in db [:auth :user]))
         (not (str/blank? (get-in db [:auth :token]))))))

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
