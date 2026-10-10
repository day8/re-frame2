(ns re-frame2-pair-mcp.cache
  "Per-session response cache keyed on a hash of the serialised wire
  payload.

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

  Hashing happens one step downstream of app-db: by the time the
  result is built, it has already been path-sliced, summarised,
  diff-encoded, deduped, scrubbed, etc. Two calls with the same args
  against an unchanged app-db produce the same serialised text — so
  hashing the text catches the same hit and is robust against every
  transform in the wire pipeline. It also lets one cache cover every
  tool uniformly (snapshot, get-path, trace-window, etc.) instead of
  needing per-tool hash strategies.

  Hashing the final text is also what makes a hit sound. A read's
  wire value passes through `rf/project-egress`, which reads
  runtime-db state (the elision and sensitive registries, the egress
  profile, the per-call `include-sensitive` gate) as well as app-db,
  so no hash taken before the tool runs could decide a hit. The tool
  always runs; a hit saves the wire bytes, not the eval.

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
    `re-frame2-pair-mcp.tools.args/parse-bool-arg` table.
  - **Per-tool opt-out**: action tools
    (`dispatch` with `:trace`) bypass the cache. Their
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
  used by `:rf.mcp/overflow`, `:rf.mcp/dedup-table`, `:rf.mcp/summary`,
  and `:rf.size/large-elided`. Agents that learned the family see one
  more slot."
  (:require [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.tools.registry :as registry]
            [re-frame2-pair-mcp.tools.wire :as wire]))

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
  "Build the cache key tuple for a tool invocation.

  The key includes the resolved BUILD as well as
  `(tool, args-fingerprint)`. The same `(tool, args)` against two
  different shadow-cljs builds reachable over the one nREPL connection is
  two distinct reads; folding the build into the key keeps every hit a
  same-build comparison, so identical response text under two builds is
  a fresh store, not a hit. `build` is the resolved build-id keyword (from
  `wire/arg-build`); a call without one passes nil and keys on
  `(tool, args)` alone.

  Note: the OPERATING FRAME for an omitted-`:frame` call is not knowable
  here (it resolves runtime-side), so it cannot be folded into the key.
  That axis is covered by clearing the whole cache on every operating-
  frame change (the `invoke` chokepoint calls `cache/clear!` after a
  successful `set-operating-frame` / `reset-operating-frame`), which
  keeps every hit a same-frame comparison, so `:unchanged-since` dates a
  read of the frame the call resolves to."
  ([tool args] (cache-key tool args nil))
  ([tool args build]
   [tool build (args->fingerprint args)]))

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

(defn forget!
  "Drop the entry for `k` — both the entry and its LRU slot. The inverse
  of [[store!]], for the case where the payload whose hash was recorded
  never reached the caller."
  [k]
  (swap! state
         (fn [{:keys [entries order]}]
           {:entries (dissoc entries k)
            :order   (filterv #(not= % k) order)})))

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
  envelope plus the `:structuredContent` slot."
  [entry tool]
  ;; Route through `wire/result` so the cache-hit marker's
  ;; structuredContent keeps its namespace: a raw `clj->js` truncates
  ;; the `:rf.mcp/cache-hit` marker key to `"cache-hit"`, so SDK-friendly
  ;; hosts reading structuredContent miss the marker entirely.
  (wire/result (cache-hit-payload (assoc entry :tool tool)) false))

;; ---------------------------------------------------------------------------
;; The wire-boundary entry-point.
;; ---------------------------------------------------------------------------

(def cacheable?
  "Predicate — should this tool ever consult the cache?

  Forwarded to `registry/cacheable?` — the cacheable-bool is stored on
  each entry in the single-source-of-truth registry, so cache.cljs
  doesn't redeclare the allowlist. The name here keeps the
  call-site vocabulary (`cache/cacheable?`) for the tests and for the
  `apply-cache` / `withhold!` use sites below."
  registry/cacheable?)

(defn apply-cache
  "Wire-boundary cache check, run after the tool. Returns either:

    - `result-js` unchanged (cache disabled, tool not cacheable,
      isError result, or fresh store) — and as a side effect records
      the hash for future hits.
    - A fresh result carrying `{:rf.mcp/cache-hit ...}` — when the
      hash matches the prior entry for `(tool, args)`.

  Errors are never cached: an `:isError` result is passed through
  untouched and records nothing. The cache-hit marker is a success
  result, so a hit standing in for a failure would drop its `isError`
  flag."
  [result-js {:keys [tool args enabled? build]}]
  (cond
    (not enabled?)               result-js
    (nil? result-js)             result-js
    (not (cacheable? tool))      result-js
    (boolean (j/get result-js :isError)) result-js
    :else
    (let [k          (cache-key tool args build)
          h          (hash-result result-js)
          prior      (lookup k)
          now        (.getTime (js/Date.))]
      (if (and prior (= (:hash prior) h))
        (do (record-hit! k)
            (cache-hit-result prior tool))
        (do (store! k {:hash h :sent-at now :tool tool})
            result-js)))))

(defn withhold!
  "Undo the identity [[apply-cache]] just recorded, because the payload it
  hashed was never delivered to the caller.

  A `:rf.mcp/cache-hit` is an instruction: *re-use the response you
  already have*. `apply-cache` runs BEFORE the wire cap, so on a miss it
  stores the full response's hash and `:sent-at` and then the cap can
  replace that response with `:rf.mcp/overflow` — a payload the caller
  never saw. Left standing, that entry would match the next identical
  read and tell it to re-use bytes that were never sent, erasing the
  actionable size-limit diagnosis on every repeat.

  Dropping the candidate entry leaves the fast hit-before-cap path
  intact for genuinely delivered responses (an under-cap payload leaves
  its entry standing), and no overflow marker is ever cached as if it
  were the source payload.

  Takes the same `cache-opts` map `apply-cache` does. A no-op when the
  cache is off or the tool is not cacheable, since nothing was stored."
  [{:keys [tool args enabled? build]}]
  (when (and enabled? (cacheable? tool))
    (forget! (cache-key tool args build))))
