(ns re-frame.bench.hicasso.hd8-rows
  "HD-008's rows — the arms wired to their mount doors, the parity gate,
  the two clocks, and the per-sample records the arm-order guard
  adjudicates (rf2-2rtt6.7).

  ## The instrument, and the fifteen faults it is built against

  The predecessor programme caught fifteen instrument faults, and every
  one of them produced a plausible precise WRONG NUMBER before it was
  caught. The method here is the recorded remedy for each, and none of it
  is optional:

    - **Both orders, always.** Arms are interleaved at the SAMPLE index
      with an order that rotates AND REFLECTS ([[re-frame.bench.hicasso.lane/slot-order]]).
      A cyclic rotation changes which arm goes first and nothing else —
      every arm keeps one predecessor for ever — which is the trap
      `order_guard.cjs` proves arithmetically.
    - **POSITION DOMINATES ADJACENCY.** The live reproduction in
      `order_guard.cjs` measured one control, nothing else varying,
      reading `10.32 10.26 10.26 10.26 10.33 10.28` and then `8.12` for
      ever: +27% for six windows on call count alone. So every sample
      carries its POSITION IN THE RUN as well as its predecessor, and the
      guard partitions on both.
    - **Ranges, never a mean alone.** Overlapping ranges mean
      INDISTINGUISHABLE. A range that straddles 1.0 is reported as a
      non-result, not as a winner.
    - **Every measured write is read back out of the DOM inside its own
      window**, and the count of writes that failed that read-back is
      published beside the figure as `N unverified of M`.
    - **A positive control with PREDICTED against MEASURED, every run**
      ([[positive-control!]]).
    - **Canonical-DOM parity before any clock.** Attribute names sorted,
      so the comparison is of the page and not of the browser's
      insertion-ordered serialiser. Without it two arms are timed while
      building two different pages.

  ## One adapter per process, so three runs

  Spec 006 allows exactly one installed adapter per process, and the two
  Reagent paths need the ratom spine while the frontier arm and the donor
  arms ride React hooks. So the app runs three times over ONE bundle —
  `?adapter=uix`, `?adapter=reagent`, `?adapter=slim` — and every HD-008
  comparison lands WITHIN a run:

      run       arms                                                    answers
      uix       floor uix donor-r1 donor-r2 donor-fh                    donor vs frontier
      reagent   floor reagent uix donor-r1 donor-r2 donor-fh            donor vs stock Reagent
      slim      floor reagent-slim uix donor-r1 donor-r2 donor-fh       donor vs reagent-slim

  The frontier arm rides all three, which also prices what the SUB
  IMPLEMENTATION contributes: the same UIx arm measured over Reagent
  reactions and over the React spine's containers.

  ## What this arm takes from the lane, and what it deliberately does not

  This instrument was written before rf2-2rtt6.2's shared lane landed. It
  now takes mount, release, parity, `across-rounds`, `slot-order`,
  `now-ms`, `round4`, `summarise`, `ratio-between` and `bulk-probes` from
  `re-frame.bench.hicasso.lane` (rf2-f5roa). Four of the lane's mechanisms
  are deliberately NOT adopted, and the reasons are here so the question is
  not reopened by inspection:

  1. **The BATCHED write window IS now adopted on the narrow row, and it
     came with its re-run (rf2-9zysg).** `lane/mount-batch!`'s argument —
     timing `k` operations as one sample lifts a reading clear of Chrome's
     100 µs `performance.now()` clamp, and is not the same as summing `k`
     separately-clamped readings — applies to the narrow write row, which
     read p50s of 0.4–1.2 ms, i.e. 4 to 12 quanta, and published ranges as
     wide as `4.500–8.000x` because of it. [[narrow-batch-k]] writes now
     share one clock ([[window-of]] carries the shape and the argument for
     why the DOM read-back survives the batching).

     This CHANGED THE MEASURED WINDOW, so the earlier narrow rows were
     re-taken rather than re-labelled: `docs/design/hicasso/studio/
     hd8-composed-donor-arm.md` carries the new numbers, the producing SHA
     and a repro command that runs at that SHA, and says plainly that the
     superseded ranges were taken on an unbatched window. The BULK row
     passes a batch of one — the pre-batch window exactly — and did not
     move; the mount rows (4–13 ms) never needed it.

  2. **`lane/control-verdict` is WEAKER than [[positive-control!]]'s own
     rule and would be a downgrade.** The lane passes a control whose
     measured range merely OVERLAPS ±slack of the prediction; this one
     requires EVERY round inside the band. Letting a good round vouch for a
     bad one is how an instrument stops being one, and the stricter rule
     stays.

  3. **`lane/tally` counts `{:of :bad}` in one atom; this row counts them
     PER ARM.** A pooled count says half the writes failed and leaves a
     reader to guess which arm. The per-arm map is the difference between a
     diagnosis and a mystery, and it is threaded immutably through the
     promise chain rather than mutated beside it.

  4. **The verdict is adjudicated in the DRIVER, not in ClojureScript.**
     `lane/guard!` adjudicates in-bundle with the `.cljc` copy of the rule;
     `hd8_run.cjs` adjudicates with the `.cjs` copy. The two copies are
     held in step by shared self-test fixtures, and a lane that exercises
     the other one is worth more than the symmetry.

  Normative owner: `docs/design/hicasso/decisions.md` HD-008; results to
  `rf2-2rtt6.1` and `docs/design/hicasso/studio/`."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uixa]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.hd8-witnesses :as w]
            [reagent.core :as reagent]
            [reagent.dom.client :as rdc]
            [reagent.ratom :as reagent-ratom]
            [reagent2.dom.client :as slim-rdc]
            [reagent2.ratom :as slim-ratom]
            [uix.core :refer [$]]))

;; ===========================================================================
;; Frames — one per arm, so no arm's write can notify another's boundaries
;; ===========================================================================

(defn frame-of [arm-id] (keyword "hd8" (name arm-id)))

(defn ensure-frame!
  "Create (or idempotently replace) `arm-id`'s frame, seed it, and prime
  its dispatch lookup. Runs OUTSIDE every measured window."
  [arm-id]
  (let [fid (frame-of arm-id)]
    (rf/make-frame {:id fid :initial-events [[:hd8/seed w/cells-n]]})
    (w/prime-frame! fid)
    fid))

(defn reseed!
  "Return `arm-id`'s frame to its seeded state between rounds, so a write
  row never measures a page that a previous round already mutated."
  [arm-id]
  (let [fid (frame-of arm-id)]
    (rf/with-frame fid (rf/dispatch-sync [:hd8/seed w/cells-n]))
    nil))

(defn- db-of [arm-id] (frame/frame-app-db-value (frame-of arm-id)))

;; ===========================================================================
;; MOUNT arms
;; ===========================================================================
;;
;; Each arm answers the `{:id :mount :unmount}` shape
;; [[re-frame.bench.hicasso.lane/mount-arm!]] drives, so the timed
;; window is the harness's and not a near-copy per substrate. The window
;; is a `flushSync` around the render: it contains element construction
;; AND React's render, commit and DOM mutation, with nothing scheduled out
;; of it, and the container is created outside it.

(defn- react-root-arm [id element-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [r (react-dom-client/createRoot container)]
                (.render r (element-of props))
                r))
   :unmount (fn [r] (.unmount r))})

(defn- reagent-arm
  "Stock Reagent's own mount door. `rdc/create-root` + `rdc/render` is what
  a Reagent application calls."
  [id form-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [r (rdc/create-root container)]
                (rdc/render r (form-of props))
                r))
   :unmount (fn [r] (rdc/unmount r))})

(defn- slim-arm
  "reagent-slim's own mount door — the same source form, the other engine."
  [id form-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [r (slim-rdc/create-root container)]
                (slim-rdc/render r (form-of props))
                r))
   :unmount (fn [r] (slim-rdc/unmount r))})

(defn- provided
  "Wrap `child` (a React element) in the shared frame context for `fid`,
  through the published UIx provider. Not a DOM element, so parity is
  untouched."
  [fid child]
  ($ uixa/frame-provider {:frame fid} child))

;; -- the M page -------------------------------------------------------------

(defn m-arm [id]
  (let [fid (frame-of id)]
    (case id
      :floor        (react-root-arm :floor (fn [{:keys [n]}]
                                             (w/floor-m {:rows (:rows (db-of :floor))
                                                         :n    n})))
      :reagent      (reagent-arm :reagent
                                 (fn [{:keys [n]}]
                                   [rf/frame-provider {:frame fid} [w/rg-m n]]))
      :reagent-slim (slim-arm :reagent-slim
                              (fn [{:keys [n]}]
                                [rf/frame-provider {:frame fid} [w/rg-m n]]))
      :uix          (react-root-arm :uix (fn [{:keys [n]}]
                                           (provided fid ($ w/ux-m {:n n}))))
      :donor-r1     (react-root-arm :donor-r1 (fn [{:keys [n]}]
                                                (w/slim-element (w/r1-m n fid))))
      :donor-r2     (react-root-arm :donor-r2 (fn [{:keys [n]}]
                                                (provided fid (w/slim-element (w/r2-m n)))))
      ;; The FOURTH DONOR (rf2-2rtt6.29): the SAME `slim-element` call over
      ;; the SAME skeleton and the same 300 `:f>` crossings as `donor-r1`.
      ;; What differs is inside the boundaries, where Freehand's codec builds
      ;; the markup — see [[re-frame.bench.hicasso.hd8-witnesses]]'s fourth-arm
      ;; section for what that leaves varying and which way the residual runs.
      :donor-fh     (react-root-arm :donor-fh (fn [{:keys [n]}]
                                                (w/slim-element (w/fh-m n fid)))))))

;; -- the U page (mounted for parity; measured by the write rows) ------------

(defn u-arm [id]
  (let [fid (frame-of id)]
    (case id
      :floor        (react-root-arm :floor (fn [{:keys [n]}]
                                             (w/floor-u {:cells (:cells (db-of :floor))
                                                         :n     n})))
      :reagent      (reagent-arm :reagent
                                 (fn [{:keys [n]}]
                                   [rf/frame-provider {:frame fid} [w/rg-u n]]))
      :reagent-slim (slim-arm :reagent-slim
                              (fn [{:keys [n]}]
                                [rf/frame-provider {:frame fid} [w/rg-u n]]))
      :uix          (react-root-arm :uix (fn [{:keys [n]}]
                                           (provided fid ($ w/ux-u {:n n}))))
      :donor-r1     (react-root-arm :donor-r1 (fn [{:keys [n]}]
                                                (w/slim-element (w/r1-u n fid))))
      :donor-r2     (react-root-arm :donor-r2 (fn [{:keys [n]}]
                                                (provided fid (w/slim-element (w/r2-u n)))))
      :donor-fh     (react-root-arm :donor-fh (fn [{:keys [n]}]
                                                (w/slim-element (w/fh-u n fid)))))))

;; ===========================================================================
;; The arm sets — one adapter per process (Spec 006)
;; ===========================================================================

(def arm-ids-for
  "Which arms are measurable under which installed adapter — and this
  partition is itself an HD-008 finding, arrived at by measurement rather
  than by design.

  The first cut put the frontier arm and both donor rungs into ALL THREE
  runs, so that every comparison would land within one process. It does not
  work, and the instrument said so rather than quietly producing numbers: on
  a ratom spine the lowering check reported `:db-after \"T\"` — the click
  dispatched, the event ran, `app-db` was written — while the DOM stayed at
  `\"0\"`. The React `use-subscribe` spine does not propagate over a ratom
  spine, in either direction, and no drain fixes it: `ratom/flush!` settles
  the subscription graph and `reagent.core/flush` renders the dirty
  components, and a `useSyncExternalStore` subscriber watching a Reagent
  Reaction is notified by neither.

  That is worth stating plainly, because it bounds what \"composed from
  parts already in the repo\" can mean: Spec 006 allows exactly one adapter
  per process, the two Reagent paths need the ratom spine, the donor rungs
  need the React one, and the composition therefore cannot share a process
  with the thing it must beat.

  So each run measures the arms NATIVE to its adapter, and the donor
  comparison against either Reagent path is made through the floor — the
  same hand-written `createElement` code, in the same bundle, touching no
  adapter at all. Within-run where the physics allows it; floor-normalised
  and LABELLED AS SUCH where it does not."
  {:uix     [:floor :uix :donor-r1 :donor-r2 :donor-fh]
   :reagent [:floor :reagent :uix :donor-r1 :donor-r2 :donor-fh]
   :slim    [:floor :reagent-slim :uix :donor-r1 :donor-r2 :donor-fh]})

(def write-arm-ids-for
  "The WRITE rows are narrower than the mount rows, and only there.

  A mount is a ONE-SHOT READ: `use-subscribe` takes its first snapshot
  correctly under either spine, and the canonical-DOM parity gate proves it
  — every arm above builds the same page in every run, at both sizes. So
  the mount rows, which carry half of HD-012's ship bar, get their
  donor-vs-Reagent comparison WITHIN a single process.

  Updates are where the spines part company, so the write rows keep only
  the arms native to the installed adapter and the donor comparison there
  is made through the floor."
  {:uix     [:floor :uix :donor-r1 :donor-r2 :donor-fh]
   :reagent [:floor :reagent]
   :slim    [:floor :reagent-slim]})

(defn donor-run?
  "Is this the run whose donor arms are REACTIVE, and therefore the run
  whose lowering check can answer? Under a ratom spine the lowered handler
  dispatches and `app-db` changes — the check reports `:db-after` to prove
  it — but no view follows, so a lowering check there would fail for a
  reason that has nothing to do with the lowering."
  [adapter]
  (= adapter :uix))

(def witnesses
  [{:id       :M
    :doc      "300 rows, each a boundary with its own subscription and its own handler — markup-dominant"
    :arm-of   m-arm
    :props    {:n w/rows-n}
    :small    {:n 6}
    :elements (w/m-elements w/rows-n)
    :small-elements (w/m-elements 6)}
   {:id       :U
    :doc      "a 300-cell grid, same per-cell shape — the page the write rows drive"
    :arm-of   u-arm
    :props    {:n w/cells-n}
    :small    {:n 6}
    :elements (w/u-elements w/cells-n)
    :small-elements (w/u-elements 6)}])

(defn arms-for [witness arm-ids] (mapv (:arm-of witness) arm-ids))

;; ===========================================================================
;; The fairness gate — canonical DOM, every arm, before any clock
;; ===========================================================================

(defn parity
  "Mount every arm at once and answer the harness's parity record. Runs
  before a clock is read, and again under `:advanced`, because a parity
  that held under `:none` and failed under `:advanced` would be a renaming
  bug silently deciding the comparison."
  [witness arm-ids props]
  (lane/parity (arms-for witness arm-ids) props))

(defn parity-problems
  "Answer a vector of problems — empty when every arm built one page with
  the element count the witness's arithmetic predicts."
  [arm-ids]
  (reduce
    (fn [problems {:keys [id props elements small small-elements] :as wit}]
      (reduce
        (fn [acc [p expected what]]
          (let [{:keys [mounts agree? counts disagree canon]} (parity wit arm-ids p)]
            (try
              (cond-> acc
                (not agree?)
                (conj {:witness id :size what :problem :canonical-dom-disagreement
                       :arms disagree})

                (not (every? #(= expected %) (vals counts)))
                (conj {:witness id :size what :problem :element-count
                       :predicted expected :measured counts})

                (not (pos? (count (get canon (first arm-ids) ""))))
                (conj {:witness id :size what :problem :built-nothing}))
              (finally (doseq [mnt mounts] (lane/release! mnt))))))
        problems
        [[props elements :stress] [small small-elements :small]]))
    []
    witnesses))

(defn parity-can-fail?
  "A comparison nobody has watched answer FALSE is not evidence that two
  things agree. The same witness at two different sizes, compared the same
  way, must disagree."
  [arm-ids]
  (let [wit (first witnesses)
        a   (parity wit arm-ids {:n 6})
        b   (parity wit arm-ids {:n 7})]
    (try
      (and (not= (:reference a) (:reference b)) (:agree? a) (:agree? b))
      (finally
        (doseq [mnt (:mounts a)] (lane/release! mnt))
        (doseq [mnt (:mounts b)] (lane/release! mnt))))))

;; ===========================================================================
;; The mount clock — with the order-guard's per-sample records
;; ===========================================================================

(def ^:private round4 lane/round4)
(defn- p50 [xs] (:p50 (lane/summarise xs)))

(def slot-order
  "Slot order for `k` arms at sample index `s` — `lane/slot-order`, and
  nothing local.

  This was a local override, added because `lane/slot-order` composed a
  rotation with a reflection and the two CANCEL at `k = 2`: rotating a pair
  by one is the same permutation as reversing it, so `[0 1]` came back at
  every index and a two-arm plan ran in ONE ORDER for ever. The arm-order
  guard found that rather than a reader — the two-arm runs came back `only
  1 stratum — the question was never asked`, and REFUSED, which is
  precisely what a single-order result is supposed to do.

  The override was local rather than shared because sibling P0 arms were
  measuring on the shared copies at the time, and a shared instrument must
  not change under a measurement in flight. Those arms have landed, and
  `rf2-ouwh8` has since repaired the schedule where it lives — so the
  override is gone and this name is the lane's, exactly like every other
  mechanism in this file."
  lane/slot-order)

(defn mount-round!
  "One round over `arms`: `(+ warmup samples)` sample indices, every arm
  mounted at every index, order rotating AND REFLECTING with the index.

  Answers `{:readings {id [ms …]} :samples [{:arm :value :predecessor
  :position}]}`. The sample records are what `order_guard.cjs` partitions;
  a driver that recorded only the readings could not ask whether a figure
  moves with the plan, and an unasked question refuses as loudly as a
  failed one."
  [arms props {:keys [warmup samples]} position0]
  (let [k    (count arms)
        acc  (atom (zipmap (map :id arms) (repeat [])))
        recs (atom [])
        pos  (atom position0)
        prev (atom nil)]
    (dotimes [s (+ warmup samples)]
      (doseq [j (slot-order k s)]
        (let [arm (nth arms j)
              mnt (lane/mount-arm! arm props)
              p   @pos]
          (when (>= s warmup)
            (swap! acc update (:id arm) conj (:ms mnt))
            (swap! recs conj {:arm         (name (:id arm))
                              :value       (:ms mnt)
                              :predecessor (some-> @prev name)
                              :position    p}))
          (reset! prev (:id arm))
          (swap! pos inc)
          (lane/release! mnt))))
    {:readings @acc :samples @recs :position @pos}))

(defn normalise
  "One round's raw readings as `{:p50 {id ms} :ratio {id r}}`, every ratio
  against the floor measured in THIS round."
  [readings]
  (let [p50s  (into {} (map (fn [[id xs]] [id (p50 xs)])) readings)
        floor (get p50s :floor)]
    {:p50   p50s
     :ratio (into {} (map (fn [[id v]] [id (round4 (/ v floor))])) p50s)}))

(def head-to-head-pairs
  "The comparisons HD-008 is actually about. The floor is the calibrator,
  never a rival: the stop rule asks whether the composed arm *clearly
  beats both Reagent paths* and *stays acceptably close to direct UIx*, so
  those are the ratios that have to be published as first-class figures
  rather than left for a reader to divide out of two floor-normalised
  numbers (which would also lose the within-round pairing that makes them
  trustworthy).

  `donor-r2 / donor-r1` is the price of the product shell, and it is the
  one figure in this instrument that no comparator can supply.

  `donor-fh / donor-r1` is the other one (rf2-2rtt6.29): THE CODEC RATIO,
  with the boundary mechanism, the hook, the pinned frame, the dispatch
  lookup, the author closure and the outer skeleton all held fixed and
  only the runtime hiccup codec varying. It is the pair the fourth arm
  exists for, and `hd8-witnesses`'s `codecs-differ?` is what stops a
  reading of 1.0 being ambiguous between *the codecs cost the same* and
  *the arm ran one codec twice*."
  [[:donor-r1 :reagent] [:donor-r2 :reagent] [:donor-fh :reagent]
   [:donor-r1 :reagent-slim] [:donor-r2 :reagent-slim] [:donor-fh :reagent-slim]
   [:donor-r1 :uix] [:donor-r2 :uix] [:donor-fh :uix]
   [:donor-r2 :donor-r1]
   [:donor-fh :donor-r1] [:donor-fh :donor-r2]])

(defn head-to-head
  "Per-round arm-to-arm ratios, ranged across rounds.

  Each ratio is formed WITHIN a round from that round's own p50s, so drift
  between rounds cancels exactly as it does for the floor-normalised
  figures. Reported as a RANGE, with `:straddles-1?` set when the range
  includes 1.0 — in which case the two arms are INDISTINGUISHABLE on this
  witness and the mean must not be quoted as a winner.

  The arithmetic is `lane/ratio-between` and no longer a second copy of it
  (rf2-f5roa). It is fed this row's raw `:p50` maps rather than its
  floor-normalised `:ratio` maps — algebraically the floor cancels either
  way, but rounding to four places twice is not the same as rounding once,
  and these are the digits the published rows carry."
  [per-round]
  (let [present (set (keys (:p50 (first per-round))))
        p50s    (mapv :p50 per-round)]
    (into {}
          (keep (fn [[a b]]
                  (when (and (contains? present a) (contains? present b))
                    [(keyword (str (name a) "-over-" (name b)))
                     (lane/ratio-between p50s a b)])))
          head-to-head-pairs)))

(defn measure-mount!
  "`rounds` interleaved rounds of the mount clock over `witness`.

  Answers the published record: per-round p50s and floor ratios, the
  across-rounds RANGE (never a mean alone), and every sample the guard
  needs."
  [witness arm-ids rounds sampling]
  (let [arms (arms-for witness arm-ids)
        _    (doseq [id arm-ids] (reseed! id))
        acc  (reduce (fn [{:keys [rounds-out samples position]} _]
                       (let [r (mount-round! arms (:props witness) sampling position)]
                         {:rounds-out (conj rounds-out (normalise (:readings r)))
                          :samples    (into samples (:samples r))
                          :position   (:position r)}))
                     {:rounds-out [] :samples [] :position 0}
                     (range rounds))
        norm (:rounds-out acc)]
    {:witness   (:id witness)
     :doc       (:doc witness)
     :arms      (vec arm-ids)
     :per-round norm
     :summary   (lane/across-rounds (mapv :ratio norm))
     :head-to-head (head-to-head norm)
     :samples   (:samples acc)}))

;; ===========================================================================
;; The write clock — narrow and bulk, every write read back out of the DOM
;; ===========================================================================

(defn spine-drain!
  "The INSTALLED substrate's own synchronous render drain.

  This is a property of the run, not of the arm, and discovering that cost
  a debugging pass: under a RATOM spine (`?adapter=reagent|slim`) the
  frame's app-db is a reactive atom, so a write marks dependent reactions
  dirty and queues them on the substrate's batching queue — and NOTHING
  propagates, not even to a `useSyncExternalStore` subscriber, until that
  queue is flushed. An empty `flushSync` (which is the whole drain under
  the React spine, where the container notifies synchronously) commits
  nothing there, and the DOM read-back reported every donor write as
  unverified. Which is the read-back doing its job: the alternative was a
  fast, precise, meaningless number.

  So every arm in a run shares the run's drain, exactly as b6's method
  prescribes — each substrate's OWN documented synchronous drain, inside
  the measured window."
  [adapter]
  (case adapter
    ;; SETTLE, THEN RENDER, and both halves are load-bearing. `ratom/flush!`
    ;; runs the queued Reactions — which is what notifies a
    ;; `useSyncExternalStore` subscriber watching one — and
    ;; `reagent.core/flush` renders the dirty Reagent components. Draining
    ;; only the component queue leaves every hook-based arm reading a stale
    ;; snapshot: the click reached `app-db` (`:db-after` said so) and the DOM
    ;; never followed.
    :reagent (react-dom/flushSync (fn [] (reagent-ratom/flush!) (reagent/flush)))
    ;; reagent-slim's drain brings its OWN `flushSync` boundary (it wraps
    ;; `(do (f) (batching/flush!))`), so wrapping it in a second one would
    ;; nest the commit and bill this run for a boundary no slim application
    ;; pays. Its `f` slot is exactly where the subscription-graph settle
    ;; belongs.
    :slim    (slim-rdc/flush-render! (fn [] (slim-ratom/flush!)))
    ;; The React spine notifies its containers synchronously, so the empty
    ;; `flushSync` is the whole drain: it makes React commit the already
    ;; queued notification inside this window rather than on its own
    ;; scheduler a couple of milliseconds later.
    (react-dom/flushSync (fn [] nil))))

(defn arm-scheduler
  "Which queue THIS arm's own render work is scheduled on — and therefore
  how its write window must wait for it (rf2-b69lw).

  This is the fact [[window-of]] needs and the one the instrument
  previously did not ask for. A window that waits a fixed one microtask
  serves an arm whose notification is queued somewhere ELSE and silently
  breaks an arm whose notification is queued on the very queue the
  harness is yielding to.

    - `:none` — the floor (a local `swap!` and a `flushSync` render, with
      nothing queued anywhere) and the React spine (`use-subscribe`'s
      containers notify synchronously; the empty `flushSync` is the whole
      drain).
    - `:animation-frame` — stock Reagent. `reagent.impl.batching` schedules
      its component queue on `requestAnimationFrame`, so the queue is still
      full a microtask later and the drain finds the work.
    - `:microtask` — reagent-slim. `reagent2.impl.batching` is *\"a
      microtask-based scheduler … no requestAnimationFrame fallback is
      required\"*, so a microtask yield hands the queue to the substrate
      BEFORE the drain: it issues its `forceUpdate` outside any `flushSync`
      boundary, React parks the work at the default lane, and the drain
      that follows finds nothing to commit."
  [arm-id adapter]
  (if (= arm-id :floor)
    :none
    (case adapter
      :slim    :microtask
      :reagent :animation-frame
      :none)))

(defn- window-of
  "The measured write window for an arm on `scheduler`.

  Answers `(fn [steps] → promise of {:ms :oks})`, where `steps` is a seq of
  `{:write! :verify!}` and ALL of them share ONE clock. The clock starts
  before the first write and stops after the last drain; the DOM read-backs
  run after it, in the same turn as the last drain, never in a later one, so
  a commit that arrived a turn late reads as UNVERIFIED rather than as a pass.

  ## ONE CLOCK OVER k OPERATIONS, AND WHY THE READ-BACK SURVIVES IT

  Chrome clamps `performance.now()` to 100 µs and the narrow row sits 4 to
  12 quanta above it, so a per-write clock quantises every reading and the
  published ranges carry that noise (rf2-9zysg). Timing `k` writes as ONE
  sample lifts the window clear of the clamp, and it is NOT the same as
  summing `k` separately-clamped readings, which quantises `k` times and
  adds the errors. `lane/mount-batch!` does exactly this for mounts.

  The cost is that a batched window cannot verify each write in the turn
  its own drain ran in, because there is only one clock. It verifies all
  `k` after the clock stops — and that is not the weakening it looks like,
  for a reason worth writing down rather than rediscovering:

    NO MACROTASK RUNS INSIDE THE WINDOW. Every turn between the first
    write and the last drain is a MICROTASK (a resolved-promise turn).
    The fault this read-back exists to catch is a commit React has PARKED
    at the default lane — which is scheduled through the Scheduler's
    `MessageChannel`, i.e. a macrotask — so it cannot land inside the
    window no matter how many microtasks pass. A parked commit is still
    parked when the read-backs run, and all `k` read UNVERIFIED.

  What the batch does relax is microtask-scale lateness: an early write in
  the batch gets up to `k - 1` extra microtask turns before it is read
  back. Today's unbatched window already tolerates exactly one such turn
  by construction — that IS the yield — so this is a difference of degree
  on a tolerance the instrument already grants, not a new blind spot. It
  is stated in the row's `:measurement-method` rather than left implicit.

  `k = 1` reduces this to the pre-batch window exactly, which is why the
  BULK row is untouched by any of it.

  TWO SHAPES, BECAUSE ONE FIXED WAIT IS NOT NEUTRAL ACROSS SCHEDULER
  FAMILIES. That is rf2-b69lw, and rf2-z3vlz diagnosed it against a
  standalone rig: the reagent-slim write row read `78 of 78` unverified —
  and, unsuppressed, `0.16–0.50x` the floor while the page never changed —
  purely because the harness put a microtask between the write and the
  drain. `docs/design/hicasso/studio/slim-non-reactive-arm-diagnosis.md`
  carries the evidence, including the positive control (a plain component
  reading a plain `reagent2.core/atom`, no re-frame anywhere on the path)
  that reproduces it in all four bundle compositions. The general form —
  the same fixed yield in the shared `lane/verified-write!` — is rf2-pq7d8,
  and it HAS since been repaired: the lane takes the same `:scheduler`
  declaration and gives it these same two shapes, lifted from here rather
  than invented a second time. That repair is additive — an arm that
  declares no `:scheduler` gets the lane's unchanged window, and none does
  outside this file — so no published row moved.

    `:microtask`  write, then drain, with NOTHING between them. The
                  substrate's queue is filled synchronously by the write and
                  is still there when `flush-render!` opens its boundary, so
                  the commit lands inside the window.

    everything else  today's window, unchanged: write, yield ONE microtask,
                  drain. b6 records that with the microtask deleted every
                  arm whose notification is queued elsewhere fails its own
                  read-back on every write, and the published HD-008 rows
                  were taken through exactly this shape.

  The two shapes do NOT bill the same wait, and that difference is not left
  as an assurance: [[yield-cost!]] prices the harness microtask against the
  same clock, outside every arm's window, and it is published beside the
  write rows."
  [scheduler force!]
  (let [verify-all (fn [steps] (mapv (fn [s] ((:verify! s))) steps))]
    (if (= scheduler :microtask)
      ;; Write, then drain, with NOTHING between them — and the whole batch
      ;; is one synchronous run, so not even a microtask separates a drain
      ;; from the next write.
      (fn [steps]
        (let [t0 (lane/now-ms)]
          (doseq [s steps] ((:write! s)) (force!))
          (let [ms (- (lane/now-ms) t0)]
            (js/Promise.resolve {:ms ms :oks (verify-all steps)}))))
      ;; Write, yield ONE microtask, drain — per operation, k times, under
      ;; one clock. The next write starts in the turn the previous drain
      ;; finished in, so the window holds exactly k harness turns and not
      ;; 2k: the fixed yield is preserved per operation, never batched away
      ;; and never doubled.
      (fn [steps]
        (let [t0 (lane/now-ms)]
          (letfn [(run [ss]
                    (if (empty? ss)
                      (let [ms (- (lane/now-ms) t0)]
                        (js/Promise.resolve {:ms ms :oks (verify-all steps)}))
                      (do ((:write! (first ss)))
                          (-> (js/Promise.resolve nil)
                              (.then (fn [_] (force!) (run (next ss))))))))]
            (run (seq steps))))))))

(defn- write-arm
  "The write door for `arm-id` on the U page.

  The floor's render happens INSIDE the flushSync for the reason b6
  records: `root.render` outside a React event schedules at the default
  lane, and an EMPTY flushSync flushes only the sync lane — a floor arm
  that rendered in `write!` would have its commit land outside the window
  entirely. Every other arm drains through [[spine-drain!]].

  The arm also carries its own `:window!` ([[window-of]]), because the wait
  between the write and that drain is a property of the arm's scheduler and
  not of the harness."
  [arm-id adapter]
  (let [fid   (frame-of arm-id)
        state (atom nil)
        rt    (volatile! nil)
        arm   (u-arm arm-id)
        sched (arm-scheduler arm-id adapter)
        force! (fn []
                 (if (= arm-id :floor)
                   (react-dom/flushSync
                     (fn [] (.render ^js @rt (w/floor-u {:cells @state :n w/cells-n}))))
                   (spine-drain! adapter)))]
    {:id     arm-id
     :mount  (fn [container]
               (if (= arm-id :floor)
                 (let [r (react-dom-client/createRoot container)]
                   (reset! state (:cells (db-of :floor)))
                   (vreset! rt r)
                   (react-dom/flushSync
                     (fn [] (.render r (w/floor-u {:cells @state :n w/cells-n}))))
                   r)
                 (let [handle (volatile! nil)]
                   (react-dom/flushSync
                     (fn [] (vreset! handle ((:mount arm) container {:n w/cells-n} 0))))
                   @handle)))
     :write! (fn [i val]
               (if (= arm-id :floor)
                 ;; The floor has no frame and no events by construction — it
                 ;; is the arm with no substrate at all. Its write is a local
                 ;; swap, and its `force!` re-renders the whole root, which
                 ;; is exactly what an application with no reactive substrate
                 ;; costs. On UPDATE the floor is therefore not a lower bound
                 ;; and the report says so: a fine-grained substrate can and
                 ;; should beat it on a narrow write.
                 (if (= i :all)
                   (reset! state (vec (repeat w/cells-n val)))
                   (swap! state assoc i val))
                 (if (= i :all)
                   (rf/dispatch-sync [:hd8/set-all val] {:frame fid})
                   (rf/dispatch-sync [:hd8/set i val] {:frame fid}))))
     :force!    force!
     :scheduler sched
     :window!   (window-of sched force!)
     :unmount (fn [handle]
                (react-dom/flushSync
                  (fn [] (if (= arm-id :floor)
                           (.unmount ^js handle)
                           ((:unmount arm) handle)))))}))

(defn cell-text [container i]
  (some-> (.querySelector container (str "[data-i=\"" i "\"]")) (.-textContent)))

(defn mount-write-arms! [arm-ids adapter]
  (mapv (fn [id]
          (let [a (write-arm id adapter)
                c (js/document.createElement "div")]
            (.appendChild js/document.body c)
            {:arm a :container c :handle ((:mount a) c)}))
        arm-ids))

(defn release-write-arms!
  "Release every write arm. A throw is RECORDED, never swallowed — and a
  normal return is not taken at its word: `lane/container-released!` reads
  each container before it is removed, exactly as `lane/release!` does,
  because a root that survives its own unmount does so on a DETACHED tree
  no later census can see (rf2-jk3vj)."
  [mounts]
  (doseq [{:keys [arm handle container]} mounts]
    (when (try ((:unmount arm) handle)
               true
               (catch :default e
                 (lane/teardown-failure! (str "release-write-arms! " (:id arm)) e)))
      (lane/container-released! (str "release-write-arms! " (:id arm)) container))
    (.remove container)))

(defn assert-teardown-clean!
  "Throw if any teardown has failed since the last check.

  Called BETWEEN rows rather than at the end of the run, because that is
  where the damage is done: an arm whose unmount threw has left its React
  root, its watches and its subscription caches standing, and every row
  measured after it is measured on a page carrying them. The throw lands
  in `hd8-app`'s existing `.catch`, which records `HD8_ERROR` and exits 1
  — the same fail-closed path parity and the lowering check already take
  (rf2-f5roa, from the PR #7263 audit)."
  [after]
  (let [fs (lane/drain-teardown-failures!)]
    (when (seq fs)
      (throw (ex-info (str "teardown FAILED after " after
                           " — an arm did not unmount, so its React root, watches and "
                           "subscription caches are still standing and every row after "
                           "this one would be measured on a page carrying them: "
                           (pr-str fs))
                      {:after after :failures fs})))
    nil))

(defn timed-write!
  "Run `ops` — a seq of `[i val probes]` — as ONE measured window: each
  writes, waits the way THIS ARM'S SCHEDULER requires and forces the arm's
  own synchronous drain; the clock stops after the last of them; then EVERY
  `probes` cell of EVERY op is read back out of the DOM. Answers a promise
  of `{:ms … :oks [bool …]}`, one flag per op.

  `ops` is a SEQ because the narrow row batches `k` writes under one clock
  to clear Chrome's 100 µs quantum ([[window-of]] carries that argument and
  the read-back's survival of it). The bulk row passes a single op, which
  is the pre-batch window exactly.

  The wait belongs to the arm ([[window-of]]) and not to this function,
  which is the whole of rf2-b69lw: a fixed one-microtask yield is
  load-bearing for an arm whose notification is queued somewhere else and
  is FATAL for an arm whose notification is queued on the microtask queue
  itself.

  `probes` is a SEQ and used to be a single cell, which was thin on the
  BULK row (rf2-f5roa): a broad write changes all 300 cells, this probed
  cell 0, and a page left stale by a commit landing outside the window can
  still carry one fresh cell from the previous write. `lane/bulk-probes`
  is the rule now, and the P0 arm three files away already used it. The
  NARROW row is unchanged and was already right — one cell changes, so one
  probe is the whole page's worth of verification.

  The read-back is inside the window's own iteration and in the same turn
  as the drain, so an arm that silently rendered nothing — or rendered a
  turn late — reports as UNVERIFIED rather than as the fastest substrate in
  the table, which is the exact shape of the fault this exists to prevent.
  Widening the probe seq does not move the measured window: the clock stops
  before the first `querySelector` either way."
  [{:keys [arm container]} ops]
  ((:window! arm)
   (mapv (fn [[i val probes]]
           {:write!  (fn [] ((:write! arm) i val))
            :verify! (fn [] (every? #(= val (cell-text container %)) probes))})
         ops)))

(defn- chain
  "Sequence `f` over `xs` through promises, threading `acc`."
  [acc xs f]
  (reduce (fn [p x] (.then p (fn [a] (f a x)))) (js/Promise.resolve acc) xs))

(def narrow-batch-k
  "How many narrow writes share ONE clock (rf2-9zysg).

  Chrome clamps `performance.now()` to 100 µs. The unbatched narrow row
  read p50s of 0.4–1.2 ms — 4 to 12 quanta — and published ranges as wide
  as `4.500–8.000x` the floor because of it. Ten writes per window puts
  the sample 40 to 120 quanta above the clamp, where the quantum is
  roughly 1% of the reading rather than 10–25% of it.

  Ten and not more: the batch's cells must be DISTINCT (they are
  `(mod o 300)` over consecutive `o`, so any `k` up to 300 is distinct),
  and the microtask-scale tolerance an early write in the batch receives
  grows with `k` ([[window-of]]). Ten buys the clamp headroom the row
  needs and keeps that tolerance at nine turns.

  The BULK row does not use this — it passes a batch of one, which is the
  pre-batch window exactly — because its readings are already 4–13 ms."
  10)

(defn- write-round!
  "One write round. Every mounted arm writes at every sample index, order
  rotating and reflecting; `kind` is `:narrow` ([[narrow-batch-k]] cells,
  one per commit, all under ONE clock) or `:bulk` (all 300 in one commit,
  one commit per window)."
  [mounts kind {:keys [warmup samples]} position0]
  (let [k   (count mounts)
        ids (mapv #(:id (:arm %)) mounts)]
    (chain {:readings (zipmap ids (repeat []))
            :samples  []
            ;; PER ARM, not a single total. A pooled count says half the
            ;; writes failed and leaves a reader to guess which arm; the
            ;; per-arm map names it, which is the difference between a
            ;; diagnosis and a mystery.
            :unverified (zipmap ids (repeat 0))
            :total    (zipmap ids (repeat 0))
            :position position0
            :prev     nil}
           (range (+ warmup samples))
           (fn [acc s]
             (chain acc (slot-order k s)
                    (fn [a j]
                      (let [mnt (nth mounts j)
                            id  (:id (:arm mnt))
                            p   (:position a)
                            bk  (if (= kind :bulk) 1 narrow-batch-k)
                            ;; Every op in a batch gets its OWN value and
                            ;; its OWN cell, so no read-back can be
                            ;; satisfied by a neighbour's commit and the
                            ;; batch's k cells are k distinct cells.
                            ;;
                            ;; A broad write changes every cell, so it is
                            ;; verified at three (`lane/bulk-probes`); a
                            ;; narrow write changes one, so it is verified
                            ;; at that one.
                            ops (mapv (fn [c]
                                        (let [o (+ (* p bk) c)
                                              v (str "v" o)]
                                          (if (= kind :bulk)
                                            [:all v (lane/bulk-probes o w/cells-n)]
                                            (let [cell (mod o w/cells-n)]
                                              [cell v [cell]]))))
                                      (range bk))]
                        (-> (timed-write! mnt ops)
                            (.then (fn [{:keys [ms oks]}]
                                     (let [bad (count (remove identity oks))]
                                       (cond-> (-> a
                                                   (assoc :position (inc p) :prev id)
                                                   (update-in [:total id] + bk)
                                                   (update-in [:unverified id] + bad))
                                         (>= s warmup)
                                         (-> (update-in [:readings id] conj ms)
                                             (update :samples conj
                                                     {:arm         (name id)
                                                      :value       ms
                                                      :predecessor (some-> (:prev a) name)
                                                      :position    p}))))))))))))))

(defn- mask-failed-read-backs
  "Apply the DOM-read-back publication mask to one write row's published
  surfaces, and answer the row.

  AN ARM WHOSE WRITES DID NOT REACH THE DOM HAS NO FIGURE. Its clock
  readings are real milliseconds and they are measuring a page that never
  changed — which is the cheapest possible way to be fast and the exact
  fault the read-back exists to catch. So `:summary` carries
  `:unpublished` in that arm's place rather than a number a reader could
  quote, and every head-to-head pair touching it is dropped.

  Named rather than inlined in [[measure-write!]] so the self-test's
  fixture rows are masked by the instrument's own rule, never by a
  hand-written approximation that could drift from it (rf2-b69lw, from
  the PR #7295 audit)."
  [{:keys [unverified writes] :as row}]
  (let [bad (into #{} (comp (filter (fn [[_ n]] (pos? n))) (map key)) unverified)]
    (-> row
        (update :summary
                (fn [m]
                  (reduce (fn [acc arm]
                            (assoc acc arm {:unpublished :failed-dom-read-back
                                            :unverified  (get unverified arm)
                                            :of          (get writes arm)}))
                          m
                          bad)))
        (update :head-to-head
                (fn [m]
                  (into {}
                        (remove (fn [[k _]]
                                  (some #(re-find (re-pattern (name %)) (name k)) bad)))
                        m))))))

(defn grain-record
  "How many of the clock's own ticks each arm's window is worth, per round.

  `tick` is [[clock-resolution!]]'s measured grain. A p50 of `v` against a
  grain of `t` is `v/t` ticks, and the grain's SHARE of that reading is
  `t/v` — the fraction of the number that is the instrument rather than the
  arm. Both are recorded per arm per round, because the ratios are formed
  WITHIN a round and it is each round's own p50 that becomes a published
  endpoint.

  Answers `{:tick :quanta {arm [q …]} :worst {arm q} :below-grain #{arm …}}`.
  `:below-grain` is the set of arms whose p50 does not EXCEED the grain in
  at least one round — two measured quantities with no constant between
  them, the same shape as [[correct-round]]'s survival test. An arm in it
  has a window the clock cannot tell from one half its size or twice it.

  A `nil` tick (the clock never advanced, [[clock-resolution!]]'s spin cap)
  puts EVERY arm below the grain: an instrument that cannot state its own
  resolution has not earned a magnitude."
  [row tick]
  (let [arms   (:arms row)
        rounds (mapv :p50 (:per-round row))
        quanta (into {}
                     (map (fn [id]
                            [id (mapv (fn [p50]
                                        (let [v (get p50 id)]
                                          (when (and (number? v) (number? tick) (pos? tick))
                                            (round4 (/ v tick)))))
                                      rounds)]))
                     arms)
        worst  (into {} (map (fn [[id qs]] [id (when (every? number? qs) (apply min qs))])) quanta)]
    {:tick        tick
     :quanta      quanta
     :worst       worst
     :grain-share (into {} (map (fn [[id q]] [id (when (number? q) (round4 (/ 1.0 q)))])) worst)
     :below-grain (into #{} (keep (fn [[id q]] (when-not (and (number? q) (> q 1.0)) id))) worst)}))

(defn- mask-below-grain
  "Apply the CLOCK-GRAIN publication mask to one write row's published
  surfaces, and answer the row (rf2-d2tzk).

  A WINDOW THAT DOES NOT EXCEED THE CLOCK'S OWN GRAIN HAS NO MAGNITUDE.
  The reading is a real number and it is the instrument's resolution
  wearing an arm's clothes: the bulk row's floor is a single commit under
  one clock, it reads 1.0 to 2.0 ticks of a 0.1 ms grain, and its
  neighbouring attainable values are 33 % to 100 % apart — so every ratio
  normalised by it is determined only to within a factor of about two. The
  row's own six rounds show it: the `reagent-slim` numerator moved 1.18x
  across them while the published ratio moved 2.06x, and the whole of that
  swing is the denominator stepping between one tick and two.

  The mask is the DOM read-back's ([[mask-failed-read-backs]]) applied to
  a second way of having no publishable timing, and it is deliberately the
  same shape: `:summary` carries `:unpublished` in place of a number a
  reader could quote, and every head-to-head pair touching an offending
  arm is dropped. The two differ in what they mean and the driver keeps
  them apart — a failed read-back is a FAULT and refuses the run; a window
  beneath the grain is a LIMIT of the instrument, published as one.

  WHICH FIGURES GO. The floor is the denominator of every floor-normalised
  figure, so a floor beneath the grain takes the whole column. A
  head-to-head pair is arm-over-arm and the floor cancels out of it
  exactly, so those pairs SURVIVE — which is not a concession, it is the
  measurement: the clamp destroys the cross-run column (the weaker
  warrant) and leaves the within-run pairs (the stronger one) untouched.

  The permanent repair is the batched window the narrow row got
  (`narrow-batch-k`, rf2-9zysg): it lifts the sample clear of the clamp.
  That changes a measured window and obliges a re-take of the row on every
  adapter, which is a re-publication and rf2-2rtt6.7's to authorise — so
  this mask states the limit rather than manufacturing resolution the
  instrument does not have."
  [row grain]
  (let [bad (:below-grain grain)]
    (if (empty? bad)
      row
      (let [marker (fn [arm]
                     (let [offending (vec (sort (filter bad (distinct [:floor arm]))))
                           qs        (keep #(get (:worst grain) %) offending)]
                       {:unpublished  :below-clock-grain
                        :arms         offending
                        :tick         (:tick grain)
                        :worst-quanta (when (seq qs) (apply min qs))}))]
        (-> row
            (update :summary
                    (fn [m]
                      (into {}
                            (map (fn [[arm v]]
                                   ;; An entry a mask has ALREADY taken keeps the
                                   ;; marker it has: a failed DOM read-back is a
                                   ;; page that never changed, which is the more
                                   ;; serious thing to say about an arm than the
                                   ;; resolution of the clock that timed it.
                                   [arm (if (and (not (:unpublished v))
                                                 (or (bad :floor) (bad arm)))
                                          (marker arm)
                                          v)]))
                            m)))
            (update :head-to-head
                    (fn [m]
                      (into {}
                            (remove (fn [[_ v]] (or (bad (:numerator v)) (bad (:denominator v)))))
                            m))))))))

(defn measure-write!
  "`rounds` interleaved rounds of the write clock, `kind` ∈ `#{:narrow
  :bulk}`. Answers a promise of the published record.

  `clock` is [[clock-resolution!]]'s reading, and it decides which figures
  in the row have a magnitude at all: a window that does not exceed the
  clock's own grain publishes nothing a reader could quote
  ([[mask-below-grain]], rf2-d2tzk)."
  [arm-ids adapter kind rounds sampling clock]
  (doseq [id arm-ids] (reseed! id))
  (let [mounts (mount-write-arms! arm-ids adapter)]
    (-> (chain {:rounds-out [] :samples [] :unverified {} :total {} :position 0}
               (range rounds)
               (fn [acc _]
                 (-> (write-round! mounts kind sampling (:position acc))
                     (.then (fn [r]
                              {:rounds-out (conj (:rounds-out acc) (normalise (:readings r)))
                               :samples    (into (:samples acc) (:samples r))
                               :unverified (merge-with + (:unverified acc) (:unverified r))
                               :total      (merge-with + (:total acc) (:total r))
                               :position   (:position r)})))))
        (.then (fn [acc]
                 (release-write-arms! mounts)
                 ;; TWO PUBLICATION MASKS, both the instrument's own rule
                 ;; applied over the raw summary and head-to-head rather than
                 ;; a hand-written approximation of it, so the fixtures the
                 ;; self-test replays are the rules this row ships.
                 ;;
                 ;;   read-back — an arm whose writes did not reach the DOM
                 ;;               has no figure (rf2-x6g04). A FAULT.
                 ;;   grain     — an arm whose window does not exceed the
                 ;;               clock's own resolution has no magnitude
                 ;;               (rf2-d2tzk). A LIMIT.
                 ;;
                 ;; The read-back mask runs FIRST so that an arm carrying both
                 ;; keeps the fault marker: a page that never changed is the
                 ;; more serious thing to say about it, and the grain mask must
                 ;; not overwrite it with the milder one.
                 (let [row   (mask-failed-read-backs
                               {:witness      (keyword (str "U-" (name kind)))
                                :doc          (if (= kind :bulk)
                                                "all 300 cells written in ONE commit — bulk view work"
                                                (str narrow-batch-k " single-cell writes into a 300-cell "
                                                     "grid, each its own commit, all inside ONE timed "
                                                     "window — the narrow-write path. The per-write "
                                                     "figure is the sample divided by " narrow-batch-k))
                                ;; Stated on the row, because a reader comparing this
                                ;; against the pre-rf2-9zysg numbers is comparing two
                                ;; different windows and has to be told so.
                                :writes-per-sample (if (= kind :bulk) 1 narrow-batch-k)
                                :arms         (vec arm-ids)
                                :per-round    (:rounds-out acc)
                                :summary      (lane/across-rounds (mapv :ratio (:rounds-out acc)))
                                :head-to-head (head-to-head (:rounds-out acc))
                                :unverified   (:unverified acc)
                                :writes       (:total acc)
                                :samples      (:samples acc)})
                       grain (grain-record row (:tick clock))]
                   (-> row
                       (assoc :grain grain)
                       (mask-below-grain grain)))))
        (.catch (fn [e] (release-write-arms! mounts) (throw e))))))

;; ===========================================================================
;; What the two window shapes cost differently
;; ===========================================================================

(defn- yield-window!
  "Price `k` consecutive harness microtasks inside ONE clock window.

  `now-ms`, `k` yields, `now-ms`, and nothing else in between.

  The recursion mirrors [[window-of]]'s non-microtask branch EXACTLY —
  `(js/Promise.resolve nil)` with the continuation inside the `.then`, so
  the next turn begins in the turn the previous one finished in. That
  matters: threading these through a promise-returning `.then` instead
  would add two resolution ticks per step and price a window the arms
  never run. At `k = 1` this is the original one-turn reading exactly."
  [k]
  (let [t0 (lane/now-ms)]
    (letfn [(run [n]
              (if (zero? n)
                (js/Promise.resolve (- (lane/now-ms) t0))
                (-> (js/Promise.resolve nil)
                    (.then (fn [_] (run (dec n)))))))]
      (run k))))

(defn yield-cost!
  "Price the harness microtask against the same clock the write rows use —
  ONE turn per window, and `narrow-batch-k` turns per window.

  [[window-of]] gives a microtask-scheduled arm a window that does NOT
  contain this turn while every other arm's does, and that asymmetry has to
  be a NUMBER rather than an assurance — it is the only thing separating
  the reagent-slim write row from a like-for-like comparison against the
  floor beside it. Chrome clamps `performance.now()` to 100 µs, so a
  reading of `0.0` here is the instrument saying the difference is below
  its own resolution; anything else has to be subtracted before a ratio is
  quoted, and the report must say which happened.

  WHY THE SECOND WINDOW EXISTS (rf2-2rtt6.19). The narrow row now batches
  `narrow-batch-k` writes under one clock, so its window contains that many
  harness microtasks rather than one — for every arm except the
  microtask-scheduled one, which contains none. The one-turn reading bounds
  the asymmetry at `< 100 µs` for ONE turn; multiplying it by ten does NOT
  bound ten turns at `< 100 µs`, it bounds them at `< 1.0 ms`, which is up
  to ~26% of a 3.8–7.6 ms batched sample. `10 × below-resolution` is not
  below resolution, and that is the same argument that justified batching
  the writes in the first place: timing k operations as ONE window lifts
  the sample clear of the clamp, and is not the same as summing k
  separately-clamped readings.

  So the control is batched the way the row is. Ten turns share one clock,
  the per-turn figure is that sample divided by ten, and the asymmetry
  becomes a measurement instead of a multiplication.

  Measured OUTSIDE every arm's window, in its own turns, so it perturbs no
  published figure. Warmed like every other row, because a first promise
  turn is not a representative one."
  [{:keys [warmup samples]}]
  (let [run (fn [k]
              (chain []
                     (range (+ warmup samples))
                     (fn [acc s]
                       (-> (yield-window! k)
                           (.then (fn [ms] (cond-> acc (>= s warmup) (conj ms))))))))]
    ;; SEQUENTIALLY, never `Promise.all`: two microtask chains in flight at
    ;; once interleave on the one queue, and each would then be timing the
    ;; other's turns as well as its own. The second run nests inside the
    ;; first's `.then` and closes over its samples.
    (-> (run 1)
        (.then
          (fn [xs1]
            (.then
              (run narrow-batch-k)
              (fn [xsk]
                (let [s1  (lane/summarise xs1)
                      sk  (lane/summarise xsk)
                      per (fn [v] (when (number? v) (/ v narrow-batch-k)))]
                  {:control :harness-microtask-yield
                   ;; The original one-turn reading, unchanged, so nothing
                   ;; published against it moves.
                   :p50     (:p50 s1)
                   :min     (:min s1)
                   :max     (:max s1)
                   :n       (:n s1)
                   :clamp   "Chrome clamps performance.now() to 100 µs"
                   :note    (str "the turn every non-microtask-scheduled arm's write "
                                 "window contains and the reagent-slim arm's does not "
                                 "(rf2-b69lw); 0.0 means ONE such turn is below this "
                                 "instrument's resolution")
                   ;; The batched reading — the one the batched narrow row
                   ;; actually needs, because its window holds k of these.
                   :batched
                   {:k            narrow-batch-k
                    :window-p50   (:p50 sk)
                    :window-min   (:min sk)
                    :window-max   (:max sk)
                    :n            (:n sk)
                    :per-turn-p50 (per (:p50 sk))
                    :per-turn-min (per (:min sk))
                    :per-turn-max (per (:max sk))
                    :note (str narrow-batch-k " harness microtasks under ONE clock, "
                               "the same technique the narrow row uses for its "
                               "writes. This is the quantity the batched narrow "
                               "window's asymmetry actually is — present in every "
                               "arm except the microtask-scheduled one. The "
                               "one-turn reading above bounds ONE turn below the "
                               "clamp; it does NOT bound " narrow-batch-k
                               " of them, and this window measures them instead "
                               "of multiplying (rf2-2rtt6.19)")}}))))))))

;; ===========================================================================
;; The instrument's OWN resolution — measured, not asserted
;; ===========================================================================

(defn- tick-once
  "One observation of the clock's grain: the first difference
  [[lane/now-ms]] reports after a busy wait, or `nil` if it reported none
  within `spin-cap` reads.

  A clamped clock answers the SAME value for every read inside a tick, so
  the first difference IS one tick. The cap is not a tolerance — it is the
  only alternative to hanging the page on a clock that never advances, and
  it answers `nil` so a missing reading fails closed rather than arriving
  as a small number."
  [spin-cap]
  (let [t0 (lane/now-ms)]
    (loop [i 0]
      (let [t1 (lane/now-ms)]
        (cond
          (> t1 t0)      (round4 (- t1 t0))
          (>= i spin-cap) nil
          :else          (recur (inc i)))))))

(defn clock-resolution!
  "Measure the smallest interval this instrument's clock can report.

  Every page in this studio states *Chrome clamps `performance.now()` to
  100 µs* and then reasons against 100 µs as though it were a property of
  the measurement. It is a property of a browser build and its
  cross-origin-isolation state, and a row whose DENOMINATOR sits on that
  grain is decided by it — so the number is taken in the run it governs
  rather than quoted from a comment, on the same argument that made
  [[yield-cost!]] measure the harness turn instead of asserting it was
  small (rf2-d2tzk).

  The method is the only one a clamped clock permits: read, spin until the
  value CHANGES, record the difference. `:tick` is the MINIMUM across `n`
  such observations, because a spin that overran a boundary reports a
  multiple and the smallest observation is the only one that cannot.

  Answers `{:tick :p50 :max :n :distinct :unresolved}`. `:distinct` is the
  set of observed differences — on a clamped clock they are multiples of
  `:tick`, which is what makes the reading a RESOLUTION and not a latency.
  `:unresolved` counts observations that hit the spin cap; any of them
  leaves `:tick` nil, and [[denominator-resolution]] treats a missing tick
  as unevaluable rather than as zero."
  [n]
  (let [spin-cap 5000000
        ds       (vec (repeatedly n #(tick-once spin-cap)))
        good     (vec (remove nil? ds))
        s        (lane/summarise good)]
    {:control     :clock-resolution
     :tick        (when (= (count good) (count ds)) (:min s))
     :p50         (:p50 s)
     :max         (:max s)
     :n           (count ds)
     :unresolved  (- (count ds) (count good))
     :distinct    (vec (sort (distinct good)))
     :note (str "the smallest difference lane/now-ms reports, measured by spinning until "
                "the clock advances. A denominator at this size cannot be told from one "
                "half its size or twice it, so a ratio taken against it carries the grain "
                "rather than the arm (rf2-d2tzk)")}))

;; ===========================================================================
;; THE CORRECTION-OR-REFUSAL CONTRACT — the asymmetry adjudicated, not noted
;; ===========================================================================

(defn- window-shape
  "`:write-then-drain` (no harness microtask in the window) or
  `:write-yield-drain` (`k` of them), for `arm-id` under `adapter`."
  [arm-id adapter]
  (if (= (arm-scheduler arm-id adapter) :microtask)
    :write-then-drain
    :write-yield-drain))

(defn- correct-round
  "One round's p50 map with `bound` ms subtracted from every arm in
  `bearers`, and the floor ratios re-formed from the corrected p50s.

  Re-formed and not rescaled: the ratios are within-round by construction
  and the correction moves the DENOMINATOR whenever the floor is a bearer,
  which no rescaling of the published ratio can express.

  `checked` is the subset of `bearers` the SURVIVAL test is applied to —
  the ones a published figure is formed from ([[published-arms]]). Every
  bearer is still subtracted from, because the turns were in its window
  whatever the row prints; what a bearer with no published figure cannot
  do is refuse a row on behalf of a number nobody may quote (rf2-d2tzk)."
  [{:keys [p50]} bearers checked bound]
  (let [adj   (into {} (map (fn [[id v]] [id (if (contains? bearers id) (- v bound) v)])) p50)
        floor (get adj :floor)]
    ;; SURVIVAL: WHAT REMAINS MUST EXCEED WHAT WAS REMOVED.
    ;;
    ;; Only the BEARERS are checked, because only they were subtracted from —
    ;; an exempt arm reading zero is a clamp problem this contract did not
    ;; create and must not claim to have found.
    ;;
    ;; The test is not `pos?`, and finding that out cost a wrong number rather
    ;; than an argument. `pos?` passed a bulk row whose floor read one clock
    ;; quantum against a bound of one clock quantum: the corrected floor came
    ;; out at ~1e-8 ms, still positive, and the row published a corrected
    ;; `reagent-slim / floor` of 15,518,934x. A denominator that survives
    ;; subtraction by a rounding error is not a denominator.
    ;;
    ;; So a bearer survives only when the correction is the SMALLER part of its
    ;; window. That is not a threshold invented here — it is what makes a
    ;; correction a correction rather than the measurement, and it compares two
    ;; MEASURED quantities with no constant between them.
    (when (every? (fn [[_ v]] (> v bound)) (select-keys adj checked))
      {:p50   adj
       :ratio (into {} (map (fn [[id v]] [id (round4 (/ v floor))])) adj)})))

(defn- side-of-1
  "Which side of 1.0 a published range sits on — `:below`, `:straddles` or
  `:above`.

  Three states rather than the `:straddles-1?` boolean, because the boolean
  cannot see a COMPLETE crossing: a band wholly below 1.0 that lands wholly
  above it after correction is `false -> false`, no straddle at either end,
  and the PR #7282 audit built a live-rule counterexample that published
  exactly that — 0.95x unadjusted, 1.0556x corrected, a full direction
  reversal accepted as `:corrected`. The classification is still the
  instrument's own house rule extended to which side of the line a DECIDED
  band sits on: `:straddles-1?` names the indistinguishable state, and the
  other two are the only places a decided band can be."
  [v]
  (cond
    (:straddles-1? v) :straddles
    (< (:max v) 1.0)  :below
    :else             :above))

(defn- inherit-publication-mask
  "The corrected band for an arm the row's own summary marked
  `:unpublished` is that marker, never a number (rf2-b69lw, from the
  PR #7295 audit).

  The correction rebuilds its bands from `:per-round`, which retains
  every arm's raw timings — including an arm whose writes failed their
  DOM read-back, whose milliseconds are real and are measuring a page
  that never changed. Without this, the corrected-band path printed
  `1.200 – 1.300 [CORRECTED]` directly beneath `UNPUBLISHED (1/78
  unverified)` for the SAME arm, which defeats the rule that a failed
  read-back has no publishable timing. The mask is INHERITED from the
  original summary rather than recomputed, so the two bands cannot
  disagree about which arms have a figure."
  [corrected original]
  (into {}
        (map (fn [[id v]]
               (let [o (get original id)]
                 [id (if (:unpublished o) o v)])))
        corrected))

(defn- published-arms
  "Every arm the row still publishes a figure ABOUT or a figure AGAINST.

  A floor-normalised entry is formed from its own arm and from the floor,
  which is its denominator; a head-to-head pair from the two arms
  `lane/ratio-between` named in it. An entry a publication mask has
  replaced with `:unpublished` contributes nothing, because there is no
  longer a figure there for anything to change.

  This is what [[yield-correction]] adjudicates over. A bearer that no
  published figure is formed from cannot move one, and a contract that
  refused on it would be refusing on a number the row does not print
  (rf2-d2tzk)."
  [row]
  (let [normalised (into #{} (comp (remove (fn [[_ v]] (:unpublished v))) (map key)) (:summary row))
        pairs      (into #{} (mapcat (fn [[_ v]] [(:numerator v) (:denominator v)])) (:head-to-head row))]
    (cond-> (into pairs normalised)
      (seq normalised) (conj :floor))))

(defn yield-correction
  "Adjudicate one write row against the harness-microtask asymmetry, and
  answer a verdict rather than an observation (rf2-b69lw).

  ## What is owed, and why

  [[window-of]] gives a `:microtask`-scheduled arm a window with NOTHING
  between its write and its drain, and every other arm a window holding
  `k` harness microtasks. So on a row that mixes the two shapes, the
  yield-bearing arms are billed for turns their rival is not, and the
  studio record says plainly that any nonzero reading of that turn owes
  the reader a subtraction. It said so and nothing acted on it: the
  driver recorded [[yield-cost!]] beside the row, exited 0, and published
  unadjusted ratios over a slim run that had measured
  `{:p50 0 :max 0.1 :n 10}`. A contract nothing enforces is a sentence,
  not a contract.

  ## The five verdicts

  `:not-owed` — every arm in the row shares one window shape, so the turns
  are in the numerator and the denominator alike. Only a row that MIXES
  shapes owes anything, and today that is the `slim` run's write rows and
  no others.

  `:moot` — the row mixes shapes, but no figure it still PUBLISHES is
  formed from a yield-bearing arm ([[published-arms]]), so the correction
  has nothing to move and a refusal would have nothing to protect. This is
  the BULK row on the slim run: its only bearer is the floor, the floor is
  its denominator, and the floor sits on the clock's own grain — so
  [[mask-below-grain]] has already withdrawn every figure normalised by it,
  permanently and on stronger grounds than the harness turn. Before this
  verdict existed the row's disposition was drawn from the clock rather
  than from the measurement: a one-turn aggregate of `0.0` published it
  unadjusted and an aggregate of `0.1` refused it and failed the whole
  sweep, on the same page, the same arms and the same source, three times
  and twice out of five observed runs (rf2-d2tzk).

  `:below-resolution` — the row owes, and the aggregate yield window's MAX
  is zero across every sample. Chrome clamps `performance.now()` to 100 µs;
  a max of zero over `n` windows is the instrument saying it could not
  resolve the asymmetry in any of them. There is nothing to subtract, and
  the row publishes unadjusted with the bound on the record.

  `:corrected` — the row owes, the aggregate reads nonzero, and the
  subtraction is APPLIED. Its direction is fixed and known: the turns sit
  in the bearers' windows only, so removing them makes the bearers
  smaller. Where the floor is a bearer — which it is on the slim run — the
  corrected ratio is LARGER, and the published unadjusted figure is
  therefore a LOWER BOUND on the exempt arm's cost. Both ends are
  published; a reader is given the interval rather than a point that
  quietly sits at one end of it.

  `:refused` — the row owes and the correction cannot be discharged. Two
  ways, and both mean the same thing: the window is substantially the
  harness's own turns rather than the arm's work.

    (a) THE CORRECTION CHANGES THE VERDICT. If subtracting the bound moves
        any published range across 1.0 — indistinguishable becoming a
        finding, a finding becoming indistinguishable, or a band leaping
        the line WHOLE, wholly below 1.0 before the correction and wholly
        above it after — then what the row reports is an artefact of the
        asymmetry. The test is three-state ([[side-of-1]]): a band is
        below, straddling, or above, and ANY change of state is a
        crossing. It began as a comparison of the `:straddles-1?` boolean
        alone, and a complete reversal never flips that boolean — the PR
        #7282 audit's counterexample published 0.95x unadjusted as 1.0556x
        corrected through exactly that hole. The classification is still
        the instrument's OWN house rule extended to which side of the line
        a decided band sits on, not a threshold invented here: this
        contract remains not entitled to a tolerance of its own.

    (b) THE CORRECTION EXCEEDS THE WINDOW — a bearer for which what REMAINS
        after the subtraction does not exceed what was REMOVED. That window
        was measuring the harness's turns, not the arm. `pos?` is not the
        test and the difference is not academic: it passed a bulk row whose
        floor read one clock quantum against a bound of one clock quantum,
        left a corrected floor of ~1e-8 ms, and published a corrected ratio
        of 15,518,934x. See [[correct-round]].

  Refusal is not this function's to soften and not the caller's to widen.
  `hd8-app` fails the run on one, exactly as it does on a positive control
  that missed its prediction.

  ## The publication mask survives the correction

  A `:corrected` verdict's bands are rebuilt from `:per-round`, which
  retains raw timings for EVERY arm — including one whose writes failed
  their DOM read-back and whose `:summary` entry is therefore the
  `:unpublished` marker rather than a band. The corrected bands INHERIT
  that mask ([[inherit-publication-mask]]): the marker stays in the
  masked arm's place, and a head-to-head pair the mask dropped stays
  dropped. A correction adjusts a figure the row was entitled to publish;
  it must never mint one the read-back refused (rf2-b69lw, from the
  PR #7295 audit).

  ## Which reading is subtracted

  The aggregate measured for THIS row's `k`, never a per-turn figure
  multiplied up — `10 x below-resolution` is not below resolution, which is
  the same argument that batched the writes in the first place. The narrow
  row's `k` is [[narrow-batch-k]] and takes `yield-cost!`'s `:batched`
  window; the bulk row's `k` is 1 and takes the one-turn reading. A row
  whose `k` has no measured aggregate is `:refused` as unevaluable rather
  than corrected with the nearest available number.

  The bound subtracted is the aggregate's MAX. The p50 would be the central
  estimate of a correction; the max is the largest the correction can
  honestly be, and a contract that decides whether a row may be published
  has to be evaluated at the end that could change the answer."
  [row adapter yc]
  (let [k        (:writes-per-sample row)
        arms     (:arms row)
        shapes   (into {} (map (fn [id] [id (window-shape id adapter)])) arms)
        bearers  (into #{} (keep (fn [[id s]] (when (= s :write-yield-drain) id))) shapes)
        exempt   (into #{} (keep (fn [[id s]] (when (= s :write-then-drain) id))) shapes)
        ;; Only over the figures the row still PUBLISHES ([[published-arms]]).
        ;; A bearer whose column a publication mask has already withdrawn has
        ;; no figure left for the subtraction to move or for a refusal to
        ;; protect, and the difference is not theoretical: the BULK row's
        ;; floor is its only bearer on the slim run and it sits ON the clock's
        ;; grain, so this row's disposition used to be a coin toss between
        ;; publishing unadjusted (a 0.0 aggregate) and failing the whole sweep
        ;; (a 0.1 aggregate) — the same page, the same arms, two opposite
        ;; answers drawn from the clock rather than from the measurement
        ;; (rf2-d2tzk).
        published (published-arms row)
        borne     (into #{} (filter published) bearers)
        base     {:row (:witness row) :k k :windows shapes
                  :bearers (vec bearers) :exempt (vec exempt)
                  :bearers-published (vec borne)}
        agg      (cond
                   (= k narrow-batch-k) (let [b (:batched yc)]
                                          (when b {:source :batched-aggregate
                                                   :k      (:k b)
                                                   :p50    (:window-p50 b)
                                                   :max    (:window-max b)
                                                   :n      (:n b)}))
                   (= k 1)              {:source :one-turn
                                         :k      1
                                         :p50    (:p50 yc)
                                         :max    (:max yc)
                                         :n      (:n yc)}
                   :else                nil)]
    (cond
      (or (empty? bearers) (empty? exempt))
      (assoc base :verdict :not-owed
                  :why (str "every arm in this row was measured through the same window shape ("
                            (name (or (first (vals shapes)) :none))
                            "), so the harness turns are in the numerator and the denominator "
                            "alike and no arm is billed for a turn its rival escapes"))

      (empty? borne)
      (assoc base :verdict :moot
                  :reason :no-published-figure-bears-it
                  :why (str "this row mixes window shapes, but every figure it still publishes is "
                            "formed only from arms that escape the harness turn ("
                            (str/join ", " (map name exempt)) "). The yield-bearing arm(s) "
                            (str/join ", " (map name bearers)) " carry no published figure — a "
                            "publication mask has already withdrawn the column they denominate — "
                            "so there is nothing for the subtraction to move and nothing for a "
                            "refusal to protect. Head-to-head pairs are arm-over-arm and the "
                            "floor cancels out of them exactly"))

      (nil? agg)
      (assoc base :verdict :refused
                  :reason :unevaluable
                  :why (str "this row batches " k " writes to a window and yield-cost! measured no "
                            "aggregate for that k, so the asymmetry it owes cannot be priced. "
                            "Measure " k " turns under one clock — never multiply the one-turn "
                            "reading, which bounds one turn and not " k " of them"))

      (not (and (number? (:max agg)) (>= (:max agg) 0)))
      (assoc base :verdict :refused :reason :unevaluable :aggregate agg
                  :why "the aggregate yield window is not a number")

      (zero? (:max agg))
      (assoc base :verdict :below-resolution :aggregate agg
                  :why (str "the " (:k agg) "-turn aggregate read 0.0 ms in every one of its "
                            (:n agg) " windows, so the asymmetry is below this instrument's "
                            "own resolution (Chrome clamps performance.now() to 100 µs). "
                            "Nothing to subtract; the row publishes unadjusted and the bound "
                            "is on the record rather than assumed"))

      :else
      (let [bound     (:max agg)
            corrected (mapv #(correct-round % bearers borne bound) (:per-round row))]
        (if (some nil? corrected)
          (assoc base :verdict :refused :reason :correction-exceeds-window
                      :aggregate agg :bound bound
                      :why (str "in at least one round a yield-bearing arm has LESS LEFT after "
                                "subtracting the " (:k agg) "-turn aggregate bound of " bound
                                " ms than the subtraction removed. That window was measuring "
                                "the harness's turns rather than the arm, so no ratio taken "
                                "against it may be published — and a corrected value that "
                                "survives only by a rounding error is not a denominator"))
          (let [;; BOTH corrected bands inherit the original row's publication
                ;; mask ([[inherit-publication-mask]]): an arm UNPUBLISHED for
                ;; a failed DOM read-back keeps its marker in the corrected
                ;; summary, and a head-to-head pair the mask dropped stays
                ;; dropped — the corrected map carries exactly the pairs the
                ;; original published, never one it withheld.
                summary'  (inherit-publication-mask
                            (lane/across-rounds (mapv :ratio corrected))
                            (:summary row))
                h2h'      (select-keys (head-to-head corrected)
                                       (keys (:head-to-head row)))
                ;; [[side-of-1]] on both ends, and ANY change of state is a
                ;; crossing. Comparing the `:straddles-1?` booleans is not
                ;; the same test: a band that leaps the line WHOLE never
                ;; straddles at either end (PR #7282 audit).
                crossed   (fn [id v v']
                            (when (and v' (not (:unpublished v))
                                       (not= (side-of-1 v) (side-of-1 v')))
                              {:figure id :was v :corrected v'
                               :was-side (side-of-1 v) :now-side (side-of-1 v')}))
                flipped   (into []
                                (keep (fn [[id v]] (crossed id v (get summary' id))))
                                (:summary row))
                flipped   (into flipped
                                (keep (fn [[id v]] (crossed id v (get h2h' id))))
                                (:head-to-head row))]
            (if (seq flipped)
              (assoc base :verdict :refused :reason :correction-changes-the-verdict
                          :aggregate agg :bound bound :flipped flipped
                          :why (str "subtracting the measured " (:k agg) "-turn asymmetry ("
                                    bound " ms) moves " (count flipped) " published range(s) "
                                    "across 1.0. What this row reports is the asymmetry and "
                                    "not the arms; no figure in it may be published"))
              (assoc base :verdict :corrected
                          :aggregate agg :bound bound
                          :summary-corrected summary'
                          :head-to-head-corrected h2h'
                          :why (str "the " (:k agg) "-turn aggregate read " bound
                                    " ms at its worst window; it is subtracted from every "
                                    "yield-bearing arm (" (str/join ", " (map name bearers))
                                    ") and the ratios re-formed within each round. The "
                                    "correction's direction is fixed — removing turns makes a "
                                    "bearer smaller — so the published unadjusted figure and "
                                    "the corrected one bracket the answer, and no range "
                                    "crossed 1.0 between them")))))))))

(defn- fixture-row
  "A synthetic write row built from per-round p50 maps, so a fixture is a
  handful of milliseconds rather than a hand-written summary that could
  disagree with its own rounds."
  [witness k arms rounds]
  (let [per-round (mapv (fn [p50]
                          (let [floor (get p50 :floor)]
                            {:p50   p50
                             :ratio (into {} (map (fn [[id v]] [id (round4 (/ v floor))])) p50)}))
                        rounds)]
    {:witness           witness
     :arms              arms
     :writes-per-sample k
     :per-round         per-round
     :summary           (lane/across-rounds (mapv :ratio per-round))
     :head-to-head      (head-to-head per-round)}))

(defn- grain-fixture
  "A [[fixture-row]] carried through the instrument's OWN clock-grain rule
  at a stated `tick`, so a grain fixture is a handful of milliseconds and a
  resolution rather than a hand-written marker that could drift from what
  [[measure-write!]] applies (rf2-d2tzk). `tick` may be `nil` — that is the
  reading a clock which never advanced produces, and it must fail closed."
  [witness k arms rounds tick]
  (let [row   (fixture-row witness k arms rounds)
        grain (grain-record row tick)]
    (-> row (assoc :grain grain) (mask-below-grain grain))))

(defn yield-correction-self-test
  "Replay the write rows' PUBLICATION RULES — [[mask-below-grain]] and
  [[yield-correction]] — over recorded fixtures, and answer
  `{:ok? :checks}`.

  **A gate nobody has watched refuse is not a gate**, and this one refuses
  rarely by design: the harness asymmetry reads `0.0` on most runs of this
  host and resolves only intermittently, so a live run cannot be relied on
  to exercise the branch that matters. `parity-can-fail?` makes exactly
  this argument about the parity gate and `hd8_run.cjs`'s gate 4 makes it
  about the DOM read-back. This is the same argument for the correction
  contract, and the technique is `order_guard.cjs`'s: fixtures replayed
  through the live rule, before anything is measured, deterministic, and
  fatal when they disagree.

  Fixture 4 is not invented. It is the row that made the survival test
  `pos?` publish a corrected ratio of 15,518,934x — a bulk floor of one
  clock quantum against a bound of one clock quantum — and it is here so
  that repair cannot silently come undone. Fixture 7 is not invented
  either: it is the PR #7282 audit's counterexample, the band that crossed
  1.0 WHOLE without flipping `:straddles-1?` at either end, published as
  0.95x unadjusted and 1.0556x corrected — kept here for the same reason.
  Neither is fixture 8: it is the PR #7295 audit's polarity, an arm
  UNPUBLISHED for a failed DOM read-back (1 of 78) whose corrected band
  was rebuilt numeric from the retained `:per-round` timings and printed
  `1.200 – 1.300 [CORRECTED]` beneath the marker — masked by
  [[mask-failed-read-backs]] itself, the instrument's own rule, so the
  fixture cannot drift from what [[measure-write!]] actually applies."
  []
  (let [zero-yc    {:p50 0 :min 0 :max 0 :n 10
                    :batched {:k narrow-batch-k :window-p50 0 :window-max 0 :n 10}}
        live-yc    {:p50 0 :min 0 :max 0.1 :n 10
                    :batched {:k narrow-batch-k :window-p50 0 :window-max 0.1 :n 10}}
        no-agg-yc  {:p50 0 :min 0 :max 0.1 :n 10}
        verdict-of (fn [row adapter yc] (yield-correction row adapter yc))
        checks
        [;; 1. Both arms carry the harness turns, so nothing is asymmetric.
         (let [v (verdict-of (fixture-row :U-narrow narrow-batch-k [:floor :reagent]
                                          [{:floor 1.0 :reagent 5.0} {:floor 1.0 :reagent 5.0}])
                             :reagent zero-yc)]
           {:name "one window shape in the row is NOT-OWED"
            :ok   (= :not-owed (:verdict v))
            :detail (name (:verdict v))})

         ;; 2. Mixed shapes, aggregate flat zero across every window.
         (let [v (verdict-of (fixture-row :U-narrow narrow-batch-k [:floor :reagent-slim]
                                          [{:floor 1.0 :reagent-slim 5.0} {:floor 1.2 :reagent-slim 6.0}])
                             :slim zero-yc)]
           {:name "mixed shapes with a 0.0 aggregate is BELOW-RESOLUTION"
            :ok   (= :below-resolution (:verdict v))
            :detail (name (:verdict v))})

         ;; 3. Mixed shapes, aggregate resolves, the bearer survives and no
         ;;    range crosses 1.0 — so the subtraction is applied and the
         ;;    corrected ratio is LARGER, which is the fixed direction.
         (let [v (verdict-of (fixture-row :U-narrow narrow-batch-k [:floor :reagent-slim]
                                          [{:floor 1.0 :reagent-slim 5.0} {:floor 1.2 :reagent-slim 6.0}])
                             :slim live-yc)
               was  (get-in (fixture-row :U-narrow narrow-batch-k [:floor :reagent-slim]
                                         [{:floor 1.0 :reagent-slim 5.0} {:floor 1.2 :reagent-slim 6.0}])
                            [:summary :reagent-slim :min])
               now  (get-in v [:summary-corrected :reagent-slim :min])]
           {:name "a resolving aggregate is CORRECTED, and the corrected ratio is larger"
            :ok   (and (= :corrected (:verdict v)) (number? now) (> now was))
            :detail (str (name (:verdict v)) " " was " -> " now)})

         ;; 4. THE 15,518,934x ROW. A bulk floor of one quantum against a
         ;;    bound of one quantum: `pos?` passed it, this must not.
         (let [v (verdict-of (fixture-row :U-bulk 1 [:floor :reagent-slim]
                                          [{:floor 0.1 :reagent-slim 1.5} {:floor 0.1 :reagent-slim 1.6}])
                             :slim live-yc)]
           {:name "a bearer with less left than was removed is REFUSED (the 15,518,934x row)"
            :ok   (and (= :refused (:verdict v))
                       (= :correction-exceeds-window (:reason v)))
            :detail (str (name (:verdict v)) "/" (some-> (:reason v) name))})

         ;; 5. The correction moves a range across 1.0, so the row is
         ;;    reporting the asymmetry rather than the arms.
         (let [v (verdict-of (fixture-row :U-narrow narrow-batch-k [:floor :reagent-slim]
                                          [{:floor 1.0 :reagent-slim 0.9} {:floor 1.0 :reagent-slim 1.1}])
                             :slim {:p50 0 :min 0 :max 0.3 :n 10
                                    :batched {:k narrow-batch-k :window-p50 0 :window-max 0.3 :n 10}})]
           {:name "a correction that moves a range across 1.0 is REFUSED"
            :ok   (and (= :refused (:verdict v))
                       (= :correction-changes-the-verdict (:reason v)))
            :detail (str (name (:verdict v)) "/" (some-> (:reason v) name))})

         ;; 6. No measured aggregate for this row's k. Refused as
         ;;    unevaluable, never corrected with the nearest number to hand.
         (let [v (verdict-of (fixture-row :U-narrow narrow-batch-k [:floor :reagent-slim]
                                          [{:floor 1.0 :reagent-slim 5.0} {:floor 1.2 :reagent-slim 6.0}])
                             :slim no-agg-yc)]
           {:name "no aggregate measured for this row's k is REFUSED as unevaluable"
            :ok   (and (= :refused (:verdict v)) (= :unevaluable (:reason v)))
            :detail (str (name (:verdict v)) "/" (some-> (:reason v) name))})

         ;; 7. THE WHOLE-BAND CROSSING (PR #7282 audit). A band wholly below
         ;;    1.0 that lands wholly above it after correction never flips
         ;;    `:straddles-1?` — false -> false at both ends — and the
         ;;    boolean test published exactly this complete direction
         ;;    reversal as :corrected: 0.95x unadjusted, 1.0556x corrected.
         ;;    [[side-of-1]] is three-state so that it cannot.
         (let [v (verdict-of (fixture-row :U-narrow narrow-batch-k [:floor :reagent-slim]
                                          [{:floor 1.0 :reagent-slim 0.95} {:floor 1.0 :reagent-slim 0.95}])
                             :slim live-yc)]
           {:name "a band that crosses 1.0 WHOLE (no straddle at either end) is REFUSED"
            :ok   (and (= :refused (:verdict v))
                       (= :correction-changes-the-verdict (:reason v)))
            :detail (str (name (:verdict v)) "/" (some-> (:reason v) name))})

         ;; 8. THE PUBLICATION MASK SURVIVES THE CORRECTION (PR #7295
         ;;    audit). An arm whose writes failed their DOM read-back is
         ;;    UNPUBLISHED in the original summary, but its raw timings are
         ;;    retained in :per-round — and the corrected band, rebuilt from
         ;;    exactly those timings, printed a number the read-back had
         ;;    refused: `UNPUBLISHED (1/78 unverified) [UNADJUSTED]` then
         ;;    `1.200 – 1.300 [CORRECTED]` for the SAME arm. The corrected
         ;;    summary must carry the marker in that arm's place, the
         ;;    dropped head-to-head pairs must stay dropped, and a
         ;;    PUBLISHED arm's corrected band must still be numeric — the
         ;;    row is masked by [[mask-failed-read-backs]], the rule
         ;;    [[measure-write!]] itself applies.
         (let [row  (-> (fixture-row :U-narrow narrow-batch-k
                                     [:floor :reagent-slim :donor-r1 :donor-r2]
                                     [{:floor 1.0 :reagent-slim 5.0 :donor-r1 2.0 :donor-r2 3.0}
                                      {:floor 1.2 :reagent-slim 6.0 :donor-r1 2.4 :donor-r2 3.6}])
                        (assoc :unverified {:floor 0 :reagent-slim 1 :donor-r1 0 :donor-r2 0}
                               :writes     {:floor 78 :reagent-slim 78 :donor-r1 78 :donor-r2 78})
                        mask-failed-read-backs)
               v    (verdict-of row :slim live-yc)
               slim (get-in v [:summary-corrected :reagent-slim])
               d1   (get-in v [:summary-corrected :donor-r1])]
           {:name "a corrected band inherits its original's publication mask (failed DOM read-back)"
            :ok   (and (= :corrected (:verdict v))
                       (= :failed-dom-read-back (:unpublished slim))
                       (nil? (:min slim))
                       (number? (:min d1))
                       (contains? (:head-to-head-corrected v) :donor-r2-over-donor-r1)
                       (not (contains? (:head-to-head-corrected v) :donor-r1-over-reagent-slim)))
            :detail (str (name (:verdict v))
                         " reagent-slim-corrected=" (pr-str slim)
                         " h2h-corrected=" (pr-str (vec (keys (:head-to-head-corrected v)))))})

         ;; 9. THE CLOCK-GRAIN MASK (rf2-d2tzk). The live bulk row: a floor
         ;;    of one and two 0.1 ms ticks under a measured 0.1 ms grain. The
         ;;    floor is every floor-normalised figure's DENOMINATOR, so the
         ;;    whole column goes — and the head-to-head pairs, which are
         ;;    arm-over-arm with the floor cancelling exactly, STAY. The
         ;;    numbers are the run recorded on this bead, not invented.
         (let [row   (grain-fixture :U-bulk 1 [:floor :reagent-slim :donor-r1]
                                    [{:floor 0.1 :reagent-slim 1.75 :donor-r1 1.2}
                                     {:floor 0.2 :reagent-slim 1.7  :donor-r1 1.3}]
                                    0.1)
               slim  (get-in row [:summary :reagent-slim])]
           {:name "a floor at the clock's own grain unpublishes the floor-normalised column"
            :ok   (and (= :below-clock-grain (:unpublished slim))
                       (nil? (:min slim))
                       (= [:floor] (:arms slim))
                       (= :below-clock-grain (:unpublished (get-in row [:summary :floor])))
                       (contains? (:head-to-head row) :donor-r1-over-reagent-slim))
            :detail (str "reagent-slim=" (pr-str slim)
                         " h2h=" (pr-str (vec (keys (:head-to-head row)))))})

         ;; 10. AND THE CORRECTION OVER THAT ROW IS MOOT — which is the whole
         ;;     of this bead. The same fixture, the same aggregate that made
         ;;     the unmasked row REFUSE at check 4, now has no published
         ;;     figure to refuse over: the bearer is the floor and the floor's
         ;;     column is gone. Two draws of the clock, one answer.
         (let [row  (grain-fixture :U-bulk 1 [:floor :reagent-slim]
                                   [{:floor 0.1 :reagent-slim 1.5} {:floor 0.1 :reagent-slim 1.6}]
                                   0.1)
               live (verdict-of row :slim live-yc)
               zero (verdict-of row :slim zero-yc)]
           {:name "and the correction over a grain-masked row is MOOT on BOTH draws of the clock"
            :ok   (and (= :moot (:verdict live)) (= :moot (:verdict zero))
                       (= :no-published-figure-bears-it (:reason live)))
            :detail (str "resolving=" (name (:verdict live)) " flat=" (name (:verdict zero)))})

         ;; 11. THE MASK IS NOT A BLANKET. A floor well clear of the grain —
         ;;     the batched NARROW row, 9 and 10 ticks on the same 0.1 ms
         ;;     clock — publishes exactly as before, and its correction is
         ;;     adjudicated exactly as before. Same rule, same clock, the
         ;;     other answer: what separates the two rows is the measurement.
         (let [row (grain-fixture :U-narrow narrow-batch-k [:floor :reagent-slim]
                                  [{:floor 0.9 :reagent-slim 4.45} {:floor 1.0 :reagent-slim 5.3}]
                                  0.1)
               v   (verdict-of row :slim live-yc)]
           {:name "a floor nine to ten ticks clear of the grain is untouched, and still corrected"
            :ok   (and (number? (get-in row [:summary :reagent-slim :min]))
                       (nil? (:unpublished (get-in row [:summary :reagent-slim])))
                       (= :corrected (:verdict v)))
            :detail (str "band=" (pr-str (get-in row [:summary :reagent-slim]))
                         " verdict=" (name (:verdict v)))})

         ;; 12. FAIL CLOSED ON AN UNMEASURABLE CLOCK. `clock-resolution!`
         ;;     answers a nil tick when the clock never advanced inside its
         ;;     spin cap. An instrument that cannot state its own resolution
         ;;     has not earned a magnitude, so EVERY arm is below the grain —
         ;;     never "no tick, so nothing is below it", which is the
         ;;     fail-open reading of the same fact.
         (let [row (grain-fixture :U-bulk 1 [:floor :reagent-slim]
                                  [{:floor 5.0 :reagent-slim 50.0}]
                                  nil)]
           {:name "a clock that could not state its own resolution unpublishes everything"
            :ok   (= :below-clock-grain (:unpublished (get-in row [:summary :reagent-slim])))
            :detail (pr-str (get-in row [:summary :reagent-slim]))})]]
    {:ok?    (every? :ok checks)
     :checks (mapv (fn [c] (update c :ok boolean)) checks)}))

;; ===========================================================================
;; The positive control — predicted against measured, every run
;; ===========================================================================

(defn positive-control!
  "The floor arm mounting the M page at N and at N/2.

  The page is `3 + 3N` elements, so the PREDICTED clock ratio is
  `(3 + 3N) / (3 + 3(N/2))` — 1.9900 at N = 300. It is a prediction and
  not an observation: it comes from the witness's own arithmetic, before
  the clock is read.

  This is the row that separates *this arm is cheap* from *the instrument
  is not running*. An instrument that reported a plausible number for a
  page it never built, or whose window was dominated by fixed per-mount
  cost rather than by the page, misses here — the ratio collapses toward
  1.0 as the fixed cost grows. The tolerance is wide (±30%) because the
  window genuinely does contain `createRoot` and a `flushSync`; it is
  wide enough to pass an honest instrument and far too tight to pass one
  that is not measuring the page."
  [rounds sampling]
  (let [n         w/rows-n
        half      (quot n 2)
        predicted (/ (double (w/m-elements n)) (double (w/m-elements half)))
        ;; The two sizes are TWO ARMS in ONE round, interleaved and
        ;; reflected like everything else. They were not, in the first cut,
        ;; and the control promptly read 0.42 in one round of three — the
        ;; full page measured FASTER than the half page. A control measured
        ;; as two consecutive blocks is subject to the very drift it exists
        ;; to detect, which would have made it a source of false alarm
        ;; rather than an instrument.
        full-arm  (react-root-arm :control-full
                                  (fn [_] (w/floor-m {:rows (:rows (db-of :floor)) :n n})))
        half-arm  (react-root-arm :control-half
                                  (fn [_] (w/floor-m {:rows (:rows (db-of :floor)) :n half})))
        per-round (mapv (fn [_]
                          (let [r (mount-round! [full-arm half-arm] {} sampling 0)]
                            (round4 (/ (p50 (get (:readings r) :control-full))
                                       (p50 (get (:readings r) :control-half))))))
                        (range rounds))
        lo (apply min per-round)
        hi (apply max per-round)
        tol 0.30]
    {:control    :floor-M-page-halved
     :predicted  (round4 predicted)
     :measured   {:min lo :max hi :rounds per-round}
     :tolerance  tol
     ;; EVERY round inside the band, not merely a range that overlaps it: a
     ;; control whose worst round is wrong has caught something, and letting
     ;; a good round vouch for a bad one is how an instrument stops being one.
     :within?    (and (>= lo (* predicted (- 1.0 tol)))
                      (<= hi (* predicted (+ 1.0 tol))))
     :note       (str "predicted from the witness arithmetic (3 + 3N) / (3 + 3(N/2)) "
                      "at N=" n ", before any clock was read; the two sizes are "
                      "interleaved arms in one round, not consecutive blocks")}))

;; ===========================================================================
;; The lowering correctness check — rung 2 must LOWER, not merely construct
;; ===========================================================================

(defn lowering-works!
  "Fire ONE click through rung 2's codec-lowered handler, outside every
  measured window, and read the result back out of the DOM. Answers a
  promise.

  Without this the rung-2 clock could be pricing a lowering that produces
  a closure nobody can call — the fastest possible implementation of the
  wrong thing, and one that would flatter rung 2 rather than fail it.
  `:hd8/touch` writes `\"T\"` into the clicked cell, so a working lowering
  is visible at the DOM and a broken one is not.

  Asynchronous because `dispatch` is: re-frame's event queue drains on its
  own turn, so a synchronous read-back after `.click` sees the page before
  the event ran and reports a working lowering as broken. (It did, on the
  first run of this check — which is the read-back doing its job in the
  safe direction.) The yield is a macrotask, comfortably past the queue's
  own drain, and the check is outside every clock so it costs nothing that
  is published. The run's own [[spine-drain!]] follows, because under a
  ratom spine nothing reaches the DOM without it."
  [adapter]
  (reseed! :donor-r2)
  (let [fid       (frame-of :donor-r2)
        container (js/document.createElement "div")
        _         (.appendChild js/document.body container)
        root      (react-dom-client/createRoot container)
        cleanup!  (fn []
                    (try (react-dom/flushSync (fn [] (.unmount root)))
                         (catch :default e
                           (lane/teardown-failure! "lowering-works! cleanup" e)))
                    (.remove container)
                    (reseed! :donor-r2))]
    (react-dom/flushSync
      (fn [] (.render root (provided fid (w/slim-element (w/r2-u 8))))))
    (let [before (cell-text container 3)
          node   (.querySelector container "[data-i=\"3\"]")]
      (.click node)
      (-> (js/Promise. (fn [resolve] (js/setTimeout #(resolve nil) 0)))
          (.then (fn [_]
                   (spine-drain! adapter)
                   (let [after (cell-text container 3)
                         ;; The app-db value beside the DOM value, because
                         ;; the two failure modes need different repairs and
                         ;; look identical from the DOM alone: `:db-after`
                         ;; unchanged means the lowered closure never
                         ;; dispatched; `:db-after` written while `:after` is
                         ;; stale means it dispatched and the view did not
                         ;; follow, which is a drain problem, not a lowering
                         ;; one.
                         db-after (get-in (db-of :donor-r2) [:cells 3])]
                     (cleanup!)
                     {:before   before
                      :after    after
                      :db-after db-after
                      :lowered? (and (not= before after) (= "T" after))})))
          (.catch (fn [e] (cleanup!) (throw e)))))))

;; ===========================================================================
;; Provenance
;; ===========================================================================

(defn method-record
  "The method, published beside every figure so a reader never has to
  reconstruct it from prose."
  [adapter arm-ids write-ids rounds mount-sampling write-sampling]
  {:bead              "rf2-2rtt6.7"
   :decision          "HD-008"
   :adapter           adapter
   :mount-arms        (vec arm-ids)
   :write-arms        (vec write-ids)
   ;; WHICH CODEC builds each arm's markup — the axis the fourth arm varies,
   ;; and the one thing a reader cannot recover from an arm id (rf2-2rtt6.29).
   ;; Two arms in this table run a RUNTIME hiccup interpreter over the same
   ;; page through the same boundary; naming them here is what stops
   ;; `donor-fh / donor-r1` being read as anything other than what it is.
   :markup-codec      {:floor        "hand-written react/createElement — no codec"
                       :reagent      "stock Reagent's template (reg-view)"
                       :reagent-slim "reagent2.impl.template/as-element (reg-view)"
                       :uix          "uix.core/$ — a MACRO; createElement at compile time, no runtime walk"
                       :donor-r1     "reagent2.impl.template/as-element"
                       :donor-r2     "reagent2.impl.template/as-element"
                       :donor-fh     "re-frame.freehand.react/element"}
   ;; Which window shape each write arm was measured through, stated rather
   ;; than left to be inferred from the adapter (rf2-b69lw). A row whose
   ;; reader cannot tell whether its window contained a harness microtask
   ;; cannot tell whether two arms in it were compared like for like.
   :write-windows     (into {} (map (fn [id]
                                      [id (let [s (arm-scheduler id adapter)]
                                            (if (= s :microtask)
                                              {:scheduler s :window :write-then-drain}
                                              {:scheduler s :window :write-yield-drain}))]))
                            write-ids)
   ;; The runtime is labelled beside every figure, not in a footnote. HD-012
   ;; makes every bar-relevant number a BROWSER number under `:advanced`
   ;; with goog.DEBUG false; a JVM or Node figure is diagnostic only and is
   ;; never quotable against the bar. A record that could not say which it
   ;; was would be quotable by accident.
   :runtime           {:host       "browser (Chromium via Playwright)"
                       :user-agent (when (exists? js/navigator) (.-userAgent js/navigator))
                       :build      "shadow-cljs :advanced, goog.DEBUG false"
                       :react      (or (.-version ^js react-dom) "unknown")}
   :rounds            rounds
   :mount-sampling    mount-sampling
   :write-sampling    write-sampling
   ;; A narrow SAMPLE is k writes under one clock (rf2-9zysg); a bulk
   ;; sample is one. Published because the narrow figures before that
   ;; change were taken through a different window and are not comparable
   ;; to these without knowing it.
   :writes-per-sample {:narrow narrow-batch-k :bulk 1}
   :measurement-method
   (str "arms interleaved at the SAMPLE level with the order rotating AND "
        "REFLECTING on the sample index (a cyclic rotation does not vary "
        "adjacency); every sample carries its predecessor and its position in "
        "the run; ratios taken against the floor measured in the SAME round; "
        "reported as a RANGE across rounds, and a range straddling 1.0 is "
        "INDISTINGUISHABLE rather than a winner; every write read back out of "
        "the DOM inside its own window — where a NARROW window is "
        narrow-batch-k " writes to " narrow-batch-k " distinct cells "
        "under one clock, each read back after the clock stops and in the same "
        "turn as the last drain, so a commit React parked at the default lane "
        "(a macrotask, and no macrotask runs inside the window) still reads "
        "UNVERIFIED")})
