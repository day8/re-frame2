(ns day8.re-frame2-xray.panels.machines.topology-view
  "The static context band every machine topology chart shares: the
  Machine inspector, the Static Machines topology and the simulator read
  a definition's context shape (EP-0005) through `static-context-shape`
  and its provenance through `static-context-inferred?`, so all three
  badge the shape the same way."
  (:require [day8.re-frame2-machines-viz.context-shape :as context-shape]))

(defn static-context-shape
  "Derive the STATIC context shape (EP-0005) from a
  machine definition for the chart's root Context panel. Returns a map of
  `{key type-caption}` so the root chrome shows the operator the context
  KEYS + their TYPE shape (e.g. `{:opened-count \"number\" :trail
  \"vector\"}`) even with no live snapshot in hand. This is deliberately
  the SHAPE, not the live values — the live runtime `:data` overlay
  is a separate diagnostic. Returns nil when the
  definition declares neither a `[:schemas :data]` schema nor a map `:data` (so the
  panel stays hidden).

  Declared over inferred: when the machine declares a
  `[:schemas :data]` schema, the shape is read AUTHORITATIVELY off the schema; otherwise
  it is INFERRED from one sample of the initial `:data`. The provenance is
  reported by `static-context-inferred?` (drives the chart's
  `:context-band-inferred?` badge gate). Delegates to the machines-viz
  `context-shape` helper so the derivation lives with the chart it feeds.

  Pure fn — testable in isolation."
  [definition]
  (:shape (context-shape/static-context-shape definition)))

(defn static-context-inferred?
  "Per EP-0005, true when `static-context-shape` for this definition
  is INFERRED from one sample of `:data` (the one-sample caveat applies —
  the chart shows the `inferred from :data` badge); false when it is AUTHORITATIVE
  from a declared `[:schemas :data]` schema (the chart drops the inferred badge and shows
  a `declared` badge). Feeds the chart's `:context-band-inferred?` prop.

  Defaults to true when there is no shape at all, so a host that threads it
  unconditionally gets the inferred-by-default posture. Pure."
  [definition]
  (let [result (context-shape/static-context-shape definition)]
    (if result (:inferred? result) true)))
