(ns re-frame.freehand.bench.b6-profile-app
  "B6's PROFILING entry — ONE update arm, driven in a tight loop, with no
  other arm and no harness work sharing the window.

  This namespace exists because
  [[re-frame.freehand.bench.b6-prod-app]] cannot be profiled. It
  interleaves four arms at the sample level, which is exactly right for
  the clock and useless for attribution: a CPU profile of the whole suite
  puts ~43% of its samples in `(idle)` and splits the rest across four
  substrates, so no frame's share means anything. Attribution needs one
  arm, alone, for a long uninterrupted stretch.

  **Nothing about the measured window changes.** The write is
  [[re-frame.freehand.bench.b6-rows/timed-write!]] verbatim — the same
  state install, the same single microtask yield, the same empty
  `react-dom/flushSync` drain, and the same per-write read-back of the
  written cell out of the DOM. Profiling a window that differs from the
  published one would describe a measurement nobody took.

  ## The positive control is part of the instrument

  `window.B6_CONTROL_MS` spins [[control-spin!]] for a known number of
  milliseconds inside every write. It exists to FALSIFY the profiler: a
  named frame of known cost must appear in the capture at the share
  arithmetic predicts, or the attribution below it cannot be trusted
  either. Two instrument faults on the predecessor bead each produced a
  plausible wrong table, so a capture that cannot be checked against a
  known quantity is not evidence.

  Driven by
  `implementation/freehand/test/re_frame/freehand/bench/b6_profile_run.cjs`.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b6-rows :as rows]
            [re-frame.freehand.bench.b6-witnesses-flat :as flat]
            [re-frame.freehand.bench.measure :as m]))

;; ---------------------------------------------------------------------------
;; The positive control
;; ---------------------------------------------------------------------------

(defonce ^:private spin-sink (volatile! 0.0))

(defn control-spin!
  "Burn `ms` milliseconds of CPU in a frame with a name a profile can find.

  The result is parked in a live volatile because Closure is entitled to
  delete a loop whose value nothing reads — the same mistake the
  predecessor report's forced-reflow reading made, where `layout-ms` came
  back exactly 0.000 in every arm because the property read had been
  dropped."
  [ms]
  (let [end (+ (m/now-ms) ms)]
    (loop [x (double @spin-sink)]
      (if (< (m/now-ms) end)
        (recur (+ x (js/Math.sqrt (+ 1.0 x))))
        (vreset! spin-sink x)))))

;; ---------------------------------------------------------------------------
;; The loop
;; ---------------------------------------------------------------------------

(defn- arm-named
  "The four published arms, plus the ABLATION arm — the same 300 reads and
  the same DOM under ONE boundary instead of three hundred. It is built
  through [[re-frame.freehand.bench.b6-rows/freehand-update-arm]], the
  same constructor the published Freehand arm uses, so the two differ in
  the view they mount and in nothing else."
  [id]
  (or (first (filter #(= id (:id %)) (rows/make-update-arms)))
      (when (= id :freehand-flat)
        (rows/freehand-update-arm :freehand-flat flat/u-grid-flat))
      (throw (ex-info "no such arm" {:id id}))))

(defn- run-writes!
  "`n` broad writes on `mnt`, each through the published window. Answers a
  promise of the accumulated legs and the count of writes whose DOM
  read-back FAILED."
  [mnt n control-ms]
  (rows/chain {:ms [] :write [] :gap [] :force [] :bad 0} (range n)
    (fn [acc _]
      (let [val (rows/next-gen!)]
        (when (pos? control-ms) (control-spin! control-ms))
        (-> (rows/timed-write! mnt :all val)
            (.then (fn [{:keys [ms write-ms gap-ms force-ms ok? ok2?]}]
                     (cond-> (-> acc
                                 (update :ms conj ms)
                                 (update :write conj write-ms)
                                 (update :gap conj gap-ms)
                                 (update :force conj force-ms))
                       (not (and ok? ok2?)) (update :bad inc)))))))))

(defn- summarise
  [{:keys [ms write gap force bad]} n control-ms]
  {:writes         n
   :control-ms     control-ms
   :unverified     bad
   :ms             (m/summarise ms)
   :write-ms       (m/summarise write)
   :gap-ms         (m/summarise gap)
   :force-ms       (m/summarise force)
   :total-ms       (reduce + 0.0 ms)})

(defn ^:export -main
  []
  (try
    (rf/init! react-substrate/adapter)
    (h/leave-act-environment!)
    (let [arm-id     (keyword (or (.-B6_ARM js/window) "freehand-interpreted"))
          control-ms (or (.-B6_CONTROL_MS js/window) 0)
          mounts     (rows/mount-update-arms! [(arm-named arm-id)])
          mnt        (first mounts)]
      ;; The run door. The driver warms, then starts the CPU profiler, then
      ;; calls this — so the capture contains writes and nothing else.
      (set! (.-B6_RUN js/window)
            (fn [n]
              (-> (run-writes! mnt n control-ms)
                  (.then (fn [acc]
                           (let [s (summarise acc n control-ms)]
                             (set! (.-B6_LAST js/window) (pr-str (assoc s :arm arm-id)))
                             (clj->js {:unverified (:unverified s)
                                       :p50        (:p50 (:ms s))
                                       :edn        (.-B6_LAST js/window)})))))))
      ;; THE ABLATION ARM IS GATED ON CANONICAL DOM, like every other arm.
      ;; A one-boundary grid that quietly built a different page would be a
      ;; fast number for a different measurement — which is exactly the
      ;; mistake the predecessor report's first parity run nearly published.
      ;; Only for an arm that is NOT the reference: mounting a second
      ;; `:freehand-interpreted` root would collide on the reference arm's
      ;; own root identity, which `v/mount` refuses (idempotent per root).
      (when (not= arm-id :freehand-interpreted)
       (let [ref   (rows/mount-update-arms! [(arm-named :freehand-interpreted)])
            a     (h/canonical (:container (first ref)))
            b     (h/canonical (:container mnt))
            same? (= a b)]
        (rows/release-update-arms! ref)
        (set! (.-B6_PARITY js/window) same?)
        (when-not same?
          (let [n (count (take-while true? (map = a b)))]
            (throw (ex-info "the profiled arm does not build the reference page"
                            {:arm arm-id :at n :ref-len (count a) :got-len (count b)
                             :ref (subs a (max 0 (- n 60)) (min (count a) (+ n 120)))
                             :got (subs b (max 0 (- n 60)) (min (count b) (+ n 120)))}))))))
      (-> (rows/seed-update-arms! mounts)
          (.then (fn [_]
                   (set! (.-B6_READY js/window) true)
                   (js/console.log (str ";; B6 PROFILE READY " (name arm-id)
                                        " parity=" (.-B6_PARITY js/window)))
                   nil))))
    (catch :default e
      (set! (.-B6_ERROR js/window) (str e))
      (js/console.error (str ";; B6 PROFILE FAILED — " e)))))
