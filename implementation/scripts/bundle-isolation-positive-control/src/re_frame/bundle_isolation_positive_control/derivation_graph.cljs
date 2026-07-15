(ns re-frame.bundle-isolation-positive-control.derivation-graph
  (:require [re-frame.derivation.graph :as graph]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationDerivationGraph"
        #js [graph/derivation-graph graph/live-derivation-graph]))
