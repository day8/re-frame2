(ns example.realworld.core
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
     http.cljs             — :http registered fx
     ssr.cljc              — hydration payload helper for the RealWorld app"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [example.realworld.schema]
            [example.realworld.http]
            [example.realworld.routing :as routing]
            [example.realworld.auth :as auth]
            [example.realworld.articles :as articles]
            [example.realworld.comments :as comments]
            [example.realworld.article-editor :as editor]
            [example.realworld.profile :as profile]
            [example.realworld.favorites]
            [example.realworld.tags]
            [example.realworld.settings :as settings])
  (:require-macros [re-frame.views-macros :refer [reg-view with-frame]]))

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
;; HEADLESS TESTS  (top-level smoke)
;; ============================================================================

(defn app-smoke-test []
  (with-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                    :fx-overrides {:http :http.canned-success
                                                   :auth.session/store :rf/no-op
                                                   :auth.session/clear :rf/no-op}})]
    ;; After init: auth, articles, and tags slices are present.
    (let [db (rf/get-frame-db f)]
      (assert (contains? db :auth))
      (assert (contains? db :articles))
      (assert (contains? db :tags)))))

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/dispatch-sync [:app/initialise])
  (routing/install-router!)
  (rdc/render root [root-view]))
