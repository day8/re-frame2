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

  ## Cache cardinality is part of the witness (rf2-2rtt6.16, rf2-5prok)

  A retained-bytes-per-boundary figure is only defined relative to how
  many boundaries share a subscription, so every reading here stamps **B**
  (boundaries), **E** (boundary-query edges) and **Q** (unique live query
  keys). B is read back off the DOM and E follows from B and the arm's
  read count; Q is the one a page cannot see from the outside, so
  [[mount!]] asks the frame's own sub-cache how many entries it is
  holding and the driver refuses a mount whose answer is not the Q the
  plan asked for. A Q that is asserted rather than counted is the same
  class of decoration as a mount count that is printed and not gated.

  Owner: rf2-2rtt6.1 (standard); this arm rf2-2rtt6.4; the fan-out family
  and the additive model rf2-5prok."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [clojure.string :as str]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.order-guard :as guard]
            [re-frame.bench.p0-arms :as arms]
            [re-frame.bench.p0-fixture :as fx]
            [re-frame.bench.p0-floor :as floor]
            [re-frame.bench.p0-harness :as h]
            [re-frame.bench.p0-reagent :as rg]
            [re-frame.bench.p0-uix :as ux]
            [re-frame.frame :as frame]
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

;; `element-of` / `form-of` take the ROOT INDEX. The published arms ignore
;; it — four roots of `grid/uix` are four copies of one page, which is the
;; shape that produced the fan-out finding in the first place — but the
;; fan-out family needs it, because a root's boundaries are numbered from
;; its own base in a GLOBAL numbering and that base is the only thing
;; separating "four roots sharing every key" from "four roots sharing
;; none".

(defn- react-root-arm [element-of selector expected]
  {:selector    selector
   :expected    expected
   :mount-one   (fn [c i]
                  (let [r (react-dom-client/createRoot c)]
                    (react-dom/flushSync (fn [] (.render r (element-of i))))
                    r))
   :unmount-one (fn [r] (.unmount r))})

(defn- reagent-root-arm [form-of selector expected]
  {:selector    selector
   :expected    expected
   :mount-one   (fn [c i]
                  (let [rt (rdc/create-root c)]
                    (react-dom/flushSync (fn [] (rdc/render rt (form-of i))))
                    rt))
   :unmount-one (fn [rt] (rdc/unmount rt))})

(defn- uix-root-arm [element-of selector expected]
  {:selector    selector
   :expected    expected
   :mount-one   (fn [c i]
                  (let [rt (uix-dom/create-root c)]
                    (react-dom/flushSync (fn [] (uix-dom/render-root (element-of i) rt)))
                    rt))
   :unmount-one (fn [rt] (uix-dom/unmount-root rt))})

(def arm-table
  "`arm-id -> {:segment :selector :expected :keys-expected :mount-one
  :unmount-one}`.

  `:segment` is which adapter the arm needs; the driver calls `prepare!`
  with it before taking the baseline of the pair. A floor arm names the
  segment it is being measured IN, because the floor is the calibrator and
  has to be read on both sides of the seam.

  `:keys-expected` is **Q**, and it is written down here rather than
  derived, because the published arms are exactly the ones whose Q the
  ruling had to reconstruct by reading the source: every root of
  `grid/uix` renders the same `[:p0/cell 0…299]` vectors against the same
  frame, so four roots hold **300** reactions and not 1,200. That claim is
  now a number this instrument checks on every mount instead of an
  argument about a file."
  {:list/floor
   (assoc (react-root-arm (fn [_] (floor/w1 (mapv fx/row-value (range rows-per-root))))
                          ".row" rows-per-root)
          :segment nil :keys-expected 0)

   :list/reagent
   (assoc (reagent-root-arm (fn [_] (rg/w1-root arms/frame-id rows-per-root))
                            ".row" rows-per-root)
          :segment :reagent-subs :keys-expected rows-per-root)

   :list/uix
   (assoc (uix-root-arm (fn [_] (ux/w1-root arms/frame-id rows-per-root))
                        ".row" rows-per-root)
          :segment :uix-subs :keys-expected rows-per-root)

   :grid/floor
   (assoc (react-root-arm (fn [_] (floor/u-grid (vec (repeat per-root 0))))
                          ".cell" per-root)
          :segment nil :keys-expected 0)

   :grid/reagent
   (assoc (reagent-root-arm (fn [_] (rg/u-root arms/frame-id)) ".cell" per-root)
          :segment :reagent-subs :keys-expected per-root)

   :grid/uix
   (assoc (uix-root-arm (fn [_] (ux/u-root arms/frame-id)) ".cell" per-root)
          :segment :uix-subs :keys-expected per-root)})

;; ---------------------------------------------------------------------------
;; The fan-out family — B, E and Q moved independently (rf2-5prok)
;; ---------------------------------------------------------------------------

(defn- fan-arm
  "One rung of the fan-out sweep: `reads` subscription reads on each of the
  same `.cell` boundaries the `grid` family holds, drawn from `q` unique
  query keys across the whole page.

  Constructed per mount rather than tabled, because the rung IS the
  parameters. B is `ROOTS × per-root` and does not move; E is `B × reads`;
  Q is `q`. Mean fan-out E/Q therefore falls out of the plan and is not a
  property of a hand-written page."
  [substrate reads q]
  (let [root-of (case substrate
                  :reagent (fn [r] (rg/fan-root arms/frame-id (* r per-root) per-root reads))
                  :uix     (fn [r] (ux/fan-root arms/frame-id (* r per-root) per-root reads)))
        base    (case substrate
                  :reagent (reagent-root-arm root-of ".cell" per-root)
                  :uix     (uix-root-arm root-of ".cell" per-root))]
    (assoc base
           :segment       (case substrate :reagent :reagent-subs :uix :uix-subs)
           :reads         reads
           ;; A boundary that reads NOTHING holds no cache entry, so the
           ;; R=0 rung's Q is 0 whatever `q` says. Stating it here keeps
           ;; the driver from having to special-case its own shell rung.
           :keys-expected (if (zero? (long reads)) 0 (long q)))))

(defn- arm-for
  "Resolve `arm-id` to an arm map. `:fan/*` is parameterised by `opts`
  (`{:reads r :keys q}`); everything else is the published table."
  [arm-id opts]
  (or (get arm-table arm-id)
      (case arm-id
        :fan/reagent (fan-arm :reagent (:reads opts) (:keys opts))
        :fan/uix     (fan-arm :uix     (:reads opts) (:keys opts))
        nil)))

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
  (when-some [{:keys [unmount-one handles containers]} @held]
    (doseq [hd (reverse handles)]
      (unmount-one hd))
    (doseq [c containers] (.remove c))
    (reset! held nil))
  nil)

(defn- live-key-count
  "How many UNIQUE query keys the frame's subscription cache is holding —
  **Q**, counted rather than asserted.

  The production cache is `{query-vector {:reaction … :ref-count n}}` per
  frame and an entry is evicted in-tick when its last derefer drops
  (`re-frame.subs.cache`), so between arms this reads 0 and during an arm
  it reads exactly the number of distinct query vectors the mounted page
  holds — independently of how many boundaries hold each one. That is the
  quantity the two published heap families disagreed about, and it is
  cheaper to count it than to argue about it."
  []
  (if-some [f (frame/frame arms/frame-id)]
    (count @(:sub-cache f))
    0))

(defn mount!
  "Mount `k` roots of `arm-id` and KEEP them. Answers the DOM read-back
  over the boundary class the arm should have produced, AND the sub-cache
  read-back over the unique query keys the plan said it would hold.

  `opts` carries the fan-out rung — `{:reads r :keys q}` — and is ignored
  by the published arms. Q is set on the fixture BEFORE the first root
  mounts and once for the whole arm, because the roots of an arm share one
  frame and therefore one key space.

  A literal `#js` object rather than `clj->js`: `clj->js` would render
  `:ok?` as the key `\"ok?\"` and a driver reading `verify.ok` would see
  `undefined` — every mount reported unverified while every mount was
  fine. That happened, on the predecessor's first run."
  [arm-id k opts]
  (release!*)
  (let [opts       (js->clj opts :keywordize-keys true)
        arm        (arm-for (keyword arm-id) opts)
        {:keys [mount-one unmount-one selector expected keys-expected]} arm
        _          (when-some [q (:keys opts)] (fx/set-fan-keys! q))
        containers (mapv (fn [_] (h/container!)) (range k))
        handles    (into [] (map-indexed (fn [i c] (mount-one c i))) containers)
        elements   (.-length (.querySelectorAll js/document selector))
        want       (* k expected)
        live       (live-key-count)]
    (reset! held {:arm (keyword arm-id) :unmount-one unmount-one
                  :handles handles :containers containers})
    #js {:elements     elements
         :expected     want
         :keys         live
         :keysExpected keys-expected
         :ok           (and (= elements want) (= live keys-expected))}))

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
;; THE ADDITIVE MODEL — and the rule that decides whether it may be priced
;; ---------------------------------------------------------------------------
;;
;; The heap-regime ruling (rf2-2rtt6.16 Part 3) adopts a target shape for
;; the budget: a boundary's retained cost decomposes into a SHELL, a
;; per-EDGE term (one boundary's attachment to one query) and a per-unique
;; -KEY term (one cached reaction, however many boundaries hold it).
;;
;;     y  =  shell  +  (E/B)·edge  +  (Q/B)·key
;;
;; It also REFUSES to freeze those numbers from cross-instrument algebra,
;; because the paired rows came from different instruments and runs — and
;; it makes this sweep the gate. So the sweep has to do two things a fit
;; alone does not: identify the terms from CONTRASTS that move one factor
;; at a time, and then try to BREAK the model on rungs it was not fitted
;; to.
;;
;; Identification. The R=1 family holds E/B = 1 and walks Q/B, so a
;; straight line through it has slope `key` and intercept `shell + edge`;
;; the R=0 rung measures `shell` on its own; `edge` is the difference. All
;; three come out of one page, one round, one reader.
;;
;; Falsification. Two R=2 rungs are then measured and PREDICTED FROM
;; NOTHING BUT THE ABOVE. They are not in the fit. If the cost of a read
;; depended on how many reads a boundary already had, or if a shared key
;; were cheaper per consumer than an exclusive one, the R=1 family would
;; still be a line and the R=2 predictions would miss. The self-test below
;; proves that is not a hypothetical: a synthetic page with a quadratic
;; key term passes the r² floor at 0.997 and is caught only here.
;;
;; The verdict this returns is not an instrument fault and does not stop a
;; run. It decides whether the numbers may be QUOTED as component prices,
;; which is the thing the ruling gated.

(def additive-criterion
  "The thresholds, fixed before the sweep ran and not moved after it.

  `:min-r2` — the R=1 family must be a line in Q/B. 0.98 over four rungs
  is well inside what this instrument has shown it can resolve (the reads
  ladder returned r² ≥ 0.9987 on both substrates) and far outside what a
  page with a real interaction term would return.

  `:max-oos-error` — each R=2 rung's prediction must land within 10% of
  the measured value. The rungs sit at 3–13 KB per boundary and this
  instrument's per-arm ranges are well under 1% wide, so 10% is a margin
  for the model, not for the reader.

  `:max-key-disagreement` — the per-key term solved from the R=2 PAIR
  alone must agree with the R=1 slope within 15%. Two disjoint subsets of
  the sweep, one arithmetic."
  {:min-r2 0.98 :max-oos-error 0.10 :max-key-disagreement 0.15})

(defn- ols
  "Ordinary least squares of `[x y]` pairs. Answers `{:slope :intercept
  :r2 :n}`. `:r2` is 1.0 for a degenerate y — a family that does not vary
  is perfectly explained by a flat line, and calling that 0 would refuse
  the one case where the model is trivially exact."
  [pts]
  (let [n   (count pts)
        xs  (mapv first pts)
        ys  (mapv second pts)
        mx  (/ (reduce + xs) n)
        my  (/ (reduce + ys) n)
        sxx (reduce + (map (fn [x] (let [d (- x mx)] (* d d))) xs))
        sxy (reduce + (map (fn [x y] (* (- x mx) (- y my))) xs ys))
        slope (if (zero? sxx) 0.0 (/ sxy sxx))
        b0  (- my (* slope mx))
        syy (reduce + (map (fn [y] (let [d (- y my)] (* d d))) ys))
        ssr (reduce + (map (fn [x y] (let [e (- y (+ b0 (* slope x)))] (* e e))) xs ys))]
    {:slope slope :intercept b0 :n n
     :r2 (if (zero? syy) 1.0 (- 1.0 (/ ssr syy)))}))

(defn- pct [x] (str (.toFixed (* 100.0 x) 2) "%"))

(defn additive-fit
  "Price `shell`, `edge` and `key` from one substrate's rungs, and
  adjudicate whether they may be quoted.

  `rungs` is a seq of `{:rung :reads :keys :boundaries :y}`, where `y` is
  exclusive retained bytes per boundary (arm − floor). Answers a map whose
  `:ok?` is the ruling's refusal rule: false means the page publishes the
  rows and NOT the prices, and says which check failed."
  [rungs {:keys [min-r2 max-oos-error max-key-disagreement]}]
  (let [xof      (fn [{:keys [keys boundaries]}] (/ (double keys) (double boundaries)))
        of-reads (fn [r] (vec (sort-by xof (filterv #(= r (long (:reads %))) rungs))))
        zero     (first (of-reads 0))
        ones     (of-reads 1)
        twos     (of-reads 2)]
    (if (or (nil? zero) (< (count ones) 3) (< (count twos) 2))
      {:ok?  false
       :why  (str "the sweep is missing a rung the model needs — R=0 ×"
                  (if zero 1 0) ", R=1 ×" (count ones) ", R=2 ×" (count twos)
                  " (need 1, ≥3, 2)")
       :checks []}
      (let [{:keys [slope intercept r2]} (ols (mapv (fn [g] [(xof g) (:y g)]) ones))
            shell    (:y zero)
            key-term slope
            edge     (- intercept shell)
            predict  (fn [g] (+ shell (* 2.0 edge) (* (xof g) key-term)))
            oos      (mapv (fn [g]
                             (let [p (predict g)]
                               {:rung     (:rung g)
                                :q-over-b (xof g)
                                :measured (:y g)
                                :predicted p
                                :error    (/ (- p (:y g)) (:y g))}))
                           twos)
            lo       (first twos)
            hi       (peek twos)
            dx       (- (xof hi) (xof lo))
            key-2    (when-not (zero? dx) (/ (- (:y hi) (:y lo)) dx))
            key-gap  (when (and key-2 (not (zero? key-term)))
                       (/ (- key-2 key-term) key-term))
            twin     (first (filter #(< (js/Math.abs (- (xof %) (xof lo))) 1e-9) ones))
            edge-2   (when twin (- (:y lo) (:y twin)))
            checks
            [{:name "the R=1 family is a LINE in Q/B"
              :ok   (>= r2 min-r2)
              :detail (str "r² " (.toFixed r2 5) " over " (count ones)
                           " rungs (floor " min-r2 ")")}
             {:name "the two R=2 rungs are predicted OUT OF SAMPLE"
              :ok   (every? #(<= (js/Math.abs (:error %)) max-oos-error) oos)
              :detail (str/join
                        " · "
                        (map (fn [o]
                               (str (:rung o) " predicted " (.toFixed (:predicted o) 0)
                                    " B, measured " (.toFixed (:measured o) 0)
                                    " B, " (pct (:error o))))
                             oos))}
             {:name "the per-key term from the R=2 PAIR agrees with the R=1 slope"
              :ok   (boolean (and key-gap
                                  (<= (js/Math.abs key-gap) max-key-disagreement)))
              :detail (if key-gap
                        (str "R=2 pair " (.toFixed key-2 0) " B vs R=1 slope "
                             (.toFixed key-term 0) " B — " (pct key-gap))
                        "the two R=2 rungs share a Q/B; the pair cannot price a slope")}]]
        {:ok?        (every? :ok checks)
         :checks     checks
         :shell      shell
         :edge       edge
         :key        key-term
         :edge-alt   edge-2
         :key-alt    key-2
         :intercept  intercept
         :r2         r2
         :oos        oos
         :criterion  {:min-r2 min-r2
                      :max-oos-error max-oos-error
                      :max-key-disagreement max-key-disagreement}
         :why        (if (every? :ok checks)
                       "additive — component prices may be quoted"
                       (str "REFUSED: "
                            (str/join "; " (map :name (remove :ok checks)))))}))))

(defn fan-self-test
  "The adjudicator's own positive control, PREDICTED before it is run.

  Two synthetic pages, both built by arithmetic rather than measured:

  **A** is the model exactly — shell 400, edge 250, key 900 over B = 1,200
  — and must be priced back to those three numbers and accepted.

  **B** adds a QUADRATIC key term (`+300·(Q/B)²`), which is what a page
  whose shared reactions were cheaper per consumer than its exclusive ones
  would look like. It must be REFUSED — and the interesting part is where:
  its R=1 family still fits a line at r² ≈ 0.997, comfortably past the
  0.98 floor, so the linearity check passes and only the out-of-sample
  R=2 rungs catch it. That is the whole reason the R=2 rungs exist, and a
  self-test that did not price it would leave them looking like padding.

  A rule that cannot fail has not adjudicated anything."
  []
  (let [b     1200.0
        mk    (fn [f] (mapv (fn [[id r q]] {:rung id :reads r :keys q :boundaries b
                                            :y (f r (/ (double q) b))})
                            [["R0" 0 0] ["R1Q1" 1 1200] ["R1Q2" 1 600]
                             ["R1Q4" 1 300] ["R1Q8" 1 150]
                             ["R2Q2B" 2 2400] ["R2QB2" 2 600]]))
        exact (mk (fn [r x] (if (zero? r) 400.0 (+ 400.0 (* r 250.0) (* x 900.0)))))
        curvy (mk (fn [r x] (if (zero? r)
                              400.0
                              (+ 400.0 (* r 250.0) (* x 900.0) (* x x 300.0)))))
        fa    (additive-fit exact additive-criterion)
        fb    (additive-fit curvy additive-criterion)
        near? (fn [a b tol] (< (js/Math.abs (- a b)) tol))
        chk   [{:name "the exact additive page is priced back to its own three terms"
                :ok   (and (:ok? fa)
                           (near? (:shell fa) 400.0 0.5)
                           (near? (:edge fa) 250.0 0.5)
                           (near? (:key fa) 900.0 0.5))
                :detail (str "shell " (.toFixed (:shell fa) 1)
                             " · edge " (.toFixed (:edge fa) 1)
                             " · key " (.toFixed (:key fa) 1))}
               {:name "the exact page's R=2 rungs are predicted to the byte"
                :ok   (every? #(< (js/Math.abs (:error %)) 1e-9) (:oos fa))
                :detail (str/join " · " (map #(str (:rung %) " " (pct (:error %)))
                                             (:oos fa)))}
               {:name "a QUADRATIC key term is refused"
                :ok   (not (:ok? fb))
                :detail (:why fb)}
               {:name "…and it is refused OUT OF SAMPLE, not by the r² floor"
                :ok   (and (>= (:r2 fb) (:min-r2 additive-criterion))
                           (not (:ok (second (:checks fb)))))
                :detail (str "r² " (.toFixed (:r2 fb) 5)
                             " passes the " (:min-r2 additive-criterion)
                             " floor; the R=2 rungs miss by "
                             (str/join " and "
                                       (map #(pct (:error %)) (:oos fb))))}]]
    {:ok? (every? :ok chk) :checks chk}))

;; ---------------------------------------------------------------------------
;; The page-side door
;; ---------------------------------------------------------------------------

(defn install!
  "Publish the instrument on `window.P0H`. The page mounts; it never
  decides when a reading is taken."
  []
  (set! (.-P0H js/window)
        #js {:prepare        (fn [seg] (prepare! (when seg (keyword seg))))
             :mount          (fn [arm k opts] (mount! arm k opts))
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
             ;; The fan-out sweep's two doors, and both of them are RULES
             ;; rather than arithmetic, so both live here rather than in a
             ;; JavaScript restatement the driver would own alone. The
             ;; self-test runs before the sweep measures anything, exactly
             ;; as the arm-order guard's does.
             ;;
             ;; Flat `#js` answers, never `clj->js`, for the reason
             ;; `mount!` carries the scar from: `clj->js` renders `:ok?`
             ;; as the key `"ok?"` and a driver reading `v.ok` sees
             ;; `undefined` — a gate green for ever because nothing could
             ;; read it. The EDN rides along whole, for the record file.
             :fanSelfTest    (fn []
                               (let [st (fan-self-test)]
                                 #js {:ok     (boolean (:ok? st))
                                      :checks (into-array
                                                (map (fn [c]
                                                       #js {:ok     (boolean (:ok c))
                                                            :name   (:name c)
                                                            :detail (:detail c)})
                                                     (:checks st)))}))
             :fanVerdict     (fn [rungs]
                               (let [v (additive-fit
                                         (js->clj rungs :keywordize-keys true)
                                         additive-criterion)]
                                 #js {:ok       (boolean (:ok? v))
                                      :why      (:why v)
                                      :shell    (:shell v)
                                      :edge     (:edge v)
                                      :key      (:key v)
                                      :edgeAlt  (:edge-alt v)
                                      :keyAlt   (:key-alt v)
                                      :r2       (:r2 v)
                                      :checks   (into-array
                                                  (map (fn [c]
                                                         #js {:ok     (boolean (:ok c))
                                                              :name   (:name c)
                                                              :detail (:detail c)})
                                                       (:checks v)))
                                      :edn      (pr-str v)}))
             :boundariesPerRoot #js {:list rows-per-root :grid per-root}})
  nil)
