(ns re-frame.freehand.bench.hd8-rows
  "HD-008's rows — the arms wired to their mount doors, the parity gate,
  the two clocks, and the per-sample records the arm-order guard
  adjudicates (rf2-2rtt6.7).

  ## The instrument, and the fifteen faults it is built against

  The predecessor programme caught fifteen instrument faults, and every
  one of them produced a plausible precise WRONG NUMBER before it was
  caught. The method here is the recorded remedy for each, and none of it
  is optional:

    - **Both orders, always.** Arms are interleaved at the SAMPLE index
      with an order that rotates AND REFLECTS ([[re-frame.freehand.bench.b6-harness/slot-order]]).
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
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.hd8-witnesses :as w]
            [re-frame.freehand.bench.measure :as m]
            [reagent.core :as reagent]
            [reagent.dom.client :as rdc]
            [reagent2.dom.client :as slim-rdc]
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
;; [[re-frame.freehand.bench.b6-harness/mount-arm!]] drives, so the timed
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
  "Which arms are measurable under which installed adapter. The frontier
  arm rides every run: it reads through `use-current-frame` +
  two-arity `use-subscribe`, neither of which consults the installed
  adapter's frame hook, so it is the one arm whose figure can be compared
  ACROSS runs as well as within one."
  {:uix     [:floor :uix :donor-r1 :donor-r2]
   :reagent [:floor :reagent :uix :donor-r1 :donor-r2]
   :slim    [:floor :reagent-slim :uix :donor-r1 :donor-r2]})

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
  (h/parity (arms-for witness arm-ids) props))

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
              (finally (doseq [mnt mounts] (h/release! mnt))))))
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
        (doseq [mnt (:mounts a)] (h/release! mnt))
        (doseq [mnt (:mounts b)] (h/release! mnt))))))

;; ===========================================================================
;; The mount clock — with the order-guard's per-sample records
;; ===========================================================================

(defn- round4 [x] (/ (js/Math.round (* (double x) 10000.0)) 10000.0))
(defn- p50 [xs] (:p50 (m/summarise xs)))

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
      (doseq [j (h/slot-order k s)]
        (let [arm (nth arms j)
              mnt (h/mount-arm! arm props)
              p   @pos]
          (when (>= s warmup)
            (swap! acc update (:id arm) conj (:ms mnt))
            (swap! recs conj {:arm         (name (:id arm))
                              :value       (:ms mnt)
                              :predecessor (some-> @prev name)
                              :position    p}))
          (reset! prev (:id arm))
          (swap! pos inc)
          (h/release! mnt))))
    {:readings @acc :samples @recs :position @pos}))

(defn normalise
  "One round's raw readings as `{:p50 {id ms} :ratio {id r}}`, every ratio
  against the floor measured in THIS round."
  [readings]
  (let [p50s  (into {} (map (fn [[id xs]] [id (p50 xs)])) readings)
        floor (get p50s :floor)]
    {:p50   p50s
     :ratio (into {} (map (fn [[id v]] [id (round4 (/ v floor))])) p50s)}))

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
    {:witness  (:id witness)
     :doc      (:doc witness)
     :arms     (vec arm-ids)
     :per-round norm
     :summary  (h/across-rounds (mapv :ratio norm))
     :samples  (:samples acc)}))

;; ===========================================================================
;; The write clock — narrow and bulk, every write read back out of the DOM
;; ===========================================================================

(defn- write-arm
  "The write door for `arm-id` on the U page.

  `:force!` is each substrate's OWN synchronous drain, wrapped in
  `flushSync` so the commit lands inside the measured window rather than
  on React's scheduler a couple of milliseconds later. The floor's render
  happens INSIDE the flushSync for the reason b6 records: `root.render`
  outside a React event schedules at the default lane, and an EMPTY
  flushSync flushes only the sync lane — a floor arm that rendered in
  `write!` would have its commit land outside the window entirely."
  [arm-id]
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
                 (if (= i :all)
                   (reset! state (vec (repeat w/cells-n val)))
                   (swap! state assoc i val))
                 (if (= i :all)
                   (frame/replace-app-db!
                     fid (assoc (frame/frame-app-db-value fid)
                                :cells (vec (repeat w/cells-n val))))
                   (frame/replace-app-db!
                     fid (update (frame/frame-app-db-value fid) :cells assoc i val)))))
     :force! (fn []
               (case arm-id
                 :floor        (react-dom/flushSync
                                 (fn [] (.render ^js @rt (w/floor-u {:cells @state
                                                                     :n w/cells-n}))))
                 :reagent      (react-dom/flushSync (fn [] (reagent/flush)))
                 :reagent-slim (react-dom/flushSync (fn [] (slim-rdc/flush-render!)))
                 (react-dom/flushSync (fn [] nil))))
     :unmount (fn [handle]
                (react-dom/flushSync
                  (fn [] (if (= arm-id :floor)
                           (.unmount ^js handle)
                           ((:unmount arm) handle)))))}))

(defn cell-text [container i]
  (some-> (.querySelector container (str "[data-i=\"" i "\"]")) (.-textContent)))

(defn mount-write-arms! [arm-ids]
  (mapv (fn [id]
          (let [a (write-arm id)
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
  (let [t0 (m/now-ms)]
    ((:write! arm) i val)
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 ((:force! arm))
                 (let [ms    (- (m/now-ms) t0)
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
  (let [k (count mounts)]
    (chain {:readings (zipmap (map #(:id (:arm %)) mounts) (repeat []))
            :samples  []
            :unverified 0
            :total    0
            :position position0
            :prev     nil}
           (range (+ warmup samples))
           (fn [acc s]
             (chain acc (h/slot-order k s)
                    (fn [a j]
                      (let [mnt (nth mounts j)
                            id  (:id (:arm mnt))
                            v   (str "v" (:position a))]
                        (-> (timed-write! mnt (if (= kind :bulk) :all (mod (:position a) w/cells-n)) v)
                            (.then (fn [{:keys [ms ok?]}]
                                     (cond-> (assoc a
                                                    :position (inc (:position a))
                                                    :prev id
                                                    :total (inc (:total a))
                                                    :unverified (cond-> (:unverified a)
                                                                  (not ok?) inc))
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
  [arm-ids kind rounds sampling]
  (doseq [id arm-ids] (reseed! id))
  (let [mounts (mount-write-arms! arm-ids)]
    (-> (chain {:rounds-out [] :samples [] :unverified 0 :total 0 :position 0}
               (range rounds)
               (fn [acc _]
                 (-> (write-round! mounts kind sampling (:position acc))
                     (.then (fn [r]
                              {:rounds-out (conj (:rounds-out acc) (normalise (:readings r)))
                               :samples    (into (:samples acc) (:samples r))
                               :unverified (+ (:unverified acc) (:unverified r))
                               :total      (+ (:total acc) (:total r))
                               :position   (:position r)})))))
        (.then (fn [acc]
                 (release-write-arms! mounts)
                 {:witness    (keyword (str "U-" (name kind)))
                  :doc        (if (= kind :bulk)
                                "all 300 cells written in ONE commit — bulk view work"
                                "one cell written in a 300-cell grid — the narrow-write path")
                  :arms       (vec arm-ids)
                  :per-round  (:rounds-out acc)
                  :summary    (h/across-rounds (mapv :ratio (:rounds-out acc)))
                  :unverified (:unverified acc)
                  :writes     (:total acc)
                  :samples    (:samples acc)}))
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
  (let [big   (m-arm :floor)
        small (m-arm :floor)
        n     w/rows-n
        half  (quot n 2)
        predicted (/ (double (w/m-elements n)) (double (w/m-elements half)))
        per-round (mapv (fn [_]
                          (let [b (mount-round! [big] {:n n} sampling 0)
                                s (mount-round! [small] {:n half} sampling 0)]
                            (round4 (/ (p50 (get (:readings b) :floor))
                                       (p50 (get (:readings s) :floor))))))
                        (range rounds))
        lo (apply min per-round)
        hi (apply max per-round)]
    {:control    :floor-M-page-halved
     :predicted  (round4 predicted)
     :measured   {:min lo :max hi :rounds per-round}
     :tolerance  0.30
     :within?    (and (>= hi (* predicted 0.70)) (<= lo (* predicted 1.30)))
     :note       (str "predicted from the witness arithmetic (3 + 3N) / (3 + 3(N/2)) "
                      "at N=" n ", before any clock was read")}))

;; ===========================================================================
;; The lowering correctness check — rung 2 must LOWER, not merely construct
;; ===========================================================================

(defn lowering-works?
  "Fire ONE click through rung 2's codec-lowered handler, outside every
  measured window, and read the result back out of the DOM.

  Without this the rung-2 clock could be pricing a lowering that produces
  a closure nobody can call — the fastest possible implementation of the
  wrong thing. `:hd8/touch` writes `\"T\"` into the clicked cell, so a
  working lowering is visible at the DOM and a broken one is not."
  []
  (reseed! :donor-r2)
  (let [fid       (frame-of :donor-r2)
        container (js/document.createElement "div")
        _         (.appendChild js/document.body container)
        root      (react-dom-client/createRoot container)]
    (try
      (react-dom/flushSync
        (fn [] (.render root (provided fid (w/slim-element (w/r2-u 8))))))
      (let [before (cell-text container 3)
            node   (.querySelector container "[data-i=\"3\"]")]
        (react-dom/flushSync (fn [] (.click node)))
        (react-dom/flushSync (fn [] nil))
        (let [after (cell-text container 3)]
          {:before before :after after :lowered? (and (not= before after) (= "T" after))}))
      (finally
        (try (react-dom/flushSync (fn [] (.unmount root))) (catch :default _ nil))
        (.remove container)
        (reseed! :donor-r2)))))

;; ===========================================================================
;; Provenance
;; ===========================================================================

(defn method-record
  "The method, published beside every figure so a reader never has to
  reconstruct it from prose."
  [adapter arm-ids rounds mount-sampling write-sampling]
  {:bead              "rf2-2rtt6.7"
   :decision          "HD-008"
   :adapter           adapter
   :arms              (vec arm-ids)
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
