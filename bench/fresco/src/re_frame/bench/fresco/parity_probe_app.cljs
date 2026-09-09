(ns re-frame.bench.hicasso.parity-probe-app
  "DIAGNOSTIC ONLY (rf2-6c237): reproduce the census clock's boot parity
  refusal and print the first canonical-DOM divergence per row, so the
  disagreement is a readable diff instead of a boolean. Mounts the same
  arms through the same doors as `census_clock_arms/parity-at`; prints and
  exits. Not a witness, not a clock; deleted or kept as lane tooling at
  the reviewer's pleasure."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.shapes.census-clock-arms :as rf.bench.hicasso.shapes.census-clock-arms]
            [re-frame.core :as rf]))

(defn- first-diff [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (cond
        (== i n) (if (== (count a) (count b)) -1 n)
        (identical? (.charAt a i) (.charAt b i)) (recur (inc i))
        :else i))))

(defn- window [s i]
  (let [from (max 0 (- i 120))
        to   (min (count s) (+ i 160))]
    (subs s from to)))

(defn ^:export -main []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.hicasso.lane/leave-act-environment!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (rf.bench.hicasso.shapes.census-clock-arms/ensure-frames! [:hicasso :uix])
          (doseq [row [:large-template :feed :ordinary]]
            (let [seed (:seed (get rf.bench.hicasso.shapes.census-clock-arms/rows row))
                  _    (rf.bench.hicasso.shapes.census-clock-arms/reseed-row! row [:hicasso :uix] seed)
                  built [(rf.bench.hicasso.shapes.census-clock-arms/arm row :uix) (rf.bench.hicasso.shapes.census-clock-arms/arm row :hicasso)]
                  p    (rf.bench.hicasso.lane/parity built {})
                  cu   (get (:canon p) :uix)
                  ch   (get (:canon p) :hicasso)]
              (doseq [m (:mounts p)] (rf.bench.hicasso.lane/release! m))
              ;; RELABELLED, not converted (rf2-2rtt6.121). These two
              ;; numbers exist to be read beside `first diff at i`, and `i`
              ;; is a `.charAt` index — a code-unit offset. Stating them in
              ;; bytes would put the length and the offset it locates on
              ;; two different rulers, which is worse than either alone.
              (js/console.log (str ";; ROW " (name row)
                                   " agree? " (:agree? p)
                                   " uix-code-units " (count cu)
                                   " hicasso-code-units " (count ch)))
              (when-not (:agree? p)
                (let [i (first-diff cu ch)]
                  (js/console.log (str ";; first diff at " i))
                  (js/console.log (str ";; UIX     ...[" (window cu i) "]..."))
                  (js/console.log (str ";; HICASSO ...[" (window ch i) "]..."))))))
          (rf.bench.hicasso.lane/done!)))
      (.catch (fn [e]
                (rf.bench.hicasso.lane/fail! (or (some-> e .-message) (str e)))
                (rf.bench.hicasso.lane/done!)))))
