(ns ssr-streaming.core
  "Streaming SSR: a dashboard with three slow cards. The shell + header
  render immediately on the server. Each card then streams its content
  in as its data resolves, so the browser shows a usable shell within
  ~50ms while the cards trickle in over ~300ms each.

  What this example shows:
   - `:rf/suspense-boundary` — the declarative hiccup marker for a
     streamable subtree.
   - Per-card fallback hiccup — `[:div.card.skeleton …]`.
   - Failure isolation — one card deliberately throws to show a failing
     boundary stays on its fallback instead of 500-ing the page.
   - Per-subtree hydration — each chunk's
     `<script data-rf2-suspense-hydrate>` carries that card's app-db delta.
   - A final `__rf_payload` chunk with the canonical full state.

  The `.cljc` split: the `:clj` branch is what a Ring server invokes; the
  `:cljs` branch is what the page bootstraps once the chunks arrive.

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
;; We hold the schema as a plain value and register it per-frame at each
;; entry point with the `{:frame …}` override. Streaming SSR runs two
;; frames — the per-request server frame (gensym, in `handle-request`) and
;; the fixed client hydration frame (`app-frame`, in `run`) — and both need
;; the same contract, so each registers it explicitly. Schemas are
;; frame-local, so holding the schema as a plain def keeps ns-load free of
;; side effects: the namespace loads under no frame.
;;
;; `[:maybe …]` because the `:cards` slice is nil until `:rf/server-init`
;; seeds it on the server, or the client hydrates it. A bare `[:map-of …]`
;; would reject that legitimate nil and roll the commit back.
;; See the [schemas guide](../../../docs/guide/how-to/validate-with-schemas.md).
(def CardsSchema
  [:maybe [:map-of :keyword [:map [:title :string] [:value [:maybe :int]]]]])

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :rf/server-init
  {:doc       "Per-request server-side init. A real app would dispatch
               :rf.http/managed fetches here to load each card's data. This
               demo seeds three card values synchronously so the whole wire
               shape can be inspected in one browser request."
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
  ;; Failure isolation. This view deliberately throws. The streaming runtime
  ;; catches it, emits a :rf.ssr/suspense-boundary-failed trace, and ships the
  ;; fallback HTML in the chunk's place (marked data-rf2-suspense-failed). The
  ;; page still loads — only this card stays on its fallback. See the
  ;; [SSR guide — Streaming](../../../docs/ssr/concepts.md#streaming-rfsuspense-boundary).
  (throw (ex-info "flaky third-party metric service" {})))

(rf/reg-view ^{:rf/id :dashboard/root} root-view []
  [:main.dashboard
   [:header
    [:h1 "Dashboard"]
    [:p "Streamed SSR demo — shell renders first, cards stream in."]]
   [:section.cards
    ;; `:rf/suspense-boundary` marks a streamable subtree. The `:fallback`
    ;; ships inline in the shell now; the real child subtree streams in as
    ;; its own chunk once it resolves. The `:id` pairs the incoming chunk
    ;; with its placeholder on the client — you pick it, and it must be unique.
    [:rf/suspense-boundary
     {:id :card.revenue :fallback [:dashboard/card-skeleton :revenue]}
     [:dashboard/card :revenue]]
    [:rf/suspense-boundary
     {:id :card.signups :fallback [:dashboard/card-skeleton :signups]}
     [:dashboard/card :signups]]
    [:rf/suspense-boundary
     {:id :card.latency :fallback [:dashboard/card-skeleton :latency]}
     [:dashboard/card :latency]]
    ;; The failure-path card. Same wire shape; its chunk carries the
    ;; fallback HTML with `data-rf2-suspense-failed="1"` and no hydrate-delta
    ;; script.
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
     "What a host adapter (re-frame.ssr.ring/stream-handler) would call.
     This shape is one logical request → one chunked response; the
     adapter handles the actual Ring wiring and the writer thread. Here
     we synthesise the steps in-line so the example is JVM-runnable
     without a live server.

     Returns: a map carrying the rendered shell, the per-card chunks
     in order, and the final payload — the per-chunk byte sequence the
     streaming adapter would emit in order."
     [_request]
     (let [fid (keyword "rf.frame" (str (gensym "")))
           ;; Register the app schema against this per-request frame BEFORE
           ;; creating it. The server-side `:cards` commit validates in this
           ;; frame, and `reg-frame` runs the `:initial-events` cascade (which
           ;; fires `:rf/server-init`) synchronously, so the schema must be in
           ;; place first.
           _   (rf/reg-app-schema [:cards] {:schema CardsSchema :frame fid})
           _   (rf/reg-frame fid {:doc "ssr-streaming-example frame"
                                  :platform :server
                                  :initial-events [[:rf/server-init]]})
           hiccup (rf/with-frame fid ((rf/view :dashboard/root)))
           {:keys [shell-html continuations]}
           (rf/with-frame fid (ssr/streaming-render-shell hiccup))
           ;; Drain each continuation in order, collecting the resolved
           ;; subtree HTML + per-subtree hydration delta.
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
           ;; The final payload is the canonical full app-db. The per-card
           ;; deltas above are a speed prop; this is the correctness lock. If
           ;; a delta ever disagrees with the payload, the payload wins.
           ;;
           ;; The `:payload` opt decides what crosses the wire, and it is
           ;; fail-closed. This demo's app-db is safe to expose whole (every
           ;; key the dashboard populates is meant for the client), so we opt
           ;; in with `:rf.ssr.payload/whole-app-db`. Production normally
           ;; passes an explicit allowlist vector of top-level app-db keys.
           final-payload (rf/with-frame fid
                           (ssr/streaming-build-final-payload
                             fid render-hash
                             {:version 1
                              :payload :rf.ssr.payload/whole-app-db}))
           ;; Drop the payload's `:rf/frame-id`. The builder stamps this
           ;; per-request frame (`fid`), but the client hydrates a fixed
           ;; `app-frame` (below). `ssr/hydrate!` checks a present `:rf/frame-id`
           ;; against the client's explicit `:frame` and fails closed on
           ;; disagreement, which a per-request gensym always would. With no
           ;; frame-id on the wire the client's explicit target stands. (A
           ;; deployment that wants one on the wire stamps a stable id both
           ;; sides share, not a gensym.) See the
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
;; The streaming hydration sequence on the client:
;;
;;  1. The first chunk lands. The browser receives the shell, whose slow
;;     regions are inline `<template data-rf2-suspense-fallback>`
;;     placeholders. A `<template>`'s content is inert by the HTML spec (its
;;     `.content` is a detached DocumentFragment, never painted), so the
;;     skeletons do NOT paint on the raw bytes. They paint when
;;     `streaming-install!` (step 2) materialises each inert `<template>`
;;     into a live `<rf-suspense data-rf2-suspense-mount>` mount. The
;;     `<script src="main.js">` at the end of `<body>` begins downloading.
;;  2. Resolved-card chunks stream in (Transfer-Encoding: chunked). Each is
;;     a `<template data-rf2-suspense-resolved=…>…</template>` plus a
;;     `<script data-rf2-suspense-hydrate=…>…</script>`. The client-side
;;     streaming runtime (`ssr/streaming-install!`) first materialises the
;;     inert fallback `<template>`s into live `<rf-suspense>` mounts (the
;;     skeletons now paint), then swaps each mount's content for the resolved
;;     subtree in place and merges that subtree's delta into the client
;;     app-db as the chunk arrives. `run` installs it before the first render
;;     so it catches chunks already streamed in and any still arriving.
;;  3. The final `<script id="__rf_payload">` is the canonical full payload.
;;     `run` dispatches `:rf/hydrate` against it (via `ssr/hydrate!`), which
;;     replaces the whole client frame-state in one step — the payload's
;;     app-db plus its serialisable runtime-db slice. The streaming runtime
;;     auto-disconnects once it sees `__rf_payload`, so no late delta can
;;     race the canonical replace. The per-card deltas were a speed prop;
;;     this final replace is the correctness lock.
;;
;; See the [SSR guide — Streaming](../../../docs/ssr/concepts.md#streaming-rfsuspense-boundary)
;; and [hydrate, then verify](../../../docs/ssr/concepts.md#the-client-side-hydrate-then-verify).
;;
;; This example boots from a static `index.html` that stands in for the
;; streaming server — it ships the final resolved state and payload
;; pre-baked, so the browser runs with no Clojure server in the loop.
;; Because the streaming runtime is additive, a page whose chunks already
;; resolved hydrates exactly the same: the install sweep finds no un-swapped
;; fallback, sees `__rf_payload` already present, and goes straight to the
;; `ssr/hydrate!` reconciliation. The progressive path (chunk-arrival swap +
;; delta merge before the final payload) is exercised end-to-end by the
;; browser acceptance test `re-frame.ssr.streaming-client-dom-cljs-test`.

;; The React root is held in an atom and created lazily inside `run`, not at
;; ns-load. ns-load must produce no DOM side effects, so co-required example
;; namespaces don't race `create-root` onto the shared `#app`. (See the
;; mount-isolation convention in examples/TESTING.md.)
#?(:cljs (defonce react-root (atom nil)))

;; The client app-frame id. The app carries it explicitly — the same id
;; flows to the streaming `install!`, `ssr/hydrate!`, and the root
;; `frame-provider`. This example uses `:rf/default`. It must be a
;; `:client`-platform frame so the `:rf.ssr/check-*` compatibility checks the
;; `:rf/hydrate` handler dispatches actually fire. Neither the static
;; `index.html` payload nor the `handle-request` payload carries a
;; `:rf/frame-id`: the server renders under a per-request gensym frame and the
;; client hydrates this fixed frame, so an absent frame-id is the right shape —
;; the explicit `:frame` is the target. See
;; [frames](../../../docs/guide/glossary.md#frame-identity-is-carried-not-found).
#?(:cljs (def app-frame :rf/default))

#?(:cljs
   (defn run []
     ;; `init!` installs the Reagent adapter. It creates no frame — the app
     ;; creates its own explicitly below.
     (rf/init! reagent-adapter/adapter)
     ;; Establish the carried client app-frame BEFORE installing the streaming
     ;; runtime and hydrating into it. `reg-frame` is a no-op on
     ;; re-registration, so hot-reload just works. `:platform :client` makes
     ;; the hydrate compatibility checks fire.
     (rf/reg-frame app-frame {:doc      "ssr-streaming-example client app-frame"
                              :platform :client})
     ;; Register the app schema against this client frame so the `:cards`
     ;; commits — both the streamed deltas and the final hydrate — validate on
     ;; the client too. The symmetric counterpart of the registration in
     ;; `handle-request`.
     (rf/reg-app-schema [:cards] {:schema CardsSchema :frame app-frame})
     ;; Install the client-side streaming runtime BEFORE the first render so it
     ;; catches resolved-subtree chunks as they arrive (and sweeps any already
     ;; present). It swaps fallbacks and merges per-subtree deltas into the
     ;; carried frame, then disconnects on `__rf_payload`.
     (ssr/streaming-install! {:frame app-frame})
     ;; Reconcile against the canonical payload: read `__rf_payload` and
     ;; dispatch `:rf/hydrate` into the explicit `:frame`. This is the
     ;; correctness lock the streamed deltas defer to. Hydrate BEFORE the first
     ;; render so the initial render runs against the seeded app-db.
     ;; (`:render-tree-fn` is omitted because this static demo shell carries a
     ;; placeholder render-hash, so mismatch verification would always warn. A
     ;; real streaming server stamps the genuine hash, and the host passes
     ;; `:render-tree-fn` to turn mismatch detection on.)
     (ssr/hydrate! {:frame app-frame})
     (when (exists? js/document)
       (when-not @react-root
         (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
       ;; Wrap the mount in the carried frame's `frame-provider` so every
       ;; in-tree `dispatch`/`subscribe` resolves to the hydrated frame.
       (rdc/render @react-root
                   [rf/frame-provider {:frame app-frame}
                    [(rf/view :dashboard/root)]]))))

;; The JVM headless test that exercises the server stream (shell → per-card
;; resolved chunks → final payload) lives in re-frame.examples-test
;; (implementation/core/test/), as the `ssr-streaming-example-runs-end-to-end`
;; deftest. The example tree itself stays test-free.
