(ns re-frame-pair2-mcp.tools.summary
  "Tree-summary marker primitive (rf2-tygdv, generalised rf2-u2029).

  Per causa-mcp's wire-protocol Principles §\"4. Lazy summary\", the
  default response mode for a rich nested value is a *summary*, not the
  full payload. The summary declares the shape without committing the
  token budget:

    {:rf.mcp/summary {:type  :map | :vector | :set | :scalar
                      :keys  [<top-level keys>]   ; maps only
                      :count <int>                ; non-scalars only
                      :bytes ~<int>}}             ; pr-str char count

  This namespace owns the marker primitive (`tree-summary`) and the
  path-walking helper (`deepest-valid-prefix`) that the snapshot
  pipeline composes downstream. The pipeline itself — slice-by-slice
  application of summarise / path-slice / diff-encode / dedup — lives
  in `tools/snapshot-pipeline.cljs`."
  (:require))

(def summary-keys-cap
  "Top-N keys included verbatim in a tree-summary marker. Above this,
  the summary truncates and attaches `:keys-truncated? true` so the
  marker itself stays bounded — a 5,000-entry map's key list alone
  would exceed the wire cap otherwise. 64 is large enough that a
  human-scale app-db root surfaces every key, and small enough that
  the marker is always tens of tokens."
  64)

(defn tree-summary
  "Compute a server-friendly tree summary of `v`. Returns the marker
  shape causa-mcp's §Lazy-summary mechanism pins. Cheap — one pass
  over the top-level structure, no deep walk. The marker itself is
  bounded: long key lists are truncated to `summary-keys-cap` entries
  and flagged via `:keys-truncated? true` so the marker can never
  blow the wire cap."
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
                                :bytes  (count (pr-str v))}
                         (> n summary-keys-cap)
                         (assoc :keys-truncated? true))})
    (vector? v)
    {:rf.mcp/summary {:type  :vector
                      :count (count v)
                      :bytes (count (pr-str v))}}
    (set? v)
    {:rf.mcp/summary {:type  :set
                      :count (count v)
                      :bytes (count (pr-str v))}}
    (sequential? v)
    {:rf.mcp/summary {:type  :seq
                      :count (count v)
                      :bytes (count (pr-str v))}}
    :else
    {:rf.mcp/summary {:type  :scalar
                      :value v
                      :bytes (count (pr-str v))}}))

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
