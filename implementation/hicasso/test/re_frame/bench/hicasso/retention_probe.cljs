(ns re-frame.bench.hicasso.retention-probe
  "rf2-flqpd — WHAT HOLDS THE 12 MB. A diagnostic, not a row.

  ## The observation this exists to explain

  rf2-2rtt6.4's own probe measured, while building the P0 UIx-on-subs
  frontier arm, `performance.memory.usedJSHeapSize` climbing
  34 -> 46 -> 55 -> 63 -> 75 -> 87 MB across six adapter-segment entries —
  about 12 MB per entry — with `document.body.childElementCount` at 2
  throughout, so nothing was leaking into the document. On that heap a
  FLOOR arm doing byte-identical work every round drifted 3.4 -> 7.0 ms and
  the arm-order guard refused on phase. A forced MAJOR collection between
  samples did not move it, so the memory is RETAINED and not garbage;
  hoisting the arms did not move it, so it is not call-site polymorphism;
  the floor-only positive control, which installs and destroys no adapter,
  stayed flat. ~12 MB is about every root mounted in a segment.

  rf2-2rtt6.4 MITIGATED it by running one round per page and filed this.

  ## Why this counts REFERENCES rather than taking a snapshot

  A retained-heap figure says HOW MUCH and never WHAT. The bead's next
  step is a heap snapshot with retainer paths, and a snapshot is the right
  instrument for a cause nobody can name. But two causes CAN be named, and
  naming one is cheaper to check than to search for:

    H1  every mounted boundary subscribes, and if unmounting does not
        unsubscribe, each cached subscription's watcher set grows by one
        per boundary and every watcher is a closure over a dead component.
        PREDICTS: watchers linear in cumulative roots; sub-cache ENTRIES
        flat, because the arms re-use the same query vectors every mount.

    H2  the SEGMENT ENTRY is what retains — `destroy-frame!` +
        `destroy-adapter!` + `init!` + `make-frame` leaves the previous
        incarnation reachable. PREDICTS: a step per `prepare!` with
        nothing mounted at all.

  Both are falsifiable by counting, and the two predict different SHAPES,
  which is why the driver runs mount cycles and bare segment entries as
  separate series. If every count is flat while the heap climbs, both
  hypotheses are dead and the snapshot is the next instrument — and this
  probe will have said so rather than implied it.

  ## WHAT IT FOUND — the retention is not retention

  Run on 2026-07-31, Chromium 147.0.7727.15 under Playwright, `:advanced`
  with `goog.DEBUG` false, in the bead's own shape: six adapter-segment
  entries alternating UIx and Reagent, 50 mount/unmount cycles of 4 roots
  each (200 roots, 60,000 boundaries per segment), every cycle verified at
  the DOM at 1,200 elements.

      segment   COLLECTED MB   UNCOLLECTED MB   garbage MB
      baseline      4.73            4.75            0.02
      1             5.13           17.73           12.60
      2             5.42           65.03           59.60
      3             5.52           30.92           25.39
      4             5.50           81.03           75.52
      5             5.61           12.44            6.84
      6             5.63           55.72           50.09

  **The UNCOLLECTED column is the bead's observation.** It reaches the
  reported magnitude — 12.6 MB after the first segment, 81 MB later — and
  it SAWTOOTHS rather than climbing, which a leak cannot do. The COLLECTED
  column, read at the same instants after a forced major collection,
  climbs 0.90 MB in total across six segments and settles. Repeating it
  with `RETENTION_COLLECT=page` — `window.gc({type:'major',
  execution:'sync'})` and nothing else, the same door rf2-2rtt6.4 used —
  gives 2.99 -> 5.90 MB, so the page's own collector reclaims it too and
  the CDP door is not doing something the page could not have asked for.

  So the ~12 MB per segment is GARBAGE, not retained memory: the arms
  allocate heavily, and `usedJSHeapSize` read without a collection
  immediately before it measures how much rubbish is lying about rather
  than how much is held. The floor-only control stayed flat in the
  original observation for the same reason it stays flat here — it
  allocates a fraction as much, so it leaves a fraction as much rubbish.

  **NOTHING SHIPPED IS LEAKING.** The sub-cache census (positive-controlled:
  it reads 300 entries with the roots standing and 0 after release, on
  every cycle) says every subscription is disposed on unmount, so H1 is
  dead; the collected heap does not step at a segment seam, so H2 is dead.

  **The instrument lesson stands, and rf2-2rtt6.4's mitigation was right
  for a reason it did not have:** one round per page keeps allocation
  pressure off the clock, and 30-80 MB of uncollected rubbish in a page IS
  what makes an unchanging floor arm drift 3.4 -> 7.0 ms — a scavenge
  inside a timed window is a measurement of the collector. A heap figure
  must be preceded by a forced collection AT THE READING, not merely
  somewhere earlier in the run.

  ## What this probe still cannot say

  The WATCHERS column is BLIND under `:advanced`: `watches` is a `deftype`
  field and Closure renames it, so `goog.object/get` cannot reach it from
  outside the type. The driver's positive control reports that in so many
  words, and the column must not be read as evidence that nothing is
  watching. The sub-cache ENTRIES column is the one with a working
  control, and it is the one the conclusion above rests on.

  ## What is deliberately absent

  No verdict, no threshold, no published figure, and no arm of its own:
  the arms are `re-frame.bench.p0-heap`'s, reached through the `window.P0H`
  door it already publishes, so this diagnostic cannot change what the P0
  heap row measures. Every number here is a census of live references
  beside a heap reading, printed as a SERIES so the shape — flat, linear,
  stepped — is what a reader judges. A diagnostic that adjudicated would
  be a row, and rows on this programme are operator-owned (rf2-2rtt6.1).

  Owner: rf2-flqpd."
  (:require [goog.object :as gobj]
            [re-frame.bench.p0-arms :as arms]
            [re-frame.bench.p0-heap :as heap]
            [re-frame.frame :as frame]))

;; ---------------------------------------------------------------------------
;; The census
;; ---------------------------------------------------------------------------

(defn- watch-count
  "How many watchers `x` carries, or `nil` when it is not a thing that has
  watchers.

  Duck-typed on purpose. Reagent's `Reaction`, reagent-slim's, an
  `IWatchable` atom and the React spine's container are four different
  types across three artefacts, and a census that picked one and silently
  reported `nil` for the others would be the exact shape of fault this
  programme keeps finding: a precise number over a smaller set than the
  one it names. `:watchers-of` in the reading says how many of the objects
  offered actually answered, so a census that stopped counting is visible."
  [x]
  (try
    (let [w (gobj/get x "watches")]
      (cond
        (nil? w)    nil
        (map? w)    (count w)
        (object? w) (count (gobj/getKeys w))
        :else       nil))
    (catch :default _ nil)))

(defn- sub-cache-census
  "The frame's sub-cache: entries, and the watchers its reactions carry.

  ENTRIES should be FLAT across cycles — the P0 arms subscribe to the same
  query vectors every mount and the cache keys by query-vector equality.
  WATCHERS are H1: one per live boundary, so they must return to their
  starting value once every root is unmounted."
  [frame-id]
  (try
    (if-let [f (frame/frame frame-id)]
      (if-let [cache (:sub-cache f)]
        (let [entries   (vals @cache)
              ;; The entry shape is the sub-cache's, not this probe's, so
              ;; every plausible slot is offered to `watch-count` and the
              ;; reading says how many ANSWERED. A census that assumed one
              ;; key would read a confident zero for ever.
              reactions (into [] (comp (mapcat (fn [e]
                                                 (if (map? e)
                                                   (vals e)
                                                   [e])))
                                       (remove nil?))
                              entries)
              counts    (keep watch-count reactions)]
          {:entries        (count entries)
           :reactions      (count reactions)
           :watchers-of    (count counts)
           :watchers-total (reduce + 0 counts)
           :watchers-max   (if (seq counts) (apply max counts) 0)})
        {:entries :no-sub-cache})
      {:entries :no-frame})
    (catch :default e {:error (str e)})))

(defn- projection-census
  "Watchers on the frame's two partition projections. `destroy-frame!`
  disposes these; a segment entry that left them watched would retain the
  physical container and everything reachable from it — which is H2."
  [frame-id]
  {:app-db     (try (watch-count (frame/app-db-container frame-id))
                    (catch :default _ :unreadable))
   :runtime-db (try (watch-count (frame/runtime-db-container frame-id))
                    (catch :default _ :unreadable))})

(defn census
  "One reading: the DOM, the sub-cache, the projections, the JS heap.

  `usedJSHeapSize` wants `--enable-precise-memory-info`; without it Chrome
  quantises to 100 KB buckets, which still shows a 12 MB climb perfectly
  well, so its absence is reported (`-1`) rather than fatal."
  []
  (let [mem (when (exists? js/performance) (gobj/get js/performance "memory"))]
    #js {"subCache"     (clj->js (sub-cache-census arms/frame-id))
         "projections"  (clj->js (projection-census arms/frame-id))
         "bodyChildren" (.-childElementCount js/document.body)
         "domElements"  (.-length (.querySelectorAll js/document "*"))
         ;; WHETHER THE PAGE CAN COLLECT AT ALL, reported on every reading.
         ;; rf2-flqpd's evidence that the climb was RETENTION and not
         ;; garbage is that `gc({type:'major',execution:'sync'})` between
         ;; samples did not move it. That argument is only as good as the
         ;; function existing: `--js-flags=--expose-gc` is given to the
         ;; BROWSER process and a renderer does not necessarily inherit it,
         ;; so a page can call a `gc` that is not there and a guarded call
         ;; skips silently. A probe that assumed it worked would repeat the
         ;; assumption rather than test it.
         "gcAvailable"  (boolean (gobj/get js/window "gc"))
         "usedJSHeap"   (if mem (gobj/get mem "usedJSHeapSize") -1)}))

;; ---------------------------------------------------------------------------
;; The page-side door
;; ---------------------------------------------------------------------------

(defn ^:export -main
  "Install the P0 heap instrument's own door and this census beside it,
  then stop. The DRIVER decides when anything happens, because only the
  driver can force a collection and a reading taken on the collector's
  own schedule is a reading of the collector."
  []
  (heap/install!)
  (set! (.-RETENTION js/window) #js {:census (fn [] (census))})
  (set! (.-RETENTION_READY js/window) true)
  nil)
