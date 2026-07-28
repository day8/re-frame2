(ns re-frame.freehand.bench.b6-yield-app
  "Does B6's update window still need its microtask yield? — `rf2-vxfjt`.

  The published window is *write, yield ONE microtask, force the arm's own
  synchronous drain, stop the clock*, and the yield is there because the
  slowest arm needed it: a mounted Freehand `v/sub` was not repainted
  inside `react-dom/flushSync`, so a first pass timed Freehand writes that
  never reached the DOM at all. `rf2-w2m25` (#7133) fixed that, and
  `b6-rows`' own docstring has since described the yield as removable.

  `rf2-huhno`'s re-take says removing it is not a free tightening. On main
  the window RESTRUCTURED: the empty `flushSync` that used to carry
  3.0–3.5 ms now costs ~0.0 and the gap that used to be ~0.2 ms now
  carries 3.6–3.9. Freehand's notification, React's render and React's
  commit all run inside that microtask. So removing it either moves the
  work into the `flushSync`, leaving the total unchanged and the legs
  re-attributed again, or the notification has not fired when the empty
  `flushSync` runs, the commit lands after the measured window, and the
  DOM read-back fails.

  ## What this entry does, and what it refuses to do

  1. **The non-vacuity gate first, on every arm.** `rf2-0fgth` added the
     check that a Freehand write inside `flushSync` reaches the DOM before
     the flush returns; it asks it of one arm. This asks it of every arm,
     and asks every window shape of every arm. Every probe installs a
     value no other write ever installs and reads it back out of the DOM.
     **A measurement of an arm that did nothing is the fault a canonical
     DOM gate caught once**, when an ablation arm was rendering an empty
     page — so the gate is on the record before any figure, and the
     per-write read-back inside every measured window is the same check
     repeated 3,960 times.
  2. **A positive control whose size is arithmetic.** `:floor+spin` is the
     floor arm with a CPU spin of a known number of milliseconds wrapped
     around its own drain. Its sample must read the floor's plus that
     number times the sample's write count, in every window, or the clock
     is not measuring the window it claims to.
  3. **Three windows interleaved, at the sample level.** Not before and
     after. A worker on this surface measured a 20% regression that turned
     out to be machine state, and caught it only by re-running the
     baseline LAST; the same lesson here is that the windows must rotate
     inside one round, with the arm order rotating and reflecting
     (`b6-harness/slot-order`), so no window is systematically first into
     a cold cache.
  4. **Ranges across rounds, never a mean.** Overlapping ranges are
     reported as indistinguishable.

  This entry rides `b6_prod_run.cjs`'s `B6_INIT_FN` seam, which exists for
  exactly this — an ablation reusing the published driver rather than
  growing a second one:

      B6_INIT_FN=re-frame.freehand.bench.b6-yield-app/-main \\
      B6_OUT_DIR=out/b6-yield B6_PORT=8128 \\
        node implementation/freehand/test/re_frame/freehand/bench/b6_prod_run.cjs

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react-dom" :as react-dom]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b6-profile-app :as prof]
            [re-frame.freehand.bench.b6-rows :as rows]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.bench.provenance :as prov]))

(def ^:private rounds 6)
(def ^:private sampling {:warmup 3 :samples 8})

(def ^:private control-ms
  "The positive control's predicted size, in milliseconds. Large enough to
  clear Chrome's 100 µs `performance.now()` clamp by a factor of twenty."
  2.0)

;; ---------------------------------------------------------------------------
;; Arms
;; ---------------------------------------------------------------------------

(defn- spin-arm
  "The floor arm with a spin of `ms` wrapped around its own drain — the
  positive control. Everything else about the arm is the floor's, so the
  difference between the two arms' windows is the spin and nothing else,
  and it is a quantity chosen rather than measured."
  [ms]
  (let [floor (first (filter #(= :floor (:id %)) (rows/make-update-arms)))]
    (assoc floor
           :id :floor+spin
           :force! (fn [] (prof/control-spin! ms) ((:force! floor))))))

(defn- make-arms []
  (conj (rows/make-update-arms) (spin-arm control-ms)))

;; ---------------------------------------------------------------------------
;; The non-vacuity gate
;; ---------------------------------------------------------------------------

(defn- settle!
  "Drain whatever the previous probe left pending: one microtask, then the
  arm's own synchronous drain.

  Not optional, and the first version of this gate did without it. Every
  probe deliberately includes a shape that MIGHT leave a commit
  outstanding, and the very next probe then read the DOM while that
  commit was landing — so `:commits-in-flush-window?` answered `false`
  for both Freehand arms while the measured `:in-flush` window verified
  660 of 660 writes. A probe contradicting the measurement it gates is
  the probe's bug, not the measurement's."
  [arm]
  (-> (js/Promise.resolve nil) (.then (fn [_] ((:force! arm)) nil))))

(def ^:private probe-shapes
  "The three synchronous window shapes, each as it installs a value.

  `:commits-inside-flushsync?` is `rf2-0fgth`'s check generalised to every
  arm. The FLOOR arm is expected to answer `false` and that is not a
  failure: the floor's `write!` only moves a plain atom and its render
  lives in `force!` by construction, because `root.render` outside a React
  event schedules at the default lane and an EMPTY `flushSync` does not
  flush it — the fault that left 80 of 320 floor samples on a stale cell.
  The probes that gate the re-take are the other two."
  [[:commits-inside-flushsync?
    (fn [arm v] (react-dom/flushSync (fn [] ((:write! arm) 0 v))))]
   [:commits-without-yield?
    (fn [arm v] ((:write! arm) 0 v) ((:force! arm)))]
   [:commits-in-flush-window?
    (fn [arm v] (react-dom/flushSync (fn [] ((:write! arm) 0 v))) ((:force! arm)))]])

(defn- probe-arm!
  "Every window shape over one arm, each installing a value no other write
  ever installs, each preceded by a settle, each read back out of the DOM."
  [{:keys [arm container] :as _mnt}]
  (-> (rows/chain {:arm (:id arm)} probe-shapes
        (fn [acc [k install!]]
          (-> (settle! arm)
              (.then (fn [_]
                       (let [v (rows/next-gen!)]
                         (install! arm v)
                         (assoc acc k (= (str v) (rows/cell-text container 0)))))))))
      (.then (fn [acc]
               ;; The published window last, because it is the one that
               ;; must never regress and a reader should see it decided
               ;; from a settled state like the others.
               (-> (settle! arm)
                   (.then (fn [_]
                            (let [v (rows/next-gen!)]
                              ((:write! arm) 0 v)
                              (-> (js/Promise.resolve nil)
                                  (.then (fn [_]
                                           ((:force! arm))
                                           (assoc acc :commits-with-yield?
                                                  (= (str v)
                                                     (rows/cell-text container 0))))))))))))))

(defn- gate!
  "Probe every mounted arm and answer
  `{:probes [...] :yield-free-ok? bool :failing [ids]}`."
  [mounts]
  (-> (rows/chain [] mounts (fn [acc mnt] (-> (probe-arm! mnt) (.then #(conj acc %)))))
      (.then (fn [probes]
               (let [failing (into [] (comp (remove :commits-without-yield?) (map :arm)) probes)
                     failing-in-flush (into [] (comp (remove :commits-in-flush-window?)
                                                     (map :arm))
                                            probes)]
                 {:probes                probes
                  :failing               failing
                  :failing-in-flush      failing-in-flush
                  :yield-free-ok?        (empty? failing)
                  :in-flush-window-ok?   (empty? failing-in-flush)})))))

;; ---------------------------------------------------------------------------
;; The paired measurement
;; ---------------------------------------------------------------------------

(def ^:private windows
  "The published window and the two yield-free candidates.

  `:no-yield` is `timed-write!` with the microtask deleted and nothing
  else changed — the literal ablation the bead asks for. `:in-flush`
  additionally moves the write inside a `react-dom/flushSync`, which is
  the shape `rf2-0fgth`'s non-vacuity check pins; it is the only other
  yield-free window available, so leaving it unmeasured would answer
  'the yield stays' without having asked the obvious follow-up."
  {:yield    rows/timed-write!
   :no-yield rows/timed-write-no-yield!
   :in-flush rows/timed-write-in-flush!})

(def ^:private window-ids [:yield :no-yield :in-flush])

(defn- window-order
  "Which window goes first at this (sample, slot). Rotating, so no window
  is systematically first into a cold cache — the same reason the arms
  rotate and reflect."
  [s j]
  (let [n (count window-ids)
        r (mod (+ s j) n)]
    (into (subvec window-ids r) (subvec window-ids 0 r))))

(defn- n-writes!
  "`n` writes on one arm through one window, timed as one sample. Every
  write carries a fresh value and every one is read back out of the DOM."
  [write! mnt kind n]
  (rows/chain {:ms 0 :write-ms 0 :gap-ms 0 :force-ms 0 :bad 0} (range n)
    (fn [acc _]
      (let [val (rows/next-gen!)
            i   (if (= kind :broad) :all (mod val rows/cells-n))]
        (-> (write! mnt i val)
            (.then (fn [{:keys [ms write-ms gap-ms force-ms ok? ok2?]}]
                     (cond-> (-> acc
                                 (update :ms + ms)
                                 (update :write-ms + write-ms)
                                 (update :gap-ms + gap-ms)
                                 (update :force-ms + force-ms))
                       (not (and ok? ok2?)) (update :bad inc)))))))))

(defn- round!
  [mounts kind]
  (let [k     (count mounts)
        total (+ (:warmup sampling) (:samples sampling))
        n     (get rows/writes-per-sample kind)
        ids   (mapv #(:id (:arm %)) mounts)
        blank (zipmap ids (repeat (zipmap window-ids (repeat []))))]
    (rows/chain {:readings blank
                 :legs     blank
                 :bad      (zipmap ids (repeat (zipmap window-ids (repeat 0))))
                 :writes   (zipmap ids (repeat (zipmap window-ids (repeat 0))))}
                (for [s (range total) j (h/slot-order k s) w (window-order s j)] [s j w])
                (fn [acc [s j w]]
                  (let [mnt (nth mounts j)
                        id  (:id (:arm mnt))]
                    (-> (n-writes! (get windows w) mnt kind n)
                        (.then (fn [{:keys [ms write-ms gap-ms force-ms bad]}]
                                 (cond-> (-> acc
                                             (update-in [:bad id w] + bad)
                                             (update-in [:writes id w] + n))
                                   (>= s (:warmup sampling))
                                   (-> (update-in [:readings id w] conj ms)
                                       (update-in [:legs id w] conj
                                                  {:write write-ms
                                                   :gap   gap-ms
                                                   :force force-ms})))))))))))

(defn- p50s
  "Per-arm, per-window p50 of one round, in ms per SAMPLE."
  [readings]
  (into {} (map (fn [[id ws]]
                  [id (into {} (map (fn [[w xs]] [w (when (seq xs) (:p50 (m/summarise xs)))])) ws)]))
        readings))

(defn- legs-of
  [legs]
  (into {} (map (fn [[id ws]]
                  [id (into {} (map (fn [[w xs]]
                                      [w (when (seq xs)
                                           {:write-ms (:p50 (m/summarise (map :write xs)))
                                            :gap-ms   (:p50 (m/summarise (map :gap xs)))
                                            :force-ms (:p50 (m/summarise (map :force xs)))})]))
                            ws)]))
        legs))

(defn- range-of [xs]
  (let [xs (remove nil? xs)]
    (when (seq xs)
      {:min (apply min xs) :max (apply max xs) :p50 (:p50 (m/summarise xs)) :rounds (count xs)})))

(defn- overlap?
  [a b]
  (and a b (<= (:min a) (:max b)) (<= (:min b) (:max a))))

(defn- summarise
  "Fold the per-round p50s into a range per arm per window, and answer the
  verdict the operator actually asked for: did the yield matter?

  `:indistinguishable?` is the house rule — overlapping ranges across
  rounds mean the two windows are not separable at this instrument's
  resolution, and no percentage may be quoted off them."
  [per-round]
  (into {}
        (map (fn [id]
               (let [rs (into {} (map (fn [w] [w (range-of (map #(get-in % [id w]) per-round))]))
                              window-ids)
                     y  (:yield rs)]
                 [id (into rs
                           (map (fn [w]
                                  (let [r (get rs w)]
                                    [(keyword (str "vs-yield-" (name w)))
                                     (when (and y r)
                                       {:delta-p50          (- (:p50 r) (:p50 y))
                                        :ratio              (when (pos? (:p50 y))
                                                              (/ (:p50 r) (:p50 y)))
                                        :indistinguishable? (overlap? y r)})])))
                           (remove #{:yield} window-ids))])))
        (keys (first per-round))))

(defn- control-check
  "Predicted against measured, in every window. The spin arm's sample is
  the floor arm's plus `control-ms` PER WRITE — and the per-write factor
  is not decoration: the first run of this entry predicted a flat
  `+2.0 ms` and read `+39.1` on the narrow row, because a narrow SAMPLE
  is twenty writes and the spin is paid on every one of them. The control
  caught the harness's arithmetic, which is what a control is for."
  [summary kind]
  (let [per-sample (* control-ms (get rows/writes-per-sample kind))]
    (into {:predicted-delta-ms per-sample
           :writes-per-sample  (get rows/writes-per-sample kind)
           :arm                :floor+spin
           :note (str "the floor arm with a CPU spin of " control-ms
                      " ms wrapped around its own drain, paid on every write of the sample")}
          (map (fn [w]
                 (let [f (get-in summary [:floor w :p50])
                       s (get-in summary [:floor+spin w :p50])]
                   [w {:floor-p50 f
                       :spin-p50  s
                       :predicted (when f (+ f per-sample))
                       :measured  s
                       :error-ms  (when (and f s) (- s (+ f per-sample)))
                       :error-pct (when (and f s (pos? (+ f per-sample)))
                                    (* 100.0 (/ (- s (+ f per-sample)) (+ f per-sample))))}]))
               window-ids))))

;; ---------------------------------------------------------------------------
;; Entry
;; ---------------------------------------------------------------------------

(defn- fail! [why]
  (set! (.-B6_ERROR js/window) (str why))
  (js/console.error (str ";; B6 YIELD FAILED — " why)))

(defn- record! [k v]
  (let [acc (or (.-B6_RESULTS js/window) #js {})]
    (aset acc (name k) (pr-str v))
    (set! (.-B6_RESULTS js/window) acc)
    v))

(defn- measure!
  [mounts kind gate]
  (-> (rows/chain [] (range rounds)
                  (fn [acc _]
                    (-> (rows/seed-update-arms! mounts)
                        (.then (fn [_] (round! mounts kind)))
                        (.then (fn [rd] (conj acc rd))))))
      (.then (fn [rds]
               (let [per-round (mapv #(p50s (:readings %)) rds)
                     summary   (summarise per-round)
                     merge+    (fn [ms] (apply merge-with (partial merge-with +) ms))
                     bad-by    (merge+ (map :bad rds))
                     writes-by (merge+ (map :writes rds))
                     tot       (fn [m] (reduce + (mapcat vals (vals m))))
                     bad       (tot bad-by)
                     writes    (tot writes-by)]
                 (record!
                   (keyword (str "yield-" (name kind)))
                   {:benchmark (keyword "B6" (str "update-" (name kind) "-yield-ablation"))
                    :doc       (str "the published window (write, ONE microtask, forced drain) "
                                    "against the same window with the microtask removed, "
                                    "interleaved at the sample level")
                    :bead      :rf2-vxfjt
                    :revision  (prov/detect-revision)
                    :build     (prov/detect-build)
                    :host      (prov/detect-host)
                    :runtime   "Chromium via Playwright, :advanced, goog.DEBUG false"
                    :fixture   {:cells             rows/cells-n
                                :kind              kind
                                :arms              (mapv #(:id (:arm %)) mounts)
                                :adapter           :rf.adapter/uix
                                :writes-per-sample (get rows/writes-per-sample kind)
                                :unverified-writes bad
                                :total-writes      writes
                                :unverified-by-arm bad-by
                                :writes-by-arm     writes-by
                                :measurement-method
                                (str "two windows over one set of mounts, alternating at the "
                                     "sample level so neither is systematically first; arms "
                                     "rotating AND reflecting on the sample index (rf2-88pie); "
                                     rounds " rounds of " (:warmup sampling) " warmup + "
                                     (:samples sampling) " samples; every write read back out "
                                     "of the DOM inside its own window")}
                    :non-vacuity gate
                    :control     (control-check summary kind)
                    :per-round   per-round
                    :legs        (mapv #(legs-of (:legs %)) rds)
                    :summary     summary
                    :status      :evidence})
                 {:summary summary :bad bad :writes writes})))))

(defn ^:export -main
  []
  (try
    (rf/init! react-substrate/adapter)
    (h/leave-act-environment!)
    (let [mounts (rows/mount-update-arms! (make-arms))
          held   (volatile! nil)]
      (-> (gate! mounts)
          (.then (fn [gate]
                   (vreset! held gate)
                   (record! :non-vacuity gate)
                   (js/console.log (str ";; B6 YIELD GATE " (pr-str gate)))
                   (when-not (:yield-free-ok? gate)
                     ;; NOT a hard stop. An arm whose commit lands outside
                     ;; the yield-free window is the ANSWER to the bead, and
                     ;; the measurement below still prices what the other
                     ;; arms do — but the failure is on the record first, so
                     ;; no reader can take the row as a clean re-take.
                     (js/console.warn
                       (str ";; B6 YIELD — these arms do NOT commit inside a yield-free "
                            "window: " (pr-str (:failing gate)))))
                   (measure! mounts :broad gate)))
          (.then (fn [_] (measure! mounts :narrow @held)))
          (.then (fn [_]
                   (rows/release-update-arms! mounts)
                   (set! (.-B6_DONE js/window) true)
                   (js/console.log ";; B6 YIELD DONE")
                   nil))
          (.catch (fn [e]
                    (rows/release-update-arms! mounts)
                    (fail! (str "yield ablation rejected: " e))
                    (set! (.-B6_DONE js/window) true)))))
    (catch :default e
      (fail! (str "yield ablation threw: " e))
      (set! (.-B6_DONE js/window) true))))
