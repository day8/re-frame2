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

  WITHOUT a client consuming these chunks, the whole protocol is inert:
  the fallback `<template>`s never paint (a `<template>`'s content is a
  detached `DocumentFragment` by the HTML spec — see chunk (1) / rf2-xzhf2a),
  the resolved `<template>`s never swap in, and the deltas never merge — the
  browser shows blank boundary regions until the final `__rf_payload` lands,
  then `:rf/hydrate` renders everything at once. Identical UX to
  non-streaming, defeating the speed prop of suspense boundaries. This
  namespace is that consumer: it materialises the inert fallbacks into
  visible mounts AND swaps in resolved chunks progressively.

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
  dispatches `:rf/hydrate` with `:replace-frame-state` semantics — the
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
            [re-frame.ssr.constants :as constants]
            [re-frame.ssr.streaming.constants :as wire]
            [re-frame.trace :as trace]))

;; ---- wire-shape constants (the attribute names the server stamps) ---------
;;
;; The server↔client streaming wire attribute names live in
;; `re-frame.ssr.streaming.constants` so a rename is a one-edit change
;; there, not a grep-driven sweep across server, client, and tests
;; (rf2-3w6dmy finding 2). These MUST match the attributes
;; `re-frame.ssr.streaming/{suspense-template,hydrate-delta-script}` stamp —
;; they read from the SAME `wire` ns, so the agreement is enforced by the
;; shared source rather than a comment. Aliased here so the call sites read
;; the same as before.

(def ^:private attr-suspense-id        wire/attr-suspense-id)
(def ^:private attr-suspense-fallback  wire/attr-suspense-fallback)
(def ^:private attr-suspense-resolved  wire/attr-suspense-resolved)
(def ^:private attr-suspense-failed    wire/attr-suspense-failed)
(def ^:private attr-suspense-hydrate   wire/attr-suspense-hydrate)

;; The client-owned LIVE mount element. The server emits the fallback
;; wrapped in a `<template>`, whose content is INERT by the HTML spec
;; (`.content` is a detached DocumentFragment — not painted). So the
;; runtime materialises each fallback into a live `<rf-suspense
;; data-rf2-suspense-mount="<id>">…fallback…</rf-suspense>` wrapper on
;; install: the user sees the skeleton, and the wrapper is the live swap
;; target the resolved chunk replaces in-place. This is the canonical
;; streaming-hydration shape (visible fallback + a stable mount the
;; resolved subtree swaps into) — the same model React 18 / Solid use,
;; expressed over the server's `<template>`-marker protocol. Client-owned
;; but pinned in the shared `wire` ns alongside the server-emitted
;; attributes (rf2-3w6dmy finding 2).
(def ^:private attr-suspense-mount     wire/attr-suspense-mount)
(def ^:private mount-tag               wire/mount-tag)

;; ---- id parsing ------------------------------------------------------------

(defn- read-boundary-id
  "Parse a boundary-id attribute string back into the id value the
  hiccup author wrote. The server stamps `(html/escape-attr (str id))`;
  for a keyword id that is the keyword's printed form (`:card.revenue`),
  for a string id the raw string (`card-revenue`).

  `cljs.reader/read-string` recovers a keyword id as a keyword. But a bare
  string id (`(str \"card-revenue\")` → `card-revenue`) parses to an EDN
  SYMBOL, not a string — `(str id)` erased the string vs symbol distinction
  on the wire (a hiccup id is only ever a keyword or a string, never a
  symbol). So a SYMBOL parse is always a string id that lost its quotes in
  transit: coerce it back to its printed string, preserving the documented
  contract that a string id stays a string in trace payloads (rf2-m96yhw).
  `(str sym)` faithfully reconstructs the original string for both a bare
  (`card-revenue`) and a slash-bearing (`card/revenue`) string id — `name`
  would drop the namespace segment. Keyword ids (and any other non-symbol
  parse) keep their parsed type. On a parse FAILURE (an exotic string id with
  reader-significant chars) we fall back to the raw attribute string. The id
  is only ever used as a map/DOM-attribute key or a trace payload, never
  evaluated."
  [s]
  (let [parsed (try
                 (reader/read-string s)
                 (catch :default _ ::fail))]
    (cond
      (= ::fail parsed) s
      ;; A bare-token parse is a symbol — the server emitted a STRING id via
      ;; `(str id)`, dropping the quotes; recover the string (rf2-m96yhw).
      (symbol? parsed)  (str parsed)
      :else             parsed)))

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

(defn- mounts-for
  "Every live `<rf-suspense data-rf2-suspense-mount=\"<id>\">` wrapper for
  boundary `id-str` under `root`, in document order. A well-formed page has
  exactly one per id; a DUPLICATE-id boundary (a programmer error the server
  surfaces with `:rf.error/suspense-boundary-duplicate-id`, fail-soft
  last-write-wins) yields more than one — one live mount per declared
  boundary, all carrying the same id."
  [root id-str]
  (->> (query-by-attr root attr-suspense-mount)
       (filter #(= id-str (.getAttribute % attr-suspense-mount)))))

(defn- mount-for
  "The swap-target live mount for boundary `id-str` under `root`, or nil.
  Matching is by the id attribute so the swap is exact even across nested
  boundaries. For a DUPLICATE-id boundary the target is the LAST mount in
  document order — the server keeps the LAST registration (last-write-wins,
  Spec 011 §Boundary nesting and recursion) and ships only that
  continuation's resolved chunk, so the resolved content must land in the
  last boundary's position; the earlier mounts keep showing their fallback
  (rf2-8en9mu). For the common single-id case this is just that one mount."
  [root id-str]
  (last (mounts-for root id-str)))

(defn- materialise-fallback!
  "Turn one inert `data-rf2-suspense-fallback` `<template>` into a LIVE
  visible mount. Inserts an `<rf-suspense data-rf2-suspense-mount>`
  wrapper carrying the boundary id, fills it with the template's parsed
  fallback content (so the user sees the skeleton), and removes the
  template (its job — carrying the fallback markup across the wire — is
  done).

  Per-template idempotent by construction: a materialised template is
  REMOVED from the DOM, so a later sweep's `materialise-fallbacks!` query
  cannot re-encounter it. We therefore do NOT short-circuit on an existing
  same-id mount — doing so would collapse a DUPLICATE-id boundary's second
  fallback `<template>` into the first boundary's mount, leaving the second
  template stranded inert in the DOM (rf2-8en9mu). Each declared boundary
  gets its OWN visible mount; the resolved chunk later targets the LAST one
  (`mount-for`).

  Returns the new mount element, or nil if the template carried no id."
  [fb-tmpl]
  (when-let [id-str (.getAttribute fb-tmpl attr-suspense-id)]
    (let [parent (.-parentNode fb-tmpl)
          mount  (.createElement js/document mount-tag)]
      (.setAttribute mount attr-suspense-mount id-str)
      (.appendChild mount (template-content-fragment fb-tmpl))
      (when parent
        (.insertBefore parent mount fb-tmpl)
        (.removeChild parent fb-tmpl))
      mount)))

(defn- materialise-fallbacks!
  "Materialise every un-mounted fallback `<template>` under `root` into a
  live mount. Run on install + on observed additions so a fallback that
  streamed in after install still becomes visible."
  [root]
  (doseq [t (query-by-attr root attr-suspense-fallback)]
    (materialise-fallback! t)))

(defn- replace-mount-content!
  "Replace the live mount's children for `id-str` with the resolved
  `<template>`'s parsed content, in-place. Targets `mount-for` — the LAST
  mount for the id, so a DUPLICATE-id boundary's single resolved chunk
  (the server's last-write-wins registration) lands in the LAST boundary's
  position while earlier mounts keep their fallback (rf2-8en9mu). Returns
  true if a mount was found + swapped, false otherwise (a resolved chunk
  with no matching fallback mount yet — e.g. a nested inner chunk racing
  ahead of its mount). The mount wrapper itself stays in the DOM carrying
  its id — harmless, and it keeps the swap target stable."
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

(defn- malformed-delta!
  "Emit the `:rf.ssr/suspense-boundary-failed` / `:skipped-delta` trace for
  a delta `<script>` whose body did not yield a usable delta-map — EITHER a
  reader exception (unparseable EDN) OR a parseable-but-non-map value (a
  vector, number, string, …). Factored out so both fail-CLOSED branches emit
  the SAME catalogued event (Spec 009 §Error event catalogue —
  `:rf.ssr/suspense-boundary-failed`, `:recovery :skipped-delta`). `extra` is
  merged in to carry the branch-specific field (`:exception` for the reader
  throw, `:malformed-value-type` for a non-map parse)."
  [frame-id id-str reason extra]
  (trace/emit-error! :rf.ssr/suspense-boundary-failed
                     (merge {:id       (read-boundary-id id-str)
                             :frame    frame-id
                             :where    'rf.ssr/streaming-client
                             :reason   reason
                             :recovery :skipped-delta}
                            extra)))

(defn- read-delta
  "Parse a delta `<script>`'s bare delta-map EDN body, failing CLOSED on any
  shape that is NOT a usable delta-map. Returns the parsed map when the body
  is a (possibly empty) map, else nil — and emits the malformed-delta trace
  for the two fail-OPEN classes a silent drop would otherwise hide:

    - a reader EXCEPTION (the body is not parseable EDN), and
    - a PARSEABLE-but-non-map value (`[…]`, `42`, `\"x\"`, `:k`) — a
      server/client wire-shape regression that `read-string` accepts but
      which `merge-delta!`'s `(map? delta)` guard would silently no-op,
      swapping the resolved HTML + removing the script while leaving app-db
      stale and emitting NO diagnostic (rf2-l3paoi).

  A nil parse (an empty / whitespace-only body) is the documented no-delta
  shape — NOT malformed — and returns nil without a trace, matching the
  prior `(when delta …)` no-op. The bare delta-map EDN body is the shipped
  wire contract (Spec 011 §Hydration interleaving — \"the per-subtree delta
  is shipped as the bare delta-map EDN\"); anything else is the bug."
  [frame-id id-str body]
  (let [parsed (try
                 (reader/read-string body)
                 (catch :default e
                   (malformed-delta! frame-id id-str
                                     (str "Malformed hydration-delta EDN for boundary " id-str)
                                     {:exception (ex-message e)})
                   ::skip))]
    (cond
      (= ::skip parsed) nil
      (nil? parsed)     nil                              ;; no-delta body — not malformed
      (map? parsed)     parsed
      :else
      (do
        (malformed-delta! frame-id id-str
                          (str "Hydration-delta EDN for boundary " id-str
                               " parsed to a non-map (got " (pr-str (type parsed))
                               "); skipped — the delta-map wire contract was violated")
                          {:malformed-value-type (pr-str (type parsed))})
        nil))))

(defn- apply-delta-script!
  "Find the `data-rf2-suspense-hydrate` delta `<script>` for `id-str`,
  read its bare delta-map EDN, merge it into `frame-id`'s app-db, and
  drop the script node. The server emits the delta `<script>`
  immediately after the resolved `<template>`, but DOM order after our
  swap is not guaranteed, so we match by id attribute rather than
  sibling position. A malformed delta (unparseable EDN, or parseable but
  non-map) is skipped with a trace — a bad speculative chunk must not
  break hydration (the final payload is the correctness lock), but it must
  also not silently pass for a successful hydration (rf2-l3paoi)."
  [root frame-id id-str]
  (when-let [script (->> (query-by-attr root attr-suspense-hydrate)
                         (filter #(= id-str (.getAttribute % attr-suspense-hydrate)))
                         first)]
    (when-let [delta (read-delta frame-id id-str (.-textContent script))]
      (merge-delta! frame-id delta))
    ;; The delta is consumed (or skipped); drop the script node so the DOM
    ;; is left in its final, script-free shape regardless of delta validity.
    (when-let [sp (.-parentNode script)]
      (.removeChild sp script))))

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

  `seen` is an atom of the id-strings already SUCCESSFULLY processed so a
  chunk is applied at most once even if the observer fires twice for the
  same node (defensive — MutationObserver batching + the initial sweep
  can both surface the same node). Idempotent.

  rf2-58zvy1 finding 4 — an id is marked `seen` ONLY after a successful
  swap, and the swapped-in content is re-scanned for fallback templates
  before later resolved templates are processed. Two coupled corners for
  a NESTED boundary whose inner chunk is ALREADY present when the client
  installs late:

    1. The inner fallback `<template>` lives INSIDE the outer resolved
       `<template>`'s content, so the one-shot `materialise-fallbacks!`
       at sweep start cannot see it (it is still inert template content,
       not live DOM). It only enters the live tree when the outer mount
       is swapped. If we processed the (already-present) inner resolved
       chunk before its mount existed, `replace-mount-content!` would
       return false. Previously the id was marked seen FIRST, so that
       failed inner swap was skipped permanently and no later sweep could
       recover it → the nested boundary stuck on fallback forever.
       Fix: after a successful outer swap, immediately materialise any
       fallbacks the new content introduced, so the inner mount exists
       before the inner resolved template is processed later in the
       SAME `doseq`.
    2. Mark `seen` only on a successful swap. A resolved template whose
       mount does not exist yet (raced ahead of its fallback) stays
       un-`seen` and is retried on the next sweep instead of being burned.
       Idempotency is still guaranteed: a successful swap REMOVES the
       resolved template node (`replace-mount-content!`), so a re-fire
       cannot re-process it even before consulting `seen`.

  Returns `true` when this call made PROGRESS (a swap happened, or a
  failed-boundary fallback was applied) so the sweep can iterate to a
  fixpoint — a nested inner chunk whose mount only appeared after the
  outer swap is recovered within the same sweep even when document order
  put the inner resolved template before the outer one."
  [root frame-id seen resolved-tmpl]
  (let [id-str  (.getAttribute resolved-tmpl attr-suspense-id)
        failed? (= "1" (.getAttribute resolved-tmpl attr-suspense-failed))]
    (if (and id-str (not (contains? @seen id-str)))
      (let [swapped? (replace-mount-content! root id-str resolved-tmpl)]
        (when swapped?
          ;; Mark seen ONLY on success — an unresolved mount is retryable
          ;; on the next pass rather than permanently skipped (finding 4).
          (swap! seen conj id-str)
          ;; The just-swapped content may carry NESTED fallback templates
          ;; that were inert inside the resolved <template> and are now
          ;; live DOM. Materialise them so a nested resolved chunk found
          ;; later in this same sweep has a mount to swap into (finding 4).
          (materialise-fallbacks! root))
        (cond
          failed?
          (trace/emit-error! :rf.ssr/suspense-boundary-failed
                             {:id        (read-boundary-id id-str)
                              :frame     frame-id
                              :where     'rf.ssr/streaming-client
                              :reason    "Server-side continuation render failed; client swapped the fallback HTML, no delta applied."
                              :recovery  :inline-fallback})
          swapped?
          (apply-delta-script! root frame-id id-str))
        (boolean swapped?))
      false)))

(defn- sweep!
  "One full sweep: materialise any un-mounted fallback `<template>`s into
  live visible mounts, then process every `data-rf2-suspense-resolved`
  `<template>` (swap + delta-merge). Called once on install (chunks that
  streamed in before the bundle ran) and again whenever the observer
  reports new nodes. Fallback materialisation runs FIRST so a resolved
  chunk in the same batch always finds a live mount to swap into.

  rf2-58zvy1 finding 4 — the resolved-processing pass iterates to a
  FIXPOINT. A nested boundary whose inner resolved chunk is already in
  the DOM when the client installs late only gets its live mount AFTER
  the outer mount is swapped (the inner fallback was inert template
  content inside the outer resolved <template>). A single document-order
  pass can therefore visit the inner resolved template before its mount
  exists; gating `seen` on success (above) keeps it retryable, and this
  loop re-runs the pass while any swap is still making progress, so the
  inner chunk lands within the same sweep instead of being stranded on
  fallback until the final payload. The loop terminates because every
  iteration that continues swapped at least one boundary (monotone:
  boundaries only move resolved-ward, never back), and the total boundary
  count is finite."
  [root frame-id seen]
  (materialise-fallbacks! root)
  (loop []
    (let [progress? (reduce (fn [acc t]
                              (or (process-resolved-template! root frame-id seen t)
                                  acc))
                            false
                            (query-by-attr root attr-suspense-resolved))]
      (when progress?
        (recur)))))

(defn- element-by-id
  "Find an element with id `id` under `root` WITHOUT building a raw `#id`
  CSS selector. Per rf2-58zvy1 finding 3 — a `:payload-id` override that
  is a VALID HTML id but contains CSS-selector-significant chars (`.`,
  `:`, `[`, space, …) makes `(.querySelector root (str \"#\" id))` either
  select the wrong element or throw a `SyntaxError`, breaking `install!`.
  HTML ids are matched by exact string, not by CSS grammar, so:

    - Document / DocumentFragment roots have native `getElementById`
      (the same exact-string lookup the DOM-read bootstrap uses) — use it.
    - Element roots have no `getElementById`; scan `[id]`-bearing
      descendants and compare `.id` exactly. `[id]` (presence) is a
      structurally-fixed selector — it never embeds the user-supplied id —
      so it cannot be poisoned by a CSS-special payload-id."
  [root id]
  (if (some? (.-getElementById root))
    (.getElementById root id)
    (->> (array-seq (.querySelectorAll root "[id]"))
         (some #(when (= id (.-id %)) %)))))

(defn- final-payload-present?
  "True once the canonical `__rf_payload` `<script>` has parsed into the
  DOM — the signal that streaming is complete and the observer should
  disconnect (the final `:rf/hydrate` is the reconciliation point;
  deltas after it would race the canonical replace).

  Per rf2-58zvy1 finding 3 — matches the payload by EXACT id string via
  `element-by-id`, never a raw `#id` CSS selector, so a documented
  `:payload-id` override with CSS-special chars cannot throw or mis-match."
  [root payload-id]
  (some? (element-by-id root payload-id)))

;; ---- public surface --------------------------------------------------------

(defn install!
  "Install the client-side streaming-SSR runtime. Watches the document
  (or a supplied root) for resolved-subtree chunks as the chunked
  response streams in; for each, swaps the fallback `<template>` for the
  resolved content in-place and merges the per-subtree app-db delta into
  the target frame. Disconnects once the final `__rf_payload` node lands
  (the bootstrap's `:rf/hydrate` then replaces app-db canonically).

  Opts:

    :frame      — REQUIRED. The target frame id whose app-db receives the
                  deltas — the SAME frame the bootstrap `ssr/hydrate!`s
                  into and the root provider mounts (EP-0002 rf2-acjknb —
                  one carried frame; the streaming target is supplied, not
                  synthesised). An absent `:frame` emits + throws
                  `:rf.error/no-frame-context` (the pre-EP `:rf/default`
                  default is removed).
    :root       — the DOM root to observe + query. Default
                  `js/document`. A test harness passes a detached
                  container so it can drive chunk-arrival deterministically.
    :payload-id — the id of the final-payload `<script>` whose arrival
                  signals stream completion. Default
                  `re-frame.ssr.constants/payload-script-id`
                  (`\"__rf_payload\"`) — the SAME constant
                  `re-frame.ssr.boot/read-server-payload` reads, so the
                  streaming runtime and the non-streaming bootstrap agree
                  on the final-payload id by construction. Accepted as an
                  opt so a host that overrode the shell's payload id can
                  match it.

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
           (streaming-client/install! {:frame :app/main})
           (rdc/render react-root
             [rf/frame-provider-existing {:frame :app/main}
              [(rf/view :app/root)]])
           ;; Reconcile against the canonical payload once it lands.
           ;; (A host typically polls / observes for `__rf_payload`, or
           ;; the streaming bootstrap calls `ssr/hydrate!` on completion.)
           ))"
  ([] (install! {}))
  ([{:keys [frame root payload-id]
     ;; EP-0002 (rf2-acjknb): NO `:or {frame :rf/default}` floor — a nil
     ;; frame is an absent target that `require-frame-stamp!` (below) fails
     ;; closed on, never a synthesised `:rf/default`. `payload-id` defaults
     ;; to the pinned `constants/payload-script-id` (main).
     :or   {root       (when (exists? js/document) js/document)
            payload-id constants/payload-script-id}}]
   ;; EP-0002 (rf2-acjknb): the streaming delta-merge target is CARRIED —
   ;; supplied explicitly via `:frame`. A nil stamp is an absent target,
   ;; not a request to synthesise `:rf/default`; surface the always-on
   ;; `:rf.error/no-frame-context`. Per Spec 002 §Frame target resolution.
   (let [frame (frame/require-frame-stamp!
                 frame :rf.ssr/streaming-install
                 {:where 'rf.ssr.streaming.client/install!})]
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
             stop!)))))))
