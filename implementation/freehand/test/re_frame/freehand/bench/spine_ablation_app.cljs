(ns re-frame.freehand.bench.spine-ablation-app
  "The UIx spine per-read ablation's `:advanced` entry — one bundle, one
  substrate per page load.

  Read from the artefact a consumer ships (`:advanced`, `goog.DEBUG
  false`) for the reason B7's entry gives: Spec 009 instrumentation,
  schema validation and trace emission are all `goog.DEBUG`-gated, all sit
  on the subscription path being priced, and a development build would
  charge the re-frame2 side of every arm for machinery a release bundle
  does not contain. `:advanced` cannot compile shadow's `:browser-test`
  target, so the reading needs a plain `:browser` entry — this one.

  `?adapter=uix` and `?adapter=reagent` are two PAGE LOADS of the same
  bundle, not two modes. `rf/init!` installs at most one adapter per JS
  context and silently keeps the first, so one page cannot host both; a
  fresh context per substrate is the only honest way to run them, and each
  page carries its own floor arm so no subtraction crosses the page
  boundary.

  The page installs the instrument on `window.ABL` and then does nothing
  at all. Heap readings belong to the DRIVER, which owns the collector: a
  page cannot force a garbage collection, and a page that tried to decide
  when a reading was taken would be reading whatever the collector
  happened to have done.

  Built and driven by
  `implementation/freehand/test/re_frame/freehand/bench/spine_ablation_run.cjs`."
  (:require [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.freehand.bench.spine-ablation :as abl]))

(defn- fail! [why]
  (set! (.-ABL_ERROR js/window) (str why))
  (js/console.error (str ";; ABLATION FAILED — " why)))

(defn- requested-substrate []
  (let [v (.get (js/URLSearchParams. (.-search js/location)) "adapter")]
    (case v
      "reagent" :reagent
      "uix"     :uix
      nil)))

(defn ^:export -main []
  (try
    (if-some [substrate (requested-substrate)]
      (do
        (rf/init! (case substrate
                    :reagent reagent-adapter/adapter
                    :uix     uix-adapter/adapter))
        (abl/ensure-frame!)
        (abl/install! substrate)
        (set! (.-ABL_READY js/window) true))
      (fail! (str "no ?adapter=uix|reagent on the query string — this bundle "
                  "runs one substrate per page load")))
    (catch :default e
      (fail! (str "boot threw: " (.-message e))))))
