(ns re-frame2-pair-mcp.tools.summary
  "Tree-summary marker primitive (rf2-tygdv, generalised rf2-u2029).

  Per the cross-MCP wire-protocol §\"4. Lazy summary\", the
  default response mode for a rich nested value is a *summary*, not the
  full payload. The summary declares the shape without committing the
  token budget:

    {:rf.mcp/summary {:type  :map | :vector | :set | :scalar
                      :keys  [<top-level keys>]   ; maps only
                      :count <int>                ; non-scalars only
                      :bytes ~<int>}}             ; cheap byte estimate

  This namespace owns the marker primitive (`tree-summary`) and the
  path-walking helper (`deepest-valid-prefix`) that the snapshot
  pipeline composes downstream. The pipeline itself — slice-by-slice
  application of summarise / path-slice / diff-encode / dedup — lives
  in `tools/snapshot-pipeline.cljs`.

  ## Approximate `:bytes` field (rf2-qta8j, sampled rf2-lbm21)

  The `:bytes` field is a *cheap* approximation, not a precise count.
  Earlier shape computed `(count (pr-str v))` on every branch which
  deeply serialised the full value just to discard the string — the
  whole point of `tree-summary` is to AVOID shipping the deep value,
  so paying for a full pr-str just to drop it contradicts the marker's
  cost model. On a 54MB app-db slice (per `lazy_summary_test` fixture)
  that's a 54MB string allocation + ~13M tokens of serialisation work
  per summary.

  The estimate is `~ entry-count × per-entry-bytes`. Per-entry-bytes is
  SAMPLED from one representative entry (rf2-lbm21): the prior fixed
  8-/16-byte constants assumed every entry was a scalar, which under-
  reported a collection of deep entries by ~1000×. The dominant
  offender was the `:epochs` slice — a vector of epoch records, each
  carrying a full `:db-before` / `:db-after` app-db pair. A 20-record
  slice estimated `20 × 8 = 160` bytes while the FULL expansion ran to
  ~171K tokens; an agent reading the `:bytes` hint to decide whether to
  drill (`mode full`) saw a slice that looked trivial and blew its
  budget on expansion. Sampling one entry's serialised size captures
  the per-entry depth so the hint reflects the full-expansion cost.

  Cost stays bounded: we `pr-str` exactly ONE entry (and ONE map
  value), not all N — `O(one-entry-depth)`, independent of `:count`.
  The estimate is still intentionally rough — the marker is consumed by
  agents for *planning* (\"is this slice worth drilling into?\"), not
  for precise budgeting. Agents needing a precise size measure their
  drill-down result directly.")

(def summary-keys-cap
  "Top-N keys included verbatim in a tree-summary marker. Above this,
  the summary truncates and attaches `:keys-truncated? true` so the
  marker itself stays bounded — a 5,000-entry map's key list alone
  would exceed the wire cap otherwise. 64 is large enough that a
  human-scale app-db root surfaces every key, and small enough that
  the marker is always tens of tokens."
  64)

;; ---------------------------------------------------------------------------
;; Sampled `:bytes` estimator (rf2-qta8j, rf2-lbm21).
;;
;; `:bytes` ≈ entry-count × per-entry-bytes, where per-entry-bytes is
;; SAMPLED from one representative entry rather than assumed a flat
;; scalar constant. The prior fixed constants (8 for coll entries, 16
;; for map entries) under-reported a collection of DEEP entries — most
;; egregiously the `:epochs` slice, where each vector entry is a full
;; epoch record carrying a `:db-before` / `:db-after` app-db pair. A
;; 20-record slice reported `20 × 8 = 160` bytes while the full
;; expansion was ~171K tokens — the ~1000× miss rf2-lbm21 fixes.
;;
;; Sampling reads ONE entry's serialised size (and, for maps, one
;; value's), so the cost is `O(one-entry-depth)` — independent of N.
;; Floors keep tiny scalar entries from rounding to zero and preserve
;; the old order-of-magnitude shape for shallow collections.
;; ---------------------------------------------------------------------------

(def ^:const ^:private map-key-overhead-bytes
  "Per map-entry overhead for the key + `:`/space punctuation under
  `pr-str`, on TOP of the sampled value size. ~16 chars for a typical
  keyword key."
  16)

(def ^:const ^:private coll-entry-floor-bytes
  "Floor for a sampled vector / set / seq entry — a bare scalar entry
  (`1`, `:x`) prints to ~1-4 chars; floor at 8 to keep the old shape
  for shallow collections and avoid zero-byte estimates."
  8)

(defn- sample-entry-bytes
  "Serialised size of ONE sample value under `pr-str`, floored at
  `coll-entry-floor-bytes`. The single-entry `pr-str` is the whole cost
  of the sampled estimate — bounded by the entry's own depth, NOT by
  the collection's `:count`. nil / empty sample ⇒ the floor."
  [sample]
  (if (nil? sample)
    coll-entry-floor-bytes
    (max coll-entry-floor-bytes (count (pr-str sample)))))

(defn- approx-coll-bytes
  "Estimate the full-expansion byte cost of a collection: `count ×
  sampled-per-entry-bytes`. `first-entry` is one representative element
  (the slice is non-empty when `n` is positive)."
  [n first-entry]
  (* n (sample-entry-bytes first-entry)))

(defn- approx-map-bytes
  "Estimate the full-expansion byte cost of a map: `count × (key
  overhead + sampled-value-bytes)`. `first-val` is one representative
  value sampled to capture per-value depth (a map of deep values — a
  per-frame epoch index, say — is as expensive to expand as a vector
  of deep entries)."
  [n first-val]
  (* n (+ map-key-overhead-bytes (sample-entry-bytes first-val))))

(defn tree-summary
  "Compute a server-friendly tree summary of `v`. Returns the marker
  shape the cross-MCP §Lazy-summary mechanism pins.

  Cheap — one pass over the top-level structure, no deep walk. The
  marker itself is bounded: long key lists are truncated to
  `summary-keys-cap` entries and flagged via `:keys-truncated? true`
  so the marker can never blow the wire cap.

  The `:bytes` field is an APPROXIMATION (per rf2-qta8j). Earlier
  shape deep-serialised the value with `(count (pr-str v))` — that
  pays the very cost the summary marker exists to avoid (a 54MB
  app-db slice burns a 54MB string allocation to compute a single
  integer estimate). The current implementation multiplies the
  entry count by a per-entry constant — see the namespace docstring
  for the constants and rationale.

  Scalars are returned unchanged (rf2-ambfv): they already fit the
  wire cap by definition, so wrapping them in a summary marker would
  add tokens without saving any."
  [v]
  (cond
    (map? v)
    (let [ks      (keys v)
          n       (count ks)
          shown   (if (> n summary-keys-cap)
                    (vec (take summary-keys-cap ks))
                    (vec ks))
          ;; Sample one value to capture per-value depth (rf2-lbm21).
          ;; `(first (vals v))` is O(1) — it doesn't realise the rest.
          first-val (when (pos? n) (val (first v)))]
      {:rf.mcp/summary (cond-> {:type   :map
                                :keys   shown
                                :count  n
                                :bytes  (approx-map-bytes n first-val)}
                         (> n summary-keys-cap)
                         (assoc :keys-truncated? true))})
    (vector? v)
    (let [n (count v)]
      {:rf.mcp/summary {:type  :vector
                        :count n
                        :bytes (approx-coll-bytes n (first v))}})
    (set? v)
    (let [n (count v)]
      {:rf.mcp/summary {:type  :set
                        :count n
                        :bytes (approx-coll-bytes n (first v))}})
    (sequential? v)
    (let [n (count v)]
      {:rf.mcp/summary {:type  :seq
                        :count n
                        :bytes (approx-coll-bytes n (first v))}})
    :else v))

(defn deepest-valid-prefix
  "Walk `path` against `db` and return the deepest prefix that
  resolves. Used in `:path-not-found` errors so the agent can re-aim
  without a binary search. Handles map keys + sequential indices;
  anything else (a scalar at depth, a function value, etc.) terminates
  the walk."
  [db path]
  (loop [acc [] cur db remaining path]
    (if (empty? remaining)
      acc
      (let [k (first remaining)]
        (cond
          (and (map? cur) (contains? cur k))
          (recur (conj acc k) (get cur k) (rest remaining))

          (and (sequential? cur) (integer? k) (<= 0 k (dec (count cur))))
          (recur (conj acc k) (nth (vec cur) k) (rest remaining))

          :else acc)))))
