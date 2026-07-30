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

      run           arms                                         answers
      uix           floor uix donor-r1 donor-r2                  donor vs frontier
      reagent       floor reagent uix donor-r1 donor-r2          donor vs stock Reagent
      slim          floor reagent-slim uix donor-r1 donor-r2     donor vs reagent-slim

  The frontier arm rides all three, which also prices what the SUB
  IMPLEMENTATION contributes: the same UIx arm measured over Reagent
  reactions and over the React spine's containers.

  Normative owner: `docs/design/hicasso/decisions.md` HD-008; results to
  `rf2-2rtt6.1` and `docs/design/hicasso/studio/`."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
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
                                                (provided fid (w/slim-element (w/r2-m n))))))))

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
                                                (provided fid (w/slim-element (w/r2-u n))))))))

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
  {:uix     [:floor :uix :donor-r1 :donor-r2]
   :reagent [:floor :reagent :uix :donor-r1 :donor-r2]
   :slim    [:floor :reagent-slim :uix :donor-r1 :donor-r2]})

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
  {:uix     [:floor :uix :donor-r1 :donor-r2]
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

(defn- round4 [x] (/ (js/Math.round (* (double x) 10000.0)) 10000.0))
(defn- p50 [xs] (:p50 (lane/summarise xs)))

(defn slot-order
  "Slot order for `k` arms at sample index `s`.

  `lane/slot-order` rotates forward by `s` and then REFLECTS on odd
  `s`, which gives every arm at least two distinct predecessors — for
  `k >= 3`. AT `k = 2` THE TWO OPERATIONS CANCEL: rotating a pair by one is
  the same permutation as reversing it, so `[0 1]` comes back at every
  index and the plan runs in ONE ORDER for ever.

  The arm-order guard found this rather than a reader: the two-arm runs
  came back `only 1 stratum — the question was never asked`, and REFUSED,
  which is precisely what a single-order result is supposed to do. The
  repair belongs to the PLAN, never to the guard — so a pair alternates
  explicitly here, and everything wider defers to the shared harness.

  The shared copies are left alone deliberately. There are THREE of them —
  `re-frame.bench.order-guard/slot-order` (which `lane/slot-order`
  re-exports), `b6-harness/slot-order`, and `order_guard.cjs`'s
  `schedule` — all carrying the same arithmetic and therefore the same
  degeneracy, and sibling P0 arms are measuring on them right now. A shared
  instrument must not change under a measurement in flight, so the defect
  is FILED (rf2-ouwh8) rather than patched here, and this local override is
  deleted when that lands."
  [k s]
  (if (= k 2)
    (if (even? s) [0 1] [1 0])
    (lane/slot-order k s)))

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
  one figure in this instrument that no comparator can supply."
  [[:donor-r1 :reagent] [:donor-r2 :reagent]
   [:donor-r1 :reagent-slim] [:donor-r2 :reagent-slim]
   [:donor-r1 :uix] [:donor-r2 :uix]
   [:donor-r2 :donor-r1]])

(defn head-to-head
  "Per-round arm-to-arm ratios, ranged across rounds.

  Each ratio is formed WITHIN a round from that round's own p50s, so drift
  between rounds cancels exactly as it does for the floor-normalised
  figures. Reported as a RANGE, with `:straddles-1?` set when the range
  includes 1.0 — in which case the two arms are INDISTINGUISHABLE on this
  witness and the mean must not be quoted as a winner."
  [per-round]
  (let [present (set (keys (:p50 (first per-round))))]
    (into {}
          (keep (fn [[a b]]
                  (when (and (contains? present a) (contains? present b))
                    (let [vs (mapv (fn [r] (round4 (/ (get-in r [:p50 a])
                                                      (get-in r [:p50 b]))))
                                   per-round)
                          lo (apply min vs)
                          hi (apply max vs)]
                      [(keyword (str (name a) "-over-" (name b)))
                       {:min lo :max hi :rounds vs
                        :straddles-1? (and (<= lo 1.0) (>= hi 1.0))}]))))
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

(defn- write-arm
  "The write door for `arm-id` on the U page.

  The floor's render happens INSIDE the flushSync for the reason b6
  records: `root.render` outside a React event schedules at the default
  lane, and an EMPTY flushSync flushes only the sync lane — a floor arm
  that rendered in `write!` would have its commit land outside the window
  entirely. Every other arm drains through [[spine-drain!]]."
  [arm-id adapter]
  (let [fid   (frame-of arm-id)
        state (atom nil)
        rt    (volatile! nil)
        arm   (u-arm arm-id)]
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
     :force! (fn []
               (if (= arm-id :floor)
                 (react-dom/flushSync
                   (fn [] (.render ^js @rt (w/floor-u {:cells @state :n w/cells-n}))))
                 (spine-drain! adapter)))
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

(defn release-write-arms! [mounts]
  (doseq [{:keys [arm handle container]} mounts]
    (try ((:unmount arm) handle) (catch :default _ nil))
    (.remove container)))

(defn timed-write!
  "Write, yield ONE microtask, force the arm's own synchronous drain, stop
  the clock — then VERIFY at the DOM that the probed cell holds what was
  written. Answers a promise of `{:ms … :ok? …}`.

  The yield is not historical and not optional: b6 records that with the
  microtask deleted every reactive arm fails this function's own read-back
  on every write. The read-back is inside the window's own iteration, so
  an arm that silently rendered nothing reports as UNVERIFIED rather than
  as the fastest substrate in the table — which is the exact shape of the
  fault this exists to prevent."
  [{:keys [arm container]} i val]
  (let [t0 (lane/now-ms)]
    ((:write! arm) i val)
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 ((:force! arm))
                 (let [ms    (- (lane/now-ms) t0)
                       probe (if (= i :all) 0 i)]
                   {:ms ms :ok? (= val (cell-text container probe))}))))))

(defn- chain
  "Sequence `f` over `xs` through promises, threading `acc`."
  [acc xs f]
  (reduce (fn [p x] (.then p (fn [a] (f a x)))) (js/Promise.resolve acc) xs))

(defn- write-round!
  "One write round. Every mounted arm writes at every sample index, order
  rotating and reflecting; `kind` is `:narrow` (one cell) or `:bulk` (all
  300 in one commit)."
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
                            v   (str "v" (:position a))]
                        (-> (timed-write! mnt (if (= kind :bulk) :all (mod (:position a) w/cells-n)) v)
                            (.then (fn [{:keys [ms ok?]}]
                                     (cond-> (-> a
                                                 (assoc :position (inc (:position a)) :prev id)
                                                 (update-in [:total id] inc)
                                                 (cond-> (not ok?) (update-in [:unverified id] inc)))
                                       (>= s warmup)
                                       (-> (update-in [:readings id] conj ms)
                                           (update :samples conj
                                                   {:arm         (name id)
                                                    :value       ms
                                                    :predecessor (some-> (:prev a) name)
                                                    :position    (:position a)})))))))))))))

(defn measure-write!
  "`rounds` interleaved rounds of the write clock, `kind` ∈ `#{:narrow
  :bulk}`. Answers a promise of the published record."
  [arm-ids adapter kind rounds sampling]
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
                 (let [bad (into #{} (comp (filter (fn [[_ n]] (pos? n))) (map key))
                                 (:unverified acc))
                       publishable (fn [m]
                                     (reduce (fn [acc' arm]
                                               (assoc acc' arm
                                                      {:unpublished :failed-dom-read-back
                                                       :unverified  (get (:unverified acc) arm)
                                                       :of          (get (:total acc) arm)}))
                                             m
                                             bad))]
                 {:witness    (keyword (str "U-" (name kind)))
                  :doc        (if (= kind :bulk)
                                "all 300 cells written in ONE commit — bulk view work"
                                "one cell written in a 300-cell grid — the narrow-write path")
                  :arms       (vec arm-ids)
                  :per-round  (:rounds-out acc)
                  ;; AN ARM WHOSE WRITES DID NOT REACH THE DOM HAS NO FIGURE.
                  ;; Its clock readings are real milliseconds and they are
                  ;; measuring a page that never changed — which is the
                  ;; cheapest possible way to be fast and the exact fault the
                  ;; read-back exists to catch. So the summary carries
                  ;; `:unpublished` in that arm's place rather than a number a
                  ;; reader could quote, and every head-to-head pair touching
                  ;; it is dropped.
                  :summary    (publishable (lane/across-rounds (mapv :ratio (:rounds-out acc))))
                  :head-to-head (into {}
                                      (remove (fn [[k _]]
                                                (some #(re-find (re-pattern (name %)) (name k))
                                                      bad)))
                                      (head-to-head (:rounds-out acc)))
                  :unverified (:unverified acc)
                  :writes     (:total acc)
                  :samples    (:samples acc)})))
        (.catch (fn [e] (release-write-arms! mounts) (throw e))))))

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
                         (catch :default _ nil))
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
   :measurement-method
   (str "arms interleaved at the SAMPLE level with the order rotating AND "
        "REFLECTING on the sample index (a cyclic rotation does not vary "
        "adjacency); every sample carries its predecessor and its position in "
        "the run; ratios taken against the floor measured in the SAME round; "
        "reported as a RANGE across rounds, and a range straddling 1.0 is "
        "INDISTINGUISHABLE rather than a winner; every write read back out of "
        "the DOM inside its own window")})
