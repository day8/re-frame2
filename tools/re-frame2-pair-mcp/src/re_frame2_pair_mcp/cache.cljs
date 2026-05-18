(ns re-frame2-pair-mcp.cache
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

  The original (rf2-3rt1f) shape paid the full nREPL round-trip and
  local transform pipeline; the cache only saved the *wire bytes*.
  rf2-36xod added a **precheck** path on top: an entry can carry a
  cheap `:precheck-hash` fetched via one bencode round-trip. rf2-9pe31
  collapsed the precheck eval to `(re-frame2-pair.runtime/app-db-hash
  frame)` — an O(1) accessor over a per-frame integer cache the
  runtime keeps current via its epoch listener (every settled
  mutation updates the cached hash). Before running the tool, the MCP
  server fetches the current precheck-hash; if it matches the stored
  `:precheck-hash` for `(tool, args)`, the server emits the
  `:rf.mcp/cache-hit` marker WITHOUT running the tool. Saves both
  wire bytes AND the heavyweight tool eval + transform pipeline.

  See `precheck` (decide before running the tool) vs `apply-cache`
  (decide after running the tool — the original behaviour, used as
  the backstop for tools without a precheck wiring).

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
    The arg is parsed by the shared
    `re-frame2-pair-mcp.tools.args/parse-bool-arg` table (rf2-c4fmh).
  - **Per-tool opt-out**: streaming / progress-bearing tools
    (`subscribe`, `dispatch` with `:trace`) bypass the cache. Their
    return value is the result of an action, not a read.

  ## Marker shape

  ```clojure
  {:rf.mcp/cache-hit
   {:hash             <integer>
    :unchanged-since  <ms-since-epoch>
    :tool             \"<tool-name>\"
    :via              :precheck | :result-hash   ;; rf2-36xod
    :hint             \"<agent-host instruction string>\"}}
  ```

  `:via :precheck` signals the hit short-circuited the full tool eval
  (rf2-36xod path); `:via :result-hash` is the original rf2-3rt1f
  match-after-eval path. Same wire vocabulary, different cost saved.

  The `:rf.mcp/*` namespace matches the wire-vocabulary convention
  used by `:rf.mcp/overflow` (rf2-rvyzy), `:rf.mcp/dedup-table`
  (rf2-obpa9), `:rf.mcp/summary` (rf2-tygdv), `:rf.size/large-elided`
  (rf2-urjnc). Agents that learned the family see one more slot."
  (:require [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.tools.registry :as registry]))

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
  "Build the structured wire marker that replaces a cached response.
  `via` defaults to `:result-hash` (the rf2-3rt1f match-after-eval
  path); pass `:precheck` for the rf2-36xod skip-the-tool-eval path."
  ([entry] (cache-hit-payload entry :result-hash))
  ([{:keys [tool hash sent-at]} via]
   {:rf.mcp/cache-hit {:hash            hash
                       :unchanged-since sent-at
                       :tool            tool
                       :via             via
                       :hint            cache-hit-hint}}))

(defn cache-hit-result
  "Wrap `cache-hit-payload` in the MCP `{:content [{:type \"text\" ...}]}`
  envelope. `via` annotates which cache path produced the hit
  (`:result-hash` = rf2-3rt1f post-eval match; `:precheck` =
  rf2-36xod pre-eval short-circuit)."
  ([entry tool] (cache-hit-result entry tool :result-hash))
  ([entry tool via]
   #js {:content #js [#js {:type "text"
                           :text (pr-str (cache-hit-payload
                                           (assoc entry :tool tool)
                                           via))}]}))

;; ---------------------------------------------------------------------------
;; The wire-boundary entry-point.
;; ---------------------------------------------------------------------------

(def cacheable?
  "Predicate — should this tool ever consult the cache?

  Forwarded to `registry/cacheable?` (rf2-47g8l) — the cacheable-bool
  is stored on each entry in the single-source-of-truth registry, so
  cache.cljs doesn't redeclare the allowlist. Keeping the name here
  preserves the call-site vocabulary (`cache/cacheable?`) for existing
  tests and for the `apply-cache` / `precheck` use sites below."
  registry/cacheable?)

(defn apply-cache
  "Wire-boundary cache check (rf2-3rt1f match-after-eval path). Returns
  either:

    - `result-js` unchanged (cache disabled, tool not cacheable,
      isError result, or fresh store) — and as a side effect records
      the hash for future hits.
    - A fresh result carrying `{:rf.mcp/cache-hit ...}` — when the
      hash matches the prior entry for `(tool, args)`.

  Errors are never cached: an `:isError` result is passed through
  untouched and does NOT poison the cache. That keeps a transient
  failure from masking a future successful read.

  If `:precheck-hash` is supplied (the value fetched from the runtime
  via the rf2-36xod precheck wiring), it is stored alongside the
  result hash so the NEXT call can short-circuit via `precheck`
  without re-running the tool."
  [result-js {:keys [tool args enabled? precheck-hash]}]
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
            (cache-hit-result prior tool :result-hash))
        (do (store! k (cond-> {:hash h :sent-at now :tool tool}
                        (some? precheck-hash) (assoc :precheck-hash precheck-hash)))
            result-js)))))

;; ---------------------------------------------------------------------------
;; Precheck — rf2-36xod. Decide cache-hit BEFORE running the tool.
;; ---------------------------------------------------------------------------

(defn precheck
  "Pre-eval cache check (rf2-36xod). Returns either:

    - `nil` — no decision; the caller proceeds with the full tool eval
      and feeds the result back through `apply-cache`.
    - A `{:rf.mcp/cache-hit ... :via :precheck}` MCP result — the
      caller short-circuits and returns this directly, skipping the
      tool eval entirely.

  Decision rule: only when (a) cache is enabled, (b) the tool is
  cacheable, (c) the (tool, args) key has a prior entry, (d) that
  prior entry has a stored `:precheck-hash`, and (e) the
  `current-precheck-hash` argument matches it.

  `current-precheck-hash` is the value the MCP server fetched in a
  single bencode round-trip (today: `(re-frame2-pair.runtime/app-db-hash
  frame)` — an O(1) accessor over the runtime's per-frame cached
  hash, kept current by its epoch listener — see rf2-9pe31). When
  the caller has no precheck wiring for this tool (yet), it passes
  `nil` and this fn returns `nil`, leaving the legacy post-eval path
  in charge.

  This fn does NOT mutate the cache on a miss — the subsequent
  `apply-cache` call records the new result+precheck-hash together
  after the tool runs.

  On a hit, it does touch the LRU (so the entry stays warm)."
  [{:keys [tool args enabled?]} current-precheck-hash]
  (cond
    (not enabled?)                       nil
    (not (cacheable? tool))              nil
    (nil? current-precheck-hash)         nil
    :else
    (let [k     (cache-key tool args)
          prior (lookup k)]
      (when (and prior
                 (some? (:precheck-hash prior))
                 (= (:precheck-hash prior) current-precheck-hash))
        (record-hit! k)
        (cache-hit-result prior tool :precheck)))))
