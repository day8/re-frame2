(ns ssr.core
  "Worked example for [Construction Prompt CP-9](../../../spec/Construction-Prompts.md)
   and [Spec 011 SSR & Hydration](../../../spec/011-SSR.md). A small server+client app:
   server renders a 'recent articles' page; client hydrates and remains
   interactive.

   Per [011-SSR.md](../../../spec/011-SSR.md): SSR is part of the target
   architecture, not a future concession. View pure-fn requirement,
   id-valued override seam, hydration via :rf/hydrate.

   This is .cljc so the same code can be evaluated server-side (JVM, with
   :clj branches) and client-side (browser, with :cljs branches).

   Demonstrates:
   - Per-request frame on the server                 (with-frame)
   - :rf/server-init dispatched at frame creation
   - Server-only fx via :platforms #{:server}        (and skipping on the client)
   - Pure hiccup → HTML emitter                       (rf/render-to-string)
   - Hydration payload format                         (:rf/hydration-payload schema)
   - Per-request frame teardown in a `finally`        (rf/destroy-frame!)
   - Framework-owned hydration: the client boots via the `ssr/hydrate!`
     helper, which dispatches the framework's reserved `:rf/hydrate` event
     (the app does NOT re-register it). `:rf/hydrate` replaces (not merges)
     the client frame-state, per the locked :replace-frame-state policy.
   - data-rf-render-hash structural marker on the root element; `ssr/hydrate!`
     verifies the client render-tree hash against the server's after first
     render and the runtime emits :rf.ssr/hydration-mismatch on disagreement

   Runnable form: the hand-written `index.html` next to this
   file ships with pre-rendered HTML inside `<div id='app'>` and a pre-
   baked `<script id='__rf_payload'>` — exactly the shape `handle-request`
   below would emit if a real Clojure server were sitting in front. The
   browser-side `run` calls `ssr/hydrate!` (read payload → dispatch
   `:rf/hydrate` → verify) and renders against the now-seeded frame-state."
  (:require [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves at the call sites below.
            [re-frame.schemas]
            ;; Managed-HTTP ships in day8/re-frame2-http.
            ;; Loading the ns here registers the `:rf.http/managed` fx
            ;; family — the SSR worked example dispatches
            ;; `:rf.http/managed` for the article-list fetch and uses
            ;; per-frame `:fx-overrides` to redirect to a canned-success
            ;; stub during render. Without the require, the override
            ;; would target an unregistered fx-id.
            [re-frame.http.managed]
            ;; The canned-stub fx ids
            ;; (`:rf.http/managed-canned-success`,
            ;; `:rf.http/managed-canned-failure`) register from
            ;; re-frame.http.test-support, NOT re-frame.http.managed.
            ;; The SSR demo drives the canned stubs via :fx-overrides
            ;; and is a test/demo affordance (no Conduit backend ships
            ;; with the example), so the test-support require is the
            ;; explicit opt-in.
            [re-frame.http.test-support]
            ;; SSR ships in day8/re-frame2-ssr. Loading
            ;; the ns here registers the six `:rf.server/*` server-only
            ;; fxs, the `:rf/hydrate` event, and the
            ;; `:rf.ssr/default-error-projector`, and publishes the
            ;; late-bind hooks (`:ssr/render-tree-hash`,
            ;; `:ssr/render-to-string`, `:ssr/reg-error-projector`,
            ;; `:ssr/project-error`). Without the require the four core
            ;; re-exports raise `:rf.error/ssr-artefact-missing`.
            [re-frame.ssr :as ssr]
            ;; The EDN-aware `<script>`-body escaper the production Ring
            ;; host uses (`re-frame.ssr.ring.shell/payload-script-tag` →
            ;; `escape-edn-script-body`). Server-side only — the `:clj`
            ;; `handle-request` drops the `pr-str`'d payload inside a
            ;; `<script type="application/edn">` body and a server-provided
            ;; string containing `</script>` would otherwise close the
            ;; envelope (security audit 2026-05-14 §P1). It lives
            ;; in the SSR artefact (`re-frame.ssr.html-helpers`), so it ships
            ;; with the same `day8/re-frame2-ssr` dep the example already
            ;; requires above.
            #?(:clj [re-frame.ssr.html-helpers :as html])
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; SCHEMA
;; ============================================================================
;;
;; EP-0002: `reg-app-schema` is context-required frame-local and
;; raises `:rf.error/no-frame-context` under no frame scope — so a bare ns-load
;; registration is wrong, and a naive `with-frame :rf/default`
;; would bind the schema to the client frame ONLY, leaving the per-request
;; SERVER frames (where the SSR commits actually validate) unschema'd. SSR has
;; TWO frame families: the per-request server frame (gensym, in
;; `handle-request`) and the FIXED client hydration frame (`app-frame` →
;; `:rf/default`, in `run`). The schema is the same contract for both, so we
;; hold it as a value and register it explicitly against EACH frame at its
;; entry point (server: per request; client: at boot) with the `{:frame …}`
;; override. Holding it as a def also keeps ns-load side-effect-free — the
;; entry namespace loads without any ambient frame fixture.
;; `[:maybe …]` because the slice is ABSENT (nil) until `:articles/loaded`
;; commits — the per-request server frame's `:rf/server-init` commits an
;; articles-free db first (it only kicks off the managed-HTTP fetch), and the
;; client frame starts empty before hydration. A bare `[:vector …]` would reject
;; that legitimate intermediate `nil` and roll the commit back (the
;; bug was masked precisely because validation never ran on these frames).
(def ArticlesSchema
  [:maybe [:vector [:map
                    [:id    :string]
                    [:title :string]
                    [:body  :string]]]])

;; ============================================================================
;; FX
;; ============================================================================
;;
;; HTTP requests go via the framework-shipped `:rf.http/managed` (Spec 014).
;; The example deliberately doesn't register an HTTP-side fx of its own —
;; the SSR test below uses the `:fx-overrides` seam to redirect
;; `:rf.http/managed` to a per-frame canned-success stub so the JVM-side
;; render exercises the full domino loop without real network traffic.

(rf/reg-fx :auth.session/store
  {:doc       "Persist a session token in localStorage."
   :platforms #{:client}}              ;; client-only — server SSR skips this
  (fn fx-auth-session-store [_m {:keys [token]}]
    #?(:cljs (when-let [ls (.-localStorage js/globalThis)]
               (.setItem ls "auth/token" token)))))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :rf/server-init
  {:doc       "Per-request server-side initialisation. Reads the request
               via the :rf.server/request cofx (Spec 011 §Request storage
               substrate), dispatches setup events. Server only."
   :platforms #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn handler-rf-server-init [{:keys [db rf.server/request]} _]
    ;; `request` is the host-supplied HTTP request map (Ring shape under
    ;; the bundled adapter); read URL/headers/cookies from here rather
    ;; than from a positional event arg.
    ;;
    ;; EP-0001: the framework route slice lives in the
    ;; runtime-db partition at `[:rf.runtime/routing :current]`. This
    ;; example's render never reads the route slice, so the `:db` write is
    ;; simply dropped — the server flow only needs to kick off the
    ;; managed-HTTP article fetch.
    {:db db
     :fx [[:rf.http/managed
           {:request    {:method :get :url "/api/articles"}
            :decode     :json
            :on-success [:articles/loaded]}]]}))

(rf/reg-event :articles/loaded
  (fn handler-articles-loaded [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc db :articles value)}))

;; HYDRATION IS FRAMEWORK-OWNED. `:rf/hydrate` is a reserved `:rf/*` event
;; (Conventions §Reserved namespaces) registered by `re-frame.ssr` — the app
;; MUST NOT re-register it. The framework handler installs a coherent
;; frame-state in one atomic transition under the locked :replace-frame-state
;; policy (Spec 011 §The :rf/hydrate event): server is authoritative for the
;; initial client frame-state, so the payload's :rf/app-db replaces the app-db
;; partition AND :rf/runtime-db replaces the serializable runtime-db projection
;; (machine snapshots, route slice, …). It also does work the app must not
;; reinvent: it validates the payload fail-closed (a non-map payload or a
;; present-but-non-map :rf/app-db / :rf/runtime-db slice is REJECTED, leaving
;; the client frame-state untouched), and it stashes the server's
;; :rf/render-hash under [:rf.runtime/ssr :hydration :server-hash] so
;; `ssr/verify-hydration!` can drive mismatch detection after the first render.
;; (This example carries no machines and doesn't hydrate the route, so its
;; :rf/runtime-db is empty — but the shape is the same one a richer app uses.)
;; The client `run` below boots via `ssr/hydrate!`, the framework helper that
;; reads the payload, dispatches `[:rf/hydrate payload]`, and verifies — see
;; the CLIENT ENTRY POINT section.

;; ============================================================================
;; CLIENT-SIDE INTERACTIVITY EVENTS
;; ============================================================================
;;
;; A small interactive surface so we can verify that hydration left the
;; client fully reactive — clicking "Hide bodies" must toggle the body
;; paragraphs in/out without a full re-render. The slice has no server
;; correspondent, so it lives outside the SSR payload's authoritative
;; slice and starts at its default value on the client.

(rf/reg-event :articles/toggle-bodies
  (fn [{:keys [db]} _] {:db (update db :articles/show-bodies? (fnil not true))}))

;; ============================================================================
;; SUBSCRIPTIONS / VIEWS
;; ============================================================================

(rf/reg-sub :articles/slice (fn [db _] (:articles db)))

(rf/reg-sub :articles/show-bodies?
  (fn [db _]
    ;; Default is true so the SSR pass renders bodies; the client can hide
    ;; them post-hydration.
    (let [v (:articles/show-bodies? db)]
      (if (nil? v) true v))))

;; reg-view (defn-shape per Spec 004 §reg-view) auto-defs the symbol and
;; registers under (keyword *ns* sym) — overridden here via
;; ^{:rf/id ...} so the :pages/articles / :app/root ids the view
;; callers below use match the registrations.
;;
;; Server-side (JVM) the auto-injected `subscribe` in `reg-view` is a
;; macro-time concept that resolves to (:subscribe (rf/frame-handle)) — a frame-bound
;; subscribe fn — at runtime. On the JVM render path, deref of a
;; subscription yields its current value; on the client, deref tracks
;; the reaction so re-renders fire on app-db changes. Same code, both
;; sides — that's the SSR/CLJS parity Spec 011 promises.
(rf/reg-view ^{:rf/id :pages/articles} articles-page []
  (let [arts         @(subscribe [:articles/slice])
        show-bodies? @(subscribe [:articles/show-bodies?])]
    [:div.page
     [:h1 "Recent articles"]
     [:button.toggle-bodies
      {:data-testid "toggle-bodies"
       :on-click #(dispatch [:articles/toggle-bodies])}
      (if show-bodies? "Hide bodies" "Show bodies")]
     (if (seq arts)
       (into [:ul {:data-testid "articles-list"}]
             (for [{:keys [id title body]} arts]
               ^{:key id}
               [:li [:h3 title]
                (when show-bodies? [:p.body {:data-testid "article-body"} body])]))
       [:p "No articles."])]))

(rf/reg-view ^{:rf/id :app/root} root-view []
  [(rf/view :pages/articles)])

;; ============================================================================
;; SERVER ENTRY POINT
;; ============================================================================
;;
;; The server flow:
;;   1. Accept request.
;;   2. set-request! populates the per-frame slot for the :rf.server/request
;;      cofx (Spec 011 §Request storage substrate).
;;   3. make-frame; :on-create dispatches :rf/server-init (which reads
;;      the request via the cofx).
;;   4. Drain settles (HTTP fetches resolve via :rf.http/managed; the JVM
;;      transport uses java.net.http.HttpClient under the hood).
;;   5. Render to string via the pure hiccup → HTML emitter.
;;   6. Serialise app-db; ship in the HTML.
;;   7. destroy-frame! in a `finally` — per Spec 011 §Per-request frame
;;      teardown contract every per-request server frame ends with
;;      `destroy-frame!`. This is load-bearing for memory hygiene on a
;;      long-running server: it drops the frame record AND fires the
;;      `:ssr/on-frame-destroyed` hook, which releases the per-frame
;;      request slot + response accumulator + error-trace buffer (all
;;      frame-id-keyed side-channel atoms). Without it, every request
;;      leaks a frame + a request entry. The `finally` runs on BOTH the
;;      success and throw paths (e.g. a render or fetch error), so no
;;      partially-built frame leaks either. This mirrors `ssr_streaming`'s
;;      `handle-request` and the bundled `re-frame.ssr.ring` host adapter,
;;      which both tear the frame down on every exit path.

#?(:clj
   (defn handle-request [request]
     (let [fid (keyword "rf.frame" (str (gensym "f")))
           _   (ssr/set-request! fid request)
           ;; Register the app schema AGAINST THIS per-request server frame
           ;; BEFORE `:on-create` fires `:rf/server-init` — the
           ;; per-request frame is where the server-side `:articles` commit
           ;; actually validates, so the schema must bind here, not only on the
           ;; client frame. `reg-frame` runs the `:on-create` cascade
           ;; synchronously before returning, so the schema must be in place
           ;; first; we register it under the gensym `fid` and only then create
           ;; the frame.
           _   (rf/reg-app-schema [:articles] {:schema ArticlesSchema :frame fid})
           f   (rf/reg-frame fid
                 {:doc       "ssr-example per-request frame"
                  :platform  :server
                  :initial-events [[:rf/server-init]]})]
       (try
         (rf/with-frame f
           (let [final-db      (rf/app-db-value f)        ;; app-db partition
                 final-runtime (rf/runtime-db-value f)    ;; runtime-db partition (serializable)
                 hiccup   ((rf/view :app/root))
                 ;; render-to-string with :emit-hash? embeds
                 ;; data-rf-render-hash="<hex>" on the root element. The
                 ;; client recomputes the hash after its first render and
                 ;; the runtime emits :rf.ssr/hydration-mismatch on
                 ;; disagreement.
                 html     (rf/render-to-string hiccup
                                               {:doctype?    true
                                                :emit-hash?  true})
                 ;; Same hash also lands on the payload so non-DOM
                 ;; environments (server logs, CDN cache keys) can read it
                 ;; without HTML parsing.
                 render-hash (rf/render-tree-hash hiccup)
                 ;; EP-0002: the payload DELIBERATELY OMITS
                 ;; `:rf/frame-id`. The server renders under a per-request
                 ;; gensym frame (`f`), but the client hydrates a FIXED app-
                 ;; frame (`app-frame` → `:rf/default`, below). `ssr/hydrate!`
                 ;; VALIDATES a present payload `:rf/frame-id` against the
                 ;; client's explicit `:frame` and raises
                 ;; `:rf.error/hydration-frame-id-mismatch` on disagreement
                 ;; (Spec 011 §The hydration payload — the frame-id is
                 ;; validation evidence, NOT a target resolver). Stamping the
                 ;; server's per-request gensym here would always conflict
                 ;; with the client's fixed frame; an ABSENT `:rf/frame-id` is
                 ;; explicitly NO conflict (the explicit client target
                 ;; stands), so the dynamic server output matches the static
                 ;; `index.html` next to this file exactly. A deployment that
                 ;; WANTS the round-trip to carry a frame-id stamps a STABLE
                 ;; id both sides agree on (or has the client read the
                 ;; payload's frame-id as its hydration target) — not a
                 ;; per-request gensym.
                 payload  {:rf/version     1
                           :rf/app-db      final-db        ;; app-db partition
                           :rf/runtime-db  final-runtime   ;; serializable runtime-db projection
                           :rf/render-hash render-hash}]
             {:status  200
              :headers {"Content-Type" "text/html"}
              :body
              (str "<!DOCTYPE html><html><head>"
                   "<meta charset='utf-8'/>"
                   "<title>SSR demo</title>"
                   "</head><body>"
                   "<div id='app'>" html "</div>"
                   ;; Emit the payload `<script>` through the EDN-aware
                   ;; `</script>`-escaper the production Ring host uses
                   ;; (security audit 2026-05-14 §P1): a server-
                   ;; provided string carrying `</script>` (round-tripped
                   ;; through app-db) can't close the envelope. The encoder
                   ;; rewrites a less-than char to its unicode reader escape
                   ;; ONLY inside EDN string literals, so the payload still
                   ;; round-trips through the client's `cljs.reader/read-string`
                   ;; unchanged.
                   "<script id='__rf_payload' type='application/edn'>"
                   (html/escape-edn-script-body (pr-str payload))
                   "</script>"
                   "<script src='/main.js'></script>"
                   "</body></html>")}))
         ;; Tear the per-request frame down on EVERY exit path. The
         ;; `:ssr/on-frame-destroyed` hook (fired by destroy-frame!)
         ;; clears the request slot + the SSR side-channel atoms, so no
         ;; explicit `clear-request!` is needed here.
         (finally
           (rf/destroy-frame! fid))))))

;; ============================================================================
;; CLIENT ENTRY POINT
;; ============================================================================
;;
;; The client flow is `ssr/hydrate!` — the framework's client-boot helper,
;; the symmetric counterpart of the server-side render pipeline (Spec 011
;; §Client flow / §Client-side hydration boot helper). One call fuses the
;; three steps the spec mandates, in order:
;;   1. READ    — the embedded `__rf_payload` `<script>` via the pinned id.
;;                A malformed payload fails CLOSED (no app-db replacement) and
;;                a missing payload is the client-only first-load shape.
;;   2. HYDRATE — dispatch-sync `[:rf/hydrate payload]` BEFORE first render.
;;                The framework handler *replaces* the frame-state with the
;;                server slice (locked :replace-frame-state policy) and stashes
;;                the server's :rf/render-hash for the verify step.
;;   3. VERIFY  — after first render, hash the `:render-tree-fn` result and
;;                compare it to the server hash; a disagreement emits
;;                :rf.ssr/hydration-mismatch.
;; `hydrate!` returns the payload it applied (nil on a client-only load), so
;; `run` branches on "was this server-rendered?" without re-reading the DOM.
;; The app does NOT hand-roll a `read-server-payload` + bare dispatch — that
;; bypasses the fail-closed validation, the metadata stash, and the verify
;; step the helper pins.

;; User events live under an app-chosen namespace, NEVER under the
;; reserved `:rf/*` root (Conventions §Reserved namespaces — user code MUST
;; NOT register handlers under `:rf/*`). `:rf/hydrate` and `:rf/server-init`
;; are framework-owned (the runtime / re-frame.ssr register `:rf/hydrate`;
;; `:rf/server-init` is the documented per-request server-init pattern event
;; the app supplies a body for). This client-only-load bootstrap is a fresh
;; user invention, so it gets the app's own `:ssr/` namespace.
(rf/reg-event :ssr/client-bootstrap
  {:doc "Client-side init that runs even if the server didn't render this page."}
  (fn [{:keys [db]} _] {:db db}))

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
#?(:cljs (defonce react-root (atom nil)))

;; EP-0002: under the carried-frame invariant the
;; runtime never synthesises a frame from absence — the SSR hydration target
;; is CARRIED, established explicitly by the app and threaded through both
;; `ssr/hydrate!` (the seed target) AND the root `frame-provider` (where every
;; in-tree `dispatch`/`subscribe` resolves). This example uses `:rf/default`
;; as its client app-frame id (a migration may pick `:rf/default`, but the
;; runtime will not infer it). It MUST be a `:client`-platform frame so the
;; `:rf.ssr/check-*` compatibility-check fxs the `:rf/hydrate` handler
;; dispatches actually fire (Spec 011 §The :rf/hydrate event — a `:server`-
;; platform frame skips them). BOTH the static `index.html` payload AND the
;; dynamic `handle-request` payload above carry NO `:rf/frame-id`: the server
;; renders under a per-request gensym frame the client can't know ahead of
;; time, and the client hydrates this FIXED app-frame, so an absent frame-id
;; is the correct shape (it is NOT a hydration-frame-id conflict — the
;; explicit `:frame` stands). A present-but-different payload `:rf/frame-id`
;; — e.g. the server's per-request gensym against this `:rf/default` client
;; target — WOULD surface a structured `:rf.error/hydration-frame-id-mismatch`
;; (Spec 011 §The hydration payload), which is exactly why `handle-request`
;; omits it.
(def app-frame :rf/default)

#?(:cljs
   (defn run []
     ;; Boot the runtime against the Reagent substrate. Idempotent — the
     ;; first call installs the adapter; subsequent calls (e.g. shadow-cljs
     ;; hot reloads) are no-ops. `init!` installs the adapter but does NOT
     ;; create a frame — EP-0002: the app establishes its frame explicitly
     ;; (below) and the runtime never synthesises `:rf/default` from absence.
     ;;
     ;; Pass the adapter spec map directly. There is no
     ;; default-adapter registry — each adapter ns exports an `adapter`
     ;; var the consumer requires and passes here.
     (rf/init! reagent-adapter/adapter)
     ;; Establish the carried client app-frame BEFORE hydrating into it.
     ;; `reg-frame` is a surgical no-op on re-registration (hot-reload Just
     ;; Works). `:platform :client` makes the hydrate compatibility-check
     ;; fxs fire (Spec 011 §The :rf/hydrate event).
     (rf/reg-frame app-frame {:doc      "ssr-example client app-frame"
                              :platform :client})
     ;; Register the app schema AGAINST THE FIXED CLIENT FRAME so
     ;; the hydrated `:articles` commit + every post-hydration interactive
     ;; commit validate on the client too — the symmetric counterpart of the
     ;; per-request registration in `handle-request`. `{:frame app-frame}` is
     ;; the explicit override; `reg-app-schema` is a no-op-safe re-registration
     ;; on hot-reload.
     (rf/reg-app-schema [:articles] {:schema ArticlesSchema :frame app-frame})
     ;; `ssr/hydrate!` is the framework client-boot helper (Spec 011
     ;; §Client-side hydration boot helper). It READs the `__rf_payload`
     ;; script, dispatch-syncs `[:rf/hydrate payload]` to install the
     ;; server's frame-state into the EXPLICIT `:frame` BEFORE first render
     ;; (locked :replace-frame-state policy), and — given a `:render-tree-fn`
     ;; — runs the post-hydrate VERIFY step: it hashes the client render-tree
     ;; and compares it to the server's :rf/render-hash (stashed by the
     ;; framework `:rf/hydrate` handler at [:rf.runtime/ssr :hydration
     ;; :server-hash]), emitting :rf.ssr/hydration-mismatch on disagreement.
     ;; `:render-tree-fn` must return the SAME *resolved* hiccup tree the
     ;; server hashed — the server computed `(rf/render-tree-hash
     ;; ((rf/view :app/root)))`, so we CALL the view fn here, `((rf/view
     ;; :app/root))`, rather than the vector-wrapped component reference
     ;; `[(rf/view :app/root)]` we hand Reagent to mount. EP-0002: the
     ;; hydration target is CARRIED — the same `app-frame` flows to
     ;; `hydrate!` and the root `frame-provider`. It returns the payload it
     ;; applied (nil on a client-only first load with no payload script), so
     ;; we can branch on "was this server-rendered?".
     (let [payload (ssr/hydrate! {:frame          app-frame
                                  :render-tree-fn (fn [] ((rf/view :app/root)))})]
       (when-not payload
         ;; Client-only load (no server render): run the app's own
         ;; bootstrap against the carried frame; the page renders the
         ;; empty-articles fallback.
         (rf/dispatch-sync [:ssr/client-bootstrap] {:frame app-frame})))
     (when (exists? js/document)
       (when-not @react-root
         (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
       ;; Wrap the mount in the carried frame's `frame-provider` so every
       ;; in-tree `dispatch`/`subscribe` resolves to the hydrated frame.
       (rdc/render @react-root
                   [rf/frame-provider-existing {:frame app-frame}
                    [(rf/view :app/root)]]))))

;; The JVM-runnable headless tests for this example live in
;; re-frame.examples-test (implementation/core/test/), keeping this example
;; source pure demonstrative code (the example tree is test-free).
;; They run on the JVM:
;;   - `ssr-example-runs-end-to-end` — the full server flow (per-request
;;     frame → :rf/server-init → managed-HTTP via a canned stub →
;;     render-to-string → render-hash).
;;   - `ssr-example-handle-request-tears-down-per-request-frame` +
;;     `…-tears-down-on-throw` — per-request frame teardown on both the
;;     success and throw paths.
;;   - `ssr-example-client-hydration-*` — the client hydration path against
;;     the framework-owned :rf/hydrate: server-hash stash, matching/divergent
;;     hash verify, and fail-closed on a malformed payload.
