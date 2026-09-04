(ns re-frame.bench.hicasso.direct-return-clock-app
  "THE CLOCK HALF OF THE DIRECT-RETURN DELTA (rf2-5yn9).

  `rf2-hic-033` took the DETERMINISTIC half — budgets.md D10–D13, entries
  into `codec/vec->element`, `codec/convert-props`, `intent/lower-prop`
  and `controlled/install!`, each read against the crossing's own floor.
  It did not take the clock: the machine available to it was carrying
  three concurrent compiles, and a duration measured beside three
  compiles is a number nobody can attribute. This entry is the clock, on
  the P0 lane, in a pinned quiet window.

  D10–D13 are CITED, never re-derived here. A second source for one
  number is a second thing to drift, and the counts are the half that
  needs no window.

  ## The pair, and why it is transcribed rather than required

  [[hiccup-body]] and [[direct-body]] are `direct_return_cljs_test`'s
  pair, verbatim: the same props in the same order, the same two ambient
  reads, the same data, the same spelled-out class shorthand. The
  original lives in
  `implementation/hicasso/test/re_frame/hicasso/direct_return_cljs_test.cljs`,
  which is a `*-cljs-test` namespace on the node lane. Requiring it from
  an `:advanced` `:browser` bundle would pull `cljs.test` and the whole
  suite into the measured page, so the pair is COPIED.

  **A copy is a thing that can drift, and the fairness gate is what
  stops it drifting silently.** [[parity!]] mounts both arms and compares
  their canonical DOM before a clock is read; a transcription that had
  lost a prop, a read or a class would part the two pages and the run
  refuses before it measures anything. The gate is also driven to FAIL
  on purpose ([[parity-can-fail?]]), because an equality that cannot
  answer false is not a gate.

  It is MOUNTED-DOM equality here, through `rf.bench.hicasso.lane/canonical`, and that is
  a STRONGER claim than the deterministic half made. That file states its
  own limit explicitly: it settles markup under `renderToStaticMarkup`
  and says nothing about a mounted node's properties or what a commit
  does — the axes a controlled field and a native input genuinely differ
  on. Taking the equality in the browser is this arm's own obligation and
  it is taken before the first sample.

  ## What is measured

  One page written twice, at [[boundaries]] boundaries, mounted into a
  fresh container inside ONE `react-dom/flushSync` window
  (`rf.bench.hicasso.lane/mount-batch!`). The window therefore holds the substrate's
  element construction AND React's render, commit and DOM mutation —
  which is the point: the escape removes CLJS lowering from a cost React
  otherwise dominates, and a figure that excluded React's share would
  overstate what an author gets.

    :floor    the crossing carrying nothing — [[floor-body]] answers nil,
              so the page is the wrapper and no more. Every ratio is
              against the floor measured in THAT round, because this box
              drifts across rounds by more than the effect being measured.
    :hiccup   the Rung 1 spelling
    :direct   the Rung 3 spelling
    :ctl-2x   THE POSITIVE CONTROL — the hiccup arm's own page, mounted
              TWICE inside one window. Not a doubled page: literally the
              same operation performed twice, so the per-sample additive
              constants double with it and the prediction is a clean
              2.00x rather than a modelled one. `rf2-jcm3p` records the
              other shape UNDERSHOOTING (1.8173x over seven runs) for
              exactly the constant this one doubles.

  `:floor` and `:ctl-2x` are `:parity-exempt?`: one builds an empty page
  and one builds two pages, both on purpose, and folding either into the
  equality would make the fairness gate a permanent failure.

  ## The control is adjudicated STRICTLY, and per round (rf2-gsn62)

  `rf.bench.hicasso.lane/control-verdict-strict` decides it: every round's `:ctl-2x` /
  `:hiccup` ratio must sit inside ±25% of 2.00x, and one bad round
  refuses the run however good the others were. This instrument is
  entitled to that rule and the coarse-leg rows are not — the 2026-07-31
  ruling keeps the weaker OVERLAP rule for legs sitting on Chrome's
  100 µs clamp and names a batched window clear of the quantum as its own
  revisit trigger. This one reads ~2.25 ms judged and ~4.3 ms control (the
  figures S8 publishes), twenty to forty-three quanta clear, so a round
  outside the band here is not the clock. The floor's own leg is coarse
  and does not enter: the control divides one floor-normalised ratio by
  another taken in the SAME round, so the floor cancels exactly.

  The adjudication is also PER ROUND rather than aggregate. The two runs
  taken for S8 compared `2.0 x` the ACROSS-ROUND median of `:hiccup`
  against the ACROSS-ROUND range of `:ctl-2x`, which never puts a round
  beside its own denominator — a round whose judged leg ran fast was
  measured against everybody else's. That shape also recorded only the
  aggregate, so neither run's strict verdict is recoverable and none is
  claimed; this file now records `:per-round` for the control as well as
  for the escape, so the next run can be re-adjudicated under either rule
  WITHOUT re-running the window.

  ## The published figure

  `rf.bench.hicasso.lane/ratio-between :direct :hiccup` over the per-round floor-normalised
  ratios — direct-return mount time as a fraction of the hiccup spelling's,
  with its range and its `:straddles-1?` flag. A range that includes 1.0
  means INDISTINGUISHABLE and the row must say so rather than quote the
  mean as a win. That is the reading budgets.md §4 takes, and C8's benefit
  threshold (>= 20% recovered, or >= 2 ms p95, or a failed user-visible
  budget converted) is adjudicated against it.

  Owner: rf2-5yn9. Instrument: `lane.cljs`, ridden through `run.cjs` on the
  existing `:hicasso-bench` build id via `HICASSO_INIT_FN` — no
  `shadow-cljs.edn` edit, which is what the driver's `--config-merge`
  exists for."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            ["react" :as react]))

(def frame-id ::direct-return-clock)

(def ^:private boundaries
  "Boundaries per page. Large enough that the per-mount constants
  (`createRoot`, the container, the root crossing) are a small share of
  the window and that the window clears Chrome's 100 us
  `performance.now()` clamp by orders of magnitude; small enough that a
  round is seconds rather than minutes."
  200)

;; ---------------------------------------------------------------------------
;; Subscriptions and data — `direct_return_cljs_test`'s, scaled to N ids
;; ---------------------------------------------------------------------------
;;
;; The pair reads `[:dr/label id]` and `[:dr/tags id]`. Both survive
;; verbatim; only the SEED grows, because a page of one boundary cannot
;; be timed. Labels are per-row so the far-end read-back distinguishes a
;; page that rendered from a page that rendered its prefix.

(rf/reg-sub :dr/label (fn [db [_ id]] (get-in db [:labels id])))
(rf/reg-sub :dr/tags  (fn [db [_ id]] (get-in db [:tags id])))

(rf/reg-event :dr/seed (fn [_ [_ db]] {:db db}))

(defn- seed-db
  [n label-of]
  {:labels (into {} (map (fn [i] [i (label-of i)])) (range n))
   :tags   (into {} (map (fn [i] [i ["clj" "react"]])) (range n))})

(defn- default-label [i] (str "quarterly-" i))
(defn- mutated-label [i] (str "annual-" i))

;; ---------------------------------------------------------------------------
;; The pair — `direct_return_cljs_test`'s, verbatim
;; ---------------------------------------------------------------------------
;;
;; The prop keys are written in the SAME ORDER on both sides and the class
;; shorthand is spelled out rather than folded, because both sides
;; normalise through the one `impl.slot/prop-name` rule. Same rule plus
;; same order is what makes the equality a property of the source rather
;; than something to discover.

(defn hiccup-body
  "The ordinary Rung 1 spelling. Two ambient reads, an intent vector at a
  callback slot, and a controlled field."
  [{:keys [id]}]
  (let [label (rf.hicasso/sub [:dr/label id])
        n     (count (rf.hicasso/sub [:dr/tags id]))]
    [:div {:class "row" :on-click [:dr/picked id]}
     [:span {:class "label"} (str label " " n)]
     [:input {:class "field" :value label :on-input [:dr/edited id ::rf.hicasso/value]}]]))

(defn direct-body
  "The Rung 3 spelling of the same page. The reads are the boundary's,
  unchanged. A raw React element passes through the codec untouched, so
  there is no intent lowering — the callback slot holds an ordinary
  function — and no controlled repair, so the field's echo is the
  author's to write."
  [{:keys [id]}]
  (let [label (rf.hicasso/sub [:dr/label id])
        n     (count (rf.hicasso/sub [:dr/tags id]))]
    (react/createElement "div" #js {:className "row" :onClick (fn [_] nil)}
      (react/createElement "span" #js {:className "label"} (str label " " n))
      (react/createElement "input" #js {:className "field" :value label
                                        :onChange (fn [_] nil)}))))

(defn floor-body
  "The crossing carrying nothing. A boundary is reached through a hiccup
  vector whatever its body answers, so the floor is the crossing and not
  zero."
  [_]
  nil)

(rf.hicasso/defview hiccup-arm [props] (hiccup-body props))
(rf.hicasso/defview direct-arm [props] (direct-body props))
(rf.hicasso/defview floor-arm  [props] (floor-body props))

;; ---------------------------------------------------------------------------
;; The page — identical for every arm but the body
;; ---------------------------------------------------------------------------
;;
;; Children are spliced into a VECTOR rather than lowered as a seq, so no
;; arm meets the key diagnostic and the two arms cannot differ on it. The
;; wrapper `div` is common to all three and is the floor's whole page.

(defn- page [view]
  (into [:div {:class "page"}]
        (map (fn [i] [view {:id i}]))
        (range boundaries)))

(def ^:private page-elements
  "Elements per mounted page. The wrapper, plus `div`/`span`/`input` per
  boundary — the arithmetic written down before the run, so a mount that
  rendered a prefix cannot be banked as a sample."
  (+ 1 (* 3 boundaries)))

(def ^:private floor-elements 1)

;; ---------------------------------------------------------------------------
;; Arms
;; ---------------------------------------------------------------------------

(defn- mount-page
  [view]
  (fn [container _props _n] (rf.hicasso/mount! container {:frame frame-id} (page view))))

(defn- unmount-page [handle] (rf.hicasso/unmount! handle))

(def ^:private arms
  [{:id :floor  :k 1 :elements floor-elements :parity-exempt? true
    :mount (mount-page floor-arm)  :unmount unmount-page}
   {:id :hiccup :k 1 :elements page-elements
    :mount (mount-page hiccup-arm) :unmount unmount-page}
   {:id :direct :k 1 :elements page-elements
    :mount (mount-page direct-arm) :unmount unmount-page}
   {:id :ctl-2x :k 2 :elements page-elements :parity-exempt? true
    :mount (mount-page hiccup-arm) :unmount unmount-page}])

(def ^:private sampling
  "THE LANE'S PAGE-MOUNT SAMPLING, which this arm now runs (rf2-h904p).

  It ran `{:warmup 3 :samples 6}`, and the arm-order guard refused the S8
  window at exit 2 on a PHASE contrast: `:hiccup`'s first third read
  `2.3000 ms [2.1000-4.9000]` against a last third of
  `1.9000 ms [1.7000-2.0000]`, `1.2105x` apart with disjoint ranges, while
  the other two page-mounting arms drifted `1.2338x` (`:ctl-2x`) and
  `1.2000x` (`:direct`) and passed only because their ranges overlapped.
  Every by-predecessor contrast passed on every arm. A decay common to all
  three mounting arms and absent from the by-predecessor axis is a
  POSITION effect, and three warm-up mounts is what this file was giving
  it.

  THREE SITS BELOW THE STEP THE LANE ITSELF RECORDS. `lane.cljs`'s live
  reproduction read one control `10.32 10.26 10.26 10.26 10.33 10.28` and
  then `8.12` for ever — a +27% step falling after the SIXTH execution of
  the site, with nothing varying but how many times it had run. At
  `:warmup 3` a step of that shape lands INSIDE the measured samples and
  splits round one, which is the contrast the guard reported; at
  `:warmup 8` it lands inside the warm-up and no measured sample straddles
  it. `{:warmup 8 :samples 12}` is also what every other harness riding
  [[rf.bench.hicasso.lane/mount-batch!]] already runs. That set is checkable and small —
  `(rf.bench.hicasso.lane/mount-batch!` has five call sites on this lane, this file,
  `amp_merge_clock_app`, `coldmount_app`, `p0_converge_app` and
  `p0_reagent_app` — and the last three all sample at 8/12, so the two
  clocks were the lane's only batched-mount outliers. This arm stops
  being one rather than acquiring a figure of its own.

  WHAT WAS DELIBERATELY NOT MOVED. Not the guard's tolerance, which is not
  the arm's to move. Not [[boundaries]]: lengthening the flushSync window
  is the third move `run.cjs` names, but it would change the SUBJECT
  budgets.md S8 already publishes — `on a 200-boundary mount` — and make
  the next window incomparable to run 1 for a reason that has nothing to
  do with the refusal. And not the arm count, which the same repair on
  `amp_merge_clock_app` settles with the schedule arithmetic.

  This re-tunes the conditions under which S8 is measured, so run 1's
  published figure is NOT comparable to what this file will read next.
  That was already the position: the refusal left S8 waiting on a fresh
  window, and this is the rig change it was waiting for."
  {:warmup 8 :samples 12})

(def ^:private rounds 5)
(def ^:private control-slack 0.25)

;; ---------------------------------------------------------------------------
;; Read-back — every measured mount, at its own far end
;; ---------------------------------------------------------------------------

(defn- far-end
  "The LAST `.label` span's text, or nil. Reading the far end and not the
  first is the difference between proving the page rendered and proving
  it started: a mount that rendered its prefix passes an element count
  taken at index 0 and fails here."
  [container]
  (let [ns' (.querySelectorAll container "span.label")
        c   (.-length ns')]
    (when (pos? c) (.-textContent (.item ns' (dec c))))))

(defn- expected-far-end []
  (str (default-label (dec boundaries)) " 2"))

(defn- verified?
  "This arm's own arithmetic, on this mount. The floor has no `.label` to
  read, so its far-end probe is its element count and nothing else — a
  probe that cannot pass manufactures a defect and hides real ones behind
  it."
  [arm container]
  (and (= (:elements arm) (rf.bench.hicasso.lane/element-count container))
       (or (= :floor (:id arm))
           (= (expected-far-end) (far-end container)))))

;; ---------------------------------------------------------------------------
;; The fairness gate
;; ---------------------------------------------------------------------------

(defn parity!
  "Mount every arm at once and compare the judged arms' canonical DOM.
  Answers `rf.bench.hicasso.lane/parity`'s verdict with the mounts already released."
  []
  (let [{:keys [mounts] :as p} (rf.bench.hicasso.lane/parity arms nil)]
    (doseq [m mounts] (rf.bench.hicasso.lane/release! m))
    p))

(defn parity-can-fail?
  "THE MUTATION. Move the data under one arm and the gate must part the
  two pages; an equality that cannot answer false is not a gate. Reseeds
  to the run's own labels afterwards, so nothing downstream is measured
  on the mutated page."
  []
  (let [before (let [m (rf.bench.hicasso.lane/mount-arm! (nth arms 1) nil)
                     s (rf.bench.hicasso.lane/canonical (:container m))]
                 (rf.bench.hicasso.lane/release! m)
                 s)]
    (rf/with-frame frame-id
      (rf/dispatch-sync [:dr/seed (seed-db boundaries mutated-label)]))
    (let [after (let [m (rf.bench.hicasso.lane/mount-arm! (nth arms 2) nil)
                      s (rf.bench.hicasso.lane/canonical (:container m))]
                  (rf.bench.hicasso.lane/release! m)
                  s)]
      (rf/with-frame frame-id
        (rf/dispatch-sync [:dr/seed (seed-db boundaries default-label)]))
      (not= before after))))

;; ---------------------------------------------------------------------------
;; The measured window
;; ---------------------------------------------------------------------------

(defn- measure-one!
  [tally arm]
  (let [{:keys [ms mounts]} (rf.bench.hicasso.lane/mount-batch! arm nil (:k arm))]
    (doseq [m mounts]
      (let [ok? (verified? arm (:container m))]
        (swap! tally (fn [{:keys [of bad]}]
                       {:of (inc of) :bad (if ok? bad (inc bad))}))))
    (doseq [m mounts] (rf.bench.hicasso.lane/release! m))
    ms))

(defn- fmt [x n] (.toFixed (double x) n))

(defn- measurement-method []
  (str "a SAMPLE is " boundaries " boundaries mounted into a fresh container "
       "inside ONE react-dom/flushSync window, containers created and attached "
       "BEFORE the clock starts; the :ctl-2x arm's sample is the SAME operation "
       "performed TWICE inside one window, so its per-sample constants double "
       "with its work and its prediction is 2.00x by construction rather than by "
       "model — adjudicated by rf.bench.hicasso.lane/control-verdict-strict, which requires EVERY "
       "round's ctl-2x/hiccup ratio to sit inside ±"
       (.toFixed (* 100.0 control-slack) 0)
       "% of it rather than merely the range, and records the per-round values so "
       "the run can be re-adjudicated without being re-run. Arms interleaved at "
       "the SAMPLE level under the lane's rotating AND "
       "REFLECTING schedule, so no arm always follows the same neighbour; "
       rounds " rounds of " (:warmup sampling) " warm-up + " (:samples sampling)
       " samples per arm per round; every measured mount read back out of the DOM "
       "against THAT ARM'S OWN element count and at its own FAR END; every ratio "
       "against the floor measured in THAT round. Nothing runs inside an `act` "
       "environment."))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(defn ^:export -main []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (rf/make-frame {:id frame-id
                          :initial-events [[:dr/seed (seed-db boundaries default-label)]]})
          (rf/with-frame frame-id
            (rf/dispatch-sync [:dr/seed (seed-db boundaries default-label)]))
          ;; THE FAIRNESS GATE, before a clock is read. Both halves, and
          ;; both fatal: the arms must agree, and the comparison must be
          ;; known able to disagree.
          (let [p         (parity!)
                can-fail? (parity-can-fail?)]
            (rf.bench.hicasso.lane/record! :direct-return-clock-parity
                          {:agree?    (:agree? p)
                           :disagree  (:disagree p)
                           :counts    (:counts p)
                           :can-fail? can-fail?
                           :bytes     (rf.bench.hicasso.lane/utf8-bytes (or (:reference p) ""))})
            (when-not (:agree? p)
              (throw (ex-info (str "FAIRNESS GATE: the two arms do not build the same "
                                   "mounted page, so no ratio between them is about the "
                                   "lowering. Disagreeing arms: " (pr-str (:disagree p)))
                              {:counts (:counts p)})))
            (when-not can-fail?
              (throw (ex-info (str "FAIRNESS GATE: the canonical-DOM comparison did NOT "
                                   "part two pages built from different data. An equality "
                                   "that cannot answer false is not a gate, and the "
                                   "agreement above is worth nothing.")
                              {}))))
          (rf.bench.hicasso.lane/assert-teardown-clean! "the fairness gate")
          (.then (rf.bench.hicasso.lane/settle!) (fn [_] nil))))
      (.then
        (fn [_]
          (let [baseline (rf.bench.hicasso.lane/residue frame-id)
                tally    (rf.bench.hicasso.lane/tally)
                {:keys [readings samples]}
                (rf.bench.hicasso.lane/rounds! arms sampling rounds (partial measure-one! tally))
                norm     (mapv #(rf.bench.hicasso.lane/normalise % :floor) readings)
                ratios   (mapv :ratio norm)
                summ     (rf.bench.hicasso.lane/across-rounds ratios)
                p50s     (mapv :p50 norm)
                abs      (into {}
                               (map (fn [{:keys [id]}]
                                      [id (rf.bench.hicasso.lane/summarise (mapv #(get % id) p50s))]))
                               arms)
                escape   (rf.bench.hicasso.lane/ratio-between ratios :direct :hiccup)
                gv       (rf.bench.hicasso.lane/guard! samples "direct-return clock arms (in-page ms)")
                ;; THE POSITIVE CONTROL, per round and strictly (rf2-gsn62).
                ;; `:ctl-2x` is `:hiccup`'s own operation performed twice in
                ;; one window, so 2.00x is arithmetic rather than a model,
                ;; and dividing each round by ITS OWN `:hiccup` leaves the
                ;; floor and the round's drift out of it.
                ctl-ratio (rf.bench.hicasso.lane/ratio-between ratios :ctl-2x :hiccup)
                ctl      (rf.bench.hicasso.lane/control-verdict-strict
                           2.0 (:per-round ctl-ratio) control-slack)
                tv       (rf.bench.hicasso.lane/tally-value tally)]
            (rf.bench.hicasso.lane/assert-teardown-clean! "the measured rounds")
            (rf.bench.hicasso.lane/record! :direct-return-clock
                          {:benchmark   :hicasso.P0/direct-return-clock
                           :bead        "rf2-5yn9"
                           :cites       "budgets.md D10-D13 (rf2-hic-033) — the deterministic half, NOT re-derived here"
                           :grade       :distributional
                           :runtime     (rf.bench.hicasso.lane/runtime-label)
                           :boundaries  boundaries
                           :elements    {:floor floor-elements :page page-elements}
                           :design      {:rounds rounds :sampling sampling}
                           :method      (measurement-method)
                           :absolute-ms abs
                           :ratio-to-floor summ
                           :escape      escape
                           :control     ctl
                           :writes      tv})
            (js/console.log ";; ==== DIRECT-RETURN CLOCK (rf2-5yn9) ====")
            (js/console.log (str ";;   " boundaries " boundaries/page, " page-elements
                                 " elements; " rounds " rounds x ("
                                 (:warmup sampling) "+" (:samples sampling) ")"))
            (doseq [{:keys [id]} arms]
              (let [a (get abs id)
                    r (get summ id)]
                (js/console.log
                  (str ";;   " (name id) ": " (fmt (:p50 a) 4) " ms/mount ["
                       (fmt (:min a) 4) " - " (fmt (:max a) 4) "]  ratio-to-floor "
                       (fmt (:mean r) 4) " [" (fmt (:min r) 4) " - " (fmt (:max r) 4) "]"))))
            (js/console.log
              (str ";;   ESCAPE direct/hiccup: " (fmt (:mean escape) 4)
                   "x [" (fmt (:min escape) 4) " - " (fmt (:max escape) 4) "]"
                   (if (:straddles-1? escape)
                     "  <- STRADDLES 1.0: INDISTINGUISHABLE"
                     "  <- range excludes 1.0")))
            (js/console.log (str ";;   per-round: " (pr-str (:per-round escape))))
            (js/console.log (str ";;   control (" (name (:rule ctl)) ", ctl-2x/hiccup): "
                                 (:why ctl)))
            (js/console.log (str ";;   control per-round: " (pr-str (:per-round ctl))))
            (js/console.log (str ";;   writes: " (:unverified tv) " unverified of "
                                 (:writes tv)))
            (when (pos? (:unverified tv))
              (throw (ex-info (str "READ-BACK: " (:unverified tv) " of " (:writes tv)
                                   " measured mounts did not match their own arithmetic")
                              tv)))
            (when (:refuse? gv)
              (set! (.-HICASSO_GUARD_REFUSED js/window) true))
            (when-not (:ok? ctl)
              (set! (.-HICASSO_CONTROL_FAILED js/window) true))
            (.then (rf.bench.hicasso.lane/settle!)
                   (fn [_]
                     (rf.bench.hicasso.lane/assert-residue! baseline frame-id "the measured rounds")
                     (rf.bench.hicasso.lane/done!))))))
      (.catch (fn [e]
                (rf.bench.hicasso.lane/fail! (or (some-> e .-message) (str e)))
                (rf.bench.hicasso.lane/done!)))))
