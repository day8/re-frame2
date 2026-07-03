(ns ssr-streaming.core
  "Streaming SSR — a dashboard that refuses to wait on its slowest part.

  The page is four cards. The shell and header render instantly on the
  server, then each card streams in the moment its own data resolves.
  Picture three slow microservices behind those cards: the browser paints
  a usable skeleton in ~50ms, and the real numbers trickle in over ~300ms
  each, instead of the whole page sitting blank until the slowest one
  answers.

  Five ideas earn their keep here:
   - `:rf/suspense-boundary` — one hiccup marker that says \"this region
     may arrive late.\" That marker IS the whole streaming API.
   - A `:fallback` to show in the meantime — here a skeleton card.
   - Failure isolation — one card throws on purpose, to prove a blown
     boundary stays on its fallback instead of taking the page down with
     it.
   - Per-card state — each chunk's `<script data-rf2-suspense-hydrate>`
     carries that card's app-db delta, so the streamed-in subtree's subs
     have something to read.
   - A final `__rf_payload` chunk carrying the canonical full state. The
     deltas are a speed bet; this is the truth, and it wins any tie.

  One `.cljc` artefact, two runtimes: the `:clj` branch is what a Ring
  server calls per request, the `:cljs` branch is what the page boots once
  the chunks land.

  See the [SSR guide — Streaming](../../../docs/ssr/concepts.md#streaming-rfsuspense-boundary)."
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
;; See the [schemas guide](../../../docs/core/how-to/validate-with-schemas.md).
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

(rf/reg-view ^{:rf/id :dashboard/card} card-view [card-id]
  (let [card @(subscribe [:card/by-id card-id])]
    [:div.card
     [:h3 (:title card)]
     [:p.value (str (:value card))]]))

(rf/reg-view ^{:rf/id :dashboard/card-skeleton} card-skeleton [card-id]
  [:div.card.skeleton
   [:h3 (str "Loading " (name card-id) " …")]
   [:p.value "—"]])

(rf/reg-view ^{:rf/id :dashboard/throwing-card} throwing-card []
  ;; The card that fails on purpose, standing in for that one flaky
  ;; third-party metric service every dashboard seems to have. When this
  ;; view throws, the streaming runtime catches it inside this one
  ;; boundary, emits a :rf.ssr/suspense-boundary-failed trace, and ships
  ;; the fallback HTML in the chunk's slot (marked data-rf2-suspense-failed)
  ;; with no hydrate delta. The other three cards stream on as if nothing
  ;; happened. A thrown render takes down exactly one boundary, never the
  ;; page. See the
  ;; [SSR guide — Streaming](../../../docs/ssr/concepts.md#streaming-rfsuspense-boundary).
  (throw (ex-info "flaky third-party metric service" {})))

(rf/reg-view ^{:rf/id :dashboard/root} root-view []
  [:main.dashboard
   [:header
    [:h1 "Dashboard"]
    [:p "Streamed SSR demo — shell renders first, cards stream in."]]
   [:section.cards
    ;; Here's the whole trick, four times over. `:rf/suspense-boundary`
    ;; wraps a region that's allowed to arrive late. The `:fallback` ships
    ;; inline in the shell right now; the real child renders separately and
    ;; streams in as its own chunk once it resolves. The `:id` is how the
    ;; client pairs an arriving chunk with its placeholder — you pick it,
    ;; and it has to be unique.
    [:rf/suspense-boundary
     {:id :card.revenue :fallback [:dashboard/card-skeleton :revenue]}
     [:dashboard/card :revenue]]
    [:rf/suspense-boundary
     {:id :card.signups :fallback [:dashboard/card-skeleton :signups]}
     [:dashboard/card :signups]]
    [:rf/suspense-boundary
     {:id :card.latency :fallback [:dashboard/card-skeleton :latency]}
     [:dashboard/card :latency]]
    ;; The failure-path card — same marker, same shape as the three above.
    ;; The only difference shows up on the wire: its chunk carries the
    ;; fallback HTML stamped `data-rf2-suspense-failed="1"` and no
    ;; hydrate-delta script. A failure is just another way for a boundary to
    ;; resolve.
    [:rf/suspense-boundary
     {:id :card.flaky
      :fallback [:dashboard/card-skeleton :flaky]}
     [:dashboard/throwing-card]]]
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
           ;; against this frame BEFORE creating it. `reg-frame` fires the
           ;; `:initial-events` pipeline run — and therefore `:rf/server-init` and
           ;; its `:cards` commit — synchronously, so the contract has to be
           ;; in place by then or the commit validates against nothing.
           _   (rf/reg-app-schema [:cards] {:schema CardsSchema :frame fid})
           _   (rf/reg-frame fid {:doc "ssr-streaming-example frame"
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
                             {:version 1
                              :payload :rf.ssr.payload/whole-app-db}))
           ;; Strip the payload's `:rf/frame-id` before it goes over the
           ;; wire. The two sides don't share a frame id: the server renders
           ;; under this per-request gensym frame (`fid`), the client hydrates
           ;; a fixed `app-frame` (below). When `ssr/hydrate!` sees a frame-id on the
           ;; wire, it checks it against the client's explicit `:frame` and
           ;; fails closed if they differ — which a gensym always would. Send
           ;; no frame-id and the client's explicit target simply stands. (If
           ;; you do want one on the wire, share a stable id both sides agree
           ;; on, not a gensym.) See the
           ;; [SSR guide — hydrate, then verify](../../../docs/ssr/concepts.md#the-client-side-hydrate-then-verify).
           final-payload (dissoc final-payload :rf/frame-id)
           _ (rf/destroy-frame! fid)]
       {:shell shell-html
        :resolved-chunks resolved-chunks
        :final-payload final-payload
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
;;  3. The final `<script id="__rf_payload">` is the canonical full state.
;;     `run` hands it to `ssr/hydrate!`, which dispatches `:rf/hydrate` and
;;     replaces the whole client frame-state in one step — app-db plus its
;;     serialisable runtime-db slice. The runtime auto-disconnects the moment
;;     it sees `__rf_payload`, so no late delta can race that replace. The
;;     deltas got each card on screen early; this last step makes the whole
;;     thing correct.
;;
;; See the [SSR guide — Streaming](../../../docs/ssr/concepts.md#streaming-rfsuspense-boundary)
;; and [hydrate, then verify](../../../docs/ssr/concepts.md#the-client-side-hydrate-then-verify).
;;
;; One honest caveat: this example boots from a hand-written `index.html`
;; that plays the part of the streaming server, with the resolved chunks and
;; final payload baked in. No Clojure server runs in the loop. That works
;; because the streaming runtime is additive — a page whose chunks already
;; resolved hydrates exactly the same way: the install sweep finds nothing
;; left to swap, sees `__rf_payload` already there, and goes straight to the
;; `ssr/hydrate!` reconciliation. The genuinely progressive path (swap on
;; chunk arrival, merge the delta, then the final payload) is what the
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
;; [frames](../../../docs/core/glossary.md#frame-identity-is-carried-not-found).
#?(:cljs (def app-frame :rf/default))

#?(:cljs
   (defn run []
     ;; `init!` installs the Reagent adapter and nothing more — it creates no
     ;; frame. The app stands up its own, explicitly, just below.
     (rf/init! reagent-adapter/adapter)
     ;; Stand up the client app-frame BEFORE we install streaming or hydrate
     ;; into it — both need a frame to land in. `reg-frame` is a no-op the
     ;; second time round, so hot-reload just works. `:platform :client` is
     ;; what makes the hydrate compatibility checks fire.
     (rf/reg-frame app-frame {:doc      "ssr-streaming-example client app-frame"
                              :platform :client})
     ;; The mirror image of the server registration in `handle-request`:
     ;; same schema, this frame. With it in place, the `:cards` commits on the
     ;; client — both the streamed deltas and the final hydrate — validate
     ;; against the same contract the server used.
     (rf/reg-app-schema [:cards] {:schema CardsSchema :frame app-frame})
     ;; Turn on the streaming runtime BEFORE the first render, so it catches
     ;; resolved-subtree chunks as they arrive (and sweeps up any that already
     ;; landed). For each one it swaps the fallback for the real subtree and
     ;; merges that subtree's delta into the frame — then disconnects itself
     ;; the moment `__rf_payload` shows up.
     (ssr/streaming-install! {:frame app-frame})
     ;; Now reconcile against the canonical payload: read `__rf_payload` and
     ;; dispatch `:rf/hydrate` into the explicit `:frame`. This is the truth
     ;; the streamed deltas defer to. Hydrate BEFORE the first render so that
     ;; render runs against fully seeded app-db, not a half-filled one.
     ;; (We leave out `:render-tree-fn` because this static demo's shell
     ;; carries a placeholder render-hash, so mismatch verification would warn
     ;; every time. A real streaming server stamps the genuine hash and passes
     ;; `:render-tree-fn` to switch mismatch detection on.)
     (ssr/hydrate! {:frame app-frame})
     (when (exists? js/document)
       (when-not @react-root
         (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
       ;; Wrap the mount in `frame-provider` so every `dispatch` and
       ;; `subscribe` inside the tree resolves to the frame we just hydrated.
       (rdc/render @react-root
                   [rf/frame-provider {:frame app-frame}
                    [(rf/view :dashboard/root)]]))))

;; The JVM headless test that walks the server stream end to end (shell →
;; per-card resolved chunks → final payload) lives over in
;; re-frame.examples-test (implementation/core/test/), as the
;; `ssr-streaming-example-runs-end-to-end` deftest. The example tree itself
;; stays test-free.
