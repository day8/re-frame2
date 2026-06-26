(ns ssr-streaming.core
  "Worked example for Spec 011 §Streaming SSR.

  A dashboard with three slow cards: the page's shell + header render
  immediately on the server, then each card streams its content as its
  data fetch resolves. The browser shows a usable shell within ~50ms
  while the cards trickle in over ~300ms each.

  Demonstrates:
   - `:rf/suspense-boundary` hiccup marker — declarative
   - Per-card fallback hiccup — `[:div.card.skeleton …]`
   - Inline-fallback failure semantics — one card deliberately throws
     to show the failure path doesn't 500 the page
   - Hydration interleaved per subtree — each chunk's
     `<script data-rf2-suspense-hydrate>` carries the per-card app-db
     delta
   - Final `__rf_payload` arrives last with the canonical full state

  The .cljc shape mirrors `examples/reagent/ssr/core.cljc`: server
  branch in `:clj`, browser branch in `:cljs`. The :clj branch is
  what a Ring server would invoke; the :cljs branch is what the page
  bootstraps after the chunks arrive.

  Per [Spec 011 §Streaming SSR](../../../spec/011-SSR.md#streaming-ssr)."
  (:require [re-frame.core :as rf]
            [re-frame.schemas]
            [re-frame.ssr :as ssr]
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
;; SERVER frame (where the SSR commits actually validate) unschema'd. Streaming
;; SSR has TWO frame families: the per-request server frame (gensym, in
;; `handle-request`) and the FIXED client hydration frame (`app-frame` →
;; `:rf/default`, in `run`). The schema is the same contract for both, so we
;; hold it as a value and register it explicitly against EACH frame at its
;; entry point with the `{:frame …}` override. Holding it as a def also keeps
;; ns-load side-effect-free — the entry namespace loads without any ambient
;; frame fixture.
;; `[:maybe …]` because the slice is ABSENT (nil) until `:rf/server-init`
;; seeds it on the server / the client hydrates it — a bare `[:map-of …]` would
;; reject the legitimate pre-seed `nil` and roll the commit back. (The bug was
;; masked because validation never actually ran on these frames.)
(def CardsSchema
  [:maybe [:map-of :keyword [:map [:title :string] [:value [:maybe :int]]]]])

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :rf/server-init
  {:doc       "Per-request server-side init. In a real app the cards' data
               loads would dispatch :rf.http/managed fetches against three
               microservices; here we synchronously seed three card values
               of varying sizes so the demo's wire shape can be inspected
               in a single browser request."
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
  ;; Demonstrate Spec 011 §Failure semantics — inline fallback. This view
  ;; deliberately throws; the streaming runtime catches it inside
  ;; render-continuation, emits :rf.ssr/suspense-boundary-failed on the
  ;; trace bus, and ships the fallback HTML in the resolved-chunk
  ;; position (with data-rf2-suspense-failed marker). The PAGE still
  ;; loads — only the failing card stays in its fallback state.
  (throw (ex-info "flaky third-party metric service" {})))

(rf/reg-view ^{:rf/id :dashboard/root} root-view []
  [:main.dashboard
   [:header
    [:h1 "Dashboard"]
    [:p "Streamed SSR demo — shell renders first, cards stream in."]]
   [:section.cards
    ;; `:rf/suspense-boundary` marks a streamable subtree: the `:fallback`
    ;; ships inline in the shell now; the real child subtree streams in as
    ;; its own chunk when it resolves. The `:id` pairs the incoming chunk
    ;; with its placeholder on the client — you pick it, it must be unique.
    [:rf/suspense-boundary
     {:id :card.revenue :fallback [:dashboard/card-skeleton :revenue]}
     [:dashboard/card :revenue]]
    [:rf/suspense-boundary
     {:id :card.signups :fallback [:dashboard/card-skeleton :signups]}
     [:dashboard/card :signups]]
    [:rf/suspense-boundary
     {:id :card.latency :fallback [:dashboard/card-skeleton :latency]}
     [:dashboard/card :latency]]
    ;; The failure-path demonstrator card. Same wire shape; the
    ;; chunk carries the fallback HTML with `data-rf2-suspense-failed="1"`
    ;; and no hydrate-delta script.
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
           ;; Register the app schema AGAINST THIS per-request server frame
           ;; BEFORE the `:initial-events` `:rf/server-init` step runs — the
           ;; per-request frame is where the server-side `:cards` commit
           ;; actually validates, so the schema must bind here. `reg-frame` runs
           ;; the `:initial-events` cascade synchronously before returning, so the
           ;; schema must be in place first; register under `fid`, then create.
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
           ;; The final payload is the canonical full app-db — the correctness
           ;; lock that the per-card deltas above were only a speed prop for. If
           ;; a speculative delta ever disagreed with this, this wins.
           ;; Hydration-payload policy is explicit + fail-closed, carried
           ;; by the single `:payload` opt. This example's app-db is
           ;; structurally safe to expose end-to-end (every key the
           ;; dashboard handlers populate is intended for the client), so
           ;; we opt in with `:payload :rf.ssr.payload/whole-app-db`. A
           ;; real production deployment would normally pass `:payload`
           ;; with an explicit vector allowlist of top-level app-db keys.
           final-payload (rf/with-frame fid
                           (ssr/streaming-build-final-payload
                             fid render-hash
                             {:version 1
                              :payload :rf.ssr.payload/whole-app-db}))
           ;; EP-0002: `streaming-build-final-payload` stamps the
           ;; per-request server frame (`fid`) as `:rf/frame-id`, but the
           ;; client hydrates a FIXED app-frame (`app-frame` → `:rf/default`,
           ;; below). `ssr/hydrate!` VALIDATES a present payload `:rf/frame-id`
           ;; against the client's explicit `:frame` and raises
           ;; `:rf.error/hydration-frame-id-mismatch` on disagreement (Spec 011
           ;; §The hydration payload). The server's per-request gensym would
           ;; always conflict with the client's fixed frame, so we DROP it —
           ;; an absent `:rf/frame-id` is explicitly NO conflict (the explicit
           ;; client target stands), matching the static `index.html` next to
           ;; this file. A deployment that wants a frame-id on the wire stamps
           ;; a STABLE id both sides agree on, not a per-request gensym.
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
;; Streaming hydration shape (per Spec 011 §Streaming SSR — Client-side
;; hydration semantics):
;;
;;  1. First chunk lands — the browser receives the shell, whose slow
;;     regions are inline `<template data-rf2-suspense-fallback>`
;;     placeholders. A `<template>`'s content is INERT by the HTML spec
;;     (its `.content` is a detached DocumentFragment, never painted), so
;;     the skeletons do NOT paint on the raw bytes; they paint when
;;     `streaming-install!` (step 2) materialises each inert `<template>`
;;     into a live, visible `<rf-suspense data-rf2-suspense-mount>` mount.
;;     The `<script src="main.js">` reference at the end of `<body>`
;;     begins downloading.
;;  2. Resolved-card chunks stream in via Transfer-Encoding: chunked.
;;     Each one is `<template data-rf2-suspense-resolved=…>…</template>`
;;     plus `<script data-rf2-suspense-hydrate=…>…</script>`. The
;;     client-side streaming runtime (`ssr/streaming-install!`, per
;;     Spec 011 §Streaming SSR — client-side hydration semantics) first
;;     materialises the inert fallback `<template>`s into live
;;     `<rf-suspense>` mounts (the skeletons now paint), then swaps each
;;     mount's content for the resolved subtree in-place and merges the
;;     per-subtree delta into the client app-db AS each chunk arrives —
;;     progressive hydration, before the final payload. `run` installs it
;;     below before the first render so it catches both chunks that
;;     already streamed in and any still arriving.
;;  3. The final `<script id="__rf_payload">` is the canonical full
;;     payload; `run` dispatches `:rf/hydrate` against it (via
;;     `ssr/hydrate!`), which runs :replace-frame-state semantics (the
;;     payload's `:rf/app-db` + serialisable runtime-db slice replace the
;;     whole client frame-state in one step) — the
;;     per-card deltas were progressive-render speed props; the final
;;     payload is the correctness lock. The streaming runtime
;;     auto-disconnects once it sees `__rf_payload`, so no late delta can
;;     race the canonical replace.
;;
;; This worked example boots from a static `index.html` that stands in
;; for the streaming server (it ships the final resolved state + the
;; final payload pre-baked, so the browser runs without a Clojure server
;; in the loop). Because the streaming runtime is additive, a page whose
;; chunks already resolved hydrates exactly the same: the install sweep
;; finds no un-swapped fallback, sees `__rf_payload` already present, and
;; goes straight to the `ssr/hydrate!` reconciliation. The runtime's
;; progressive path (chunk-arrival swap + delta merge before the final
;; payload) is exercised end-to-end by the browser acceptance test
;; `re-frame.ssr.streaming-client-dom-cljs-test`.

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
#?(:cljs (defonce react-root (atom nil)))

;; EP-0002: the SSR hydration target is CARRIED —
;; established explicitly by the app and threaded through the streaming
;; `install!`, `ssr/hydrate!`, AND the root `frame-provider`. The runtime
;; never synthesises a frame from absence. This example uses `:rf/default`
;; as its client app-frame id; it MUST be a `:client`-platform frame so the
;; `:rf.ssr/check-*` compatibility-check fxs the `:rf/hydrate` handler
;; dispatches actually fire (Spec 011 §The :rf/hydrate event). BOTH the static
;; `index.html` payload AND the dynamic `handle-request` final-payload above
;; carry NO `:rf/frame-id`: the server renders under a per-request gensym
;; frame the client can't know ahead of time, and the client hydrates this
;; FIXED app-frame, so an absent frame-id is the correct shape (it is NOT a
;; hydration-frame-id conflict — the explicit `:frame` stands). A present-but-
;; different stamp (the server's per-request gensym against this `:rf/default`
;; client target) WOULD surface `:rf.error/hydration-frame-id-mismatch` (Spec
;; 011 §The hydration payload), which is why `handle-request` drops it.
#?(:cljs (def app-frame :rf/default))

#?(:cljs
   (defn run []
     ;; `init!` installs the adapter but does NOT create a frame — EP-0002:
     ;; the app establishes its frame explicitly (below).
     (rf/init! reagent-adapter/adapter)
     ;; Establish the carried client app-frame BEFORE installing the
     ;; streaming runtime / hydrating into it. `reg-frame` is a surgical
     ;; no-op on re-registration (hot-reload Just Works). `:platform :client`
     ;; makes the hydrate compatibility-check fxs fire.
     (rf/reg-frame app-frame {:doc      "ssr-streaming-example client app-frame"
                              :platform :client})
     ;; Register the app schema AGAINST THE FIXED CLIENT FRAME so
     ;; the progressively-merged + finally-hydrated `:cards` commits validate on
     ;; the client too — the symmetric counterpart of the per-request
     ;; registration in `handle-request`. `{:frame app-frame}` is the explicit
     ;; override; re-registration is a no-op-safe on hot-reload.
     (rf/reg-app-schema [:cards] {:schema CardsSchema :frame app-frame})
     ;; Install the client-side streaming runtime BEFORE the first render
     ;; so it catches resolved-subtree chunks as they arrive (and sweeps
     ;; any already present). It swaps fallbacks + merges per-subtree
     ;; deltas progressively into the carried frame, then disconnects on
     ;; `__rf_payload`.
     (ssr/streaming-install! {:frame app-frame})
     ;; Reconcile against the canonical payload: read `__rf_payload` +
     ;; dispatch `:rf/hydrate` (:replace-frame-state) into the EXPLICIT `:frame`.
     ;; The deltas were speculative; this is the correctness lock.
     ;; (`:render-tree-fn` is omitted — the static demo shell carries a
     ;; placeholder render-hash, so hash-mismatch verification would always
     ;; warn here; a real streaming server stamps the genuine hash and a host
     ;; then passes `:render-tree-fn` to enable mismatch detection.) Hydrate
     ;; BEFORE first render so the initial render runs against the seeded
     ;; app-db.
     (ssr/hydrate! {:frame app-frame})
     (when (exists? js/document)
       (when-not @react-root
         (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
       ;; Wrap the mount in the carried frame's `frame-provider` so every
       ;; in-tree `dispatch`/`subscribe` resolves to the hydrated frame.
       (rdc/render @react-root
                   [rf/frame-provider-existing {:frame app-frame}
                    [(rf/view :dashboard/root)]]))))

;; The JVM-runnable headless test that exercises the server stream
;; (shell → per-card resolved chunks → final payload) lives in
;; re-frame.examples-test (implementation/core/test/), folded inline as
;; the `ssr-streaming-example-runs-end-to-end` deftest,
;; keeping this example source pure demonstrative code (the example tree
;; is test-free). It runs on the JVM.
