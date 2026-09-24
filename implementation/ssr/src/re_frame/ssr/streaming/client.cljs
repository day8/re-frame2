(ns re-frame.ssr.streaming.client
  "Client-side streaming-SSR runtime — the consumer of the server's
  `<template>` / `<script data-rf2-suspense-hydrate>` delta-chunk
  protocol. Per Spec 011 §Streaming SSR client-side hydration semantics.

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
  detached `DocumentFragment` by the HTML spec),
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
  `install!` reconciles by disconnecting its observer once the parser has
  closed the final-payload node, so no stray delta can race the canonical
  state.

  Every chunk element is read only once the parser has CLOSED it
  (`parser-closed?`): the parser inserts a `<script>` or `<template>` at
  its start tag and fills it as bytes arrive, so an element's presence
  says nothing about whether its contents are all there. A fallback
  `<template>` is painted from what has arrived so the skeleton shows at
  once, but is consumed only once closed (`materialise-fallback!`).

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
      so the delta round-trips. (Spec 011 §Hydration
      interleaving specifies this bare shape.)
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

  This namespace is CLJS-only and host-opted-in: it reaches into the DOM and
  installs a `MutationObserver`."
  (:require [cljs.reader :as reader]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.ssr.constants :as rf.ssr.constants]
            ;; The suspense component's render-time failed-boundary record.
            ;; `suspense` depends on core only, never on this ns.
            [re-frame.ssr.suspense :as rf.ssr.suspense]
            [re-frame.ssr.streaming.constants :as rf.ssr.streaming.constants]
            [re-frame.trace :as rf.trace]))

;; ---- wire-shape constants (the attribute names the server stamps) ---------
;;
;; The server↔client streaming wire attribute names live in
;; `re-frame.ssr.streaming.constants` so a rename is a one-edit change
;; there, not a grep-driven sweep across server, client, and tests.
;; These must match the attributes
;; `re-frame.ssr.streaming/{suspense-template,hydrate-delta-script}` stamp —
;; they read from the SAME constants ns, so the agreement is enforced by the
;; shared source rather than a comment. Aliased here so the call sites stay
;; short.

(def ^:private attr-suspense-id        rf.ssr.streaming.constants/attr-suspense-id)
(def ^:private attr-suspense-fallback  rf.ssr.streaming.constants/attr-suspense-fallback)
(def ^:private attr-suspense-resolved  rf.ssr.streaming.constants/attr-suspense-resolved)
(def ^:private attr-suspense-failed    rf.ssr.streaming.constants/attr-suspense-failed)
(def ^:private attr-suspense-hydrate   rf.ssr.streaming.constants/attr-suspense-hydrate)

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
;; but pinned in the shared constants ns alongside the server-emitted
;; attributes.
(def ^:private attr-suspense-mount     rf.ssr.streaming.constants/attr-suspense-mount)
(def ^:private mount-tag               rf.ssr.streaming.constants/mount-tag)

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
  contract that a string id stays a string in trace payloads.
  `(str sym)` faithfully reconstructs the original string for both a bare
  (`card-revenue`) and a slash-bearing (`card/revenue`) string id — `name`
  would drop the namespace segment. Keyword ids (and any other non-symbol
  parse) keep their parsed type. On a parse FAILURE (an exotic string id with
  reader-significant chars) we fall back to the raw attribute string. The id
  is only ever used as a map/DOM-attribute key or a trace payload, never
  evaluated."
  [wire-id-string]
  (let [parsed (try
                 (reader/read-string wire-id-string)
                 (catch :default _ ::fail))]
    (cond
      (= ::fail parsed) wire-id-string
      ;; A bare-token parse is a symbol — the server emitted a STRING id via
      ;; `(str id)`, dropping the quotes; recover the string.
      (symbol? parsed)  (str parsed)
      :else             parsed)))

;; ---- DOM helpers -----------------------------------------------------------

(defn- query-by-attr
  "Return a seq of elements under `root` carrying attribute `attr`
  (presence, any value). `root` is a Document or Element."
  [root attribute-name]
  (array-seq (.querySelectorAll root (str "[" attribute-name "]"))))

(defn- still-parsing?
  "True while the HTML parser may still be writing into `root`: `root` is a
  Document whose `readyState` is `\"loading\"`. Only a Document reports its
  parse state, so an Element root reads false — the test-harness contract,
  where each chunk is appended whole and is complete the moment it exists."
  [root]
  (= "loading" (.-readyState root)))

(defn- parser-closed?
  "True once the HTML parser can no longer be writing into chunk element
  `el`.

  The parser INSERTS a `<script>` or `<template>` at its start tag, then
  fills it — a script's text, a template's `.content` — as network bytes
  arrive, and an observer batch can fall between two reads. Reading such an
  element early gets a prefix: a payload or delta whose EDN fails to parse,
  or a template clone missing its tail while the parser writes that tail
  into the removed original.

  So an element counts as whole once a node FOLLOWS it in document order —
  its own next sibling, or the next sibling of any element it sits inside:
  the parser adds a node after an element only once it has closed it, and
  closing an element closes everything inside it — or once the document has
  finished parsing. The ancestors matter for a fallback `<template>` that is
  the last child of its `<section>`: it never gets a sibling of its own, but
  the next chunk lands after `#app`. The last element on the
  page (usually the payload) has nothing after it, so for that one the end
  of parsing is the signal."
  [root el]
  (or (not (still-parsing? root))
      (loop [node el]
        (cond
          (nil? node)                  false
          (some? (.-nextSibling node)) true
          :else                        (recur (.-parentNode node))))))

(defn- template-content-fragment
  "Materialise a `<template>`'s parsed content as a DocumentFragment
  ready to insert. `<template>` elements expose their parsed children
  via `.content` (a DocumentFragment); we clone it so the source
  template can be removed without detaching the inserted nodes."
  [template]
  (.cloneNode (.-content template) true))

(defn- mounts-for
  "Every live `<rf-suspense data-rf2-suspense-mount=\"<id>\">` wrapper for
  boundary `wire-id-string` under `root`, in document order. A well-formed page has
  exactly one per id; a DUPLICATE-id boundary (a programmer error the server
  surfaces with `:rf.error/suspense-boundary-duplicate-id`, fail-soft
  last-write-wins) yields more than one — one live mount per declared
  boundary, all carrying the same id."
  [root wire-id-string]
  (->> (query-by-attr root attr-suspense-mount)
       (filter #(= wire-id-string (.getAttribute % attr-suspense-mount)))))

(defn- mount-for
  "The swap-target live mount for boundary `wire-id-string` under `root`, or nil.
  Matching is by the id attribute so the swap is exact even across nested
  boundaries. For a DUPLICATE-id boundary the target is the LAST mount in
  document order — the server keeps the LAST registration (last-write-wins,
  Spec 011 §Boundary nesting and recursion) and ships only that
  continuation's resolved chunk, so the resolved content must land in the
  last boundary's position; the earlier mounts keep showing their fallback
  For the common single-id case this is just that one mount."
  [root wire-id-string]
  (last (mounts-for root wire-id-string)))

(def ^:private provisional-mounts
  "Fallback `<template>` → the mount painted from it while the parser was
  still writing it (`materialise-fallback!`). Weak, so an entry
  never outlives its template."
  (js/WeakMap.))

(defn- materialise-fallback!
  "Turn one inert `data-rf2-suspense-fallback` `<template>` into a LIVE
  visible mount. Inserts an `<rf-suspense data-rf2-suspense-mount>`
  wrapper carrying the boundary id, fills it with the template's parsed
  fallback content (so the user sees the skeleton), and removes the
  template (its job — carrying the fallback markup across the wire — is
  done).

  Per-template idempotent by construction: a materialised template is
  REMOVED from the DOM, so a later sweep's `materialise-fallbacks!` query
  cannot re-encounter it; while the parser is still writing it (below), it
  is remembered with its mount in `provisional-mounts`, so a later sweep
  finds that mount rather than making a second. We do NOT short-circuit on
  an existing same-id mount — doing so would collapse a DUPLICATE-id
  boundary's second fallback `<template>` into the first boundary's mount,
  leaving the second template stranded inert in the DOM. Each declared boundary
  gets its OWN visible mount; the resolved chunk later targets the LAST one
  (`mount-for`).

  A template the parser has not yet closed (`parser-closed?`) is painted
  PROVISIONALLY and not consumed. The parser fills a
  `<template>`'s `.content` as bytes arrive, so a fallback split across
  network reads holds only its prefix, and the parser goes on writing the
  rest into the template it holds — into a removed original, had we
  consumed it. So the mount shows the prefix at once (the skeleton is the
  first paint), the template stays in place, and the first sweep after the
  parser has closed it repaints the mount from the WHOLE template and
  consumes it. Until then later sweeps leave the mount alone: a repaint is
  a mutation, which the observer would answer with another sweep, and so
  on for as long as the template stayed open.

  Returns the mount element, or nil if the template carried no id."
  [root fallback-template]
  (when-let [wire-id-string (.getAttribute fallback-template attr-suspense-id)]
    (let [parent      (.-parentNode fallback-template)
          provisional (.get provisional-mounts fallback-template)
          closed?     (parser-closed? root fallback-template)]
      (if provisional
        (do (when closed?
              (set! (.-innerHTML provisional) "")
              (.appendChild provisional (template-content-fragment fallback-template))
              (.delete provisional-mounts fallback-template)
              (when parent
                (.removeChild parent fallback-template)))
            provisional)
        (let [mount (.createElement js/document mount-tag)]
          (.setAttribute mount attr-suspense-mount wire-id-string)
          (.appendChild mount (template-content-fragment fallback-template))
          (when parent
            (.insertBefore parent mount fallback-template)
            (if closed?
              (.removeChild parent fallback-template)
              (.set provisional-mounts fallback-template mount)))
          mount)))))

(defn- materialise-fallbacks!
  "Materialise every un-mounted fallback `<template>` under `root` into a
  live mount. Run on install + on observed additions so a fallback that
  streamed in after install still becomes visible, and so a provisionally
  painted one is repainted whole once the parser has closed it."
  [root]
  (doseq [fallback-template (query-by-attr root attr-suspense-fallback)]
    (materialise-fallback! root fallback-template)))

(defn- replace-mount-content!
  "Replace the live mount's children for `wire-id-string` with the resolved
  `<template>`'s parsed content, in-place. Targets `mount-for` — the LAST
  mount for the id, so a DUPLICATE-id boundary's single resolved chunk
  (the server's last-write-wins registration) lands in the LAST boundary's
  position while earlier mounts keep their fallback. Returns
  true if a mount was found + swapped, false otherwise (a resolved chunk
  with no matching fallback mount yet — e.g. a nested inner chunk racing
  ahead of its mount). The mount wrapper itself stays in the DOM carrying
  its id — harmless, and it keeps the swap target stable."
  [root wire-id-string resolved-template]
  (if-let [mount (mount-for root wire-id-string)]
    (do
      (set! (.-innerHTML mount) "")
      (.appendChild mount (template-content-fragment resolved-template))
      (when-let [resolved-parent (.-parentNode resolved-template)]
        (.removeChild resolved-parent resolved-template))
      true)
    false))

(defn- unwrap-mount!
  "Remove one `<rf-suspense>` mount wrapper, splicing its children into
  the parent at the wrapper's position.

  This is the step that makes a streamed page HYDRATABLE. The mount is
  PROTOCOL DOM — the client-owned swap target, invented by `install!`,
  named by no render tree on any host. Left in place it sits between the
  author's `<section>` and the resolved `<div class=\"card\">`, so React's
  `hydrateRoot` walks the client tree and finds an element the tree never
  described: a structural mismatch on every boundary, on a page whose
  content is otherwise byte-correct.

  Unwrapping restores the DOM the author's tree DOES describe — the same
  shape `render-to-string` produces for the equivalent non-streamed
  tree — so one ordinary whole-root hydration reconciles it.

  Children are moved (not cloned): `insertBefore` relocates a node that
  already has a parent, so the loop drains the mount in document order
  without copying, and node identity is preserved for anything already
  holding a reference."
  [mount]
  (when-let [parent (.-parentNode mount)]
    (loop []
      (when-let [child (.-firstChild mount)]
        (.insertBefore parent child mount)
        (recur)))
    (.removeChild parent mount)))

(defn- unwrap-mounts!
  "Unwrap EVERY live mount under `root`. Runs once, at finalization,
  after the last sweep — so no resolved chunk can arrive to find its swap
  target gone. A boundary still showing its fallback (the server never
  resolved it, or it failed) unwraps too: the fallback markup is what the
  page is left painting, and the client `boundary` component renders that
  same declared fallback for a failed id, so the two agree."
  [root]
  (doseq [mount (query-by-attr root attr-suspense-mount)]
    (unwrap-mount! mount)))

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
    (rf.frame/swap-frame-db! frame-id
                          (fn [app-db]
                            (into app-db delta)))))

;; ---- chunk processing ------------------------------------------------------

(defn- always-on-boundary-failure!
  "Fan a STRUCTURAL-ONLY `:rf.ssr/suspense-boundary-failed` union record onto
  the always-on axis, beside the dev traces below.

  Every caller of this ns runs in the BROWSER, where a streaming-SSR
  boundary can fail in production exactly as it can in dev. The
  `rf.trace/emit-error!` traces beside each call site are DCE'd under
  `:advanced` + `goog.DEBUG=false` — `emit-error!`'s whole body sits inside
  `rf.interop/debug-enabled?` — so without this record a production boundary
  failure would be absorbed fail-closed and reported to NOBODY, the same
  gap the hydration mismatch's always-on record closes in `ssr/hydrate.cljc`.

  STRUCTURAL SLOTS ONLY. This record fans out to corpus listeners (Sentry /
  Datadog) and the frame's `:observability :errors` sinks RAW — it is NOT
  privacy-gated like the dev trace. It carries the boundary id, the frame,
  the `:where` locator and the `:recovery` disposition, which is what names
  WHICH of the three fail-closed arms fired (`:skipped-delta` /
  `:quarantined-delta` / `:inline-fallback`). The branch-specific
  `:reason` prose, the reader `:exception`, and `:malformed-value-type`
  stay on the DCE'd dev trace: the first interpolates the raw wire id and
  the second two are derived from UNTRUSTED wire bytes. No delta, no
  markup, no app-db slice — ever.

  Reached through the published `:error-emit/dispatch-error-record`
  late-bind hook rather than a static require into core's error-emit ns,
  the same route `ssr/hydrate.cljc` and `ssr/boot.cljc` take. A nil hook is
  a silent skip."
  [frame-id boundary-id recovery]
  (when-let [dispatch-error-record!
             (rf.late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-error-record!
      {:error    :rf.ssr/suspense-boundary-failed
       :id       boundary-id
       :frame    frame-id
       :where    'rf.ssr/streaming-client
       :recovery recovery
       :time     (rf.interop/now-ms)}))
  nil)

(defn- malformed-delta!
  "Emit the `:rf.ssr/suspense-boundary-failed` / `:skipped-delta` trace for
  a delta `<script>` whose body did not yield a usable delta-map — EITHER a
  reader exception (unparseable EDN) OR a parseable-but-non-map value (a
  vector, number, string, …). Factored out so both fail-CLOSED branches emit
  the SAME catalogued event (Spec 009 §Error event catalogue —
  `:rf.ssr/suspense-boundary-failed`, `:recovery :skipped-delta`). `extra` is
  merged in to carry the branch-specific field (`:exception` for the reader
  throw, `:malformed-value-type` for a non-map parse)."
  [frame-id wire-id-string reason extra]
  (let [boundary-id (read-boundary-id wire-id-string)]
    ;; Axis 1 — always-on. Structural slots only: the
    ;; branch-specific `extra` (`:exception` / `:malformed-value-type`) is
    ;; derived from UNTRUSTED wire bytes and stays on the dev trace.
    (always-on-boundary-failure! frame-id boundary-id :skipped-delta)
    ;; Axis 2 — the dev-only trace.
    (rf.trace/emit-error! :rf.ssr/suspense-boundary-failed
                       (merge {:id       boundary-id
                               :frame    frame-id
                               :where    'rf.ssr/streaming-client
                               :reason   reason
                               :recovery :skipped-delta}
                              extra))))

(defn- read-delta
  "Parse a delta `<script>`'s bare delta-map EDN body, failing CLOSED on any
  shape that is NOT a usable delta-map. Returns the parsed map when the body
  is a (possibly empty) map, else nil — and emits the malformed-delta trace
  for the two fail-OPEN classes a silent drop would otherwise hide:

    - a reader EXCEPTION (the body is not parseable EDN), and
    - a PARSEABLE-but-non-map value (`[…]`, `42`, `\"x\"`, `:k`) — a
      server/client wire-shape regression that `read-string` accepts but
      which `merge-delta!`'s `(map? delta)` guard would silently no-op,
      swapping the resolved HTML and removing the script while leaving app-db
      stale without a diagnostic.

  A nil parse (an empty / whitespace-only body) is the documented no-delta
  shape — NOT malformed — and returns nil without a trace. The bare
  delta-map EDN body is the shipped
  wire contract (Spec 011 §Hydration interleaving — \"the per-subtree delta
  is shipped as the bare delta-map EDN\"); anything else is the bug."
  [frame-id wire-id-string delta-edn]
  (let [parsed (try
                 (reader/read-string delta-edn)
                 (catch :default e
                   (malformed-delta! frame-id wire-id-string
                                     (str "Malformed hydration-delta EDN for boundary "
                                          wire-id-string)
                                     {:exception (ex-message e)})
                   ::skip))]
    (cond
      (= ::skip parsed) nil
      (nil? parsed)     nil                              ;; no-delta body — not malformed
      (map? parsed)     parsed
      :else
      (do
        (malformed-delta! frame-id wire-id-string
                          (str "Hydration-delta EDN for boundary " wire-id-string
                               " parsed to a non-map (got " (pr-str (type parsed))
                               "); skipped — the delta-map wire contract was violated")
                          {:malformed-value-type (pr-str (type parsed))})
        nil))))

(defn- quarantined-failed-delta!
  "Emit the `:rf.ssr/suspense-boundary-failed` / `:quarantined-delta` trace for
  a hydrate-delta `<script>` whose boundary the server flagged FAILED. The
  shipped server emits NO delta for a failed continuation (Spec 011 §Failure
  semantics — inline fallback), so a delta matching a `:failed` boundary is a
  contradictory / duplicated / reordered wire shape. We fail CLOSED: the delta
  is consumed (its script dropped by the caller) but NEVER merged, so an
  explicit failed continuation cannot inject speculative state — the final
  `__rf_payload` `:rf/hydrate` stays the correctness lock. One bounded
  diagnostic per contradictory script (the script is dropped, so no re-emit on
  a later sweep). Reuses the catalogued `:rf.ssr/suspense-boundary-failed`
  event with a distinct `:recovery :quarantined-delta` disposition — the
  sibling of `malformed-delta!`'s `:skipped-delta` and the swap-time
  `:inline-fallback`."
  [frame-id wire-id-string]
  ;; Axis 1 — always-on. The `:reason` below interpolates the
  ;; raw `wire-id-string`, so it stays on the DCE'd dev trace; the record
  ;; carries the READ boundary id and the recovery disposition alone.
  (always-on-boundary-failure! frame-id
                               (read-boundary-id wire-id-string)
                               :quarantined-delta)
  ;; Axis 2 — the dev-only trace.
  (rf.trace/emit-error! :rf.ssr/suspense-boundary-failed
                     {:id       (read-boundary-id wire-id-string)
                      :frame    frame-id
                      :where    'rf.ssr/streaming-client
                      :reason   (str "Hydration-delta script present for boundary "
                                     wire-id-string
                                      " which the server flagged FAILED; a failed continuation "
                                      "carries no delta — quarantined without merging "
                                      "(contradictory/reordered wire shape)")
                      :recovery :quarantined-delta}))

(defn- apply-ready-deltas!
  "Apply, quarantine, or defer every `data-rf2-suspense-hydrate` delta
  `<script>` by its boundary's recorded SWAP OUTCOME (`@boundary-outcomes` maps
  `wire-id-string → :resolved | :failed`), independent of whether the resolved
  `<template>` is still in the DOM. Matched by id attribute (not sibling
  position) — DOM order after a swap is not guaranteed.

    - `:resolved` — a SUCCESSFUL swap authorizes the delta: read its bare
      delta-map EDN and merge it into `frame-id`'s app-db, then drop the
      script. A malformed delta (unparseable EDN, or parseable but non-map)
      is skipped with a trace — a bad speculative chunk must not break
      hydration (the final payload is the correctness lock), but it must also
      not silently pass for a successful hydration.
    - `:failed` — the server flagged this boundary FAILED and emits no delta
      for it (Spec 011 §Failure semantics — inline fallback). A matching
      delta is therefore a contradictory / duplicated / reordered stream:
      QUARANTINE it (never merged) with one bounded diagnostic, then drop the
      script. This is the fail-closed rule — an
      undifferentiated `seen` set would merge a failed
      boundary's delta.
    - not yet swapped (`nil` outcome) — a delta racing ahead of its template:
      LEFT in the DOM (not consumed) for a future sweep to reclassify.
    - still being parsed (`parser-closed?` false) — LEFT in the DOM whatever
      its outcome. Its text so far is a prefix of the delta, which would
      fail to parse and be dropped.

  This decouples delta handling from the resolved-template's transient
  presence. The server flushes the resolved `<template>` and its delta
  `<script>` as TWO SEPARATE chunks — each `.flush`ed (see
  `re-frame.ssr.ring.streaming/write-chunk!`) — so the client's HTML parser
  can surface them in SEPARATE `MutationObserver` batches, in EITHER order:

    - template first, delta later: the template is swapped + REMOVED + its
      outcome recorded in an earlier sweep; scanning the delta `<script>`s
      here (rather than only as a side effect of processing the — now gone —
      template) resolves the delta when it lands in a later sweep. Without
      this scan a successful boundary's delta would be orphaned and its
      `(into existing delta)` merge LOST until the final `__rf_payload`
      self-heals it.
    - delta first, template later: a delta whose boundary has NOT swapped yet
      (no outcome) is LEFT in the DOM for a future sweep; a later sweep's swap
      records the outcome, and the delta is then applied (`:resolved`) or
      quarantined (`:failed`) accordingly — order-independent.

  Idempotent by script removal: a consumed (applied OR quarantined) delta
  `<script>` is dropped from the DOM, so no later sweep can re-process it —
  mirroring the swap's own once-only-by-node-removal guarantee, so no separate
  `delta-applied` set is needed."
  [root frame-id boundary-outcomes]
  (doseq [script (query-by-attr root attr-suspense-hydrate)
          :when  (parser-closed? root script)]
    (let [wire-id-string (.getAttribute script attr-suspense-hydrate)
          outcome        (get @boundary-outcomes wire-id-string)]
      ;; `:resolved` and `:failed` both CONSUME the script (drop it) so it
      ;; cannot be reconsidered on a later sweep; a nil outcome (boundary not
      ;; swapped yet — a delta racing ahead of its template) leaves it for a
      ;; future sweep to reclassify.
      (when (some? outcome)
        (case outcome
          :resolved (when-let [delta (read-delta frame-id wire-id-string
                                                 (.-textContent script))]
                      (merge-delta! frame-id delta))
          :failed   (quarantined-failed-delta! frame-id wire-id-string))
        ;; The delta is consumed (merged, skipped, or quarantined); drop the
        ;; script so the DOM is left in its final, script-free shape.
        (when-let [script-parent (.-parentNode script)]
          (.removeChild script-parent script))))))

(defn- process-resolved-template!
  "Process one resolved-subtree `<template>` (carrying
  `data-rf2-suspense-resolved`): swap the live mount's content for the
  resolved HTML and record the boundary outcome. The matching per-subtree
  hydration-delta `<script>` is applied SEPARATELY by `apply-ready-deltas!`
  at the sweep level (not here), so a delta arriving in a later observer
  batch than this template is still merged. A
  `data-rf2-suspense-failed` chunk carries the fallback HTML (which still
  swaps in, so the author's declared loading state replaces the streaming
  placeholder) and NO delta; it surfaces `:rf.ssr/suspense-boundary-failed`
  for observability without a 500 (Spec 011 §Failure semantics — inline
  fallback).

  `boundary-outcomes` is an atom MAP of `wire-id-string → outcome` (`:resolved` for a successful
  content swap, `:failed` for a failed-boundary fallback swap) recording every
  boundary already processed, so a chunk is applied at most once even if the
  observer fires twice for the same node (defensive — MutationObserver
  batching + the initial sweep can both surface the same node). The outcome is
  what lets `apply-ready-deltas!` authorize a delta only for a `:resolved`
  boundary while quarantining one matching a `:failed` boundary.
  Idempotent.

  An id is recorded in `boundary-outcomes` only after a successful
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
       return false; recording the id before the swap would then skip that
       failed inner swap permanently, and no later sweep could
       recover it → the nested boundary stuck on fallback forever.
       So after a successful outer swap, any
       fallbacks the new content introduced are materialised at once, so
       the inner mount exists
       before the inner resolved template is processed later in the
       SAME `doseq`.
    2. Record an outcome only on a successful swap. A resolved template whose
       mount does not exist yet (raced ahead of its fallback) has no
       outcome, so it is retried on the next sweep instead of being burned.
       Idempotency is still guaranteed: a successful swap REMOVES the
       resolved template node (`replace-mount-content!`), so a re-fire
       cannot re-process it even before consulting `boundary-outcomes`.

  Returns `true` when this call made PROGRESS (a swap happened, or a
  failed-boundary fallback was applied) so the sweep can iterate to a
  fixpoint — a nested inner chunk whose mount only appeared after the
  outer swap is recovered within the same sweep even when document order
  put the inner resolved template before the outer one."
  [root frame-id boundary-outcomes resolved-template]
  (let [wire-id-string (.getAttribute resolved-template attr-suspense-id)
        failed?        (= "1" (.getAttribute resolved-template
                                              attr-suspense-failed))]
    (if (and wire-id-string
             (not (contains? @boundary-outcomes wire-id-string)))
      (let [swapped? (replace-mount-content! root wire-id-string
                                             resolved-template)]
        (when swapped?
          ;; Record the OUTCOME ONLY on success — an unresolved mount is
          ;; retryable on the next pass rather than permanently skipped.
          ;; The outcome (`:resolved` vs `:failed`) is what gates
          ;; delta application: a `:failed` boundary's delta is quarantined,
          ;; not merged.
          (swap! boundary-outcomes assoc wire-id-string
                 (if failed? :failed :resolved))
          ;; The just-swapped content may carry NESTED fallback templates
          ;; that were inert inside the resolved <template> and are now
          ;; live DOM. Materialise them so a nested resolved chunk found
          ;; later in this same sweep has a mount to swap into.
          (materialise-fallbacks! root))
        ;; The failed-boundary trace is gated on swap success.
        ;; The failed content (the author's fallback HTML) only lands when
        ;; `replace-mount-content!` finds a live mount, so emitting exactly
        ;; when the swap happens is both correct and exactly-once: the outcome is
        ;; recorded only on a successful swap and the swapped-in <template> is
        ;; removed, so a re-fire cannot re-process it. Firing the failed
        ;; arm regardless of `swapped?` would let a resolved-failed
        ;; <template> swept before its mount existed (a nested-boundary race
        ;; / out-of-order stream), which has no outcome, RE-EMIT the trace
        ;; on every MutationObserver re-sweep — multi-emit.
        ;;
        ;; A non-failed boundary's DELTA is NOT applied here — it is handled
        ;; by `apply-ready-deltas!` at the sweep level so it survives arriving
        ;; in a later observer batch than this template.
        (when (and failed? swapped?)
          ;; Axis 1 — always-on. A server continuation that
          ;; failed and was swapped for its fallback is a production-visible
          ;; degradation, so it must survive `goog.DEBUG=false`.
          (always-on-boundary-failure! frame-id
                                       (read-boundary-id wire-id-string)
                                       :inline-fallback)
          ;; Axis 2 — the dev-only trace.
          (rf.trace/emit-error! :rf.ssr/suspense-boundary-failed
                             {:id        (read-boundary-id wire-id-string)
                              :frame     frame-id
                              :where     'rf.ssr/streaming-client
                              :reason    "Server-side continuation render failed; client swapped the fallback HTML, no delta applied."
                              :recovery  :inline-fallback}))
        (boolean swapped?))
      false)))

(defn- sweep!
  "One full sweep: materialise any un-mounted fallback `<template>`s into
  live visible mounts, process every `data-rf2-suspense-resolved`
  `<template>` the parser has closed (swap the mount + record its outcome;
  a template still being parsed waits for a later sweep, `parser-closed?`),
  then apply every ready
  per-subtree delta `<script>` (`apply-ready-deltas!`). Called once on
  install (chunks that streamed in before the bundle ran) and again whenever
  the observer reports new nodes. Fallback materialisation runs FIRST so a
  resolved chunk in the same batch always finds a live mount to swap into;
  delta application runs LAST so every boundary swapped this sweep has an
  outcome before its delta is scanned for.

  Swap and delta application are DECOUPLED: the server flushes a boundary's
  resolved `<template>` and its delta `<script>` as two separate chunks that
  the parser can surface in separate observer batches, so applying the delta
  only as a side effect of the swap would drop it whenever the delta lands in
  a later batch than its (already-swapped, already-removed) template. Running
  an outcome-gated delta scan every sweep makes delta application retryable
  across sweeps just like the swap.

  The resolved-processing pass iterates to a fixpoint. A nested boundary whose
  inner resolved chunk is already in
  the DOM when the client installs late only gets its live mount AFTER
  the outer mount is swapped (the inner fallback was inert template
  content inside the outer resolved <template>). A single document-order
  pass can therefore visit the inner resolved template before its mount
  exists; recording outcomes only on success (above) keeps it retryable, and
  this loop re-runs the pass while any swap is still making progress, so the
  inner chunk lands within the same sweep instead of being stranded on
  fallback until the final payload. The loop terminates because every
  iteration that continues swapped at least one boundary (monotone:
  boundaries only move resolved-ward, never back), and the total boundary
  count is finite."
  [root frame-id boundary-outcomes]
  (materialise-fallbacks! root)
  (loop []
    (let [progress? (reduce (fn [made-progress? resolved-template]
                              (or (process-resolved-template!
                                    root frame-id boundary-outcomes resolved-template)
                                  made-progress?))
                            false
                            (filter #(parser-closed? root %)
                                    (query-by-attr root attr-suspense-resolved)))]
      (when progress?
        (recur))))
  ;; Apply any per-subtree deltas whose boundary has now swapped in. Runs
  ;; AFTER the swap fixpoint (so every boundary swapped this sweep has an
  ;; outcome) and independent of resolved-`<template>` presence (so a delta
  ;; arriving in a later batch than its template — the two are separate flushed
  ;; chunks — is still merged).
  (apply-ready-deltas! root frame-id boundary-outcomes))

(defn- element-by-id
  "Find an element with id `id` under `root` without building a raw `#id`
  CSS selector. A `:payload-id` override that
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

(defn- stream-complete?
  "True once the parser has CLOSED the canonical `__rf_payload` `<script>` —
  the signal that streaming is complete and the observer should disconnect
  (the final `:rf/hydrate` is the reconciliation point; deltas after it
  would race the canonical replace). The element's presence is not enough:
  it exists from its start tag, holding only the EDN parsed so far, and a
  bootstrap hydrating from it would refuse it as malformed
  (`parser-closed?`).

  Matches the payload by exact id string via
  `element-by-id`, never a raw `#id` CSS selector, so a documented
  `:payload-id` override with CSS-special chars cannot throw or mis-match."
  [root payload-id]
  (if-let [payload (element-by-id root payload-id)]
    (parser-closed? root payload)
    false))

(defn- readiness-report
  "The outcome summary handed to `:on-ready` — `{:resolved #{ids} :failed
  #{ids}}` over the boundary ids this runtime actually processed, with
  ids parsed back to the values the author wrote. Empty sets on a page
  that carried no boundaries."
  [boundary-outcomes]
  (reduce-kv (fn [readiness-report wire-id-string outcome]
               (update readiness-report
                       (case outcome :failed :failed :resolved)
                       conj
                       (read-boundary-id wire-id-string)))
             {:resolved #{} :failed #{}}
             @boundary-outcomes))

(defn- finalize!
  "The one-time FINALIZATION step, run once the parser has closed the
  final `__rf_payload` (or had already closed it at install time).

  Spec 011 pins the streaming protocol as progressive PRE-HYDRATION PAINT
  followed by ONE ordinary whole-root hydration. Finalization is the
  boundary between those two phases, and it must leave the DOM carrying
  nothing but the application tree:

    1. `sweep!` — process any chunk still pending (a resolved
       `<template>` that landed in the same observer batch as the
       payload), which also records its outcome and consumes or
       quarantines its delta.
    2. `unwrap-mounts!` — remove every `<rf-suspense>` wrapper. This is
       the structural fix: protocol DOM is transport, never part of the
       application tree.
    3. `stop!` — disconnect, so no late mutation can race the canonical
       `:rf/hydrate` replace.
    4. `on-ready` — hand the caller its readiness signal, ONCE.

  Ordering is load-bearing. Sweeping after unwrapping would leave a
  resolved chunk with no mount to swap into; unwrapping after
  disconnecting would be fine but pointlessly late; and firing
  `on-ready` before the unwrap would hand the bootstrap a DOM that still
  carries wrappers — the exact tree `hydrateRoot` cannot match.

  `ready?` is the once-only latch: a `compare-and-set!` on it, not a
  boolean read, so an observer batch that races the initial sweep cannot
  fire `on-ready` twice."
  [root frame-id boundary-outcomes ready? stop! on-ready]
  (sweep! root frame-id boundary-outcomes)
  (let [report (readiness-report boundary-outcomes)]
    ;; Publish the failed set BEFORE unwrapping / readiness: the
    ;; `boundary` component reads it during the hydration render that
    ;; `on-ready` triggers, so it has to be in place by then.
    (rf.ssr.suspense/record-failed-boundaries! (:failed report))
    (unwrap-mounts! root)
    (stop!)
    (when (compare-and-set! ready? false true)
      (when on-ready
        (on-ready report)))))

;; ---- public surface --------------------------------------------------------

(defn install!
  "Install the client-side streaming-SSR runtime. Watches the document
  (or a supplied root) for resolved-subtree chunks as the chunked
  response streams in; for each, swaps the fallback `<template>` for the
  resolved content in-place and merges the per-subtree app-db delta into
  the target frame. Disconnects once the parser has closed the final
  `__rf_payload` node (the bootstrap's `:rf/hydrate` then replaces app-db
  canonically). A chunk element is read only once the parser has closed
  it; see `parser-closed?`.

  Opts:

    :frame      — REQUIRED. The target frame id whose app-db receives the
                  deltas — the SAME frame the bootstrap `ssr/hydrate!`s
                  into and the root provider mounts. The streaming target is supplied, not
                  synthesised. An absent `:frame` emits + throws
                  `:rf.error/no-frame-context`; there is no `:rf/default`
                  default.
    :root       — the DOM root to observe + query. Default
                  `js/document`. A test harness passes a detached
                  container so it can drive chunk-arrival deterministically.
                  Only a Document root reports whether the parser is still
                  writing into it, so an Element root is taken to receive
                  each chunk whole, as a harness appends it.
    :payload-id — the id of the final-payload `<script>` whose arrival
                  signals stream completion. Default
                  `re-frame.ssr.constants/payload-script-id`
                  (`\"__rf_payload\"`) — the SAME constant
                  `re-frame.ssr.boot/read-server-payload` reads, so the
                  streaming runtime and the non-streaming bootstrap agree
                  on the final-payload id by construction. Accepted as an
                  opt so a host that overrode the shell's payload id can
                  match it.
    :on-ready   — 1-arity fn called EXACTLY ONCE when the stream has
                  FINALISED: every chunk processed, every delta consumed
                  or quarantined, every `<rf-suspense>` mount unwrapped,
                  the observer disconnected. Receives a readiness report
                  `{:resolved #{ids} :failed #{ids}}`.

                  This is the hydration trigger. A streaming bootstrap
                  calls `ssr/hydrate!` and the adapter's `hydrate-root`
                  from HERE — not on a timer, not by polling the DOM for
                  `__rf_payload`, and above all not by falling through to
                  `create-root` because the payload has not landed yet.
                  Before readiness the streamed root is server-painted
                  markup carrying protocol DOM and no framework handlers;
                  taking React ownership of it early is the
                  create-root/hydrate-root race a live stream exposes.
                  Fires SYNCHRONOUSLY during `install!` when the parser
                  had already closed the payload before the bundle booted
                  (the common fast-page case, and always so for a
                  deferred or module bundle or a bundle `<script>` placed
                  after the payload), so the callback must not assume a
                  later tick.

  Returns a 0-arity `stop!` fn that disconnects the observer early (so a
  host can tear the runtime down on its own schedule — e.g. an SPA
  navigation that abandons the stream). The runtime also auto-disconnects
  once the final payload is complete, so most hosts never call it.
  Calling `stop!` early ABANDONS the stream: finalization does not run
  and `:on-ready` never fires.

  Idempotent per chunk: the same resolved node is applied at most once
  even if the observer batches or the initial sweep races a mutation.

  Usage (streaming-aware Reagent-slim bootstrap, readiness-driven):

      ;; (:require [re-frame.core :as rf]
      ;;           [re-frame.ssr :as ssr]
      ;;           [re-frame.ssr.streaming.client :as streaming-client]
      ;;           [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
      ;;           [reagent2.dom.client :as rdc])
      (defn ^:export run []
        (rf/init! reagent-slim-adapter/adapter)
        ;; The live target frame exists FIRST: deltas merge into it as
        ;; chunks land, and `hydrate!` seeds it at readiness.
        (rf/make-frame {:id :app/main :platform :client})
        ;; Bind everything `:on-ready` touches BEFORE `install!` — on an
        ;; already-buffered response it fires synchronously INSIDE it.
        (let [container (js/document.getElementById \"app\")]
          ;; Install early: the observer catches chunks as they stream in,
          ;; and the initial sweep covers any that landed before the bundle.
          (streaming-client/install!
            {:frame    :app/main
             :on-ready (fn [_readiness-report]
                         ;; Finalised: chunks applied, wrappers unwrapped,
                         ;; observer disconnected. Now, and only now, seed
                         ;; from the canonical payload and adopt the DOM.
                         (ssr/hydrate! {:frame :app/main})
                         (rdc/hydrate-root container
                           [rf/frame-provider {:frame :app/main}
                            [(rf/view :app/root)]]))})))

  Nothing touches the streamed root before `:on-ready`: no `create-root`,
  no early `hydrate-root`, no polling for `__rf_payload`. Keep the returned
  `stop!` only if the host may abandon the stream (see above)."
  ([] (install! {}))
  ([{:keys [frame root payload-id on-ready]
     ;; No implicit default: a nil frame is an absent target that
     ;; `require-frame-stamp!` fails
     ;; closed on, never a synthesised `:rf/default`. `payload-id` defaults
     ;; to the pinned `constants/payload-script-id` (main).
     :or   {root       (when (exists? js/document) js/document)
            payload-id rf.ssr.constants/payload-script-id}}]
   ;; The streaming delta-merge target is supplied explicitly via `:frame`.
   ;; A nil stamp is an absent target,
   ;; not a request to synthesise `:rf/default`; surface the always-on
   ;; `:rf.error/no-frame-context`. Per Spec 002 §Frame target resolution.
   (let [frame-id (rf.frame/require-frame-stamp!
                    frame :rf.ssr/streaming-install
                    {:where 'rf.ssr.streaming.client/install!})]
     ;; No DOM (a non-browser runtime / a host calling install! too early)
     ;; → no-op stop fn. The runtime is a DOM consumer by definition.
     (if (or (nil? root) (not (exists? js/MutationObserver)))
       (fn no-op-stop! [])
       (let [boundary-outcomes (atom {}) ;; wire-id-string → :resolved | :failed
             observer          (atom nil)
             parsed-listener   (atom nil) ;; the DOMContentLoaded listener, while armed
             ready?            (atom false) ;; once-only finalization latch
             stop!             (fn stop! []
                                 (when-let [observer-instance @observer]
                                   (.disconnect observer-instance)
                                   (reset! observer nil))
                                 (when-let [listener @parsed-listener]
                                   (.removeEventListener root "DOMContentLoaded" listener)
                                   (reset! parsed-listener nil)))
             finish!           (fn finish! []
                                 (finalize! root frame-id boundary-outcomes
                                            ready? stop! on-ready))
             on-change
             (fn on-change []
               ;; A cheap full re-sweep on any mutation is correct + simple:
               ;; `boundary-outcomes` makes re-processing idempotent, and the
               ;; resolved-chunk count over a page's lifetime is small (one per
               ;; suspense boundary). Walking the mutation records to find only
               ;; the added resolved templates is a micro-opt that adds tree-
               ;; walking complexity for no measurable win at these cardinalities.
               (sweep! root frame-id boundary-outcomes)
               ;; The payload is the LAST chunk: once the parser has closed
               ;; it the stream is done, so run finalization (which re-sweeps
               ;; for anything in this same batch, unwraps the protocol DOM,
               ;; disconnects, and signals readiness).
               (when (stream-complete? root payload-id)
                 (finish!)))]
         ;; Initial sweep — chunks that streamed in before this bundle
         ;; executed (the common case: the shell + several cards land while
         ;; main.js downloads + boots). Materialises fallbacks into visible
         ;; mounts + applies any resolved chunks already present.
         (sweep! root frame-id boundary-outcomes)
         (if (stream-complete? root payload-id)
           ;; Stream already complete by the time we installed — the whole
           ;; response buffered before the bundle booted. There is nothing
           ;; to observe, but finalization still MUST run: the initial
           ;; sweep materialised fallbacks into `<rf-suspense>` mounts, and
           ;; leaving them would hand the bootstrap an unhydratable DOM.
           ;; `:on-ready` fires synchronously here — the fast-page path a
           ;; readiness-driven bootstrap must not hang on.
           (do (finish!)
               (fn already-complete-stop! []))
           (let [observer-instance (js/MutationObserver.
                                     (fn [_mutations _observer] (on-change)))]
             (reset! observer observer-instance)
             ;; Observe the whole subtree: resolved `<template>`s + the
             ;; final payload `<script>` arrive as descendant additions of
             ;; `<body>` / `#app` as the response streams.
             (.observe observer-instance root #js {:childList true :subtree true})
             ;; The last element the parser writes has no node after it, so
             ;; only the end of parsing closes it — and the end of parsing
             ;; mutates nothing. Re-sweep on DOMContentLoaded too.
             (when (still-parsing? root)
               (let [listener (fn on-parsed [_event] (on-change))]
                 (reset! parsed-listener listener)
                 (.addEventListener root "DOMContentLoaded" listener)))
             stop!)))))))
