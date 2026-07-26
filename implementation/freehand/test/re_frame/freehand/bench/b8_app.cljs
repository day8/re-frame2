(ns re-frame.freehand.bench.b8-app
  "B8's `:advanced` entry — mounts the update arms once and hands the page
  to `b8_run.cjs`.

  The driver owns every window boundary, because the page cannot force a
  garbage collection and therefore cannot decide when a window starts.
  This namespace only mounts, seeds, and exposes the doors."
  (:require [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b6-rows :as rows]
            [re-frame.freehand.bench.b8-alloc :as b8]))

(defn ^:export -main
  []
  (try
    (rf/init! react-substrate/adapter)
    (h/leave-act-environment!)
    (let [arms   (b8/make-arms)
          mounts (rows/mount-update-arms! arms)
          by-id  (into {} (map (fn [m] [(name (:id (:arm m))) m])) mounts)]
      (set! (.-B8 js/window)
            #js {:mem     (fn [] (b8/mem))
                 :arms    (clj->js (mapv #(name (:id %)) arms))
                 :prepare (fn [d] (b8/control-prepare! d))
                 ;; One window: `n` writes on one arm, counter read at every
                 ;; leg boundary. The driver collects before and reads after.
                 :run     (fn [arm kind n]
                            (b8/run-window! (get by-id arm) (keyword kind) n))
                 ;; The same window through the PUBLISHED timed-write!,
                 ;; unmirrored, for the cross-check.
                 :runPub  (fn [arm kind n]
                            (b8/run-published-window! (get by-id arm) (keyword kind) n))
                 ;; Re-seed every arm to a known state between windows so no
                 ;; window inherits another's growth.
                 :seed    (fn [] (rows/seed-update-arms! mounts))})
      (-> (rows/seed-update-arms! mounts)
          (.then (fn [_]
                   (set! (.-B8_READY js/window) true)
                   (js/console.log ";; B8 READY")
                   nil))))
    (catch :default e
      (set! (.-B8_ERROR js/window) (str e))
      (js/console.error (str ";; B8 FAILED — " e)))))
