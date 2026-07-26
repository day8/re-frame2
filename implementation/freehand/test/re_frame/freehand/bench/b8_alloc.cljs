(ns re-frame.freehand.bench.b8-alloc
  "B8 — bytes ALLOCATED per write, across substrates.

  B7 measured what a boundary KEEPS. This measures what a write TOUCHES
  and throws away. They are different questions and they need different
  instruments, and the whole difficulty of this namespace is that the
  obvious instruments answer the first question while appearing to answer
  the second.

  ## Why the two obvious instruments cannot be used

  **V8's CDP sampling heap profiler drops the samples of collected
  objects.** Pointed at an allocate-and-drop loop it reports the residue,
  not the traffic: B7's predecessor watched the same 80,000 objects read
  4.77 MB when a global held them and 0.00 MB when nothing did. A sampler
  is a retention instrument wearing an allocation instrument's name, and
  it has already produced one wrong table on this surface. It is not used
  here, and [[re-frame.freehand.bench.b8-alloc]]'s driver runs it once as
  a NEGATIVE control so the claim is demonstrated rather than asserted.

  **B7's own retention door cannot answer it either**, by construction:
  mount, collect, read measures what survived a collection, and transient
  garbage is exactly what does not.

  ## The instrument: a window with no collection in it

  If no garbage collection runs between two readings of V8's used-heap
  counter, then the difference between them is the number of bytes
  allocated in between — **including every byte that is already
  unreachable**, because nothing has reclaimed it yet. Garbage is fully
  visible precisely because it has not been collected.

  So a reading is: force a full collection; read; run N writes; read.
  The whole method rests on the middle step containing no collection, so
  that is not assumed — it is **detected and the window is rejected**.
  The used-heap counter is sampled at every leg boundary of every write
  into a preallocated buffer, and a counter that only ever rises is a
  window in which nothing was reclaimed. One decrease anywhere and the
  window is thrown away rather than averaged in.

  ## The positive control is a piece of garbage of predicted size

  [[control-alloc!]] copies a prebuilt packed array of D doubles with
  `.slice()` and immediately drops the copy. V8 stores such an array as
  D unboxed 8-byte slots, so the copy costs a **predicted 8D bytes**, and
  `.slice()` of an already-double-typed array allocates the result
  directly with no intermediate `FixedArray` to transition away from —
  which `new Array(D).fill(0.5)` would have had, and which would have made
  the prediction 12D and this comment an excuse.

  Nothing holds the copy past the statement that makes it. So:

  - it is measured **directly**, as its own leg of the window, and
  - it is measured **differentially**, as the slope between two values of
    D across otherwise identical runs, which cancels every constant.

  A retention instrument reads this control as ZERO. An instrument that
  reads it as 8D per write can see garbage, and that is the entire claim
  this namespace needs to establish before any arm is quoted.

  B7's control failed in draft — a 4.7 MB `'x'.repeat(n)` string read as
  six kilobytes on all three readers, because V8 does not materialise it.
  The lesson taken here is that a control is only worth having if its
  size is predicted before it is measured, so both numbers are reported
  every round.

  ## The window is B6's, and the legs are split

  The measured window mirrors
  [[re-frame.freehand.bench.b6-rows/timed-write!]] leg for leg — the same
  state install, the same single microtask yield, the same empty
  `react-dom/flushSync` drain, the same per-write read-back of the written
  cell out of the DOM — with the used-heap counter read where that
  function reads the clock. It is a mirror rather than a call because the
  published function reads `performance.now()` and nothing else; the
  driver ALSO runs the published function verbatim over the same window
  and checks the two totals against each other, so the mirror's fidelity
  is a measured claim and not a promise in a docstring.

  The split matters for the same reason it matters on the clock, and
  `freehand-vs-reagent.md` §2a is the reason: the Freehand arm reads
  `app-db` through a subscription graph while the Reagent arm reads a
  bare `reagent.core/atom`. `:write-bytes` is that leg alone — the
  re-frame write and the signal graph, before React is involved — so a
  reader can see how much of any gap a re-frame-shaped Reagent
  application would also have paid.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [goog.object :as gobj]
            [re-frame.freehand.bench.b6-rows :as rows]))

;; ---------------------------------------------------------------------------
;; Why every property below is a STRING LITERAL through `goog.object`
;; ---------------------------------------------------------------------------
;;
;; The first draft of this namespace used `#js {:h …}` literals and
;; `(.-h o)` accessors, and under `:advanced` it produced this:
;;
;;     construction   return {h:[f,g,k,l,p], ok:…}
;;     read           var l = k.Me, p = l[0]
;;
;; `(.-h r)` was renamed to `Me`; the literal key was not. `k.Me` is
;; `undefined` and `undefined[0]` throws, which is how it was caught.
;;
;; THE CRASH WAS LUCK, AND THAT IS THE POINT. In the same build:
;;
;;     (.-decreases a) -> e.fc      literal key `decreases`
;;     (.-worstDrop a) -> e.oc      literal key `worstDrop`
;;     (.-bad a)       -> e.Mc      literal key `bad`
;;     (.-control a)   -> e.control literal key `control`   (survived)
;;     (.-ok r)        -> k.ok      literal key `ok`        (survived)
;;
;; The survivors survived by ACCIDENT: `ok`, `control`, `write`, `gap`,
;; `force`, `total`, `first` and `last` all appear in Closure's browser
;; externs (`Response.ok`, `Touch.force`, `document.write`, CSS `gap`, …)
;; and Closure will not rename a name it finds there. `h`, `decreases`,
;; `worstDrop` and `bad` do not, so they were renamed.
;;
;; None of the survivors would have thrown. `e.Mc += 1` on an undefined
;; slot yields `NaN` and leaves the literal's `bad: 0` untouched, so the
;; driver would have read **zero unverified writes, always**. `decreases`
;; is worse: it is the GATE that rejects a window with a collection in
;; it, and it would have read a constant zero — every window accepted,
;; including the ones whose totals a collection had silently truncated.
;;
;; That is a plausible wrong number produced by a harness that looks
;; green, which is the failure mode this whole surface exists to avoid.
;; So: string literals through `goog.object`, which compile to a dynamic
;; index and are never renamed — and [[integrity-probe]], which proves it
;; from inside the shipped bundle before any figure is taken.

(defn- bump! [o k v] (gobj/set o k (+ (gobj/get o k) v)))

(defn- fresh-acc []
  (js-obj "control" 0 "write" 0 "gap" 0 "force" 0 "total" 0
          "bad" 0 "decreases" 0 "worstDrop" 0 "first" 0 "last" 0))

(defn integrity-probe
  "Prove, inside the `:advanced` bundle, that this namespace reads the
  keys it writes.

  Runs before any measurement. Writes known values through exactly the
  accessors the measured path uses and hands back both the values and
  the object's own key list, so the driver can assert on names it never
  sees renamed. A renaming mismatch makes this return `null` or the
  wrong number, and the driver refuses to measure."
  []
  (let [o (fresh-acc)]
    (bump! o "control" 7)
    (bump! o "decreases" 3)
    (bump! o "worstDrop" -11)
    (bump! o "bad" 5)
    (js-obj "control"   (gobj/get o "control")
            "decreases" (gobj/get o "decreases")
            "worstDrop" (gobj/get o "worstDrop")
            "bad"       (gobj/get o "bad")
            "keys"      (.join (js/Object.keys o) ","))))

;; ---------------------------------------------------------------------------
;; The reader
;; ---------------------------------------------------------------------------

(defn mem
  "V8's used-heap counter, in bytes, read from inside the page.

  Requires `--enable-precise-memory-info`; without it Chrome quantises
  this to 100 KB buckets and every figure below would be noise. The
  driver asserts the flag took by checking that the value is not a round
  multiple of 100,000 across several reads.

  This is the SAME counter CDP's `Runtime.getHeapUsage` returns — B7
  established that they agree to the byte on 80,000 held objects, and
  this namespace does not pretend otherwise. It is read here rather than
  over CDP for one reason: a CDP round trip costs about a millisecond and
  there are five readings per write, which would cost more than the
  window being measured. The driver's CDP readings bracket the whole
  window and are what the in-page sum is checked against."
  []
  (let [m (.-memory js/performance)]
    (if (some? m) (.-usedJSHeapSize m) -1)))

;; ---------------------------------------------------------------------------
;; The positive control — garbage of predicted size
;; ---------------------------------------------------------------------------

(defonce ^:private ctl-template (volatile! nil))
(defonce ^:private ctl-sink (volatile! 0.0))

(defn control-prepare!
  "Build the template ONCE, outside every window. `js/Array` + `fill`
  transitions the elements kind and allocates twice; doing it here means
  the copy taken inside the window is a single clean `FixedDoubleArray`."
  [d]
  (vreset! ctl-template (when (pos? d) (.fill (js/Array. d) 0.5)))
  (vreset! ctl-sink 0.0))

(defn control-alloc!
  "Allocate 8·D bytes of immediate garbage.

  The slot read into a live sink is not decoration: Closure is entitled
  to delete an expression whose value nothing reads, and this surface has
  already published a `layout-ms` of exactly 0.000 in every arm because a
  property read was dropped. One element of the copy is summed into a
  volatile that outlives the call, so the copy must exist."
  []
  (when-some [t @ctl-template]
    (let [c (.slice t)]
      (vreset! ctl-sink (+ @ctl-sink (aget c 0))))))

;; ---------------------------------------------------------------------------
;; Arms
;; ---------------------------------------------------------------------------

(def ^:private wanted
  "The compiled tier is ruled out and is not mounted. UIx has no update
  arm in B6 — its update story is `use-state` and React's own scheduler,
  a different mechanism that would need an arm of its own."
  #{:floor :freehand-interpreted :reagent})

(defn- instrument-arm
  "A pseudo-arm that installs no state and drains nothing, so a window
  over it contains the harness and NOTHING ELSE — five `mem` reads, one
  promise, one accumulate. It prices the instrument's own footprint,
  which is otherwise an unexamined constant sitting inside every figure
  in the table. Its read-back is skipped and it is counted separately
  from the arms, because it renders no cell to read back."
  []
  {:id :instrument :skip-verify? true
   :mount   (fn [container] container)
   :write!  (fn [_ _] nil)
   :force!  (fn [] nil)
   :unmount (fn [_] nil)})

(defn make-arms []
  (conj (filterv #(wanted (:id %)) (rows/make-update-arms))
        (instrument-arm)))

;; ---------------------------------------------------------------------------
;; The measured window — B6's, with the counter read where the clock is
;; ---------------------------------------------------------------------------

(defn- alloc-write!
  "One write, with the used-heap counter read at every leg boundary.
  Answers a promise of a five-reading vector plus the read-back verdict."
  [{:keys [arm container]} i val]
  (let [h0 (mem)]
    (control-alloc!)
    (let [hc (mem)]
      ((:write! arm) i val)
      (let [h1 (mem)]
        (-> (js/Promise.resolve nil)
            (.then (fn [_]
                     (let [h2 (mem)]
                       ((:force! arm))
                       (let [h3    (mem)
                             probe (if (= i :all) 0 i)]
                         (js-obj "readings" (array h0 hc h1 h2 h3)
                                 "ok" (or (boolean (:skip-verify? arm))
                                          (and (= (str val) (rows/cell-text container probe))
                                               (or (not= i :all)
                                                   (= (str val)
                                                      (rows/cell-text
                                                        container
                                                        (dec rows/cells-n)))))))))))))))

(defn run-window!
  "N writes over one arm, inside one collection-free window.

  Answers a promise of the accumulated legs, the count of writes whose
  DOM read-back failed, and — the gate — the number of places where the
  used-heap counter went DOWN. A window with any decrease had a
  collection in it and is not a measurement of allocation; the driver
  drops it."
  [mnt kind n]
  (let [acc  (fresh-acc)
        prev (volatile! nil)
        drop! (fn [a x y]
                (when (< y x)
                  (bump! a "decreases" 1)
                  (gobj/set a "worstDrop" (min (gobj/get a "worstDrop") (- y x)))))]
    (rows/chain acc (range n)
      (fn [a _]
        (let [val (rows/next-gen!)
              i   (if (= kind :broad) :all (mod val rows/cells-n))]
          (-> (alloc-write! mnt i val)
              (.then (fn [r]
                       (let [h  (gobj/get r "readings")
                             h0 (aget h 0) hc (aget h 1) h1 (aget h 2)
                             h2 (aget h 3) h3 (aget h 4)]
                         ;; Decreases are counted across the WHOLE window,
                         ;; including the seam between one write's last
                         ;; reading and the next write's first.
                         (when-some [p @prev] (drop! a p h0))
                         (drop! a h0 hc) (drop! a hc h1)
                         (drop! a h1 h2) (drop! a h2 h3)
                         (when (nil? @prev) (gobj/set a "first" h0))
                         (vreset! prev h3)
                         (gobj/set a "last" h3)
                         (bump! a "control" (- hc h0))
                         (bump! a "write"   (- h1 hc))
                         (bump! a "gap"     (- h2 h1))
                         (bump! a "force"   (- h3 h2))
                         (bump! a "total"   (- h3 h0))
                         (when-not (gobj/get r "ok") (bump! a "bad" 1))
                         a)))))))))

(defn run-published-window!
  "The same N writes through
  [[re-frame.freehand.bench.b6-rows/timed-write!]] VERBATIM — the
  published function, not a mirror of it.

  The driver brackets this with CDP heap readings. Its total against the
  mirror's total is what makes the mirror's leg split trustworthy: two
  loops over the same window, one reading the counter five times a write
  and one not reading it at all, must allocate the same bytes to within
  the instrument's own footprint."
  [mnt kind n]
  (let [skip? (boolean (:skip-verify? (:arm mnt)))]
    (rows/chain (js-obj "bad" 0) (range n)
      (fn [a _]
        (let [val (rows/next-gen!)
              i   (if (= kind :broad) :all (mod val rows/cells-n))]
          (control-alloc!)
          (-> (rows/timed-write! mnt i val)
              (.then (fn [{:keys [ok? ok2?]}]
                       (when-not (or skip? (and ok? ok2?)) (bump! a "bad" 1))
                       a))))))))
