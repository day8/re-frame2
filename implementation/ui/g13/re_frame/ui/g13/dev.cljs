(ns re-frame.ui.g13.dev
  "Development-half of G-13: exact push-work counts plus timing evidence.
  Timing is descriptive only; correctness rests entirely on deterministic
  counts before and after the single read/render commit."
  (:require [re-frame.core :as rf]
            [re-frame.substrate.observation :as obs]
            [re-frame.ui :as ui]
            [re-frame.ui.g13.fixture :as fixture]
            [re-frame.ui.g13.measure :as measure]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(def sizes [100 500])
(def warmups 3)
(def recorded-samples 9)

(defn- fail! [message data]
  (throw (ex-info (str "G-13: " message) data)))

(defn- ensure! [pred message data]
  (when-not pred (fail! message data)))

(defn- cell-query [cell]
  (some (fn [[kind _frame-id query]]
          (when (= :sub kind) query))
        (reactive/committed-target-keys cell)))

(defn- split-cells [v]
  (let [cells (vec (reactive/current-live-cells))
        hot   (filterv #(= [::fixture/hot] (cell-query %)) cells)
        cold  (filterv #(let [q (cell-query %)]
                          (and (vector? q) (= ::fixture/cold (first q))))
                       cells)
        idle  (filterv #(empty? (reactive/committed-target-keys %)) cells)
        owning (into [] (concat hot cold))]
    (ensure! (= v (count owning)) "ownership-bearing ViewCell cardinality drift"
             {:v v :owning (count owning) :cells (count cells)})
    (ensure! (= fixture/hot-count (count hot)) "hot ViewCell cardinality drift"
             {:v v :hot (count hot)})
    (ensure! (= (- v fixture/hot-count) (count cold))
             "cold ViewCell cardinality drift"
             {:v v :cold (count cold)})
    (ensure! (= fixture/idle-shell-count (count idle))
             "fixed-shell empty ViewCell cardinality drift"
             {:v v :expected fixture/idle-shell-count :idle (count idle)})
    (ensure! (= (set cells) (set (concat owning idle)))
             "a mounted ViewCell was neither an owner nor an empty shell"
             {:v v :cells (count cells) :owning (count owning)
              :idle (count idle)})
    {:all cells :owning owning :hot hot :cold cold :idle idle}))

(defn- deltas [cells before]
  (mapv #(- (reactive/revision %) (get before %)) cells))

;; rf2-vxgfnd.213 — the timing sample and the correctness sample are two
;; SEPARATE drains, on purpose. A single drain cannot be both timed and
;; audited: the exact-work projection needs O(V) live-cell scans (dirty split,
;; revision deltas, port fan-out) captured between the write coalesce and the
;; read commit, and those scans — plus every post-commit assertion and DOM
;; query — are exactly the V-dependent work that must NOT sit inside a
;; dispatch-to-commit interval. So the clean timing cycle carries no evidence
;; collection at all, and the untimed correctness cycle carries all of it.
;;
;; rf2-52isf — ORDER matters as much as separation: `sample!` runs the clean
;; timing cycle FIRST, then the correctness cycle. For the `cold` sample this
;; makes the timed span the true FIRST post-mount dispatch/commit — before this
;; sample's own correctness dispatch, O(V) audit, DOM read, and cache/React
;; warm-up. `:timing-pre-hot` (the app-db `:hot` value captured just before the
;; timing dispatch; 0 at the mount seed, +queued-writes per drain) is the causal
;; witness the gate asserts: a cold `:timing-pre-hot` of 0 proves no drain
;; preceded the cold timer.

(defn- timing-cycle!
  "CLEAN dispatch-to-commit measurement. The measured interval spans exactly
  the eight write epochs plus the single read/render commit: no pre-commit
  evidence scan and no post-commit validation runs inside it, so the sample is
  independent of the V-scaled audit work done by the correctness cycle.

  This function supplies the collaborators and the WORK; it does not own the
  clock. `measure/measure-dispatch-to-commit!` owns the interval — witness,
  start, flush of the supplied thunk, end, witness — and its docstring carries
  the containment argument in full. The two properties that matter here:

  rf2-6k4cm — `:pre-hot` is causal to the measurement. It is read inside the
  seam, so any drain that preceded this cycle shows up as a nonzero witness and
  a warmed cycle can never masquerade as the cold first drain.

  rf2-a0i2y / rf2-muhsq — `:post-hot` is the closing witness, read after the
  end timestamp. The gate asserts `post-hot - pre-hot = queued-writes`. Because
  the seam reads BOTH witnesses, a dispatch removed from `:work!`, replaced
  with a no-op, hoisted above this call, or deferred past the resolved Promise
  all collapse the delta and turn the gate red. That is why the work is passed
  as a thunk rather than written inline around a timestamp.

  Returns a Promise of
  `{:elapsed-ms <ms> :pre-hot <:hot at dispatch> :post-hot <:hot at commit>}`."
  [frame]
  (measure/measure-dispatch-to-commit!
   {:read-hot (fn [] (:hot (rf/app-db-value frame)))
    :now      (fn [] (js/performance.now))
    :flush!   uit/flush!
    ;; dispatch! completes all eight write epochs synchronously; the render
    ;; phase runs when flush!'s Promise advances to the commit.
    :work!    (fn [] (rf/dispatch-sync [::fixture/step fixture/queued-writes] {:frame frame}))}))

(defn- correctness-cycle!
  "Exact push-work accounting for ONE drain — UNTIMED. Owns the O(V) live-cell
  scans that prove the V−C non-affected cells contribute zero work; those scans
  and every assertion sit here, never inside a timing interval. Returns the
  deterministic projection plus the fixed-shell count."
  [root frame v label]
  (let [{:keys [all hot cold idle]} (split-cells v)
        before (into {} (map (juxt identity reactive/revision)) all)
        pre    (atom nil)]
    (fixture/reset-counters!)
    ;; rf2-vxgfnd.250 — reset the production-erased port candidate-inspection
    ;; witness so this sample counts only THIS drain's inspections.
    (obs/reset-g13-candidate-inspections!)
    (-> (uit/flush!
         (fn []
           ;; dispatch! completes all eight write epochs synchronously. The
           ;; render phase has not run until flush!'s returned Promise advances.
           (rf/dispatch-sync [::fixture/step fixture/queued-writes] {:frame frame})
           (reset! pre
                   {:pending (reactive/pending-cell-count)
                    :hot-dirty (count (filter reactive/dirty? hot))
                    :cold-dirty (count (filter reactive/dirty? cold))
                    :idle-dirty (count (filter reactive/dirty? idle))
                    :revision-delta (reduce + (deltas all before))
                    :evidence-counts
                    (mapv #(get (reactive/pending-evidence %) :count) hot)
                    ;; rf2-vxgfnd.210 — the NAMED candidate-work axis.
                    ;; Summed over EVERY live cell (all V), `:port-fan-out` is
                    ;; the observation port's total DELIVERED fan-out for the
                    ;; drain: one fold per (queued write × affected owner). Its
                    ;; value is C*Q, and — critically — the V−C non-affected
                    ;; cells contribute ZERO (`:fan-out-cells` counts the cells
                    ;; that received ANY fold, and it is exactly C). The number
                    ;; is therefore causally independent of V: it is not merely
                    ;; numerically equal across the two runs, it is summed over
                    ;; all V and provably sourced from only C of them.
                    :port-fan-out
                    (reduce + 0 (keep #(:count (reactive/pending-evidence %)) all))
                    :fan-out-cells
                    (count (filter #(some? (reactive/pending-evidence %)) all))
                    :counters (fixture/counter-snapshot)})))
        (.then
         (fn [_]
           (let [hot-deltas  (deltas hot before)
                 cold-deltas (deltas cold before)
                 idle-deltas (deltas idle before)
                 counters    (fixture/counter-snapshot)
                 projection  {:enrolled (:pending @pre)
                              :advances (count (filter #(= 1 %) hot-deltas))
                              :revision-delta (reduce + (deltas all before))
                              :hot-renders (:hot-body counters)
                              :hot-renders-by-index (:hot-body-by-index counters)
                              :cold-renders (:cold-body counters)
                              :root-commits (:commits counters)
                              :hot-base (:hot-base counters)
                              :stable-parent (:stable-parent counters)
                              :cold-leaf (:cold-leaf counters)
                              :port-fan-out (:port-fan-out @pre)
                              :fan-out-cells (:fan-out-cells @pre)
                              ;; rf2-vxgfnd.250 — the production-erased port
                              ;; candidate-inspection witness. The compiled-view
                              ;; commit reconciler probes/reads/current?-checks
                              ;; f(C) candidates per drain, INDEPENDENT of V; a
                              ;; source mutant that scans all V mounted cells (even
                              ;; delivering to only C) inflates these past f(C),
                              ;; which the V-independence check below turns red.
                              :port-candidate-inspections
                              (obs/g13-candidate-inspection-snapshot)}
                 expected    {:enrolled 8 :advances 8 :revision-delta 8
                              :hot-renders 8
                              :hot-renders-by-index (vec (repeat 8 1))
                              :cold-renders 0 :root-commits 1
                              :hot-base 8 :stable-parent 8 :cold-leaf 0
                              :port-fan-out 64 :fan-out-cells 8
                              :port-candidate-inspections
                              {:probe 8 :read 8 :current? 16}}]
             (ensure! (= {:pending 8 :hot-dirty 8 :cold-dirty 0 :idle-dirty 0
                          :revision-delta 0
                          :evidence-counts (vec (repeat 8 8))
                          :port-fan-out 64 :fan-out-cells 8
                          :counters {:writes 8 :hot-base 8 :stable-parent 8
                                     :cold-leaf 0 :hot-body 0 :cold-body 0
                                     :hot-body-by-index (vec (repeat 8 0))
                                     :commits 0}}
                         @pre)
                      "update and commit phases did not coalesce before the render phase"
                      {:v v :label label :pre @pre})
             (ensure! (= expected projection) "post-drain work projection drift"
                      {:v v :label label :expected expected :actual projection})
             (ensure! (every? zero? cold-deltas) "a cold ViewCell advanced"
                      {:v v :label label :cold-deltas cold-deltas})
             (ensure! (every? zero? idle-deltas)
                      "an ownership-empty fixed-shell ViewCell advanced"
                      {:v v :label label :idle-deltas idle-deltas})
             (ensure! (zero? (reactive/pending-cell-count))
                      "dirty registry did not drain" {:v v :label label})
             (let [published (str (:hot (rf/app-db-value frame)))]
               (doseq [i (range fixture/hot-count)]
                 (ensure! (= published
                             (.-textContent
                              (.querySelector
                               root
                               (str "[data-g13-kind='hot'][data-g13-index='"
                                    i "']"))))
                          "a hot DOM row did not publish the eighth queued write"
                          {:v v :label label :index i :expected published})))
             (ensure! (= (str "cold-" (dec v))
                         (.-textContent
                          (.querySelector root (str "[data-g13-index='" (dec v) "']"))))
                      "cold DOM changed during a hot-only drain"
                      {:v v :label label})
             {:label label
              :fixed-shell-idle-cells (count idle)
              :projection projection}))))))

(defn- sample!
  "One recorded sample: the CLEAN timing cycle FIRST, then the untimed
  correctness cycle (exact-work projection + DOM proof). Timing runs before
  correctness so the reported `:elapsed-ms` is a true first-drain
  dispatch-to-commit span — never the second dispatch after this sample's own
  O(V) audit, DOM read, and cache/React warm-up. `:timing-pre-hot` is the
  witness `timing-cycle!` read of app-db `:hot` immediately BEFORE its own timed
  dispatch (outside the measured interval) and returned WITH that cycle
  (rf2-6k4cm): the mount seed leaves it 0 and every drain adds `queued-writes`,
  so for the `cold` sample it is 0 — the CAUSAL proof that the timer measured the
  FIRST post-mount dispatch. Because it rides the timed cycle rather than being
  read at `sample!` entry, running correctness before timing advances it to
  `queued-writes` and the cold-first control fails; it cannot pass vacuously.
  The exact counts still come from a cycle never on the clock.

  rf2-a0i2y — `:timing-post-hot` is that same timed cycle's CLOSING witness, read
  after its end timestamp. Every sample therefore carries the pair the runner
  needs to prove the measured interval contained its own dispatch and commit:
  `timing-post-hot` minus `timing-pre-hot` must be `queued-writes` for THIS cycle."
  [root frame v label]
  ;; rf2-6k4cm — the timed cycle runs FIRST and reports its OWN pre-hot witness;
  ;; `:timing-pre-hot` is that witness, so it is causal to the measured dispatch.
  ;; Inverting these two stages (correctness before timing) would advance the
  ;; witness off the mount seed and redden the cold-first control — it can no
  ;; longer stay 0 while the timer measures a warmed second drain.
  (-> (timing-cycle! frame)
      (.then (fn [{:keys [elapsed-ms pre-hot post-hot]}]
               (-> (correctness-cycle! root frame v label)
                   (.then (fn [c]
                            (assoc c :elapsed-ms elapsed-ms
                                   :timing-pre-hot pre-hot
                                   :timing-post-hot post-hot))))))))

;; rf2-6k4cm — the CAUSAL cold-first order control. The gate's `assertColdIsFirstDrain`
;; rests on `:timing-pre-hot` being 0 for the cold sample. Forging that field, or
;; asserting a value read before either cycle, does NOT prove the witness tracks the
;; timed dispatch — it can stay 0 while the timer secretly measures a warmed second
;; drain. This control runs the two cycles in BOTH orders on FRESH frames and returns
;; each timed cycle's OWN witness. Because the witness now rides `timing-cycle!`'s
;; dispatch, the two orders DIVERGE: timing-first witnesses the mount seed 0,
;; correctness-first witnesses `queued-writes`. The runner asserts the divergence, so
;; a non-causal witness (0 in both) is rejected. This is source/order evidence — it
;; runs correctness before timing for real, not a JSON mutation.
(defn- timed-witness!
  "Mount a FRESH v-sized fixture, run the two cycles in `order` (`:timing-first`
  or `:correctness-first`), and return the TIMED cycle's own witness PAIR
  `{:pre-hot _ :post-hot _}` (rf2-a0i2y — the closing witness rides both order
  paths too, so each fresh-frame path proves its own timed interval did the work,
  not merely which state preceded it). Tears the frame down on every exit."
  [v order]
  (let [frame (rf/make-frame {:initial-events [[:rf/set-db (fixture/seed v)]]})]
    (-> (uit/with-root [root [ui/frame-provider {:frame frame}
                              [fixture/app {:v v}]]]
          (if (= order :correctness-first)
            ;; A correctness drain advances app-db :hot off the mount seed BEFORE
            ;; the timed cycle runs; the timed cycle must then witness that advance.
            (-> (correctness-cycle! root frame v "order-control")
                (.then (fn [_] (timing-cycle! frame)))
                (.then (fn [timed] (select-keys timed [:pre-hot :post-hot]))))
            ;; The true cold order: the timed cycle runs first, so it witnesses 0.
            (-> (timing-cycle! frame)
                (.then (fn [timed] (select-keys timed [:pre-hot :post-hot]))))))
        (.then (fn [witness]
                 (rf/destroy-frame! frame)
                 witness)
               (fn [e]
                 (rf/destroy-frame! frame)
                 (throw e))))))

(defn- order-control!
  "Report each order's timed-cycle witness pair for the runner's causal
  cold-first control: `:timing-first` (`:pre-hot` 0, passes the cold-first
  assertion) and `:correctness-first` (`:pre-hot` `queued-writes`, fails it).
  Both pairs also carry the rf2-a0i2y closing witness, so each path proves its
  own timed interval advanced app-db by exactly `queued-writes`."
  [v]
  (-> (timed-witness! v :timing-first)
      (.then (fn [timing-first]
               (-> (timed-witness! v :correctness-first)
                   (.then (fn [correctness-first]
                            {:timing-first timing-first
                             :correctness-first correctness-first})))))))

(defn- run-size! [v]
  (let [frame (rf/make-frame {:initial-events [[:rf/set-db (fixture/seed v)]]})
        cold* (atom nil)]
    (-> (uit/with-root [root [ui/frame-provider {:frame frame}
                              [fixture/app {:v v}]]]
          (-> (sample! root frame v "cold")
              (.then
               (fn [cold]
                 (reset! cold* cold)
                 (reduce (fn [p i]
                           (.then p (fn [xs]
                                      (-> (sample! root frame v (str "warmup-" i))
                                          (.then #(conj xs %))))))
                         (js/Promise.resolve [])
                         (range warmups))))
              (.then
               (fn [_]
                 (reduce (fn [p i]
                           (.then p (fn [xs]
                                      (-> (sample! root frame v (str "sample-" i))
                                          (.then #(conj xs %))))))
                         (js/Promise.resolve [])
                         (range recorded-samples))))
              (.then
               (fn [samples]
                 (let [raw (mapv :elapsed-ms samples)
                       ;; rf2-vxgfnd.212 — stamp the ACTUAL mounted graph size
                       ;; the fixture rendered (`data-g13-v`), never the runner's
                       ;; expected metadata. A harness bug that mounts one size
                       ;; twice then reports it as two therefore stamps the same
                       ;; V twice, and the runner's exact-roster check rejects it
                       ;; before any projection comparison.
                       mounted-v (js/parseInt
                                  (.getAttribute
                                   (.querySelector root "[data-g13-v]") "data-g13-v")
                                  10)]
                   ;; rf2-vxgfnd.213 — the browser emits only the RAW warm
                   ;; dispatch-to-commit samples; the runner is the single owner
                   ;; of the (stated, nearest-rank) quantile convention and folds
                   ;; p50/p95 back on. No percentile is computed here, so there
                   ;; is exactly one convention and it is unit-tested.
                   {:v mounted-v
                    :cold @cold*
                    :warm {:raw-ms raw}
                    :samples samples})))))
        (.then (fn [result]
                 (rf/destroy-frame! frame)
                 result)
               (fn [e]
                 (rf/destroy-frame! frame)
                 (throw e))))))

(defn- execute! []
  (fixture/register!)
  (rf/init! ui/adapter)
  (-> (reduce (fn [p v]
                (.then p (fn [xs]
                           (-> (run-size! v) (.then #(conj xs %))))))
              (js/Promise.resolve [])
              sizes)
      (.then
       (fn [results]
         ;; rf2-6k4cm — run the causal cold-first order control on a fresh frame
         ;; (both orders), then fold its witnesses into the result the runner asserts.
         (-> (order-control! (first sizes))
             (.then
              (fn [order]
                (let [projections (mapv #(get-in % [:cold :projection]) results)]
                  (ensure! (apply = projections)
                           "candidate-work projection depends on V"
                           {:projections projections})
                  {:gate "G-13"
                   :status "pass"
                   :sizes sizes
                   :queued-writes fixture/queued-writes
                   :affected-viewcells fixture/hot-count
                   :fixed-shell-idle-cells fixture/idle-shell-count
                   ;; rf2-6k4cm — the timed-cycle witness under both cycle orders.
                   ;; `:timing-first` is the real cold order (:pre-hot 0);
                   ;; `:correctness-first` is a drain before the timer (:pre-hot
                   ;; queued-writes). The runner proves the cold-first control accepts
                   ;; the former and rejects the latter. rf2-a0i2y — each pair also
                   ;; carries `:post-hot`, so both fresh-frame paths prove their own
                   ;; timed interval advanced app-db by exactly queued-writes.
                   :cold-first-order-control order
                   :timing-posture "evidence-only; no threshold"
                   ;; rf2-vxgfnd.210 — the explicit candidate-work axis plus the HONEST
                   ;; scope of what the counts prove. `port-fan-out` (C*Q, summed over
                   ;; all V live cells with the V−C non-affected cells contributing
                   ;; zero) is the named V-independent candidate-work projection. The
                   ;; counts prove V-independent DELIVERED push work (fan-out
                   ;; occurrences + sub recomputes + renders + one commit). A pure
                   ;; membership SCAN that inspects V candidates but delivers to only C
                   ;; is observable only by a production-side counter at the port's
                   ;; candidate-iteration choke point — out of this gate's reach — and
                   ;; is tracked by timing evidence alone, never asserted by a count
                   ;; nor by a wall-clock threshold.
                   :candidate-work-projection "port-fan-out"
                   :proof-scope
                   "v-independent delivered push work; pure candidate scan is timing-evidence-only"
                   :results results}))))))))

(defn -main []
  (-> (execute!)
      (.then (fn [result]
               (unchecked-set js/globalThis
                              "__RF2_G13_RESULT_SENTINEL__"
                              (clj->js result))))
      (.catch (fn [e]
                (unchecked-set js/globalThis "__RF2_G13_ERROR__"
                               (or (.-stack e) (str e)))))))
