(ns re-frame.bench.hicasso.parity-probe-app
  "DIAGNOSTIC ONLY (rf2-6c237): reproduce the census clock's boot parity
  refusal and print the first canonical-DOM divergence per row, so the
  disagreement is a readable diff instead of a boolean. Mounts the same
  arms through the same doors as `census_clock_arms/parity-at`; prints and
  exits. Not a witness, not a clock; deleted or kept as lane tooling at
  the reviewer's pleasure."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.census-clock-arms :as arms]
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
  (rf/init! uix-adapter/adapter)
  (lane/leave-act-environment!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (arms/ensure-frames! [:hicasso :uix])
          (doseq [row [:large-template :feed :ordinary]]
            (let [seed (:seed (get arms/rows row))
                  _    (arms/reseed-row! row [:hicasso :uix] seed)
                  built [(arms/arm row :uix) (arms/arm row :hicasso)]
                  p    (lane/parity built {})
                  cu   (get (:canon p) :uix)
                  ch   (get (:canon p) :hicasso)]
              (doseq [m (:mounts p)] (lane/release! m))
              (js/console.log (str ";; ROW " (name row)
                                   " agree? " (:agree? p)
                                   " uix-bytes " (count cu)
                                   " hicasso-bytes " (count ch)))
              (when-not (:agree? p)
                (let [i (first-diff cu ch)]
                  (js/console.log (str ";; first diff at " i))
                  (js/console.log (str ";; UIX     ...[" (window cu i) "]..."))
                  (js/console.log (str ";; HICASSO ...[" (window ch i) "]..."))))))
          (lane/done!)))
      (.catch (fn [e]
                (lane/fail! (or (some-> e .-message) (str e)))
                (lane/done!)))))
