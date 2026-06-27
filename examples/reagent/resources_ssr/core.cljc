(ns resources-ssr.core
  "Server-side rendering over a re-frame2 RESOURCE.

   The page's state is a resource — a live cache the framework owns. The
   server renders the article list with that resource preloaded, then ships
   the cache to the client. The client hydrates it and, for anything still
   fresh, renders on the first paint without re-fetching. That last part is
   the whole point of preloading: don't make the browser ask for what the
   server just handed it.

   This is `.cljc`, so one file is both halves of the app. The `:clj`
   branches run on the server (a JVM), the `:cljs` branches run in the
   browser, and the reader picks the side. Read it top to bottom as one
   request and its client pickup.

   The four things the resource-SSR contract buys you:

   - Each request renders in its own frame. The cache is shared state, so a
     process-global one would leak one user's articles into another's render.
     A frame per request keeps every cache private.
   - The page resource is ensured under an `[:ssr request-id nav-token]`
     owner with cause `:ssr-preload`, and the ensure blocks the render until
     the read settles — no half-loaded page goes out the door.
   - Only the allowed resource entries ride the hydration payload (under
     `:rf/runtime-db`). Fetch handles and the tag/owner indexes stay home;
     the indexes recompute from `:entries` the moment the client installs them.
   - On the client the framework's `:rf/hydrate` event drops those entries
     into the frame's `:rf.runtime/resources` slice. A fresh entry renders
     immediately and serves from cache. Stale, redacted, or omitted entries
     refetch — because for those the client has nothing usable in hand.

   This drives the real server path, not a stand-in. It drains the blocking
   page resource before rendering (`ssr/drain-blocking-resources!`) and ships
   only the allowed runtime-db projection (`payload-policy/project-runtime-db`)
   under `:rf/runtime-db` — never the full cache. The `:rf/app-db` slice goes
   through the same fail-closed allowlist (`apply-policy`) the production Ring
   host uses. The static `index.html` next to this file carries a pre-baked payload
   so the browser side runs without a Clojure server in the box.

   For the full picture see [Resources: SSR and hydration](../../../docs/resources/concepts.md#ssr-and-hydration)
   and [Server-side rendering](../../../docs/ssr/concepts.md).

   The sibling `examples/reagent/ssr/` is the simpler cousin: it bakes a
   fetched list into app-db and hands that frozen value across. Here the data
   is a runtime-managed resource, so identity, scope, staleness, and hydration
   are the framework's job, and the cache lives in the runtime partition rather
   than in app-db."
  (:require [re-frame.core :as rf]
            ;; Managed HTTP — how the resource actually fetches.
            [re-frame.http.managed]
            ;; The resources artefact. Loading it boots the registrations and
            ;; the SSR projection hook the ssr artefact reaches for.
            [re-frame.resources]
            ;; SSR: render-to-string, the `:rf/hydrate` event, and the
            ;; per-request server effects.
            [re-frame.ssr :as ssr]
            ;; The payload assembly the production Ring host uses, so this
            ;; example builds the wire payload the same way the real thing does:
            ;; the fail-closed app-db allowlist (`apply-policy`) and the
            ;; runtime-db projection (`project-runtime-db`) that ships only the
            ;; allowed resource `:entries` under `:rf/runtime-db` (sensitive
            ;; data redacted; indexes rebuilt on install). Server-side only.
            #?(:clj [re-frame.ssr.payload-policy :as payload-policy])
            ;; The EDN-aware escaper for the payload `<script>` body. A server
            ;; string that happens to contain `</script>` would otherwise close
            ;; the envelope early — so we escape it. Server-side only.
            #?(:clj [re-frame.ssr.html-helpers :as html])
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; RESOURCE
;; ============================================================================
;;
;; The page's server-state, declared once. You say what the read is and how to
;; fetch it; the runtime takes it from there — caching, deduping, and tracking
;; staleness so you don't have to.
;;
;; `:scope :rf.scope/global` is a claim: this article list is identical for
;; every user. Scope is part of a read's cache identity, which is what stops
;; one user's data from ever surfacing in another's cache. It carries through
;; to hydration too — the serialized scope and the client's scope must agree
;; before the client trusts hydrated data, and hydration never crosses a scope
;; boundary. A per-user page would supply a scope resolver here instead; the
;; global claim is the explicit, checkable statement that this handoff is safe.
;; See docs/resources/glossary.md#scope.

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
;; `:rf/server-init` runs when the per-request frame is born. Its one job is to
;; ensure the page resource — fetch it if the cache is cold — so that by render
;; time the data is already warm. The renderer then waits for it to settle
;; before walking the tree.
;;
;; Two things ride along with the ensure, and they answer two different
;; questions. The owner `[:ssr request-id nav-token]` is a lease — it keeps the
;; entry alive for exactly this server render and not a moment longer. The
;; cause `:ssr-preload` is the story for the trace: why this fetch happened.
;; Owner = how long; cause = why. See docs/resources/glossary.md#owner--cause.

(rf/reg-event :rf/server-init
  {:doc       "Per-request server init — preload the page resource."
   :platforms #{:server}}
  (fn [{:keys [db]} _]
    {:db db
     :fx [[:dispatch [:rf.resource/ensure
                      {:resource :articles/list
                       :params   {}
                       ;; The request-id and nav-token are hard-coded for the
                       ;; demo; a real server would thread them off the actual
                       ;; request so each render's lease is genuinely unique.
                       :owner    [:ssr :ssr/req-1 :ssr/nav-1]
                       :cause    :ssr-preload}]]]}))

;; ============================================================================
;; VIEWS — passive reads, server and client alike
;; ============================================================================
;;
;; The view does the simplest possible thing: it reads the resource through a
;; subscription and renders whatever's there. Reading never kicks off a fetch —
;; the preload (server) and the hydration (client) already warmed the cache, so
;; the data is just sitting there waiting. `:rf.resource/state` is the
;; runtime's own subscription; it turns a cache entry into a tidy view-model —
;; a status flag and the data. Either side, the data is present by first render,
;; so there's no skeleton to flash. The same view code runs on both — the "one
;; app, runs twice" promise, now over a managed resource.
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
;; This is one request, start to finish: mint a per-request frame whose
;; `:initial-events` fire `:rf/server-init`, drain the blocking page resource,
;; render to a string, and build a hydration payload carrying the allowed
;; resource projection under `:rf/runtime-db`. The frame is torn down in a
;; `finally` on every exit path — success or throw — so a request can never
;; leak a frame and slowly drown the JVM.
;;
;; Two steps are what make this the real server path rather than a toy:
;;
;;   1. `ssr/drain-blocking-resources!` waits for the `[:ssr …]`-owned ensure to
;;      settle — reach `:loaded`, or time out into a structured first-load
;;      failure — before we walk the tree. So the render never catches the page
;;      mid-fetch with a `:loading` skeleton frozen into the HTML.
;;   2. `payload-policy/project-runtime-db` boils the runtime-db down to just
;;      what's safe to serialize: the durable `:rf.runtime/resources`
;;      `:entries`, with `:sensitive?` values redacted, `:large?` values
;;      omitted, and the reverse indexes dropped (the client rebuilds those
;;      from `:entries`, so shipping them would be sending data we're about to
;;      throw away). Ship the projection, never the whole cache. The app-db
;;      slice rides the fail-closed allowlist via `apply-policy` — empty here,
;;      because on this page the resource *is* the state and app-db holds
;;      nothing of its own.

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
           ;; (1) Settle the blocking page resource before we render a thing.
           ;; The `[:ssr …]`-owned ensure that `:rf/server-init` kicked off has
           ;; to land on a terminal status (:loaded / :error), or time out into
           ;; a settled first-load failure, so the render walk works with real
           ;; data instead of an in-flight guess.
           (ssr/drain-blocking-resources! f)
           (let [final-db      (rf/app-db-value f)
                 final-runtime (rf/runtime-db-value f)   ;; this is where :rf.runtime/resources lives
                 hiccup        ((rf/view :app/root))
                 html          (rf/render-to-string hiccup {:doctype? true :emit-hash? true})
                 render-hash   (rf/render-tree-hash hiccup)
                 ;; (2) Build the payload exactly the way the Ring host does:
                 ;; the app-db slice through the fail-closed allowlist, and the
                 ;; runtime-db through the SSR projection (the allowed resource
                 ;; `:entries`, and nothing more).
                 ;;
                 ;; The page state is the resource — it rides `:rf/runtime-db` —
                 ;; so app-db has nothing of its own to send. But the payload
                 ;; policy is fail-closed by design, so "send nothing" still has
                 ;; to be said out loud. The `:rf.ssr.payload/whole-app-db`
                 ;; opt-in projects the empty app-db to `{}` cleanly. A bare `[]`
                 ;; allowlist would *not* mean "ship nothing" — it reads as a
                 ;; missing policy and throws
                 ;; `:rf.error/ssr-missing-payload-policy`. Silence is the one
                 ;; thing fail-closed won't let you get away with.
                 ;; See docs/ssr/concepts.md#payload--the-fail-closed-allowlist.
                 policy-opts   {:payload :rf.ssr.payload/whole-app-db}
                 payload       (payload-policy/build-payload
                                 f
                                 (payload-policy/apply-policy final-db policy-opts)
                                 render-hash
                                 (assoc policy-opts
                                        :runtime-db (payload-policy/project-runtime-db
                                                      final-runtime)))
                 ;; Drop the payload's `:rf/frame-id`. `build-payload` stamps it
                 ;; with this per-request gensym frame (`f`), but the client
                 ;; hydrates a fixed app-frame (`:rf/default`, below). When a
                 ;; `:rf/frame-id` *is* present, `ssr/hydrate!` checks it against
                 ;; the client's `:frame` and raises
                 ;; `:rf.error/hydration-frame-id-mismatch` if they disagree — so
                 ;; the frame-id is a sanity check, not a hydration target. Our
                 ;; gensym could never match the client's fixed id, so leaving it
                 ;; in would guarantee a false mismatch; dropping it sidesteps
                 ;; the check entirely (an absent id is no conflict). A
                 ;; deployment that wants a frame-id on the wire would stamp one
                 ;; both sides agree on up front.
                 payload       (dissoc payload :rf/frame-id)]
             {:status  200
              :headers {"Content-Type" "text/html"}
              :body
              (str "<!DOCTYPE html><html><head><meta charset='utf-8'/>"
                   "<title>Resources SSR demo</title></head><body>"
                   "<div id='app'>" html "</div>"
                   ;; Run the payload `<script>` body through the same EDN-aware
                   ;; escaper the production Ring host uses, so a server string
                   ;; that happens to contain `</script>` can't slam the
                   ;; envelope shut from the inside.
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
;; The browser's side of the handoff. `ssr/hydrate!` reads the payload,
;; dispatch-syncs `[:rf/hydrate payload]` to install the resource projection
;; into the frame's `:rf.runtime/resources` slice, then verifies the render
;; hash to confirm client and server agree on what the page should look like.
;; A fresh hydrated entry renders its data on the first paint and serves it
;; straight from cache — no second fetch, which is exactly what preloading was
;; for. A stale entry quietly refetches in the background, per its policy.
;; See docs/ssr/concepts.md#the-client-side-hydrate-then-verify.

#?(:cljs (defonce react-root (atom nil)))

;; The client's one app-frame. The app names its hydration target out loud and
;; threads the same id through two places: `ssr/hydrate!` (where the server
;; state lands) and the root `frame-provider` (where in-tree dispatch/subscribe
;; go looking for their frame). One id, both ends — that's what makes them meet.
;; It has to be a `:client`-platform frame, or the `:rf.ssr/check-*`
;; compatibility checks that `:rf/hydrate` fires would simply sit there inert.
;; See docs/ssr/concepts.md#deploy-drift-checks-come-along-for-free.
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
