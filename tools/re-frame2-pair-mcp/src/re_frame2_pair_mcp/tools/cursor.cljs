(ns re-frame2-pair-mcp.tools.cursor
  "Cursor pagination on epoch-shipping tools.

  `trace-window` and `watch-epochs` both surface unbounded epoch
  vectors. With default ring depth 50 and ~2× app-db per record (or ~1%
  that, after diff-encode), a single call can blow the 5K
  wire-cap. Lazy-summary trims `snapshot` for
  discovery; here the agent explicitly asked for the records — the
  right answer is to PAGE the slice, not to collapse it.

  ## Shared codec

  The base64 codec, the tagged-literal-rejecting EDN reader, the size
  cap, the `::malformed` recovery contract, and the cursor-stale
  envelope all live in `re-frame.mcp-base.cursor` — story-mcp and
  re-frame2-pair-mcp share one implementation. This ns is the thin
  pair-side adapter: it bakes the pair's `:limit` default, supplies the
  pair's cursor-payload shape predicate, and shapes the stale envelope
  through `wire/err-text`. The format-divergent payload slots stay
  here; the machinery is the base's.

  ## Mechanism

  Both tools accept `:limit` (int, default 50 — sized to fit the cap
  after diff-encode + dedup) and `:cursor` (opaque string). The
  response carries:

    {:items [...]                                ; capped at :limit
     :next-cursor \"<base64>\"                   ; nil when no more
     :has-more? true|false
     :estimated-remaining N}

  The opaque cursor is `pr-str`+base64 of a small EDN map. Encoding is
  an implementation detail — agents pass it back verbatim. The shape
  (today; subject to change behind the opaque boundary) is:

    {:v 1
     :after-id <epoch-id>            ; the last epoch-id we emitted
     :ms <int-or-nil>                ; trace-window's :ms arg (sticky)
     :until-ms <int-or-nil>          ; first-call clock; bounds the
                                     ; window so trace-window doesn't
                                     ; admit fresh epochs mid-page
     :frame <keyword-or-nil>}

  ## Why `:after-id` is `:any`, not `string?`

  An epoch-id is OPAQUE — `spec/Spec-Schemas.md` declares `:epoch-id`
  as `:any` and the reference epoch runtime
  (`re-frame/epoch/state.cljc` `next-epoch-id`) emits **integers**
  (`(swap! counter inc)`). The `valid?` predicate requires only that
  the slot be PRESENT (`some?`), not that it be a string — a `string?`
  guard would reject every integer-bearing second-page cursor as
  `::malformed` → spurious `:rf.mcp/cursor-stale`, breaking pagination
  against the real runtime. Any EDN-printable id (integer, keyword,
  string) round-trips losslessly through the base64-of-EDN
  codec. The codec, size cap, and tagged-literal rejection are the
  base's; only this shape predicate is the pair's.

  ## Cursor staleness

  The runtime's epoch ring (depth 50 by default) is a bounded buffer.
  If a cursor's `:after-id` no longer exists in the ring (because
  enough epochs landed between calls that the buffer rotated past it),
  the server returns a structured error rather than silently restarting
  or shipping an out-of-order batch:

    {:ok? false
     :reason :rf.mcp/cursor-stale
     :requested-id <id>
     :head-id <current-head>
     :hint \"...\"}

  The runtime already surfaces `:id-aged-out? true` from `epochs-since`
  when the cursor's id can't be found — we lift that signal to a
  top-level error.

  ## Why opaque

  Per `spec/Principles.md` §Pagination, cursors are opaque on the wire.
  The agent has no business decoding the format; it MUST pass the value
  back verbatim. The base64 + EDN-inside choice gives us room to evolve
  the cursor shape (add fields, switch keys) without a wire-protocol
  break."
  (:require [re-frame.mcp-base.cursor :as base-cursor]
            [re-frame2-pair-mcp.tools.wire :as wire]))

(def default-limit
  ;; Sized to fit a 5K-token cap after diff-encode and
  ;; dedup. With diff-encode collapsing `:db-after` to ~1%
  ;; of `:db-before` and dedup pooling repeated `:db-before` references,
  ;; 50 epochs fit comfortably under 5K tokens for typical app-db sizes.
  ;; Agents that have explicit budget headroom raise via `:limit` arg.
  50)

(def ^:const max-limit
  ;; Hard ceiling on a caller-supplied `:limit`. The wire-cap is the
  ;; real backstop (an over-large page overflows to the
  ;; `:rf.mcp/overflow` marker), so this is a generous DoS guard, not
  ;; the tuning knob — a legitimate large request still works, just
  ;; capped here before the runtime slices.
  1000)

(defn parse-limit-arg
  "Normalise the `:limit` MCP arg into an integer in `[1, max-limit]`.
  Default `default-limit` (50). Delegates to
  `re-frame.mcp-base.cursor/parse-limit-arg`, which
  itself routes through `re-frame.mcp-base.args/parse-positive-int`
  for the shared string/number coercion."
  [raw]
  (base-cursor/parse-limit-arg raw default-limit max-limit))

(defn- valid-payload?
  "The pair's cursor-payload shape predicate. A well-formed cursor is a
  map carrying a PRESENT `:after-id` of ANY type — per the `:any`
  `:epoch-id` contract, the reference runtime's epoch-ids
  are integers, so a `string?` guard here would reject every legitimate
  second-page cursor. We require presence, not type."
  [v]
  (and (map? v) (some? (:after-id v))))

(defn encode-cursor
  "Encode a cursor payload as an opaque base64 string. Returns nil on a
  nil/empty payload or one with no `:after-id` — pagination is over.
  Delegates the codec to `re-frame.mcp-base.cursor/encode-cursor`."
  [payload]
  (when (valid-payload? payload)
    (base-cursor/encode-cursor payload)))

(defn decode-cursor
  "Decode a base64 cursor back to its EDN payload. Returns nil if the
  cursor is absent; returns `::malformed` if the cursor exists but
  doesn't decode to a valid payload map. Callers translate `::malformed`
  into the same `:rf.mcp/cursor-stale` error as a runtime age-out — a
  cursor that doesn't decode is, in effect, stale.

  The codec, size cap, and tagged-literal rejection are
  `re-frame.mcp-base.cursor/decode-cursor`'s; `valid-payload?` is the
  pair's shape check. The base's `::malformed` sentinel is re-aliased
  to this ns's `::malformed` so existing call-sites
  (`(= cursor-in ::cursor/malformed)`) keep matching."
  [s]
  (let [decoded (base-cursor/decode-cursor s valid-payload?)]
    (if (base-cursor/malformed? decoded)
      ::malformed
      decoded)))

(defn cursor-stale-result
  "Structured cursor-stale error result. The `:reason` value
  (`base-vocab/cursor-stale-reason`, namespaced `:rf.mcp/cursor-stale`)
  is the cross-MCP convention agents pattern-match on;
  they either restart (drop the cursor) or rewind via a wider window.

  Shapes the envelope through `wire/err-text` (the pair's wire
  convention) via `re-frame.mcp-base.cursor/cursor-stale-result`, which
  owns only the cross-MCP slot vocabulary (`:reason`, `:tool`, default
  message + hint). The pair-specific `:requested-id` / `:head-id` slots
  ride the `:extra` map."
  [tool {:keys [requested-id head-id]}]
  (base-cursor/cursor-stale-result
    (fn [_message data-map] (wire/err-text data-map))
    tool
    {:hint  (str "Cursor's epoch-id is no longer in the runtime ring. "
                 "Drop the cursor and restart, or widen the window "
                 "to recover the missed records.")
     :extra (cond-> {}
              requested-id (assoc :requested-id requested-id)
              head-id      (assoc :head-id head-id))}))
