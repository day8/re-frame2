(ns re-frame.freehand.bench.b7-app
  "B7's `:advanced` entry — one bundle, two modes.

  Both B7 rows have to be read from the artefact a consumer ships
  (`:advanced`, `goog.DEBUG false`) for the reason B6's production entry
  gives: Spec 009 instrumentation, schema validation and trace emission
  are all `goog.DEBUG`-gated, all sit on the paths being measured, and
  Reagent has no counterpart to any of them, so a development build
  penalises Freehand in one direction only. And `:advanced` cannot compile
  shadow's `:browser-test` target, so the reading needs a plain `:browser`
  entry — this one.

  `?mode=mount-frame` runs `rf2-prjh0`'s row to completion in the page and
  parks the records on `window.B7_RESULTS`.

  `?mode=heap` installs `rf2-9oj7v`'s retention instrument on
  `window.B7H` and then does nothing at all. Heap readings belong to the
  DRIVER, which owns the collector: the page cannot force a garbage
  collection, and a page that tried to decide when a heap reading was
  taken would be reading whatever the collector happened to have done.

  Built and driven by
  `implementation/freehand/test/re_frame/freehand/bench/b7_run.cjs`."
  (:require [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b7-heap :as heap]
            [re-frame.freehand.bench.b7-mount-frame :as mf]))

(defn- q
  "A query-string integer, or `default`. The published run takes the
  defaults — B6's own sampling, so the two rows are comparable — and the
  overrides exist so a smoke run can prove the harness wires up without
  paying for six rounds of it."
  [k default]
  (if-some [v (.get (js/URLSearchParams. (.-search js/location)) k)]
    (js/parseInt v 10)
    default))

(defn- fail! [why]
  (set! (.-B7_ERROR js/window) (str why))
  (js/console.error (str ";; B7 FAILED — " why)))

(defn- record! [k v]
  (let [acc (or (.-B7_RESULTS js/window) #js {})]
    (aset acc (name k) (pr-str v))
    (set! (.-B7_RESULTS js/window) acc)
    v))

(defn- run-mount-frame!
  []
  (mf/ensure-shared-frame!)
  (let [rounds   (q "rounds" 6)
        sampling {:warmup (q "warmup" 5) :samples (q "samples" 20)}
        problems (into [] (mapcat mf/parity-problems) mf/witnesses)]
    (record! :parity {:problems problems :ok? (empty? problems)})
    (if (seq problems)
      (fail! (str "the arms do not build the same page under :advanced — " (pr-str problems)))
      (doseq [w mf/witnesses]
        (record! (:id w) (:record (mf/measure! w rounds sampling)))))))

(defn- mode []
  (or (.get (js/URLSearchParams. (.-search js/location)) "mode") "mount-frame"))

(defn ^:export -main
  []
  (try
    (rf/init! react-substrate/adapter)
    (h/leave-act-environment!)
    (case (mode)
      "heap" (do (heap/install!)
                 (js/console.log ";; B7 heap instrument installed")
                 (set! (.-B7_READY js/window) true))
      (do (run-mount-frame!)
          (set! (.-B7_DONE js/window) true)
          (js/console.log ";; B7 DONE")))
    (catch :default e
      (fail! (str "b7 run threw: " e))
      (set! (.-B7_DONE js/window) true)
      (set! (.-B7_READY js/window) true))))
