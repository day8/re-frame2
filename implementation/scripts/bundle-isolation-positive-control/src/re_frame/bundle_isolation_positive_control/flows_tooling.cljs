(ns re-frame.bundle-isolation-positive-control.flows-tooling
  (:require [re-frame.flows.tooling :as tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationFlowsTooling"
        tooling/flow-algebra-view))
