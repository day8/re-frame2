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

   STATUS. EP-0003 graduated accepted→final on 2026-06-11 (rf2-9l9xs2), so the
   resources + mutations runtime is real and this example runs live. The
   example tree is test-free (rf2-8cevm); resource/mutation contract coverage
   lives in `implementation/resources/test/` and the conformance fixtures.

   For the `:rf.http/managed` counterpart (schema-driven decode, retry/abort,
   the classification order, optimistic rollback against managed HTTP), see the
   sibling `examples/reagent/realworld/` — kept intact as the canonical Spec
   014 demo."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Managed HTTP — the single built-in resource/mutation transport.
            [re-frame.http-managed]
            ;; The canned-stub fxs the demo backend delegates to (Spec 014
            ;; §Testing). A real Conduit deployment would drop this require.
            [re-frame.http-test-support]
            ;; Resources + mutations runtime (day8/re-frame2-resources).
            [re-frame.resources]
            ;; Routing + machines artefacts (late-bind the `:resources` route
            ;; key + the auth machine).
            [re-frame.routing]
            [re-frame.machines]
            [re-frame.adapter.reagent :as reagent-adapter]
            [realworld-resources.schema]
            [realworld-resources.scope]
            [realworld-resources.http :as rh]
            [realworld-resources.resources]
            [realworld-resources.mutations]
            [realworld-resources.routing :as routing]
            [realworld-resources.auth :as auth]
            [realworld-resources.settings :as settings]
            [realworld-resources.views :as views])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event-fx :app/initialise
  {:doc "App boot. Seeds the auth slice + form drafts and kicks session
         restore. The page reads (articles, tags, feed, …) are CAUSED by the
         route's `:resources` metadata on the initial URL→route sync, not from
         here — that is the point of the variant."}
  (fn [_ _]
    {:fx [[:dispatch [:auth/initialise]]
          [:dispatch [:auth.login-form/initialise]]
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
          [:li.nav-item [rf/route-link {:to :realworld.user/settings :class "nav-link" :data-testid "nav-settings"}
                         [:i.ion-gear-a] " Settings"]]
          [:li.nav-item [rf/route-link {:to :realworld.profile/show :params {:username (:username user)}
                                        :class "nav-link" :data-testid "nav-username"}
                         (:username user)]]
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

(reg-view settings-mount []
  ;; The settings page seeds its draft on mount (the route is auth-guarded).
  (let [_ (dispatch [:settings/load])]
    [settings/settings-page]))

(reg-view not-found-page []
  [:div.not-found-page [:h1 "Page not found"] [rf/route-link {:to :realworld/home} "Home"]])

(reg-view root-view []
  [:div.app
   [header]
   (case @(subscribe [:rf.route/id])
     :realworld/home          [views/home-page]
     :realworld.auth/login    [auth/login-page]
     :realworld.auth/register [auth/register-page]
     :realworld.article/show  [views/article-page]
     :realworld.profile/show  [views/profile-page]
     :realworld.user/settings [settings-mount]
     [not-found-page])
   [footer]])

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================

(defonce react-root (atom nil))

(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  ;; EP-0002: the frame is established explicitly, owns the browser URL
  ;; (`:url-bound? true`), and prepends the auth-guard interceptor + routes
  ;; the demo `:rf.http/managed` through the in-process backend stub so reads
  ;; (resources) and writes (mutations) run without a network.
  (rf/reg-frame app-frame
    {:doc          "RealWorld-on-resources demo frame."
     :url-bound?   true
     :interceptors [routing/auth-guard]
     :fx-overrides {:rf.http/managed :realworld-resources.demo/http-stub}})
  ;; The orchestrator serves this example at /realworld-resources/; strip that
  ;; prefix before the matcher sees the URL so :realworld/home (path "/") matches.
  (routing/set-base-path! "/realworld-resources")
  (rf/with-frame app-frame
    (rf/dispatch-sync [:app/initialise])
    ;; The initial URL→route sync (under the frame scope) is what fires the
    ;; route's `:resources` ensures — server-state loads because the route
    ;; became active, not because a view asked.
    (routing/install-router!)
    ;; Focus/reconnect revalidation: refetch active-and-stale reads when the
    ;; tab returns or the network reconnects (Spec 016 §Stale and GC; CLJS-only,
    ;; idempotent). The host signals are never dispatched by hand.
    (rf/install-revalidation-listeners! app-frame))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:frame app-frame} [root-view]])))
