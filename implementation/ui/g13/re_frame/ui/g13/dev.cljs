(ns re-frame.ui.g13.dev
  "Development-half of G-13: exact push-work counts plus timing evidence.
  Timing is descriptive only; correctness rests entirely on deterministic
  counts before and after the single read/render commit."
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui]
            [re-frame.ui.g13.fixture :as fixture]
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
                       cells)]
    (ensure! (= v (count cells)) "mounted ViewCell cardinality drift"
             {:v v :cells (count cells)})
    (ensure! (= fixture/hot-count (count hot)) "hot ViewCell cardinality drift"
             {:v v :hot (count hot)})
    (ensure! (= (- v fixture/hot-count) (count cold))
             "cold ViewCell cardinality drift"
             {:v v :cold (count cold)})
    {:all cells :hot hot :cold cold}))

(defn- deltas [cells before]
  (mapv #(- (reactive/revision %) (get before %)) cells))

(defn- sample! [root frame v label]
  (let [{:keys [all hot cold]} (split-cells v)
        before (into {} (map (juxt identity reactive/revision)) all)
        pre    (atom nil)
        started (js/performance.now)]
    (fixture/reset-counters!)
    (-> (uit/flush!
         (fn []
           ;; dispatch! completes all eight write epochs synchronously. The
           ;; read side has not run until flush!'s returned Promise advances.
           (uit/dispatch! frame [::fixture/step fixture/queued-writes])
           (reset! pre
                   {:pending (reactive/pending-cell-count)
                    :hot-dirty (count (filter reactive/dirty? hot))
                    :cold-dirty (count (filter reactive/dirty? cold))
                    :revision-delta (reduce + (deltas all before))
                    :evidence-counts
                    (mapv #(get (reactive/pending-evidence %) :count) hot)
                    :counters (fixture/counter-snapshot)})))
        (.then
         (fn []
           (let [hot-deltas  (deltas hot before)
                 cold-deltas (deltas cold before)
                 counters    (fixture/counter-snapshot)
                 projection  {:enrolled (:pending @pre)
                              :advances (count (filter #(= 1 %) hot-deltas))
                              :revision-delta (reduce + (deltas all before))
                              :hot-renders (:hot-body counters)
                              :cold-renders (:cold-body counters)
                              :root-commits (:commits counters)
                              :hot-base (:hot-base counters)
                              :stable-parent (:stable-parent counters)
                              :cold-leaf (:cold-leaf counters)}
                 expected    {:enrolled 8 :advances 8 :revision-delta 8
                              :hot-renders 8 :cold-renders 0 :root-commits 1
                              :hot-base 8 :stable-parent 8 :cold-leaf 0}]
             (ensure! (= {:pending 8 :hot-dirty 8 :cold-dirty 0
                          :revision-delta 0
                          :evidence-counts (vec (repeat 8 8))
                          :counters {:writes 8 :hot-base 8 :stable-parent 8
                                     :cold-leaf 0 :hot-body 0 :cold-body 0
                                     :commits 0}}
                         @pre)
                      "write side did not coalesce before the read side"
                      {:v v :label label :pre @pre})
             (ensure! (= expected projection) "post-drain work projection drift"
                      {:v v :label label :expected expected :actual projection})
             (ensure! (every? zero? cold-deltas) "a cold ViewCell advanced"
                      {:v v :label label :cold-deltas cold-deltas})
             (ensure! (zero? (reactive/pending-cell-count))
                      "dirty registry did not drain" {:v v :label label})
             (ensure! (= (str (* 8 fixture/queued-writes))
                         (.-textContent
                          (uit/query root "[data-g13-kind='hot']")))
                      "DOM did not reflect the eighth queued write"
                      {:v v :label label})
             {:label label
              :elapsed-ms (- (js/performance.now) started)
              :projection projection}))))))

(defn- percentile [xs p]
  (let [sorted (vec (sort xs))]
    (nth sorted (js/Math.floor (* p (dec (count sorted)))))))

(defn- run-size! [v]
  (let [frame (uit/frame {:app-db (fixture/seed v)})
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
                 (let [raw (mapv :elapsed-ms samples)]
                   {:v v
                    :cold @cold*
                    :warm {:raw-ms raw
                           :p50-ms (percentile raw 0.50)
                           :p95-ms (percentile raw 0.95)}
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
         (let [projections (mapv #(get-in % [:cold :projection]) results)]
           (ensure! (apply = projections)
                    "count projection depends on V"
                    {:projections projections})
           {:gate "G-13"
            :status "pass"
            :sizes sizes
            :queued-writes fixture/queued-writes
            :affected-viewcells fixture/hot-count
            :timing-posture "evidence-only; no threshold"
            :results results})))))

(defn -main []
  (-> (execute!)
      (.then (fn [result]
               (unchecked-set js/globalThis
                              "__RF2_G13_RESULT_SENTINEL__"
                              (clj->js result))))
      (.catch (fn [e]
                (unchecked-set js/globalThis "__RF2_G13_ERROR__"
                               (or (.-stack e) (str e)))))))
