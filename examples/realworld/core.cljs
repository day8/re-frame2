(ns realworld.core
  "Entry point for the RealWorld (Conduit) example.

   Wires the app together:
   - Pulls in every feature namespace (each registers its own events/subs/fx).
   - Defines :app/initialise (the :on-create event).
   - Defines the root-view that switches on :rf.route/id.
   - Mounts the React root and installs the URL listener.

   This is single-file glue; the per-feature work lives in:
     auth.cljs             — login / register / session-restore
     articles.cljs         — global feed + tag list + home page
     comments.cljs         — article detail + comments
     article_editor.cljs   — create / edit / delete article
     profile.cljs          — public profile routes
     favorites.cljs        — favorite toggle + your-feed slice
     tags.cljs             — home-page query helpers
     settings.cljs         — user settings page
     routing.cljs          — route registrations + router wiring
     schema.cljs           — Malli schemas for the example slices
     http.cljs             — request-builder + retry policy for :rf.http/managed
     ssr.cljc              — hydration payload helper for the RealWorld app

   This is the canonical Spec 014 (`:rf.http/managed`) demo per
   rf2-kauy / rf2-o8t6. Every Conduit endpoint goes via the
   framework-shipped managed-HTTP fx; the demo entry below installs a
   canned-stub override so the headless smoke and Playwright run without
   a network."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; rf2-5kpd: managed-HTTP ships in day8/re-frame-2-http.
            ;; Requiring re-frame.http-managed at app boot is what
            ;; triggers its load-time fx registrations (`:rf.http/managed`
            ;; and family) and publishes the late-bind hooks; without
            ;; it, dispatching `:rf.http/managed` would fail with
            ;; :rf.error/no-such-fx. RealWorld is the canonical Spec 014
            ;; demo so the require is mandatory here.
            [re-frame.http-managed]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.reagent :as reagent-adapter]
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
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

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
          [:dispatch [:auth.login-form/initialise]]
          [:dispatch [:auth.register-form/initialise]]]}))

;; ============================================================================
;; APP-SHELL VIEWS
;; ============================================================================

(reg-view ^{:doc "The site header. Shows different links based on auth state."}
          header []
  (let [authed? @(subscribe [:auth/authenticated?])
        user    @(subscribe [:auth/user])]
    [:nav.navbar.navbar-light
     [:div.container
      [routing/route-link {:to :route/home :class "navbar-brand"} "conduit"]
      [:ul.nav.navbar-nav.pull-xs-right
       [:li.nav-item
        [routing/route-link {:to :route/home :class "nav-link"} "Home"]]
       (if authed?
         [:<>
          [:li.nav-item
           [routing/route-link {:to :route/editor :class "nav-link"}
            [:i.ion-compose] " New Article"]]
          [:li.nav-item
           [routing/route-link {:to :route/settings :class "nav-link"}
            [:i.ion-gear-a] " Settings"]]
          [:li.nav-item
           [routing/route-link {:to :route/profile
                                :params {:username (:username user)}
                                :class "nav-link"}
            (:username user)]]
          [:li.nav-item
           [:a.nav-link {:href "#"
                         :on-click #(do (.preventDefault %)
                                        (dispatch [:auth/flow [:auth/logout]]))}
            "Logout"]]]
         [:<>
          [:li.nav-item
           [routing/route-link {:to :route/login :class "nav-link"} "Sign in"]]
          [:li.nav-item
           [routing/route-link {:to :route/register :class "nav-link"} "Sign up"]]])]]]))

(reg-view footer []
  [:footer
   [:div.container
    [routing/route-link {:to :route/home :class "logo-font"} "conduit"]
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
     [routing/route-link {:to :route/home} "Home"]]))

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

(defn- demo-payload-for-url [url]
  (let [u (str url)]
    (cond
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
               with a synthesised :value per Spec 014 §Testing."
   :platforms #{:server :client}}
  (fn fx-managed-demo-stub [frame-ctx args-map]
    (let [url     (-> args-map :request :url)
          payload (demo-payload-for-url url)
          stub-fn (registrar/handler :fx :rf.http/managed-canned-success)]
      ;; Drive the framework-shipped canned-success stub to get the
      ;; correct reply shape (default reply addressing or explicit
      ;; :on-success — Spec 014 §Reply addressing).
      (when stub-fn
        (js/setTimeout
          (fn [] (stub-fn frame-ctx (assoc args-map :value payload)))
          20)))))

;; React root named `react-root` (not `root`) so it does NOT collide with
;; the `root-view` reg-view above (rf2-562e). Gated on (exists? js/document)
;; so the ns is safe to require under :node-test (rf2-4v73).
(defonce react-root
  (when (exists? js/document)
    (rdc/create-root (js/document.getElementById "app"))))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  ;; Override :rf.http/managed on the default frame so all the realworld
  ;; feature HTTP calls land on the demo stub (no real backend required).
  (rf/reg-frame :rf/default {:doc          "Realworld demo frame."
                             :fx-overrides {:rf.http/managed :rf.http/managed.realworld-demo}})
  ;; The orchestrator serves this example at /realworld/; strip that
  ;; prefix before the route matcher sees the URL so :route/home (path "/")
  ;; matches.
  (routing/set-base-path! "/realworld")
  (rf/dispatch-sync [:app/initialise])
  (routing/install-router!)
  (rdc/render react-root [root-view]))
