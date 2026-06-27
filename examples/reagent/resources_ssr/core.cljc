(ns resources-ssr.core
  "Worked example: SSR over a re-frame2 RESOURCE. A server+client app whose
   page server-state is a resource. The server renders an article list with
   the resource preloaded; the client hydrates the resource cache and skips a
   duplicate fetch for entries that are still fresh.

   This is .cljc so the same code runs server-side (JVM, `:clj` branches)
   and client-side (browser, `:cljs` branches).

   What the resource SSR contract gives you here:

   - The server renders each request in its own frame. A process-global
     resource cache would leak one user's data into another's.
   - The page's resource is ensured under an `[:ssr request-id nav-token]`
     owner with cause `:ssr-preload`. The ensure blocks the render until the
     read settles.
   - Only the allowed resource entries ride the hydration payload (under
     `:rf/runtime-db`). Host handles and the tag/owner indexes are never
     serialized; the indexes recompute from `:entries` on install.
   - On the client the framework `:rf/hydrate` event installs those entries
     into the target frame's `:rf.runtime/resources` slice. A fresh hydrated
     entry renders on first paint and serves from the cache. Stale, redacted,
     or omitted entries refetch.

   This example drives the real server path. It drains the blocking page
   resource before render (`ssr/drain-blocking-resources!`) and serializes
   only the allowed runtime-db projection (`payload-policy/project-runtime-db`)
   into `:rf/runtime-db` — never the full runtime-db. The `:rf/app-db` slice
   goes through the same fail-closed allowlist (`apply-policy`) the production
   Ring host uses. The static `index.html` next to this file carries a
   pre-baked payload so the browser-side `run` works without a Clojure server.

   For the full picture see [Resources: SSR and hydration](../../../docs/resources/concepts.md#ssr-and-hydration)
   and [Server-side rendering](../../../docs/ssr/concepts.md).

   Compare with `examples/reagent/ssr/` (plain SSR with a managed-HTTP fetch
   written into app-db): here the same data is a runtime-managed resource, so
   identity, scope, staleness, and hydration are the framework's job, and the
   cache lives in the runtime partition rather than app-db."
  (:require [re-frame.core :as rf]
            ;; Managed HTTP — the resource transport.
            [re-frame.http.managed]
            ;; Resources artefact: boots the registrations and the SSR
            ;; projection hook the ssr artefact consults.
            [re-frame.resources]
            ;; SSR: render-to-string, the `:rf/hydrate` event, and the
            ;; per-request server effects.
            [re-frame.ssr :as ssr]
            ;; The hydration-payload assembly the production Ring host uses:
            ;; the fail-closed app-db allowlist (`apply-policy`) and the
            ;; runtime-db projection (`project-runtime-db`) that rides only the
            ;; allowed resource `:entries` onto `:rf/runtime-db` (sensitive
            ;; data redacted; indexes recompute on install). Server-side only.
            #?(:clj [re-frame.ssr.payload-policy :as payload-policy])
            ;; The EDN-aware escaper for the payload `<script>` body. A
            ;; server-provided string carrying `</script>` would otherwise
            ;; close the envelope, so escape it. Server-side only.
            #?(:clj [re-frame.ssr.html-helpers :as html])
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; RESOURCE
;; ============================================================================
;;
;; The page's server-state, declared once. You describe what the read is and
;; how to fetch it; the runtime owns the cache, dedupe, and staleness.
;;
;; `:scope :rf.scope/global` declares that this article list is the same for
;; every user. Scope is part of the read's cache identity, so one user's data
;; can never surface in another's cache. It also matters for hydration: the
;; serialized scope and the client's scope must agree before the client treats
;; hydrated data as usable, and hydration never crosses scopes. A user-scoped
;; page would carry a scope resolver instead. See
;; docs/resources/glossary.md#scope.

(rf/reg-resource :articles/list
  {:doc            "Recent articles (public, SSR-preloaded)."
   :params-schema  [:map]
   :scope          :rf.scope/global
   :stale-after-ms 60000
   :tags           (fn [_params _data] #{[:article-list]})}
  (fn [_params _ctx]
    {:request {:method :get :url "/api/articles"}
     :decode  :json}))

;; ============================================================================
;; SERVER-SIDE PRELOAD EVENT
;; ============================================================================
;;
;; `:rf/server-init` runs at per-request frame creation. It ensures the page
;; resource — fetching it if the cache is cold — so the render finds it warm.
;; The renderer then waits for it to settle before rendering.
;;
;; Two facts ride the ensure. The owner `[:ssr request-id nav-token]` is a
;; lease: it keeps the entry alive for the duration of this server render and
;; no longer. The cause `:ssr-preload` is provenance — why the fetch happened,
;; recorded for the trace. Owner = lifetime; cause = explanation. See
;; docs/resources/glossary.md#owner--cause.

(rf/reg-event :rf/server-init
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
;; The view is a passive reader: it pulls the resource through a subscription
;; and renders whatever it finds, the same on server and client. Reading does
;; not trigger a fetch — the preload and hydration already warmed the cache.
;; `:rf.resource/state` is the runtime-supplied subscription that turns the
;; cache entry into a view-model (a status flag plus the data). On the server
;; the resource was preloaded; on the client the hydrated entry is already
;; present, so the first render shows data straight away. Same view code on
;; both sides — the "one app, runs twice" promise, now over a managed resource.
;; See docs/ssr/glossary.md#render-to-string.

(rf/reg-view ^{:rf/id :pages/articles} articles-page []
  (let [state @(rf/subscribe [:rf.resource/state
                              {:resource :articles/list
                               :scope    :rf.scope/global
                               :params   {}}])]
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
;; A per-request frame whose `:initial-events` dispatch `:rf/server-init`, a
;; drain to settle the blocking page resource, a render to string, and a
;; hydration payload carrying the allowed resource projection in
;; `:rf/runtime-db`. The per-request frame is torn down in a `finally` on
;; every exit path, so a request never leaks a frame.
;;
;; Two steps make this the real server path:
;;
;;   1. `ssr/drain-blocking-resources!` settles the `[:ssr …]`-owned blocking
;;      ensure (or times it out into a structured first-load failure) before
;;      the render walk, so the render never sees a hung `:loading` skeleton.
;;   2. `payload-policy/project-runtime-db` projects the runtime-db down to the
;;      serializable allowlist — only the durable `:rf.runtime/resources`
;;      `:entries`, per-entry redacted (`:sensitive?`) or omitted (`:large?`),
;;      with the reverse indexes dropped (they recompute on install). Ship the
;;      projection, never the full runtime-db. The app-db slice rides the
;;      fail-closed allowlist via `apply-policy` (here empty — the page state
;;      is the resource, so app-db holds nothing of its own).

#?(:clj
   (defn handle-request [request]
     (let [fid (keyword "rf.frame" (str (gensym "f")))
           _   (ssr/set-request! fid request)
           f   (rf/reg-frame fid
                 {:doc       "resources-ssr per-request frame"
                  :platform  :server
                  :initial-events [[:rf/server-init]]})]
       (try
         (rf/with-frame f
           ;; (1) Settle the blocking page resource before rendering. The
           ;; `[:ssr …]`-owned ensure dispatched by `:rf/server-init` must reach
           ;; a terminal status (:loaded / :error) or time out into a settled
           ;; first-load failure, so the render walk sees real data.
           (ssr/drain-blocking-resources! f)
           (let [final-db      (rf/app-db-value f)
                 final-runtime (rf/runtime-db-value f)   ;; carries :rf.runtime/resources
                 hiccup        ((rf/view :app/root))
                 html          (rf/render-to-string hiccup {:doctype? true :emit-hash? true})
                 render-hash   (rf/render-tree-hash hiccup)
                 ;; (2) Build the payload the way the Ring host does: the
                 ;; app-db slice through the fail-closed allowlist, and the
                 ;; runtime-db through the SSR projection (only the allowed
                 ;; resource `:entries`).
                 ;;
                 ;; The page state is the resource (it rides `:rf/runtime-db`),
                 ;; so app-db carries nothing of its own. `:payload` is
                 ;; fail-closed, so name the intent explicitly: the
                 ;; `:rf.ssr.payload/whole-app-db` opt-in projects the (empty)
                 ;; app-db to `{}` cleanly. A bare empty `[]` allowlist instead
                 ;; reads as a missing policy and throws
                 ;; `:rf.error/ssr-missing-payload-policy`, not "ship nothing".
                 ;; See docs/ssr/concepts.md#payload--the-fail-closed-allowlist.
                 policy-opts   {:payload :rf.ssr.payload/whole-app-db}
                 payload       (payload-policy/build-payload
                                 f
                                 (payload-policy/apply-policy final-db policy-opts)
                                 render-hash
                                 (assoc policy-opts
                                        :runtime-db (payload-policy/project-runtime-db
                                                      final-runtime)))
                 ;; Drop the payload's `:rf/frame-id`. `build-payload` stamps
                 ;; it with this per-request gensym frame (`f`), but the client
                 ;; hydrates a fixed app-frame (`:rf/default`, below).
                 ;; `ssr/hydrate!` checks any present `:rf/frame-id` against the
                 ;; client's `:frame` and raises
                 ;; `:rf.error/hydration-frame-id-mismatch` when they differ —
                 ;; the frame-id is validation evidence, not a hydration target.
                 ;; A per-request gensym can never equal the client's fixed id,
                 ;; so drop it; an absent frame-id is no conflict. A deployment
                 ;; that wants a frame-id on the wire stamps a stable id both
                 ;; sides agree on.
                 payload       (dissoc payload :rf/frame-id)]
             {:status  200
              :headers {"Content-Type" "text/html"}
              :body
              (str "<!DOCTYPE html><html><head><meta charset='utf-8'/>"
                   "<title>Resources SSR demo</title></head><body>"
                   "<div id='app'>" html "</div>"
                   ;; Emit the payload `<script>` through the EDN-aware
                   ;; escaper the production Ring host uses, so a server-
                   ;; provided string carrying `</script>` can't close the
                   ;; envelope.
                   "<script id='__rf_payload' type='application/edn'>"
                   (html/escape-edn-script-body (pr-str payload))
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
;; `:rf.runtime/resources` slice), and verifies the render hash. A fresh
;; hydrated entry renders its data on first paint and serves it straight from
;; the cache — no duplicate fetch, which is the whole point of preloading. A
;; stale entry would background-refetch by policy. See
;; docs/ssr/concepts.md#the-client-side-hydrate-then-verify.

#?(:cljs (defonce react-root (atom nil)))

;; The fixed client app-frame. The app names its hydration target explicitly
;; and threads the same id through both `ssr/hydrate!` (where the server state
;; lands) and the root `frame-provider` (where in-tree dispatch/subscribe
;; resolve). It must be a `:client`-platform frame so the `:rf.ssr/check-*`
;; compatibility-check effects the `:rf/hydrate` handler dispatches actually
;; fire. See docs/ssr/concepts.md#deploy-drift-checks-come-along-for-free.
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
