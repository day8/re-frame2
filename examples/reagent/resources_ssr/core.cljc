(ns resources-ssr.core
  "Worked example for [Spec 016 §SSR and hydration](../../../spec/016-Resources.md)
   over [Spec 011 SSR](../../../spec/011-SSR.md). A server+client app whose
   page server-state is a re-frame2 RESOURCE: the server renders an article
   list with the resource preloaded; the client hydrates the resource cache
   and avoids a duplicate immediate fetch for fresh entries.

   This is .cljc so the same code runs server-side (JVM, `:clj` branches)
   and client-side (browser, `:cljs` branches).

   Demonstrates the resource SSR contract (Spec 016 §SSR and hydration):

   - SSR uses a REQUEST-LOCAL frame (a process-global resource cache would
     leak data between users) — the per-request `handle-request` frame.
   - The page's resource is ensured under an `[:ssr request-id nav-token]`
     owner with cause `:ssr-preload`, BLOCKING the render until it settles.
   - Only the ALLOWED resource projection is serialized into the hydration
     payload (`:rf/runtime-db`); host handles + the tag/owner indexes are
     never serialized (the indexes recompute from `:entries` on install).
   - On the client the framework `:rf/hydrate` installs the projection into
     the target frame's `:rf.runtime/resources` slice; a fresh hydrated
     entry renders immediately and does NOT immediately re-fetch.

   SLICE STATUS (rf2-p10npe). Resources ships here at its SKELETON slice:
   `reg-resource`, the passive `[:rf.resource/*]` subs, and the SSR
   projection hook are real and load cleanly, so this example COMPILES and
   the SSR shape is the canonical one. The blocking-drain + hydration-install
   RUNTIME lands in later slices (rf2-ctk2av / rf2-pbxj48); the static
   `index.html` next to this file carries a pre-baked payload illustrating
   the serialized projection, exactly as the sibling `examples/reagent/ssr/`
   does for plain SSR. The example tree is test-free (rf2-8cevm).

   Compare with `examples/reagent/ssr/` (plain SSR with a managed-HTTP fetch
   written into app-db): here the same data is a runtime-managed RESOURCE, so
   identity / scope / staleness / hydration are the framework's job, and the
   cache lives in the runtime partition rather than app-db."
  (:require [re-frame.core :as rf]
            ;; Managed HTTP — the single built-in resource transport.
            [re-frame.http-managed]
            [re-frame.http-test-support]
            ;; Resources artefact (boots the registrations + the LATE-BOUND
            ;; SSR projection hook the ssr artefact consults).
            [re-frame.resources]
            ;; SSR ships in day8/re-frame2-ssr — render-to-string, the
            ;; `:rf/hydrate` event, and the per-request server fxs. The
            ;; resources artefact late-binds its runtime-db projection into
            ;; the SSR `project-runtime-db` allowlist (Spec 016 §SSR).
            [re-frame.ssr :as ssr]
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; RESOURCE
;; ============================================================================
;;
;; The page's server-state, declared once. `:scope :rf.scope/global` is the
;; explicit auditable claim that this article list is the same for every
;; user (a user-scoped page would carry a scope resolver; SSR hydration
;; MUST NEVER cross scopes — request-local frames + serialized scopes must
;; agree before the client treats hydrated data as usable).

(rf/reg-resource :articles/list
  {:doc            "Recent articles (public, SSR-preloaded)."
   :params-schema  [:map]
   :scope          :rf.scope/global
   :request        (fn [_params _ctx]
                     {:request {:method :get :url "/api/articles"}
                      :decode  :json})
   :stale-after-ms 60000
   :tags           (fn [_params _data] #{[:article-list]})})

;; ============================================================================
;; SERVER-SIDE PRELOAD EVENT
;; ============================================================================
;;
;; On the server, `:rf/server-init` (dispatched at per-request frame
;; creation) ensures the page resource under an SSR owner with cause
;; `:ssr-preload`. `:blocking?`-style preload means the renderer waits for
;; the resource to settle before rendering (Spec 016 §SSR §Server route
;; handling). The `[:ssr request-id nav-token]` owner belongs to THIS server
;; render and is released on request teardown — it never survives as a live
;; client lease (it reconciles to an orphan on hydration).

(rf/reg-event-fx :rf/server-init
  {:doc       "Per-request server init — preload the page resource."
   :platforms #{:server}}
  (fn [{:keys [db]} _]
    {:db db
     :fx [[:dispatch [:rf.resource/ensure
                      {:resource :articles/list
                       :params   {}
                       ;; request-id + nav-token are illustrative here; a
                       ;; real server threads them from the request frame.
                       :owner    [:ssr :ssr/req-1 :ssr/nav-1]
                       :cause    :ssr-preload}]]]}))

;; ============================================================================
;; VIEWS — passive reads, server and client alike
;; ============================================================================
;;
;; The view reads the resource passively. On the server the resource was
;; preloaded; on the client the hydrated entry is already present, so the
;; first render shows data without a fetch. Same view code both sides —
;; the SSR/CLJS parity Spec 011 promises, now over a resource.

(rf/reg-view ^{:rf/id :pages/articles} articles-page []
  (let [state @(subscribe [:rf.resource/state {:resource :articles/list :params {}}])]
    [:div.page
     [:h1 "Recent articles"]
     (cond
       (:loading? state)
       [:p {:data-testid "articles-skeleton"} "Loading…"]

       (and (:error state) (not (:has-data? state)))
       [:p.error {:data-testid "articles-error"} "Could not load articles."]

       :else
       (into [:ul {:data-testid "articles-list"}]
             (for [{:keys [slug title]} (:data state)]
               ^{:key slug}
               [:li {:data-testid (str "article-" slug)} title])))]))

(rf/reg-view ^{:rf/id :app/root} root-view []
  [(rf/view :pages/articles)])

;; ============================================================================
;; SERVER ENTRY POINT
;; ============================================================================
;;
;; Mirrors examples/reagent/ssr/ — a per-request frame whose `:on-create`
;; dispatches `:rf/server-init`, a drain to settle the blocking resource, a
;; render to string, and a hydration payload carrying the serialized
;; resource projection in `:rf/runtime-db`. The per-request frame is torn
;; down in a `finally` on every exit path (memory hygiene). When the
;; resource runtime's blocking-drain lands (rf2-ctk2av) the drain settles
;; the `[:ssr …]`-owned ensure before render.

#?(:clj
   (defn handle-request [request]
     (let [fid (keyword "rf.frame" (str (gensym "f")))
           _   (ssr/set-request! fid request)
           f   (rf/reg-frame fid
                 {:doc       "resources-ssr per-request frame"
                  :platform  :server
                  :on-create [:rf/server-init]})]
       (try
         (rf/with-frame f
           (let [final-db      (rf/app-db-value f)
                 final-runtime (rf/runtime-db-value f)   ;; carries :rf.runtime/resources
                 hiccup        ((rf/view :app/root))
                 html          (rf/render-to-string hiccup {:doctype? true :emit-hash? true})
                 render-hash   (rf/render-tree-hash hiccup)
                 payload       {:rf/version     1
                                :rf/frame-id    f
                                :rf/app-db      final-db
                                ;; The serializable runtime-db projection —
                                ;; the SSR allowlist includes only the durable
                                ;; resource `:entries`; indexes recompute on
                                ;; install, host handles never serialize.
                                :rf/runtime-db  final-runtime
                                :rf/render-hash render-hash}]
             {:status  200
              :headers {"Content-Type" "text/html"}
              :body
              (str "<!DOCTYPE html><html><head><meta charset='utf-8'/>"
                   "<title>Resources SSR demo</title></head><body>"
                   "<div id='app'>" html "</div>"
                   "<script id='__rf_payload' type='application/edn'>"
                   (pr-str payload)
                   "</script><script src='/main.js'></script>"
                   "</body></html>")}))
         (finally
           (rf/destroy-frame! fid))))))

;; ============================================================================
;; CLIENT ENTRY POINT
;; ============================================================================
;;
;; `ssr/hydrate!` reads the payload, dispatch-syncs `[:rf/hydrate payload]`
;; (which installs the resource projection into the carried frame's
;; `:rf.runtime/resources` slice under the locked :replace-frame-state
;; policy), and verifies the render hash. A FRESH hydrated entry renders its
;; data immediately and does NOT immediately re-fetch (Spec 016 §SSR client
;; hydration); a stale entry would background-refetch by policy.

#?(:cljs (defonce react-root (atom nil)))

(def app-frame :rf/default)

#?(:cljs
   (defn run []
     (rf/init! reagent-adapter/adapter)
     (rf/reg-frame app-frame {:doc "resources-ssr client app-frame" :platform :client})
     (ssr/hydrate! {:frame          app-frame
                    :render-tree-fn (fn [] ((rf/view :app/root)))})
     (when (exists? js/document)
       (when-not @react-root
         (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
       (rdc/render @react-root
                   [rf/frame-provider {:frame app-frame}
                    [(rf/view :app/root)]]))))
