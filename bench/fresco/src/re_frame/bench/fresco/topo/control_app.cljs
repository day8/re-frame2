(ns re-frame.bench.hicasso.topo.control-app
  "**THE TOURNAMENT'S CLOCK CONTROL** — one positive control per arm, each
  doubling the quantity THAT arm's commit cost is proportional to
  (rf2-m6i0, replacing the changed-set control rf2-hic-036 built and
  refused).

  Driven by the lane's generic driver, so it adds no driver of its own:

      HICASSO_INIT_FN=re-frame.bench.hicasso.topo.control-app/-main \\
      HICASSO_OUT_DIR=out/topo-control \\
      HICASSO_PORT=8147 \\
      node implementation/hicasso/test/re_frame/bench/hicasso/run.cjs

  ## What this file decides, and why it runs BEFORE any cell

  The topology tournament's four operations are all WRITE rows, and the
  write rows of this lane's existing clock driver are refused by
  construction:

  > bulk-class rows cannot hold a difference-statistic control at the
  > ~3.5% floor a magnitude needs (rf2-7iqb5, 28–48% within-block IQR),
  > and the narrow class sits on the clock clamp (rf2-d2tzk)
  > — `shapes/census_clock_run.cjs`

  So the question this window exists to answer is not *what are the
  numbers* but *can any number here be trusted*. That question is settled
  by a control, and the control therefore runs first and alone. If it
  refuses, the tournament's clock half refuses with it and the cells are
  never taken — which is a result, not a failure.

  ## THE TWO CONTROLS THIS ONE REPLACES, AND THE ONE FAULT THEY SHARE

  Both predecessors were correct about their own arm and degenerate
  elsewhere, and the reason is the same in both directions: **a
  manipulation certifies an instrument only when EVERY cost in the
  measured window moves with it.** Whatever does not move is a shared
  constant, and a shared constant compresses the measured ratio toward 1
  — which reads exactly like an instrument that cannot see.

  1. **Page-scaling** (every control this lane had before `rf2-7iqb5`).
     Refused for update rows in general, and rightly: on an update row
     the work does not scale with the page, so even a perfect instrument
     reads below the prediction. `rf2-7iqb5`'s own run failed high at
     13.696 / 13.583 / 13.112x against a registered 8–13x band.

  2. **Changed-set doubling** (`rf2-7iqb5`'s prescribed repair, built by
     `rf2-hic-036` over `:topo/bump-stride`). Hold the page fixed, double
     the changed set, predict 2.00x. It was **degenerate on `coarse` and
     `chunked`**, which rebuild every row whichever stride runs — and it
     **REFUSED on `fine`**, the arm where its markup arithmetic is not
     degenerate, measuring 1.331 / 1.387 / 1.424 / 1.471 / 1.325 against
     a [1.60, 2.50] band on a quiet box with the order guard clean.

     `rf2-m6i0`'s diagnosis of that refusal is the design input for this
     file. Three costs sat in the measured window and only ONE doubled:
     `:topo/bump-stride` `reduce-kv`s the whole thousand-row table at
     both strides, the subscription layer answers for every row at both
     strides, and only the 100→200 rows of markup actually doubled. The
     page-scaling failure had reappeared *inside the control built to
     replace it*.

  ## THE REPAIR: double the arm's RENDERED PAGE, with an INDEXED write

  Two changes, and each one closes one of the two leaks above.

  **The write is indexed.** `:topo/bump-indexed` `update-in`s the rows it
  moves instead of rebuilding the table, so the handler costs
  `limit/stride` rather than `B`. That is the diagnosis's first
  condition: the event handler now costs in proportion to the changed
  set.

  **The manipulation scales the arm's own rendered page.** With the page
  doubled, the subscription layer and the render both double as well —
  the diagnosis's second condition — because there are twice as many rows
  to answer for and twice as many to build. Page-scaling is invalid for
  an update row *in general*, exactly as `rf2-7iqb5` says; it is valid
  HERE because the manipulation doubles the changed set too. Nothing in
  the window is held constant, which is precisely what the two refused
  controls each got wrong from opposite ends.

  ## WHY THE SCALED QUANTITY IS `rendered-rows` AND NOT `B`

  One rule, four instantiations, and the fourth is why the rule is stated
  in terms of `model/rendered-rows` rather than `B`:

  | arm | scaled | small → large | rendered |
  |---|---|---|---|
  | `fine` | `B` | 500 → 1000 | 500 → 1000 |
  | `coarse` | `B` | 500 → 1000 | 500 → 1000 |
  | `chunked` | `B` | 500 → 1000 | 500 → 1000 |
  | `virtual` | `w` | 20 → 40 | 20 → 40 |

  **Doubling `B` on the windowed arm is degenerate** — it renders `w`
  rows however large the table is, so its subscription layer, its markup
  and its DOM would all be constant across the manipulation and only the
  handler would move. That arm's page is its WINDOW, and doubling the
  window is the same control applied to the page the arm actually has.
  This is the mirror image of the changed-set control's degeneracy on
  `coarse`/`chunked`, and it is the reason no single scaled quantity can
  serve all four arms.

  ## The predicted factor is DERIVED, never assumed to be 2.00

  `census_clock_arms/ctl-predicted` computes 1.9759 / 1.9944 / 1.7255
  from element arithmetic rather than printing 2.00, because the page
  chrome does not double. The same discipline here, against the same
  committed function: the prediction is the ratio of
  [[re-frame.bench.hicasso.topo.model/elements-for]] at the two rendered
  row counts, and the `ul` that does not double is why it is 1.9996 and
  1.9901 rather than 2.

  The band is the lane's standing `CONTROL_SLACK` of ±25%
  (`shapes/census_clock_run.cjs`) under its strict rule — EVERY round
  inside, so one bad round refuses rather than being averaged away.
  **This is not a widening**: ±25% of 1.9996 floors at 1.4997, and the
  refused changed-set run measured 1.331–1.471. The band that admits this
  control still refuses that one.

  ## The sign rule, which is the fix that landed in PR #7634

  A band alone admits a control certifying that MORE WORK READS FASTER:
  `rf2-7iqb5` proved it live, capturing `num=-1.194 den=-0.594
  measured=2.0101x band=[1.5076,2.5126] ok=TRUE` against a decreasing
  fixture. [[verdict]] therefore requires the measured ratio to exceed
  1.0 in every round as well as sit in band, and refuses on the sign.

  ## The read-back, which the predecessor claimed and did not perform

  Its `window!` said the clock stops after the last drain and *\"the
  read-back happens afterwards\"*, and then returned elapsed time without
  reading anything (rf2-m6i0's audit of PR #8160). A control that never
  looks at the page can adjudicate a no-op from event and subscription
  work alone.

  So every window here banks TWO cell-addressed probes into
  `rf.bench.hicasso.lane/tally`, outside the clock, and [[rf.bench.hicasso.lane/assert-verified!]] — the
  lane's one meaning of *verified* — turns any miss into a throw and a
  non-zero exit:

      row 5   CHANGED    n must have advanced by exactly one per commit
      row 6   UNCHANGED  n must still read its seed value

  The unchanged probe is the half that makes the pair load-bearing: a
  write that reached every row, or a page rebuilt from a stale seed,
  passes the changed probe alone. Both are addressed by their own
  `data-testid`, so neither can be satisfied by a neighbour's commit.
  `topo/control_witness_dom_cljs_test` mounts a real arm, withholds the
  commit, and asserts this refuses."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.mount :as rf.bench.hicasso.arm1.mount]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.topo.arms :as rf.bench.hicasso.topo.arms]
            [re-frame.bench.hicasso.topo.model :as rf.bench.hicasso.topo.model]
            [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Pre-registered — the whole of the control's arithmetic, before any run
;; ---------------------------------------------------------------------------

(def scale
  "The factor the control multiplies the arm's rendered page by. Two,
  because a control's claim is `THE INSTRUMENT HAS SIGNAL` and two is the
  smallest factor that is unambiguously signal."
  2)

(def stride
  "Every `stride`-th rendered row moves. A STATED CONSTANT, held at both
  halves of the manipulation — this control does not vary it, which is
  the entire difference from the changed-set control it replaces.

  Five, and not one: rows that do NOT move are what the unchanged probe
  reads, and a write touching every row would leave nothing to read. It
  is below [[re-frame.bench.hicasso.topo.model/chunk-size]] so that every
  chunk of the chunked arm contains a moved row at both page sizes, which
  is what keeps that arm's markup proportional to its page."
  5)

(def base-rows
  "`B` for the un-windowed arms' SMALL page; the large page is twice it,
  landing on 1000 — the tournament's own largest size, so the control is
  taken in the regime the clock table most wants to publish."
  500)

(def base-window
  "`w` for the windowed arm's SMALL page — the committed
  [[re-frame.bench.hicasso.topo.model/window-size]] — with the large page
  at twice it. `B` is held at [[virtual-rows]] throughout, so the ONLY
  thing that moves for this arm is the size of the page it renders."
  rf.bench.hicasso.topo.model/window-size)

(def virtual-rows
  "The table the windowed arm windows. Fixed across its manipulation."
  1000)

(def sampling {:warmup 3 :samples 12})
(def rounds 5)

(def batch-k
  "Operations under ONE clock.

  Chrome clamps `performance.now()` to 100 µs, and `rf2-d2tzk` records
  what that does to a narrow row: HD-008's bulk floor read a p50 of about
  0.1 ms — ONE quantum — and a yield correction over it consumed the
  whole window. `rf2-9zysg`'s repair for the narrow row was to batch, and
  this is that repair, not a new idea: `k` commits share one clock, which
  lifts the window clear of the clamp and is NOT the same as summing `k`
  separately-clamped readings."
  20)

(def slack
  "The lane's standing `CONTROL_SLACK` (`shapes/census_clock_run.cjs`),
  applied to a DERIVED prediction under the strict every-round rule."
  0.25)

(def changed-probe
  "A row the write moves — `(mod changed-probe stride)` is zero. Rendered
  by all four arms at both page sizes, which is what makes it a probe
  rather than four probes."
  5)

(def unchanged-probe
  "A row the write does not move. Also rendered by every arm at both
  sizes, and adjacent to [[changed-probe]] so that a page which rebuilt
  the wrong region fails one of the two."
  6)

(defn sizes
  "The two pages `arm`'s control mounts, small first, as `{:b :w}`.

  The windowed arm scales `w`; every other arm scales `b`. See the
  namespace docstring for why that is forced rather than chosen."
  [arm]
  (if (= :virtual arm)
    [{:b virtual-rows :w base-window}
     {:b virtual-rows :w (* scale base-window)}]
    [{:b base-rows :w rf.bench.hicasso.topo.model/window-size}
     {:b (* scale base-rows) :w rf.bench.hicasso.topo.model/window-size}]))

(defn rendered-of
  "How many rows `arm` renders on page `size` — the control's scale
  parameter, read from the model rather than restated here."
  [arm {:keys [b w]}]
  (rf.bench.hicasso.topo.model/rendered-rows arm b w))

(defn predicted
  "`arm`'s predicted factor, from `model/elements-for` at the two rendered
  row counts.

  Not 2.00: the `ul` does not double. 1.9996 for the un-windowed arms
  (5001/2501) and 1.9901 for the windowed one (201/101)."
  [arm]
  (let [[small large] (sizes arm)]
    (/ (rf.bench.hicasso.topo.model/elements-for (rendered-of arm large))
       (rf.bench.hicasso.topo.model/elements-for (rendered-of arm small)))))

(defn band-of
  "`arm`'s registered band — ±[[slack]] of its own [[predicted]]."
  [arm]
  (let [p (predicted arm)]
    {:predicted p :lo (* p (- 1.0 slack)) :hi (* p (+ 1.0 slack))}))

(defn markup-expected
  "The rows of markup a single commit builds on `arm`'s page `size` —
  exact integers, and the DETERMINISTIC half of this control's claim.

  `fine` and `virtual` rebuild only the rows the indexed write moved.
  `coarse` rebuilds its whole view-model and `chunked` rebuilds every
  chunk, because [[stride]] is below `chunk-size` so no chunk is spared.
  All four are therefore proportional to the arm's rendered page, which
  is what [[run-arm!]] checks BEFORE it starts a clock."
  [arm size]
  (let [rendered (rendered-of arm size)]
    (case arm
      (:fine :virtual) (long (Math/ceil (/ rendered stride)))
      (:coarse :chunked) rendered)))

;; ---------------------------------------------------------------------------
;; The verdict — pure, and exported for its own exit-path test
;; ---------------------------------------------------------------------------

(defn verdict
  "Adjudicate one arm's control. STRICT on three counts, any of which
  refuses:

  - **in band**, round-wise rather than pooled, so one bad round refuses
    instead of being averaged away (`census_clock_run/controlVerdict`'s
    rule, and the strict side of the rf2-egdaq split, which kept
    `rf.bench.hicasso.lane/control-verdict`'s overlap rule only for clamp-limited clock
    legs);
  - **positive in sign**, which is PR #7634's fix — a band alone admits a
    control certifying that more work reads faster;
  - **verified**, which is the audit obligation on this file: `0
    unverified of M` probes, or the numbers describe a page nobody read.

  Pure, and exported so the gate arithmetic is checkable without a
  headless Chromium."
  [arm round-ratios {:keys [writes unverified] :as verification}]
  (let [{:keys [predicted lo hi]} (band-of arm)
        rs      (vec round-ratios)
        every?* (fn [p] (and (seq rs) (every? p rs)))
        in-band (every?* (fn [r] (and (>= r lo) (<= r hi))))
        signed  (every?* (fn [r] (> r 1.0)))
        clean   (and (pos? (long (or writes 0))) (zero? (long (or unverified 0))))]
    {:arm          arm
     :rounds       rs
     :predicted    (rf.bench.hicasso.lane/round4 predicted)
     :band         [(rf.bench.hicasso.lane/round4 lo) (rf.bench.hicasso.lane/round4 hi)]
     :verification verification
     :in-band?     in-band
     :positive?    signed
     :verified?    clean
     :ok?          (boolean (and in-band signed clean))
     :why          (cond
                     (empty? rs)   "no rounds — a control that measured nothing cannot certify anything"
                     (not clean)   (str "REFUSED ON THE READ-BACK — " (or unverified 0)
                                        " unverified of " (or writes 0)
                                        " probes; the clock measured a page that did not commit what it claims")
                     (not signed)  "REFUSED ON THE SIGN — a round read the doubled page as no slower, which is a control certifying that more work costs less"
                     (not in-band) (str "REFUSED ON THE BAND — a round fell outside ["
                                        (rf.bench.hicasso.lane/round4 lo) ", " (rf.bench.hicasso.lane/round4 hi) "] around the derived "
                                        (rf.bench.hicasso.lane/round4 predicted))
                     :else         "in band, positive and verified in every round")}))

;; ---------------------------------------------------------------------------
;; One measured window
;; ---------------------------------------------------------------------------

(defn n-at
  "The `n` cell of row `i` inside `container`, as text, or nil.

  Cell-addressed by `data-testid`, not by the row's `data-i`: the row's
  own text node concatenates label, `n`, draft and the button's caption,
  so a probe reading it could be satisfied by any of them moving."
  [container i]
  (some-> (.querySelector container (str "[data-testid=\"n-" i "\"]")) (.-textContent)))

(defn probe-misses
  "Which of the two probes `container` fails, given `commits` commits of
  [[stride]]-indexed writes since the seed. A seq of maps, empty when the
  page committed what it claims.

  Pure arithmetic over the model's own seed: row `i`'s `n` starts at
  `(:n (model/row i))`, and the changed probe advances by exactly one per
  commit because the write is an `inc`."
  [container commits]
  (let [want-changed   (str (+ (:n (rf.bench.hicasso.topo.model/row changed-probe)) commits))
        want-unchanged (str (:n (rf.bench.hicasso.topo.model/row unchanged-probe)))]
    (remove nil?
            [(let [got (n-at container changed-probe)]
               (when-not (= want-changed got)
                 {:probe :changed :row changed-probe :want want-changed :got got}))
             (let [got (n-at container unchanged-probe)]
               (when-not (= want-unchanged got)
                 {:probe :unchanged :row unchanged-probe :want want-unchanged :got got}))])))

(defn window!
  "`batch-k` commits under ONE clock, then — with the clock stopped — read
  the two probes back and BANK them in `t`.

  The read-back is outside the window and after the last drain, which is
  the batched shape [[rf.bench.hicasso.lane/verified-writes!]] documents and defends: no
  macrotask runs between the first write and the last drain, so a commit
  React has parked cannot land inside the window however many microtasks
  pass. What the batch relaxes is microtask-scale lateness, on a
  tolerance the unbatched window already grants.

  Answers the elapsed milliseconds for the whole batch. `committed` is
  the page's running commit count, which the probe arithmetic needs and
  which is why it is a page-scoped atom rather than a local."
  [t {:keys [frame-id container limit committed]}]
  (let [t0 (rf.bench.hicasso.lane/now-ms)]
    (dotimes [_ batch-k]
      (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:topo/bump-indexed limit stride])
      (rf.bench.hicasso.arm1.mount/settle!))
    (let [ms     (- (rf.bench.hicasso.lane/now-ms) t0)
          total  (swap! committed + batch-k)
          misses (probe-misses container total)]
      ;; `rf.bench.hicasso.lane/verified-writes!`'s own `bank!`, over this control's probes:
      ;; one tally, one meaning of "verified", one `assert-verified!`.
      (swap! t (fn [{:keys [of bad]}]
                 {:of (+ of 2) :bad (+ bad (count misses))}))
      (when (seq misses)
        (js/console.error (str ";; PROBE MISS — " (pr-str misses))))
      ms)))

;; ---------------------------------------------------------------------------
;; One arm's control
;; ---------------------------------------------------------------------------

(def ^:private frame-ids
  (into {} (for [arm rf.bench.hicasso.topo.arms/arm-ids tag [:small :large]]
             [[arm tag] (keyword "topo.control" (str (name arm) "-" (name tag)))])))

(defn- mount-page!
  "Seed a frame at `size`, mount `arm` over it, and answer the page map
  the rest of this file passes around."
  [arm tag size]
  (let [{:keys [b w]} size
        frame-id (get frame-ids [arm tag])]
    (rf.bench.hicasso.topo.model/make-frame! frame-id b w)
    (rf.bench.hicasso.topo.model/reseed! frame-id b w)
    (let [handle (rf.bench.hicasso.arm1.mount/root! (rf.bench.hicasso.arm1.mount/fresh-container!) frame-id [(rf.bench.hicasso.topo.arms/view-of arm) {}])]
      {:tag       tag
       :size      size
       :frame-id  frame-id
       :handle    handle
       :container (:container handle)
       :limit     (rendered-of arm size)
       :committed (atom 0)})))

(defn- structure-of
  "`page`'s own structure. `:boundaries` and `:edges` come from a DELTA
  against the runtime's live table, because two pages are mounted at once
  and `rf.bench.hicasso.arm1.runtime/stats` counts every live cell in the process rather than one
  container's."
  [page before]
  (let [{:keys [boundaries edges]} (rf.bench.hicasso.arm1.runtime/stats)
        container (:container page)]
    {:boundaries    (- boundaries (:boundaries before))
     :edges         (- edges (:edges before))
     :elements      (rf.bench.hicasso.lane/element-count container)
     :rendered-rows (.-length (.querySelectorAll container "li.topo-row"))}))

(defn- markup-of
  "Rows of markup ONE commit builds on `page` — measured, on the counters,
  outside every clock. The control refuses if this disagrees with
  [[markup-expected]], which is how a page that is not doing the work its
  arithmetic predicts is caught BEFORE a clock reads it."
  [arm page]
  (rf.bench.hicasso.topo.arms/reset-counters!)
  (rf.bench.hicasso.arm1.runtime/dispatch! (:frame-id page) [:topo/bump-indexed (:limit page) stride])
  (rf.bench.hicasso.arm1.mount/settle!)
  (swap! (:committed page) inc)
  (:markup (rf.bench.hicasso.topo.arms/runs)))

(defn run-arm!
  "One arm's whole control: mount both pages, check each is the arm it
  claims, check the work asymmetry the prediction rests on, then
  interleave the two under the clock.

  Answers `{:verdict :per-round :structure :markup :guard}`. Both pages
  are released before the next arm mounts, so the document holds one
  arm's two pages and never eight."
  [arm]
  (let [[small large] (sizes arm)
        t      (rf.bench.hicasso.lane/tally)
        base   (rf.bench.hicasso.arm1.runtime/stats)
        p-s    (mount-page! arm :small small)
        st-s   (structure-of p-s base)
        mid    (rf.bench.hicasso.arm1.runtime/stats)
        p-l    (mount-page! arm :large large)
        st-l   (structure-of p-l mid)
        want-s (select-keys (rf.bench.hicasso.topo.arms/expected arm (:b small) (:w small))
                            [:boundaries :edges :elements :rendered-rows])
        want-l (select-keys (rf.bench.hicasso.topo.arms/expected arm (:b large) (:w large))
                            [:boundaries :edges :elements :rendered-rows])]
    (try
      (cond
        (not= want-s st-s)
        {:fatal (str arm "'s SMALL page is not the arm it claims: expected "
                     (pr-str want-s) ", mounted " (pr-str st-s))}

        (not= want-l st-l)
        {:fatal (str arm "'s LARGE page is not the arm it claims: expected "
                     (pr-str want-l) ", mounted " (pr-str st-l))}

        :else
        (let [mk-s (markup-of arm p-s)
              mk-l (markup-of arm p-l)
              want-mk-s (markup-expected arm small)
              want-mk-l (markup-expected arm large)]
          (if (or (not= mk-s want-mk-s) (not= mk-l want-mk-l))
            {:fatal (str arm "'s commit does not build the markup its arithmetic "
                         "predicts, so the prediction the clock is about to be judged "
                         "against is not this page's: expected small=" want-mk-s
                         " large=" want-mk-l ", built small=" mk-s " large=" mk-l)}
            (let [pages {:small p-s :large p-l}
                  {:keys [readings samples]}
                  (rf.bench.hicasso.lane/rounds! [{:id :small} {:id :large}]
                                sampling rounds
                                (fn [a] (window! t (get pages (:id a))))
                                (fn [a] (str (name arm) "-" (name (:id a)))))
                  ratios (mapv (fn [r]
                                 (let [p (fn [id] (:p50 (rf.bench.hicasso.lane/summarise (get r id))))]
                                   (rf.bench.hicasso.lane/round4 (/ (p :large) (p :small)))))
                               readings)
                  ;; PUBLISHED BEFORE ADJUDICATED: `assert-verified!` throws, and
                  ;; the evidence a reader needs has to be on the console first.
                  vv     (rf.bench.hicasso.lane/tally-value t)
                  v      (verdict arm ratios vv)
                  gv     (rf.bench.hicasso.lane/guard! samples (str "topo rendered-scale control (" (name arm) ")"))]
              {:verdict   v
               :guard     gv
               :structure {:small st-s :large st-l}
               :markup    {:small mk-s :large mk-l
                           :ratio (rf.bench.hicasso.lane/round4 (/ mk-l mk-s))}
               :per-round (mapv (fn [r] {:small (rf.bench.hicasso.lane/summarise (get r :small))
                                         :large (rf.bench.hicasso.lane/summarise (get r :large))})
                                readings)
               :tally     t}))))
      (finally
        (rf.bench.hicasso.arm1.mount/release! (:handle p-s))
        (rf.bench.hicasso.arm1.mount/release! (:handle p-l))))))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn ^:export -main []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.lane/self-test!)
  (try
    (rf.bench.hicasso.lane/record! :design
                  {:scale scale :stride stride :batch-k batch-k
                   :rounds rounds :sampling sampling :slack slack
                   :probes {:changed changed-probe :unchanged unchanged-probe}
                   :arms (into {} (map (fn [arm]
                                         (let [[s l] (sizes arm)]
                                           [arm {:small s :large l
                                                 :rendered [(rendered-of arm s) (rendered-of arm l)]
                                                 :markup [(markup-expected arm s) (markup-expected arm l)]
                                                 :band (band-of arm)}])))
                                 rf.bench.hicasso.topo.arms/arm-ids)})
    (rf.bench.hicasso.lane/record! :runtime (rf.bench.hicasso.lane/runtime-label))
    (let [results (reduce
                    (fn [acc arm]
                      (let [r (run-arm! arm)]
                        (if-let [f (:fatal r)]
                          (do (set! (.-HICASSO_CONTROL_FAILED js/window) true)
                              (rf.bench.hicasso.lane/fail! f)
                              (reduced (assoc acc arm {:fatal f})))
                          (do (rf.bench.hicasso.lane/record! (keyword (str "control-" (name arm)))
                                            (select-keys r [:verdict :structure :markup :per-round]))
                              (when (:refuse? (:guard r))
                                (set! (.-HICASSO_GUARD_REFUSED js/window) true))
                              (when-not (:ok? (:verdict r))
                                (set! (.-HICASSO_CONTROL_FAILED js/window) true)
                                (js/console.error (str ";; CONTROL REFUSED (" (name arm) ") — "
                                                       (:why (:verdict r)))))
                              ;; The lane's ONE adjudication of a read-back. It throws,
                              ;; and it is called AFTER the record above is published.
                              (rf.bench.hicasso.lane/assert-verified! (:tally r)
                                                     (str "topo rendered-scale control (" (name arm) ")"))
                              (assoc acc arm (:verdict r))))))
                    {}
                    rf.bench.hicasso.topo.arms/arm-ids)]
      (rf.bench.hicasso.lane/record! :summary
                    (into {} (map (fn [[arm v]]
                                    [arm (select-keys v [:predicted :band :rounds :ok? :why])]))
                          results)))
    (catch :default e
      (set! (.-HICASSO_CONTROL_FAILED js/window) true)
      (rf.bench.hicasso.lane/fail! (str "topo control threw: " (or (ex-message e) (.-message e))))))
  (rf.bench.hicasso.lane/done!))
