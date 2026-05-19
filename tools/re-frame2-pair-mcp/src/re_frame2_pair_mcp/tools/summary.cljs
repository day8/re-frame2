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

  ## Approximate `:bytes` field (rf2-qta8j)

  The `:bytes` field is a *cheap* approximation, not a precise count.
  Earlier shape computed `(count (pr-str v))` on every branch which
  deeply serialised the full value just to discard the string — the
  whole point of `tree-summary` is to AVOID shipping the deep value,
  so paying for a full pr-str just to drop it contradicts the marker's
  cost model. On a 54MB app-db slice (per `lazy_summary_test` fixture)
  that's a 54MB string allocation + ~13M tokens of serialisation work
  per summary.

  The current heuristic estimates `~ entry-count × constant` (see
  `byte-estimate-per-entry`). It is intentionally rough — the marker
  is consumed by agents for *planning* (\"is this slice worth
  drilling into?\"), not for precise budgeting. Agents needing a
  precise size measure their drill-down result directly."
  (:require))

(def summary-keys-cap
  "Top-N keys included verbatim in a tree-summary marker. Above this,
  the summary truncates and attaches `:keys-truncated? true` so the
  marker itself stays bounded — a 5,000-entry map's key list alone
  would exceed the wire cap otherwise. 64 is large enough that a
  human-scale app-db root surfaces every key, and small enough that
  the marker is always tens of tokens."
  64)

;; ---------------------------------------------------------------------------
;; Cheap `:bytes` estimator (rf2-qta8j).
;;
;; Average per-entry overhead under `pr-str` for representative
;; runtime values:
;;
;; - map entry: keyword key + scalar value + space + colon → ~16 chars
;; - vector / set / seq entry: scalar value + space         → ~8  chars
;;
;; These are *order-of-magnitude* constants, not measurements. The
;; marker's `:bytes` field is consumed for planning ("is this slice
;; worth drilling?"); agents needing a precise byte count walk the
;; drill-down result directly. Drop a level of precision in exchange
;; for one constant-time multiplication per summarised value.
;; ---------------------------------------------------------------------------

(def ^:const ^:private map-bytes-per-entry  16)
(def ^:const ^:private coll-bytes-per-entry 8)

(defn- approx-map-bytes  [n] (* map-bytes-per-entry  n))
(defn- approx-coll-bytes [n] (* coll-bytes-per-entry n))

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
                    (vec ks))]
      {:rf.mcp/summary (cond-> {:type   :map
                                :keys   shown
                                :count  n
                                :bytes  (approx-map-bytes n)}
                         (> n summary-keys-cap)
                         (assoc :keys-truncated? true))})
    (vector? v)
    (let [n (count v)]
      {:rf.mcp/summary {:type  :vector
                        :count n
                        :bytes (approx-coll-bytes n)}})
    (set? v)
    (let [n (count v)]
      {:rf.mcp/summary {:type  :set
                        :count n
                        :bytes (approx-coll-bytes n)}})
    (sequential? v)
    (let [n (count v)]
      {:rf.mcp/summary {:type  :seq
                        :count n
                        :bytes (approx-coll-bytes n)}})
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
