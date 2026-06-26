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
     the target frame's `:rf.runtime/resources` slice; the hydration reconcile
     orphans the SSR owner, recomputes the reverse indexes from `:entries`,
     and settles a wire-stripped in-flight entry to a stable status; a fresh
     hydrated entry renders immediately and does NOT immediately re-fetch
     (stale / redacted / omitted entries refetch by the hydration plan).

   The SSR blocking-drain, the per-entry projection with redaction /
   omission / scoped-key privacy / index omission, and the client hydration
   reconcile + refetch plan are all runtime behaviour (EP-0003). This
   example drives the actual server path: it DRAINS the blocking page
   resource before render (`ssr/drain-blocking-resources!`) and serializes
   only the ALLOWED runtime-db projection (`payload-policy/project-runtime-db`)
   into `:rf/runtime-db` — NEVER the full runtime-db. The `:rf/app-db` slice is
   projected through the explicit fail-closed allowlist (`apply-policy`) the
   real Ring host uses. The example tree is test-free; the static
   `index.html` next to this file carries a pre-baked payload for the runnable
   browser-side `run` (no Clojure server ships with the example), exactly as
   the sibling `examples/reagent/ssr/` does for plain SSR.

   Compare with `examples/reagent/ssr/` (plain SSR with a managed-HTTP fetch
   written into app-db): here the same data is a runtime-managed RESOURCE, so
   identity / scope / staleness / hydration are the framework's job, and the
   cache lives in the runtime partition rather than app-db."
  (:require [re-frame.core :as rf]
            ;; Managed HTTP — the single built-in resource transport.
            [re-frame.http.managed]
            ;; Resources artefact (boots the registrations + the LATE-BOUND
            ;; SSR projection hook the ssr artefact consults).
            [re-frame.resources]
            ;; SSR ships in day8/re-frame2-ssr — render-to-string, the
            ;; `:rf/hydrate` event, and the per-request server fxs. The
            ;; resources artefact late-binds its runtime-db projection into
            ;; the SSR `project-runtime-db` allowlist (Spec 016 §SSR).
            [re-frame.ssr :as ssr]
            ;; The hydration-payload assembly the real Ring host uses: the
            ;; fail-closed app-db allowlist (`apply-policy`) + the SSR
            ;; runtime-db projection (`project-runtime-db`) that rides ONLY the
            ;; allowed resource `:entries` onto `:rf/runtime-db` (redacted /
            ;; omitted per the resource's classification; indexes recompute on
            ;; install). Server-side only — `handle-request` (`:clj`) uses it.
            #?(:clj [re-frame.ssr.payload-policy :as payload-policy])
            ;; The EDN-aware `<script>`-body escaper the production Ring host
            ;; uses (`re-frame.ssr.ring.shell/payload-script-tag` →
            ;; `escape-edn-script-body`). Server-side only — a server-provided
            ;; string carrying `</script>` would otherwise close the payload
            ;; `<script>` envelope (security audit 2026-05-14 §P1).
            #?(:clj [re-frame.ssr.html-helpers :as html])
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; RESOURCE
;; ============================================================================
;;
;; The page's server-state, declared once. You describe WHAT the read is and
;; HOW to fetch it; the runtime owns the cache, dedupe, and staleness.
;;
;; `:scope :rf.scope/global` is the explicit, auditable claim that this article
;; list is the SAME for every user. Scope is part of the read's cache identity,
;; so one principal's data can never surface in another's cache — and it is
;; load-bearing for hydration: the serialized scope and the client's scope must
;; agree before the client treats hydrated data as usable. SSR hydration MUST
;; NEVER cross scopes (a user-scoped page would carry a scope resolver instead).

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
;; On the server, `:rf/server-init` (dispatched at per-request frame creation)
;; ensures the page resource — fetch it if the cache is cold — so the render
;; finds it warm. The renderer then waits for it to settle before rendering
;; (Spec 016 §SSR §Server route handling).
;;
;; Two facts ride the ensure. The OWNER `[:ssr request-id nav-token]` is a
;; lease: it keeps the entry alive for the duration of THIS server render and
;; nothing longer (released on teardown; reconciled to an orphan on hydration —
;; a server render's lease has no business surviving as a live client hold).
;; The CAUSE `:ssr-preload` is pure provenance — WHY the fetch happened,
;; recorded for the trace, affecting no liveness. Owner = lifetime; cause =
;; explanation.

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
;; The view reads the resource through a subscription and never fetches — it
;; doesn't know or care which runtime it is in. `:rf.resource/state` is the
;; runtime-supplied derivation that turns the cache entry into a view-model
;; (a status flag + the data). On the server the resource was preloaded; on
;; the client the hydrated entry is already present, so the first render shows
;; data without a fetch. Same view code both sides — the SSR/CLJS parity Spec
;; 011 promises, now over a runtime-managed resource.

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
;; Mirrors examples/reagent/ssr/ — a per-request frame whose `:initial-events`
;; dispatch `:rf/server-init`, a DRAIN to settle the blocking page resource,
;; a render to string, and a hydration payload carrying the ALLOWED resource
;; projection in `:rf/runtime-db`. The per-request frame is torn down in a
;; `finally` on every exit path (memory hygiene).
;;
;; Two steps make this the canonical server path (EP-0003):
;;
;;   1. `ssr/drain-blocking-resources!` settles the `[:ssr …]`-owned blocking
;;      ensure (or times it out into a structured first-load failure) BEFORE
;;      the render walk, so the render never sees a hung `:loading` skeleton.
;;   2. `payload-policy/project-runtime-db` projects the runtime-db to the
;;      SERIALIZABLE allowlist — only the durable `:rf.runtime/resources`
;;      `:entries`, per-entry redacted (`:sensitive?`) / omitted (`:large?`),
;;      with the reverse indexes EXCLUDED (recomputed on install). The example
;;      NEVER serializes the full runtime-db. The app-db slice rides the
;;      explicit fail-closed allowlist via `apply-policy` (here an empty
;;      allowlist — the page state is the RESOURCE, not app-db).

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
           ;; (1) settle the blocking page resource before rendering — the
           ;; `[:ssr …]`-owned ensure dispatched by `:rf/server-init` must reach
           ;; a terminal status (:loaded / :error) or time out into a settled
           ;; first-load failure, so the render walk sees real data.
           (ssr/drain-blocking-resources! f)
           (let [final-db      (rf/app-db-value f)
                 final-runtime (rf/runtime-db-value f)   ;; carries :rf.runtime/resources
                 hiccup        ((rf/view :app/root))
                 html          (rf/render-to-string hiccup {:doctype? true :emit-hash? true})
                 render-hash   (rf/render-tree-hash hiccup)
                 ;; (2) build the canonical payload the way the Ring host does:
                 ;; the app-db slice through the fail-closed allowlist, and
                 ;; the runtime-db through the SSR projection (only the allowed
                 ;; resource `:entries`).
                 ;;
                 ;; The page state is the RESOURCE (it rides `:rf/runtime-db`),
                 ;; so app-db carries nothing of its own — but `:payload` is
                 ;; FAIL-CLOSED and an empty `[]` allowlist is treated as
                 ;; MISSING policy (it throws `:rf.error/ssr-missing-payload-
                 ;; policy`), not as a valid empty allowlist (Spec 011 §`:rf/app-db`
                 ;; projection). The explicit, valid policy for "ship the whole
                 ;; (here empty) app-db" is the `:rf.ssr.payload/whole-app-db`
                 ;; opt-in keyword — it projects the empty app-db to `{}`
                 ;; cleanly.
                 policy-opts   {:payload :rf.ssr.payload/whole-app-db}
                 payload       (payload-policy/build-payload
                                 f
                                 (payload-policy/apply-policy final-db policy-opts)
                                 render-hash
                                 (assoc policy-opts
                                        :runtime-db (payload-policy/project-runtime-db
                                                      final-runtime)))
                 ;; EP-0002: `build-payload` stamps the per-request
                 ;; server frame (`f`) as `:rf/frame-id`, but the client
                 ;; hydrates a FIXED app-frame (`app-frame` → `:rf/default`,
                 ;; below). `ssr/hydrate!` VALIDATES a present payload
                 ;; `:rf/frame-id` against the client's explicit `:frame` and
                 ;; raises `:rf.error/hydration-frame-id-mismatch` only when the
                 ;; two DISAGREE (Spec 011 §The hydration payload — the frame-id
                 ;; is validation evidence, not a target resolver). The server's
                 ;; per-request gensym would never equal the client's fixed
                 ;; `:rf/default`, so a present stamp here would always conflict;
                 ;; we therefore DROP it, and an absent `:rf/frame-id` is
                 ;; explicitly NO conflict (the explicit client target stands).
                 ;; The static `index.html` next to this file reaches the same
                 ;; no-conflict outcome by the OTHER valid route: it stamps a
                 ;; `:rf/frame-id :rf/default` that EQUALS this client target
                 ;; (present-and-equal is also no conflict — a hand-written
                 ;; stand-in can pin the matching id, where a live per-request
                 ;; server cannot). A deployment that wants a frame-id on the
                 ;; dynamic wire stamps a STABLE id both sides agree on, not a
                 ;; per-request gensym.
                 payload       (dissoc payload :rf/frame-id)]
             {:status  200
              :headers {"Content-Type" "text/html"}
              :body
              (str "<!DOCTYPE html><html><head><meta charset='utf-8'/>"
                   "<title>Resources SSR demo</title></head><body>"
                   "<div id='app'>" html "</div>"
                   ;; Emit the payload `<script>` through the EDN-aware
                   ;; `</script>`-escaper the production Ring host uses
                   ;; (security audit 2026-05-14 §P1) so a server-
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
;; `:rf.runtime/resources` slice under the locked :replace-frame-state
;; policy), and verifies the render hash. A FRESH hydrated entry renders its
;; data immediately and does NOT immediately re-fetch (Spec 016 §SSR client
;; hydration); a stale entry would background-refetch by policy.

#?(:cljs (defonce react-root (atom nil)))

;; EP-0002: the SSR hydration target is CARRIED — established
;; explicitly here and threaded through both `ssr/hydrate!` and the root
;; `frame-provider`. This example uses `:rf/default` as its FIXED client
;; app-frame. `ssr/hydrate!` validates the payload's `:rf/frame-id` against
;; this explicit target and raises `:rf.error/hydration-frame-id-mismatch`
;; only on a present-AND-DIFFERENT value (Spec 011 §The hydration payload).
;; The two no-conflict shapes both appear in this example: `handle-request`
;; above DROPS `:rf/frame-id` (its per-request gensym would never equal this
;; target, so an absent frame-id is the safe shape), while the static
;; `index.html` next to this file carries `:rf/frame-id :rf/default` — present
;; AND equal to this target, the other no-conflict case. The frame MUST be
;; `:client`-platform so the `:rf.ssr/check-*` compatibility-check fxs the
;; `:rf/hydrate` handler dispatches actually fire.
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
