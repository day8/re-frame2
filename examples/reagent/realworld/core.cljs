(ns realworld.core
  "Entry point for the RealWorld (Conduit) example.

   Wires the app together:
   - Pulls in every feature namespace (each registers its own events/subs/fx).
   - Defines :app/initialise (the :on-create event).
   - Defines the root-view that switches on :rf.route/id.
   - Mounts the React root and installs the URL listener.

   This is single-file glue; the per-feature work lives in:
     auth.cljs             — login / register / session-restore
     articles.cljs         — global feed + home page
     comments.cljs         — article detail + comments
     article_editor.cljs   — create / edit / delete article
     profile.cljs          — public profile routes
     favorites.cljs        — favorite toggle + your-feed slice
     tags.cljs             — popular-tags machine (:data-region
                             machine variant of Pattern-RemoteData) +
                             home-page query helpers
     settings.cljs         — user settings page (:form-region machine
                             variant of Pattern-Forms)
     routing.cljs          — route registrations + router wiring
     schema.cljs           — Malli schemas for the example slices
     http.cljs             — request-builder + retry policy for :rf.http/managed
     ssr.cljc              — hydration payload helper for the RealWorld app

   This is the canonical Spec 014 (`:rf.http/managed`) demo. Every
   Conduit endpoint goes via the framework-shipped managed-HTTP fx;
   the demo entry below installs a canned-stub override so the headless
   smoke and Playwright run without a network."
  (:require [clojure.string :as str]
            [reagent2.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Managed-HTTP ships in day8/re-frame2-http.
            ;; Requiring re-frame.http-managed at app boot is what
            ;; triggers its load-time fx registrations (`:rf.http/managed`
            ;; and family) and publishes the late-bind hooks; without
            ;; it, dispatching `:rf.http/managed` would fail with
            ;; :rf.error/no-such-fx. RealWorld is the canonical Spec 014
            ;; demo so the require is mandatory here.
            [re-frame.http-managed]
            ;; SSR ships in day8/re-frame2-ssr. Requiring
            ;; re-frame.ssr at app boot publishes the late-bind hooks
            ;; (`:ssr/render-tree-hash` etc.) and registers the
            ;; `:rf/hydrate` handler — the RealWorld ssr.cljc helper
            ;; calls `rf/render-tree-hash` and dispatches
            ;; `:rf/hydrate`. Without the require those calls raise
            ;; :rf.error/ssr-artefact-missing.
            [re-frame.ssr]
            [re-frame.registrar :as registrar]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [realworld.schema]
            [realworld.http]
            [realworld.routing :as routing]
            [realworld.auth :as auth]
            [realworld.articles :as articles]
            [realworld.comments :as comments]
            [realworld.article-editor :as editor]
            [realworld.profile :as profile]
            [realworld.favorites]
            [realworld.tags]
            [realworld.settings :as settings])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event-fx :app/initialise
  {:doc "App boot. Fans out to per-feature initialisers."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:auth/initialise]]
          [:dispatch [:articles/initialise]]
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

;; ============================================================================
;; APP-SHELL VIEWS
;; ============================================================================

(reg-view ^{:doc "The site header. Shows different links based on auth state.
                  Each nav link carries a stable data-testid hook so the
                  Playwright spec can target it without brittle class /
                  text matching."}
          header []
  (let [authed? @(subscribe [:auth/authenticated?])
        user    @(subscribe [:auth/user])]
    [:nav.navbar.navbar-light
     [:div.container
      [rf/route-link {:to :route/home :class "navbar-brand"} "conduit"]
      [:ul.nav.navbar-nav.pull-xs-right
       [:li.nav-item
        [rf/route-link {:to :route/home :class "nav-link"} "Home"]]
       (if authed?
         [:<>
          [:li.nav-item
           [rf/route-link {:to :route/editor :class "nav-link"}
            [:i.ion-compose] " New Article"]]
          [:li.nav-item
           [rf/route-link {:to :route/settings :class "nav-link"}
            [:i.ion-gear-a] " Settings"]]
          [:li.nav-item
           [rf/route-link {:to :route/profile
                                :params {:username (:username user)}
                                :class "nav-link"
                                :data-testid "nav-username"}
            (:username user)]]
          [:li.nav-item
           [:a.nav-link {:data-testid "nav-logout"
                         :href        "#"
                         :on-click    #(do (.preventDefault %)
                                           (dispatch [:auth/flow [:auth/logout]]))}
            "Logout"]]]
         [:<>
          [:li.nav-item
           [rf/route-link {:to :route/login
                                :class "nav-link"
                                :data-testid "nav-signin"}
            "Sign in"]]
          [:li.nav-item
           [rf/route-link {:to :route/register
                                :class "nav-link"
                                :data-testid "nav-signup"}
            "Sign up"]]])]]]))

(reg-view footer []
  [:footer
   [:div.container
    [rf/route-link {:to :route/home :class "logo-font"} "conduit"]
    [:span.attribution "An interactive learning project from Thinkster."
     " Code & design licensed under MIT."]]])

(reg-view ^{:doc "Renders a confirm dialog when navigation is blocked by a
                  :can-leave guard. Reads the :rf/pending-navigation slot."}
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
     [rf/route-link {:to :route/home} "Home"]]))

(reg-view ^{:doc "App-level root. Switches on :rf.route/id to render the active page."}
          root-view []
  [:div.app
   [header]
   [pending-nav-dialog]
   (case @(subscribe [:rf.route/id])
     :route/home              [articles/home-page]
     :route/login             [auth/login-page]
     :route/register          [auth/register-page]
     :route/article           [comments/article-page]
     :route/editor            [editor/editor-page]
     :route/editor.edit       [editor/editor-page]
     :route/profile           [profile/profile-page]
     :route/profile.favorites [profile/profile-page]
     :route/settings          [settings/settings-page]
     [not-found-page])
   [footer]])

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================

;; ----------------------------------------------------------------------------
;; DEMO STUBS
;;
;; The realworld example would normally hit https://api.realworld.io/api,
;; which is unreliable for headless smoke and slow for a demo. Override
;; :rf.http/managed with a small in-process stub that synthesises the
;; canonical Spec 014 reply shape for the routes the demo actually
;; exercises (global feed, tags, profile). Anything not covered resolves
;; to an empty-payload success — enough for the app shell + main feed
;; to render.
;;
;; The override uses :rf.http/managed-canned-success directly with a
;; per-URL :value payload. This is the same shape Spec 014 §Testing
;; documents — just routed by URL inspection in a wrapper fx so the
;; demo doesn't have to know one URL ahead of time.
;; ----------------------------------------------------------------------------

(def ^:private demo-articles
  [{:slug "hello-conduit"
    :title "Hello, Conduit"
    :description "A short greeting from the realworld stub."
    :body "This article is served by the demo :rf.http/managed override."
    :tagList ["intro" "demo"]
    :createdAt "2026-01-01T00:00:00Z"
    :updatedAt "2026-01-01T00:00:00Z"
    :favorited false
    :favoritesCount 0
    :author {:username "stub-bot"
             :bio "A friendly stub."
             :image ""
             :following false}}
   {:slug "second-article"
    :title "Second article"
    :description "A second short article."
    :body "More canned demo content."
    :tagList ["demo"]
    :createdAt "2026-02-01T00:00:00Z"
    :updatedAt "2026-02-01T00:00:00Z"
    :favorited false
    :favoritesCount 0
    :author {:username "stub-bot"
             :bio "A friendly stub."
             :image ""
             :following false}}])

(def ^:private demo-tags
  ["intro" "demo" "clojure" "re-frame"])

(def ^:private demo-user
  "Canned `User` payload returned by /users/login, /users (register),
   and /user (session restore). The Playwright spec submits matching
   credentials at the login form; the stub doesn't verify the body,
   it just synthesises the success reply the auth machine expects."
  {:email    "demo@conduit.dev"
   :token    "stub.demo.jwt"
   :username "demo"
   :bio      "Canned demo user."
   :image    ""})

(defn- counter-comment
  "Synthesise a saved Comment reply for POST /articles/:slug/comments.
   The Spec 014 reply value is `{:comment <Comment>}`; the comments
   handler patches the optimistic temp card out and inserts this saved
   row by `:id`. A unique numeric id per call avoids :key collisions
   when the spec posts more than once."
  [body]
  (let [id (+ 1000 (rand-int 100000))]
    {:comment {:id        id
               :createdAt "2026-05-13T00:00:00Z"
               :updatedAt "2026-05-13T00:00:00Z"
               :body      (or body "stubbed comment")
               :author    {:username  "demo"
                           :bio       "Canned demo user."
                           :image     ""
                           :following false}}}))

(defn- demo-payload-for-args [args-map]
  (let [req    (:request args-map)
        u      (str (:url req))
        method (or (:method req) :get)]
    (cond
      ;; /users/login (POST) — Spec User wire shape.
      (and (= method :post) (str/ends-with? u "/users/login"))
      {:user demo-user}

      ;; /users (POST, register) — Spec User wire shape. Must precede
      ;; the bare /users (GET, current user) clause.
      (and (= method :post) (str/ends-with? u "/users"))
      {:user demo-user}

      ;; GET /user — current-user session restore. We deliberately do
      ;; NOT auto-restore here (return empty payload so the schema
      ;; decode fails into the failure branch and the auth machine
      ;; falls back to :idle). The Playwright spec relies on the app
      ;; starting unauthenticated.
      (and (= method :get) (str/ends-with? u "/user"))
      {}

      ;; POST /articles/:slug/comments — synthesise the saved Comment
      ;; the optimistic submit path expects.
      (and (= method :post) (re-find #"/articles/[^/]+/comments$" u))
      (counter-comment (some-> req :body :comment :body))

      (str/includes? u "/articles/feed")
      {:articles [] :articlesCount 0}

      (re-find #"/articles/[^/]+/comments" u)
      {:comments []}

      (re-find #"/articles/[^/?]+$" u)
      {:article (first demo-articles)}

      (or (str/ends-with? u "/articles") (str/includes? u "/articles?"))
      {:articles demo-articles :articlesCount (count demo-articles)}

      (str/includes? u "/tags")
      {:tags demo-tags}

      (str/includes? u "/profiles/")
      {:profile {:username "stub-bot" :bio "" :image "" :following false}}

      :else {})))

(rf/reg-fx :rf.http/managed.realworld-demo
  {:doc       "Demo override for :rf.http/managed: routes by URL to canned
               Conduit-shaped responses so the example runs standalone
               without a backend. Delegates to :rf.http/managed-canned-success
               with a synthesised :value per Spec 014 §Testing.

               NOTE on the raw js/setTimeout below. The deferred work
               is an fx invocation (`:rf.http/managed-canned-success`),
               not a dispatch, so the framework's `:dispatch-later`
               path is not a 1:1 swap. The timer is here ONLY to delay
               the canned reply long enough for the `:loading` UI state
               to be observable in the demo; production app code should
               never use raw `js/setTimeout` — use `:dispatch-later` or
               (for an fx invocation) drive it through a tiny
               framework dispatch (e.g. dispatch-later to a private
               event whose handler issues the fx) so framework time
               controls (Tool-Pair time-travel, the documented
               `:dispatch-later` nil-override seam) still apply."
   :platforms #{:server :client}}
  (fn fx-managed-demo-stub [frame-ctx args-map]
    (let [payload (demo-payload-for-args args-map)
          stub-fn (registrar/handler :fx :rf.http/managed-canned-success)]
      ;; Drive the framework-shipped canned-success stub to get the
      ;; correct reply shape (default reply addressing or explicit
      ;; :on-success — Spec 014 §Reply addressing).
      (when stub-fn
        ;; Demo-only artificial latency — see the fx doc above.
        (js/setTimeout
          (fn [] (stub-fn frame-ctx (assoc args-map :value payload)))
          20)))))

;; React root named `react-root` (not `root`) so it does NOT collide with
;; the `root-view` reg-view above. Held in an atom and populated lazily
;; inside `run` rather than at ns-load (rf2-gkf9). Multiple example
;; namespaces (this one, nine-states, boot, long-running-work, websocket)
;; are co-required by the browser-test bundle's wrapper test namespaces
;; — and the test harness shares a single `#app` mount point. Performing
;; `create-root` at ns-load would race multiple roots onto the same
;; container, leaking example-A's mount into example-B's tests (and
;; emitting React "createRoot called twice" warnings). Mounting only in
;; `run` keeps ns-load DOM-side-effect-free.
(defonce react-root (atom nil))

;; ============================================================================
;; HTTP REQUEST INTERCEPTOR — Spec 014 §Middleware
;; ============================================================================
;;
;; Demonstrates the per-frame request interceptor surface: a single
;; `:before` fn injects a Bearer token from the auth slice, so every
;; outbound :rf.http/managed request that crosses this frame picks the
;; auth header up automatically. With this pattern, individual call
;; sites (`articles.cljs`, `comments.cljs`, ...) don't need to thread
;; the token through the request builder per-call — the auth slice is
;; the single source of truth and the interceptor is the single read
;; site.
;;
;; The interceptor returns the ctx unchanged when no token is present,
;; so login / register / public-read endpoints are unaffected. Compare
;; with `realworld.http/request` (which threads the header explicitly)
;; — both shapes work; the interceptor pattern is the lighter option
;; once the auth slot is established.

(defn- bearer-auth-interceptor [ctx]
  (let [token (some-> (rf/get-frame-db (:frame ctx))
                      :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))

(defn ^:export run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-slim-adapter/adapter)
  ;; Override :rf.http/managed on the default frame so all the realworld
  ;; feature HTTP calls land on the demo stub (no real backend required).
  (rf/reg-frame :rf/default {:doc          "Realworld demo frame."
                             :fx-overrides {:rf.http/managed :rf.http/managed.realworld-demo}})
  ;; Register the Bearer-auth interceptor at app boot. Order matters:
  ;; before :app/initialise dispatches, since session-restore will fire
  ;; authenticated requests as soon as the JWT is hydrated.
  (rf/reg-http-interceptor
    {:frame  :rf/default
     :id     :realworld/bearer-auth
     :before bearer-auth-interceptor})
  ;; The orchestrator serves this example at /realworld/; strip that
  ;; prefix before the route matcher sees the URL so :route/home (path "/")
  ;; matches.
  (routing/set-base-path! "/realworld")
  (rf/dispatch-sync [:app/initialise])
  (routing/install-router!)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root [root-view])))
