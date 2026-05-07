(ns example.realworld.core
  "Entry point for the RealWorld (Conduit) example.

   Wires the app together:
   - Pulls in every feature namespace (each registers its own events/subs/fx).
   - Defines :app/initialise (the :on-create event).
   - Defines the root-view that switches on :route/id.
   - Mounts the React root and installs the URL listener.

   This is single-file glue; the per-feature work lives in:
     auth.cljs           — login / register / session-restore (implemented)
     articles.cljs       — article-list page (implemented)
     routing.cljs        — route registrations + router wiring (implemented
                            subset — see the file's ownership-boundary
                            docstring; some handlers are local re-statements
                            of framework-shipped defaults for documentation)
     schema.cljs         — Malli schemas for the wire payloads / slices used
                            by the implemented files (more land as the TODO
                            files do)
     http.cljs           — :http registered fx (implemented)
     article_editor.cljs — TODO
     comments.cljs       — TODO
     profile.cljs        — TODO
     favorites.cljs      — TODO
     tags.cljs           — TODO  (note: tag *list* loaded by articles.cljs;
                                  this file is for tag *filter* feature.)
     ssr.cljs            — TODO  (see examples/ssr/core.cljc for the
                                  minimal SSR worked example)"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [example.realworld.schema]
            [example.realworld.http]
            [example.realworld.routing :as routing]
            [example.realworld.auth :as auth]
            [example.realworld.articles :as articles]
            ;; TODO — uncomment as each placeholder is implemented:
            ;; [example.realworld.article-editor :as editor]
            ;; [example.realworld.comments :as comments]
            ;; [example.realworld.profile :as profile]
            ;; [example.realworld.favorites :as favorites]
            ;; [example.realworld.tags :as tags]
            ))

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event-fx :app/initialise
  {:doc "App boot. Fans out to per-feature initialisers."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:auth/initialise]]
          [:dispatch [:articles/initialise]]
          [:dispatch [:tags/initialise]]
          [:dispatch [:auth.login-form/initialise]]
          [:dispatch [:auth.register-form/initialise]]]}))

;; ============================================================================
;; APP-SHELL VIEWS
;; ============================================================================

(def header
  (rf/reg-view :app/header
    {:doc "The site header. Shows different links based on auth state."}
    (fn render-header []
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
               [routing/route-link {:to :route/register :class "nav-link"} "Sign up"]]])]]]))))

(def footer
  (rf/reg-view :app/footer
    (fn render-footer []
      [:footer
       [:div.container
        [routing/route-link {:to :route/home :class "logo-font"} "conduit"]
        [:span.attribution "An interactive learning project from Thinkster."
         " Code & design licensed under MIT."]]])))

(def pending-nav-dialog
  (rf/reg-view :app/pending-nav-dialog
    {:doc "Renders a confirm dialog when navigation is blocked by a
           :can-leave guard. Reads the :rf/pending-navigation slot."}
    (fn render-pending-nav-dialog []
      (when-let [pending @(subscribe [:rf/pending-navigation])]
        [:div.pending-nav-overlay
         [:div.pending-nav-dialog
          [:p (or (:reason pending) "You have unsaved changes. Leave anyway?")]
          [:button {:on-click #(dispatch [:route/continue (:id pending)])}
           "Discard changes"]
          [:button {:on-click #(dispatch [:route/cancel (:id pending)])}
           "Stay"]]]))))

(def not-found-page
  (rf/reg-view :pages/not-found
    (fn render-not-found []
      (let [url (:url @(subscribe [:route/params]))]
        [:div.not-found-page
         [:h1 "Page not found"]
         (when url [:p (str "No route matches: " url)])
         [routing/route-link {:to :route/home} "Home"]]))))

(def root-view
  (rf/reg-view :app/root
    {:doc "App-level root. Switches on :route/id to render the active page."}
    (fn render-root []
      [:div.app
       [header]
       [pending-nav-dialog]
       (case @(subscribe [:route/id])
         :route/home              [articles/home-page]
         :route/login             [auth/login-page]
         :route/register          [auth/register-page]
         ;; TODO — wire each page-view as its file is implemented:
         ;; :route/article         [comments/article-page]
         ;; :route/editor          [editor/editor-page]
         ;; :route/editor.edit     [editor/editor-page]
         ;; :route/profile         [profile/profile-page]
         ;; :route/profile.favorites [profile/profile-page]
         ;; :route/settings        [settings/settings-page]
         [not-found-page])
       [footer]])))

;; ============================================================================
;; HEADLESS TESTS  (top-level smoke)
;; ============================================================================

(defn app-smoke-test []
  (rf/with-frame [f (rf/make-frame {:on-create    [:app/initialise]
                                    :fx-overrides {:http :http.canned-success
                                                   :auth.session/store :rf/no-op
                                                   :auth.session/clear :rf/no-op}})]
    ;; After init: auth slice present, articles slice present, route is at home.
    (let [db @(rf/get-frame-db f)]
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
