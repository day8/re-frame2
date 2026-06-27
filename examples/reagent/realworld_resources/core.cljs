(ns realworld-resources.core
  "Entry point for the RealWorld (Conduit) example — a full Medium-style blogging
   app, built to show off how re-frame2 handles server state.

   Two ideas carry the whole app. Reads are RESOURCES (`reg-resource`): named,
   cached server-state the framework owns and your views read passively. Writes
   are MUTATIONS (`reg-mutation`): they change remote state and invalidate the
   reads they touched, so the right things refetch on their own. Read here,
   write there, and the cache keeps itself honest. The resources guide has the
   full story: ../../../docs/resources/concepts.md.

   This namespace is the wiring closet. It:
   - pulls in every feature ns (each one registers its own resources / mutations
     / events / subs / views / the auth machine);
   - installs a demo `:rf.http/managed` backend stub — since reads and writes
     both lower onto managed HTTP, one stub serves the entire API;
   - defines `:app/initialise`, the boot fan-out;
   - defines the app shell and a route switch on `:rf.route/id`;
   - mounts the React root and installs the URL listener.

   The actual features live next door:
     resources.cljs  — every read, as a `reg-resource`
     mutations.cljs  — every write, as a `reg-mutation` (+ :invalidates / :populates)
     routing.cljs    — routes carrying `:resources` metadata + the auth guard
     auth.cljs       — the auth machine (login / register / restore / logout)
     settings.cljs   — settings, modelled as a mutation instance
     scope.cljs      — the fail-closed session cache scope
     schema.cljs     — Malli wire shapes + the handful of app-db schemas
     http.cljs       — the demo backend stub + failure projection
     views.cljs      — passive pages + the small UI event glue"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Managed HTTP — the one built-in transport both resources and
            ;; mutations lower onto.
            [re-frame.http.managed]
            ;; The canned-stub fxs the demo backend leans on. A real Conduit
            ;; deployment would simply drop this require.
            [re-frame.http.test-support]
            ;; The resources + mutations runtime.
            [re-frame.resources]
            ;; Routing + machines. Loading routing unlocks the `:resources` route
            ;; key; the auth machine needs machines.
            [re-frame.routing]
            [re-frame.machines]
            [re-frame.adapter.reagent :as reagent-adapter]
            [realworld-shared.avatar :as avatar]
            [realworld-resources.schema]
            [realworld-resources.scope]
            [realworld-resources.http :as rh]
            [realworld-resources.resources]
            [realworld-resources.mutations]
            [realworld-resources.routing :as routing]
            [realworld-resources.auth :as auth]
            [realworld-resources.settings :as settings]
            [realworld-resources.article-editor :as editor]
            [realworld-resources.views :as views])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event :app/initialise
  {:doc "App boot. Seeds the two form drafts and nothing else. Session restore
         is deliberately not here — it's its own `:initial-events` step
         (`:auth/initialise`) so its recordable token coeffect can ride its own
         dispatch. And the page reads (articles, tags, feed, …) aren't here
         either: the route's `:resources` metadata pulls those in on the first
         URL→route sync. Booting an app is mostly about deciding what NOT to do
         up front."}
  (fn [_ _]
    {:fx [[:dispatch [:auth.login-form/initialise]]
          [:dispatch [:auth.register-form/initialise]]]}))

;; The JWT at [:auth :token] is both durable and sensitive — exactly the kind of
;; thing you don't want leaking off the box. The `:sensitive` effect marks it as
;; such. We return it alongside `:db` from an event the frame runs at creation
;; (`:initial-events`, before anything leaves the box), so the raw token is
;; redacted from any capture, SSR payload, or trace. See data classification:
;; ../../../docs/guide/glossary.md#data-classification.
(rf/reg-event :auth/classify-token
  {:doc "Marks the durable JWT path [:auth :token] as sensitive, at frame
         creation."}
  (fn [{:keys [db]} _]
    {:db db :sensitive [[:auth :token]]}))

;; ============================================================================
;; APP-SHELL VIEWS
;; ============================================================================

(reg-view header []
  (let [authed? @(subscribe [:auth/authenticated?])
        user    @(subscribe [:auth/user])]
    [:nav.navbar.navbar-light
     [:div.container
      [rf/route-link {:to :realworld/home :class "navbar-brand"} "conduit"]
      [:ul.nav.navbar-nav.pull-xs-right
       [:li.nav-item [rf/route-link {:to :realworld/home :class "nav-link"} "Home"]]
       (if authed?
         [:<>
          [:li.nav-item [rf/route-link {:to :realworld.editor/new :class "nav-link" :data-testid "nav-new-article"}
                         [:i.ion-compose] " New Article"]]
          [:li.nav-item [rf/route-link {:to :realworld.user/settings :class "nav-link" :data-testid "nav-settings"}
                         [:i.ion-gear-a] " Settings"]]
          [:li.nav-item [rf/route-link {:to :realworld.profile/show :params {:username (:username user)}
                                        :class "nav-link" :data-testid "nav-username"}
                         ;; The Conduit navbar shows the user's avatar
                         ;; (`.user-pic`) next to their name.
                         [:img.user-pic {:src (avatar/avatar-src (:image user))}]
                         " " (:username user)]]
          [:li.nav-item [:a.nav-link {:data-testid "nav-logout" :href "#"
                                      :on-click #(do (.preventDefault %) (dispatch [:auth/flow [:auth/logout]]))}
                         "Logout"]]]
         [:<>
          [:li.nav-item [rf/route-link {:to :realworld.auth/login :class "nav-link" :data-testid "nav-signin"} "Sign in"]]
          [:li.nav-item [rf/route-link {:to :realworld.auth/register :class "nav-link" :data-testid "nav-signup"} "Sign up"]]])]]]))

(reg-view footer []
  [:footer [:div.container
            [rf/route-link {:to :realworld/home :class "logo-font"} "conduit"]
            [:span.attribution "An interactive learning project from Thinkster."
             " Code & design licensed under MIT."]]])

(reg-view ^{:doc "The 'you have unsaved changes' dialog. It appears when a
                   `:can-leave` guard blocks a navigation — typically the editor
                   refusing to throw away a dirty draft. It reads the blocked
                   navigation off the `:rf/pending-navigation` sub; the buttons
                   either wave the navigation through or call it off. See route
                   guard: ../../../docs/routing/glossary.md#route-guard."}
          pending-nav-dialog []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.pending-nav-overlay {:data-testid "pending-nav-dialog"}
     [:div.pending-nav-dialog
      [:p (or (:reason pending) "You have unsaved changes. Leave anyway?")]
      [:button {:data-testid "pending-nav-discard"
                :on-click #(dispatch [:rf.route/continue (:id pending)])}
       "Discard changes"]
      [:button {:data-testid "pending-nav-stay"
                :on-click #(dispatch [:rf.route/cancel (:id pending)])}
       "Stay"]]]))

(reg-view not-found-page []
  [:div.not-found-page [:h1 "Page not found"] [rf/route-link {:to :realworld/home} "Home"]])

(reg-view root-view []
  [:div.app
   [header]
   [pending-nav-dialog]
   (case @(subscribe [:rf.route/id])
     :realworld/home          [views/home-page]
     :realworld/home-tag      [views/home-page]
     :realworld.auth/login    [auth/login-page]
     :realworld.auth/register [auth/register-page]
     :realworld.article/show  [views/article-page]
     :realworld.editor/new    [editor/editor-page]
     :realworld.editor/edit   [editor/editor-page]
     :realworld.profile/show      [views/profile-page]
     :realworld.profile/favorites [views/profile-page]
     :realworld.user/settings [settings/settings-page]
     [not-found-page])
   [footer]])

;; ============================================================================
;; BEARER-AUTH HTTP INTERCEPTOR
;; ============================================================================
;;
;; The classic auth chore: every authenticated request needs an
;; `Authorization: Token <jwt>` header. The tedious way is to thread the token
;; through every call site. The pleasant way is right here — one `:before` fn
;; that reads the token from the auth slice and stamps the header on its way out.
;;
;; Because resources and mutations both lower onto `:rf.http/managed`, this one
;; interceptor decorates the entire Conduit API at a stroke: the route-caused
;; reads (`/articles/feed`, the lists, detail), the writes (favorite / follow /
;; comment / settings / save-article), and the auth machine's session-restore
;; `GET /user`. The auth slice is the single source of truth, and this is the
;; single place it's read. Nice when one fact lives in exactly one spot.
;;
;; The token is read from `(:frame ctx)` — the frame this cascade is running
;; under — rather than a hard-coded id, so the header follows along to a
;; non-default or multi-frame mount. No token present? The interceptor hands ctx
;; straight back, so login, register, and the public logged-out reads sail
;; through untouched.
(defn bearer-auth-interceptor [ctx]
  (let [token (some-> (rf/app-db-value (:frame ctx))
                      :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))

;; ============================================================================
;; window.__conduit_debug__  — CONFORMANCE-CONTRACT SURFACE
;; ============================================================================
;;
;; The official RealWorld browser/E2E harness sometimes peeks at session state
;; through a global accessor, so we install `window.__conduit_debug__` with
;; `getToken` / `getAuthState` / `getCurrentUser`, each reading the live app
;; frame's app-db.
;;
;; A loud warning, because this is the sort of code that gets copy-pasted: this
;; is a conformance surface, NOT a re-frame2 pattern. A global token accessor
;; like this is precisely the anti-pattern you don't want in a real app — it
;; sidesteps the frame/sub system and hands the raw JWT to any script on the
;; page. Real re-frame2 code reads session state through subs, under the frame
;; provider, never off a `window.*` global. This exists only so the external test
;; suite can introspect the demo; a production Conduit app would never ship it.
(defn- install-conduit-debug! [frame-id]
  (when (exists? js/window)
    (set! (.-__conduit_debug__ js/window)
          #js {:getToken       (fn [] (some-> (rf/app-db-value frame-id) :auth :token))
               :getAuthState   (fn [] (name (if (some-> (rf/app-db-value frame-id) :auth :user)
                                              :authed :anonymous)))
               :getCurrentUser (fn [] (clj->js (some-> (rf/app-db-value frame-id) :auth :user)))})))

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================

(defonce react-root (atom nil))

(def app-frame :rf/default)

;; `:rf/default` is just a frame id. It carries no special framework privilege —
;; `init!` installs the adapter and creates no frame at all. This app earns URL
;; ownership the honest way: by declaring `:url-bound? true` on the frame below.
;;
;; The frame is created, configured, and seeded in exactly one place: the
;; `frame-provider {:id …}` ensure form at the render root. On first mount it
;; creates the frame under `app-frame` and applies the config — `:url-bound? true`
;; so it owns the URL, the auth-guard interceptor referenced by id, and the demo
;; `:rf.http/managed` routed through the in-process backend stub so reads and
;; writes work with no network in sight — then runs `:initial-events` once. A hot
;; reload reuses the frame and skips the re-seed (durable app-db survives). That
;; same id is what every in-tree `dispatch` / `subscribe` resolves against.
;;
;; The order of `:initial-events` matters, for two reasons:
;;   - `:auth/classify-token` goes first. The JWT at [:auth :token] is a durable
;;     secret, so we mark it sensitive before anything (an Xray capture, an SSR
;;     payload) could lay eyes on the raw value. See data classification:
;;     ../../../docs/guide/glossary.md#data-classification.
;;   - `:auth/initialise` runs before `:app/initialise`. Session restore reads
;;     the saved JWT from a recordable coeffect (auth.cljs) and folds it into
;;     durable [:auth :token]. It rides its own dispatch — the `:app/initialise`
;;     fan-out doesn't forward `:rf.cofx` — and goes first, so the token is in
;;     app-db before the bearer-auth interceptor fires its first authenticated
;;     request.
;;
;; Two things we deliberately DON'T classify, since under-redacting and
;; over-redacting are both mistakes. The outbound `Authorization` Bearer header
;; is already redacted off-box by the framework's built-in HTTP carrier denylist,
;; so doing it again here would be belt-and-braces noise. And the session-scope
;; key ([:auth :user :username]) is identity, not a secret — redacting it would
;; only blur the cache-leak boundary this whole example is here to show.
(defn run []
  (rf/init! reagent-adapter/adapter)
  ;; Register the bearer-auth interceptor at boot, before the frame's
  ;; `:initial-events` dispatch. Timing matters: session-restore fires an
  ;; authenticated `GET /user` the moment the JWT is hydrated, so the header has
  ;; to be wired up before `:auth/initialise` runs at frame creation (below).
  (rf/reg-http-interceptor :realworld/bearer-auth
                           {:before bearer-auth-interceptor})
  ;; The dev orchestrator serves this example under /realworld-resources/. Strip
  ;; that prefix before the matcher sees the URL, so :realworld/home (path "/")
  ;; still matches. Has to happen before install-router!'s first URL→route sync.
  (routing/set-base-path! "/realworld-resources")
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; Create + configure + seed the app frame, all in one place (see the block
    ;; above). The frame is created synchronously during render, so by the time
    ;; this call returns it's live and seeded.
    (rdc/render @react-root
                [rf/frame-provider {:id              app-frame
                                    :doc             "RealWorld-on-resources demo frame."
                                    :url-bound?      true
                                    :interceptors    [:realworld-resources.routing/auth-guard]
                                    :fx-overrides    {:rf.http/managed :realworld-resources.demo/http-stub}
                                    :initial-events  [[:auth/classify-token]
                                                      [:auth/initialise]
                                                      [:app/initialise]]}
                 [root-view]])
    ;; The frame is now live and session-seeded. The next three calls install
    ;; ongoing listeners, each doing an initial sync against that frame — so they
    ;; have to run AFTER the render that created it, never before.
    ;;
    ;; install-router! wires up the popstate handler and does the first URL→route
    ;; sync (under the URL-owning frame). That sync fires the route's `:resources`
    ;; ensures, which is the heart of the trick: server-state loads because the
    ;; route became active, not because a view went looking for it. The session
    ;; token seeded above is already in app-db, so the bearer-auth interceptor
    ;; decorates those reads on the way out.
    (routing/install-router!)
    ;; Focus/reconnect revalidation: when the tab comes back or the network
    ;; reconnects, refetch the reads that are both active and stale. Idempotent,
    ;; and you never dispatch the host signals by hand.
    (rf/install-revalidation-listeners! app-frame)
    ;; The conformance surface — NOT a re-frame2 pattern; see install-conduit-debug!
    ;; above. The external RealWorld suite may read it.
    (install-conduit-debug! app-frame)))
