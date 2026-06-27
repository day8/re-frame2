(ns realworld-resources.core
  "Entry point for the RealWorld (Conduit) example. The read surface is
   RESOURCES (`reg-resource`) and the write surface is MUTATIONS
   (`reg-mutation`): the framework owns the server-state cache, views read it
   passively, and a write invalidates the reads it affected. See the resources
   guide: ../../../docs/resources/concepts.md.

   Wires the app together:
   - pulls in every feature ns (each registers its resources / mutations /
     events / subs / views / the auth machine);
   - installs the demo `:rf.http/managed` backend stub (resources + mutations
     lower onto managed HTTP, so one stub serves the whole API);
   - defines `:app/initialise` (the boot fan-out);
   - defines the app shell + a route switch on `:rf.route/id`;
   - mounts the React root and installs the URL listener.

   Per-feature work lives in:
     resources.cljs  — every read as `reg-resource`
     mutations.cljs  — every write as `reg-mutation` (+ :invalidates / :populates)
     routing.cljs    — routes with `:resources` metadata + the auth guard
     auth.cljs       — the auth machine (login / register / restore / logout)
     settings.cljs   — settings as a mutation instance
     scope.cljs      — the fail-closed session cache scope
     schema.cljs     — Malli wire shapes + the small app-db schemas
     http.cljs       — the demo backend stub + failure projection
     views.cljs      — passive pages + the small UI event glue"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Managed HTTP — the single built-in resource/mutation transport.
            [re-frame.http.managed]
            ;; The canned-stub fxs the demo backend delegates to. A real Conduit
            ;; deployment would drop this require.
            [re-frame.http.test-support]
            ;; Resources + mutations runtime.
            [re-frame.resources]
            ;; Routing + machines. Loading routing makes the `:resources` route
            ;; key available; the auth machine needs machines.
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
  {:doc "App boot. Seeds the form drafts. Session restore is its own
         `:initial-events` step (`:auth/initialise`), not part of this fan-out,
         so its recordable token coeffect rides its own dispatch. The page reads
         (articles, tags, feed, …) are caused by the route's `:resources`
         metadata on the initial URL→route sync, not from here."}
  (fn [_ _]
    {:fx [[:dispatch [:auth.login-form/initialise]]
          [:dispatch [:auth.register-form/initialise]]]}))

;; The JWT at [:auth :token] is durable and sensitive. Classify it via the
;; `:sensitive` effect, returned alongside `:db` from an event the frame runs at
;; creation (`:initial-events`, before any off-box egress) so the raw token is
;; redacted from anything that leaves the box. See data classification:
;; ../../../docs/guide/glossary.md#data-classification.
(rf/reg-event :auth/classify-token
  {:doc "Classifies the durable JWT path [:auth :token] sensitive at frame
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
                         ;; Official navbar shows the user's avatar
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

(reg-view ^{:doc "Confirm dialog shown when a `:can-leave` guard blocks a
                   navigation (e.g. the editor with a dirty draft). Reads the
                   blocked navigation off the `:rf/pending-navigation` sub; the
                   buttons continue or cancel the pending nav. See route guard:
                   ../../../docs/routing/glossary.md#route-guard."}
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
;; A single `:before` fn injects the Bearer token from the auth slice, so EVERY
;; outbound `:rf.http/managed` request that crosses this frame picks the header
;; up automatically. Resources AND mutations lower onto `:rf.http/managed`, so
;; this one interceptor decorates the WHOLE Conduit API — the route-caused reads
;; (`/articles/feed`, the lists, detail), the writes (favorite / follow /
;; comment / settings / save-article), and the auth machine's session-restore
;; `GET /user`. No `:request` fn threads the token per-call; the auth slice is
;; the single source of truth and this is the single read site.
;;
;; The token is read from `(:frame ctx)` — the frame the cascade runs under —
;; not a hard-coded id, so the header tracks a non-default / multi-frame mount.
;; The interceptor returns ctx unchanged when no token is present, so login /
;; register / the public (logged-out) reads are unaffected.
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
;; The official RealWorld browser/E2E harness sometimes reads app session state
;; through a global accessor. This installs `window.__conduit_debug__` with
;; `getToken` / `getAuthState` / `getCurrentUser`, reading the live app frame's
;; app-db.
;;
;; THIS IS A CONFORMANCE SURFACE, NOT A re-frame2 PATTERN. An unannotated global
;; token accessor is the anti-pattern that gets copied into real apps: it
;; bypasses the frame/sub system and leaks the raw JWT to any script on the
;; page. Real re-frame2 code reads session state through subs under the frame
;; provider, never a `window.*` global. This exists only so the external suite
;; can introspect the demo; a production RealWorld app would not ship it.
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

;; `:rf/default` is an ordinary frame id with no framework privilege — `init!`
;; installs only the adapter and creates no frame. This app earns URL ownership
;; by declaring `:url-bound? true` on the frame below.
;;
;; The app frame is created, configured, and seeded in ONE spot: the
;; `frame-provider {:id …}` ensure form at the render root. On first mount it
;; creates the frame under `app-frame`, applies the config (`:url-bound? true`
;; so it owns the URL; the auth-guard interceptor referenced by id; the demo
;; `:rf.http/managed` routed through the in-process backend stub so reads and
;; writes run without a network), and runs `:initial-events` once. Hot reload
;; reuses the frame without re-seeding (durable app-db survives). That id is what
;; every in-tree `dispatch`/`subscribe` resolves against.
;;
;; `:initial-events` ordering carries two constraints:
;;   - `:auth/classify-token` first: the JWT at [:auth :token] is a durable
;;     sensitive fact, so classify it before any off-box egress (Xray capture,
;;     an SSR payload) can see the raw token. See data classification:
;;     ../../../docs/guide/glossary.md#data-classification.
;;   - `:auth/initialise` before `:app/initialise`: session restore reads the
;;     saved JWT from a recordable coeffect (auth.cljs) and folds it into durable
;;     [:auth :token]. It rides its own dispatch (not the `:app/initialise`
;;     fan-out, which does not forward `:rf.cofx`) and runs first, so the token
;;     is in app-db before the bearer-auth interceptor fires any authenticated
;;     request.
;;
;; The outbound `Authorization` Bearer header is not classified here — the
;; framework's built-in HTTP carrier denylist already redacts it off-box. The
;; session-scope key ([:auth :user :username]) is identity, not a secret, so it
;; is deliberately not classified — over-redacting would obscure the cache-leak
;; boundary this example shows.
(defn run []
  (rf/init! reagent-adapter/adapter)
  ;; Register the Bearer-auth interceptor at app boot, BEFORE the frame's
  ;; `:initial-events` dispatch — session-restore fires an authenticated
  ;; `GET /user` as soon as the JWT is hydrated, so the header must already be
  ;; wired by the time `:auth/initialise` runs at frame creation (below).
  (rf/reg-http-interceptor :realworld/bearer-auth
                           {:before bearer-auth-interceptor})
  ;; The orchestrator serves this example at /realworld-resources/; strip that
  ;; prefix before the matcher sees the URL so :realworld/home (path "/") matches.
  ;; Set BEFORE install-router!'s initial URL→route sync (below).
  (routing/set-base-path! "/realworld-resources")
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; Create + configure + seed the app frame in one spot (see the block above).
    ;; The frame is created synchronously during render, so it is live and seeded
    ;; once this returns.
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
    ;; The frame is now live + session-seeded. These install ONGOING listeners
    ;; (each doing its initial sync against that frame) — so they run AFTER the
    ;; render that created it, not before.
    ;;
    ;; install-router! installs the popstate handler AND does the initial
    ;; URL→route sync (under the URL-owner frame), which fires the route's
    ;; `:resources` ensures — server-state loads because the route became
    ;; active, not because a view asked. The session token seeded above is
    ;; already in app-db, so the bearer-auth interceptor decorates those reads.
    (routing/install-router!)
    ;; Focus/reconnect revalidation: refetch active-and-stale reads when the tab
    ;; returns or the network reconnects (idempotent). The host signals are never
    ;; dispatched by hand.
    (rf/install-revalidation-listeners! app-frame)
    ;; Conformance-contract surface — NOT a re-frame2 pattern; see
    ;; install-conduit-debug! above. The external RealWorld suite may read it.
    (install-conduit-debug! app-frame)))
