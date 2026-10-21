(ns re-frame.integration-adapter
  "The reactive-substrate adapter the framework integration suite installs.

  The suite drives the examples' and the features' events, subscriptions,
  routing, flows, resources and managed effects rather than their views, so
  it runs on the taught default, Fresco's substrate. Every fixture in the
  suite that installs an adapter reads `adapter` from here, so pointing this
  one var at another adapter's map runs the whole suite on that adapter.
  Adapter tests stay with their adapter. Per Conventions §Adapter test matrix
  policy."
  (:require [re-frame.fresco.substrate :as rf.fresco.substrate]))

(def adapter
  "The adapter map each integration fixture passes as `:adapter`."
  rf.fresco.substrate/adapter)
