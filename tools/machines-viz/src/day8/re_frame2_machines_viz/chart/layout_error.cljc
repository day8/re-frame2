(ns day8.re-frame2-machines-viz.chart.layout-error
  "Pure helpers for the ELK layout-failure surface.

  When `chart/compute-layout!` hits an ELK error — either the sync
  throw around `(.layout elk-instance input)` or an async promise
  rejection — it hands `done-fn` a non-nil result-map carrying a
  `:layout-error`, so the downstream `(when result ...)` guard still
  fires and the projector paints an in-panel diagnostic banner rather
  than silently falling back to (0, 0)-stacked boxes.

  This namespace owns the pure side of that surface — the
  input-summary, the JS-error → CLJS-data adapter, and the result-map
  builder the `done-fn` callback receives on failure. The side-effects
  (trace emit + dev-only `console.error`) stay in `chart.cljs` next to
  the elkjs interop; this ns is split out so the .cljc test corpus can
  pin the data shape without loading xyflow / elkjs (the same split
  posture that keeps the pure projector in `chart.projection`).

  ## Shapes

    - `(input-summary parsed direction layout-options)`
        → `{:node-count :edge-count :region-count :parallel?
            :direction :layout-option-ks}` — counts + the layout
        option KEY-SET (values omitted — caller-supplied + may drift).
        Suitable as an error-trace `:tags` payload.

    - `(error->data err)`
        → `{:message <str>}` (and `:name <str>` when present).
        Adapter for any thrown value (JS error, string, or nil) into
        a CLJS map fit for an error trace. We deliberately do NOT
        include `:stack` — stacks are long, environment-specific, and
        bloat the bus.

    - `(layout-error-result err parsed direction layout-options)`
        → `{:positions {} :edge-points {} :edge-labels {}
            :layout-error {:error <error-data>
                           :input-summary <summary>}}`
        The callback result-map handed to `done-fn` on failure. The
        existing `(when result ...)` guard at the chart callsite still
        gates on truthiness (the map is non-nil, so the reset! still
        runs); the projector reads `:layout-error` to paint the in-
        panel indicator banner.

  Every fn here is pure, JVM-runnable through `.cljc`, and free of
  framework state."
  (:require [clojure.string :as str]))

(defn input-summary
  "Build a SAFE, error-tag-suitable summary of the `(parsed,
  direction, layout-options)` input that fed a failing layout pass.
  Carries counts + direction + the layout-option KEY-SET (values
  omitted — caller-supplied + may drift) so a tool reading the error
  trace sees enough to diagnose without the projector's full graph on
  the wire. Pure fn."
  [parsed direction layout-options]
  {:node-count       (count (:nodes parsed))
   :edge-count       (count (:edges parsed))
   :region-count     (count (filter :region? (:nodes parsed)))
   :parallel?        (boolean (:parallel? parsed))
   :direction        direction
   :layout-option-ks (vec (sort (keys (or layout-options {}))))})

(defn error->data
  "Adapter: turn a thrown value into a CLJS map fit for an error
  trace's `:tags`.

    - nil → `{:message \"unknown error\"}`
    - string → `{:message <the-string>}`
    - JS `Error` (CLJS) — reads `.message` + `.name`
    - JVM `Throwable` — reads `.getMessage` + `.getName` of its
      class (cljc consumers exercise the JVM branch in unit tests)

  Deliberately omits `:stack` — stacks are long, environment-
  specific, and bloat the trace bus."
  [e]
  (cond
    (nil? e)    {:message "unknown error"}
    (string? e) {:message e}
    :else       #?(:cljs (let [msg (some-> ^js e .-message)
                               nm  (some-> ^js e .-name)]
                           (cond-> {:message (or msg "unknown error")}
                             (not (str/blank? nm)) (assoc :name nm)))
                   :clj  (let [msg (when (instance? Throwable e)
                                     (.getMessage ^Throwable e))
                               nm  (when e (.getName (class e)))]
                           (cond-> {:message (or msg "unknown error")}
                             (not (str/blank? nm)) (assoc :name nm))))))

(defn layout-error-result
  "Build the result-map the layout callback receives on ELK failure.
  Empty `:positions` + `:edge-points` + `:edge-labels` (the projector
  still renders, every node at default origin — same as a pre-layout
  commit) plus `:layout-error` so the chart can paint the in-panel
  indicator banner. Pure fn.

  `:edge-labels` mirrors the success-path `elk-result->positions` shape
  (elk's computed edge-label positions); empty on failure, same as
  `:edge-points`."
  [error parsed direction layout-options]
  {:positions    {}
   :edge-points  {}
   :edge-labels  {}
   :layout-error {:error         (error->data error)
                  :input-summary (input-summary parsed direction layout-options)}})
