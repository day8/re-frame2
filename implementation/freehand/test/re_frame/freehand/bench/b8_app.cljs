(ns re-frame.freehand.bench.b8-app
  "B8's `:advanced` entry — mounts the update arms once and hands the page
  to `b8_run.cjs`.

  The driver owns every window boundary, because the page cannot force a
  garbage collection and therefore cannot decide when a window starts.
  This namespace only mounts, seeds, and exposes the doors.

  Every name crossing the JS boundary is a STRING LITERAL through
  `js-obj` / `goog.object`, never a `#js {:kw …}` literal read back with
  `(.-kw o)`. Under `:advanced` those two disagree — the compiled
  evidence is recorded at the head of
  [[re-frame.freehand.bench.b8-alloc]] — and the disagreement is SILENT
  for any key that happens to collide with Closure's browser externs."
  (:require [goog.object :as gobj]
            [re-frame.adapter.uix :as react-substrate]
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
      (gobj/set js/window "B8"
        (js-obj
          "mem"       (fn [] (b8/mem))
          "arms"      (clj->js (mapv #(name (:id %)) arms))
          ;; Run before any measurement: proves this bundle reads the keys
          ;; it writes, under the renaming it was actually compiled with.
          "integrity" (fn [] (b8/integrity-probe))
          "prepare"   (fn [d] (b8/control-prepare! d))
          ;; The control object priced by RETENTION instead — B7's
          ;; instrument, on the same object, so the two can be compared.
          "hold"      (fn [k] (b8/control-hold! k))
          "unhold"    (fn [] (b8/control-release!))
          ;; The decisive self-test: the same loop with the control's
          ;; copies kept and with them dropped.
          "keep"      (fn [b] (b8/control-keep! b))
          "kept"      (fn [] (b8/control-kept-count))
          ;; One window: `n` writes on one arm, counter read at every leg
          ;; boundary. The driver collects before and reads after.
          "run"       (fn [arm kind n]
                        (b8/run-window! (get by-id arm) (keyword kind) n))
          ;; The same window through the PUBLISHED timed-write!, unmirrored,
          ;; for the cross-check.
          "runPub"    (fn [arm kind n]
                        (b8/run-published-window! (get by-id arm) (keyword kind) n))
          ;; Re-seed every arm to a known state between windows so no window
          ;; inherits another's growth.
          "seed"      (fn [] (rows/seed-update-arms! mounts))))
      (-> (rows/seed-update-arms! mounts)
          (.then (fn [_]
                   (gobj/set js/window "B8_READY" true)
                   (js/console.log ";; B8 READY")
                   nil))))
    (catch :default e
      (gobj/set js/window "B8_ERROR" (str e))
      (js/console.error (str ";; B8 FAILED — " e)))))
