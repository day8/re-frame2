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

   This drives the real server path, not a stand-in. It blocks on the
   preloaded page resource before rendering (`await-resource-loaded!`,
   server entry point below) and ships only the allowed runtime-db
   projection (`rf.ssr.payload-policy/project-runtime-db`) under `:rf/runtime-db` —
   never the full cache. The `:rf/app-db` slice goes through the same
   fail-closed allowlist (`apply-policy`) the production Ring host uses. The
   static `index.html` next to this file carries a pre-baked payload so the
   browser side runs without a Clojure server in the box.

   One wrinkle worth calling out: this page has no route table (no
   `reg-route`, no router install) — it renders unconditionally, the
   simplest possible SSR shape. The framework's own SSR blocking-drain
   (`re-frame.ssr/drain-blocking-resources!`) is ROUTE-blocking-keyed — it
   waits only on resources a `reg-route` on-route-entry plan enqueued for
   the current nav-token, so on a route-free page it has nothing to read and
   returns settled immediately without ever looking at `:articles/list`.
   There is no non-route blocking-drain surface to reach for instead (Spec
   016 §SSR and hydration ties blocking to the route resource plan), so this
   example polls the one resource it cares about directly via the public
   `rf/resource-state` introspection read — see `await-resource-loaded!`
   below.

   For the full picture see [Resources: SSR and hydration](../../../../docs/resources/concepts.md#ssr-and-hydration)
   and [Server-side rendering](../../../../docs/ssr/concepts.md).

   The sibling `examples/capabilities/ssr/ssr/` is the simpler cousin: it bakes a
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
            [re-frame.ssr :as rf.ssr]
            ;; The payload assembly the production Ring host uses, so this
            ;; example builds the wire payload the same way the real thing does:
            ;; the fail-closed app-db allowlist (`apply-policy`) and the
            ;; runtime-db projection (`project-runtime-db`) that ships only the
            ;; allowed resource `:entries` under `:rf/runtime-db` (sensitive
            ;; data redacted; indexes rebuilt on install). Server-side only.
            #?(:clj [re-frame.ssr.payload-policy :as rf.ssr.payload-policy])
            ;; The EDN-aware escaper for the payload `<script>` body. A server
            ;; string that happens to contain `</script>` would otherwise close
            ;; the envelope early — so we escape it. Server-side only.
            #?(:clj [re-frame.ssr.html-helpers :as rf.ssr.html-helpers])
            #?(:cljs [re-frame.adapter.reagent :as rf.adapter.reagent])))

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

;; This list declares NO time-based staleness policy (`:stale-after-ms` is
;; deliberately absent), so an entry never ages out on a wall-clock timer — its
;; `:stale-at` is nil and it stays fresh until something explicitly invalidates
;; its `[:article-list]` tag. That is the right freshness shape for THIS demo:
;; the point is the SSR cache handoff, not TTL behaviour, and it lets the
;; offline `index.html` bake a `:stale-at nil` entry that stays deterministically
;; fresh forever — no rotting absolute deadline a static file would have to
;; rebase. A resource that genuinely ages would add `:stale-after-ms <ms>`;
;; explicit invalidation is the freshness authority either way. See
;; docs/resources/glossary.md#invalidate.

(rf/reg-resource :articles/list
  {:doc            "Recent articles (public, SSR-preloaded, invalidation-only freshness)."
   :params-schema  [:map]
   :scope          :rf.scope/global
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
;; questions. The owner `[:ssr request-id nav-token]` is a hold — it keeps the
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
                       ;; request so each render's owner is genuinely unique.
                       :owner    [:ssr :ssr/req-1 :ssr/nav-1]
                       :cause    :ssr-preload}]]]}))

;; ============================================================================
;; VIEWS — passive reads, server and client alike
;; ============================================================================
;;
;; The view does the simplest possible thing: it reads the resource through a
;; subscription and renders whatever's there. Reading never kicks off a fetch —
;; the preload (server) and the hydration (client) already warmed the cache, so
;; the data is just sitting there waiting. `:rf/resource` is the
;; runtime's own subscription; it turns a cache entry into a tidy view-model —
;; a status flag and the data. Either side, the data is present by first render,
;; so there's no skeleton to flash. The same view code runs on both — the "one
;; app, runs twice" promise, now over a managed resource.
;; See docs/ssr/glossary.md#render-to-string.

(rf/reg-view ^{:rf/id :pages/articles} articles-page []
  (let [state @(rf/subscribe [:rf/resource
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
;; DIRECT RESOURCE-PRELOAD POLL (route-free — no `reg-route` in this example)
;; ============================================================================
;;
;; The framework's `rf.ssr/drain-blocking-resources!` is ROUTE-blocking-keyed:
;; it drains only the resources a `reg-route` on-route-entry plan enqueued
;; for the CURRENT nav-token (`[:rf.runtime/routing :resource-blocking
;; <nav-token>]`, written by the routing artefact). This page has no route
;; table at all — see the ns docstring — so the `[:ssr request-id nav-token]`
;; owner `:rf/server-init` ensures under never registers there, and
;; `drain-blocking-resources!` would return `{:settled? true}` immediately,
;; whether or not `:articles/list` had actually resolved — a route-free page
;; leaning on it reports itself settled even when the resource has not.
;;
;; For a route-free SSR page there is no non-route blocking-drain surface to
;; reach for instead (Spec 016 §SSR and hydration ties blocking to the route
;; resource plan). So `await-resource-loaded!` polls the ONE resource this
;; page cares about directly, via the public `rf/resource-state`
;; introspection read — the same bounded-yield-then-recheck shape the
;; framework's own drain loop uses internally (`re-frame.ssr`'s
;; `default-blocking-pump!`), just keyed on a single scoped resource instead
;; of a route's blocking set. Unlike the framework's route-blocking-timeout
;; policy, a deadline here does not settle the entry to a structured
;; first-load failure — it simply stops waiting and lets the render proceed
;; with whatever status the entry is already in.

#?(:clj
   (def ^:private preload-deadline-ms
     "Wall-clock render-deadline budget for `await-resource-loaded!` —
      mirrors the framework's own SSR blocking-drain default
      (`re-frame.ssr`'s `default-ssr-blocking-timeout-ms`)."
     5000))

#?(:clj
   (defn- await-resource-loaded!
     "Poll a resource's runtime entry (`rf/resource-state`) until its
      `:status` reaches a terminal `:loaded` / `:error`, or
      `preload-deadline-ms` elapses. Yields the JVM thread between checks
      (`Thread/sleep`) so the managed-HTTP transport's async reply lands and
      its reply event dispatches — the JVM transport issues requests via
      `HttpClient/sendAsync`, so nothing settles the entry synchronously
      inside the `:rf.resource/ensure` dispatch itself."
     [{:keys [resource scope params frame]}]
     (let [deadline (+ (System/currentTimeMillis) preload-deadline-ms)]
       (loop []
         (let [entry (rf/resource-state {:resource resource :scope scope
                                          :params params :frame frame})]
           (if (or (contains? #{:loaded :error} (:status entry))
                   (>= (System/currentTimeMillis) deadline))
             entry
             (do (Thread/sleep 5)
                 (recur))))))))

;; ============================================================================
;; SERVER ENTRY POINT
;; ============================================================================
;;
;; This is one request, start to finish: mint a per-request frame whose
;; `:initial-events` fire `:rf/server-init`, block on the preloaded page
;; resource, render to a string, and build a hydration payload carrying the
;; allowed resource projection under `:rf/runtime-db`. The frame is torn
;; down in a `finally` on every exit path — success or throw — so a request
;; can never leak a frame and slowly drown the JVM.
;;
;; Two steps are what make this the real server path rather than a toy:
;;
;;   1. `await-resource-loaded!` waits for the `[:ssr …]`-owned ensure to
;;      settle — reach `:loaded` / `:error` — before we walk the tree. So
;;      the render never catches the page mid-fetch with a `:loading`
;;      skeleton frozen into the HTML. See the DIRECT RESOURCE-PRELOAD POLL
;;      section above for why this page polls directly instead of calling
;;      `rf.ssr/drain-blocking-resources!`.
;;   2. `rf.ssr.payload-policy/project-runtime-db` boils the runtime-db down to just
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
           _   (rf.ssr/set-request! fid request)
           f   (rf/make-frame {:id fid :doc       "resources-ssr per-request frame"
                               :platform  :server
                               :initial-events [[:rf/server-init]]})]
       (try
         (rf/with-frame f
           ;; (1) Settle the blocking page resource before we render a thing.
           ;; The `[:ssr …]`-owned ensure that `:rf/server-init` kicked off has
           ;; to land on a terminal status (:loaded / :error) before the render
           ;; walk works with real data instead of an in-flight guess.
           (await-resource-loaded! {:resource :articles/list
                                     :scope    :rf.scope/global
                                     :params   {}
                                     :frame    f})
           (let [final-db      (rf/app-db-value f)
                 final-runtime (:rf.db/runtime (rf/frame-state-value f))   ;; this is where :rf.runtime/resources lives
                 hiccup        ((rf/view :app/root))
                 ;; Render the app as a FRAGMENT, not a full document. The
                 ;; `handle-request` envelope below owns the ONE document shell —
                 ;; the outer `<!DOCTYPE html>`, `<head>` metadata, the payload
                 ;; `<script>`, and `main.js`. The app render only fills
                 ;; `<div id='app'>`, so it must NOT prepend its own doctype:
                 ;; `:doctype? true` here would nest a second `<!DOCTYPE html>`
                 ;; inside `#app`, and the response would be malformed HTML that
                 ;; only survives via browser parser recovery. Keep
                 ;; `:emit-hash? true` — the hydration hash the client verifies
                 ;; still belongs on the fragment's root element.
                 html          (rf/render-to-string hiccup {:emit-hash? true})
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
                 payload       (rf.ssr.payload-policy/build-payload
                                 f
                                 (rf.ssr.payload-policy/apply-policy final-db policy-opts)
                                 render-hash
                                 (assoc policy-opts
                                        :runtime-db (rf.ssr.payload-policy/project-runtime-db
                                                      final-runtime)))
                 ;; Drop the payload's `:rf/frame-id`. `build-payload` stamps it
                 ;; with this per-request gensym frame (`f`), but the client
                 ;; hydrates a fixed app-frame (`:rf/default`, below). When a
                 ;; `:rf/frame-id` *is* present, `rf.ssr/hydrate!` checks it against
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
                   (rf.ssr.html-helpers/escape-edn-script-body (pr-str payload))
                   "</script><script src='/main.js'></script>"
                   "</body></html>")}))
         (finally
           (rf/destroy-frame! fid))))))

;; ============================================================================
;; CLIENT ENTRY POINT
;; ============================================================================
;;
;; The browser's side of the handoff. `rf.ssr/hydrate!` reads the payload,
;; dispatch-syncs `[:rf/hydrate payload]` to install the resource projection
;; into the frame's `:rf.runtime/resources` slice, then verifies the render
;; hash to confirm client and server agree on what the page should look like.
;; A fresh hydrated entry renders its data on the first paint and serves it
;; straight from cache — no second fetch, which is exactly what preloading was
;; for. A stale entry quietly refetches in the background, per its policy.
;; See docs/ssr/concepts.md#the-client-side-hydrate-then-verify.

#?(:cljs (defonce app-root (rf.adapter.reagent/client-root)))

;; The client's one app-frame. The app names its hydration target out loud and
;; threads the same id through two places: `rf.ssr/hydrate!` (where the server
;; state lands) and the root `frame-provider` (where in-tree dispatch/subscribe
;; go looking for their frame). One id, both ends — that's what makes them meet.
;; It has to be a `:client`-platform frame, or the `:rf.ssr/check-*`
;; compatibility checks that `:rf/hydrate` fires would simply sit there inert.
;; See docs/ssr/concepts.md#deploy-drift-checks-come-along-for-free.
(def app-frame :rf/default)

;; Re-render the current view through the adapter's client root. Tagged
;; `^:dev/after-load` so shadow-cljs re-runs it after each hot reload — edited
;; views re-render into the same root and the already-hydrated frame. The
;; handle makes the reload safe by construction: `run` performed the ONE
;; hydrating first render (or a fresh mount on a client-only load), and every
;; later `render!` through the same handle updates that root — never a second
;; root, never a re-hydration, so a reload can't re-seed over the interactive
;; state.
#?(:cljs
   (defn ^:dev/after-load render! []
     (when-let [el (and (exists? js/document) (js/document.getElementById "app"))]
       (rf.adapter.reagent/render! app-root
         [rf/frame-provider {:frame app-frame}
          [(rf/view :app/root)]]
         el))))

#?(:cljs
   (defn run []
     (rf/init! rf.adapter.reagent/adapter)
     (rf/make-frame {:id app-frame :doc "resources-ssr client app-frame" :platform :client})
     ;; READ, HYDRATE, VERIFY against the app-frame. `hydrate!` seeds STATE only
     ;; — it never touches the DOM — and hands back the payload it applied, or
     ;; nil on a plain client load with no server render to adopt.
     (let [el      (and (exists? js/document) (js/document.getElementById "app"))
           payload (rf.ssr/hydrate! {:frame          app-frame
                                  :render-tree-fn (fn [] ((rf/view :app/root)))})
           tree    [rf/frame-provider {:frame app-frame} [(rf/view :app/root)]]]
       (when el
         ;; The whole adopt-vs-fresh decision is one option. With a payload the
         ;; server painted the page, so the first render HYDRATES: React
         ;; reconciles against the server markup — same nodes, listeners
         ;; attached, no re-paint. Without one it is a plain first load and the
         ;; adapter mounts a fresh root; a fresh entry serves from cache, a
         ;; stale or absent one refetches per its policy. Either way the root
         ;; is created exactly once, here, and `render!` above reuses it.
         (rf.adapter.reagent/render! app-root tree el {:hydrate? (some? payload)})))))
