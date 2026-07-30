(ns re-frame.bench.hicasso-narrow-app
  "The `:advanced` entry for the P0 ratom-spine narrow-write leg — mounts
  the arms once and hands the page to `hicasso_narrow_run.cjs`.

  The driver owns every window boundary, because only the driver can
  decide when a warm-up has settled and only the driver holds the plan.
  This namespace mounts, seeds, and exposes the doors.

  Every name crossing the JS boundary is a STRING LITERAL through
  `js-obj` / `goog.object`, never a `#js {:kw ...}` literal read back with
  `(.-kw o)`. Under `:advanced` those two disagree, and the disagreement
  is SILENT for any key that happens to collide with Closure's browser
  externs — the reasoning is recorded at the head of
  [[re-frame.bench.hicasso-narrow]]."
  (:require [goog.object :as gobj]
            [re-frame.bench.hicasso-narrow :as hn]
            [re-frame.bench.order-guard :as guard]))

(defonce ^:private mounts (volatile! nil))
(defonce ^:private by-id (volatile! {}))
(defonce ^:private neg-mount (volatile! nil))
(defonce ^:private collected (volatile! []))

(defn- samples->js
  "Only what the driver prints. The full sample set stays in the page and
  is folded there, so no per-sample map crosses the boundary."
  [ss]
  (clj->js (mapv (fn [s]
                   {"arm"      (name (:arm s))
                    "position" (:position s)
                    "order"    (name (:order s))
                    "total"    (hn/per-write (:total s) (:writes s))
                    "write"    (hn/per-write (:write s) (:writes s))
                    "gap"      (hn/per-write (:gap s) (:writes s))
                    "force"    (hn/per-write (:force s) (:writes s))
                    "control"  (hn/per-write (:control s) (:writes s))
                    "bad"      (:bad s)})
                 ss)))

(defn- warm-window!
  "One full-size window on one arm, discarded — the warm-up unit. Answers
  the per-write total in milliseconds so the driver can watch the
  trajectory settle."
  [arm-id writes]
  (-> (hn/run-sample! (get @by-id arm-id) writes)
      (.then (fn [a]
               (hn/per-write (gobj/get a "total") (gobj/get a "writes"))))))

(defn- run-round!
  "One round, and the driver's running POSITION COUNTER carried across it.

  The counter is threaded through the driver rather than kept here because
  the phase factor's whole question is *where in the RUN* — not where in
  the round — a sample was taken, and a per-round counter would restart the
  trajectory four times and ask nothing.

  `rows` is not called `samples`, and that is a repair rather than a style
  choice. It was: the destructured `{:keys [samples ...]}` shadowed the
  numeric `samples` PARAMETER, so `(+ warmup samples)` added a vector to a
  number, `next` came back `NaN`, and every position from round two onward
  was non-finite. The guard filters non-finite positions, so it went on
  reporting `24 samples` per arm while adjudicating the phase question on
  the SIX that survived — an `[ok]` verdict resting on a quarter of the
  evidence it named. Nothing threw and no figure looked wrong. The driver
  now prints the finite-position count per arm beside the strata, so the
  next version of this cannot hide."
  [warmup samples writes position0]
  (-> (hn/seed-arms! @mounts)
      (.then (fn [_] (hn/run-round! @mounts
                                    {:warmup warmup :samples samples :writes writes}
                                    position0)))
      (.then (fn [{rows :samples bad :bad}]
               (vswap! collected into rows)
               (js-obj "samples" (samples->js rows)
                       "bad"     bad
                       "next"    (+ position0
                                    (* (count @mounts) (+ warmup samples))))))))

(defn- negative-control!
  "Mount the deliberately-broken arm, run one window, unmount. Answers the
  unverified count and the total, so the driver can assert the read-back
  gate goes red on an empty `flushSync` — a gate that cannot fail has not
  verified anything."
  [writes]
  (let [mnt (first (hn/mount-arms! [(hn/negative-control-arm)]))]
    (vreset! neg-mount mnt)
    (-> (hn/run-sample! mnt writes)
        (.then (fn [a]
                 (let [bad (gobj/get a "bad")
                       n   (gobj/get a "writes")]
                   (hn/release-arms! [mnt])
                   (vreset! neg-mount nil)
                   (js-obj "bad" bad "writes" n)))))))

(defn- report!
  "Fold every collected sample, adjudicate the order question, and answer
  the record plus the guard's own report lines."
  [tolerance]
  (let [summary (hn/summarise-run @collected tolerance)
        v       (:guard summary)]
    (js-obj "edn"     (pr-str summary)
            "lines"   (clj->js (vec (guard/report-lines v "P0 ratom-spine narrow write")))
            "refuse"  (boolean (:refuse? v))
            "contaminated" (boolean (:contaminated? v))
            "unchecked"    (boolean (:unchecked? v)))))

(defn ^:export -main []
  (try
    (hn/init-adapter!)
    (let [arms (hn/make-arms)
          ms   (hn/mount-arms! arms)]
      (vreset! mounts ms)
      (vreset! by-id (into {} (map (fn [m] [(name (:id (:arm m))) m])) ms))
      (gobj/set js/window "HN"
        (js-obj
          "arms"          (clj->js (mapv #(name (:id %)) arms))
          "cellsN"        hn/cells-n
          ;; The subscription-count ladder, top rung LAST and equal to
          ;; `cellsN` — the top rung is `:spine-replace` itself rather than
          ;; a fourth mounted copy of it.
          "ladder"        (clj->js hn/ladder-cells)
          ;; Arms whose windows render no cell, so a DOM read-back on them
          ;; would be verifying nothing. The driver excludes these from the
          ;; "N unverified of M" denominator rather than counting a forced
          ;; `ok` as a verified write.
          "skipVerify"    (clj->js (into [] (comp (filter :skip-verify?)
                                                  (map #(name (:id %))))
                                         arms))
          ;; How many of each arm's `cellsN` cells actually subscribe. The
          ;; ladder is fitted through THIS, and the driver prints it so the
          ;; isolation claim is checkable rather than asserted.
          "subs"          (clj->js (into {} (map (fn [a] [(name (:id a)) (:subs a)])) arms))
          ;; Run before any measurement: proves this bundle reads the keys
          ;; it writes, under the renaming it was actually compiled with.
          "integrity"     (fn [] (hn/integrity-probe))
          ;; Deterministic fixtures replayed from the recorded study. A
          ;; harness whose guard is broken may measure nothing.
          "guardSelfTest" (fn [] (hn/guard-self-test!))
          "quantum"       (fn [] (hn/clock-quantum))
          "prepare"       (fn [ms'] (hn/control-prepare! ms'))
          "warm"          (fn [arm writes] (warm-window! arm writes))
          ;; The ladder pass runs at a SECOND control size and must not
          ;; land in the headline fold, so the driver clears the collected
          ;; set between passes and adjudicates the ladder from the
          ;; per-sample payload it already holds.
          "reset"         (fn [] (vreset! collected []) true)
          "seed"          (fn [] (-> (hn/seed-arms! @mounts) (.then (fn [_] true))))
          "round"         (fn [warmup samples writes pos]
                            (run-round! warmup samples writes pos))
          "negControl"    (fn [writes] (negative-control! writes))
          "report"        (fn [tol] (report! tol))))
      (-> (hn/seed-arms! ms)
          (.then (fn [_]
                   (gobj/set js/window "HN_READY" true)
                   (js/console.log ";; HN READY")
                   nil))))
    (catch :default e
      (gobj/set js/window "HN_ERROR" (str e))
      (js/console.error (str ";; HN FAILED — " e)))))
