(ns re-frame.freehand.bench.b9-app
  "B9's `:advanced` entry — SCAFFOLDING for `rf2-so3io`.

  One bundle, two modes, both of them existing instruments with extra
  arms:

  - `?mode=heap` installs `window.B7H` over
    [[re-frame.freehand.bench.b9-nc/heap-arms]] — B7's own door, B7's own
    collector, B7's own positive control, B7's own DOM read-back.
  - the default mode runs B6's update rows over
    [[re-frame.freehand.bench.b9-nc/make-update-arms]] — B6's own
    `timed-write!` window, B6's own interleaving, B6's own floor
    normalisation — and prints the sync-lane probe beside them.

  Built and driven by
  `implementation/freehand/test/re_frame/freehand/bench/b6_prod_run.cjs`
  and `b7_run.cjs`, both pointed here through their `*_INIT_FN` /
  `*_ARMS` environment seams. Neither driver's defaults change."
  (:require [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b6-rows :as rows]
            [re-frame.freehand.bench.b9-nc :as nc]))

(defn- q
  "A query-string integer, or `default` — B7's own smoke seam. The
  published run takes the defaults, which are B6's."
  [k default]
  (if-some [v (.get (js/URLSearchParams. (.-search js/location)) k)]
    (js/parseInt v 10)
    default))

(defn- fail! [why]
  (set! (.-B6_ERROR js/window) (str why))
  (js/console.error (str ";; B6 PROD FAILED — " why)))

(defn- record! [k v]
  (let [acc (or (.-B6_RESULTS js/window) #js {})]
    (aset acc (name k) (pr-str v))
    (set! (.-B6_RESULTS js/window) acc)
    v))

(defn- flag? [k]
  (= "1" (.get (js/URLSearchParams. (.-search js/location)) k)))

(defn- run-mount!
  "B6's mount measurement over the W2 storm, with the two B9 rungs added.
  Gated on B6's own canonical-DOM parity before a clock is read."
  [rounds sampling reversed?]
  (let [w                (nc/mount-witness reversed?)
        {:keys [mounts agree? counts disagree]} (rows/mount-parity w (:props w))
        problems (cond-> []
                   (not agree?) (conj {:problem :canonical-dom :arms disagree})
                   (not (every? #(= (:elements w) %) (vals counts)))
                   (conj {:problem :element-count :expected (:elements w) :got counts}))]
    (doseq [m mounts] (h/release! m))
    (record! :mount-parity {:problems problems :ok? (empty? problems) :counts counts})
    (if (seq problems)
      (fail! (str "mount arms do not build the same page: " (pr-str problems)))
      (record! :mount-storm (:record (rows/measure-mount! w rounds sampling))))))

(defn- run-clock! []
  (let [rounds          (q "rounds" 6)
        update-sampling {:warmup (q "warmup" 4) :samples (q "samples" 12)}
        mount-sampling  {:warmup (q "warmup" 5) :samples (q "samples" 20)}
        reversed?       (flag? "reverse")
        ;; The mount row runs FIRST and on an otherwise empty page: the
        ;; update grids would otherwise stand behind it, six roots of 300
        ;; boundaries each, while a mount is timed.
        _               (do (record! :arm-order (if reversed? :reversed :forward))
                            (run-mount! rounds mount-sampling reversed?))
        mounts          (rows/mount-update-arms! (nc/make-update-arms reversed?))]
    (-> (nc/sync-lane-probe!)
        (.then (fn [probe]
                 (record! :sync-lane-probe probe)
                 (js/console.log (str ";; B6 sync-lane probe " (pr-str probe)))
                 (rows/write-and-compare! mounts)))
        (.then (fn [{:keys [ids before after]}]
                 ;; THE FAIRNESS GATE, and it is not ceremony: an ablation arm
                 ;; that rendered a different page — or no page — would read as
                 ;; the fastest substrate in the table. B6's own canonical-DOM
                 ;; comparison, over the extended arm set.
                 (record! :update-parity {:ids ids :agree? (apply = after)
                                          :moved? (mapv not= before after)})
                 (when-not (apply = after)
                   (fail! (str "update arms disagree under :advanced: " (pr-str ids))))
                 (when (some true? (map = before after))
                   (fail! "an update arm's DOM did not change under :advanced"))
                 (rows/measure-update!
                   mounts :broad "one write that all 300 cells read"
                   rounds update-sampling)))
        (.then (fn [res]
                 (record! :update-broad (:record res))
                 (rows/measure-update!
                   mounts :narrow "one write exactly one of 300 cells reads"
                   rounds update-sampling)))
        (.then (fn [res]
                 (record! :update-narrow (:record res))
                 (rows/release-update-arms! mounts)
                 (set! (.-B6_DONE js/window) true)
                 (js/console.log ";; B6 PROD DONE")
                 nil))
        (.catch (fn [e]
                  (rows/release-update-arms! mounts)
                  (fail! (str "b9 clock rows rejected: " e))
                  (set! (.-B6_DONE js/window) true))))))

(defn- mode []
  (or (.get (js/URLSearchParams. (.-search js/location)) "mode") "clock"))

(defn ^:export -main
  []
  (try
    (rf/init! react-substrate/adapter)
    (h/leave-act-environment!)
    (case (mode)
      "heap" (do (nc/install-heap-door!)
                 (js/console.log ";; B7 heap instrument installed (B9 arms)")
                 (set! (.-B7_READY js/window) true))
      (run-clock!))
    (catch :default e
      (fail! (str "b9 run threw: " e))
      (set! (.-B6_DONE js/window) true)
      (set! (.-B7_READY js/window) true))))
