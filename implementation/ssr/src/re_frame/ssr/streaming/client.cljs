(ns re-frame.ssr.streaming.client
  "Client-side streaming-SSR runtime — the consumer of the server's
  `<template>` / `<script data-rf2-suspense-hydrate>` delta-chunk
  protocol. Per Spec 011 §Streaming SSR — client-side hydration
  semantics (rf2-3hhv5; folds rf2-bee5i part-2).

  ## What this is

  The server side (`re-frame.ssr.streaming` +
  `re-frame.ssr.ring.streaming/stream-handler`) flushes a chunked
  response whose application-layer shape is:

    1. Shell HTML with `<template data-rf2-suspense-id=\"<id>\"
       data-rf2-suspense-fallback=\"1\">…fallback…</template>`
       placeholders inline.
    2. N resolved-subtree chunks — each a
       `<template data-rf2-suspense-id=\"<id>\"
        data-rf2-suspense-resolved=\"1\">…subtree-html…</template>`
       immediately followed by a
       `<script data-rf2-suspense-hydrate=\"<id>\"
        type=\"application/edn\">…delta-edn…</script>`.
    3. The final `<script id=\"__rf_payload\"
       type=\"application/edn\">…full-payload…</script>`.
    4. The closing `</body></html>`.

  WITHOUT a client consuming chunks (2), the deltas stream over the
  wire but are inert: the browser paints skeleton fallbacks until the
  final `__rf_payload` lands, then `:rf/hydrate` re-renders everything
  — identical UX to non-streaming, defeating the speed prop of
  suspense boundaries. This namespace is that consumer.

  `install!` performs progressive per-subtree hydration: as each
  resolved chunk's nodes parse into the DOM, the runtime

    - swaps the matching `data-rf2-suspense-fallback` `<template>` for
      the resolved content **in-place** (the user sees the card
      content the moment its chunk arrives, not after the whole
      response), and
    - merges that chunk's per-subtree app-db delta into the target
      frame's `app-db` via a top-level `(into existing delta)` merge so
      a subscription reading the now-resolved region sees the
      speculative state.

  When the final `__rf_payload` lands, the bootstrap (`ssr/hydrate!`)
  dispatches `:rf/hydrate` with `:replace-app-db` semantics — the
  deltas were speculative, the final payload is the correctness lock.
  `install!` reconciles by disconnecting its observer once it sees the
  final-payload node, so no stray delta can race the canonical state.

  ## Wire-shape contract — matches the SHIPPED server emitter EXACTLY

  This is load-bearing: the server is built + tested, so the client
  conforms to what the server actually emits, not a paraphrase.

    - Boundary id lives on the `data-rf2-suspense-id` (template) and
      `data-rf2-suspense-hydrate` (script) **attribute**, as the
      keyword/string printed via `pr-str`/`str` then `escape-attr`'d.
      `re-frame.ssr.streaming/suspense-template` and
      `hydrate-delta-script` stamp `(html/escape-attr (str id))` —
      i.e. `:card.revenue` → the attribute literal `:card.revenue`.
      `escape-attr` only escapes `&` and `\"`, so a normal keyword id
      round-trips unchanged; we read the attribute string back into an
      id via `cljs.reader/read-string` (a keyword id parses to a
      keyword; a string id stays a string).
    - The delta `<script>` body is the **bare delta-map** EDN
      (`(pr-str delta)`), NOT a wrapped `{:rf/app-db-delta … :rf/boundary-id …}`
      envelope. The boundary id is carried by the attribute only. The
      body is `escape-edn-script-body`'d — `<` inside string literals
      becomes `\\u003c` (which `cljs.reader/read-string` decodes back),
      while `<` in keyword/symbol tokens (`:<`, `:a<b`) is left intact
      so the delta round-trips (rf2-rdxxa). (Spec 011 §Hydration
      interleaving's wrapped-shape prose is corrected to this shipped
      contract.)
    - A `data-rf2-suspense-failed=\"1\"` marker on a resolved
      `<template>` means the server's continuation render threw and the
      chunk carries the *fallback* HTML; there is no hydrate-delta
      script for a failed boundary. The runtime still swaps the content
      (so the author's declared loading state replaces the streaming
      placeholder) but applies no delta and emits a client-side
      `:rf.ssr/suspense-boundary-failed` trace for observability.

  ## Host opt-in

  Non-streaming pages skip the require entirely — `install!` is called
  by a streaming-aware bootstrap only. A page with no streaming chunks
  (the final payload already inlined) needs no client runtime; the
  observer simply never matches a resolved chunk and disconnects on the
  final-payload node.

  Per the rf2-uo7v shipping convention this namespace lives in the
  `day8/re-frame2-ssr` artefact alongside the rest of the SSR surface
  (it is NOT eagerly required by the `re-frame.ssr` façade — host
  opt-in). CLJS-only (`.cljs`): it reaches into the DOM and installs a
  `MutationObserver`."
  (:require [cljs.reader :as reader]
            [re-frame.frame :as frame]
            [re-frame.trace :as trace]))

;; ---- wire-shape constants (the attribute names the server stamps) ---------
;;
;; Pinned here once so a rename of the server emitter's attribute names
;; is a one-edit-each-side change. These MUST match
;; `re-frame.ssr.streaming/{suspense-template,hydrate-delta-script}`.

(def ^:private attr-suspense-id        "data-rf2-suspense-id")
(def ^:private attr-suspense-fallback  "data-rf2-suspense-fallback")
(def ^:private attr-suspense-resolved  "data-rf2-suspense-resolved")
(def ^:private attr-suspense-failed    "data-rf2-suspense-failed")
(def ^:private attr-suspense-hydrate   "data-rf2-suspense-hydrate")

;; The client-owned LIVE mount element. The server emits the fallback
;; wrapped in a `<template>`, whose content is INERT by the HTML spec
;; (`.content` is a detached DocumentFragment — not painted). So the
;; runtime materialises each fallback into a live `<rf-suspense
;; data-rf2-suspense-mount="<id>">…fallback…</rf-suspense>` wrapper on
;; install: the user sees the skeleton, and the wrapper is the live swap
;; target the resolved chunk replaces in-place. This is the canonical
;; streaming-hydration shape (visible fallback + a stable mount the
;; resolved subtree swaps into) — the same model React 18 / Solid use,
;; expressed over the server's `<template>`-marker protocol.
(def ^:private attr-suspense-mount     "data-rf2-suspense-mount")
(def ^:private mount-tag               "rf-suspense")

;; ---- id parsing ------------------------------------------------------------

(defn- read-boundary-id
  "Parse a boundary-id attribute string back into the id value the
  hiccup author wrote. The server stamps `(html/escape-attr (str id))`;
  for a keyword id that is the keyword's printed form (`:card.revenue`),
  for a string id the raw string. `cljs.reader/read-string` recovers a
  keyword id as a keyword and any other token as itself; on a parse
  failure (an exotic string id with reader-significant chars) we fall
  back to the raw string so matching still works structurally — the id
  is only ever used as a map/DOM-attribute key, never evaluated."
  [s]
  (try
    (reader/read-string s)
    (catch :default _ s)))

;; ---- DOM helpers -----------------------------------------------------------

(defn- query-by-attr
  "Return a seq of elements under `root` carrying attribute `attr`
  (presence, any value). `root` is a Document or Element."
  [root attr]
  (array-seq (.querySelectorAll root (str "[" attr "]"))))

(defn- template-content-fragment
  "Materialise a `<template>`'s parsed content as a DocumentFragment
  ready to insert. `<template>` elements expose their parsed children
  via `.content` (a DocumentFragment); we clone it so the source
  template can be removed without detaching the inserted nodes."
  [tmpl]
  (.cloneNode (.-content tmpl) true))

(defn- mount-for
  "Find the live `<rf-suspense data-rf2-suspense-mount=\"<id>\">` wrapper
  for boundary `id-str` under `root`, or nil. This is the swap target
  the resolved chunk replaces. Matching is by the id attribute so the
  swap is exact even across nested boundaries."
  [root id-str]
  (->> (query-by-attr root attr-suspense-mount)
       (filter #(= id-str (.getAttribute % attr-suspense-mount)))
       first))

(defn- materialise-fallback!
  "Turn one inert `data-rf2-suspense-fallback` `<template>` into a LIVE
  visible mount. Inserts an `<rf-suspense data-rf2-suspense-mount>`
  wrapper carrying the boundary id, fills it with the template's parsed
  fallback content (so the user sees the skeleton), and removes the
  template (its job — carrying the fallback markup across the wire — is
  done). Idempotent: skips a template whose mount already exists.

  Returns the mount element (new or pre-existing), or nil if the
  template carried no id."
  [fb-tmpl]
  (when-let [id-str (.getAttribute fb-tmpl attr-suspense-id)]
    (let [root (or (.-parentNode fb-tmpl) fb-tmpl)]
      (or (mount-for root id-str)
          (let [parent (.-parentNode fb-tmpl)
                mount  (.createElement js/document mount-tag)]
            (.setAttribute mount attr-suspense-mount id-str)
            (.appendChild mount (template-content-fragment fb-tmpl))
            (when parent
              (.insertBefore parent mount fb-tmpl)
              (.removeChild parent fb-tmpl))
            mount)))))

(defn- materialise-fallbacks!
  "Materialise every un-mounted fallback `<template>` under `root` into a
  live mount. Run on install + on observed additions so a fallback that
  streamed in after install still becomes visible."
  [root]
  (doseq [t (query-by-attr root attr-suspense-fallback)]
    (materialise-fallback! t)))

(defn- replace-mount-content!
  "Replace the live mount's children for `id-str` with the resolved
  `<template>`'s parsed content, in-place. Returns true if a mount was
  found + swapped, false otherwise (e.g. duplicate-id: the resolved
  chunk for an id whose mount was already consumed, or a resolved chunk
  with no matching fallback). The mount wrapper itself stays in the DOM
  carrying its id — harmless, and it keeps the swap target stable if a
  later (duplicate) chunk arrives."
  [root id-str resolved-tmpl]
  (if-let [mount (mount-for root id-str)]
    (do
      (set! (.-innerHTML mount) "")
      (.appendChild mount (template-content-fragment resolved-tmpl))
      (when-let [rp (.-parentNode resolved-tmpl)]
        (.removeChild rp resolved-tmpl))
      true)
    false))

;; ---- delta merge -----------------------------------------------------------

(defn- merge-delta!
  "Merge a per-subtree hydration delta into `frame-id`'s app-db via the
  documented top-level `(into existing delta)` merge (Spec 011
  §Hydration interleaving). The delta ships the FULL after-db value for
  each changed/new top-level key, so the top-level `into` is lossless
  even for changed nested keys. No-op on an empty delta. Runs OUTSIDE
  the event loop — these are speculative pre-`:rf/hydrate` reads, not a
  dispatched event; the final `__rf_payload` `:rf/hydrate` is the
  canonical replace."
  [frame-id delta]
  (when (and (map? delta) (seq delta))
    (frame/swap-frame-db! frame-id (fn [db] (into db delta)))))

;; ---- chunk processing ------------------------------------------------------

(defn- apply-delta-script!
  "Find the `data-rf2-suspense-hydrate` delta `<script>` for `id-str`,
  read its bare delta-map EDN, merge it into `frame-id`'s app-db, and
  drop the script node. The server emits the delta `<script>`
  immediately after the resolved `<template>`, but DOM order after our
  swap is not guaranteed, so we match by id attribute rather than
  sibling position. A malformed delta is skipped with a trace — a bad
  speculative chunk must not break hydration (the final payload is the
  correctness lock)."
  [root frame-id id-str]
  (when-let [script (->> (query-by-attr root attr-suspense-hydrate)
                         (filter #(= id-str (.getAttribute % attr-suspense-hydrate)))
                         first)]
    (let [delta (try
                  (reader/read-string (.-textContent script))
                  (catch :default e
                    (trace/emit-error! :rf.ssr/suspense-boundary-failed
                                       {:id        (read-boundary-id id-str)
                                        :frame     frame-id
                                        :where     'rf.ssr/streaming-client
                                        :reason    (str "Malformed hydration-delta EDN for boundary " id-str)
                                        :exception (ex-message e)
                                        :recovery  :skipped-delta})
                    nil))]
      (when delta (merge-delta! frame-id delta))
      ;; The delta is consumed; drop the script node so the DOM is left
      ;; in its final, script-free shape.
      (when-let [sp (.-parentNode script)]
        (.removeChild sp script)))))

(defn- process-resolved-template!
  "Process one resolved-subtree `<template>` (carrying
  `data-rf2-suspense-resolved`): swap the live mount's content for the
  resolved HTML, then — for a non-failed boundary — apply the matching
  per-subtree hydration-delta `<script>`. A `data-rf2-suspense-failed`
  chunk carries the fallback HTML (which still swaps in, so the author's
  declared loading state replaces the streaming placeholder) and NO
  delta; it surfaces `:rf.ssr/suspense-boundary-failed` for
  observability without a 500 (Spec 011 §Failure semantics — inline
  fallback).

  `seen` is an atom of the id-strings already processed so a chunk is
  applied at most once even if the observer fires twice for the same
  node (defensive — MutationObserver batching + the initial sweep can
  both surface the same node). Idempotent."
  [root frame-id seen resolved-tmpl]
  (let [id-str  (.getAttribute resolved-tmpl attr-suspense-id)
        failed? (= "1" (.getAttribute resolved-tmpl attr-suspense-failed))]
    (when (and id-str (not (contains? @seen id-str)))
      (swap! seen conj id-str)
      ;; Ensure a live mount exists even if the fallback template was not
      ;; materialised yet (a resolved chunk that raced ahead of its own
      ;; fallback in the same observer batch) — materialise-fallbacks!
      ;; runs first in the sweep, so this is the belt-and-braces path.
      (let [swapped? (replace-mount-content! root id-str resolved-tmpl)]
        (if failed?
          (trace/emit-error! :rf.ssr/suspense-boundary-failed
                             {:id        (read-boundary-id id-str)
                              :frame     frame-id
                              :where     'rf.ssr/streaming-client
                              :reason    "Server-side continuation render failed; client swapped the fallback HTML, no delta applied."
                              :recovery  :inline-fallback})
          (when swapped?
            (apply-delta-script! root frame-id id-str)))))))

(defn- sweep!
  "One full sweep: materialise any un-mounted fallback `<template>`s into
  live visible mounts, then process every `data-rf2-suspense-resolved`
  `<template>` (swap + delta-merge). Called once on install (chunks that
  streamed in before the bundle ran) and again whenever the observer
  reports new nodes. Fallback materialisation runs FIRST so a resolved
  chunk in the same batch always finds a live mount to swap into."
  [root frame-id seen]
  (materialise-fallbacks! root)
  (doseq [t (query-by-attr root attr-suspense-resolved)]
    (process-resolved-template! root frame-id seen t)))

(defn- final-payload-present?
  "True once the canonical `__rf_payload` `<script>` has parsed into the
  DOM — the signal that streaming is complete and the observer should
  disconnect (the final `:rf/hydrate` is the reconciliation point;
  deltas after it would race the canonical replace)."
  [root payload-id]
  (some? (.querySelector root (str "#" payload-id))))

;; ---- public surface --------------------------------------------------------

(defn install!
  "Install the client-side streaming-SSR runtime. Watches the document
  (or a supplied root) for resolved-subtree chunks as the chunked
  response streams in; for each, swaps the fallback `<template>` for the
  resolved content in-place and merges the per-subtree app-db delta into
  the target frame. Disconnects once the final `__rf_payload` node lands
  (the bootstrap's `:rf/hydrate` then replaces app-db canonically).

  Opts:

    :frame      — the target frame id whose app-db receives the deltas.
                  Default `:rf/default` (the frame the bootstrap
                  `ssr/hydrate!`s).
    :root       — the DOM root to observe + query. Default
                  `js/document`. A test harness passes a detached
                  container so it can drive chunk-arrival deterministically.
    :payload-id — the id of the final-payload `<script>` whose arrival
                  signals stream completion. Default `\"__rf_payload\"`
                  (`re-frame.ssr.constants/payload-script-id`); accepted
                  as an opt rather than required so the runtime needs no
                  dependency on the constants ns and a host that
                  overrode the shell's payload id can match it.

  Returns a 0-arity `stop!` fn that disconnects the observer early (so a
  host can tear the runtime down on its own schedule — e.g. an SPA
  navigation that abandons the stream). The runtime also auto-disconnects
  when it observes the final-payload node, so most hosts never call it.

  Idempotent per chunk: the same resolved node is applied at most once
  even if the observer batches or the initial sweep races a mutation.

  Usage (streaming-aware Reagent bootstrap):

      #?(:cljs
         (defn ^:export run []
           (rf/init! reagent-slim-adapter/adapter)
           ;; Install BEFORE the first chunks may have arrived so the
           ;; observer catches them; the initial sweep also covers chunks
           ;; that landed before the bundle executed.
           (streaming-client/install! {:frame :rf/default})
           (rdc/render react-root [(rf/view :app/root)])
           ;; Reconcile against the canonical payload once it lands.
           ;; (A host typically polls / observes for `__rf_payload`, or
           ;; the streaming bootstrap calls `ssr/hydrate!` on completion.)
           ))"
  ([] (install! {}))
  ([{:keys [frame root payload-id]
     :or   {frame      :rf/default
            root       (when (exists? js/document) js/document)
            payload-id "__rf_payload"}}]
   ;; No DOM (a non-browser runtime / a host calling install! too early)
   ;; → no-op stop fn. The runtime is a DOM consumer by definition.
   (if (or (nil? root) (not (exists? js/MutationObserver)))
     (fn no-op-stop! [])
     (let [seen      (atom #{})
           observer  (atom nil)
           stop!     (fn stop! []
                       (when-let [o @observer]
                         (.disconnect o)
                         (reset! observer nil)))
           on-mutations
           (fn [_mutations _obs]
             ;; A cheap full re-sweep on any mutation is correct + simple:
             ;; `seen` makes re-processing idempotent, and the resolved-
             ;; chunk count over a page's lifetime is small (one per
             ;; suspense boundary). Walking the mutation records to find
             ;; only the added resolved templates is a micro-opt that adds
             ;; tree-walking complexity for no measurable win at these
             ;; cardinalities.
             (sweep! root frame seen)
             (when (final-payload-present? root payload-id)
               (stop!)))]
       ;; Initial sweep — chunks that streamed in before this bundle
       ;; executed (the common case: the shell + several cards land while
       ;; main.js downloads + boots). Materialises fallbacks into visible
       ;; mounts + applies any resolved chunks already present.
       (sweep! root frame seen)
       (if (final-payload-present? root payload-id)
         ;; Stream already complete by the time we installed — nothing to
         ;; observe; the bootstrap's `:rf/hydrate` is the reconciliation.
         (fn already-complete-stop! [])
         (let [obs (js/MutationObserver. on-mutations)]
           (reset! observer obs)
           ;; Observe the whole subtree: resolved `<template>`s + the
           ;; final payload `<script>` arrive as descendant additions of
           ;; `<body>` / `#app` as the response streams.
           (.observe obs root #js {:childList true :subtree true})
           stop!))))))
