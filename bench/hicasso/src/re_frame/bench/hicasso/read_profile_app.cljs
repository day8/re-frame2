(ns re-frame.bench.hicasso.read-profile-app
  "THE COLD-READ MOUNT TERM, PROFILED READ-BY-READ (rf2-6c237).

  rf2-y1jkm cut the interpreter walk ~39% and its closing decomposition
  moved the surviving mount gap off the walk: hicasso in-page 3.300 ms vs
  uix 1.900 ms on the acceptance shape, concentrated in the 141
  per-instance collector reads — each a cold `rf.subs/subscribe-once`
  (subscribe + deref + unsubscribe per read per mount; cells only exist
  after commit). None of the prior instruments says where INSIDE one cold
  read the time goes. This entry answers that: the acceptance page's own
  141-read roster, performed by the shipping read path and by a family of
  single-phase ablations in one process, interleaved, plus the commit
  half (cell construction, reaction wiring, reader membership) and a
  per-op micro table.

  ## DIAGNOSTIC, not published

  The clock here is in-page `performance.now` over K roster passes per
  sample. It attributes cost BETWEEN phases of one cold read; it is not
  the clock of record and no figure from this file is a gate row. The
  published before/after stays with `census_clock_run.cjs` (raw
  TaskDuration, plumb-tared, same-run donors). Stated per the instrument
  canon so a reader cannot mistake a ratio here for a gated one.

  ## The roster under the knife, and the fidelity gates

  The profiled reads are HARVESTED, not transcribed: the real
  [[re-frame.bench.hicasso.shapes.large-template/page]] is mounted once
  and the read-set entry the mount resolved is read back through the
  runtime's own [[rf.bench.hicasso.arm1.runtime/last-reads]] — its key array IS the page's read
  sequence, in realization order, straight from the machinery under test.
  Boot is fatal unless the roster is exactly the page arithmetic's
  3 + 2 x 69 = 141 reads, all distinct, and the mounted page is the
  1,202-element acceptance page.

  Timed passes run through the runtime's public [[rf.bench.hicasso.arm1.runtime/render-body]] door —
  one door per pass, the window OUTSIDE the door — so a sample bills
  everything a mount bills per boundary render: the scratch reset, the
  generation fence's two basis reads, the read-set entry resolve, and the
  141 reads themselves. Every arm rides the identical door, so deltas
  subtract the door out.

  ## The arms (phase A — the render half)

  | arm           | one pass is                                            | what it prices |
  |---------------|--------------------------------------------------------|----------------|
  | `ship`        | 141 x `(sub q)` — the shipping collector read          | the whole render-side read term |
  | `local`       | faithful copy of the read shell + `subscribe-once`     | the FROZEN pre-rf2-6c237 path — the ablation baseline, validated against `ship` |
  | `no-shell`    | 141 x bare `rf.subs/subscribe-once`                       | `local - no-shell` = the Hicasso shell (key alloc, scratch push, cells probe, entry-hit compare) |
  | `probe`       | 141 x the candidate: cache peek, else pass-scoped value map, else fresh-memo `compute-sub-with-memo` against one frame-state snapshot | the no-churn cold read (the observation port's cold-probe discipline, value-mapped per pass) |
  | `probe-fresh` | 141 x `compute-sub` (fresh memo per read, no wrap/peek/map) | the bare-compute lower bound the candidate is priced against |
  | `floor`       | 141 x registrar lookup + raw handler call on app-db    | the irreducible compute floor |
  | `warm`        | 141 x `(sub q)` with cells COMMITTED (a second frame)  | the steady-state pure-deref read, for scale |
  | `ctl2`        | 282 x bare `subscribe-once` (the roster twice)         | positive control, predicted 2.0 x `no-shell` |

  The ablation baseline is written IN THIS NAMESPACE and validated
  against the shipping path in the same process (the rf2-2rtt6.32
  discipline: a local arm timed against a foreign one compares call
  conventions as much as phases). `local` is deliberately the
  subscribe-once path even after rf2-6c237 lands its candidate, so the
  re-run of this instrument is an in-process before/after A/B.

  The `warm` arm reads a SECOND frame with the same seed, committed once
  at boot and held, because cells are global per (frame, query): a warm
  arm on the cold frame would warm every other arm's reads.

  ## The commit half (phase B — async, settled between samples)

  What the render's cold read deliberately does not pay, the commit does:
  one durable cell per unique key — `rf.subs/subscribe`, the ACTIVATION, the
  baseline deref, the value-change watch, the disposal hook, the `!cells`
  insert — plus one reader membership per key. **That last term re-shaped under
  rf2-dabt3**: the dependency index used to be a second process-global
  structure and the commit paid an `index/mount!` plus a whole-set
  `record-reads!` into it; the readers now live on the cell, so the
  commit pushes one slot per key and the copy prices that instead.
  Phase B prices that half through the runtime's own
  [[rf.bench.hicasso.arm1.runtime/commit-boundary!]] seam on [[frames-per-window]] identically-seeded
  frames per window, released and settled between samples with a residue
  equality gate.

  **That frame count is the window's resolution and it was raised from 4
  to 32 by rf2-3l6hf**, which found that at 4 the deltas below could not
  decompose anything: three of the four terms straddled zero across runs
  of one binary, and a negative delta is arithmetically impossible when
  the ablation arm does strictly less work. 4 frames was sized to clear
  the 100 µs clock clamp and nothing more, and clearing the clamp is a
  much weaker condition than resolving a term. [[frames-per-window]]
  carries the arithmetic, including why the sample count could not have
  fixed it.

  | arm         | one window is                                     | what it prices |
  |-------------|---------------------------------------------------|----------------|
  | `commit`    | [[frames-per-window]] frames x `rf.bench.hicasso.arm1.runtime/commit-boundary!` on the harvested entry | the shipping commit half |
  | `c-local`   | faithful copy: cell mint + subscribe + activation + baseline deref + watch + dispose hook + map insert + reader membership | the ablation baseline, validated against `commit` |
  | `c-null`    | `c-local` with NOTHING ablated — the same `C-FULL` mode, under a second id | THE NEGATIVE CONTROL: `c-local - c-null` has a true cost of exactly zero, so what it reads is the instrument's own error and nothing else (rf2-3l6hf) |
  | `c-null-twin` | `c-local` again, at the slot whose kept-sample POSITION FOOTPRINT is `c-local`'s own | the POSITION null: any cost that is a function of sweep position cancels term by term between these two arms, so what it reads is error position cannot explain (rf2-lo7uy) |
  | `c-null-curve` | `c-local` again, at a slot sharing `c-local`'s MEAN position on a different footprint | the CURVATURE null: a linear position drift cancels here too, a curved one does not (rf2-lo7uy) |
  | `c-noactivate` | `c-local` minus `rf.interop/activate-derived-value!` | ON THIS HOST the uncached hook resolution, a real term (rf2-tcffa); the substrate's capture run under a ratom host (rf2-lzpfj) |
  | `c-nowatch` | `c-local` minus add-watch + the disposal hook     | the watch wiring |
  | `c-nosub`   | `c-local` with `compute-sub` in place of subscribe + deref | the reaction build + cache insert (the compute is kept, priced by the swap) |
  | `c-noreaders` | `c-local` minus the cell's reader array and the membership push | the fused reverse edge (rf2-dabt3) |
  | `c-nomap`   | `c-local` minus the per-key cells-map insert      | the cell-map insert |
  | `b-build`   | [[frames-per-window]] frames x 141 bare `subscribe` + deref (torn down sync, outside the window) | build + compute WITHOUT in-window dispose — beside `no-shell` it floors the render read's dispose/evict share |

  Every phase-B delta is quoted as `c-local - variant`, a floor on the
  phase (the stubs are not free). Teardown runs outside every window;
  a settle and a residue equality gate (runtime counters AND each probe
  frame's sub-cache emptiness) sit between samples. That settle is
  [[residue-settle!]] — the runtime's own quiescence point rather than a
  bare macrotask, and its docstring says why the difference is the
  difference between phase B reaching a number and not (rf2-981nt).

  **`c-noactivate` is a REAL TERM on this host — it is NOT the noise floor,
  and nothing may be arbitrated against it** (rf2-tcffa; this docstring
  claimed the opposite until that bead, and the claim was load-bearing). This
  app installs the UIx adapter, and no activation happens on the React-hook
  spine: that spine wires one watch per source at construction, so there is
  nothing to activate and the routed call bottoms out at nil.

  **But the LOOKUP that reaches that nil is real work, and it is the one
  lookup that can never be cached.** `:adapter/activate-derived-value!` is
  published by the ratom family ALONE, and `late-bind/get-fn-cached` memoises
  positive resolutions only — its own docstring says nil resolutions are NOT
  cached, deliberately, so that a deferred publication is visible on the next
  call. A key no adapter on this host ever publishes therefore misses both
  slots on every call: two atom derefs and two hash-map misses, once per key
  of the read set, which is 141 calls per boundary commit in this arm's own
  loop. **That multiplier is the arm's and must not travel with the number.**
  `c-local` mints a fresh cell every iteration, so this arm genuinely runs the
  cold path 141 times per window; shipping code pays the same per-call cost
  once per NEW-OR-REWIRED cell (`collector.cljs:947` on a cells-map miss,
  `:967` on a post-invalidation rewire) and once per observation-handle
  acquire (`observation.cljc:2160`), while a steady-state commit reaches
  `:968` — push a reader — and stops. Neither is per commit. rf2-19usn priced
  the mechanism at that real frequency and closed won't-fix; the per-call
  number is what makes this delta non-zero, and the every-commit reading of it
  is what audit #8328 had to retract once already (rf2-ml3kt).

  It measures accordingly. Over rf2-07rnj's three runs the arm read
  0.0422 / 0.0484 / 0.0562 ms/commit — one sign on every run, and LARGER than
  the cell-map insert in two of the three. An arm reading above a term the
  same table asks you to believe is not a floor, and reading it as one invites
  the opposite of the truth: that a window resolving its terms is failing.

  **What it can be used for is what every other arm is used for** — quoted
  beside them as the price of one unpublishable hook resolution per key. What
  it cannot do is arbitrate whether the window is wide enough, which is the
  job [[frames-per-window]] gave it and which that docstring now withdraws.
  The arithmetic-impossibility residual cannot do it either: `c-noreaders` has
  an unknown positive true cost, so a negative reading of it establishes
  neither a symmetric error band nor any bound on the term, and the
  `< 0.006 ms/commit` figure once floated for reader membership is withdrawn.

  ## The measured nulls, which are what arbitrate

  **`c-null` is the negative control this instrument lacked** (rf2-3l6hf).
  It is `c-local` again — the same `C-FULL` mode, the same work, a second id —
  so `c-local - c-null` has a true cost of EXACTLY ZERO by construction, and
  every millisecond it reads is the estimator's own error at this shape, on
  this box, against a pair of arms of this size. That is the one quantity the
  ablation deltas needed and could not supply themselves.

  **Read what it licenses and not one step more.** It measures THE
  INSTRUMENT, so it says whether a term is distinguishable from zero here. It
  does NOT bound any term's true cost: an ablation delta that lands inside the
  null's spread is a term this window cannot see, which leaves its size open
  in both directions. Promoting a null spread into an upper bound on a cost is
  the same error as the withdrawn `< 0.006`, one layer further back.

  The arm still earns its place, because the term is NOT a no-op under the
  ratom family, where a `Reaction` learns its sources only
  through `deref-capture`: the capture run retains a `watching` array per
  reaction and an entry in each source's watcher set — per-key retained heap
  the model understated for as long as the call was missing. Quoting it as
  its own ablation keeps that cost attributable the day the rig is pointed
  at a ratom host, instead of folding it silently into the `c-nosub` term.

  And under that host the delta stays clean rather than double-counting,
  because the capture run REPLACES the raw `(f)` the baseline deref would
  otherwise perform: with the activation the following deref finds a settled
  node and recomputes nothing, without it that deref runs the body raw. One
  computation either way, so `c-local - c-noactivate` is the capture
  BOOKKEEPING and not a second evaluation of the sub.

  ## Three nulls, because one null cannot say what it is measuring

  rf2-3l6hf's window read that null at **+0.0234 / +0.0219 / +0.0234
  ms/commit** — three runs of a quantity whose true value is exactly zero,
  every reading within one grid step of the others. The offset is real and
  it is stable, and its CAUSE was left open between a residual arm-position
  effect, within-sweep thermal or cache drift, and the pooled median's own
  behaviour on a right-tailed arm. One null cannot separate those, because
  one null is one pair of slots (rf2-lo7uy).

  **An arm's SLOT is a measured property of it, not a presentation
  detail.** [[rounds-async!]] visits the arms in [[rf.bench.hicasso.lane/slot-order]]'s
  order, which rotates by the sample index and reflects on odd ones — and
  it is handed the SAMPLE index, not the round, so every round runs the
  identical schedule. An arm therefore occupies the same multiset of sweep
  positions in every round of every run, and no number of rounds averages a
  positional bias away. That is worth saying beside a null which repeated to
  within a grid step across three runs: a random error would not, and a
  fixed positional bias would.

  [[slot-footprint]] computes that multiset from [[rf.bench.hicasso.lane/slot-order]] itself
  rather than restating its arithmetic. At the eleven arms this roster now
  carries and this window's `2 + 8` sampling it reads:

      slot  1  c-local       [1 3 4 5 6 7 8 10]   mean 5.500
      slot  2  c-null        [0 0 2 4 5 6 7  9]   mean 4.125
      slot  9  c-null-twin   [1 3 4 5 6 7 8 10]   mean 5.500
      slot 10  c-null-curve  [2 3 4 5 6 7 8  9]   mean 5.500

  So the three nulls ask three DIFFERENT questions of the same zero:

  - `c-null` sits on a footprint displaced from `c-local`'s in both its
    mean and its shape. It is the pair the published +0.022 was measured
    on, and it is left exactly where it was so the two windows can be read
    against each other.
  - `c-null-twin` sits on `c-local`'s footprint EXACTLY. Whatever the
    within-sweep cost curve is — linear, a first-slot warm-up, anything at
    all that is a function of position — it cancels term by term between
    these two arms. What this one reads is error that position cannot
    explain.
  - `c-null-curve` shares `c-local`'s MEAN position on a different
    footprint, so a linear drift cancels here and a curved one does not.

  **What each outcome would license.** Three nulls reading alike puts the
  offset somewhere other than sweep position — the estimator, or drift on a
  clock the schedule does not touch. `c-null` offset with the twin at zero
  puts it on position. The twin at zero and the curve away from it puts it
  on position AND says the drift is not linear. The window decides; this
  file only makes the question askable, and takes no window itself.

  **Two things the extra nulls still do not license**, both held over from
  rf2-3l6hf. Do NOT subtract a null from a published term and call the term
  corrected: `c-null` calibrates slot 1 against slot 2 while reader
  membership differences slot 1 against slot 6, and the correction would
  assume the very positional model that is under test. And do NOT read any
  null as a bound on anything — it is a measured property of the ESTIMATOR,
  not of a cost.

  **The existing arms are untouched, and the arm COUNT is not.** The two
  nulls are APPENDED, so every arm the published series quotes keeps its
  slot — `commit` 0, `c-local` 1, `c-null` 2, through `b-build` 8 — and
  every subject, mode, frame, [[frames-per-window]], [[b-rounds]] and
  [[b-sampling]] is exactly what it was. What cannot be held fixed is `n`,
  which is an input to [[rf.bench.hicasso.lane/slot-order]]: eleven arms is a different sweep
  and therefore a new series, the same way nine arms was a new series
  against the eight-arm runs before it. Absolutes are not arm-by-arm
  comparable across that line, and no reading here is.

  Owner bead: rf2-6c237. Driver: `run.cjs` with
  HICASSO_INIT_FN=re-frame.bench.hicasso.read-profile-app/-main."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.mount :as rf.bench.hicasso.arm1.mount]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.shapes.large-template :as rf.bench.hicasso.shapes.large-template]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.subs :as rf.subs]))

;; ---------------------------------------------------------------------------
;; Frames
;; ---------------------------------------------------------------------------

(def cold-frame ::cold)
(def warm-frame ::warm)

(def ^:private frames-per-window
  "How many identically-seeded frames ONE phase-B window commits
  (rf2-3l6hf). It was 4, and 4 could not decompose the commit half.

  **A window's resolution is set here and by the KEPT SAMPLE COUNT'S
  PARITY, because the reported statistic is a p50.** `rf.bench.hicasso.lane/now-ms` is
  `performance.now`, which Chrome clamps to 100 µs, so every raw window
  reading is a multiple of 0.1 ms; [[rf.bench.hicasso.lane/summarise]] takes the mean of
  the two middle order statistics on an EVEN sample count, which halves
  that to 0.05 ms, and a single order statistic on an ODD one, which does
  not halve it at all; and the row divides by the frame count. So the
  grid a phase-B delta can land on is `0.05 / frames-per-window` at even
  parity and `0.1 / frames-per-window` at odd — at 4 frames and the even
  parity of the day, 0.0125, which is precisely the spacing every delta
  rf2-d360z published turned out to be a multiple of.

  **The frame count is the only knob you would REACH for, and it is not
  the only one that moves the grid.** This docstring claimed resolution
  was set HERE AND NOWHERE ELSE; [[b-rounds]] and [[b-sampling]] are
  separate vars, no contract holds their product even, and an odd kept
  total would double the grid under a design line still printing the
  halved one (merged-PR audit of #8328). [[phase-b-grid-ms]] therefore
  DERIVES the grid from the parity rather than asserting a constant, and
  [[phase-b-design-line]] prints what it derives, so the two can no
  longer disagree. `read-profile-grid-cljs-test` pins that by driving the
  design line at both parities.

  **More SAMPLES cannot move that grid.** A median of quantised readings
  is itself a grid value whatever the sample count; more samples make the
  p50 land on the right step more often, they do not create a step
  between two others. So the sample count is a stability knob and the
  frame count is the resolution knob, and only one of them was ever going
  to make three sub-quantum terms visible. That is the reasoning
  rf2-3l6hf's window was opened to act on, and it is why the frame count
  moved by 8x rather than the sampling alone.

  32 puts the grid at 0.0015625 ms/commit. The terms that need to be seen
  are the watch wiring, the reader membership and the cell-map insert,
  which the micro table's anchors price at roughly 0.004-0.02 ms/commit —
  so the smallest of them is a few grid steps rather than a fraction of
  one. The window is ~20 ms of wall clock at the absolutes this arm
  reads, still 200x the clock's own quantum, and the run stays under a
  minute of sampling.

  **`c-noactivate` is not this window's arbiter** (rf2-tcffa). This docstring
  gave that arm the job on the ground that it ablates a routed no-op and so
  reads the instrument's own floor. It does not: the call it ablates resolves
  a hook key that is never published on this host and so can never be cached,
  which is real work — rf2-07rnj's runs read the arm LARGER than the cell-map
  insert in two of three. The namespace docstring carries the mechanism. Nor
  does the arithmetic-impossibility residual serve as a floor: `c-noreaders`
  has an unknown positive true cost, so a negative reading of it bounds
  nothing.

  **The arbiter is [[c-null]], added by rf2-3l6hf**, and it is an arbiter
  precisely because its true cost is zero by construction rather than
  argued to be small. It reads the estimator's error directly, so a term
  can be adjudicated against a measured null instead of against sign
  stability alone.

  So the case for 32 is the grid arithmetic above and nothing else, and it is
  a case about RESOLUTION, not about trust in any particular delta. What the
  null adds is the other half: resolution says how finely a delta CAN land,
  the null says how far it wanders when the thing under it is nothing."
  32)

(def commit-frames
  (mapv (fn [i] (keyword "rf-readprof.commit" (str "c" i)))
        (range 1 (inc frames-per-window))))

(defn- seed-frame! [id]
  (rf.bench.hicasso.shapes.large-template/make-frame! id)
  (rf.bench.hicasso.shapes.large-template/reseed! id)
  id)

;; ---------------------------------------------------------------------------
;; The harvest — the page's own roster, read back from the machinery
;; ---------------------------------------------------------------------------

(defn- harvest-roster!
  "Mount the REAL acceptance page on `frame-id`, read the entry the mount
  resolved, and answer `{:roster <js array of query-v> :elements n}`.
  Fatal unless the page is the 1,202-element page and the roster is the
  arithmetic's 141 distinct reads."
  [frame-id]
  (let [container (rf.bench.hicasso.arm1.mount/fresh-container!)
        handle    (rf.bench.hicasso.arm1.mount/root! container frame-id [rf.bench.hicasso.shapes.large-template/page {}])
        entry     (rf.bench.hicasso.arm1.runtime/last-reads)
        keys'     (.-keys ^js entry)
        n         (rf.bench.hicasso.lane/element-count container)
        expected  (rf.bench.hicasso.shapes.large-template/element-arithmetic)
        roster    (let [a #js []]
                    (dotimes [i (alength keys')]
                      (.push a (nth (aget keys' i) 1)))
                    a)
        distinct-n (count (into #{} (array-seq roster)))
        expected-reads (+ 3 (* 2 rf.bench.hicasso.shapes.large-template/article-count))]
    (rf.bench.hicasso.arm1.mount/unmount! handle)
    (when-not (= expected n)
      (throw (ex-info (str "harvest FAILED: mounted page has " n
                           " elements, expected " expected)
                      {:elements n :expected expected})))
    (when-not (and (= expected-reads (alength roster))
                   (= expected-reads distinct-n))
      (throw (ex-info (str "harvest FAILED: roster carries " (alength roster)
                           " reads (" distinct-n " distinct), expected "
                           expected-reads " distinct reads")
                      {:reads (alength roster) :distinct distinct-n})))
    {:roster roster :elements n}))

(defn- roster-census
  "What the 141 reads are made of — the denominator table."
  [^js roster]
  (let [by-id (reduce (fn [m q] (update m (nth q 0) (fnil inc 0)))
                      {} (array-seq roster))
        kinds (reduce (fn [m q]
                        (let [k (:input-kind (rf.registrar/lookup :sub (nth q 0)))]
                          (update m k (fnil inc 0))))
                      {} (array-seq roster))]
    {:reads      (alength roster)
     :distinct   (count (into #{} (array-seq roster)))
     :by-sub-id  by-id
     :input-kinds kinds}))

;; ---------------------------------------------------------------------------
;; The door, and the window
;; ---------------------------------------------------------------------------

(def ^:private passes-per-sample
  "Roster passes inside ONE timing window, each through its own
  render-body door. Chrome clamps `performance.now` to 100 us; eight
  141-read passes hold the window in whole milliseconds, so the clamp is
  percent-level noise. Every pass is a fresh door, so a pass never sees
  another pass's per-render state — which is what keeps the shipping
  read COLD in every pass whichever read path ships."
  8)

(defn- timed-doors
  "One sample: K render-body doors on `frame-id`, `pass!` inside each,
  one clock around all K. Answers ms for the window."
  [frame-id pass!]
  (let [t0 (rf.bench.hicasso.lane/now-ms)]
    (dotimes [_ passes-per-sample]
      (rf.bench.hicasso.arm1.runtime/render-body frame-id (fn [_] (pass!) [:span]) {}))
    (- (rf.bench.hicasso.lane/now-ms) t0)))

;; ---------------------------------------------------------------------------
;; The faithful local copy of the pre-rf2-6c237 read (the frozen baseline)
;; ---------------------------------------------------------------------------
;;
;; `read-key!`'s shape at the commit this bead opened: sub-key mint, scratch
;; push, cells probe (always a miss on a cold mount), then the
;; subscribe-once crossing; after the body, the entry-hit compare (bucket
;; hash of the whole sequence + ordered pairwise compare). The copy carries
;; its OWN scratch and its own cached key array so the shipping runtime's
;; internals stay untouched, and it is validated against `ship` in the
;; ARMS table rather than assumed equivalent.

(def ^:private local-scratch #js [])
(def ^:private local-cells {})
(def ^:private ^:mutable local-entry-keys nil)

(defn- local-bucket-hash []
  (let [n (alength local-scratch)]
    (loop [i 0 h 1]
      (if (== i n)
        h
        (recur (inc i)
               (bit-or 0 (+ (bit-shift-left h 5) (- h)
                            (hash (aget local-scratch i)))))))))

(defn- local-entry-hit? []
  (let [ks local-entry-keys
        n  (alength ks)]
    (and (== n (alength local-scratch))
         (loop [i 0]
           (cond
             (== i n)                                true
             (= (aget ks i) (aget local-scratch i))  (recur (inc i))
             :else                                   false)))))

(defn- local-pass!
  "One frozen-path pass: the shell plus the subscribe-once crossing, then
  the entry-hit resolve the runtime performs after the body."
  [frame-id ^js roster]
  (set! (.-length local-scratch) 0)
  (let [n (alength roster)]
    (dotimes [i n]
      (let [q       (aget roster i)
            sub-key [frame-id q]]
        (.push local-scratch sub-key)
        (if-some [^js r (some-> ^js (get local-cells sub-key) (.-reaction))]
          @r
          (rf.subs/subscribe-once q {:frame frame-id})))))
  (local-bucket-hash)
  (when (nil? local-entry-keys)
    (set! local-entry-keys (.slice local-scratch)))
  (local-entry-hit?))

;; ---------------------------------------------------------------------------
;; The candidate — the cold-probe discipline, per read
;; ---------------------------------------------------------------------------

(defn- probe-pass!
  "One candidate pass — the shape [[re-frame.bench.hicasso.arm1.runtime]]
  lands as `cold-read!`: per read, resolve the frame record, enter
  `call-with-frame-resolution` (the resolution seam `subscribe` itself
  reads through, and the read-time coalesced reprojection flush without
  which a same-tick `reg-sub` is invisible), peek the frame's sub-cache
  and deref a live reaction without acquire/release churn; else consult
  the pass-scoped VALUE map, and on a genuine first read compute PURE
  against the pass's one frame-state snapshot with a FRESH per-read memo
  seeded with `rf.subs/observation-opts-key` (so an unregistered read emits
  the always-on `:rf.error/no-such-sub` exactly as the reactive build
  does). The snapshot and the value map are per PASS — the render-scoped
  lifetime the candidate resets at the top of every body run. The
  BEFORE run also measured the run-SHARED threaded memo here and it lost
  to this shape by ~1 us/read (its own bookkeeping against a grown map);
  that reading is preserved in the studio page, and this arm is the
  refined candidate it selected."
  [frame-id ^js roster]
  (let [pstate #js {"fs" nil "vals" {}}
        n      (alength roster)]
    (dotimes [i n]
      (let [q            (aget roster i)
            frame-record (rf.frame/frame frame-id)]
        (rf.live-frame/call-with-frame-resolution
          frame-id
          (fn []
            (if-some [r (:reaction (get @(:sub-cache frame-record) q))]
              @r
              (if-some [kv (find (unchecked-get pstate "vals") q)]
                (val kv)
                (let [fs (or (unchecked-get pstate "fs")
                             (let [v (rf.frame/frame-state-value frame-id)]
                               (unchecked-set pstate "fs" v)
                               v))
                      v  (rf.subs/compute-sub-with-memo
                           q fs (atom {rf.subs/observation-opts-key
                                       {:frame frame-id}}))]
                  (unchecked-set pstate "vals"
                                 (assoc (unchecked-get pstate "vals") q v))
                  v)))))))))

;; ---------------------------------------------------------------------------
;; The other render arms
;; ---------------------------------------------------------------------------

(defn- once-pass! [frame-id ^js roster]
  (let [n (alength roster)]
    (dotimes [i n]
      (rf.subs/subscribe-once (aget roster i) {:frame frame-id}))))

(defn- fresh-memo-pass! [frame-id ^js roster]
  (let [fs (rf.frame/frame-state-value frame-id)
        n  (alength roster)]
    (dotimes [i n]
      (rf.subs/compute-sub (aget roster i) fs))))

(defn- floor-pass!
  "The irreducible floor: one registrar lookup and one raw layer-1
  handler call per read, against the app-db partition read once."
  [frame-id ^js roster]
  (let [app-db (:rf.db/app (rf.frame/frame-state-value frame-id))
        n      (alength roster)
        sink   (volatile! nil)]
    (dotimes [i n]
      (let [q (aget roster i)]
        (vreset! sink ((:handler-fn (rf.registrar/lookup :sub (nth q 0)))
                       app-db q))))
    @sink))

(defn- sub-pass!
  "The shipping collector read, roster order — `ship` on the cold frame,
  `warm` on the committed one."
  [^js roster]
  (let [n (alength roster)]
    (dotimes [i n]
      (sub (aget roster i)))))

;; ---------------------------------------------------------------------------
;; Phase A arms
;; ---------------------------------------------------------------------------

(defn- phase-a-arms [^js roster]
  [{:id :ship       :frame cold-frame
    :pass (fn [] (sub-pass! roster))}
   {:id :local      :frame cold-frame
    :pass (fn [] (local-pass! cold-frame roster))}
   {:id :no-shell   :frame cold-frame
    :pass (fn [] (once-pass! cold-frame roster))}
   {:id :probe      :frame cold-frame
    :pass (fn [] (probe-pass! cold-frame roster))}
   {:id :probe-fresh :frame cold-frame
    :pass (fn [] (fresh-memo-pass! cold-frame roster))}
   {:id :floor      :frame cold-frame
    :pass (fn [] (floor-pass! cold-frame roster))}
   {:id :warm       :frame warm-frame
    :pass (fn [] (sub-pass! roster))}
   {:id :ctl2       :frame cold-frame
    :pass (fn [] (once-pass! cold-frame roster) (once-pass! cold-frame roster))}])

(def ^:private sampling {:warmup 4 :samples 10})
(def ^:private rounds 6)

;; ---------------------------------------------------------------------------
;; Phase B — the commit half
;; ---------------------------------------------------------------------------

(def ^:const C-FULL 0)
(def ^:const C-NOWATCH 1)
(def ^:const C-NOSUB 2)
(def ^:const C-NOREADERS 3)
(def ^:const C-NOMAP 4)
(def ^:const C-NOACTIVATE 5)

(def ^:private cell-watch-key
  "**One constant keyword for every local cell's value-change watch**,
  because that is what the runtime this file copies now installs
  (`arm1/runtime.cljs`'s own `cell-watch-key`, rf2-aqgr2). It used to mint
  `(keyword \"rf-readprof\" (str \"w\" (vswap! counter inc)))` per cell, which
  was faithful to the runtime of the day and stopped being so the moment
  the runtime dropped its counter — a copy that prices a cell shape the
  original no longer builds is an instrument measuring itself (rf2-6wh9o).

  Uniqueness is structural here for the same reason it is there: a mode's
  cells are built one per key of a read SET, `rf.subs/subscribe` hands back
  the cached reaction for that `(frame, query)`, and [[rounds-async!]]
  runs every teardown and clears every sub-cache between arms behind the
  residue gate — so no two live cells ever hold the same reaction. The
  namespace is this file's own, so it cannot collide with the runtime's
  watches when the `commit` arm and a `c-*` arm touch the same cached
  reactions."
  ::rp-cell-watch)

(defn- commit-local!
  "Faithful copy of the commit half `make-subscribe` performs for one
  boundary at `mode`: the registration object, one cell per key of the
  read SET (`rf.subs/subscribe` + the ACTIVATION + the baseline deref + the
  value-change watch + the disposal hook + the map insert), each cell
  taking the registration onto its reader list. Answers a teardown fn
  that mirrors the returned unsubscribe closure — run OUTSIDE the window.

  **The activation is `wire-cell!`'s, in `wire-cell!`'s order** — activate,
  baseline, watch (rf2-lzpfj, transcribing the rf2-2kshh repair). It is not
  decoration: this arm's entire claim is that it transcribes `wire-cell!`,
  and these arms are deliberately LOCAL COPIES of shipping code, so they
  drift by construction — the rf2-2rtt6.32 call-convention discipline is
  the standing answer to exactly that, and rf2-6wh9o is the same lesson
  already learnt once on `cell-watch-key` below. A copy that prices a cell
  shape the original no longer builds is an instrument measuring itself.

  The stubs, stated: `c-nosub` keeps the computation (a `compute-sub`
  against the frame-state snapshot) so its delta prices the reaction
  build + cache insert rather than build-plus-compute; `c-noactivate`
  skips the activation alone (see the namespace docstring — nothing
  activates on this UIx host, but the hook resolution it ablates can never
  be cached, so the arm prices real work and is NOT a floor (rf2-tcffa); a
  real capture run under the ratom family, and quoted separately so it
  stays attributable either way); `c-nowatch` skips
  both the watch and the disposal hook; the disposal hook and the watch
  callback are no-ops rather than the arm's real repair fns, which is a
  floor in the stubs' favour.

  `c-noreaders` is the rf2-dabt3 ablation and it is the whole reason the
  arm survived the fusion: it drops the cell's `readers` array and the
  membership push, so `c-local - c-noreaders` prices the fused reverse
  edge on the same instrument that used to price the retired index's
  `mount!` + `record-reads!` pair. Attribution, not assertion."
  [mode frame-id reads-set fs]
  (let [reg   #js {"reads" reads-set "notify" (fn [] nil)}
        cells #js []
        !map  (volatile! {})]
    (doseq [sub-key reads-set]
      (let [q  (nth sub-key 1)
            r  (if (identical? mode C-NOSUB)
                 nil
                 (rf.subs/subscribe q {:frame frame-id}))
            ^js cell #js {"subKey"   sub-key
                          "frameKw"  frame-id
                          "queryV"   q
                          "reaction" r
                          "epoch"    (rf.bench.hicasso.arm1.runtime/commit-basis frame-id)
                          "disposed" false}]
        (if (identical? mode C-NOSUB)
          (rf.subs/compute-sub q fs)
          (do ;; ACTIVATE, then baseline, then watch — `wire-cell!`'s order.
              (when-not (identical? mode C-NOACTIVATE)
                (rf.interop/activate-derived-value! r))
              @r
              (when-not (identical? mode C-NOWATCH)
                (add-watch r cell-watch-key (fn [_ _ _ _] nil))
                (rf.interop/add-on-dispose! r (fn [] nil)))))
        ;; The fused reverse edge: one slot per key, which is the
        ;; boundary's edge and its reference at once (rf2-dabt3). The
        ;; array is minted with the registration already in it, exactly
        ;; as `acquire-cell!`'s `#js []` + `.push` leaves it at fan-out 1
        ;; — which IS the fan-out on this roster, every key distinct.
        (when-not (identical? mode C-NOREADERS)
          (unchecked-set cell "readers" #js [reg]))
        (.push cells cell)
        (when-not (identical? mode C-NOMAP)
          (vswap! !map assoc sub-key cell))))
    (unchecked-set reg "cells" cells)
    (fn teardown []
      (dotimes [i (alength cells)]
        (let [^js cell (aget cells i)]
          (when-some [r (.-reaction cell)]
            (remove-watch r cell-watch-key)
            (rf.subs/unsubscribe frame-id (.-queryV cell))))))))

(defn- build-only!
  "141 bare `subscribe` + deref on `frame-id`, holding every reference.
  Answers the teardown (a plain `unsubscribe` per key, synchronous
  dispose at 1 -> 0)."
  [frame-id ^js roster]
  (let [n (alength roster)]
    (dotimes [i n]
      @(rf.subs/subscribe (aget roster i) {:frame frame-id}))
    (fn teardown []
      (dotimes [i n]
        (rf.subs/unsubscribe frame-id (aget roster i))))))

(def phase-b-arm-ids
  "The phase-B roster IN SLOT ORDER — **the vector index IS the arm's
  slot**, and a slot is a measured property of the arm (see the namespace
  docstring's three-nulls section, rf2-lo7uy).

  ONE authority. `-main` reported from a second copy of this list until
  rf2-lo7uy, and a second copy is the shape where an arm gets sampled,
  torn down and residue-gated on every sample and then never appears in a
  row — invisible, because the missing arm is missing from the output that
  would have shown it. [[phase-b-arms]] refuses to answer a roster that
  disagrees with this one.

  **The two nulls are appended and nothing is reordered**, so every slot
  rf2-3l6hf's published window quotes is the slot it quoted."
  [:commit :c-local :c-null :c-noactivate :c-nowatch
   :c-nosub :c-noreaders :c-nomap :b-build :c-null-twin :c-null-curve])

(def null-arm-ids
  "Every arm whose delta against `c-local` has a TRUE COST OF EXACTLY ZERO
  by construction — each is `c-local` again, the same `C-FULL` mode
  through the same constructor under another id, differing from it in
  nothing but its slot.

  Three rather than one because one null is one pair of slots and so
  cannot say whether the offset it reads is positional (rf2-lo7uy)."
  [:c-null :c-null-twin :c-null-curve])

(def ^:private delta-arm-ids
  "The arms quoted and recorded as `c-local - arm`: every arm but the
  shipping reference it is validated against, the base itself, and
  `b-build`, which is not an ablation of `c-local` at all. Derived from
  [[phase-b-arm-ids]] so a new arm cannot be sampled and then silently
  dropped from the per-round record."
  (into [] (remove #{:commit :c-local :b-build}) phase-b-arm-ids))

(defn phase-b-arms
  "`entries` is {frame-id entry}; `sets` is {frame-id read-set};
  `fss` is {frame-id frame-state-value} — read at setup, outside windows.

  Public so a witness can read the roster it BUILDS rather than the roster
  it declares: every `:run` here is a closure, so the arms can be
  constructed without a frame, a clock or a window."
  [entries sets fss ^js roster]
  (let [mk-local (fn [mode]
                   (fn []
                     (mapv (fn [f] (commit-local! mode f (get sets f) (get fss f)))
                           commit-frames)))
        ;; Every null is built from the same `mk-local` as `c-local` rather
        ;; than transcribed beside it, because a null whose code path could
        ;; drift from the arm it nulls is not one (rf2-3l6hf).
        arms [{:id :commit
               :run (fn []
                      (mapv (fn [f] (rf.bench.hicasso.arm1.runtime/commit-boundary! (get entries f) (fn [] nil)))
                            commit-frames))}
              {:id :c-local   :run (mk-local C-FULL)}
              {:id :c-null    :run (mk-local C-FULL)}
              {:id :c-noactivate :run (mk-local C-NOACTIVATE)}
              {:id :c-nowatch :run (mk-local C-NOWATCH)}
              {:id :c-nosub   :run (mk-local C-NOSUB)}
              {:id :c-noreaders :run (mk-local C-NOREADERS)}
              {:id :c-nomap   :run (mk-local C-NOMAP)}
              {:id :b-build
               :run (fn [] (mapv (fn [f] (build-only! f roster)) commit-frames))}
              ;; The two slot nulls, APPENDED so no existing arm moves.
              {:id :c-null-twin  :run (mk-local C-FULL)}
              {:id :c-null-curve :run (mk-local C-FULL)}]]
    (when-not (= phase-b-arm-ids (mapv :id arms))
      (throw (ex-info (str "phase-B roster disagrees with `phase-b-arm-ids` — built "
                           (pr-str (mapv :id arms)) ", declared "
                           (pr-str phase-b-arm-ids))
                      {:built (mapv :id arms) :declared phase-b-arm-ids})))
    arms))

(def ^:private b-sampling
  "The stability half of the rf2-3l6hf widening — see
  [[frames-per-window]] for the resolution half and for why the two are
  different knobs. 8 rounds x 8 kept samples is 64 against the old 24:
  the arm's own distribution has a long right tail (a `max` around twice
  the `p50`, GC landing inside a window), and a 24-sample median of that
  moves a grid step or two between runs on its own. This does not buy a
  finer grid and is not asked to."
  {:warmup 2 :samples 8})

(def ^:private b-rounds 8)

(defn phase-b-shape
  "The phase-B window shape as data: `{:rounds :sampling :frames}`.

  ONE authority, read by the design line and by
  `read-profile-grid-cljs-test`'s live-shape row. A witness that
  restated the three numbers would go green on the shape it remembered
  rather than the shape the window runs, which is the same vacuity
  `read-profile-baseline-cljs-test` was written to avoid."
  []
  {:rounds b-rounds :sampling b-sampling :frames frames-per-window})

;; ---------------------------------------------------------------------------
;; Where an arm actually sits in the sweep (rf2-lo7uy)
;; ---------------------------------------------------------------------------

(defn slot-positions
  "The sweep POSITIONS `slot` occupies across ONE round's KEPT samples,
  with `n` arms under [[rf.bench.hicasso.lane/slot-order]] and this `sampling`.

  Read OUT of the schedule, never restated: the positions come from
  `rf.bench.hicasso.lane/slot-order` itself, which takes them from the order guard, so a
  change to the plan moves these numbers instead of leaving them behind
  as a second authority nothing holds in step.

  Warm-up samples are excluded because the row is, and the row is what
  carries the offset — [[rounds-async!]] runs every sample and collects
  from `s >= warmup`. **The schedule does not vary by round**: it is
  indexed by the sample, so this multiset is the arm's footprint in every
  round of every run, and rounds cannot average a positional bias out of
  it."
  [n slot {:keys [warmup samples]}]
  (into []
        (map (fn [s]
               (let [order (rf.bench.hicasso.lane/slot-order n s)]
                 (first (keep-indexed (fn [pos j] (when (= j slot) pos)) order)))))
        (range warmup (+ warmup samples))))

(defn slot-footprint
  "`{:positions <sorted> :mean-position <ms-weight>}` for one slot.

  The MEAN is what a linear within-sweep drift would price; the sorted
  MULTISET is what any drift at all would, which is why both are kept.
  Two arms sharing a footprint cancel every position-driven cost between
  them exactly, whatever shape that cost has."
  [n slot sampling]
  (let [ps (slot-positions n slot sampling)]
    {:positions     (vec (sort ps))
     :mean-position (/ (reduce + ps) (count ps))}))

(defn phase-b-slot-plan
  "Every phase-B arm with its slot and its kept-sample position
  footprint, in slot order — recorded into the transcript so a published
  window carries the layout it was taken on and can be adjudicated
  without anyone re-deriving the schedule by hand (rf2-lo7uy)."
  ([] (phase-b-slot-plan phase-b-arm-ids (:sampling (phase-b-shape))))
  ([arm-ids sampling]
   (let [n (count arm-ids)]
     (mapv (fn [slot id]
             (assoc (slot-footprint n slot sampling) :id id :slot slot))
           (range n) arm-ids))))

(def clock-clamp-ms
  "The quantum of [[rf.bench.hicasso.lane/now-ms]] on this host: Chrome clamps
  `performance.now` to 100 µs, so the difference of two readings — which
  is every raw window sample phase B takes — is a multiple of 0.1 ms."
  0.1)

(defn phase-b-grid-ms
  "The grid one phase-B row or delta can land on, in ms/commit, DERIVED
  from the shape rather than asserted (merged-PR audit of #8328).

  Three steps, and the middle one is the one that was being taken for
  granted. Raw samples are multiples of [[clock-clamp-ms]].
  [[rf.bench.hicasso.lane/summarise]]'s p50 is the MEAN OF THE TWO MIDDLE order statistics
  when the kept count is even, which puts it on a half-clamp grid, and a
  SINGLE order statistic when it is odd, which leaves it on the full
  clamp. The row then divides by the frame count. A delta is a difference
  of two p50s, so it lands on the same grid either way.

  `rounds` x `:samples` is the kept count, and nothing in this file
  constrains its parity: [[b-rounds]] and [[b-sampling]] are independent
  vars, so an editor moving either could halve the instrument's
  resolution while the design line went on advertising the finer grid.
  Deriving it here is what stops that — the number printed and the number
  the arithmetic supports are now one expression."
  [rounds {:keys [samples]} frames]
  (let [kept (* rounds samples)]
    (/ (if (even? kept) (/ clock-clamp-ms 2.0) clock-clamp-ms)
       frames)))

(defn residue-settle!
  "**The one point behind which every phase-B residue reading is taken** —
  the baseline, the between-sample gate, and the final zero.

  It is [[rf.bench.hicasso.arm1.runtime/quiesced!]] and not [[rf.bench.hicasso.lane/settle!]], and the difference is
  the whole of rf2-981nt. `settle!` yields ONE macrotask, which is the
  right point for a substrate that queues its disposals there and the
  wrong one for a runtime that arms its entry reaper at a horizon
  deliberately OUTSIDE a bare `setTimeout 0` (rf2-2rtt6.84, so an
  unclaimed entry survives long enough for `hydrateRoot`'s passive
  subscribe to claim it). Phase B's setup harvests one unclaimed entry
  per commit frame; baselined a macrotask later they are all still
  cached, and they are gone by the first sampled arm. The gate compares
  by equality — correctly, it is a count of live references — so it saw
  six entries and then five and threw, every run.

  So the baseline moves to where the runtime has actually settled. That
  is the better instrument on its own terms: a baseline that holds
  because the runtime has quiesced is evidence, where one that holds
  because nothing has had time to happen yet is a coincidence with a
  four-millisecond shelf life.

  The arms are unaffected. A reaped entry is dropped from the runtime's
  entry CACHE, not destroyed — the object survives in the closure phase B
  holds — and [[rf.bench.hicasso.arm1.runtime/commit-boundary!]] reads the entry, never the cache,
  so the `commit` arm acquires the same 141 cells through the same seam
  either way.

  **Named rather than spelled out three times**, because this defect was
  invisible for exactly as long as the concept was unnamed: three
  `rf.bench.hicasso.lane/settle!` calls look like three ordinary yields, and there was
  nowhere for the reason to live. Public because
  `read-profile-baseline-cljs-test` drives THIS fn — a witness that
  called `rf.bench.hicasso.arm1.runtime/quiesced!` itself would go green however this instrument
  settled, which is the vacuous test that let the horizon move under a
  faithful rig in the first place."
  []
  (rf.bench.hicasso.arm1.runtime/quiesced!))

(defn- probe-caches-empty? []
  (every? (fn [f] (zero? (count @(:sub-cache (rf.frame/frame f)))))
          commit-frames))

(defn- rounds-async!
  "The reflecting-schedule sampler, promise-chained: every sample index
  visits every arm in [[rf.bench.hicasso.lane/slot-order]]'s order; warm-up samples are
  taken and discarded; between samples the arm's teardowns run, the
  runtime settles behind [[residue-settle!]], and the residue gate must
  answer clean. Mirrors `rf.bench.hicasso.lane/rounds!`, which cannot yield.

  **Readings come back BUCKETED BY ROUND**, one map per round, which is
  what `rf.bench.hicasso.lane/rounds!` has always answered and what this fn used to
  flatten into a single bucket. Pooling is unchanged — [[arm-rows]]
  `mapcat`s the buckets before it summarises, so the published p50 is the
  same number over the same 64 samples — but the per-round structure now
  survives into the record, and a published window can be re-adjudicated
  without being re-taken (rf2-3l6hf)."
  [arms {:keys [warmup samples]} rounds' baseline]
  (let [k    (count arms)
        coll (rf.bench.hicasso.lane/sample-collector)
        acc  (atom (vec (repeat rounds' (zipmap (map :id arms) (repeat [])))))]
    (-> (rf.bench.hicasso.lane/chain
          nil
          (for [round (range rounds')
                s     (range (+ warmup samples))
                j     (rf.bench.hicasso.lane/slot-order k s)]
            [round s j])
          (fn [_ [round s j]]
            (let [{:keys [id run]} (nth arms j)
                  t0        (rf.bench.hicasso.lane/now-ms)
                  teardowns (run)
                  ms        (- (rf.bench.hicasso.lane/now-ms) t0)]
              (doseq [t teardowns] (t))
              (-> (residue-settle!)
                  (.then
                    (fn [_]
                      (let [now (rf.bench.hicasso.arm1.runtime/residue)]
                        (when-not (and (= baseline now) (probe-caches-empty?))
                          (throw (ex-info (str "phase-B residue after " (name id)
                                               " — expected " (pr-str baseline)
                                               ", found " (pr-str now)
                                               (when-not (probe-caches-empty?)
                                                 ", and a probe frame's sub-cache is not empty"))
                                          {:arm id :baseline baseline :residue now})))
                        (when (>= s warmup)
                          (rf.bench.hicasso.lane/collect! coll (name id) ms)
                          (swap! acc update-in [round id] conj ms))
                        nil)))))))
        (.then (fn [_] {:readings @acc :samples (:samples @coll)})))))

;; ---------------------------------------------------------------------------
;; Micro benches — the per-read primitives
;; ---------------------------------------------------------------------------

(defn- ns-per-op
  [reps ^js arr f]
  (let [sink (volatile! nil)
        n    (.-length arr)
        t0   (rf.bench.hicasso.lane/now-ms)]
    (dotimes [_ reps]
      (dotimes [i n]
        (vreset! sink (f (aget arr i)))))
    (let [ms (- (rf.bench.hicasso.lane/now-ms) t0)]
      (/ (* 1e6 ms) (* reps n)))))

(defn- micro-table [^js roster]
  (let [fs     (rf.frame/frame-state-value cold-frame)
        app-db (:rf.db/app fs)
        cache  (:sub-cache (rf.frame/frame cold-frame))]
    [[:subscribe-once     (ns-per-op 20 roster (fn [q] (rf.subs/subscribe-once q {:frame cold-frame})))]
     [:compute-sub        (ns-per-op 20 roster (fn [q] (rf.subs/compute-sub q fs)))]
     [:handler-invoke     (ns-per-op 200 roster (fn [q] ((:handler-fn (rf.registrar/lookup :sub (nth q 0))) app-db q)))]
     [:registrar-lookup   (ns-per-op 200 roster (fn [q] (rf.registrar/lookup :sub (nth q 0))))]
     [:frame-state-value  (ns-per-op 200 roster (fn [_] (rf.frame/frame-state-value cold-frame)))]
     [:resolution-wrap    (ns-per-op 200 roster (fn [_] (rf.live-frame/call-with-frame-resolution cold-frame (fn [] nil))))]
     [:cache-peek-miss    (ns-per-op 200 roster (fn [q] (:reaction (get @cache q))))]
     [:sub-key-mint       (ns-per-op 200 roster (fn [q] [cold-frame q]))]]))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- fmt [x n] (.toFixed ^number x n))

(defn phase-b-design-line
  "The `design …` line phase B prints, as a pure fn of the shape, so the
  parity claim inside it can be witnessed without running a window.

  It prints the kept count, names its parity and says which statistic
  that parity makes the p50, because the grid is only checkable by a
  reader who is told all three (merged-PR audit of #8328). The number at
  the end is [[phase-b-grid-ms]]'s, not a constant standing beside it."
  [rounds {:keys [warmup samples] :as sampling} frames]
  (let [kept       (* rounds samples)
        even-kept? (even? kept)]
    (str ";;   design " rounds "x(" warmup "+" samples ")"
         "  window = " frames " frames/commit each"
         "  kept = " kept " samples/arm (" (if even-kept? "EVEN" "ODD")
         " — p50 is " (if even-kept?
                        "the mean of two middle clock readings"
                        "a single clock reading")
         ")  grid = " (fmt (phase-b-grid-ms rounds sampling frames) 6)
         " ms/commit")))

(defn- arm-rows [arm-ids readings per-window]
  (into {}
        (map (fn [id]
               (let [xs (mapcat #(get % id) readings)]
                 [id (rf.bench.hicasso.lane/summarise (mapv #(/ % per-window) xs))])))
        arm-ids))

(defn- per-round-p50s
  "`{arm-id [p50-of-round-0 p50-of-round-1 …]}` in ms/commit — the same
  division by `per-window` [[arm-rows]] performs, applied WITHIN each
  round instead of across the pool.

  **Recorded so a published window is re-adjudicable without a re-run.**
  A pooled p50 answers one question and refuses every follow-up: whether
  a term's sign held round by round, whether one round carried a
  contaminated span, how far the null control wandered inside a single
  run. All three were asked of windows that had kept only the pool, and
  the only way to answer was to take the window again (rf2-3l6hf)."
  [arm-ids readings per-window]
  (into {}
        (map (fn [id]
               [id (mapv (fn [round]
                           (rf.bench.hicasso.lane/round4
                             (:p50 (rf.bench.hicasso.lane/summarise
                                     (mapv #(/ % per-window) (get round id))))))
                         readings)]))
        arm-ids))

(defn- per-round-deltas
  "`c-local - arm`, per round, for each ablation arm — the per-round form
  of the delta lines, on the same values [[per-round-p50s]] records."
  [p50s base-id arm-ids]
  (let [base (get p50s base-id)]
    (into {}
          (map (fn [id]
                 [id (mapv (fn [b a] (rf.bench.hicasso.lane/round4 (- b a)))
                           base (get p50s id))]))
          arm-ids)))

(defn- us-per-read [ms] (* 1e3 (/ ms 141)))

(defn- arm-line [id {:keys [p50 min max]}]
  (str ";;   " (name id) ": p50 " (fmt p50 4)
       " [" (fmt min 4) " - " (fmt max 4) "] ms/pass  ("
       (fmt (us-per-read p50) 2) " us/read)"))

(defn- delta-line [label base p50]
  (let [d (- base p50)]
    (str ";;   " label ": delta " (fmt d 4) " ms/pass ("
         (fmt (us-per-read d) 2) " us/read, "
         (fmt (* 100 (/ d base)) 1) "% of the baseline)")))

(defn ^:export -main []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (seed-frame! cold-frame)
          (seed-frame! warm-frame)
          (let [{:keys [roster elements]} (harvest-roster! cold-frame)
                census (roster-census roster)]
            (js/console.log (str ";; harvest OK — " elements " elements, "
                                 (:reads census) " reads ("
                                 (:distinct census) " distinct), from the real page's own entry"))
            ;; Commit the warm frame's cells once, and hold them.
            (rf.bench.hicasso.arm1.runtime/render-body warm-frame (fn [_] (sub-pass! roster) [:span]) {})
            (let [warm-release (rf.bench.hicasso.arm1.runtime/commit-boundary! (rf.bench.hicasso.arm1.runtime/last-reads) (fn [] nil))]
              (-> (rf.bench.hicasso.lane/settle!)
                  (.then
                    (fn [_]
                      ;; ---- Phase A: the render half, sync + interleaved.
                      (let [arms  (phase-a-arms roster)
                            {:keys [readings samples]}
                            (rf.bench.hicasso.lane/rounds! arms sampling rounds
                                          (fn [{:keys [frame pass]}]
                                            (timed-doors frame pass)))
                            rows  (arm-rows (map :id arms) readings passes-per-sample)
                            gv    (rf.bench.hicasso.lane/guard! samples "read-profile phase A (in-page ms, diagnostic)")
                            ctl   (rf.bench.hicasso.lane/control-verdict
                                    (* 2.0 (:p50 (get rows :no-shell)))
                                    (let [s (get rows :ctl2)]
                                      {:min (:min s) :max (:max s) :mean (:p50 s)})
                                    0.25)]
                        (when-not (:ok? ctl)
                          (throw (ex-info (str "phase-A positive control failed: " (:why ctl)) {})))
                        ;; The warm frame's cells must have stayed committed and
                        ;; the cold frame must have stayed cold.
                        (let [{:keys [cells cell-refs]} (rf.bench.hicasso.arm1.runtime/stats)]
                          (when-not (and (= 141 cells) (= 141 cell-refs))
                            (throw (ex-info (str "phase-A residue: cells " cells
                                                 " refs " cell-refs ", expected 141/141 "
                                                 "(the warm frame's held commit and nothing else)")
                                            {}))))
                        (rf.bench.hicasso.lane/record! :read-profile-census census)
                        (rf.bench.hicasso.lane/record! :read-profile-arms
                                      (into {} (map (fn [[k v]] [k (-> v (update :min rf.bench.hicasso.lane/round4)
                                                                       (update :max rf.bench.hicasso.lane/round4)
                                                                       (update :p50 rf.bench.hicasso.lane/round4))])) rows))
                        (js/console.log ";; ==== READ PROFILE, PHASE A (ms per 141-read pass; diagnostic in-page clock) ====")
                        (js/console.log (str ";;   reads/pass 141  passes/sample " passes-per-sample
                                             "  design " rounds "x(" (:warmup sampling) "+" (:samples sampling) ")"))
                        (doseq [{:keys [id]} arms]
                          (js/console.log (arm-line id (get rows id))))
                        (js/console.log ";; ==== PHASE A DELTAS (floors; stubs stated in the ns docstring) ====")
                        (let [ship  (:p50 (get rows :ship))
                              local (:p50 (get rows :local))]
                          (js/console.log (str ";;   copy fidelity: local/ship = " (fmt (/ local ship) 4)
                                               "  (local is the FROZEN subscribe-once path)"))
                          (js/console.log (delta-line "the-hicasso-shell (local - no-shell)" local (:p50 (get rows :no-shell))))
                          (js/console.log (delta-line "churn-vs-probe (local - probe, the candidate's saving)" local (:p50 (get rows :probe))))
                          (js/console.log (delta-line "memo-economy (probe-fresh - probe)" (:p50 (get rows :probe-fresh)) (:p50 (get rows :probe))))
                          (js/console.log (delta-line "probe-overhead (probe - floor)" (:p50 (get rows :probe)) (:p50 (get rows :floor))))
                          (js/console.log (str ";;   warm steady-state: " (fmt (:p50 (get rows :warm)) 4)
                                               " ms/pass (" (fmt (us-per-read (:p50 (get rows :warm))) 2) " us/read)"))
                          (js/console.log (str ";;   control: " (:why ctl))))
                        (when (:refuse? gv)
                          (set! (.-HICASSO_GUARD_REFUSED js/window) true))
                        ;; ---- Phase B setup: [[frames-per-window]]
                        ;; identically-seeded frames (32, not the 4 this
                        ;; comment named until rf2-3l6hf), entries
                        ;; harvested through the door.
                        (doseq [f commit-frames] (seed-frame! f))
                        (let [entries (into {} (map (fn [f]
                                                      (rf.bench.hicasso.arm1.runtime/render-body f (fn [_] (sub-pass! roster) [:span]) {})
                                                      [f (rf.bench.hicasso.arm1.runtime/last-reads)]))
                                            commit-frames)
                              sets    (into {} (map (fn [f] [f (rf.bench.hicasso.arm1.runtime/reads-of (get entries f))])) commit-frames)
                              fss     (into {} (map (fn [f] [f (rf.frame/frame-state-value f)])) commit-frames)]
                          (-> (residue-settle!)
                              (.then (fn [_]
                                       (let [baseline (rf.bench.hicasso.arm1.runtime/residue)]
                                         (rounds-async! (phase-b-arms entries sets fss roster)
                                                        b-sampling b-rounds baseline))))
                              (.then
                                (fn [{:keys [readings samples]}]
                                  (let [ids  phase-b-arm-ids
                                        plan (phase-b-slot-plan)
                                        rows (arm-rows ids readings frames-per-window)
                                        p50s (per-round-p50s ids readings frames-per-window)
                                        gv-b (rf.bench.hicasso.lane/guard! samples "read-profile phase B (in-page ms, diagnostic)")]
                                    (rf.bench.hicasso.lane/record! :read-profile-commit
                                                  (into {} (map (fn [[k v]] [k (-> v (update :min rf.bench.hicasso.lane/round4)
                                                                                   (update :max rf.bench.hicasso.lane/round4)
                                                                                   (update :p50 rf.bench.hicasso.lane/round4))])) rows))
                                    (rf.bench.hicasso.lane/record! :read-profile-commit-per-round p50s)
                                    (rf.bench.hicasso.lane/record! :read-profile-commit-per-round-deltas
                                                  (per-round-deltas p50s :c-local delta-arm-ids))
                                    (rf.bench.hicasso.lane/record! :read-profile-slot-plan
                                                  (mapv #(update % :mean-position rf.bench.hicasso.lane/round4) plan))
                                    (js/console.log ";; ==== READ PROFILE, PHASE B — THE COMMIT HALF (ms per 141-key boundary commit) ====")
                                    (js/console.log (phase-b-design-line b-rounds b-sampling frames-per-window))
                                    (js/console.log ";;   slot plan (each arm's kept-sample sweep positions — the schedule is indexed by SAMPLE, so this footprint repeats identically every round; rf2-lo7uy):")
                                    (doseq [{:keys [id slot positions mean-position]} plan]
                                      (js/console.log (str ";;     slot " slot " " (name id)
                                                           ": " (pr-str positions)
                                                           "  mean " (fmt mean-position 3))))
                                    (doseq [id ids]
                                      (js/console.log (arm-line id (get rows id))))
                                    (js/console.log ";; ==== PHASE B DELTAS (c-local minus ablation; floors) ====")
                                    (let [commit' (:p50 (get rows :commit))
                                          clocal  (:p50 (get rows :c-local))]
                                      (js/console.log (str ";;   copy fidelity: c-local/commit = " (fmt (/ clocal commit') 4)))
                                      (js/console.log (delta-line "NULL CONTROL (c-local - c-null)" clocal (:p50 (get rows :c-null))))
                                      (js/console.log ";;     ^ true cost EXACTLY ZERO by construction — both arms are C-FULL. What it reads is this instrument's own error at this shape, and it is what the terms below are adjudicated against (rf2-3l6hf). It bounds no term's cost: a delta inside this spread is a term the window cannot SEE, which leaves its size open in both directions")
                                      (js/console.log ";;     ^ slot 1 against slot 2, on footprints that differ in BOTH mean position and shape. The two nulls below hold that zero and move the SLOT, which is what makes the offset's cause readable rather than only its size (rf2-lo7uy)")
                                      (js/console.log (delta-line "NULL CONTROL, position TWIN (c-local - c-null-twin)" clocal (:p50 (get rows :c-null-twin))))
                                      (js/console.log ";;     ^ same zero, and this arm's kept-sample position footprint IS c-local's, so ANY cost that is a function of sweep position cancels term by term. A reading here is error sweep position cannot explain; a reading of zero here beside a non-zero c-null puts the offset ON position")
                                      (js/console.log (delta-line "NULL CONTROL, equal MEAN position (c-local - c-null-curve)" clocal (:p50 (get rows :c-null-curve))))
                                      (js/console.log ";;     ^ same zero, c-local's MEAN position on a DIFFERENT footprint: a linear within-sweep drift cancels here, a curved one does not. Read the three nulls together — alike means the offset is not positional; c-null alone means it is; c-null and this one means it is and is not linear")
                                      (js/console.log (delta-line "activation-capture (c-local - c-noactivate)" clocal (:p50 (get rows :c-noactivate))))
                                      (js/console.log ";;     ^ a REAL term here, NOT a floor and not an arbiter: nothing activates on this UIx host, but the hook key is never published and so never cached, and this prices that lookup (rf2-tcffa, rf2-19usn). The capture itself is real only under the ratom family (rf2-lzpfj)")
                                      (js/console.log (delta-line "watch-wiring (c-local - c-nowatch)" clocal (:p50 (get rows :c-nowatch))))
                                      (js/console.log (delta-line "reaction-build+cache-insert (c-local - c-nosub)" clocal (:p50 (get rows :c-nosub))))
                                      (js/console.log (delta-line "reader-membership (c-local - c-noreaders)" clocal (:p50 (get rows :c-noreaders))))
                                      (js/console.log (delta-line "cell-map-insert (c-local - c-nomap)" clocal (:p50 (get rows :c-nomap))))
                                      (js/console.log (str ";;   b-build (build+compute, no in-window dispose): "
                                                           (fmt (:p50 (get rows :b-build)) 4) " ms/pass ("
                                                           (fmt (us-per-read (:p50 (get rows :b-build))) 2) " us/read)")))
                                    (when (:refuse? gv-b)
                                      (set! (.-HICASSO_GUARD_REFUSED js/window) true))
                                    ;; ---- Micro table.
                                    (let [micro (micro-table roster)]
                                      (rf.bench.hicasso.lane/record! :read-profile-micro
                                                    (into {} (map (fn [[k v]] [k (rf.bench.hicasso.lane/round4 v)])) micro))
                                      (js/console.log ";; ==== MICRO (ns/op over the page's own roster) ====")
                                      (doseq [[k v] micro]
                                        (js/console.log (str ";;   " (name k) ": " (fmt v 1) " ns"))))
                                    ;; ---- Teardown: release the warm hold, verify.
                                    ;; Behind [[residue-settle!]] like every
                                    ;; other residue reading: releasing the warm
                                    ;; boundary arms the warm entry's reaper, and
                                    ;; a bare macrotask lands in front of it — so
                                    ;; the "nothing survives" gate below would
                                    ;; have failed on `:entries 1` for the same
                                    ;; reason the baseline failed on six.
                                    (warm-release)
                                    (residue-settle!))))
                              (.then
                                (fn [_]
                                  (let [res (rf.bench.hicasso.arm1.runtime/residue)]
                                    (when-not (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0} res)
                                      (throw (ex-info (str "final residue not clean: " (pr-str res)) {}))))
                                  (rf.bench.hicasso.lane/done!)))))))))))))
      (.catch (fn [e]
                (rf.bench.hicasso.lane/fail! (or (some-> e .-message) (str e)))
                (rf.bench.hicasso.lane/done!)))))

