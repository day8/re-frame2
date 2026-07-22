(ns ssr-streaming.core
  "Streaming SSR — a dashboard that refuses to wait on its slowest part.

  The page is four cards. With a live streaming host, the shell and header
  flush first, then each card follows when its own data resolves. Picture
  three slow microservices behind those cards: the browser can paint a usable
  skeleton before the real numbers arrive instead of holding the whole page
  until the slowest one answers. This offline example preserves that wire
  shape as collected data; it does not simulate network timings.

  Six ideas earn their keep here:
   - `ssr/boundary` — one component that says \"this region may arrive
     late.\" That component IS the whole streaming API.
   - It is **cross-host**: the same form defers a region on the server and
     renders it in the browser, so the views are shared verbatim and there
     is no reader-conditional slot where the two runtimes part company.
   - A `:fallback` to show in the meantime — here a skeleton card.
   - Failure isolation — one card throws on purpose, to prove a blown
     boundary stays on its fallback instead of taking the page down with
     it. The final payload names the failed boundaries, so the client
     re-renders that boundary's DECLARED fallback rather than every view
     having to guess from absent state.
   - Per-card state — each chunk's `<script data-rf2-suspense-hydrate>`
     carries that card's app-db delta, so the streamed-in subtree's subs
     have something to read.
   - A final `__rf_payload` chunk carrying the canonical full state. The
     deltas are a speed bet; this is the truth, and it wins any tie.

  One `.cljc` artefact, two runtimes: the `:clj` branch is what a Ring
  server calls per request, the `:cljs` branch is what the page boots once
  the chunks land.

  See the [SSR guide — Streaming](../../../../docs/ssr/concepts.md#streaming-rfsuspense-boundary)."
  (:require [re-frame.core :as rf]
            [re-frame.schemas]
            [re-frame.ssr :as ssr]
            #?(:cljs [reagent.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])))

;; ============================================================================
;; SCHEMA
;; ============================================================================
;;
;; The contract for the `:cards` slice, kept as a plain value. Why a plain
;; def and not a registration at the top level? Schemas are frame-local,
;; and this example runs two frames: a fresh per-request frame on the
;; server (`handle-request`) and a fixed app-frame on the client (`run`).
;; Both need this same contract, so each registers it explicitly with a
;; `{:frame …}` override. Holding the schema as a value lets the namespace
;; load under no frame at all — ns-load stays free of side effects.
;;
;; The `[:maybe …]` matters: the `:cards` slice is nil until the server
;; seeds it (`:rf/server-init`) or the client hydrates it. A bare
;; `[:map-of …]` would reject that perfectly legal nil and roll the commit
;; back on you.
;; See the [schemas guide](../../../../docs/core/how-to/validate-with-schemas.md).
(def CardsSchema
  [:maybe [:map-of :keyword [:map [:title :string] [:value [:maybe :int]]]]])

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :rf/server-init
  {:doc       "Per-request server-side init — this is where a card's data
               comes from. A real app would kick off :rf.http/managed
               fetches here, one per card. We cheat and seed three values
               synchronously instead, so the whole wire shape resolves in a
               single browser request and you can read it in one go."
   :platforms #{:server}}
  (fn [_ _]
    {:db {:cards
          {:revenue   {:title "Revenue (last 7 days)"     :value 42375}
           :signups   {:title "New signups (last 7 days)" :value 318}
           :latency   {:title "P50 latency (ms)"          :value 24}}}}))

;; ============================================================================
;; SUBSCRIPTIONS / VIEWS
;; ============================================================================

(rf/reg-sub :cards/slice (fn [db _] (:cards db)))
(rf/reg-sub :card/by-id   (fn [db [_ id]] (get-in db [:cards id])))

(rf/reg-view ^{:rf/id :dashboard/card-skeleton} card-skeleton [card-id]
  [:div.card.skeleton
   [:h3 (str "Loading " (name card-id) " …")]
   [:p.value "—"]])

(rf/reg-view ^{:rf/id :dashboard/card} card-view [card-id]
  ;; Reads its own slice of app-db and nothing else, which is what lets the
  ;; same view render correctly in both runtimes: on the server inside a
  ;; continuation, where `:rf/server-init` has already seeded the data, and
  ;; in the browser against whatever the streamed delta or the final payload
  ;; put there.
  ;;
  ;; No nil branch. This view renders a card; showing a loading state is
  ;; the BOUNDARY's job, and `card-slot` declares that fallback once. A
  ;; card whose boundary failed never reaches this view on the client —
  ;; the boundary short-circuits to the skeleton it declared.
  (let [card @(subscribe [:card/by-id card-id])]
    [:div.card
     [:h3 (:title card)]
     [:p.value (str (:value card))]]))

(rf/reg-view ^{:rf/id :dashboard/throwing-card} throwing-card []
  ;; The card that fails on purpose, standing in for that one flaky
  ;; third-party metric service every dashboard seems to have. When this
  ;; view throws, the streaming runtime catches it inside this one
  ;; boundary, emits a :rf.ssr/suspense-boundary-failed trace, and ships
  ;; the fallback HTML in the chunk's slot (marked data-rf2-suspense-failed)
  ;; with no hydrate delta. The other three cards stream on as if nothing
  ;; happened. A thrown render takes down exactly one boundary, never the
  ;; page. See the
  ;; [SSR guide — Streaming](../../../../docs/ssr/concepts.md#streaming-rfsuspense-boundary).
  (throw (ex-info "flaky third-party metric service" {})))

(defn- card-slot
  "One card's slot in the dashboard — and, since the boundary became a
  component, ONE program rather than two.

  `ssr/boundary` wraps a region that's allowed to arrive late. On the
  server the `:fallback` ships inline in the shell right now, and `body`
  renders separately and streams in as its own chunk once it resolves. In
  the browser the same form renders `body` — or, when this boundary is one
  the server reported as FAILED, the `:fallback` declared right here. The
  `:id` is how the client pairs an arriving chunk with its placeholder:
  you pick it, and it has to be unique on the page.

  This was a reader conditional until recently — `:rf/suspense-boundary`
  on the server, a bare card on the client — and it was the one place this
  example was genuinely two programs. It had to be, while the boundary was
  a hiccup keyword: a keyword head is an HTML element on every client
  substrate, so leaving the marker in a browser render tree sails straight
  through the DOM tag grammar and paints a phantom `<suspense-boundary>`
  with `:id` and `:fallback` mangled into attributes. And the marker could
  not simply be taught client semantics either — stock Reagent's element
  dispatch is an external dependency, and UIx views are `defui` /
  `$` forms where a hiccup keyword head cannot occur at all.

  A callable component has none of those problems, and removing the
  conditional removed something else with it: `card-view` no longer needs
  a nil branch duplicating the skeleton to keep the client's render
  agreeing with the DOM the stream painted. The boundary that declared the
  fallback is the one that re-renders it.

  The related trap is one level down and more tempting: a bare
  `[:dashboard/card :revenue]` head is an **HTML element**, never a view.
  The runtime does not intercept the keyword case to dispatch through the
  view registry (Conventions §Render-tree shape vs runtime lookup), so that
  head paints `<card>revenue</card>` — tag from the keyword's name, argument
  as a text node. That rule holds on EVERY host, server included: the JVM
  SSR emitter is a pure hiccup → HTML function that resolves no ids. Render
  trees reference views by **Var** (`card-view`, which `reg-view` defs for
  you) or by `(rf/view :id)` lookup."
  [boundary-id card-id body]
  [ssr/boundary {:id boundary-id :fallback [card-skeleton card-id]}
   body])

(rf/reg-view ^{:rf/id :dashboard/root} root-view []
  [:main.dashboard
   [:header
    [:h1 "Dashboard"]
    [:p "Streamed SSR demo — shell renders first, cards stream in."]]
   [:section.cards
    (card-slot :card.revenue :revenue [card-view :revenue])
    (card-slot :card.signups :signups [card-view :signups])
    (card-slot :card.latency :latency [card-view :latency])
    ;; The failure-path card — same slot, same shape as the three above. It
    ;; differs only in its deferred body, and only on the wire: its chunk
    ;; carries the fallback HTML stamped `data-rf2-suspense-failed="1"` and
    ;; no hydrate-delta script. A failure is just another way for a boundary
    ;; to resolve. Client-side that missing delta is the whole story — no
    ;; `:flaky` entry ever reaches app-db, so `card-view` renders its
    ;; skeleton, matching the fallback the failed chunk left in the DOM.
    (card-slot :card.flaky :flaky [throwing-card])]
   [:footer
    [:p "Each card above is a `:rf/suspense-boundary`."]]])

;; ============================================================================
;; SERVER ENTRY POINT (.clj branch — what a Ring server calls)
;; ============================================================================

#?(:clj
   (defn handle-request
     "One request in, one chunked response out — what a host adapter
     (re-frame.ssr.ring/stream-handler) calls. In production the adapter
     owns the Ring wiring and the writer thread that flushes chunks as
     they're ready. We do the steps by hand here so the example runs on a
     bare JVM with no live server in the loop, and so you can see the order
     the bytes go out in.

     Returns a map of the rendered shell, the per-card chunks in order, and
     the final payload — the same byte sequence the streaming adapter would
     emit, just collected instead of flushed."
     [_request]
     (let [fid (keyword "rf.frame" (str (gensym "")))
           ;; A fresh frame per request, identified by a gensym so concurrent
           ;; requests never collide. Order matters here: register the schema
           ;; against this frame BEFORE creating it. `make-frame` fires the
           ;; `:initial-events` pipeline run — and therefore `:rf/server-init` and
           ;; its `:cards` commit — synchronously, so the contract has to be
           ;; in place by then or the commit validates against nothing.
           _   (rf/reg-app-schema [:cards] {:frame fid} CardsSchema)
           _   (rf/make-frame {:id fid :doc "ssr-streaming-example frame"
                               :platform :server
                               :initial-events [[:rf/server-init]]})
           hiccup (rf/with-frame fid ((rf/view :dashboard/root)))
           {:keys [shell-html continuations]}
           (rf/with-frame fid (ssr/streaming-render-shell hiccup))
           ;; Render the shell handed us one continuation per boundary —
           ;; the slow subtrees it deferred. Drain them in order, collecting
           ;; each resolved subtree's HTML plus its hydration delta. This is
           ;; the loop the writer thread would run, flushing each chunk the
           ;; moment it's ready.
           resolved-chunks
           (mapv (fn [entry]
                   (let [{:keys [id html delta failed?]}
                         (rf/with-frame fid
                           (ssr/streaming-render-continuation fid entry))]
                     {:id    id
                      :template (if failed?
                                  (ssr/streaming-failed-template id html)
                                  (ssr/streaming-resolved-template id html))
                      :delta-script (when (and (not failed?) (some? delta))
                                      (ssr/streaming-hydrate-delta-script
                                        id (pr-str delta)))
                      :failed? failed?}))
                 continuations)
           ;; Which boundaries blew up. The server has always known this
           ;; per continuation; carrying it into the final payload is what
           ;; lets the client's boundary re-render its declared fallback
           ;; instead of every view inferring failure from missing state.
           failed-boundaries (into #{} (comp (filter :failed?) (map :id))
                                   resolved-chunks)
           render-hash (rf/with-frame fid (ssr/render-tree-hash hiccup))
           ;; The final payload: the canonical, whole app-db. This is the
           ;; load-bearing idea of the example. Those per-card deltas are a
           ;; speed bet — they exist only to paint each region early. This
           ;; payload is the truth, and if a delta ever disagrees with it,
           ;; the payload wins. You get streaming's latency with a single
           ;; authoritative hydrate's correctness.
           ;;
           ;; `:payload` decides what crosses the wire, and it's fail-closed
           ;; — nothing leaves unless you say so. This demo's app-db is safe
           ;; to ship whole (every key the dashboard fills is meant for the
           ;; client), so we opt in with `:rf.ssr.payload/whole-app-db`. Real
           ;; apps usually pass an explicit allowlist of top-level keys
           ;; instead.
           final-payload (rf/with-frame fid
                           (ssr/streaming-build-final-payload
                             fid render-hash
                             ;; No `:version` — the builder sources `:rf/version`
                             ;; from the SSR artefact's compiled-in
                             ;; pattern-protocol constant, so both wire ends agree
                             ;; with no hand-pinned literal.
                             {:payload :rf.ssr.payload/whole-app-db
                              :failed-boundaries failed-boundaries}))
           ;; Strip the payload's `:rf/frame-id` before it goes over the
           ;; wire. The two sides don't share a frame id: the server renders
           ;; under this per-request gensym frame (`fid`), the client hydrates
           ;; a fixed `app-frame` (below). When `ssr/hydrate!` sees a frame-id on the
           ;; wire, it checks it against the client's explicit `:frame` and
           ;; fails closed if they differ — which a gensym always would. Send
           ;; no frame-id and the client's explicit target simply stands. (If
           ;; you do want one on the wire, share a stable id both sides agree
           ;; on, not a gensym.) See the
           ;; [SSR guide — hydrate, then verify](../../../../docs/ssr/concepts.md#the-client-side-hydrate-then-verify).
           final-payload (dissoc final-payload :rf/frame-id)
           _ (rf/destroy-frame! fid)]
       {:shell shell-html
        :resolved-chunks resolved-chunks
        :final-payload final-payload
        :failed-boundaries failed-boundaries
        :render-hash render-hash})))

;; ============================================================================
;; CLIENT ENTRY POINT (.cljs branch — browser hydration)
;; ============================================================================
;;
;; What the browser sees, in three acts:
;;
;;  1. The shell arrives first. Its slow regions are inline
;;     `<template data-rf2-suspense-fallback>` placeholders. A `<template>`'s
;;     content is inert by the HTML spec — its `.content` is a detached
;;     DocumentFragment that never paints — so the skeletons stay invisible
;;     on the raw bytes. They only appear once step 2's `streaming-install!`
;;     materialises each inert `<template>` into a live
;;     `<rf-suspense data-rf2-suspense-mount>`. Meanwhile the
;;     `<script src="main.js">` at the foot of `<body>` starts downloading.
;;  2. The resolved cards stream in (Transfer-Encoding: chunked), each a
;;     `<template data-rf2-suspense-resolved=…>` plus a matching
;;     `<script data-rf2-suspense-hydrate=…>`. The client streaming runtime
;;     (`ssr/streaming-install!`) does two things per chunk: it brings the
;;     inert fallback `<template>`s to life as `<rf-suspense>` mounts (now
;;     the skeletons paint), then swaps each mount's content for the resolved
;;     subtree in place and merges that subtree's delta into app-db. `run`
;;     installs it before the first render, so it catches chunks that already
;;     streamed in and any still on the way.
;;  3. The final `<script id="__rf_payload">` is the canonical full state, and
;;     its arrival means the stream is done. The runtime FINALISES: it
;;     processes any chunk still pending, consumes or quarantines the last
;;     deltas, unwraps every `<rf-suspense>` mount it created — leaving the
;;     DOM as the plain markup the author's tree describes — disconnects, and
;;     calls `:on-ready`. Only then does `run` hydrate: `ssr/hydrate!`
;;     dispatches `:rf/hydrate`, replacing the whole client frame-state in one
;;     step (app-db plus its serialisable runtime-db slice, which carries
;;     which boundaries failed), and `hydrate-root` adopts the painted DOM.
;;     The deltas got each card on screen early; this last step makes the
;;     whole thing correct.
;;
;;     The protocol is therefore: progressive PRE-HYDRATION PAINT, then ONE
;;     ordinary whole-root hydration. Templates, `<rf-suspense>` mounts and
;;     delta scripts are transport — never part of the application tree — and
;;     none of them is in the DOM by the time React looks at it.
;;
;; See the [SSR guide — Streaming](../../../../docs/ssr/concepts.md#streaming-ssrboundary)
;; and [hydrate, then verify](../../../../docs/ssr/concepts.md#the-client-side-hydrate-then-verify).
;;
;; One honest caveat: this example boots from a hand-written `index.html`
;; that plays the part of the streaming server. No Clojure server runs in
;; the loop. It bakes the WHOLE wire byte sequence at once — the shell with
;; a `<template data-rf2-suspense-fallback>` per boundary, then every
;; resolved chunk, the `data-rf2-suspense-failed` chunk for `:card.flaky`,
;; and the final `__rf_payload` — captured verbatim from the streaming
;; emitter (the same bytes `handle-request` above returns). Those bytes are
;; the real streamed shape, so `install!` runs the SAME path a live stream
;; drives: it materialises each fallback into an `<rf-suspense>` mount, swaps
;; in each resolved chunk, records `:card.flaky` as FAILED from its marker,
;; then unwraps the mounts on the final payload and hydrates. The failed
;; boundary keeps its declared skeleton because the client observed the
;; failed chunk, exactly as it would on the wire. The one thing the offline
;; stand-in drops is the network TIMING — every chunk is present at once
;; instead of arriving over the wire. That genuinely time-separated arrival
;; (swap on each chunk, merge its delta, then the final payload) is what the
;; browser acceptance test `re-frame.ssr.streaming-client-dom-cljs-test`
;; drives end to end.

;; The React root lives in an atom and gets created lazily inside `run`,
;; never at ns-load. The rule: loading a namespace must touch no DOM, so
;; sibling example namespaces loaded alongside this one can't race each other
;; to `create-root` the shared `#app`. (Mount-isolation convention — see
;; examples/TESTING.md.)
#?(:cljs (defonce react-root (atom nil)))

;; The client's app-frame id, carried explicitly. The same id flows into the
;; streaming `install!`, into `ssr/hydrate!`, and into the root
;; `frame-provider` — one source of truth for which frame everything targets.
;; This example uses `:rf/default`. It has to be a `:client`-platform frame,
;; or the `:rf.ssr/check-*` compatibility checks that `:rf/hydrate` runs
;; would never fire. Neither payload (static or `handle-request`) carries a
;; `:rf/frame-id`, and that's deliberate: the server rendered under a
;; per-request gensym, the client hydrates this fixed frame, so an absent
;; frame-id is exactly right — the explicit `:frame` says where to land. See
;; [frames](../../../../docs/core/glossary.md#frame-identity-is-carried-not-found).
#?(:cljs (def app-frame :rf/default))

;; Re-render the current view into the RETAINED root. Tagged `^:dev/after-load`
;; so shadow-cljs re-runs it after each hot reload — edited views re-render into
;; the same root and the already-hydrated frame. It never creates a root and
;; never re-hydrates: the root is established once in `run` (via `hydrate-root`
;; when the server painted the page, `create-root` otherwise), and HYDRATE plus
;; the streaming install happen once there too, so a reload can't re-seed over
;; the interactive state.
#?(:cljs
   (defn ^:dev/after-load render! []
     (when-let [root @react-root]
       ;; Wrap the render in `frame-provider` so every `dispatch` and
       ;; `subscribe` inside the tree resolves to the frame we hydrated.
       (rdc/render root
                   [rf/frame-provider {:frame app-frame}
                    [(rf/view :dashboard/root)]]))))

#?(:cljs
   (defn run []
     ;; `init!` installs the Reagent adapter and nothing more — it creates no
     ;; frame. The app stands up its own, explicitly, just below.
     (rf/init! reagent-adapter/adapter)
     ;; Stand up the client app-frame BEFORE we install streaming or hydrate
     ;; into it — both need a frame to land in. `make-frame` is a no-op the
     ;; second time round, so hot-reload just works. `:platform :client` is
     ;; what makes the hydrate compatibility checks fire.
     (rf/make-frame {:id app-frame :doc      "ssr-streaming-example client app-frame"
                     :platform :client})
     ;; The mirror image of the server registration in `handle-request`:
     ;; same schema, this frame. With it in place, the `:cards` commits on the
     ;; client — both the streamed deltas and the final hydrate — validate
     ;; against the same contract the server used.
     (rf/reg-app-schema [:cards] {:frame app-frame} CardsSchema)
     ;; Turn on the streaming runtime BEFORE anything renders, so it catches
     ;; resolved-subtree chunks as they arrive (and sweeps up any that already
     ;; landed). For each one it swaps the fallback for the real subtree and
     ;; merges that subtree's delta into the frame.
     ;;
     ;; `:on-ready` is the hydration trigger, and this is the whole shape of a
     ;; streaming bootstrap. It fires ONCE, when the final payload has landed,
     ;; every delta is consumed, and every `<rf-suspense>` mount the runtime
     ;; created has been unwrapped. Those mounts are transport — the live swap
     ;; targets the runtime materialises from the shell's inert `<template>`
     ;; fallbacks — and no render tree on any host can express them. Hydrating
     ;; while they are still in the DOM is a structural mismatch at every
     ;; boundary, so React discards the streamed page and re-renders it: you
     ;; pay for streaming and get none of it.
     ;;
     ;; Note what is NOT here: no polling for `__rf_payload`, no timer, and no
     ;; `create-root` fallback taken merely because the payload hasn't arrived
     ;; yet. On a live stream it hasn't arrived yet for most of the page's
     ;; life, and mounting a fresh root in the meantime throws away the very
     ;; markup the server streamed. The readiness callback removes the race by
     ;; removing the guess.
     (ssr/streaming-install!
       {:frame    app-frame
        :on-ready (fn [_outcomes]
                    ;; Reconcile against the canonical payload first: read
                    ;; `__rf_payload` and dispatch `:rf/hydrate` into the
                    ;; explicit `:frame`. This is the truth the streamed
                    ;; deltas defer to. `hydrate!` seeds STATE only — it never
                    ;; touches the DOM — and hands back the payload it applied.
                    ;; Hydrate BEFORE the render so that render runs against
                    ;; fully seeded app-db, not a half-filled one.
                    ;;
                    ;; (We leave out `:render-tree-fn` because this static demo's
                    ;; shell carries a placeholder render-hash, so mismatch
                    ;; verification would warn every time. A real streaming
                    ;; server stamps the genuine hash and passes
                    ;; `:render-tree-fn` to switch mismatch detection on.)
                    (let [payload (ssr/hydrate! {:frame app-frame})
                          el      (and (exists? js/document)
                                       (js/document.getElementById "app"))
                          tree    [rf/frame-provider {:frame app-frame}
                                   [(rf/view :dashboard/root)]]]
                      (when el
                        (if payload
                          ;; Server-rendered: ADOPT the painted DOM.
                          ;; `hydrate-root` reconciles React against that
                          ;; markup — same nodes, listeners attached, no
                          ;; re-paint — and returns the retained root.
                          (reset! react-root (rdc/hydrate-root el tree))
                          ;; No payload means the server never rendered this
                          ;; page — a plain first load with nothing to adopt.
                          ;; Mount a fresh root.
                          (do
                            (reset! react-root (rdc/create-root el))
                            (rdc/render @react-root tree))))))})))

;; The JVM headless test that walks the server stream end to end (shell →
;; per-card resolved chunks → final payload) lives over in
;; re-frame.examples-test (implementation/core/test/), as the
;; `ssr-streaming-example-runs-end-to-end` deftest. The example tree itself
;; stays test-free.
