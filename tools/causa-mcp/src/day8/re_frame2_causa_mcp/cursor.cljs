(ns day8.re-frame2-causa-mcp.cursor
  "Wire-pipeline mechanism W-3 at the Causa-MCP boundary (rf2-8xzoe.7).
  Cursor pagination — per
  `tools/causa-mcp/spec/004-Wire-Pipeline.md` §3 (Cursor pagination).

  ## What this provides

  Sequence-returning tools (`get-trace-buffer`, `get-epoch-history`,
  `list-subscriptions`, and any read tool whose return size is a
  function of trace-bus depth) MUST accept `:cursor` (opaque
  server-managed string, omitted on the first call) and `:limit`
  (integer, default chosen so the response fits the cap).

  Responses MUST carry `:next-cursor` (an opaque string for the
  next page, or `nil` when exhausted) and `:remaining` (count or
  estimate).

  ## Opaque cursor encoding

  Per spec/Principles.md §Pagination: cursors are opaque on the
  wire. The agent has no business decoding the format; it MUST pass
  the value back verbatim. We use base64-encoded EDN as the codec
  — same shape pair2-mcp's `tools.cursor` ships, so any future
  cross-MCP tooling that inspects cursors (it shouldn't, but if it
  did) sees an identical shape.

  The cursor payload is a small EDN map. The shape (today; subject
  to change behind the opaque boundary) is:

      {:v        1
       :after-id <epoch-id-string>     ; the last record we emitted
       :until-ms <int-or-nil>          ; first-call clock; bounds the
                                       ; window so fresh records don't
                                       ; sneak in mid-page
       :frame    <keyword-or-nil>}

  Encoding is `pr-str` → base64 (via `js/Buffer`). Round-trip is
  via `decode-cursor`.

  ## Cursor staleness — :rf.mcp/cursor-stale

  The runtime's epoch ring is a bounded buffer. If a cursor's
  `:after-id` no longer exists in the ring (because enough records
  landed between calls that the buffer rotated past it), the
  server returns a structured error rather than silently restarting
  or shipping an out-of-order batch:

      {:ok?            false
       :reason         :rf.mcp/cursor-stale
       :tool           \"<tool-name>\"
       :requested-id   <id>
       :head-id        <current-head>
       :hint           \"...\"}

  The `:reason` value is the cross-MCP convention from
  `re-frame.mcp-base.vocab/cursor-stale-reason`. Agents pattern-
  match on it to either restart (drop the cursor) or widen the
  window via the tool-specific args.

  ## Composition with W-1 / W-6 / B-1

  - **With W-1 (token-cap)**: pagination is the primary defence
    against unbounded sequence responses. `:limit`'s default is
    chosen to fit the cap on typical record sizes; agents that
    overflow anyway hit W-1's `apply-cap` with `:hint :paginate`
    (re-call with a smaller `:limit`).
  - **With W-6 (size-elision)**: each record's tree-typed slots
    are already walked server-side. The cursor wrapper rides
    above the walked items; W-6's `apply-to-result` counts markers
    across the kept slice and stamps `:elided-large`. Both
    wrappers compose freely.
  - **With B-1 (privacy)**: trace-stream tools that paginate
    (`get-trace-buffer`, `subscribe` drain, `get-epoch-history`)
    apply B-1's strip BEFORE pagination so the per-page item
    count reflects the post-strip slice. The two wrappers' counts
    surface together on the same envelope.

  ## MUSTs honoured

  - MUST 10 — sequence-returning tools accept `:cursor` + `:limit`
    (spec/004 §3 L274-281). `cursor-arg` + `limit-arg` are the
    single normative parsers; per-tool dispatchers call them once
    on the raw args object.
  - MUST 11 — responses carry `:next-cursor` (or nil) + `:remaining`
    (spec/004 §3 L283-284). `apply-to-result` is the single
    normative envelope-shaper; it splices both slots."
  (:require [applied-science.js-interop :as j]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame.mcp-base.args :as base-args]
            [re-frame.mcp-base.vocab :as base-vocab]))

;; ---------------------------------------------------------------------------
;; Defaults — limit floor / cap aligned with W-1's 5K-token budget.
;; ---------------------------------------------------------------------------

(def ^:const default-limit
  "Default `:limit` MCP arg. 50 records per page — sized to fit a
  5K-token cap (W-1) after diff-encode (rf2-1wdzp) and dedup (W-5)
  on typical epoch / trace shapes.

  Identical to pair2-mcp's `tools.cursor/default-limit` so an agent
  that pages through pair2-mcp's surface sees the same per-page
  count on causa-mcp's sibling tools. Cross-server symmetry per
  spec/004 §Cross-server vocabulary."
  50)

(def ^:const min-limit
  "Lower clamp on the per-call `:limit` override. Below 1 is
  nonsensical (an empty page contradicts pagination). Pin the floor
  so a caller passing `0` or a negative collapses to `1` (smallest
  valid page) rather than disappearing into a paging dead-end."
  1)

;; ---------------------------------------------------------------------------
;; limit-arg — per-call MCP-arg resolver.
;; ---------------------------------------------------------------------------

(defn parse-limit-arg
  "Normalise a raw `:limit` value into a positive integer. Defaults
  to `default-limit` (50). Delegates to
  `re-frame.mcp-base.args/parse-positive-int` (rf2-vw4sq) so the
  accept-shape contract (integer / numeric-string / nil) stays
  cross-MCP identical."
  [raw]
  (base-args/parse-positive-int raw default-limit))

(defn limit-arg
  "Resolve the cross-server `:limit` MCP arg from a raw arguments
  object. Returns a positive integer ≥ `min-limit`.

  Accepts both the JS-side args object (the npm MCP SDK shape) and
  a CLJS map (already-coerced upstream). The slot name is the
  cross-server `limit` (string key for JS, `:limit` for CLJS) per
  spec/004 §3.

  Unrecognised / absent inputs collapse to `default-limit`."
  [args]
  (let [raw (cond
              (or (nil? args) (undefined? args))
              nil

              (map? args)
              (or (get args :limit)
                  (get args "limit"))

              :else
              (let [v (j/get args "limit")]
                (when-not (or (nil? v) (undefined? v)) v)))]
    (parse-limit-arg raw)))

;; ---------------------------------------------------------------------------
;; cursor encode / decode — opaque round-trip.
;; ---------------------------------------------------------------------------

(defn encode-cursor
  "Encode a cursor payload as a base64 string. Returns `nil` on a
  nil / empty payload (signalling pagination over) or on a payload
  missing the required `:after-id` slot.

  The payload is `pr-str`'d to EDN and base64-encoded via
  `js/Buffer`. The agent passes the resulting string back verbatim
  on the next call; the encoding is an implementation detail
  behind the opaque cursor boundary."
  [payload]
  (when (and (map? payload) (some? (:after-id payload)))
    (let [edn (pr-str payload)
          buf (js/Buffer.from edn "utf8")]
      (.toString buf "base64"))))

(defn decode-cursor
  "Decode a base64 cursor back to its EDN payload. Returns `nil`
  when the cursor is absent (first call); returns `::malformed`
  when the cursor exists but doesn't decode to a valid payload
  map.

  Callers translate `::malformed` into the same `:rf.mcp/cursor-
  stale` error a runtime age-out raises — a cursor that doesn't
  decode is, in effect, stale (the bound it referred to is no
  longer reachable)."
  [s]
  (cond
    (or (nil? s) (undefined? s)) nil
    (not (string? s)) ::malformed
    (str/blank? s)    nil
    :else
    (try
      (let [buf (js/Buffer.from s "base64")
            edn (.toString buf "utf8")
            v   (cljs.reader/read-string edn)]
        (if (and (map? v) (string? (:after-id v)))
          v
          ::malformed))
      (catch :default _ ::malformed))))

(defn cursor-arg
  "Resolve the cross-server `:cursor` MCP arg from a raw arguments
  object and decode it. Returns a CLJS map (the cursor payload),
  `nil` (first-call / absent), or `::malformed` (the cursor exists
  but doesn't decode — caller treats as stale).

  Accepts both the JS-side args object (the npm MCP SDK shape) and
  a CLJS map. The slot name is the cross-server `cursor` (string
  key for JS, `:cursor` for CLJS) per spec/004 §3.

  Unrecognised / absent inputs collapse to `nil`."
  [args]
  (let [raw (cond
              (or (nil? args) (undefined? args))
              nil

              (map? args)
              (or (get args :cursor)
                  (get args "cursor"))

              :else
              (let [v (j/get args "cursor")]
                (when-not (or (nil? v) (undefined? v)) v)))]
    (decode-cursor raw)))

;; ---------------------------------------------------------------------------
;; cursor-stale-result — the structured error per :rf.mcp/cursor-stale.
;; ---------------------------------------------------------------------------

(defn cursor-stale-result
  "Build the structured cursor-stale error result.

  Per spec/004 §3 + `re-frame.mcp-base.vocab/cursor-stale-reason`
  (`:rf.mcp/cursor-stale`) — the cross-MCP convention agents
  pattern-match on. The agent's recovery is to drop the cursor
  and restart, or widen the window to recover the missed records.

  Arguments:
    - `tool`         — string tool name. Carried on the result so
                       the agent's recovery branch knows which
                       call to retry.
    - opts:
      - `:requested-id` — the cursor's `:after-id` that aged out
                          (or that the malformed cursor referred
                          to). Optional; omitted when not known.
      - `:head-id`      — the runtime ring's current head id.
                          Optional; omitted when not known.

  Returns a map suitable for direct return from a tool dispatcher
  (the envelope itself; no `value-key` slot is meaningful on a
  cursor-stale response)."
  [tool {:keys [requested-id head-id]}]
  (cond-> {:ok?    false
           :reason base-vocab/cursor-stale-reason
           :tool   tool
           :hint   (str "Cursor's id is no longer in the runtime ring. "
                        "Drop the cursor and restart, or widen the window "
                        "to recover the missed records.")}
    requested-id (assoc :requested-id requested-id)
    head-id      (assoc :head-id head-id)))

;; ---------------------------------------------------------------------------
;; apply-to-result — per-tool boundary wrapper for paginated tools.
;;
;; Tools call this once at the end of their body with:
;;   - The already-walked (W-6) + already-stripped (B-1) items
;;     vector (post-truncation to `:limit`).
;;   - The cursor payload to encode for `:next-cursor` (or nil to
;;     signal pagination over).
;;   - The `:remaining` count / estimate.
;;
;; The wrapper splices `:next-cursor` + `:remaining` onto the
;; envelope per MUST 11 and writes the items under `items-key`.
;; ---------------------------------------------------------------------------

(defn apply-to-result
  "Apply the spec/004 §3 cursor-pagination response shape to
  `items` and write the result back into `envelope` under
  `items-key`. Returns the updated envelope.

  Arguments:
    - `envelope`       — the per-call result map (will be updated).
    - `items-key`      — the slot in `envelope` the per-page items
                         go into (e.g. `:trace-events` for
                         `get-trace-buffer`, `:epochs` for
                         `get-epoch-history`, `:subscriptions` for
                         `list-subscriptions`).
    - `items`          — the per-page items vector (already
                         truncated to `:limit`; already walked
                         through W-6; already stripped via B-1
                         when applicable).
    - opts:
      - `:next-cursor` — the cursor payload map to encode for the
                        next page, or `nil` when pagination is
                        exhausted. Encoded via `encode-cursor` —
                        a payload missing `:after-id` collapses
                        to `nil` (terminal page).
      - `:remaining`   — integer count / estimate of items still
                        unfetched. Per MUST 11. Always carried,
                        even when zero (so the agent's per-page
                        accounting is reliable).

  Returns the envelope with `items-key` set to `items`,
  `:next-cursor` to the encoded string (or `nil` for terminal
  pages), and `:remaining` to the integer."
  [envelope items-key items {:keys [next-cursor remaining]}]
  (-> envelope
      (assoc items-key items)
      (assoc :next-cursor (encode-cursor next-cursor))
      (assoc :remaining   (if (some? remaining) remaining 0))))
