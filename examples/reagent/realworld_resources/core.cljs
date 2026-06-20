(ns realworld-resources.core
  "Entry point for the RealWorld-on-resources (Conduit) example — a sibling of
   `examples/reagent/realworld/` that ports the read surface to EP-0003 (Spec
   016) RESOURCES and the write surface to MUTATIONS, instead of hand-rolled
   `:rf.http/managed` Pattern-RemoteData slices.

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
     views.cljs      — passive pages + the small UI event glue

   STATUS. EP-0003 graduated accepted→final on 2026-06-11, so the
   resources + mutations runtime is real and this example runs live. The
   example tree is test-free; resource/mutation contract coverage
   lives in `implementation/resources/test/` and the conformance fixtures.

   For the `:rf.http/managed` counterpart (schema-driven decode, retry/abort,
   the classification order, optimistic rollback against managed HTTP), see the
   sibling `examples/reagent/realworld/` — kept intact as the canonical Spec
   014 demo."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Managed HTTP — the single built-in resource/mutation transport.
            [re-frame.http.managed]
            ;; The canned-stub fxs the demo backend delegates to (Spec 014
            ;; §Testing). A real Conduit deployment would drop this require.
            [re-frame.http.test-support]
            ;; Resources + mutations runtime (day8/re-frame2-resources).
            [re-frame.resources]
            ;; Routing + machines artefacts (late-bind the `:resources` route
            ;; key + the auth machine).
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
  {:doc "App boot. Seeds the form drafts. Session restore (`:auth/initialise`)
         is NOT in this fan-out — it consumes the RECORDABLE+PROVIDED
         `:realworld-resources.session/token` coeffect, whose value the host
         boundary (`run`) reads ONCE and stamps onto a dedicated boot dispatch
         token (EP-0017). The `:dispatch` fx does not forward
         `:rf.cofx`, so that boot dispatch is issued directly at the boundary in
         `run`. The page reads (articles, tags, feed, …) are CAUSED by the
         route's `:resources` metadata on the initial URL→route sync, not from
         here — that is the point of the variant."}
  (fn [_ _]
    {:fx [[:dispatch [:auth.login-form/initialise]]
          [:dispatch [:auth.register-form/initialise]]]}))

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
                   blocked navigation off the `:rf/pending-navigation` sub
                   (Spec 012 §Redirects and guards); the buttons continue or
                   cancel the pending nav."}
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
;; BEARER-AUTH HTTP INTERCEPTOR  (Spec 014 §Middleware)
;; ============================================================================
;;
;; The cross-cutting decoration the resources/mutations variant was missing
;; (fable.md F4): a single `:before` fn injects the Bearer token from the auth
;; slice, so EVERY outbound `:rf.http/managed` request that crosses this frame
;; picks the header up automatically. Resources AND mutations lower onto
;; `:rf.http/managed`, so this one interceptor decorates the WHOLE Conduit API
;; — the route-caused reads (`/articles/feed`, the lists, detail), the writes
;; (favorite / follow / comment / settings / save-article), and the auth
;; machine's session-restore `GET /user`. No `:request` fn threads the token
;; per-call; the auth slice is the single source of truth and this is the
;; single read site.
;;
;; CARRIED-FRAME-CORRECT (EP-0002): the token is read from
;; `(:frame ctx)` — the frame the cascade actually runs under — not a
;; hard-coded `:rf/default`, so the header tracks a renamed / multi-frame
;; mount. The interceptor returns ctx unchanged when no token is present, so
;; login / register / the public reads (logged-out) are unaffected.

;; Public (not `defn-`) so a headless fixture can wire it into a test frame
;; and assert the decoration — the sibling's route guard takes the other route
;; now: it is a `reg-interceptor` descriptor referenced BY ID
;; (`:realworld-resources.routing/auth-guard`, EP-0022) rather than a var. The
;; example tree stays test-free; the visibility is the only concession.
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
;; token accessor is exactly the anti-pattern that gets copied into real apps
;; (it bypasses the frame/sub system and leaks the raw JWT to any script on the
;; page). Real re-frame2 code reads session state through subs under the frame
;; provider — never a `window.*` global. It exists ONLY so the external suite
;; can introspect this demo; a production RealWorld app would NOT ship it.
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

(defn run []
  (rf/init! reagent-adapter/adapter)
  ;; EP-0002: the frame is established explicitly, owns the browser URL
  ;; (`:url-bound? true`), and prepends the auth-guard interceptor — registered
  ;; in routing.cljs via `reg-interceptor` and referenced here BY ID per
  ;; EP-0022 — + routes
  ;; the demo `:rf.http/managed` through the in-process backend stub so reads
  ;; (resources) and writes (mutations) run without a network.
  ;; EP-0015 (frame-owned egress policy): the JWT is a durable, frame-wide
  ;; sensitive fact — it lives at [:auth :token] in app-db, so that path is
  ;; declared `:sensitive`. Projection happens at the trust boundary, so
  ;; off-box egress (Xray / observability capture, an off-box tool, an SSR
  ;; hydration payload) never sees the raw token, while on-box use keeps it.
  ;;
  ;; The outbound `Authorization` Bearer header (the interceptor above) is NOT
  ;; declared here — it is already on the framework's immutable built-in HTTP
  ;; carrier denylist (Spec 014 §Privacy), redacted off-box with no frame
  ;; config. The `:sensitive :http :headers` extension is for APP-SPECIFIC
  ;; carriers; this app sends none. The session-scope KEY ([:auth :user
  ;; :username]) that the scoped resource cache reads under is identity, not a
  ;; secret, so it is deliberately NOT classified — over-redacting would
  ;; obscure the cache-leak boundary this example exists to show.
  (rf/reg-frame app-frame
    {:doc          "RealWorld-on-resources demo frame."
     :url-bound?   true
     :sensitive    {:app-db [[:auth :token]]}
     :interceptors [:realworld-resources.routing/auth-guard]
     :fx-overrides {:rf.http/managed :realworld-resources.demo/http-stub}})
  ;; Register the Bearer-auth interceptor at app boot, BEFORE :app/initialise
  ;; dispatches — session-restore fires an authenticated `GET /user` as soon as
  ;; the JWT is hydrated, so the header must already be wired.
  (rf/reg-http-interceptor :realworld/bearer-auth
                           {:before bearer-auth-interceptor})
  ;; The orchestrator serves this example at /realworld-resources/; strip that
  ;; prefix before the matcher sees the URL so :realworld/home (path "/") matches.
  (routing/set-base-path! "/realworld-resources")
  (rf/with-frame app-frame
    ;; EP-0017: session restore consumes the RECORDABLE+PROVIDED
    ;; `:realworld-resources.session/token` coeffect and folds it into durable
    ;; [:auth :token]. The host read happens ONCE here at the boundary; its
    ;; value rides this boot dispatch token as the flat recordable coeffect, so
    ;; it is recorded and replay / epoch-restore re-presents the captured token
    ;; verbatim rather than re-reading localStorage then. It is dispatched
    ;; directly at the boundary (not via the `:app/initialise` `:dispatch`
    ;; fan-out, which does not forward `:rf.cofx`), and BEFORE `:app/initialise`
    ;; so the token is in app-db before the bearer-auth interceptor fires any
    ;; authenticated request. Tests / replay supply the value the same way.
    (rf/dispatch-sync [:auth/initialise]
                      {:rf.cofx {:realworld-resources.session/token (auth/read-jwt-from-storage)}})
    (rf/dispatch-sync [:app/initialise])
    ;; The initial URL→route sync (under the frame scope) is what fires the
    ;; route's `:resources` ensures — server-state loads because the route
    ;; became active, not because a view asked.
    (routing/install-router!)
    ;; Focus/reconnect revalidation: refetch active-and-stale reads when the
    ;; tab returns or the network reconnects (Spec 016 §Stale and GC; CLJS-only,
    ;; idempotent). The host signals are never dispatched by hand.
    (rf/install-revalidation-listeners! app-frame))
  ;; Conformance-contract surface — NOT a re-frame2 pattern; see
  ;; install-conduit-debug! above. The external RealWorld suite may read it.
  (install-conduit-debug! app-frame)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame} [root-view]])))
