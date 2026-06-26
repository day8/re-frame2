(ns realworld.core
  "Entry point for the RealWorld (Conduit) example.

   Wires the app together:
   - Pulls in every feature namespace (each registers its own events/subs/fx).
   - Defines :app/initialise (the boot event, run via the frame's :initial-events).
   - Defines the root-view that switches on :rf.route/id.
   - Mounts the React root (the `frame-provider {:id …}` ENSURE form creates +
     seeds the app frame in one spot) and installs the URL listener.

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
   the demo entry below installs a canned-stub override so the CLJS
   test fixtures (in the framework test tree at
   `implementation/adapters/reagent/test/re_frame/realworld_cljs_test.cljs`;
   the example tree is test-free) run without a network."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; Managed-HTTP ships in day8/re-frame2-http.
            ;; Requiring re-frame.http.managed at app boot is what
            ;; triggers its load-time fx registrations (`:rf.http/managed`
            ;; and family) and publishes the late-bind hooks; without
            ;; it, dispatching `:rf.http/managed` would fail with
            ;; :rf.error/no-such-fx. RealWorld is the canonical Spec 014
            ;; demo so the require is mandatory here.
            [re-frame.http.managed]
            ;; RealWorld ships without a backend; the demo routes
            ;; :rf.http/managed through a per-URL stub that delegates
            ;; to :rf.http/managed-canned-success. The canned-stub fx
            ;; ids register from re-frame.http.test-support, which the
            ;; demo opts in to here. A real Conduit backend deployment
            ;; would drop this require alongside the demo override.
            [re-frame.http.test-support]
            ;; SSR ships in day8/re-frame2-ssr. Requiring
            ;; re-frame.ssr at app boot publishes the late-bind hooks
            ;; (`:ssr/render-tree-hash` etc.) and registers the
            ;; `:rf/hydrate` handler — the RealWorld ssr.cljc helper
            ;; calls `rf/render-tree-hash` and dispatches
            ;; `:rf/hydrate`. Without the require those calls raise
            ;; :rf.error/ssr-artefact-missing.
            [re-frame.ssr]
            [re-frame.adapter.reagent :as reagent-adapter]
            [realworld-shared.avatar :as avatar]
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

(rf/reg-event :app/initialise
  {:doc "App boot. Fans out to per-feature initialisers. `:auth/initialise` is
         NOT in this fan-out — it consumes the RECORDABLE-GENERATOR
         `:auth.session/token` coeffect (EP-0017). The `:dispatch` fx does not
         forward `:rf.cofx`, so issuing it as a plain boundary dispatch in `run`
         (rather than through this fan-out) keeps the generated token on its own
         boot dispatch token, where it is recorded."}
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

;; Durable app-db classification (EP-0025): the JWT at [:auth :token] is
;; sensitive — declare it via the commit-plane `:sensitive` classification
;; effect, returned alongside `:db` from an event the frame runs at creation
;; (`:initial-events`, before any boot dispatch or off-box egress). The
;; framework folds the declaration into the per-frame elision registry, so any
;; off-box egress (Xray/observability capture, an off-box tool, an SSR
;; hydration payload) sees the token redacted while on-box rendering keeps the
;; raw value. (EP-0025 removed the durable `:sensitive {:app-db …}` frame
;; annotation — a frame is not app-db's definition site.)
(rf/reg-event :auth/classify-token
  {:doc "Classifies the durable JWT path [:auth :token] sensitive at frame
         creation (EP-0025 commit-plane classification effect)."}
  (fn handler-auth-classify-token [{:keys [db]} _]
    {:db db :sensitive [[:auth :token]]}))

;; ============================================================================
;; APP-SHELL VIEWS
;; ============================================================================

(reg-view ^{:doc "The site header. Shows different links based on auth state.
                  Each nav link carries a stable data-testid hook — the
                  idiomatic way to give UI tests a target that survives
                  styling churn (no brittle class / text matching)."}
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
           ;; The official RealWorld navbar shows the authenticated
           ;; user's avatar (`.user-pic`) next to their name; default-avatar
           ;; covers a nil/empty image.
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

(reg-view ^{:doc "Renders a confirm dialog when navigation is blocked by a
                  :can-leave guard. Reads the runtime-db [:rf.runtime/routing :pending-navigation] slot via the `:rf/pending-navigation` sub."}
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

(reg-view ^{:doc "App-level root. Switches on :rf.route/id to render the active page."}
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
;; DEMO STUBS
;;
;; The realworld example would normally hit the hosted Conduit API
;; (https://api.realworld.show/api — `realworld.http/api-base`), which is
;; unreliable for headless smoke and slow for a demo. Override
;; :rf.http/managed with a small in-process stub that synthesises the
;; canonical Spec 014 reply shape for the routes the demo actually
;; exercises (global feed, tags, profile). Anything not covered resolves
;; to an empty-payload success — enough for the app shell + main feed
;; to render.
;;
;; The override delegates straight to :rf.http/managed-canned-success
;; with a per-URL :value payload and an `:after-ms` delay so
;; the framework defers the reply via `:dispatch-later` — observable in
;; the tape, time-travel-safe, NOT raw `js/setTimeout`. This is the same
;; shape Spec 014 §Testing documents — just routed by URL inspection in a
;; wrapper fx so the demo doesn't have to know one URL ahead of time.
;; ----------------------------------------------------------------------------

(def ^:private demo-reply-delay-ms
  "How long the demo stub defers each canned reply (via the canned-success
   fx's `:after-ms`, dispatched through `:dispatch-later` — observable in
   the tape, time-travel-safe, NOT raw `js/setTimeout`). Small but non-zero
   so the `:loading` UI state is observable in the standalone demo. A
   demo-seam knob, not a production value."
  20)

;; A corpus larger than one page (25 articles) so the official
;; limit/offset pagination is observable in the standalone demo: with the
;; fixed page size of 10 (`realworld.http/page-size`) that is three pages.
;; `demo-articles` keeps its hand-written first two cards (so the article
;; detail / favorites paths see familiar slugs) and pads the rest
;; programmatically.
(def ^:private demo-articles
  (into [{:slug "hello-conduit"
          :title "Hello, Conduit"
          :description "A short greeting from the realworld stub."
          ;; A markdown body so the article-detail page exercises the
          ;; sanitized CommonMark renderer (realworld-shared.markdown/render —
          ;; render): headings, bold/italic, inline + fenced code, a safe
          ;; link, lists, plus full-CommonMark shapes a hand-rolled subset would
          ;; miss (a table, a nested list). The renderer emits
          ;; hiccup (never raw HTML), so this is real markup while any injected
          ;; `<script>` / `javascript:` link in user content degrades to inert
          ;; escaped text.
          :body (str "# Hello, Conduit\n\n"
                     "This article is served by the demo `:rf.http/managed` "
                     "override and rendered as **markdown** with *emphasis*.\n\n"
                     "See the [RealWorld spec](https://github.com/gothinkster/realworld) "
                     "for the reference behaviour.\n\n"
                     "## Highlights\n\n"
                     "- Sanitized by construction (hiccup, never raw HTML)\n"
                     "- Full CommonMark via `nextjournal/markdown`\n"
                     "  - tables, nested lists, images\n"
                     "  - safe-by-construction link/image schemes\n\n"
                     "| Feature | Status |\n"
                     "| --- | --- |\n"
                     "| markdown | CommonMark |\n"
                     "| links | scheme-allowlisted |\n\n"
                     "```clojure\n(rf/reg-event :hello (fn [{:keys [db]} _] {:db db}))\n```\n\n"
                     "> A blockquote, for good measure.")
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
                   :following false}}]
        (for [n (range 3 26)]
          {:slug           (str "article-" n)
           :title          (str "Demo article " n)
           :description    (str "Canned demo article number " n ".")
           :body           "More canned demo content."
           :tagList        ["demo"]
           :createdAt      "2026-03-01T00:00:00Z"
           :updatedAt      "2026-03-01T00:00:00Z"
           :favorited      false
           :favoritesCount 0
           :author         {:username "stub-bot"
                            :bio "A friendly stub."
                            :image ""
                            :following false}})))

(defn- parse-int-param
  "Pull an integer query param `k` (e.g. \"limit\") out of a URL string, or
   nil when absent / unparseable."
  [u k]
  (when-let [m (re-find (re-pattern (str "[?&]" k "=(\\d+)")) u)]
    (js/parseInt (second m) 10)))

(defn- page-of
  "Return the limit/offset window of `articles` for the demo stub, plus the
   grand `articlesCount` — the canonical Conduit list-response shape. Reads
   `limit` / `offset` straight off the URL (defaulting to the whole list when
   absent), so the stub paginates exactly like the real backend."
  [articles u]
  (let [total  (count articles)
        offset (or (parse-int-param u "offset") 0)
        limit  (or (parse-int-param u "limit") total)
        window (->> articles (drop offset) (take limit) vec)]
    {:articles window :articlesCount total}))

(def ^:private demo-tags
  ["intro" "demo" "clojure" "re-frame"])

(def ^:private demo-user
  "Canned `User` payload returned by /users/login, /users (register),
   and /user (session restore). The auth fixtures submit matching
   credentials at the login form; the stub doesn't verify the body,
   it just synthesises the success reply the auth machine expects."
  {:email    "demo@conduit.dev"
   :token    "stub.demo.jwt"
   :username "demo"
   :bio      "Canned demo user."
   :image    ""})

(defn- canned-comment
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
      ;; falls back to :idle). The auth fixtures rely on the app
      ;; starting unauthenticated.
      (and (= method :get) (str/ends-with? u "/user"))
      {}

      ;; POST /articles/:slug/comments — synthesise the saved Comment
      ;; the optimistic submit path expects.
      (and (= method :post) (re-find #"/articles/[^/]+/comments$" u))
      (canned-comment (some-> req :body :comment :body))

      ;; GET /articles/feed — the authenticated feed. Paged from the same
      ;; corpus so the home "Your Feed" tab paginates like the global feed
      ;; (limit/offset off the URL, grand articlesCount in the reply).
      (str/includes? u "/articles/feed")
      (page-of demo-articles u)

      (re-find #"/articles/[^/]+/comments" u)
      {:comments []}

      (re-find #"/articles/[^/?]+$" u)
      {:article (first demo-articles)}

      ;; GET /articles[?tag=…|author=…|favorited=…][&limit&offset] — the
      ;; global / tag / profile lists. `page-of` reads limit/offset off the
      ;; URL and returns the windowed page + the grand articlesCount.
      (or (str/ends-with? u "/articles") (str/includes? u "/articles?"))
      (page-of demo-articles u)

      (str/includes? u "/tags")
      {:tags demo-tags}

      (str/includes? u "/profiles/")
      {:profile {:username "stub-bot" :bio "" :image "" :following false}}

      :else {})))

(rf/reg-fx :realworld.demo/http-stub
  {:doc       "Demo override for :rf.http/managed: routes by URL to
               canned Conduit-shaped responses so the example runs
               standalone without a backend.

               Delegates straight to the framework-shipped
               `:rf.http/managed-canned-success` (Spec 014 §Testing) with
               the per-URL canned payload and `:after-ms`
               (`demo-reply-delay-ms`): the framework defers the
               reply via `:dispatch-later` —
               observable in the tape, time-travel-safe, NOT raw
               `js/setTimeout`. The delay lets the `:loading` UI state be
               observable; `:after-ms` expresses the schedule-reply
               → `:dispatch-later` → deliver-reply chain as one
               parameter of the same canned effect."
   :platforms #{:server :client}}
  (fn fx-managed-demo-stub [frame-ctx args-map]
    (let [payload (demo-payload-for-args args-map)
          stub    (registrar/handler :fx :rf.http/managed-canned-success)]
      (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :value payload)))))

;; React root named `react-root` (not `root`) so it does NOT collide with
;; the `root-view` reg-view above. Held in an atom and populated lazily
;; inside `run` rather than at ns-load. Multiple example namespaces
;; (this one, nine-states, boot, long-running-work, websocket)
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
;; so login / register / public-read endpoints are unaffected (an
;; unauthenticated user has no token to send).
;;
;; SINGLE SOURCE OF TRUTH: this is the ONE place the Bearer
;; header is written. `realworld.http/request` composes the request map and
;; leaves the token to this interceptor. Centralising the read here keeps the
;; example carried-frame-correct: the interceptor reads from `(:frame ctx)`
;; (the frame the cascade actually runs under, EP-0002), so the
;; header tracks a non-default / multi-frame mount rather than a hard-coded
;; `:rf/default`. One read site is the recommended production shape — wiring the
;; token into `rh/request` as well would hard-code `:rf/default` and bypass the
;; carried invariant.

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
;; The official RealWorld browser/E2E harness sometimes reads app session state
;; through a global accessor rather than poking the framework's internals. This
;; installs `window.__conduit_debug__` with `getToken` / `getAuthState` /
;; `getCurrentUser`, reading the live `:rf/default` frame's app-db.
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
               :getAuthState   (fn [] (name (or (some-> (rf/app-db-value frame-id)
                                                         :auth :user (#(if % :authed :anonymous)))
                                                 :anonymous)))
               :getCurrentUser (fn [] (clj->js (some-> (rf/app-db-value frame-id) :auth :user)))})))

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  ;; Override :rf.http/managed on the default frame so all the realworld
  ;; feature HTTP calls land on the demo stub (no real backend required).
  ;; The auth-guard interceptor (registered in routing.cljs via
  ;; `reg-interceptor` and referenced BY ID per EP-0022 in the provider's
  ;; `:interceptors` below) is prepended to
  ;; every event in this frame (Spec 002 §`:interceptors`) — it short-circuits
  ;; for all non-navigation events and redirects unauthenticated
  ;; `:rf.route/navigate` to `:requires-auth`-tagged routes to login (Spec
  ;; 012 §Redirects and guards). This is what makes the `:requires-auth`
  ;; tags on :realworld.user/settings / :realworld.editor/new / :realworld.editor/edit actually
  ;; protect those routes.
  ;; EP-0002: the runtime never synthesises a frame
  ;; from absence, and URL ownership is an EXPLICIT declaration — this frame
  ;; carries `:url-bound? true` so it owns the browser URL (Spec 012
  ;; §Multi-frame routing). The frame is CREATED, configured, and SEEDED in ONE
  ;; spot: the `frame-provider {:id …}` ENSURE form at the render root below.
  ;; First mount creates `:rf/default`, applies this config, and runs
  ;; `:initial-events` once; hot reload REUSES the frame WITHOUT re-seeding
  ;; (durable app-db survives; `:initial-events` are re-recorded, not replayed).
  ;; EP-0025 (durable egress classification): the JWT lives at [:auth :token]
  ;; in app-db, so that path is classified `:sensitive`. Classification rides
  ;; the commit-plane `:sensitive` effect (`:auth/classify-token`, the FIRST of
  ;; the frame's `:initial-events` at frame creation, before the session-restore
  ;; token write and any off-box egress). Projection happens at the trust boundary, so any off-box
  ;; egress — Xray/observability capture, an off-box tool, an SSR hydration
  ;; payload — sees the token redacted while on-box rendering (the navbar, the
  ;; live header the request actually sends) keeps the raw value.
  ;;
  ;; The outbound `Authorization` Bearer header is NOT declared here: it is
  ;; already on the framework's immutable built-in HTTP carrier denylist
  ;; (Spec 014 §Privacy — alongside Cookie / X-API-Key / …), so it is redacted
  ;; off-box with no frame config. The `:sensitive :http :headers` extension is
  ;; for APP-SPECIFIC carriers (e.g. an "X-Tenant-Key"); this app sends none,
  ;; so it declares no HTTP carriers — over-declaring a built-in would only
  ;; teach a redundant ritual.
  ;;
  ;; We also do NOT classify [:auth :login-form] / [:auth :register-form]: the
  ;; password draft is transient form state, owned by its registration, not a
  ;; durable frame fact (and is never sent off-box from app-db). This is the
  ;; canonical issue-5 case from the EP, surfaced only where the data is real.
  ;; Register the Bearer-auth interceptor at app boot. Order matters:
  ;; before the frame's `:initial-events` run, since session-restore will fire
  ;; authenticated requests as soon as the JWT is hydrated.
  (rf/reg-http-interceptor :realworld/bearer-auth
                           {:before bearer-auth-interceptor})
  ;; The orchestrator serves this example at /realworld/; strip that
  ;; prefix before the route matcher sees the URL so :realworld/home (path "/")
  ;; matches. Set before the ENSURE provider renders so the `:initial-events`
  ;; URL sync (below) and the popstate listener see the stripped URL.
  (routing/set-base-path! "/realworld")
  ;; Wire the popstate listener for Back/Forward (Spec 012 §popstate drives the
  ;; URL owner). This example serves from a `/realworld/` sub-path and strips it
  ;; in `current-url`, so it keeps its own base-path-aware listener rather than
  ;; the framework's `rf/install-history-listener!`. The listener targets the
  ;; URL owner resolved AT POP TIME and is idempotent (hot-reload safe). The
  ;; INITIAL URL→slice sync is seeded once via the frame's `:initial-events`
  ;; (`:rf.route/handle-url-change` below), which run synchronously at frame
  ;; creation — so a deep link lands on the right route on first paint without
  ;; depending on React's render-flush timing.
  (routing/install-router!)
  ;; Conformance-contract surface — NOT a re-frame2 pattern; see
  ;; install-conduit-debug! above. The external RealWorld suite may read it.
  (install-conduit-debug! :rf/default)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; ENSURE form: create + configure + seed the app frame in ONE spot. First
    ;; mount creates `:rf/default`, applies `:url-bound? true` + the demo
    ;; `:fx-overrides` + the auth-guard interceptor, and runs `:initial-events`
    ;; once; hot reload reuses it without re-seeding.
    ;;
    ;; `:initial-events` run in order at frame creation:
    ;;   - `:auth/classify-token` — classify the durable JWT path [:auth :token]
    ;;     `:sensitive` (EP-0025) BEFORE the token is written, so it is redacted
    ;;     off-box from the first write on.
    ;;   - `:auth/initialise` — EP-0017 session restore. It declares
    ;;     `:rf.cofx/requires [:auth.session/token]`, a RECORDABLE GENERATOR
    ;;     (auth.cljs) whose supplier reads localStorage ONCE at processing-start;
    ;;     the value rides this setup-step's dispatch token and is recorded, so
    ;;     replay / epoch-restore re-presents the captured token verbatim rather
    ;;     than re-reading localStorage. Ordered BEFORE `:app/initialise` so the
    ;;     token is in app-db before the bearer-auth interceptor fires any
    ;;     authenticated request. (A unit test stamps `{:rf.cofx …}` at the
    ;;     dispatch site as a node-side stub; in the browser the generator
    ;;     supplies the value, so no explicit cofx is needed here.)
    ;;   - `:app/initialise` — fans out to the per-feature initialisers.
    ;;   - `:rf.route/handle-url-change` — the initial URL→slice sync, computed
    ;;     once from the base-path-stripped current URL. Ordered LAST so the
    ;;     auth-guard interceptor sees the restored session when redirecting an
    ;;     unauthenticated deep link to a `:requires-auth` route.
    (rdc/render @react-root
                [rf/frame-provider {:id              :rf/default
                                    :doc             "Realworld demo frame."
                                    :url-bound?      true
                                    :initial-events  [[:auth/classify-token]
                                                      [:auth/initialise]
                                                      [:app/initialise]
                                                      [:rf.route/handle-url-change
                                                       (routing/current-url)]]
                                    :interceptors    [:realworld.routing/auth-guard]
                                    :fx-overrides    {:rf.http/managed :realworld.demo/http-stub}}
                 [root-view]])))
