(ns re-frame.bench.p0-heap
  "EP-0038 P0 — RETAINED HEAP per boundary, the second red-zone axis.

  The delegated ruling on rf2-2rtt6.1 sets a UIx threshold on the clock
  AND on retained heap, per witness family, so this axis is not a
  supporting number either.

  ## Retention, not allocation, and why that is not a preference

  V8's CDP SAMPLING heap profiler **drops the samples of collected
  objects**. Pointed at a mount/unmount loop it reports the residue of a
  page that has already been discarded, not the cost of the page: on the
  predecessor's instrument the same 80,000 objects read 4.77 MB when a
  global held them and 0.00 MB when nothing did. Nothing here counts
  allocations.

  Retention is also the right question. What an application pays for a
  boundary is what the boundary keeps STANDING — the hook records, the
  subscription registration, the fiber — not what it touched on the way
  up. The budget in validation.md is stated the same way: exclusive
  retained per boundary, ~0.4-0.5 KB target, > 1 KB fails on paper.

  ## The shape of a reading

  Mount K roots and KEEP them. The driver forces a full collection, reads
  the heap, and only then asks this namespace to mount. Nothing is timed
  and nothing is unmounted inside a window. Then the roots are released,
  the heap is collected and read again — and that second read is a control
  in its own right, because a substrate whose released heap does not
  return to baseline is retaining something after unmount, which is a
  different and worse finding than a large per-boundary figure.

  Every mount is verified at the DOM: `mount!` counts the boundary
  elements the arm should have produced and answers the count beside the
  expectation. An arm that silently rendered nothing would otherwise read
  as the leanest substrate in the table.

  ## Segments here too

  One adapter per process, so the Reagent-on-subs and UIx-on-subs arms
  cannot both be live at once. `prepare!` swaps the adapter and stands the
  frame back up, and the DRIVER calls it BEFORE the baseline read of the
  pair it is about to take — so the swap's own residue lands in the
  baseline rather than in the arm. The floor is measured in both segments
  and is the shared calibrator, exactly as on the clock.

  ## What a JS-heap reading cannot see

  DOM nodes live in Blink's C++ heap, not V8's, so none of these figures
  contain the elements themselves. Every arm builds the identical DOM —
  the clock run's canonical-DOM parity gate is what establishes that — so
  the omission cancels in `arm - floor`, which is the exclusive figure the
  red-zone is set from. The absolute column is a JS-heap figure and is
  labelled as one.

  Owner: rf2-2rtt6.1 (standard); this arm rf2-2rtt6.4."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.order-guard :as guard]
            [re-frame.bench.p0-arms :as arms]
            [re-frame.bench.p0-fixture :as fx]
            [re-frame.bench.p0-floor :as floor]
            [re-frame.bench.p0-harness :as h]
            [re-frame.bench.p0-reagent :as rg]
            [re-frame.bench.p0-uix :as ux]
            [reagent.dom.client :as rdc]
            [uix.dom :as uix-dom]))

(def per-root
  "Boundaries in one root of the `grid` family — the same 300 the clock
  rows drive, so a per-boundary figure here and a bulk ratio there
  describe the same page."
  fx/cells-n)

(def rows-per-root fx/w1-rows)

;; ---------------------------------------------------------------------------
;; Arms — keyed `family/substrate`, the same naming the driver schedules on
;; ---------------------------------------------------------------------------

(defn- react-root-arm [element-of selector expected]
  {:selector    selector
   :expected    expected
   :mount-one   (fn [c _i]
                  (let [r (react-dom-client/createRoot c)]
                    (react-dom/flushSync (fn [] (.render r (element-of))))
                    r))
   :unmount-one (fn [r] (.unmount r))})

(defn- reagent-root-arm [form-of selector expected]
  {:selector    selector
   :expected    expected
   :mount-one   (fn [c _i]
                  (let [rt (rdc/create-root c)]
                    (react-dom/flushSync (fn [] (rdc/render rt (form-of))))
                    rt))
   :unmount-one (fn [rt] (rdc/unmount rt))})

(defn- uix-root-arm [element-of selector expected]
  {:selector    selector
   :expected    expected
   :mount-one   (fn [c _i]
                  (let [rt (uix-dom/create-root c)]
                    (react-dom/flushSync (fn [] (uix-dom/render-root (element-of) rt)))
                    rt))
   :unmount-one (fn [rt] (uix-dom/unmount-root rt))})

(def arm-table
  "`arm-id -> {:segment :selector :expected :mount-one :unmount-one}`.

  `:segment` is which adapter the arm needs; the driver calls `prepare!`
  with it before taking the baseline of the pair. A floor arm names the
  segment it is being measured IN, because the floor is the calibrator and
  has to be read on both sides of the seam."
  {:list/floor
   (assoc (react-root-arm #(floor/w1 (mapv fx/row-value (range rows-per-root)))
                          ".row" rows-per-root)
          :segment nil)

   :list/reagent
   (assoc (reagent-root-arm #(rg/w1-root arms/frame-id rows-per-root)
                            ".row" rows-per-root)
          :segment :reagent-subs)

   :list/uix
   (assoc (uix-root-arm #(ux/w1-root arms/frame-id rows-per-root)
                        ".row" rows-per-root)
          :segment :uix-subs)

   :grid/floor
   (assoc (react-root-arm #(floor/u-grid (vec (repeat per-root 0)))
                          ".cell" per-root)
          :segment nil)

   :grid/reagent
   (assoc (reagent-root-arm #(rg/u-root arms/frame-id) ".cell" per-root)
          :segment :reagent-subs)

   :grid/uix
   (assoc (uix-root-arm #(ux/u-root arms/frame-id) ".cell" per-root)
          :segment :uix-subs)})

;; ---------------------------------------------------------------------------
;; Segment preparation — always OUTSIDE the baseline read
;; ---------------------------------------------------------------------------

(defn prepare!
  "Install the adapter `segment-id` names and stand the frame back up.

  Called by the driver BEFORE its baseline read, so the adapter swap's own
  residue is in the baseline and not in the arm's retained delta. Passing
  a segment that is already installed still re-seeds, so the two branches
  cost the same."
  [segment-id]
  (let [segment (first (filter #(= segment-id (:id %)) arms/segments))]
    (when segment (arms/enter-segment! segment)))
  true)

;; ---------------------------------------------------------------------------
;; Holding, and letting go
;; ---------------------------------------------------------------------------

(defonce ^:private held (atom nil))

(defn- release!*
  []
  (when-some [{:keys [arm handles containers]} @held]
    (let [{:keys [unmount-one]} (get arm-table arm)]
      (doseq [hd (reverse handles)]
        (unmount-one hd)))
    (doseq [c containers] (.remove c))
    (reset! held nil))
  nil)

(defn mount!
  "Mount `k` roots of `arm-id` and KEEP them. Answers the DOM read-back
  over the boundary class the arm should have produced.

  A literal `#js` object rather than `clj->js`: `clj->js` would render
  `:ok?` as the key `\"ok?\"` and a driver reading `verify.ok` would see
  `undefined` — every mount reported unverified while every mount was
  fine. That happened, on the predecessor's first run."
  [arm-id k]
  (release!*)
  (let [{:keys [mount-one selector expected]} (get arm-table (keyword arm-id))
        containers (mapv (fn [_] (h/container!)) (range k))
        handles    (into [] (map-indexed (fn [i c] (mount-one c i))) containers)
        elements   (.-length (.querySelectorAll js/document selector))
        want       (* k expected)]
    (reset! held {:arm (keyword arm-id) :handles handles :containers containers})
    #js {:elements elements :expected want :ok (= elements want)}))

;; ---------------------------------------------------------------------------
;; The positive control
;; ---------------------------------------------------------------------------

(defonce ^:private control-cell (atom nil))

(defn control!
  "Hold a dense JS array of exactly `n` doubles and answer its length, so
  the allocation cannot be proved dead and elided.

  V8 stores a packed double array as `n` unboxed 8-byte slots behind a
  small header, so the retained cost is **8n bytes** — PREDICTED, not
  merely observed. That is the entire point of a control: a figure the
  instrument has to hit, not one it gets to report.

  The predecessor's first draft of this control was a flat one-byte string
  of `8n` characters, on the same reasoning, and it was a FICTION: a
  4.7 MB `'x'.repeat(…)` reads as 6 KB on all three readers because V8
  does not materialise the characters. Had it shipped, the control would
  have missed by three orders of magnitude every round and the natural
  conclusion would have been that the instrument was broken."
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
  "Publish the instrument on `window.P0H`. The page mounts; it never
  decides when a reading is taken."
  []
  (set! (.-P0H js/window)
        #js {:prepare        (fn [seg] (prepare! (when seg (keyword seg))))
             :mount          (fn [arm k] (mount! arm k))
             :release        (fn [] (release!*) true)
             :control        (fn [n] (control! n))
             :controlRelease (fn [] (control-release!))
             :perfMem        (fn []
                               (if-some [m (.-memory js/performance)]
                                 (.-usedJSHeapSize m)
                                 -1))
             :slotOrder      (fn [n round] (clj->js (guard/slot-order n round)))
             ;; ONE expression of the arm-order rule, used by both rows.
             ;; The heap row's samples are produced by the DRIVER (only it
             ;; can force a collection), so the driver hands them back in
             ;; here to be adjudicated by the same `re-frame.bench.order-guard`
             ;; the clock row uses in-page. A second copy of the rule in
             ;; JavaScript would be a second place for it to drift.
             :verdict        (fn [samples tolerance]
                               (pr-str (guard/verdict (js->clj samples :keywordize-keys true)
                                                      {:tolerance tolerance})))
             ;; ONE expression of the positive-control rule too, and it is
             ;; the LANE's. The driver owns the collector, so it is the
             ;; driver that reads `predicted` against the measured range —
             ;; and until rf2-95s5b it printed that pair as a bare ratio
             ;; nothing adjudicated. `lane/control-verdict` is what the
             ;; freehand P0 arms already publish their controls under; what
             ;; it DECIDES is not this namespace's to change (rf2-egdaq),
             ;; and its docstring states the rule it applies — range
             ;; OVERLAP, not every-round-inside.
             ;;
             ;; A flat literal `#js` answer, never `clj->js`: that would
             ;; render `:ok?` as the key `"ok?"` and a driver reading
             ;; `v.ok` would see `undefined` — the control green for ever
             ;; because nothing could read it. The same trap `mount!`
             ;; already carries the scar from.
             :controlVerdict (fn [predicted measured slack]
                               (let [m (js->clj measured :keywordize-keys true)
                                     v (lane/control-verdict
                                         predicted
                                         (select-keys m [:min :max :mean])
                                         slack)]
                                 #js {:ok    (boolean (:ok? v))
                                      :why   (:why v)
                                      :slack (:slack v)}))
             :guardSelfTest  (fn [] (pr-str (guard/self-test)))
             :boundariesPerRoot #js {:list rows-per-root :grid per-root}})
  nil)
