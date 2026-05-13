(ns re-frame-pair2-mcp.cache
  "Per-session response cache keyed on a hash of the serialised wire
  payload (rf2-3rt1f).

  ## What this is

  An 8-slot LRU that lives for the lifetime of one MCP server process
  (= one MCP session per the `Single persistent nREPL socket`
  principle — see `spec/Principles.md`). Each entry is keyed by a
  `(tool, args-fingerprint)` pair and stores the hash of the most-
  recently-emitted MCP result's text payload plus the timestamp it
  was first seen.

  On a fresh tool call:

    1. Run the tool — compute the MCP result the usual way.
    2. Hash the result's serialised `:text` slot.
    3. Look up `(tool, args-fingerprint)` in the LRU.
       - Miss → store `{:hash h :sent-at now :tool t}`. Return the
         original result unchanged.
       - Hit with matching hash → return a tiny
         `{:rf.mcp/cache-hit {:hash h :unchanged-since <ms> :tool t}}`
         marker instead of the full payload. The agent host's prior
         `tools/call` for this tool already received the byte-identical
         payload; re-shipping it doubles the conversation cost for no
         new information.
       - Hit with different hash → app-db (or the relevant runtime
         state) has moved on; store the new hash + `:sent-at`, return
         the fresh result.

  ## Why hash the result text, not app-db directly

  The bead (rf2-3rt1f) proposes `(hash app-db)`. The framing here is
  one step downstream: by the time the result is built, it has already
  been path-sliced, summarised, diff-encoded, deduped, scrubbed, etc.
  Two calls with the same args against an unchanged app-db produce the
  same serialised text — so hashing the text catches the same hit and
  is robust against every transform in the wire pipeline. It also lets
  one cache cover every tool uniformly (snapshot, get-path,
  trace-window, etc.) instead of needing per-tool hash strategies.

  The trade-off: we still pay the nREPL round-trip and the local
  transform pipeline; the cache only saves the *wire bytes*. That's
  the byte cost the bead identifies (\"the wire pays full app-db cost
  twice\"). Saving the round-trip needs a server-side hash precheck
  — out of scope here; filed as a follow-on bead.

  ## LRU policy

  Capacity 8. Eviction is least-recently-USED (touch on every hit and
  every store). One entry per `(tool, args-fingerprint)` — a fresh
  hash for the same key replaces the prior entry in place (the slot
  is repurposed, not duplicated). Cache is cleared on
  `reset!` (process restart resets implicitly).

  ## Bounded by design

  - **Cap**: 8 entries × ~128 bytes of metadata = ~1KB ceiling.
    Stored payloads are NOT retained — only the hash. The marker we
    emit on a hit is tiny (sub-100 bytes); the agent host already
    has the full payload from the prior call.
  - **Disabled by default**: pass `cache true` (or the per-call MCP
    arg) to opt in. Default-off keeps the contract simple for
    callers who haven't yet learned the `:rf.mcp/cache-hit` shape.
    See `parse-cache-arg`.
  - **Per-tool opt-out**: streaming / progress-bearing tools
    (`subscribe`, `dispatch` with `:trace`) bypass the cache. Their
    return value is the result of an action, not a read.

  ## Marker shape

  ```clojure
  {:rf.mcp/cache-hit
   {:hash             <integer>
    :unchanged-since  <ms-since-epoch>
    :tool             \"<tool-name>\"
    :hint             \"<agent-host instruction string>\"}}
  ```

  The `:rf.mcp/*` namespace matches the wire-vocabulary convention
  used by `:rf.mcp/overflow` (rf2-rvyzy), `:rf.mcp/dedup-table`
  (rf2-obpa9), `:rf.mcp/summary` (rf2-tygdv), `:rf.size/large-elided`
  (rf2-urjnc). Agents that learned the family see one more slot."
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; LRU state — module-level atom; one MCP server process = one session.
;; ---------------------------------------------------------------------------

(def ^:private capacity 8)

(def ^:private state
  "{:entries {<key> {:hash <int> :sent-at <ms> :tool <str>}}
    :order   <vector of keys, oldest first>}

  A small vector preserves insertion order; on hit we re-insert at the
  tail (touch) and on overflow we drop from the head."
  (atom {:entries {} :order []}))

(defn clear!
  "Empty the cache. Exposed for tests and for the process-restart path.
  Named `clear!` rather than `reset!` to avoid shadowing
  `cljs.core/reset!` (which the namespace uses internally)."
  []
  (reset! state {:entries {} :order []}))

;; ---------------------------------------------------------------------------
;; Key + hash helpers.
;; ---------------------------------------------------------------------------

(defn args->fingerprint
  "Stabilise a JS args object into a value suitable for use as part of
  a cache key. The JS object's own keys are sorted lexicographically
  so that the same logical args always produce the same fingerprint
  irrespective of JSON-object key order. `nil` / `undefined` arrays
  collapse to a canonical `nil` so two callers that pass nothing
  share an entry."
  [args]
  (cond
    (or (nil? args) (undefined? args)) nil
    (object? args)
    (let [ks (sort (js->clj (js/Object.keys args)))]
      (reduce
        (fn [acc k]
          (let [v (j/get args k)]
            (assoc acc k (cond
                           (or (nil? v) (undefined? v)) nil
                           (array? v)                   (vec (js->clj v))
                           (object? v)                  (js->clj v)
                           :else                        v))))
        {}
        ks))
    :else (js->clj args)))

(defn cache-key
  "Build the cache key tuple for a tool invocation."
  [tool args]
  [tool (args->fingerprint args)])

(defn hash-result
  "Compute the cache hash for an MCP result. We sum every text slot's
  Clojure-`hash` and the slot's character count — using both shields
  against the rare hash collision (a different payload of the same
  length AND the same `hash` would still slip past, but the byte-count
  guard catches the common near-collision)."
  [result-js]
  (let [content (when result-js (j/get result-js :content))
        n       (if (array? content) (.-length content) 0)
        err?    (boolean (j/get result-js :isError))]
    (loop [i 0 acc 0 chars 0]
      (if (< i n)
        (let [item (aget content i)
              t    (when item (j/get item :text))]
          (if (string? t)
            (recur (inc i) (bit-xor acc (hash t)) (+ chars (count t)))
            (recur (inc i) acc chars)))
        ;; Encode isError into the high bits so an error-vs-success
        ;; flip on the same text payload doesn't read as a hit.
        (bit-xor acc chars (if err? 0xA5A5A5A5 0))))))

;; ---------------------------------------------------------------------------
;; LRU operations.
;; ---------------------------------------------------------------------------

(defn- touch
  "Move `k` to the tail of the `:order` vector (most-recently-used)."
  [order k]
  (conj (filterv #(not= % k) order) k))

(defn- enforce-capacity
  "Drop oldest entries until under `capacity`."
  [{:keys [entries order] :as st}]
  (if (<= (count order) capacity)
    st
    (let [drop-n  (- (count order) capacity)
          dropped (take drop-n order)
          order'  (vec (drop drop-n order))
          ents'   (apply dissoc entries dropped)]
      {:entries ents' :order order'})))

(defn lookup
  "Return the entry for `k`, or `nil`. Does NOT touch ordering — the
  caller decides hit vs. store semantics."
  [k]
  (get-in @state [:entries k]))

(defn store!
  "Record `entry` under key `k`. Touches the LRU and enforces capacity."
  [k entry]
  (swap! state
         (fn [{:keys [entries order]}]
           (enforce-capacity
             {:entries (assoc entries k entry)
              :order   (touch order k)}))))

(defn record-hit!
  "Touch `k` on a cache hit so the entry moves to the tail. Returns the
  existing entry."
  [k]
  (swap! state update :order touch k)
  (get-in @state [:entries k]))

(defn size
  "Current number of cached entries — exposed for tests and for the
  health surface."
  []
  (count (:order @state)))

;; ---------------------------------------------------------------------------
;; Marker construction.
;; ---------------------------------------------------------------------------

(def ^:private cache-hit-hint
  "The agent-host instruction. Pattern-matches against
  `:rf.mcp/cache-hit` and reuses the prior `tools/call` payload for
  this tool+args. State has not moved since the timestamp."
  (str "Payload byte-identical to the prior tools/call for this "
       "(tool,args). Re-use the agent's previous response; "
       "no fresh state to inspect since :unchanged-since."))

(defn cache-hit-payload
  "Build the structured wire marker that replaces a cached response."
  [{:keys [tool hash sent-at]}]
  {:rf.mcp/cache-hit {:hash            hash
                      :unchanged-since sent-at
                      :tool            tool
                      :hint            cache-hit-hint}})

(defn cache-hit-result
  "Wrap `cache-hit-payload` in the MCP `{:content [{:type \"text\" ...}]}`
  envelope."
  [entry tool]
  #js {:content #js [#js {:type "text"
                          :text (pr-str (cache-hit-payload
                                          (assoc entry :tool tool)))}]})

;; ---------------------------------------------------------------------------
;; Arg parsing — opt-in switch.
;; ---------------------------------------------------------------------------

(defn parse-cache-arg
  "Resolve the per-call cache switch. Accepts boolean or stringified
  boolean; default is FALSE (cache opt-in until agent hosts have been
  taught the marker shape — same pattern as `dedup` before its
  default flipped)."
  [raw]
  (cond
    (nil? raw)         false
    (true? raw)        true
    (false? raw)       false
    (= raw "true")     true
    (= raw "false")    false
    (= raw :true)      true
    (= raw :false)     false
    (and (string? raw) (= (str/lower-case raw) "true"))  true
    (and (string? raw) (= (str/lower-case raw) "false")) false
    :else              false))

;; ---------------------------------------------------------------------------
;; The wire-boundary entry-point.
;; ---------------------------------------------------------------------------

(def ^:private cacheable-tools
  "Tools whose return value is a read of state — re-asking with the
  same args against unchanged state legitimately returns the same
  bytes. Action tools (`dispatch`, `eval-cljs`, `tail-build`) and
  streaming tools (`subscribe`, `unsubscribe`) are excluded — their
  return value is the result of an action, not a read."
  #{"snapshot" "get-path" "trace-window" "watch-epochs" "discover-app"})

(defn cacheable?
  "Predicate — should this tool ever consult the cache?"
  [tool]
  (contains? cacheable-tools tool))

(defn apply-cache
  "Wire-boundary cache check. Returns either:

    - `result-js` unchanged (cache disabled, tool not cacheable,
      isError result, or fresh store) — and as a side effect records
      the hash for future hits.
    - A fresh result carrying `{:rf.mcp/cache-hit ...}` — when the
      hash matches the prior entry for `(tool, args)`.

  Errors are never cached: an `:isError` result is passed through
  untouched and does NOT poison the cache. That keeps a transient
  failure from masking a future successful read."
  [result-js {:keys [tool args enabled?]}]
  (cond
    (not enabled?)               result-js
    (nil? result-js)             result-js
    (not (cacheable? tool))      result-js
    (boolean (j/get result-js :isError)) result-js
    :else
    (let [k          (cache-key tool args)
          h          (hash-result result-js)
          prior      (lookup k)
          now        (.getTime (js/Date.))]
      (if (and prior (= (:hash prior) h))
        (do (record-hit! k)
            (cache-hit-result prior tool))
        (do (store! k {:hash h :sent-at now :tool tool})
            result-js)))))
