(ns re-frame.hicasso.impl.receipt
  "SPIKE ONLY — rf2-hic-081. Capability receipts: mechanical attempt facts
  per boundary, as a diagnostic.

  NOTHING HERE IS RETAINED WITHOUT GRADUATION. It exists to answer one
  question and be deleted: can the §11 receipt be produced without a tap
  on any hot loop, without allocating when detached, and without changing
  what an `:advanced` build contains?

  ## THE FINDING THE FILE IS SHAPED BY

  Five of the six named fields — body runs, read calls, unique reads,
  cache hits, codec node counts — are DERIVABLE POST HOC from state the
  runtime already retains for its own reasons, at the end of an attempt,
  with no counter anywhere inside the body:

  - the SCRATCH array is every read call the attempt made, in order,
    duplicates included, because `read-key!` pushes each one;
  - UNIQUE READS is that array's distinct count;
  - CACHE HITS is, for each scratch key, whether `!cells` holds a cell
    with a live reaction — and that answer is STABLE across the attempt,
    because `cold-read!` retains nothing and the generation fence
    guarantees a pass observes one commit. Recomputing at the end is
    therefore the same answer the read got, not an approximation;
  - CODEC NODES is a walk of the element the attempt returned;
  - ATTEMPTS is this record, counted here.

  The sixth — SELF TIME — is not derivable, because time is not retained.
  It is the only field that requires an instrument inside the body, and it
  is the field the spike kills. See the bead.

  ## Attempts, not commits

  This counts ATTEMPTS: `run-once` is where a body actually runs, so a
  generation-fence re-run is two attempts and a `React.memo` bail-out is
  none. COMMITS are already reported by the existing evidence sink's
  `:commit` event and are not re-derived here — the bead's \"attempts
  distinguished from commits\" is satisfied by keeping them on two
  different seams rather than by a flag on one record.

  ## The cost claim

  ONE tap, in `run-once`, per ATTEMPT — the same cost class as the
  `bodyRuns` increment already there, and three orders below a per-read
  or per-node tap. Detached it is one deref and one nil test; the deref is
  the outermost form, so nothing that would be handed to a receipt gets
  built. Attached, every allocation is inside the guard.

  The node walk happens AFTER the attempt's element is in hand, which is
  what keeps a count from entering a duration: were self time ever
  measured, `t1` would be taken before this call.

  ## THIS NAMESPACE REQUIRES NO SCHEMA, AND THE GATE IS WHY

  The first cut of this file required `re-frame.hicasso.evidence` so it
  could hand back a versioned envelope. `npm run build:hicasso-release`
  then FAILED, and correctly:

      hicasso production erasure: FAIL
        DEV SURFACE LEAKED: evidence projection — the versioned envelope
          sentinel: \"re-frame.hicasso.evidence\"

  `re-frame.hicasso.evidence` is dev-only BY REACHABILITY — nothing under
  `src/` requires it, so a consumer who never asks for the projection
  never ships it. A tap in the collector is reachable from the public
  door by construction, so anything the tap's namespace requires becomes
  reachable too, and the schema pin landed in the `:advanced` bundle.

  So the receipt splits across that existing boundary rather than
  breaking it: RAW COUNTERS here, where the tap is; the PROJECTION in a
  dev-only consumer that is not reachable from the door. A graduated
  version puts it in `re-frame.hicasso.tool`; the spike puts it in the
  witness, which is not in any bundle at all."
  (:require [clojure.string :as str]))

;; The attached receipt table, or nil. Read at the tap point as the
;; outermost form of the guard — the same discipline, and for the same
;; reason, as `re-frame.hicasso.impl.evidence/!evidence-sink`.
(defonce !sink (atom nil))

(defn attach!
  "Start receipting. Returns the table, which is also what [[projection]]
  reads. Detach with [[detach!]]."
  []
  (reset! !sink #js {}))

(defn detach!
  "Stop receipting and drop the table."
  []
  (reset! !sink nil)
  nil)

(defn- row-for
  [^js table view]
  (or (unchecked-get table view)
      (let [row #js {"attempts" 0 "readCalls" 0 "uniqueReads" 0
                     "cacheHits" 0 "codecNodes" 0}]
        (unchecked-set table view row)
        row)))

(defn- count-nodes
  "Nodes in the element tree `el`. React elements only — a string child is
  a node, `nil` is not, and a foreign object is one node and not walked."
  [el]
  (let [n (volatile! 0)]
    (letfn [(walk [x]
              (cond
                (nil? x)     nil
                (array? x)   (doseq [c x] (walk c))
                (seq? x)     (doseq [c x] (walk c))
                :else
                (do (vswap! n inc)
                    (when-some [props (and (object? x) (unchecked-get x "props"))]
                      (when-some [kids (unchecked-get props "children")]
                        (walk kids))))))]
      (walk el))
    @n))

(defn record-attempt!
  "THE ONE TAP. Called from `run-once` with the attempt's own leavings:
  the view's name, the scratch array of read calls, the cell table, and
  the element the body returned.

  `warm?` is handed in rather than reached for, so this namespace does not
  require the collector and the direction of the dependency stays one-way."
  [^js table view ^js scratch warm-fn el]
  (let [name    (or view "anonymous")
        row     (row-for table name)
        n       (alength scratch)
        seen    (js/Set.)]
    (loop [i 0 hits 0]
      (if (== i n)
        (do (unchecked-set row "cacheHits" (+ (unchecked-get row "cacheHits") hits))
            (unchecked-set row "uniqueReads" (.-size seen)))
        (let [k (aget scratch i)]
          (.add seen (pr-str k))
          (recur (inc i) (if (warm-fn k) (inc hits) hits)))))
    (unchecked-set row "attempts" (inc (unchecked-get row "attempts")))
    (unchecked-set row "readCalls" (+ (unchecked-get row "readCalls") n))
    (unchecked-set row "codecNodes" (+ (unchecked-get row "codecNodes")
                                       (count-nodes el)))
    nil))

(defn rows
  "The receipt table as data, one row per boundary, sorted by view name."
  []
  (when-some [^js table @!sink]
    (->> (js/Object.keys table)
         sort
         (mapv (fn [view]
                 (let [^js r (unchecked-get table view)]
                   {:view         view
                    :attempts     (unchecked-get r "attempts")
                    :read-calls   (unchecked-get r "readCalls")
                    :unique-reads (unchecked-get r "uniqueReads")
                    :cache-hits   (unchecked-get r "cacheHits")
                    :codec-nodes  (unchecked-get r "codecNodes")}))))))

;; NO `projection` HERE. See the ns docstring: the envelope needs
;; `re-frame.hicasso.evidence`, and requiring it from a namespace the
;; collector taps makes the schema reachable from the public door.

