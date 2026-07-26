(ns re-frame.freehand.bench.b7-heap
  "B7 — RETAINED HEAP per live boundary, ACROSS substrates.

  The one axis the cross-substrate comparison never measured. B6 prices
  mount and update on the wall clock and says so plainly in its §3: *no
  memory claim of any kind*. `compiled-tier-browser-worth-it.md` §1a
  priced retained heap per boundary, but only *within* Freehand — one
  substrate against itself. So the question `docs/design/freehand/studio/`
  `freehand-vs-reagent.md` names as \"the obvious next measurement\" —
  what does a boundary cost in standing bytes on Freehand versus on
  Reagent — has no answer anywhere. This namespace is that answer's
  instrument.

  ## Why this is not the allocation counter, and why that matters

  V8's CDP SAMPLING heap profiler **drops the samples of collected
  objects**. Pointed at a mount/unmount loop it reports the residue of a
  page that has already been discarded, not the cost of the page: the
  same 80,000 objects read **4.77 MB when a global held them and 0.00 MB
  when nothing did**. That fault has already produced one wrong table on
  this surface, and the intuition that rationalised it — \"allocation is
  a counter, a collection inside the window cannot make it smaller\" — is
  simply false of a sampler.

  So nothing here counts allocations. The instrument is RETENTION, which
  is the right question anyway: a ViewCell is a cell object, a hook
  record, a subscription registration and two layout effects that live as
  long as the mount, and what an application pays for a boundary is what
  the boundary keeps standing, not what it touched on the way up.

  ## The shape of a reading

  **Mount K roots and KEEP them.** The driver
  (`b7_run.cjs`) forces a full garbage collection through CDP, reads the
  heap, and only then asks this namespace to mount. Nothing is timed;
  nothing is unmounted inside a window. Then the roots are released, the
  heap is collected and read again — and that second read is a control in
  its own right, because a substrate whose released heap does not return
  to baseline is retaining something after unmount, which is a different
  and worse finding than a large per-boundary figure.

  **Every mount is verified at the DOM.** `mount!` counts the boundary
  elements the arm should have produced and answers the count beside the
  expectation. An arm that silently rendered nothing would otherwise read
  as the cheapest substrate in the table, which is exactly the shape of
  the fault this instrument exists to avoid.

  **A positive control of known size rides every round.** [[control!]]
  allocates a flat one-byte string of exactly N characters and holds it on
  a global. A `SeqOneByteString` of length N occupies N bytes of character
  data plus a small fixed header, so its retained size is PREDICTED, not
  merely observed, and the round reports predicted against measured. If
  the collector or the reader were not doing what this namespace claims,
  the control would miss, and \"this arm is cheap\" and \"the instrument
  is not running\" would stop being the same number.

  ## The two witness families

  **`storm`** — 300 sub-free leaf boundaries per root, 301 elements. The
  same W2 shape B6 mounts, in all four dialects. It prices a boundary that
  does nothing: the wrapper, and only the wrapper.

  **`reactive`** — 300 cells per root, each reading its own fine-grained
  signal: Freehand's `v/sub` against Reagent's `r/cursor`, the same pairing
  B6's update rows drive. It prices a boundary an application would
  actually write. The floor arm renders the identical 301-element DOM with
  no reactivity at all, so `arm − floor` is the substrate's own standing
  cost and not a number dominated by React fibers both arms pay for.

  All K roots of an arm share ONE state cell — one `reagent.core/atom` for
  Reagent, one frame for Freehand — because that is how an application is
  shaped and because K independent stores would price the store rather
  than the boundary.

  ## What a JS-heap reading cannot see

  DOM nodes live in Blink's C++ heap, not V8's, so none of these figures
  contain the elements themselves. Every arm builds the identical DOM —
  the canonical-DOM parity gate in B6 is what establishes that — so the
  omission cancels exactly in `arm − floor`, which is the figure the
  report quotes. The absolute per-boundary column is a JS-heap figure and
  is labelled as one.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [reagent.dom.client :as rdc]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.b6-floor :as floor]
            [re-frame.freehand.bench.b6-reagent :as rg]
            [re-frame.freehand.bench.b6-rows :as rows]
            [re-frame.freehand.bench.b6-uix :as ux]
            [re-frame.freehand.bench.b6-witnesses :as fh]
            [uix.core :refer [$]]
            [uix.dom :as uix-dom]))

(def per-root
  "Boundaries in one root. The same 300 B6 mounts, so a per-boundary
  figure here and a mount ratio there describe the same page."
  rows/cells-n)

(def shared-frame-id
  "ONE frame behind every root of the reactive Freehand arm — the
  counterpart of the single `reagent.core/atom` behind every root of the
  reactive Reagent arm. K separate frames would price frame construction,
  which is not what a per-boundary figure is for."
  :b7/grid)

;; ---------------------------------------------------------------------------
;; Arms
;; ---------------------------------------------------------------------------

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- react-root-arm
  "The floor, and the UIx arm — neither has a mount door of its own."
  [element-of]
  {:mount-one   (fn [c _i]
                  (let [r (react-dom-client/createRoot c)]
                    (react-dom/flushSync (fn [] (.render r (element-of))))
                    r))
   :unmount-one (fn [r] (react-dom/flushSync (fn [] (.unmount r))))})

(def arms
  "id → `{:selector … :mount-one … :unmount-one …}`. `:selector` is the
  DOM read-back: the class every boundary of that witness puts on the
  page, counted after the mount against `K × per-root`."
  {;; --- storm: a boundary that reads nothing -------------------------------
   :storm/floor
   (assoc (react-root-arm #(floor/w2 per-root)) :selector ".leaf")

   :storm/freehand
   {:selector    ".leaf"
    :mount-one   (fn [c i]
                   (react-dom/flushSync
                     (fn [] (v/mount [fh/w2 {:n per-root}] c
                                     {:disambiguator (keyword "b7h" (str "storm-" i))}))))
    :unmount-one (fn [m] (react-dom/flushSync (fn [] (v/unmount! m))))}

   :storm/reagent
   {:selector    ".leaf"
    :mount-one   (fn [c _i]
                   (let [rt (rdc/create-root c)]
                     (react-dom/flushSync (fn [] (rdc/render rt [rg/w2 per-root])))
                     rt))
    :unmount-one (fn [rt] (react-dom/flushSync (fn [] (rdc/unmount rt))))}

   :storm/uix
   {:selector    ".leaf"
    :mount-one   (fn [c _i]
                   (let [rt (uix-dom/create-root c)]
                     (react-dom/flushSync
                       (fn [] (uix-dom/render-root ($ ux/w2 {:n per-root}) rt)))
                     rt))
    :unmount-one (fn [rt] (react-dom/flushSync (fn [] (uix-dom/unmount-root rt))))}

   ;; --- reactive: a boundary an application would write --------------------
   :reactive/floor
   (assoc (react-root-arm #(floor/u-grid (vec (repeat per-root 0)))) :selector ".cell")

   :reactive/freehand
   {:selector    ".cell"
    ;; Root 0 ENSUREs the frame and owns it; every later root SCOPEs the
    ;; same one. A config-bearing ensure from a second root-id is refused
    ;; by design (Spec 004C §7, one frame one plan) and would be the wrong
    ;; shape anyway — K roots over one store is the application shape.
    :mount-one   (fn [c i]
                   (react-dom/flushSync
                     (fn []
                       (v/mount [fh/u-grid {:n per-root}] c
                                {:disambiguator (keyword "b7h" (str "grid-" i))
                                 :frame         (if (zero? i)
                                                  {:id             shared-frame-id
                                                   :initial-events [[:b6/seed per-root]]}
                                                  shared-frame-id)}))))
    :unmount-one (fn [m] (react-dom/flushSync (fn [] (v/unmount! m))))}

   :reactive/reagent
   {:selector    ".cell"
    :mount-one   (fn [c _i]
                   (let [rt (rdc/create-root c)]
                     (react-dom/flushSync (fn [] (rdc/render rt [rg/u-grid per-root])))
                     rt))
    :unmount-one (fn [rt] (react-dom/flushSync (fn [] (rdc/unmount rt))))}})

;; ---------------------------------------------------------------------------
;; Holding, and letting go
;; ---------------------------------------------------------------------------

(defonce ^:private held (atom nil))

(defn- release!*
  "Unmount in REVERSE mount order and detach every container. Reverse,
  because the reactive Freehand arm's root 0 owns the frame the others
  scope: tearing the owner down first would unmount live scopers from
  underneath."
  []
  (when-some [{:keys [arm handles containers]} @held]
    (let [{:keys [unmount-one]} (get arms arm)]
      (doseq [h (reverse handles)]
        (try (unmount-one h) (catch :default _ nil))))
    (doseq [c containers] (.remove c))
    (reset! held nil))
  nil)

(defn mount!
  "Mount `k` roots of `arm-id` and KEEP them. Answers
  `{:elements n :expected n :ok? bool}` — the DOM read-back, taken after
  the mount, over the boundary class the arm should have produced."
  [arm-id k]
  (release!*)
  (when (= :reactive/reagent arm-id)
    (reset! rg/cells (vec (repeat per-root 0))))
  (let [{:keys [mount-one selector]} (get arms arm-id)
        containers (mapv (fn [_] (container!)) (range k))
        handles    (into [] (map-indexed (fn [i c] (mount-one c i))) containers)
        elements   (.-length (.querySelectorAll js/document selector))
        expected   (* k per-root)]
    (reset! held {:arm arm-id :handles handles :containers containers})
    ;; A literal `#js` object rather than `clj->js`, because `clj->js`
    ;; renders `:ok?` as the key `"ok?"` and the driver reading `verify.ok`
    ;; would see `undefined` — every mount would report unverified while
    ;; every mount was in fact fine. It did, on the first run.
    #js {:elements elements :expected expected :ok (= elements expected)}))

;; ---------------------------------------------------------------------------
;; The positive control
;; ---------------------------------------------------------------------------

(defonce ^:private control-cell (atom nil))

(defn control!
  "Hold a dense JS array of exactly `n` doubles, and answer its length so
  the allocation cannot be proved dead and elided.

  V8 stores a packed double array as `n` unboxed 8-byte slots behind a
  small header, so the retained cost is **8n bytes**, PREDICTED rather
  than merely observed. That is the entire point of a control: a figure
  the instrument has to hit, not one it gets to report.

  The first draft of this control was a flat one-byte string of `8n`
  characters, on the same reasoning. **It was wrong, and only running it
  found that out**: a 4.7 MB `'x'.repeat(…)` reads as 6 KB on all three
  readers, so V8 is not materialising the characters. Had it shipped, the
  control would have missed by three orders of magnitude every round and
  the natural conclusion would have been that the instrument was broken —
  when the instrument was fine and the control was a fiction. It is
  recorded here because it is the same class of mistake as the sampling
  profiler this whole namespace exists to avoid, caught one step earlier."
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
  "Publish the instrument on `window.B7H` for the CDP driver, which owns
  the collector and both heap readers. The page mounts; it never decides
  when a reading is taken."
  []
  (set! (.-B7H js/window)
        #js {:mount          (fn [arm k] (mount! (keyword arm) k))
             :release        (fn [] (release!*) true)
             :control        (fn [n] (control! n))
             :controlRelease (fn [] (control-release!))
             :perfMem        (fn []
                               (if-some [m (.-memory js/performance)]
                                 (.-usedJSHeapSize m)
                                 -1))
             :perRoot        per-root})
  nil)
