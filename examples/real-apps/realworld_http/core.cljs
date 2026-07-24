(ns realworld-http.core
  "Entry point for the RealWorld (Conduit) example.

   This is the assembly line. It pulls in every feature namespace, defines
   the boot event, picks the page to show based on the current route, and
   mounts the whole thing into the DOM. Think of it as the spot where all
   the pieces meet — almost nothing interesting happens here, and that's the
   point: the interesting work lives in the feature files, and this file
   just snaps them together.

   What it does, end to end:
   - Pulls in every feature namespace (each registers its own events/subs/fx).
   - Defines :app/initialise — the boot event, run via the frame's
     :initial-events.
   - Defines the root-view that switches on :rf.route/id to pick the page.
   - Mounts the React root. One `frame-root {:id …}` at the render root
     creates and seeds the app frame in a single place — its `:url-bound?
     true` + base-path-aware `:url-strategy` automatically install the URL
     listener for Back/Forward.

   The per-feature work lives in:
     auth.cljs             — login / register / session-restore
     articles.cljs         — global feed + home page
     comments.cljs         — article detail + comments
     article_editor.cljs   — create / edit / delete article
     profile.cljs          — public profile routes
     favorites.cljs        — favorite toggle + your-feed slice
     tags.cljs             — popular-tags machine (lifecycle held entirely
                             in a machine) + home-page query helpers
     settings.cljs         — user settings page (form lifecycle held in a
                             machine)
     routing.cljs          — route registrations + router wiring
     schema.cljs           — Malli schemas for the example slices
     http.cljs             — request-builder + retry policy for :rf.http/managed
     ssr.cljc              — hydration payload helper for the RealWorld app

   Every Conduit endpoint goes out via `:rf.http/managed`. The demo entry
   below swaps in a canned-stub override so the app runs with no network at
   all. See the HTTP guide: ../../../docs/async/http.md"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Managed HTTP ships in its own artefact. Requiring it registers
            ;; the `:rf.http/managed` fx (and its family), so dispatching it
            ;; resolves to something.
            [re-frame.http.managed]
            ;; The demo backend delegates to `:rf.http/managed-canned-success`
            ;; / `-failure`, canned-reply fxs that ship with the framework's
            ;; test support. A real Conduit backend would drop both this require
            ;; and the demo override.
            [re-frame.http.test-support]
            ;; SSR ships in its own artefact. Requiring it registers the
            ;; `:rf/hydrate` handler and the SSR helpers ssr.cljc leans on
            ;; (`rf/render-tree-hash`).
            [re-frame.ssr]
            [re-frame.adapter.reagent :as reagent-adapter]
            [realworld-shared.avatar :as avatar]
            [realworld-http.schema]
            ;; Requiring http registers the demo `:rf.http/managed` override fx
            ;; (`:realworld.demo/http-stub`) the frame's `:fx-overrides` reference
            ;; below, alongside the request-builder + retry policy.
            [realworld-http.http]
            [realworld-http.routing :as routing]
            [realworld-http.auth :as auth]
            [realworld-http.articles :as articles]
            [realworld-http.comments :as comments]
            [realworld-http.article-editor :as editor]
            [realworld-http.profile :as profile]
            [realworld-http.favorites]
            [realworld-http.tags]
            [realworld-http.settings :as settings])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event :app/initialise
  {:doc "App boot. This is the conductor's downbeat: it fans out to every
         feature's own initialiser so each slice gets seeded.

         Notice who's NOT in this list: `:auth/initialise`. It earns its own
         entry in the frame-root's `:initial-events` instead, because it
         consumes the recordable `:auth.session/token` coeffect — and the
         `:dispatch` fx doesn't forward `:rf.cofx`. Giving it a boot dispatch
         of its own is what lets the token it reads be recorded for replay."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:articles/initialise]]
          [:dispatch [:article/initialise]]
          [:dispatch [:comments/initialise]]
          [:dispatch [:comment-form/initialise]]
          [:dispatch [:editor/initialise]]
          [:dispatch [:profile/initialise]]
          [:dispatch [:feed/initialise]]
          [:dispatch [:tags/initialise]]
          [:dispatch [:settings/initialise]]
          [:dispatch [:auth.login-form/initialise]]
          [:dispatch [:auth.register-form/initialise]]]}))

;; The JWT at [:auth :token] is a real credential, so we'd rather it didn't
;; turn up in a trace export or an SSR payload. The fix is one line: tell the
;; framework the path is sensitive, via the `:sensitive` classification
;; effect, returned alongside `:db` from an event the frame runs at creation
;; (`:initial-events`, before any boot dispatch or any chance to leak off-box).
;; From then on, anything that leaves the box — an observability capture, an
;; external tool, a hydration payload — sees the token redacted, while the
;; running app keeps the raw value it needs.
;;
;; The JWT is not the only credential in this app, and it is a mistake to think
;; the login / register / settings PASSWORD is safe just because it is
;; "throwaway form state". A password is a credential the moment it is typed,
;; and it egresses raw unless classified — every surface it crosses gets its
;; own classification, mirroring the same discipline this event applies to the
;; JWT:
;;
;;   - The DRAFT, while the user types, lives in app-db
;;     ([:auth :login-form :draft :password] / [:auth :register-form :draft
;;     :password], both classified :sensitive at slice-init in auth.cljs) or
;;     the settings machine's :data ([:data :draft :password], classified on
;;     the machine SPEC in settings.cljs).
;;   - The per-keystroke EDIT rides its own map-payload event
;;     (:auth.login-form/edit-password, :auth.register-form/edit-password,
;;     :settings/edit-password — each classified :sensitive [[:value]]) rather
;;     than the generic positional :edit-field event the non-secret fields
;;     use; a positional arg isn't path-addressable, so the password gets a
;;     dedicated event whose arg-map is. The settings machine ALSO classifies
;;     the routed :edit-password / :submit-valid sub-events it echoes into its
;;     own trace slots (the reg-machine OPTS `:sensitive`, rf2-ghgbqi).
;;   - The SUBMIT is the credential-owning handoff: the form/settings submit
;;     handler reads the draft, fires the managed-HTTP request itself
;;     ({:user {:password …}}, classified :sensitive? true on the request
;;     below / in settings.cljs), and blanks the live draft's password
;;     afterwards (secret-field hygiene).
;;   - The auth MACHINE never sees the password at all — login/register nudge
;;     it with a bare, credential-free signal (:auth/login, :auth/register),
;;     exactly the examples/core/login split. A password riding a machine
;;     dispatch as a positional sub-event isn't path-addressable, so keeping
;;     it out of the machine altogether — rather than trying to classify a
;;     position after the fact — is the fix, not a documented limitation.
;;
;; Each surface is classified on its own — classification is fail-open and
;; does not propagate. See the keep-secrets how-to:
;; ../../../docs/core/how-to/keep-secrets-out-of-traces.md
;;
;; The one carrier we deliberately do NOT classify by hand is the outbound
;; `Authorization` Bearer header: it is already on the framework's built-in HTTP
;; carrier denylist, so it is redacted off-box with zero config — over-declaring
;; a built-in would only teach a redundant ritual.
(rf/reg-event :auth/classify-token
  {:doc "Mark the durable JWT path [:auth :token] sensitive, at frame
         creation."}
  (fn handler-auth-classify-token [{:keys [db]} _]
    {:db db :sensitive [[:auth :token]]}))

;; ============================================================================
;; APP-SHELL VIEWS
;; ============================================================================

(reg-view ^{:doc "The site header. Shows one set of links when you're signed
                  in, another when you're not. Each nav link carries a stable
                  data-testid hook — the friendly way to give UI tests a
                  target that survives a CSS redesign, instead of having them
                  match on classes or button text that drift over time."}
          header []
  (let [authed? @(subscribe [:auth/authenticated?])
        user    @(subscribe [:auth/user])]
    [:nav.navbar.navbar-light
     [:div.container
      [rf/route-link {:to :realworld/home :class "navbar-brand"} "conduit"]
      [:ul.nav.navbar-nav.pull-xs-right
       [:li.nav-item
        [rf/route-link {:to :realworld/home :class "nav-link"} "Home"]]
       (if authed?
         [:<>
          [:li.nav-item
           [rf/route-link {:to :realworld.editor/new :class "nav-link"}
            [:i.ion-compose] " New Article"]]
          [:li.nav-item
           [rf/route-link {:to :realworld.user/settings :class "nav-link"}
            [:i.ion-gear-a] " Settings"]]
          [:li.nav-item
           ;; The official RealWorld navbar puts the signed-in user's avatar
           ;; (`.user-pic`) next to their name; `avatar-src` falls back to a
           ;; default when the image is nil or empty.
           [rf/route-link {:to :realworld.profile/show
                                :params {:username (:username user)}
                                :class "nav-link"
                                :data-testid "nav-username"}
            [:img.user-pic {:src (avatar/avatar-src (:image user))}]
            " " (:username user)]]
          [:li.nav-item
           [:a.nav-link {:data-testid "nav-logout"
                         :href        "#"
                         :on-click    #(do (.preventDefault %)
                                           (dispatch [:auth/flow [:auth/logout]]))}
            "Logout"]]]
         [:<>
          [:li.nav-item
           [rf/route-link {:to :realworld.auth/login
                                :class "nav-link"
                                :data-testid "nav-signin"}
            "Sign in"]]
          [:li.nav-item
           [rf/route-link {:to :realworld.auth/register
                                :class "nav-link"
                                :data-testid "nav-signup"}
            "Sign up"]]])]]]))

(reg-view footer []
  [:footer
   [:div.container
    [rf/route-link {:to :realworld/home :class "logo-font"} "conduit"]
    [:span.attribution "An interactive learning project from Thinkster."
     " Code & design licensed under MIT."]]])

(reg-view ^{:doc "The \"are you sure?\" dialog. When a `:can-leave` guard
                  blocks a navigation (you've got unsaved edits in the
                  article editor, say), the framework parks the pending nav
                  and this view pops up to ask. Reads the parked nav from the
                  `:rf/pending-navigation` sub."}
          pending-nav-dialog []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.pending-nav-overlay
     [:div.pending-nav-dialog
      [:p (or (:reason pending) "You have unsaved changes. Leave anyway?")]
      [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])}
       "Discard changes"]
      [:button {:on-click #(dispatch [:rf.route/cancel (:id pending)])}
       "Stay"]]]))

(reg-view not-found-page []
  (let [url (:url @(subscribe [:rf.route/params]))]
    [:div.not-found-page
     [:h1 "Page not found"]
     (when url [:p (str "No route matches: " url)])
     [rf/route-link {:to :realworld/home} "Home"]]))

(reg-view ^{:doc "The root of the whole tree. One big `case` over
                  :rf.route/id picks which page to render — the routing
                  table's way of saying \"you are here, show this\"."}
          root-view []
  [:div.app
   [header]
   [pending-nav-dialog]
   (case @(subscribe [:rf.route/id])
     :realworld/home              [articles/home-page]
     :realworld/home-tag          [articles/home-page]
     :realworld.auth/login             [auth/login-page]
     :realworld.auth/register          [auth/register-page]
     :realworld.article/show           [comments/article-page]
     :realworld.editor/new            [editor/editor-page]
     :realworld.editor/edit       [editor/editor-page]
     :realworld.profile/show           [profile/profile-page]
     :realworld.profile/favorites [profile/profile-page]
     :realworld.user/settings          [settings/settings-page]
     [not-found-page])
   [footer]])

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================

;; ----------------------------------------------------------------------------
;; DEMO BACKEND
;;
;; Out of the box this example talks to no server at all. Normally it would hit
;; the hosted Conduit API (https://api.realworld.show/api —
;; `realworld-http.http/api-base`), but a live backend is slow and flaky for a
;; demo, and we'd like you to be able to clone-and-run on a plane. So the demo
;; `:rf.http/managed` override — `:realworld.demo/http-stub`, defined in
;; `http.cljs` alongside the request builder and referenced from the frame's
;; `:fx-overrides` in `mount!` below — routes every request through the
;; in-process demo backend both RealWorld examples share
;; (`realworld-shared.demo-backend`): one canonical Conduit corpus + a
;; URL/method request router + a canned-reply adapter. The stub (and its
;; request-body `:sensitive` classification) lives with the rest of the app's
;; HTTP surface in `http.cljs`.
;; ----------------------------------------------------------------------------

;; The React root lives in an atom and gets created lazily inside `run`, so
;; that merely loading this namespace touches no DOM. That restraint earns
;; its keep: several example namespaces share a single `#app` mount point in
;; the browser-test bundle, and creating the root at load time would race two
;; roots onto the same container — one example's mount bleeding into another's
;; tests, with React grumbling "createRoot called twice" the whole way. The
;; name `react-root` (not `root`) keeps it distinct from the `root-view`
;; above.
(defonce react-root (atom nil))

;; ============================================================================
;; HTTP REQUEST INTERCEPTOR
;; ============================================================================
;;
;; Stamp the auth token onto every request, in exactly one place. This
;; `:before` fn reads the JWT from the auth slice and adds the Bearer header,
;; so every outbound `:rf.http/managed` request that passes through this frame
;; picks it up automatically. The call sites in articles.cljs, comments.cljs,
;; and friends never thread a token by hand — the auth slice is the single
;; source of truth, and this interceptor is the single place that reads it.
;; The day the token changes, you change it once. See the HTTP guide on
;; interceptors:
;; ../../../docs/async/http-going-further.md#interceptors-stamp-every-request-once
;;
;; No token? It hands the ctx straight back, so login, register, and
;; public-read endpoints sail through unsigned, exactly as they should.
;;
;; It reads the token from `(:frame ctx)` — whichever frame the pipeline run is
;; actually running under — rather than a hard-coded `:rf/default`, so the
;; header still tracks the right session in a non-default or multi-frame
;; mount.

(defn- bearer-auth-interceptor [ctx]
  (let [token (some-> (rf/app-db-value (:frame ctx))
                      :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))

;; ============================================================================
;; window.__conduit_debug__  — CONFORMANCE-CONTRACT SURFACE
;; ============================================================================
;;
;; The official RealWorld E2E suite peeks at session state through a global
;; accessor, so to be a good conformance citizen we hang one on the window:
;; `__conduit_debug__` with `getToken` / `getAuthState` / `getCurrentUser`,
;; each reading the live `:rf/default` frame's app-db. It's here purely so the
;; external suite can introspect this demo; a real app would never ship it.
;;
;; Treat this as a test seam, not a pattern to copy. A global token accessor
;; is exactly the sort of thing that quietly migrates into production code,
;; where it side-steps the frame/sub system and hands the raw JWT to any
;; script on the page — which is to say, a small disaster. Real re-frame2 code
;; reads session state through subs, under the frame provider.
(defn- install-conduit-debug! [frame-id]
  (when (exists? js/window)
    (set! (.-__conduit_debug__ js/window)
          #js {:getToken       (fn [] (some-> (rf/app-db-value frame-id) :auth :token))
               :getAuthState   (fn [] (name (or (some-> (rf/app-db-value frame-id)
                                                         :auth :user (#(if % :authed :anonymous)))
                                                 :anonymous)))
               :getCurrentUser (fn [] (clj->js (some-> (rf/app-db-value frame-id) :auth :user)))})))

;; DOM setup lives in `mount!`, tagged `^:dev/after-load` so shadow-cljs re-runs
;; it after each hot reload — edited views re-render into the same root and frame.
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (rdc/create-root el)))
    ;; Here's where the frame is born. A single `frame-root {:id …}` at
    ;; the render root creates, configures, and seeds the app frame all in one
    ;; place. The first mount conjures `:rf/default` and applies its config:
    ;;   - `:url-bound? true` — this frame owns the browser URL.
    ;;   - `:url-strategy routing/url-strategy` — the orchestrator serves this
    ;;     example under `/realworld/`, but the routes (routing.cljs) are
    ;;     written as if it owned `/`. `routing/url-strategy` wraps the
    ;;     default history strategy with `with-base-path` so that prefix is
    ;;     stripped/re-added automatically — no hand-rolled popstate listener,
    ;;     no explicit install call. Frame creation installs the
    ;;     base-path-aware listener AND does the first URL→slice sync itself,
    ;;     AFTER every `:initial-events` step below has run (see the ordering
    ;;     note at the bottom).
    ;;   - The auth gate is NOT a frame interceptor — it's the `:can-enter`
    ;;     guard on the `:requires-auth` routes themselves (routing.cljs). Try to
    ;;     navigate to one while logged out and the runtime's ONE pipeline
    ;;     denies entry and dispatches `:rf.route/entry-denied`, which stashes
    ;;     the destination and replace-navigates to login. That's what gives the
    ;;     `:requires-auth` tags on settings / new / edit teeth — declaratively
    ;;     on the route, fail-closed through every door, not as a hand-rolled
    ;;     interceptor.
    ;;   - `:fx-overrides {:rf.http/managed …}` — point managed HTTP at the
    ;;     demo stub so the whole thing runs without a backend.
    ;; First mount also runs `:initial-events` once. A hot reload reuses the
    ;; existing frame and does NOT re-seed — your durable app-db survives the
    ;; code swap, which is rather the point.
    ;;
    ;; `:initial-events` fire in order at frame creation, and the order is
    ;; load-bearing:
    ;;   - `:auth/classify-token` — mark [:auth :token] sensitive BEFORE the
    ;;     token is ever written, so it's redacted off-box from the very first
    ;;     write. Lock the door before anyone's home.
    ;;   - `:auth/initialise` — session restore. It pulls the saved JWT through
    ;;     the `:auth.session/token` recordable coeffect (auth.cljs), which
    ;;     reads localStorage once and records the value so replay and
    ;;     epoch-restore play back the exact same token. It runs before
    ;;     `:app/initialise` so the token is sitting in app-db before the
    ;;     bearer-auth interceptor needs to send anything authenticated.
    ;;   - `:app/initialise` — fans out to all the per-feature initialisers.
    ;; `:url-bound? true` performs the initial URL→slice sync after every
    ;; `:initial-events` step above. That ordering is load-bearing: session
    ;; restore completes before a deep link to a `:requires-auth` route runs its
    ;; `:can-enter` gate, so the gate judges the restored user rather than an
    ;; uninitialised auth slice. No explicit `:rf.route/handle-url-change`
    ;; initial event is needed.
    (rdc/render @react-root
                [rf/frame-root {:id              :rf/default
                                :doc             "Realworld demo frame."
                                :url-bound?      true
                                :url-strategy    routing/url-strategy
                                :initial-events  [[:auth/classify-token]
                                                      [:auth/initialise]
                                                      [:app/initialise]]
                                    :fx-overrides    {:rf.http/managed :realworld.demo/http-stub}}
                 [root-view]])))

(defn run []
  ;; Tell re-frame2 to render through Reagent. This is the one genuinely
  ;; frameworky step, and it has to come before any frame mounts; everything
  ;; below it is just this app wiring itself up.
  (rf/init! reagent-adapter/adapter)
  ;; Register the Bearer-auth interceptor (defined above) so it stamps the
  ;; `Authorization` header onto outbound managed requests. Order matters:
  ;; register it before the frame's `:initial-events` run, so it's already on
  ;; duty when session-restore fires its first authenticated request.
  ;;
  ;; HTTP-interceptor registration is context-required frame-local (EP-0002 /
  ;; Spec 014 §Middleware): each frame owns its own middleware chain, so the
  ;; registration has to name the frame it belongs to. A bare top-level
  ;; `reg-http-interceptor` under no frame scope raises the always-on
  ;; `:rf.error/no-frame-context` and installs nothing. We scope it to the app
  ;; frame (`:rf/default` — the same id the `frame-root` in `mount!` ensures)
  ;; with `with-frame`, so the interceptor lands on the chain the app's managed
  ;; requests actually run under. The frame need not exist yet — the chain is
  ;; keyed by frame-id and consulted when the first request fires. See the auth
  ;; how-to: ../../../docs/core/how-to/add-auth.md#3-decorate-requests-once-at-the-frame-seam
  (rf/with-frame :rf/default
    (rf/reg-http-interceptor :realworld/bearer-auth
                             {:before bearer-auth-interceptor}))
  ;; Hang the conformance accessor on the window for the external RealWorld
  ;; suite. Test seam, not a pattern — see install-conduit-debug! above.
  (install-conduit-debug! :rf/default)
  (mount!))
