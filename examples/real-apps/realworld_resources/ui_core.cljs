(ns realworld-resources.ui-core
  "Entry point for the re-frame.ui variant of the RealWorld (Conduit) example —
   the SAME app as `realworld-resources.core`, authored ENTIRELY on the
   re-frame.ui COMPILED-VIEW substrate (`ui/defview` + `ui/mount`) instead of the
   stock-Reagent `reg-view` path. Booted by shadow build `:examples/realworld-
   resources-ui`; the Reagent arm (`realworld-resources.core`, build
   `:examples/realworld-resources`) is retained untouched alongside.

   THE SPLIT THIS FILE MAKES CONCRETE. Everything BELOW the view layer is
   substrate-free re-frame2 dataflow — plain `reg-event` / `reg-sub` /
   `reg-resource` / `reg-mutation` / `reg-route` / `reg-machine` / `reg-flow` — so
   the ui variant SHARES it verbatim by requiring the same feature namespaces
   (`resources`, `mutations`, `routing`, `scope`, `schema`, `http`, and the
   dataflow that lives beside the Reagent views in `views` / `auth` / `settings` /
   `article-editor`). Those `reg-view` definitions load inert (they are never
   mounted here); the ui arm mounts its OWN `ui/defview` tree from
   `realworld-resources.ui-views` / `.ui-auth` / `.ui-settings` / `.ui-editor`.
   Nothing is deduplicated and nothing Reagent is removed — the ui arm is purely
   additive (adapter disposition 2026-07-17).

   WHAT THIS NAMESPACE OWNS (the shell + boot, re-authored for the compiled-view
   root): the two boot events (`:auth/classify-token`, `:app/initialise`), the
   frame-wide bearer-auth HTTP interceptor, the compiled app-shell root view, and
   the `ui/mount` that stands up the re-frame.ui root. It is the compiled-view
   counterpart of `realworld-resources.core`'s Reagent shell + `rdc/render`."
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview sub]]
            ;; Managed HTTP — the one built-in transport both resources and
            ;; mutations lower onto.
            [re-frame.http.managed]
            ;; The canned-stub fxs the demo backend leans on.
            [re-frame.http.test-support]
            ;; The resources + mutations runtime.
            [re-frame.resources]
            ;; Routing + machines. Loading routing unlocks the `:resources` route
            ;; key; the auth machine needs machines.
            [re-frame.routing]
            [re-frame.machines]
            ;; --- shared substrate-free dataflow (required for its registrations;
            ;;     the Reagent views these files also carry load inert) ----------
            [realworld-resources.schema]
            [realworld-resources.scope]
            [realworld-resources.http :as rh]
            [realworld-resources.resources]
            [realworld-resources.mutations]
            [realworld-resources.routing :as routing]
            [realworld-resources.auth]
            [realworld-resources.settings]
            [realworld-resources.article-editor]
            [realworld-resources.views]
            ;; --- the compiled-view (ui/defview) layer -----------------------
            [realworld-resources.ui-views :as views]
            [realworld-resources.ui-auth :as auth]
            [realworld-resources.ui-settings :as settings]
            [realworld-resources.ui-editor :as editor]))

;; ============================================================================
;; INITIALISATION  (shell/boot dataflow — the compiled-view counterpart of
;; realworld-resources.core's boot events; substrate-independent bodies)
;; ============================================================================

(rf/reg-event :app/initialise
  {:doc "App boot. Seeds the two form drafts and registers the editor's
         `:editor/can-submit?` flow once. Session restore rides its own
         `:initial-events` step (`:auth/initialise`); the page reads ride the
         route's `:resources` metadata on the first URL→route sync."}
  (fn [_ _]
    {:fx [[:dispatch [:auth.login-form/initialise]]
          [:dispatch [:auth.register-form/initialise]]
          [:dispatch [:editor/register-flow]]]}))

(rf/reg-event :auth/classify-token
  {:doc "Marks the durable JWT path [:auth :token] as sensitive, at frame
         creation — so the raw token is redacted from any capture, SSR payload,
         or trace while on-box handlers still read the live value."}
  (fn [{:keys [db]} _]
    {:db db :sensitive [[:auth :token]]}))

;; ============================================================================
;; BEARER-AUTH HTTP INTERCEPTOR
;; ============================================================================
;;
;; One `:before` fn stamps `Authorization: Token <jwt>` on every outbound
;; managed-HTTP request — the route-caused reads, every write, and the
;; session-restore `GET /user` — reading the token from the CARRIED frame (so the
;; header follows a renamed / multi-frame mount) and passing the request through
;; untouched when there is no token. Substrate-independent: resources and
;; mutations lower onto `:rf.http/managed` regardless of the view layer.
(defn bearer-auth-interceptor [ctx]
  (let [token (some-> (rf/app-db-value (:frame ctx))
                      :auth :token)]
    (cond-> ctx
      token (assoc-in [:request :headers "Authorization"]
                      (str "Token " token)))))

;; ============================================================================
;; APP-SHELL ROOT VIEW  (compiled)
;; ============================================================================

(defview root-view
  "The compiled app shell: header, the unsaved-changes dialog, the route switch,
   and the footer. While a cold-boot session restore is in flight the viewer is
   genuinely unknown, so the viewer-scoped route reads would raise — defer the
   route page for that one brief window and show a 'restoring session' state."
  []
  [:div.app
   [views/header]
   [views/pending-nav-dialog]
   (if (sub [:auth/viewer-resolving?])
     [:div.container.page {:data-testid "session-restoring"}
      [:p "Restoring your session…"]]
     (case (sub [:rf.route/id])
       :realworld/home              [views/home-page]
       :realworld/home-tag          [views/home-page]
       :realworld.auth/login        [auth/login-page]
       :realworld.auth/register     [auth/register-page]
       :realworld.article/show      [views/article-page]
       :realworld.editor/new        [editor/editor-page]
       :realworld.editor/edit       [editor/editor-page]
       :realworld.profile/show      [views/profile-page]
       :realworld.profile/favorites [views/profile-page]
       :realworld.user/settings     [settings/settings-page]
       [views/not-found-page]))
   [views/footer]])

;; ============================================================================
;; window.__conduit_debug__  — CONFORMANCE-CONTRACT SURFACE (not a re-frame2
;; pattern; the external RealWorld suite may read it — see realworld-resources.core)
;; ============================================================================

(defn- install-conduit-debug! [frame-id]
  (when (exists? js/window)
    (set! (.-__conduit_debug__ js/window)
          #js {:getToken       (fn [] (some-> (rf/app-db-value frame-id) :auth :token))
               :getAuthState   (fn [] (name (if (some-> (rf/app-db-value frame-id) :auth :user)
                                              :authed :anonymous)))
               :getCurrentUser (fn [] (clj->js (some-> (rf/app-db-value frame-id) :auth :user)))})))

;; ============================================================================
;; MOUNT  (a re-frame.ui compiled root — the point of this variant)
;; ============================================================================

;; The frame id. `ui/frame-root` requires a LITERAL keyword here (plans are
;; static identity), so it is written inline in the root form below; this def is
;; the value the post-mount listener installs reference.
(def app-frame :rf/default)

(defn ^:export run []
  ;; ONE adapter at boot: the re-frame.ui compiled-view substrate (not the
  ;; Reagent adapter). This is what makes the mounted `ui/defview` tree the app's
  ;; real, running root.
  (rf/init! ui/adapter)
  ;; Register the frame-wide bearer-auth interceptor before the frame's
  ;; `:initial-events` dispatch — session restore fires an authenticated
  ;; `GET /user` the moment the JWT hydrates, so the header must be wired first.
  (rf/with-frame app-frame
    (rf/reg-http-interceptor :realworld/bearer-auth
                             {:before bearer-auth-interceptor}))
  ;; The one-shot compiled mount: `ui/frame-root` (top-region ENSURE plan) creates
  ;; + configures + seeds the URL-bound frame at preflight — `:url-bound? true` so
  ;; it owns the URL and does the first URL→route sync itself (which fires the
  ;; route's `:resources` ensures), the auth-guard interceptor, the demo backend
  ;; `:fx-overrides`, and the ordered `:initial-events` — then `ui/mount` renders
  ;; the compiled `root-view` into #app. IDEMPOTENT per root: a hot reload finds
  ;; the frame live and reuses it, so durable app-db survives.
  (ui/mount
   [ui/frame-root {:id             :rf/default
                   :doc            "RealWorld-on-resources demo frame (re-frame.ui variant)."
                   :url-bound?     true
                   :url-strategy   routing/url-strategy
                   :interceptors   [:realworld-resources.routing/auth-guard]
                   :fx-overrides   {:rf.http/managed :realworld-resources.demo/http-stub}
                   :initial-events [[:auth/classify-token]
                                    [:auth/initialise]
                                    [:app/initialise]]}
    [root-view]]
   (js/document.getElementById "app"))
  ;; The frame is now live and session-seeded (and has already synced its first
  ;; route). Install ongoing listeners AFTER the mount that created the frame.
  ;; Focus/reconnect revalidation refetches active+stale reads; idempotent.
  (rf/install-revalidation-listeners! app-frame)
  ;; The conformance surface — NOT a re-frame2 pattern (see install-conduit-debug!).
  (install-conduit-debug! app-frame))
