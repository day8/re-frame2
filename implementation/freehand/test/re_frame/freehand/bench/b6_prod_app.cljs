(ns re-frame.freehand.bench.b6-prod-app
  "B6's PRODUCTION entry — the same rows, taken from an `:advanced` bundle
  with `goog.DEBUG false`.

  This namespace exists because the numbers B6 publishes must come from
  the artefact a consumer ships, and B6's ordinary browser suite cannot
  supply them. Two independent reasons:

  1. **Development builds systematically penalise Freehand.** The Spec 009
     instrumentation seam, schema validation and trace emission are all
     `goog.DEBUG`-gated, and all of them sit on the subscription/render
     path the update row measures. Reagent has no counterpart to any of
     them. Publishing a development ratio would report a cost no user
     pays and would do it in one direction only.
  2. **`:advanced` cannot compile shadow's `:browser-test` target** —
     `cljs-test-display`'s `goog.define`s collide under the Closure
     compiler. So the production reading needs a plain `:browser` entry,
     which is this one.

  Nothing here is a second harness. Every arm, every witness and both
  measurements are [[re-frame.freehand.bench.b6-rows]]'s, unchanged; this
  file installs the adapter, runs them, and parks the records on
  `window.B6_RESULTS` for the driver to read. The DETERMINISTIC gates
  stay where they belong, in the `-dom-cljs-test` namespaces the ordinary
  browser suite runs — but the parity check is repeated here too, because
  a parity that held under `:none` and failed under `:advanced` would be
  a renaming bug silently deciding the comparison.

  Built and driven by
  `implementation/freehand/test/re_frame/freehand/bench/b6_prod_run.cjs`.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b6-rows :as rows]))

(def ^:private rounds 6)
(def ^:private mount-sampling {:warmup 5 :samples 20})
(def ^:private update-sampling {:warmup 4 :samples 12})

(defn- fail! [why]
  (set! (.-B6_ERROR js/window) (str why))
  (js/console.error (str ";; B6 PROD FAILED — " why)))

(defn- record! [k v]
  (let [acc (or (.-B6_RESULTS js/window) #js {})]
    (aset acc (name k) (pr-str v))
    (set! (.-B6_RESULTS js/window) acc)
    v))

(defn- parity-of!
  "Re-run the mount parity gate under `:advanced`. Answers a vector of
  problems, empty when every arm still builds one page."
  []
  (reduce
    (fn [problems {:keys [id props elements] :as w}]
      (let [{:keys [mounts agree? counts disagree]} (rows/mount-parity w props)]
        (try
          (cond-> problems
            (not agree?)
            (conj {:witness id :problem :canonical-dom-disagreement :arms disagree})

            (not (every? #(= elements %) (vals counts)))
            (conj {:witness id :problem :element-count :expected elements :got counts}))
          (finally (doseq [m mounts] (h/release! m))))))
    []
    rows/mount-witnesses))

(defn ^:export -main
  []
  (try
    (rf/init! react-substrate/adapter)
    (h/leave-act-environment!)
    (let [problems (parity-of!)]
      (record! :parity {:problems problems :ok? (empty? problems)})
      (if (seq problems)
        (fail! (str "the arms do not build the same page under :advanced — " (pr-str problems)))
        (do
          (record! :mount
                   (into {} (map (fn [w]
                                   (let [{:keys [id record]} (rows/measure-mount!
                                                               w rounds mount-sampling)]
                                     [id record])))
                         rows/mount-witnesses))
          (let [mounts (rows/mount-update-arms! (rows/make-update-arms))]
            (-> (rows/write-and-compare! mounts)
                (.then (fn [{:keys [ids before after]}]
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
                          (fail! (str "update rows rejected: " e))
                          (set! (.-B6_DONE js/window) true))))))))
    (catch :default e
      (fail! (str "prod run threw: " e))
      (set! (.-B6_DONE js/window) true))))
