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
   - Mounts the React root. One `frame-provider {:id …}` at the render root
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
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; Managed HTTP ships in its own artefact. Requiring it registers
            ;; the `:rf.http/managed` fx (and its family), so dispatching it
            ;; resolves to something.
            [re-frame.http.managed]
            ;; The demo routes `:rf.http/managed` through a per-URL stub that
            ;; delegates to `:rf.http/managed-canned-success` — a canned-reply
            ;; fx that ships with the framework's test support. A real Conduit
            ;; backend would drop both this require and the demo override.
            [re-frame.http.test-support]
            ;; SSR ships in its own artefact. Requiring it registers the
            ;; `:rf/hydrate` handler and the SSR helpers ssr.cljc leans on
            ;; (`rf/render-tree-hash`).
            [re-frame.ssr]
            [re-frame.adapter.reagent :as reagent-adapter]
            [realworld-shared.avatar :as avatar]
            [realworld-http.schema]
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
         entry in the frame-provider's `:initial-events` instead, because it
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
;; One path is all this app has to classify by hand. The outbound
;; `Authorization` Bearer header is already on the framework's built-in HTTP
;; carrier denylist, so it's redacted off-box with zero config. And the
;; login / register password drafts at [:auth :login-form] /
;; [:auth :register-form] are throwaway form state that never leaves app-db,
;; so there's nothing there to protect. See the keep-secrets how-to:
;; ../../../docs/core/how-to/keep-secrets-out-of-traces.md
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
;; DEMO STUBS
;;
;; Out of the box this example talks to no server at all. Normally it would
;; hit the hosted Conduit API (https://api.realworld.show/api —
;; `realworld-http.http/api-base`), but a live backend is slow and flaky for a
;; demo, and we'd like you to be able to clone-and-run on a plane. So we
;; override `:rf.http/managed` with a tiny in-process stub that hand-rolls a
;; plausible reply for the routes the demo actually exercises (global feed,
;; tags, profile). Anything we didn't bother stubbing just comes back as an
;; empty success — enough for the shell and the main feed to render.
;;
;; The override doesn't fake the timing itself. It hands off to the
;; framework's `:rf.http/managed-canned-success` with a per-URL `:value`
;; payload and an `:after-ms` delay, so the reply rides out on
;; `:dispatch-later` — which means it shows up in the trace and survives
;; time-travel, instead of being an invisible raw `js/setTimeout`. A thin
;; wrapper fx routes by URL so the demo doesn't have to commit to one URL up
;; front.
;; ----------------------------------------------------------------------------

(def ^:private demo-reply-delay-ms
  "How long the demo stub sits on each canned reply before delivering it (the
   canned-success fx's `:after-ms`, routed through `:dispatch-later`). Small
   but not zero, on purpose — without a beat of delay you'd never see the
   `:loading` state flash by. A demo knob, not a production value."
  20)

;; Twenty-five articles — deliberately more than one page — so you can
;; actually watch pagination work in the standalone demo. At the fixed page
;; size of 10 (`realworld-http.http/page-size`) that's three pages. The first two
;; cards are hand-written (so the article-detail and favorites paths land on
;; familiar slugs); the rest are padded out in a loop, because nobody needs
;; to read twenty-three more lovingly-crafted fake articles.
(def ^:private demo-articles
  (into [{:slug "hello-conduit"
          :title "Hello, Conduit"
          :description "A short greeting from the realworld stub."
          ;; A deliberately rich markdown body, so the article-detail page
          ;; gives the sanitized CommonMark renderer
          ;; (realworld-shared.markdown/render) a real workout: headings,
          ;; bold/italic, inline + fenced code, a safe link, lists, and the
          ;; full-CommonMark shapes a hand-rolled subset always forgets — a
          ;; table, a nested list. The renderer emits hiccup, never raw HTML,
          ;; which is the whole trick: this renders as genuine markup, while
          ;; any `<script>` or `javascript:` link smuggled in via user content
          ;; comes out as inert, escaped text.
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
  "Fish an integer query param `k` (e.g. \"limit\") out of a URL string.
   nil when it's absent or doesn't look like a number."
  [u k]
  (when-let [m (re-find (re-pattern (str "[?&]" k "=(\\d+)")) u)]
    (js/parseInt (second m) 10)))

(defn- page-of
  "Slice out one page of `articles` for the demo stub, and report the grand
   `articlesCount` alongside it — the exact list-response shape Conduit uses.
   It reads `limit` / `offset` straight off the URL (and shows the whole list
   when they're missing), so the stub paginates just like the real backend
   would, and the page-number control downstream can't tell the difference."
  [articles u]
  (let [total  (count articles)
        offset (or (parse-int-param u "offset") 0)
        limit  (or (parse-int-param u "limit") total)
        window (->> articles (drop offset) (take limit) vec)]
    {:articles window :articlesCount total}))

(def ^:private demo-tags
  ["intro" "demo" "clojure" "re-frame"])

(def ^:private demo-user
  "The one user this demo knows about — handed back by /users/login,
   /users (register), and /user (session restore). The stub doesn't check
   your password (or anything else in the body); it just plays back the
   success reply the auth machine is hoping for. Security theatre it is not."
  {:email    "demo@conduit.dev"
   :token    "stub.demo.jwt"
   :username "demo"
   :bio      "Canned demo user."
   :image    ""})

(defn- canned-comment
  "Fake up the saved-Comment reply for POST /articles/:slug/comments. The
   reply is `{:comment <Comment>}`; back in comments.cljs the handler swaps
   the optimistic temp card for this saved row, matching on `:id`. We mint a
   fresh random id every call so two comments posted in a row don't collide
   on their React `:key`."
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
      ;; /users/login (POST) — the User wire shape.
      (and (= method :post) (str/ends-with? u "/users/login"))
      {:user demo-user}

      ;; /users (POST, register) — the User wire shape. Must precede the
      ;; bare /users (GET, current user) clause.
      (and (= method :post) (str/ends-with? u "/users"))
      {:user demo-user}

      ;; GET /user — session restore on boot. We deliberately synthesise a
      ;; DECODE FAILURE (not a success with an empty body): a real backend
      ;; returning `{}` would fail `:decode schema/UserResponse`'s schema
      ;; validation, and `:begin-restore`'s `:on-failure` would fire. The
      ;; canned-success stub never runs `:decode`, though — an empty-map
      ;; *success* reply would land in `:on-success` instead, taking the auth
      ;; machine to `:authed` with a nil `:user` (a broken, half-authenticated
      ;; state). Routing this through `:rf.http/managed-canned-failure` (see
      ;; the `::decode-failure` sentinel below) reproduces the real failure
      ;; path. Net effect — the demo always opens logged-out, so you get to
      ;; click "Sign in" and watch the flow rather than arriving mysteriously
      ;; (and brokenly) authenticated.
      (and (= method :get) (str/ends-with? u "/user"))
      ::decode-failure

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
  {:doc       "The demo's stand-in for `:rf.http/managed`. It looks at the URL,
               picks a matching canned Conduit-shaped reply, and lets the
               example run with no backend in sight.

               It doesn't reinvent the delivery machinery — it hands the
               payload to the framework's `:rf.http/managed-canned-success`
               with `:after-ms` set to `demo-reply-delay-ms`. The framework
               then defers the reply via `:dispatch-later`, so it shows up in
               the trace and survives time-travel instead of being a raw
               `js/setTimeout`. The little delay is what makes the `:loading`
               state actually visible.

               `demo-payload-for-args` signals a genuine failure (rather than
               a payload) with the `::decode-failure` sentinel — GET /user's
               session-restore stub is the one route the demo wants to FAIL,
               so it delegates to `:rf.http/managed-canned-failure` instead of
               `-success`. `:rf.http/managed-canned-success` never runs
               `:decode`, so handing it an empty body would incorrectly land
               on the `:on-success` branch."
   :platforms #{:server :client}}
  (fn fx-managed-demo-stub [frame-ctx args-map]
    (let [payload (demo-payload-for-args args-map)]
      (if (= ::decode-failure payload)
        (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
          (stub frame-ctx (assoc args-map
                                  :after-ms demo-reply-delay-ms
                                  :kind     :rf.http/decode-failure
                                  :tags     {:schema-validation-failure? true})))
        (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx (assoc args-map :after-ms demo-reply-delay-ms :value payload)))))))

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
;; ../../../docs/async/http.md#interceptors-stamp-every-request-once
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

(defn run []
  ;; Tell re-frame2 to render through Reagent. This is the one genuinely
  ;; frameworky step, and it has to come before any frame mounts; everything
  ;; below it is just this app wiring itself up.
  (rf/init! reagent-adapter/adapter)
  ;; Register the Bearer-auth interceptor (defined above) so it stamps the
  ;; `Authorization` header onto outbound managed requests. Order matters:
  ;; register it before the frame's `:initial-events` run, so it's already on
  ;; duty when session-restore fires its first authenticated request.
  (rf/reg-http-interceptor :realworld/bearer-auth
                           {:before bearer-auth-interceptor})
  ;; Hang the conformance accessor on the window for the external RealWorld
  ;; suite. Test seam, not a pattern — see install-conduit-debug! above.
  (install-conduit-debug! :rf/default)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    ;; Here's where the frame is born. A single `frame-provider {:id …}` at
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
    ;;     navigate to one while logged out and the runtime's ONE navigation gate
    ;;     refuses and dispatches `:rf.route/entry-blocked`, which redirects to
    ;;     login. That's what gives the `:requires-auth` tags on settings / new /
    ;;     edit teeth — declaratively on the route, fail-closed through every
    ;;     door, not as a hand-rolled interceptor.
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
    ;; There is no explicit `:rf.route/handle-url-change` step any more — the
    ;; `:url-bound? true` frame lifecycle (rf2-g8pbwg) does the first
    ;; URL→slice sync itself, automatically, AFTER every `:initial-events`
    ;; step above has run — which is exactly the ordering this app needs: by
    ;; then the session is restored, so when a deep link to a
    ;; `:requires-auth` route runs its `:can-enter` gate, it's judging a
    ;; logged-in user correctly rather than bouncing them by mistake.
    (rdc/render @react-root
                [rf/frame-provider {:id              :rf/default
                                    :doc             "Realworld demo frame."
                                    :url-bound?      true
                                    :url-strategy    routing/url-strategy
                                    :initial-events  [[:auth/classify-token]
                                                      [:auth/initialise]
                                                      [:app/initialise]]
                                    :fx-overrides    {:rf.http/managed :realworld.demo/http-stub}}
                 [root-view]])))
