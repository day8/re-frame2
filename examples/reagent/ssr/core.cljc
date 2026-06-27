(ns ssr.core
  "Server-side rendering: one app that runs twice. The server renders a
   'recent articles' page to HTML; the client hydrates it and stays
   interactive. See the SSR guide: ../../../docs/ssr/concepts.md.

   This is .cljc so the same code runs server-side (JVM, :clj branches) and
   client-side (browser, :cljs branches). The events, subscriptions, and
   views are shared — there is no second server-flavoured copy of the app.

   What this example shows:
   - A per-request frame on the server (reg-frame + with-frame).
   - :rf/server-init, dispatched when the frame is created.
   - A client-only fx gated with :platforms #{:client} — the server render
     skips it.
   - The pure hiccup -> HTML emitter (rf/render-to-string).
   - The hydration payload the server ships for the client to adopt.
   - Per-request frame teardown in a `finally` (rf/destroy-frame!).
   - Framework-owned hydration: the client boots via `ssr/hydrate!`, which
     dispatches the reserved `:rf/hydrate` event. The app writes no handler
     for it. `:rf/hydrate` replaces the client frame-state (it does not
     merge) — the server is authoritative.
   - A structural render hash. The client recomputes it after its first
     render and compares against the server's; a disagreement emits
     :rf.ssr/hydration-mismatch. See ../../../docs/ssr/glossary.md#hydration-mismatch.

   To run it: the hand-written `index.html` next to this file ships with
   pre-rendered HTML inside `<div id='app'>` and a baked
   `<script id='__rf_payload'>` — the same shape `handle-request` below
   emits. The browser-side `run` calls `ssr/hydrate!` (read payload ->
   dispatch `:rf/hydrate` -> verify) and renders against the seeded
   frame-state."
  (:require [re-frame.core :as rf]
            ;; Loading this ns registers the schema hooks so
            ;; rf/reg-app-schema resolves at the call sites below.
            [re-frame.schemas]
            ;; Registers the `:rf.http/managed` fx family. This example
            ;; dispatches `:rf.http/managed` for the article fetch and uses
            ;; per-frame `:fx-overrides` to redirect it to a canned-success
            ;; stub during render. Without the require, the override would
            ;; target an unregistered fx-id.
            [re-frame.http.managed]
            ;; Registers the canned-stub fx ids
            ;; (`:rf.http/managed-canned-success`,
            ;; `:rf.http/managed-canned-failure`). The example has no real
            ;; backend, so it drives these stubs via :fx-overrides; this
            ;; require is the explicit opt-in to the test affordance.
            [re-frame.http.test-support]
            ;; Registers the `:rf.server/*` server-only fxs, the `:rf/hydrate`
            ;; event, the default error projector, and the SSR render/hash
            ;; hooks the core re-exports below rely on.
            [re-frame.ssr :as ssr]
            ;; The EDN-aware `<script>`-body escaper, used server-side only.
            ;; The `:clj` `handle-request` drops the `pr-str`'d payload inside
            ;; a `<script type="application/edn">` body; a server-provided
            ;; string containing `</script>` would otherwise close the
            ;; envelope. The escaper rewrites `<` to its reader escape only
            ;; inside EDN string literals, so the payload still round-trips.
            #?(:clj [re-frame.ssr.html-helpers :as html])
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; SCHEMA
;; ============================================================================
;;
;; Hold the schema as a plain value. SSR has two frames — the per-request
;; server frame (a gensym, in `handle-request`) and the fixed client frame
;; (`app-frame`, in `run`) — and the same contract applies to both. Each entry
;; point registers it against its own frame with the `{:frame …}` override.
;; `reg-app-schema` is frame-local, so it needs an explicit `:frame`; holding
;; the schema as a `def` keeps ns-load free of any ambient frame.
;;
;; `[:maybe …]` because the slice is nil until `:articles/loaded` commits. The
;; server's `:rf/server-init` commits an articles-free db first (it only kicks
;; off the fetch), and the client frame starts empty before hydration. A bare
;; `[:vector …]` would reject that legitimate intermediate nil and roll the
;; commit back. See ../../../docs/guide/glossary.md#schema.
(def ArticlesSchema
  [:maybe [:vector [:map
                    [:id    :string]
                    [:title :string]
                    [:body  :string]]]])

;; ============================================================================
;; FX
;; ============================================================================
;;
;; HTTP requests go via the framework's `:rf.http/managed` fx. The headless
;; test redirects it through the `:fx-overrides` seam to a per-frame
;; canned-success stub, so the JVM render exercises the full event cascade
;; without real network traffic.

(rf/reg-fx :auth.session/store
  {:doc       "Persist a session token in localStorage."
   :platforms #{:client}}              ;; client-only — the server render skips it
  (fn fx-auth-session-store [_m {:keys [token]}]
    #?(:cljs (when-let [ls (.-localStorage js/globalThis)]
               (.setItem ls "auth/token" token)))))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :rf/server-init
  {:doc       "Per-request server-side initialisation. Reads the request via
               the :rf.server/request coeffect and kicks off setup. Server
               only. See ../../../docs/ssr/concepts.md#reading-the-request."
   :platforms #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn handler-rf-server-init [{:keys [db rf.server/request]} _]
    ;; `request` is the host-supplied HTTP request map (Ring shape under the
    ;; bundled adapter); read URL/headers/cookies from it. It arrives via the
    ;; `:rf.server/request` coeffect, declared above, not as an event arg.
    ;;
    ;; This handler leaves db unchanged and just kicks off the article fetch.
    ;; A router-driven app would store the matched route here; this
    ;; article-only render has no route to track.
    {:db db
     :fx [[:rf.http/managed
           {:request    {:method :get :url "/api/articles"}
            :decode     :json
            :on-success [:articles/loaded]}]]}))

;; The `:on-success` target for the article fetch. It takes the decoded reply
;; value and returns the next app-db; the runtime commits it. The same handler
;; runs on the server (per-request frame) and the client (post-hydration) —
;; nothing here is platform-specific.
(rf/reg-event :articles/loaded
  (fn handler-articles-loaded [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc db :articles value)}))

;; Hydration is framework-owned. `:rf/hydrate` is a reserved `:rf/*` event that
;; `re-frame.ssr` registers; the app supplies no handler for it. The framework
;; handler installs the whole client frame-state in one atomic step, replacing
;; whatever the client pre-seeded — the server is authoritative. The payload's
;; app-db replaces the app-db partition; its runtime-db slice (machine
;; snapshots, route slice) replaces the serializable runtime-db projection. The
;; handler validates the payload fail-closed: a non-map payload, or a
;; present-but-non-map slice, leaves the frame-state untouched. It also stashes
;; the server's render hash for the verify step after the first render. (This
;; example has no machines and doesn't hydrate the route, so its runtime-db
;; slice is empty — but the shape is the one a richer app uses.) The client
;; `run` below drives all of this through `ssr/hydrate!`; see the CLIENT ENTRY
;; POINT section. Why replace and not merge:
;; ../../../docs/ssr/concepts.md#the-client-side-hydrate-then-verify

;; ============================================================================
;; CLIENT-SIDE INTERACTIVITY EVENTS
;; ============================================================================
;;
;; A small interactive surface that proves hydration left the client fully
;; reactive: clicking "Hide bodies" toggles the body paragraphs in and out
;; without a full re-render. This slice has no server counterpart, so it is not
;; in the hydration payload and starts at its default value on the client.

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

;; reg-view auto-defs the symbol and registers it under (keyword *ns* sym).
;; `^{:rf/id ...}` overrides that id so the :pages/articles / :app/root ids the
;; callers below use match the registrations.
;;
;; The `subscribe` injected by `reg-view` resolves to the frame-bound subscribe
;; fn at runtime. The same view code runs on both sides: on the JVM render path,
;; deref of a subscription yields its current value; on the client, deref tracks
;; the reaction so re-renders fire on app-db changes. That parity is what lets
;; one view run twice. See ../../../docs/guide/glossary.md#view.
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
;; The server flow, start to finish (../../../docs/ssr/concepts.md#a-request-start-to-finish):
;;   1. Accept the request.
;;   2. set-request! populates the per-frame slot the :rf.server/request
;;      coeffect reads from.
;;   3. reg-frame; its :initial-events step dispatches :rf/server-init, which
;;      reads the request via the coeffect.
;;   4. The runtime drains: HTTP fetches resolve and app-db settles.
;;   5. Render to a string via the pure hiccup -> HTML emitter.
;;   6. Serialise the state and ship it in the HTML payload.
;;   7. destroy-frame! in a `finally`. This is load-bearing for memory hygiene
;;      on a long-running server: it drops the frame record and fires the
;;      `:ssr/on-frame-destroyed` hook, which releases the per-frame request
;;      slot, response accumulator, and error-trace buffer. The `finally` runs
;;      on both the success and throw paths, so no partially-built frame leaks
;;      either.

#?(:clj
   (defn handle-request [request]
     (let [fid (keyword "rf.frame" (str (gensym "f")))
           _   (ssr/set-request! fid request)
           ;; Register the app schema against this per-request server frame,
           ;; under the gensym `fid`, before creating the frame. `reg-frame`
           ;; runs its `:initial-events` cascade synchronously, so the schema
           ;; must be in place first to validate the server-side `:articles`
           ;; commit that `:rf/server-init` kicks off.
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
                 ;; The payload omits `:rf/frame-id`. The server renders under
                 ;; a per-request gensym frame (`f`); the client hydrates its
                 ;; own fixed app-frame (`app-frame`, below). A payload frame-id
                 ;; is validation evidence, not a hydration target: `ssr/hydrate!`
                 ;; checks any present `:rf/frame-id` against the client's
                 ;; explicit `:frame` and raises
                 ;; `:rf.error/hydration-frame-id-mismatch` if they disagree. An
                 ;; absent id is the no-conflict shape both this output and the
                 ;; static `index.html` use. To carry a frame-id, a deployment
                 ;; stamps a stable id both sides agree on — not a per-request
                 ;; gensym.
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
                   ;; `</script>`-escaper, so a server-provided string carrying
                   ;; `</script>` (round-tripped through app-db) can't close the
                   ;; envelope. The encoder rewrites a less-than char to its
                   ;; unicode reader escape only inside EDN string literals, so
                   ;; the payload still round-trips through the client's
                   ;; `cljs.reader/read-string` unchanged.
                   "<script id='__rf_payload' type='application/edn'>"
                   (html/escape-edn-script-body (pr-str payload))
                   "</script>"
                   "<script src='/main.js'></script>"
                   "</body></html>")}))
         ;; Tear the per-request frame down on every exit path. The
         ;; `:ssr/on-frame-destroyed` hook (fired by destroy-frame!) clears the
         ;; request slot and the SSR side-channel atoms, so no explicit
         ;; `clear-request!` is needed here.
         (finally
           (rf/destroy-frame! fid))))))

;; ============================================================================
;; CLIENT ENTRY POINT
;; ============================================================================
;;
;; The client flow is `ssr/hydrate!` — the framework's client-boot helper, the
;; counterpart of the server render. One call does the three steps, in order:
;;   1. READ    — the embedded `__rf_payload` `<script>` via the pinned id.
;;                A malformed payload fails closed (no app-db replacement); a
;;                missing payload is the client-only first-load shape.
;;   2. HYDRATE — dispatch-sync `[:rf/hydrate payload]` before the first render.
;;                The framework handler replaces the frame-state with the server
;;                slice and stashes the server's render hash for the verify step.
;;   3. VERIFY  — after the first render, hash the `:render-tree-fn` result and
;;                compare it to the server hash; a disagreement emits
;;                :rf.ssr/hydration-mismatch.
;; `hydrate!` returns the payload it applied (nil on a client-only load), so
;; `run` can branch on "was this server-rendered?" without re-reading the DOM.
;; Going through the helper pins the fail-closed validation, the hash stash, and
;; the verify step together in the right order.
;; See ../../../docs/ssr/concepts.md#the-client-side-hydrate-then-verify.

;; User events live under an app-chosen namespace, never the reserved `:rf/*`
;; root. `:rf/hydrate` is framework-owned; `:rf/server-init` is the documented
;; per-request server-init pattern the app supplies a body for. This
;; client-only-load bootstrap is a fresh user event, so it gets the app's own
;; `:ssr/` namespace.
(rf/reg-event :ssr/client-bootstrap
  {:doc "Client-side init that runs even if the server didn't render this page."}
  (fn [{:keys [db]} _] {:db db}))

;; The React root is held in an atom and created lazily inside `run`, not at
;; ns-load. ns-load must produce no DOM side effects, so co-required example
;; namespaces don't race `create-root` onto the shared `#app`.
#?(:cljs (defonce react-root (atom nil)))

;; The client app-frame id. The app names its frame explicitly and threads this
;; id through both `ssr/hydrate!` (the seed target) and the root
;; `frame-provider` (where every in-tree `dispatch`/`subscribe` resolves). It
;; must be a `:client`-platform frame so the compatibility-check fxs the
;; `:rf/hydrate` handler dispatches actually fire — a `:server` frame skips
;; them. The server payload carries no `:rf/frame-id`, so this explicit `:frame`
;; is the hydration target (see the `handle-request` note).
(def app-frame :rf/default)

#?(:cljs
   (defn run []
     ;; Boot the runtime against the Reagent substrate. Idempotent: the first
     ;; call installs the adapter, hot reloads are no-ops. `init!` installs only
     ;; the adapter; the app creates its frame itself, in the next step.
     ;;
     ;; Pass the adapter spec map directly — each adapter ns exports an
     ;; `adapter` var the consumer requires and passes here.
     (rf/init! reagent-adapter/adapter)
     ;; Create the client app-frame before hydrating into it. `reg-frame` is a
     ;; no-op on re-registration, so hot-reload just works. `:platform :client`
     ;; makes the hydrate compatibility-check fxs fire.
     (rf/reg-frame app-frame {:doc      "ssr-example client app-frame"
                              :platform :client})
     ;; Register the app schema against the fixed client frame, so the hydrated
     ;; `:articles` commit and every post-hydration interactive commit validate
     ;; on the client too — the counterpart of the per-request registration in
     ;; `handle-request`. `reg-app-schema` is no-op-safe on hot-reload.
     (rf/reg-app-schema [:articles] {:schema ArticlesSchema :frame app-frame})
     ;; Drive READ + HYDRATE + VERIFY through `ssr/hydrate!`, against the same
     ;; `app-frame` the mount below uses. `:render-tree-fn` must return the same
     ;; resolved hiccup tree the server hashed. The server hashed
     ;; `((rf/view :app/root))`, so VERIFY calls the view fn the same way —
     ;; `((rf/view :app/root))` — not the vector form `[(rf/view :app/root)]`
     ;; that Reagent mounts. `hydrate!` returns the payload it applied (nil on a
     ;; client-only load).
     (let [payload (ssr/hydrate! {:frame          app-frame
                                  :render-tree-fn (fn [] ((rf/view :app/root)))})]
       (when-not payload
         ;; Client-only load (no server render): run the app's own bootstrap
         ;; against the app-frame; the page renders the empty-articles fallback.
         (rf/dispatch-sync [:ssr/client-bootstrap] {:frame app-frame})))
     (when (exists? js/document)
       (when-not @react-root
         (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
       ;; Mount under the app-frame's `frame-provider` so every in-tree
       ;; `dispatch`/`subscribe` resolves to the hydrated frame.
       (rdc/render @react-root
                   [rf/frame-provider {:frame app-frame}
                    [(rf/view :app/root)]]))))

;; The headless tests for this example live in re-frame.examples-test
;; (implementation/core/test/), so this source stays pure demonstration (the
;; example tree is test-free). They run on the JVM:
;;   - `ssr-example-runs-end-to-end` — the full server flow: per-request frame,
;;     :rf/server-init, the article fetch via a canned stub, render-to-string,
;;     render-hash.
;;   - `ssr-example-handle-request-tears-down-per-request-frame` and
;;     `…-tears-down-on-throw` — per-request frame teardown on the success and
;;     throw paths.
;;   - `ssr-example-client-hydration-*` — the client hydration path: server-hash
;;     stash, matching/divergent hash verify, and fail-closed on a malformed
;;     payload.
