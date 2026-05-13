(ns re-frame-pair2-mcp.tools.cursor
  "Cursor pagination on epoch-shipping tools (rf2-kbqq3).

  `trace-window` and `watch-epochs` both surface unbounded epoch
  vectors. With default ring depth 50 and ~2× app-db per record (or ~1%
  that, post-rf2-1wdzp diff-encode), a single call can blow the 5K
  wire-cap (rf2-rvyzy). Lazy-summary (rf2-u2029) trims `snapshot` for
  discovery; here the agent explicitly asked for the records — the
  right answer is to PAGE the slice, not to collapse it.

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
     :after-id <epoch-id-string>     ; the last epoch-id we emitted
     :ms <int-or-nil>                ; trace-window's :ms arg (sticky)
     :until-ms <int-or-nil>          ; first-call clock; bounds the
                                     ; window so trace-window doesn't
                                     ; admit fresh epochs mid-page
     :frame <keyword-or-nil>}

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
  (:require [cljs.reader]
            [clojure.string :as str]
            [re-frame.mcp-base.args :as base-args]
            [re-frame.mcp-base.vocab :as base-vocab]
            [re-frame-pair2-mcp.tools.wire :as wire]))

(def default-limit
  ;; Sized to fit a 5K-token cap after diff-encode (rf2-1wdzp) and
  ;; dedup (rf2-obpa9). With diff-encode collapsing `:db-after` to ~1%
  ;; of `:db-before` and dedup pooling repeated `:db-before` references,
  ;; 50 epochs fit comfortably under 5K tokens for typical app-db sizes.
  ;; Agents that have explicit budget headroom raise via `:limit` arg.
  50)

(defn parse-limit-arg
  "Normalise the `:limit` MCP arg into a positive integer. Default
  `default-limit` (50). Delegates to
  `re-frame.mcp-base.args/parse-positive-int` (rf2-vw4sq)."
  [raw]
  (base-args/parse-positive-int raw default-limit))

(defn encode-cursor
  "Encode a cursor payload as a base64 string. Returns nil on a nil/empty
  payload — pagination is over."
  [payload]
  (when (and (map? payload) (some? (:after-id payload)))
    (let [edn (pr-str payload)
          buf (js/Buffer.from edn "utf8")]
      (.toString buf "base64"))))

(defn decode-cursor
  "Decode a base64 cursor back to its EDN payload. Returns nil if the
  cursor is absent; returns `::malformed` if the cursor exists but
  doesn't decode to a valid payload map. Callers translate `::malformed`
  into the same `:rf.mcp/cursor-stale` error as a runtime age-out — a
  cursor that doesn't decode is, in effect, stale."
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

(defn cursor-stale-result
  "Structured cursor-stale error result. The `:reason` value
  (`base-vocab/cursor-stale-reason`, namespaced `:rf.mcp/cursor-stale`)
  is the cross-MCP convention agents pattern-match on (rf2-vw4sq);
  they either restart (drop the cursor) or rewind via a wider window."
  [tool {:keys [requested-id head-id]}]
  (wire/err-text (cond-> {:ok? false
                          :reason base-vocab/cursor-stale-reason
                          :tool   tool
                          :hint   (str "Cursor's epoch-id is no longer in the runtime ring. "
                                       "Drop the cursor and restart, or widen the window "
                                       "to recover the missed records.")}
                   requested-id (assoc :requested-id requested-id)
                   head-id      (assoc :head-id head-id))))
