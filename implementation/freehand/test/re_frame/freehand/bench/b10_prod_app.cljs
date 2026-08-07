(ns re-frame.freehand.bench.b10-prod-app
  "B10's PRODUCTION entry — DC-09's two-clock envelope, taken from an
  `:advanced` bundle with `goog.DEBUG false`.

  This namespace exists for B6's reason, restated for B10. Every figure
  in `docs/design/freehand/studio/two-clock-operating-envelope.md` came
  from `:optimizations :none` with `goog.DEBUG true`, so the Spec 009
  instrumentation seam, schema validation and trace emission were all
  live on exactly the measured event path. Every cost there is an UPPER
  bound and every rate a LOWER bound. The direction was safe; the factor
  was unmeasured, and the 120 Hz row — 9.2 ms p95 against an 8.33 ms
  budget at k=0, a miss by 10% — was the row it could flip.

  ## No eleventh instrument, and no twelfth

  Nothing here is a second harness. Every arm, every window, both
  controls and all three measurements are
  [[re-frame.freehand.bench.b10-two-clock]]'s, unchanged. This file
  installs the adapter, runs them in the order the browser suite runs
  them, and parks the records on `window.B10_RESULTS` for the driver to
  read. The deterministic gates stay where they belong, in
  `b10-two-clock-dom-cljs-test`, which the bench browser lane still runs
  under `:none` — but the two POSITIVE CONTROLS are repeated here,
  because a control that held under `:none` and failed under `:advanced`
  would be the Closure compiler quietly deciding the comparison.

  Both controls are hard failures here rather than published rows:

    * **C2, a size.** Each mode's DOM mutation count is predicted from
      the fixture's arithmetic and measured with a `MutationObserver`.
      Under `:advanced` this is also the externs check — `.takeRecords`,
      `.-type`, `.-style`/`-transform` and `.setAttribute` all have to
      survive renaming, and a mismatch means they did not.
    * **C1, a duration.** A busy-wait of computed 4.00 ms timed through
      the same window every figure is timed through. A sample reading
      BELOW its own predicted duration means the window is not measuring
      what it brackets, whatever the optimiser did.

  And the same rule the dev run publishes under: **every measured
  crossing is read back out of the DOM inside its own window, and a
  crossing that did not land is COUNTED, not discarded.** The unverified
  count rides in every record.

  ## Runtime

  These figures are CHROME, headless, from the artefact a consumer
  ships. Nothing here is quoted for Node or for the JVM, and no share
  measured here transfers to either as an absolute.

  ## Scope

  M1 (sibling ladder, both orders), M2 (config equality at both read
  sites) and M3 (driven rates) — the three the bead asks for. M4's
  controlled-field row is not re-taken: it depends on
  `re-frame.freehand.mount-support`'s act-environment door, its verdict
  is a correctness one (no drops, intact caret) that the browser suite
  already gates deterministically, and DC-09 does not read it off the
  production bundle.

  Built and driven by
  `implementation/freehand/test/re_frame/freehand/bench/b10_prod_run.cjs`.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`. Measured claim
  owner: DC-09 in `docs/design/freehand/product-completion-setpoint.md`."
  (:require [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.freehand.bench.b10-two-clock :as b10]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.bench.provenance :as prov]))

;; The dev run's constants, verbatim. A factor read between two runs that
;; sampled differently would be a factor over the sampling, so these are
;; the one thing in this file that must not be tuned.
(def ^:private rounds 4)
(def ^:private sampling {:warmup 3 :samples 10})
(def ^:private burn-ms 4.0)
(def ^:private drive-ms 700)

(defn- fail! [why]
  (set! (.-B10_ERROR js/window) (str why))
  (js/console.error (str ";; B10 PROD FAILED — " why)))

(defn- record! [k v]
  (let [acc (or (.-B10_RESULTS js/window) #js {})]
    (aset acc (name k) (pr-str v))
    (set! (.-B10_RESULTS js/window) acc)
    v))

(defn- round3 [x] (/ (js/Math.round (* x 1000)) 1000.0))

;; ===========================================================================
;; C2 — the size control, and the externs check it doubles as
;; ===========================================================================

(defn- c2!
  "Predicted DOM mutation count against measured, at every rung and every
  mode. Answers the rows; sets `B10_ERROR` and answers nil on any
  mismatch, because a fixture that does not dirty what it says it dirties
  invalidates every number below it."
  []
  (let [mnt (b10/mount! :vega)]
    (try
      (let [rows (vec (for [k    b10/sibling-ladder
                            mode [:host-only :fresh-equal :one-leaf]]
                        (let [arm (b10/sibling-mode mode k)
                              ;; one unmeasured warm crossing, then the counted one
                              _   ((:run! arm) (:container mnt) (:observer mnt))
                              r   ((:run! arm) (:container mnt) (:observer mnt))]
                          {:mode      mode
                           :k         k
                           :predicted (:predicted-mutations arm)
                           :measured  (get-in r [:mutations :total])
                           :by-type   (get-in r [:mutations :by-type])
                           :verified? (:ok? r)})))
            bad  (filterv (fn [{:keys [predicted measured verified?]}]
                            (or (not= predicted measured) (not verified?)))
                          rows)]
        (record! :c2 {:control     :mutation-count
                      :note        (str "predicted from the fixture's arithmetic: k+1 for a "
                                        "crossing dirtying k siblings beside the leaf, one "
                                        "attribute for the host clock, none for a "
                                        "fresh-but-equal write. Under :advanced this is also "
                                        "the externs check — a renamed MutationObserver or "
                                        "style property fails here.")
                      :rows        rows
                      :mismatches  bad
                      :ok?         (empty? bad)})
        (when (seq bad)
          (fail! (str "C2 — the fixture does not dirty what its arithmetic says under "
                      ":advanced: " (pr-str bad))))
        (when (empty? bad) rows))
      (finally (b10/release! mnt)))))

;; ===========================================================================
;; C1 — the duration control
;; ===========================================================================

(defn- c1!
  "A busy-wait of computed `burn-ms` through the measured window. The
  overshoot is clock quantisation plus whatever the operating system took
  away, and it is published beside the tables so a contended run is read
  as a contended run rather than as a slow substrate. Reading BELOW the
  predicted duration is the failure."
  []
  (let [mnt (b10/mount! :vega)
        arm (b10/burn-arm burn-ms)]
    (try
      (let [rs    (vec (for [_ (range 12)] ((:run! arm) (:container mnt) (:observer mnt))))
            s     (m/summarise (mapv :total-ms rs))
            below (count (filter #(< (:total-ms %) burn-ms) rs))
            unv   (count (remove :ok? rs))]
        (record! :c1 {:control          :control-burn
                      :predicted-ms     burn-ms
                      :measured         s
                      :p50-overshoot-ms (round3 (- (:p50 s) burn-ms))
                      :below-predicted  below
                      :unverified       unv
                      :of               (count rs)
                      :note (str "overshoot is instrument quantisation plus machine "
                                 "contention; every B10 table is read on this scale")})
        (when (pos? below)
          (fail! (str "C1 — " below " of " (count rs) " samples read BELOW their own "
                      "predicted " burn-ms " ms; the window is not measuring what it "
                      "brackets (min " (:min s) ")")))
        (when (zero? below) s))
      (finally (b10/release! mnt)))))

;; ===========================================================================
;; M1 — the sibling ladder, in both orders
;; ===========================================================================

(defn- ladder-round!
  [mnt ks]
  (into {}
        (map (fn [k]
               [k (b10/run-arms! mnt
                                 [(b10/sibling-mode :host-only k)
                                  (b10/sibling-mode :fresh-equal k)
                                  (b10/sibling-mode :one-leaf k)
                                  (b10/burn-arm burn-ms)]
                                 sampling)]))
        ks))

(defn- envelope
  "The derived envelope, folded ACROSS every round rather than read off
  one of them. `{k {arm {:p95 {:min :max} :p50 {:min :max}}}}` — a range,
  because a single round's p95 is a sample of a p95 and two arms whose
  ranges overlap are indistinguishable."
  [all]
  (let [ks   (keys (first all))
        arms (keys (get (first all) (first ks)))]
    (into {}
          (map (fn [k]
                 [k (into {}
                          (map (fn [arm]
                                 (let [p95 (mapv #(get-in % [k arm :total-ms :p95]) all)
                                       p50 (mapv #(get-in % [k arm :total-ms :p50]) all)]
                                   [arm {:p95    {:min (apply min p95) :max (apply max p95)}
                                         :p50    {:min (apply min p50) :max (apply max p50)}
                                         :rounds (count all)}])))
                          arms)]))
          ks)))

(defn- m1!
  []
  (let [mnt (b10/mount! :vega)]
    (try
      (let [up   (vec (for [_ (range (/ rounds 2))]
                        (ladder-round! mnt b10/sibling-ladder)))
            down (vec (for [_ (range (/ rounds 2))]
                        (ladder-round! mnt (reverse b10/sibling-ladder))))
            all  (into up down)
            unv  (reduce + (for [r all, [_ arms] r, [_ s] arms] (:unverified s)))
            tot  (reduce + (for [r all, [_ arms] r, [_ s] arms] (:n s)))
            host (for [r all, [_ arms] r] (get-in arms [:host-only :total-ms :p50]))
            flat (- (apply max host) (apply min host))]
        (record! :m1
                 (b10/record
                   :B10/sibling-ladder
                   "service time of one semantic crossing against dirty-sibling count"
                   {:cells        b10/cells-n
                    :ladder       b10/sibling-ladder
                    :config-shape :vega
                    :arms         [:host-only :fresh-equal :one-leaf :control-burn]
                    :crossing     :dispatch-sync
                    :unverified   unv
                    :of           tot
                    :measurement-method
                    (str "t0; react-dom/flushSync around rf/dispatch-sync, with t1 taken "
                         "INSIDE the flush the instant the dispatch returns; t2 after the "
                         "flush. :event-ms is t1-t0 (router, interceptors, reducer, app-db "
                         "install, subscription notification); :commit-ms is t2-t1 (React "
                         "render, commit, DOM mutation). The crossing is then read back out "
                         "of the DOM — or, for the fresh-equal arm whose whole point is that "
                         "the page must NOT move, out of the store, by proving the reference "
                         "moved and the value did not. Arm order rotates on the sample index; "
                         "the LADDER is walked ascending in half the rounds and descending in "
                         "the other half, both published. A control-burn arm of computed "
                         "4.00 ms duration rides in every cell as the machine's contention "
                         "meter. Same instrument, same constants and same fixture as the "
                         "development reading; the ONLY difference is the bundle.")}
                   (assoc sampling :rounds rounds)
                   {:ascending  up
                    :descending down}))
        (record! :m1-envelope
                 {:note (str "budget at rate R is 1000/R ms; the envelope is the largest R "
                             "whose budget exceeds the crossing's p95. Ranges are across all "
                             (count all) " rounds — a single round's p95 is a sample of a "
                             "p95, and overlapping ranges mean indistinguishable.")
                  :budgets-ms       (into {} (map (fn [r] [r (round3 (/ 1000.0 r))]))
                                          b10/rate-ladder)
                  :across-rounds    (envelope all)
                  :host-flatness-ms (round3 flat)
                  :unverified       unv
                  :of               tot})
        (when (pos? unv)
          (fail! (str "M1 — " unv " unverified of " tot
                      "; a crossing that did not land was timed")))
        (when (>= flat 1.0)
          (fail! (str "M1 — the host-only arm is NOT flat across the ladder (spread "
                      flat " ms); the ladder is contaminating an arm that never crosses")))
        all)
      (finally (b10/release! mnt)))))

;; ===========================================================================
;; M2 — equality cost, at two config shapes, at both read sites
;; ===========================================================================

(defn- m2!
  []
  (let [out (atom {})]
    (doseq [shape   [:vega :spread]
            read-at [:parent :leaf]]
      (let [mnt (b10/mount! shape read-at)]
        (try
          (b10/reset-behavior-log!)
          (let [before (b10/behavior-calls)
                rs     (vec (for [_ (range rounds)]
                              (b10/run-arms!
                                mnt
                                [(b10/config-mode :stable shape)
                                 (b10/config-mode :fresh-equal shape)
                                 (b10/config-mode :one-leaf shape)
                                 (b10/burn-arm burn-ms)]
                                sampling)))
                after  (b10/behavior-calls)]
            (swap! out assoc [shape read-at]
                   {:rounds           rs
                    :behavior-updates (- (:update after) (:update before))
                    :leaves           (get-in b10/config-shapes [shape :leaves])}))
          (finally (b10/release! mnt)))))
    (let [unv (reduce + (for [[_ v] @out, r (:rounds v), [_ s] r] (:unverified s)))
          tot (reduce + (for [[_ v] @out, r (:rounds v), [_ s] r] (:n s)))]
      (record! :m2
               (b10/record
                 :B10/config-equality
                 "cost of one behavior-config crossing by equality class and config shape"
                 {:shapes       (into {} (map (fn [[k v]] [k (select-keys v [:leaves :channels])]))
                                      b10/config-shapes)
                  :modes        [:stable :fresh-equal :one-leaf]
                  :read-sites   [:parent :leaf]
                  :crossing     :dispatch-sync
                  :cells        b10/cells-n
                  :unverified   unv
                  :of           tot
                  :measurement-method
                  (str "the same window as M1, at both read sites — :parent reads the config "
                       "subscription in the view that also builds the 300 cells, :leaf reads "
                       "it one boundary down inside the behavior's own panel; nothing else "
                       "differs between them. :stable re-installs the value already in "
                       "app-db — identical, not merely equal — and is verified by identity; "
                       ":fresh-equal installs a freshly built map of the same contents and is "
                       "verified by the reference moving while the value does not; :one-leaf "
                       "installs one differing in exactly one leaf. The behavior's :update "
                       "call count across the whole run is published beside the timings.")}
                 (assoc sampling :rounds rounds)
                 @out))
      (when (pos? unv)
        (fail! (str "M2 — " unv " unverified of " tot)))
      @out)))

;; ===========================================================================
;; M3 — the driven arm
;; ===========================================================================

(defn- m3!
  "Async: the driven rates ride real timers, so this answers a promise."
  []
  (let [mnt  (b10/mount! :vega)
        runs (atom [])
        plan (for [k [0 200], r b10/rate-ladder] [k r])]
    (-> (reduce (fn [p [k r]]
                  (.then p (fn [_]
                             (-> (b10/drive! mnt r k drive-ms)
                                 (.then (fn [rec] (swap! runs conj rec) nil))))))
                (js/Promise.resolve nil)
                plan)
        (.then
          (fn [_]
            ;; C3 and C4 ride here too: a driven run whose tail is not
            ;; anchored at the last offer, or whose published tail is not the
            ;; difference of the two instants published beside it, is
            ;; incoherent in the same way a run that applied more than it
            ;; offered is, and fails the arm rather than publishing a
            ;; plausible wrong number. C4 is the check the third edition
            ;; lacked: C3 alone left `:tail-ms` free to be re-anchored at
            ;; settle time with every gate still green.
            (let [bad (filterv (fn [{:keys [offered applied tail-ms tail-anchored?] :as run}]
                                 (or (not (pos? offered))
                                     (> applied offered)
                                     (not tail-anchored?)
                                     (not (number? tail-ms))
                                     (neg? tail-ms)
                                     (not (b10/tail-identity-holds? run))))
                               @runs)]
              (record! :m3
                       (b10/record
                         :B10/driven-rates
                         "offered against browser-observed against accepted against committed"
                         {:rates       b10/rate-ladder
                          :ladder      [0 200]
                          :duration-ms drive-ms
                          :cells       b10/cells-n
                          :crossing    :dispatch-async
                          :measurement-method
                          (str "setInterval at 1000/R ms for " drive-ms " ms, each tick "
                               "issuing an ordinary asynchronous rf/dispatch. :expected is "
                               "computed (floor(duration x rate / 1000)) and is NOT a "
                               "measurement; :offered counts driver invocations; :applied is "
                               "incremented inside the reducer; :mutations counts DOM "
                               "records; :peak-backlog is the largest offered-minus-applied "
                               "ever seen at an offer; :tail-ms is the LAST GENERATION'S OWN "
                               "OBSERVER LATENCY, retained by the MutationObserver that "
                               "computed it rather than recomputed at publication time, so "
                               "it is the same number that generation contributes to "
                               ":latency-ms, with a 4 ms poll and a 2000 ms ceiling serving "
                               "only as the :settled? detector; :tail-offer-at-ms and "
                               ":tail-observed-at-ms publish the two readings it is the "
                               "difference of, and that identity is asserted exactly (C4); "
                               ":offer-to-poll-start-ms is the one-interval gap between the "
                               "retained offer reading and the settle decision and "
                               ":tail-anchored? gates it against half a requested period "
                               "(C3), so re-anchoring the tail at settle time fails C4 by a "
                               "whole interval and re-anchoring the published offer reading "
                               "to match fails C3; :latency-ms is the offer-to-DOM "
                               "distribution read from the MutationObserver callback that "
                               "carried each generation.")}
                         {:warmup 0 :samples (count @runs)}
                         {:runs @runs}))
              (when (seq bad)
                (fail! (str "M3 — a driven run is incoherent "
                            "(offered/applied, C3 anchor or C4 tail identity): "
                            (pr-str bad)))))
            nil))
        (.finally (fn [] (b10/release! mnt))))))

;; ===========================================================================
;; Entry
;; ===========================================================================

(defn ^:export -main
  []
  (try
    (rf/init! react-substrate/adapter)
    (h/leave-act-environment!)
    ;; Read off the RUNNING bundle rather than asserted by the driver, so
    ;; a run that was somehow not the advanced bundle says so in its own
    ;; output instead of being taken on the build command's word.
    (record! :build (assoc (prov/detect-build)
                           :revision (prov/detect-revision)
                           :host     (prov/detect-host)))
    ;; Controls FIRST. If the fixture does not dirty what it says it
    ;; dirties, or the window does not measure what it brackets, nothing
    ;; below is worth taking.
    (if-not (and (c2!) (c1!))
      (set! (.-B10_DONE js/window) true)
      (-> (js/Promise.resolve nil)
          (.then (fn [_] (m1!) nil))
          (.then (fn [_] (m2!) nil))
          (.then (fn [_] (m3!)))
          (.then (fn [_] (set! (.-B10_DONE js/window) true) nil))
          (.catch (fn [e]
                    (fail! (str "the run threw: " e))
                    (set! (.-B10_DONE js/window) true)
                    nil))))
    (catch :default e
      (fail! (str "the run threw: " e))
      (set! (.-B10_DONE js/window) true)))
  nil)
