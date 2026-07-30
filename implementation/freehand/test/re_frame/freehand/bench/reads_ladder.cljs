(ns re-frame.freehand.bench.reads-ladder
  "THE READS-PER-BOUNDARY HEAP LADDER — what a SUBSCRIBING boundary costs
  in standing bytes, on Reagent and on UIx, at 1 / 3 / 7 / 20 reads.

  Wave-0 instrument for EP-0038 (bead `rf2-2rtt6.5`, epic `rf2-2rtt6`).
  Reported into `rf2-2rtt6.1`'s P0 table and
  `docs/design/hicasso/studio/reads-per-boundary-heap-ladder.md`.

  ## The disagreement this exists to settle

  Two published figures are read as though they priced the same thing.

  * **251 B** — `docs/design/freehand/studio/freehand-vs-reagent-memory.md`,
    the `storm/uix` row: a UIx boundary above a component-free React floor.
    That witness reads **nothing**. No subscription, no store, no hook.
  * **516 B** — `rf2-oob3g`, an isolated `useSyncExternalStore` rung inside
    a decomposition of the 2,430 B Freehand boundary. That is the cost of
    **one hook**, not of a boundary.

  A UIx boundary that reads a re-frame2 subscription pays both: the
  boundary, once, and `use-subscribe` — which
  `re-frame.substrate.spine` implements over `useSyncExternalStore` plus
  two `useRef`s and a `useMemo` — once **per read**. So the two numbers
  are candidates for the INTERCEPT and the SLOPE of one line, and the only
  way to find out is to measure the line. The charter says so in as many
  words: *\"the 516-vs-251 tension is a reason to measure the real arms
  (1/3/7/20 reads, directly), not to reject hooks in advance\"*.

  Neither figure may be reached by inference from the other, and neither
  may be reached from a sub-free rung. Hence a ladder.

  ## Two curves, deliberately separated

  A single blended curve cannot tell a per-boundary cost from a per-read
  one — that conflation is what produced the disagreement. So:

  * **Curve B — fixed boundaries × growing reads.** `B = 1,200`
    boundaries, `R ∈ {1, 3, 7, 20}`. Regressed against `R`, the SLOPE is
    the per-read cost and the INTERCEPT is the per-boundary shell.
  * **Curve A — fixed reads × growing boundaries.** `R = 3`,
    `B ∈ {300, 600, 1,200, 2,400}`. Regressed against `B`, the SLOPE is
    the per-boundary cost at three reads and the INTERCEPT is whatever the
    page pays once. A non-zero intercept here would mean the per-boundary
    figure Curve B reports is partly a page constant, and the two curves
    would have to be re-read together.

  `R = 0` rides along as an **anchor, not as evidence**: it is the
  published `storm/uix` 251 B / `storm/reagent` 411 B shape rebuilt in
  this harness, so a reader can see whether this instrument lands where
  the predecessor's did before believing anything it says about reads.
  No reactive figure on this page is derived from it.

  ## Like-for-like, and why the two substrates need two pages

  Both arms read **re-frame2 subscriptions** — the same registrar, the
  same `[:lad/cell i]` query vectors, the same one frame behind every
  boundary. That is HD-012's like-for-like: the difference measured is
  Reagent's `deref`-capture against UIx's `useSyncExternalStore`, not one
  substrate's store against another's.

  `rf/init!` installs at most one adapter per JS context and silently
  keeps the first, so a page cannot host both. Each substrate therefore
  gets its own page load (`?adapter=reagent` / `?adapter=uix`) with its
  own floor arms, and every figure is `arm − floor` **within a page and
  within a round**. That is the same subtraction B7 uses, for the same
  reason: the box's drift cancels.

  ## What this namespace does NOT do

  It does not decide when a heap reading is taken. The page cannot force a
  collection, so it only mounts and holds; the driver
  (`reads_ladder_run.cjs`) owns the collector, both readers and the
  control. Nothing here counts allocations — V8's CDP *sampling* heap
  profiler drops the samples of collected objects (the same 80,000 objects
  read 4.77 MB when held and 0.00 MB when not), and that fault has already
  produced one wrong table on this surface.

  Every mount is verified at the DOM: [[mount!]] counts the boundary
  elements the arm should have produced and answers the count beside the
  expectation, because an arm that silently rendered nothing would
  otherwise read as the cheapest substrate in the table.

  ## Where this file lives, and why

  Beside B7, in the bench lane, because it RIDES B7's collector design and
  because `freehand/test` is the only `:advanced`-capable browser source
  path on the classpath until `rf2-2rtt6.2` mints the Hicasso lane. It
  requires nothing from `re-frame.freehand`. When the Hicasso lane exists
  this file moves there unchanged.

  Normative owner: bead `rf2-2rtt6.1`; programme
  `docs/design/hicasso/charter.md`."
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [reagent.dom.client :as rdc]
            [uix.core :refer [$ defui]]
            [uix.dom :as uix-dom]))

;; ---------------------------------------------------------------------------
;; The ladder's shape
;; ---------------------------------------------------------------------------

(def reads-rungs
  "The mandated ladder. `0` is the anchor to the published sub-free rows
  and is never a source of reactive inference; 1/3/7/20 are the bead's."
  [0 1 3 7 20])

(def curve-b-boundaries
  "Curve B holds boundaries fixed here and grows the reads. Large enough
  that the thinnest rung (R=1) still puts several hundred kilobytes above
  the floor, which is where this box's retention reading is legible."
  1200)

(def curve-a-reads
  "Curve A holds reads fixed here and grows the boundaries. Three, so the
  arm is unambiguously reactive without the 20-read rung's mount cost."
  3)

(def curve-a-boundaries
  "Doubling, so a straight line through the four points is a claim that
  can fail. 1,200 is shared with Curve B — the same arm, one reading,
  appearing on both curves and pinning them to each other."
  [300 600 1200 2400])

(def frame-id
  "ONE frame behind every boundary of every arm — the application shape,
  and the shape that prices a boundary rather than a store. K frames would
  price frame construction."
  :lad/frame)

(def ^:private cells-needed
  "Every boundary reads DISTINCT cells, so no two boundaries share a
  reaction and the per-read figure carries the reaction as well as the
  hook. This is the widest the page ever gets."
  (max (* curve-b-boundaries (apply max reads-rungs))
       (* (apply max curve-a-boundaries) curve-a-reads)))

;; ---------------------------------------------------------------------------
;; The store
;; ---------------------------------------------------------------------------

;; Every cell is ZERO, which is not laziness. A boundary renders the sum of
;; its reads, so with zeros every arm and every floor renders the identical
;; one-character text node "0" at every rung. Had the cells been `(range n)`
;; the arms would carry per-boundary strings the floor does not, and a
;; 24,000-boundary page would put a few kilobytes of string data on the
;; reactive side of a subtraction that is supposed to isolate the
;; subscription. The reads are still live: `use-subscribe` and
;; `rf/subscribe` are calls with registrar side effects, and their results
;; reach the DOM, so nothing here is elidable under `:advanced`.
(rf/reg-event :lad/seed
  (fn [_ctx [_ n]] {:db {:cells (vec (repeat n 0))}}))

(rf/reg-sub :lad/cell
  (fn [db [_ i]] (nth (:cells db) i)))

(defn ensure-frame!
  "Create the one shared frame, seeded wide enough for the widest arm.
  Idempotent — `rf/make-frame` on an existing id is."
  []
  (rf/make-frame {:id frame-id :initial-events [[:lad/seed cells-needed]]}))

;; ---------------------------------------------------------------------------
;; The floor — plain React, no component per boundary, no reactivity
;; ---------------------------------------------------------------------------

(defn- el [tag props children] (apply react/createElement tag props children))

(defn floor-grid
  "`n` boundary elements and the container, and NOTHING else: no component
  per cell, no store, no hook. `arm − floor` is therefore the substrate's
  own standing cost and not a figure dominated by React fibers and DOM
  handles both sides pay for.

  The floor deliberately has no component per boundary. That is the same
  choice `b6-floor/w2` makes and the reason the published 251 B / 411 B
  rows are boundary costs rather than wrapper deltas — matching it is what
  lets this ladder's R=0 anchor be compared with them at all."
  [n]
  (el "div" #js {:className "lad"}
      (into-array
        (map (fn [i] (el "span" #js {:key i :className "cell" :data-i i} "0"))
             (range n)))))

;; ---------------------------------------------------------------------------
;; The Reagent arm — R re-frame2 subscription derefs per boundary
;; ---------------------------------------------------------------------------

(defn- rg-cell
  "One Reagent boundary reading `r` DISTINCT subscriptions.

  A loop rather than an unrolled body, because Reagent has no hook-order
  rule: a form-1 component may deref a computed number of reactions and
  Reagent's `deref`-capture learns all of them. The UIx arm below is
  unrolled for exactly the opposite reason, and the asymmetry is in the
  substrates, not in the measurement."
  [base r]
  (let [v (loop [k 0 acc 0]
            (if (< k r)
              (recur (inc k)
                     (+ acc @(rf/subscribe [:lad/cell (+ base k)] {:frame frame-id})))
              acc))]
    [:span.cell {:data-i base} (str v)]))

(defn- rg-grid [n r]
  (into [:div.lad]
        (map (fn [i] ^{:key i} [rg-cell (* i r) r]))
        (range n)))

;; ---------------------------------------------------------------------------
;; The UIx arm — R `use-subscribe` hooks per boundary
;; ---------------------------------------------------------------------------

;; UNROLLED, and it has to be. `use-subscribe` is a hook over
;; `useSyncExternalStore`, so React's rule is that the call sequence is
;; identical on every render of an instance. A loop would satisfy that in
;; fact (`r` never changes for a mounted instance) but not by
;; construction, and UIx's linter reads the loop, not the fact. Writing
;; the calls out is also the honest thing for a page that is pricing hook
;; calls: the reader can count them.

(defn- us [i] (uix-adapter/use-subscribe frame-id [:lad/cell i]))

(defui ux-cell-0 [{:keys [base]}]
  ($ :span.cell {:data-i base} "0"))

(defui ux-cell-1 [{:keys [base]}]
  (let [v (us (+ base 0))]
    ($ :span.cell {:data-i base} (str v))))

(defui ux-cell-3 [{:keys [base]}]
  (let [v (+ (us (+ base 0)) (us (+ base 1)) (us (+ base 2)))]
    ($ :span.cell {:data-i base} (str v))))

(defui ux-cell-7 [{:keys [base]}]
  (let [v (+ (us (+ base 0)) (us (+ base 1)) (us (+ base 2)) (us (+ base 3))
             (us (+ base 4)) (us (+ base 5)) (us (+ base 6)))]
    ($ :span.cell {:data-i base} (str v))))

(defui ux-cell-20 [{:keys [base]}]
  (let [v (+ (us (+ base 0))  (us (+ base 1))  (us (+ base 2))  (us (+ base 3))
             (us (+ base 4))  (us (+ base 5))  (us (+ base 6))  (us (+ base 7))
             (us (+ base 8))  (us (+ base 9))  (us (+ base 10)) (us (+ base 11))
             (us (+ base 12)) (us (+ base 13)) (us (+ base 14)) (us (+ base 15))
             (us (+ base 16)) (us (+ base 17)) (us (+ base 18)) (us (+ base 19)))]
    ($ :span.cell {:data-i base} (str v))))

(def ^:private ux-cell-by-reads
  {0 ux-cell-0, 1 ux-cell-1, 3 ux-cell-3, 7 ux-cell-7, 20 ux-cell-20})

(defui ux-grid [{:keys [n r]}]
  ($ :div.lad
     (let [c (get ux-cell-by-reads r)]
       (for [i (range n)]
         ($ c {:key i :base (* i r)})))))

;; The Reagent R=0 rung takes the same shape as UIx's: a component per
;; boundary that reads nothing. `rg-cell` with `r = 0` already is one — the
;; loop runs zero times — so no separate arm is needed and none is defined.

;; ---------------------------------------------------------------------------
;; Arms
;; ---------------------------------------------------------------------------

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- floor-arm [n]
  {:boundaries  n
   :selector    ".cell"
   :mount-one   (fn [c]
                  (let [rt (react-dom-client/createRoot c)]
                    (react-dom/flushSync (fn [] (.render rt (floor-grid n))))
                    rt))
   :unmount-one (fn [rt] (react-dom/flushSync (fn [] (.unmount rt))))})

(defn- reagent-arm [n r]
  {:boundaries  n
   :selector    ".cell"
   :mount-one   (fn [c]
                  (let [rt (rdc/create-root c)]
                    (react-dom/flushSync (fn [] (rdc/render rt [rg-grid n r])))
                    rt))
   :unmount-one (fn [rt] (react-dom/flushSync (fn [] (rdc/unmount rt))))})

(defn- uix-arm [n r]
  {:boundaries  n
   :selector    ".cell"
   :mount-one   (fn [c]
                  (let [rt (uix-dom/create-root c)]
                    (react-dom/flushSync
                      (fn [] (uix-dom/render-root ($ ux-grid {:n n :r r}) rt)))
                    rt))
   :unmount-one (fn [rt] (react-dom/flushSync (fn [] (uix-dom/unmount-root rt))))})

(defn- arm-id [kind curve r n]
  (keyword (str (name kind) "/" (name curve) "-r" r "-b" n)))

(defn arms
  "The arm table for one substrate. Floors are substrate-neutral (plain
  React) but are built per page anyway, so `arm − floor` never crosses a
  page.

  Curve A's `R = 3, B = 1,200` rung IS Curve B's `R = 3` rung — one arm,
  one reading, on both curves. Duplicating it would have given the two
  curves two different numbers for the same point and no way to say which
  was the curve and which was the noise."
  [substrate]
  (let [make (case substrate :reagent reagent-arm :uix uix-arm)
        b-ns (into (sorted-set) (concat curve-a-boundaries [curve-b-boundaries]))]
    (into {}
          (concat
            ;; floors, one per distinct boundary count on either curve
            (for [n b-ns] [(arm-id :floor :b 0 n) (floor-arm n)])
            ;; Curve B — fixed boundaries, growing reads
            (for [r reads-rungs]
              [(arm-id substrate :b r curve-b-boundaries)
               (make curve-b-boundaries r)])
            ;; Curve A — fixed reads, growing boundaries (1,200 shared)
            (for [n curve-a-boundaries
                  :when (not= n curve-b-boundaries)]
              [(arm-id substrate :a curve-a-reads n)
               (make n curve-a-reads)])))))

;; ---------------------------------------------------------------------------
;; Holding, and letting go
;; ---------------------------------------------------------------------------

(defonce ^:private installed (atom nil))
(defonce ^:private held (atom nil))

(defn- release!* []
  (when-some [{:keys [arm handle container]} @held]
    (let [{:keys [unmount-one]} (get @installed arm)]
      (try (unmount-one handle) (catch :default _ nil)))
    (.remove container)
    (reset! held nil))
  nil)

(defn mount!
  "Mount `arm-id` and KEEP it. Answers `{:elements :expected :ok
  :boundaries}` — the DOM read-back over the boundary class the arm should
  have produced, and the divisor the driver needs, since arms on Curve A
  deliberately do not share one.

  A literal `#js` object rather than `clj->js`: `clj->js` renders `:ok?` as
  the key `\"ok?\"` and a driver reading `verify.ok` sees `undefined`, so
  every mount reports unverified while every mount is in fact fine. That
  happened once already, on B7's first run."
  [arm-id]
  (release!*)
  (let [{:keys [mount-one selector boundaries]} (get @installed arm-id)
        container (container!)
        handle    (mount-one container)
        elements  (.-length (.querySelectorAll js/document selector))]
    (reset! held {:arm arm-id :handle handle :container container})
    #js {:elements   elements
         :expected   boundaries
         :boundaries boundaries
         :ok         (= elements boundaries)}))

;; ---------------------------------------------------------------------------
;; The positive control
;; ---------------------------------------------------------------------------

(defonce ^:private control-cell (atom nil))

(defn control!
  "Hold a dense JS array of exactly `n` doubles and answer its length, so
  the allocation cannot be proved dead and elided.

  V8 stores a packed double array as `n` unboxed 8-byte slots behind a
  small header, so the retained cost is **8n bytes, PREDICTED** — a figure
  the instrument has to hit, not one it gets to report. It rides every
  round, in situ, on the same readers as the arms.

  B7 records what happens when a control is a fiction: its first draft was
  a flat one-byte string of the same nominal size and read as SIX
  KILOBYTES on all three readers, because V8 does not materialise
  `'x'.repeat(n)`. The instrument was fine and the control was wrong, and
  only running it found that out."
  [n]
  (let [a (js/Array. n)]
    (dotimes [i n] (aset a i (+ i 0.5)))
    (reset! control-cell a)
    (.-length a)))

(defn control-release! [] (reset! control-cell nil) 0)

;; ---------------------------------------------------------------------------
;; The page-side door
;; ---------------------------------------------------------------------------

(defn install!
  "Publish the instrument on `window.LADDER` for the CDP driver, which owns
  the collector and both heap readers. The page mounts; it never decides
  when a reading is taken."
  [substrate]
  (reset! installed (arms substrate))
  (set! (.-LADDER js/window)
        #js {:mount          (fn [arm] (mount! (keyword arm)))
             :release        (fn [] (release!*) true)
             :control        (fn [n] (control! n))
             :controlRelease (fn [] (control-release!))
             :perfMem        (fn []
                               (if-some [m (.-memory js/performance)]
                                 (.-usedJSHeapSize m)
                                 -1))
             :substrate      (name substrate)
             :plan           (clj->js
                               {:curveB {:boundaries curve-b-boundaries
                                         :reads      reads-rungs}
                                :curveA {:reads      curve-a-reads
                                         :boundaries curve-a-boundaries}
                                :cells  cells-needed})
             ;; `(subs (str kw) 1)`, NOT `name` — `name` drops the namespace,
             ;; so every arm reached the driver as `b-r0-b2400` with the
             ;; `floor`/`reagent`/`uix` half missing and nothing to parse.
             :arms           (into-array (map #(subs (str %) 1) (keys @installed)))})
  nil)
