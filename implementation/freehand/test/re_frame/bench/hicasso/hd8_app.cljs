(ns re-frame.bench.hicasso.hd8-app
  "HD-008's `:advanced` entry — the composed donor arm's published run
  (rf2-2rtt6.7).

  One bundle, three runs. `?adapter=uix|reagent|slim` picks which adapter
  `rf/init!` installs, because Spec 006 allows exactly one per process and
  the two Reagent paths need the ratom spine while the frontier arm and
  both donor rungs ride React hooks. Everything else — the arms, the
  witnesses, the parity gate, both clocks — is
  [[re-frame.bench.hicasso.hd8-rows]]'s, unchanged, so the method is one
  thing a reader checks once.

  ## Why the numbers come from here and not from a test namespace

  HD-012: every bar-relevant number is a BROWSER number taken from an
  `:advanced` build with `goog.DEBUG false` — the artefact a consumer
  ships. Development builds keep the Spec 009 instrumentation seam, schema
  validation and trace emission live, all of which sit on the subscription
  path this measures, and no Reagent path has a counterpart to any of
  them; a development ratio would report a cost no user pays, in one
  direction only. `:advanced` also cannot compile shadow's `:browser-test`
  target, so the reading needs a plain `:browser` entry. This is it.

  ## The order of operations is the method

  Parity first, and if parity fails the run STOPS — a clock read over arms
  building different pages is not a slow result, it is a wrong one. Then
  the positive control, then the lowering correctness check, then the
  clocks. Nothing is published from a run whose gates did not pass.

  Built and driven by
  `implementation/freehand/test/re_frame/bench/hicasso/hd8_run.cjs`, on
  rf2-2rtt6.2's `:hicasso-bench` build id.

  Normative owner: `docs/design/hicasso/decisions.md` HD-008."
  (:require [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.adapter.reagent-slim :as slim-adapter]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.hd8-rows :as rows]
            [re-frame.bench.hicasso.hd8-witnesses :as w]))

(def ^:private rounds 6)
(def ^:private mount-sampling {:warmup 4 :samples 12})
(def ^:private write-sampling {:warmup 3 :samples 10})
(def ^:private control-sampling {:warmup 3 :samples 8})
;; How many times the clock's grain is observed. Each observation spins until
;; `performance.now()` advances, so the whole control costs `n` ticks — about
;; 20 ms at a 100 µs grain — and it runs once, outside every measured window.
(def ^:private clock-resolution-samples 200)

;; ---------------------------------------------------------------------------
;; Publication
;; ---------------------------------------------------------------------------

(defn- fail! [why]
  (set! (.-HD8_ERROR js/window) (str why))
  (js/console.error (str ";; HD8 FAILED — " why))
  nil)

(defn- record! [k v]
  (let [acc (or (.-HD8_RESULTS js/window) #js {})]
    (aset acc (name k) (pr-str v))
    (set! (.-HD8_RESULTS js/window) acc)
    ;; Echoed to the console as EDN as well as parked on the window: the
    ;; console is what the browser lane captures, and a record that exists
    ;; only in a page variable is lost the moment the page closes.
    (js/console.log (str ";; HD8 " (name k) "\n" (pr-str v)))
    v))

(defn- samples!
  "Park one row's PER-SAMPLE records as plain JS for the driver's
  arm-order guard.

  They ride a separate channel from the EDN because the guard is a Node
  module (`order_guard.cjs`) that partitions each arm's samples by what
  ran before them and by where in the run they were taken. An EDN blob the
  driver would have to parse is a reader it does not have; and the samples
  are bulky enough that inlining them would bury the record a human reads."
  [row-name samples]
  (let [acc (or (.-HD8_SAMPLES js/window) #js [])]
    (.push acc #js {"row" (name row-name) "samples" (clj->js samples)})
    (set! (.-HD8_SAMPLES js/window) acc)
    nil))

(defn- pack-bands
  "A per-figure RANGE map packed as plain JS for the driver.

  Shared by [[summary!]] and [[correction!]] rather than private to the
  first: a `:corrected` verdict's bands ride the exact shape the unadjusted
  ones do, so the driver reads both with ONE reader and the cross-run table
  cannot end up labelling one band in a format the other lacks."
  [m]
  (clj->js
    (into {} (map (fn [[k v]]
                    [(name k)
                     (if (:unpublished v)
                       {"unpublished" (name (:unpublished v))
                        "unverified"  (:unverified v)
                        "of"          (:of v)}
                       {"min" (:min v) "max" (:max v)
                        "straddles1" (boolean (:straddles-1? v))})]))
          m)))

(defn- summary!
  "Park one row's floor-normalised RANGE per arm, plus its head-to-head
  ranges, in a shape the driver can read without an EDN reader.

  The range and not the mean, everywhere: the house rule is that
  overlapping ranges mean indistinguishable, and a table of means invites
  exactly the reading the rule forbids."
  [row-name r]
  (let [acc (or (.-HD8_SUMMARY js/window) #js {})]
    (aset acc (name row-name)
          #js {"vsFloor" (pack-bands (:summary r))
               "headToHead" (pack-bands (:head-to-head r))})
    (set! (.-HD8_SUMMARY js/window) acc)
    nil))

(defn- record-row! [k r]
  (samples! k (:samples r))
  (summary! k r)
  (record! k (dissoc r :samples)))

(defn- correction!
  "Park one write row's yield-correction VERDICT — and, on `:corrected`,
  BOTH of its bands — where the driver can read them without an EDN
  reader, so the cross-run table can stamp the row.

  A corrected row that appears in the table looking uncorrected is the
  exact fault this contract exists to prevent, and the table is what a
  reader copies a figure out of. That argument covers the ENDPOINTS too:
  this export used to carry the verdict and not the corrected bands, so a
  `:corrected` run announced that both bands publish while the copyable
  table could print only the unadjusted one (rf2-b69lw, from the PR #7282
  audit).

  The corrected bands arrive here already wearing the original row's
  publication mask (`hd8-rows`'s `inherit-publication-mask`): an arm the
  DOM read-back unpublished carries its `:unpublished` marker in the
  corrected summary too, and [[pack-bands]] exports the marker exactly as
  it does for the unadjusted band — one reader, one shape, no numeric
  band for a figure the read-back refused (rf2-b69lw, from the PR #7295
  audit). The driver's table holds the same line from its own side."
  [row-name v]
  (let [acc (or (.-HD8_CORRECTION js/window) #js {})
        o   #js {"verdict" (name (:verdict v))
                 "reason"  (some-> (:reason v) name)
                 "bound"   (:bound v)
                 "why"     (:why v)}]
    (when (= :corrected (:verdict v))
      (aset o "summaryCorrected" (pack-bands (:summary-corrected v)))
      (aset o "headToHeadCorrected" (pack-bands (:head-to-head-corrected v))))
    (aset acc (name row-name) o)
    (set! (.-HD8_CORRECTION js/window) acc)
    nil))

(defn- done! [] (set! (.-HD8_DONE js/window) true))

;; ---------------------------------------------------------------------------
;; Which adapter this run installs
;; ---------------------------------------------------------------------------

(defn- query-adapter []
  (let [s (some-> js/window .-location .-search)]
    (cond
      (nil? s)                            :uix
      (re-find #"adapter=reagent-slim" s) :slim
      (re-find #"adapter=slim" s)         :slim
      (re-find #"adapter=reagent" s)      :reagent
      :else                               :uix)))

(defn- install! [which]
  (rf/init! (case which
              :reagent reagent-adapter/adapter
              :slim    slim-adapter/adapter
              uix-adapter/adapter))
  which)

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn- run-all! []
  (let [which    (query-adapter)
        _        (install! which)
        arm-ids  (get rows/arm-ids-for which)
        write-ids (get rows/write-arm-ids-for which)
        ;; THE CLOCK'S OWN GRAIN, taken in the run it governs. Every page in
        ;; this studio asserted "Chrome clamps performance.now() to 100 µs"
        ;; and then reasoned against that constant; the write rows' floor is
        ;; a single commit and sits ON it, so the number decides whether
        ;; those rows have a magnitude at all and is measured rather than
        ;; quoted (rf2-d2tzk).
        clock    (rows/clock-resolution! clock-resolution-samples)]
    (lane/leave-act-environment!)
    (doseq [id arm-ids] (rows/ensure-frame! id))
    (record! :method (rows/method-record which arm-ids write-ids rounds mount-sampling write-sampling))
    (record! :clock-resolution clock)
    (set! (.-HD8_CLOCK js/window) (clj->js clock))

    ;; ---- gate 0: the correction contract's own self-test -------------------
    ;; Before anything is measured, because a harness that gets `false` here
    ;; may measure nothing — the same placement, and the same argument, as
    ;; the arm-order guard's self-test in `hd8_run.cjs`.
    ;;
    ;; It exists because THE BRANCH THAT MATTERS ALMOST NEVER FIRES LIVE: the
    ;; harness asymmetry reads 0.0 on most runs of this host, so a run cannot
    ;; be relied on to exercise a refusal, and a gate nobody has watched
    ;; refuse is not evidence. Fixtures replayed through the live rule are.
    ;;
    ;; It THROWS rather than recording a failure and reading on: `-main`'s
    ;; `catch` is the fail-closed path (`assert-teardown-clean!` uses it for
    ;; the same reason), and a `when-not` here would print the failure and
    ;; then measure the whole plan anyway.
    (let [st (rows/yield-correction-self-test)]
      (record! :yield-correction-self-test st)
      ;; Also on a JS-readable channel, so the driver can fail on it instead
      ;; of parsing EDN it has no reader for.
      (set! (.-HD8_CORRECTION_SELFTEST js/window)
            (clj->js {"ok" (boolean (:ok? st))
                      "checks" (mapv (fn [c] {"name" (:name c) "ok" (:ok c) "detail" (:detail c)})
                                     (:checks st))}))
      (when-not (:ok? st)
        (throw (ex-info (str "the yield-correction contract failed its own self-test — the gate "
                             "that decides whether a write row may be published does not agree "
                             "with its recorded fixtures, so nothing may be measured: "
                             (pr-str (remove :ok (:checks st))))
                        {:checks (:checks st)}))))

    ;; ---- gate 0b: the two codecs are DEMONSTRABLY different ---------------
    ;; The fourth arm's whole figure is `donor-fh / donor-r1`, and a reading
    ;; of 1.0 there means nothing unless the two codec doors are known to be
    ;; different implementations — the parity gate cannot say so, because
    ;; making the arms agree is its entire purpose (rf2-2rtt6.29). Fatal, and
    ;; before anything is measured, on the same argument as `parity-can-fail?`.
    (let [cd (w/codecs-differ?)]
      (record! :codecs-differ cd)
      (set! (.-HD8_CODECS_DIFFER js/window) (clj->js cd))
      (when-not (:ok? cd)
        (throw (ex-info (str "the two hiccup codecs did not answer differently on a form they "
                             "are known to treat differently (" (:probe cd) ") — so a "
                             "donor-fh / donor-r1 ratio could not be told apart from one arm "
                             "running one codec twice, and nothing may be measured: "
                             (pr-str cd))
                        {:codecs-differ cd}))))

    ;; ---- the fairness gate, before any clock ------------------------------
    (let [problems (rows/parity-problems arm-ids)]
      (if (seq problems)
        (do (record! :parity {:ok? false :problems problems})
            (fail! (str "canonical-DOM parity failed — every figure below it would be "
                        "a comparison of two different pages: " (pr-str problems)))
            (done!))
        (let [can-fail? (rows/parity-can-fail? arm-ids)]
          (record! :parity {:ok? true :can-fail? can-fail?
                            :note (str "every arm built the same page, compared as canonical "
                                       "DOM with attribute names sorted; the same comparison "
                                       "at two different sizes answers false, so the gate is "
                                       "not passing vacuously")})
          (if-not can-fail?
            (do (fail! (str "the parity comparison cannot answer false — a gate nobody has "
                            "watched fail is not evidence"))
                (done!))

            ;; ---- the positive control, then the lowering check -------------
            (let [pc (record! :positive-control (rows/positive-control! 3 control-sampling))]
              (if-not (:within? pc)
                ;; FAIL CLOSED. This used to `console.warn` and then measure
                ;; everything anyway, so a run whose instrument could not see
                ;; a change its own arithmetic PREDICTS still published a full
                ;; clock table and still exited 0 — and the warning sat in a
                ;; console log nobody reads beside numbers that looked fine
                ;; (rf2-f5roa, from the PR #7263 audit). A control is the row
                ;; that separates `this arm is cheap` from `the instrument is
                ;; not running`; if it misses, nothing measured after it is a
                ;; measurement, so nothing after it runs.
                (do (fail! (str "the positive control missed its prediction — predicted "
                                (:predicted pc) "x, measured " (pr-str (:measured pc))
                                " against a tolerance of " (:tolerance pc)
                                ". The instrument did not see a change its own arithmetic "
                                "says it must, so no clock figure in this run would be "
                                "reportable and none was taken."))
                    (done!))
                (-> (if (rows/donor-run? which)
                      (rows/lowering-works! which)
                      ;; The donor rungs are not in this run's arm set (see
                      ;; `arm-ids-for`), so there is no lowering here to check
                      ;; and a check that trivially passed would be worse than
                      ;; no check at all. Recorded as inapplicable rather than
                      ;; as green.
                      (js/Promise.resolve {:lowered? true :applicable? false
                                           :why (str "the donor arms are not REACTIVE under this "
                                                     "run's ratom spine, so a lowering check here "
                                                     "would fail for an unrelated reason; the uix "
                                                     "run is where this question is asked")}))
                    (.then
                      (fn [low]
                        (record! :lowering-check low)
                        (if-not (:lowered? low)
                          (do (fail! (str "rung 2's event-vector lowering did not reach the DOM: "
                                          (pr-str low) " — the rung-2 clock would be pricing a "
                                          "lowering that produces a closure nobody can call"))
                              (done!)
                              nil)

                          ;; ---- the clocks ----------------------------------
                          (do
                            (doseq [wit rows/witnesses]
                              (record-row! (keyword (str "mount-" (name (:id wit))))
                                           (rows/measure-mount! wit arm-ids rounds mount-sampling))
                              ;; A teardown that threw is checked BETWEEN rows,
                              ;; not swallowed until the end: an arm whose
                              ;; unmount failed leaves its watches and its
                              ;; caches standing, and the next row is then
                              ;; measured on a page that is carrying them
                              ;; (rf2-f5roa, from the PR #7263 audit).
                              (rows/assert-teardown-clean! (str "mount-" (name (:id wit)))))
                            ;; The harness microtask, priced before the write rows
                            ;; and outside every one of their windows. A
                            ;; microtask-scheduled arm's window does not contain
                            ;; that turn and its rivals' do (rf2-b69lw), so the
                            ;; size of the asymmetry is published beside the rows
                            ;; — and, since this bead, ADJUDICATED against them
                            ;; rather than left beside them as an observation.
                            (let [yc* (volatile! nil)]
                              (-> (rows/yield-cost! write-sampling)
                                  (.then (fn [yc]
                                           (record! :yield-cost yc)
                                           (vreset! yc* yc)
                                           (rows/measure-write! write-ids which :narrow rounds write-sampling)))
                                  (.then (fn [r]
                                           (record-row! :write-narrow r)
                                           (rows/assert-teardown-clean! "write-narrow")
                                           (-> (rows/measure-write! write-ids which :bulk rounds write-sampling)
                                               (.then (fn [b] #js [r b])))))
                                  (.then
                                    (fn [pair]
                                      (let [narrow (aget pair 0)
                                            bulk   (aget pair 1)]
                                        (record-row! :write-bulk bulk)
                                        (rows/assert-teardown-clean! "write-bulk")
                                        ;; ---- THE CORRECTION-OR-REFUSAL CONTRACT ----
                                        ;; The asymmetry between the two window shapes
                                        ;; used to be recorded here and nothing else:
                                        ;; the run exited 0 and emitted unadjusted
                                        ;; ratios over a slim run whose aggregate had
                                        ;; read nonzero, while the studio record said
                                        ;; any nonzero reading owes the reader a
                                        ;; subtraction (rf2-b69lw, from the PR #7269
                                        ;; audit). It is adjudicated now, and a row
                                        ;; whose correction cannot be discharged FAILS
                                        ;; the run — the same fail-closed path the
                                        ;; positive control takes, for the same reason:
                                        ;; a figure the instrument cannot stand behind
                                        ;; is not a measurement.
                                        (let [verdicts {:write-narrow (rows/yield-correction narrow which @yc*)
                                                        :write-bulk   (rows/yield-correction bulk which @yc*)}
                                              refused  (into {} (filter (fn [[_ v]] (= :refused (:verdict v)))) verdicts)]
                                          (record! :yield-correction verdicts)
                                          (doseq [[k v] verdicts] (correction! k v))
                                          (when (seq refused)
                                            (fail! (str "the harness-microtask correction could not be discharged on "
                                                        (pr-str (vec (keys refused))) " — "
                                                        (pr-str (into {} (map (fn [[k v]] [k (:why v)])) refused))
                                                        ". Those rows mix window shapes, so one arm is billed for "
                                                        "turns its rival escapes, and a ratio published over that "
                                                        "is a comparison of the harness.")))
                                          (done!)))))))))))
                    (.catch (fn [e]
                              (fail! (str "the run threw: " (.-message e) "\n" (.-stack e)))
                              (done!))))))))))))

(defn ^:export -main []
  (try
    (run-all!)
    (catch :default e
      (fail! (str (.-message e) "\n" (.-stack e)))
      (done!))))
