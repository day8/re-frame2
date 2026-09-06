(ns re-frame.bundle-isolation-positive-control.derivation-graph
  (:require [re-frame.derivation.graph :as rf.derivation.graph]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationDerivationGraph"
        #js [rf.derivation.graph/derivation-graph rf.derivation.graph/live-derivation-graph]))
