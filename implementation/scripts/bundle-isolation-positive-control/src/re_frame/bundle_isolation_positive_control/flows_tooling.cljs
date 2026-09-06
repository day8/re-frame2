(ns re-frame.bundle-isolation-positive-control.flows-tooling
  (:require [re-frame.flows.tooling :as rf.flows.tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationFlowsTooling"
        rf.flows.tooling/flow-algebra-view))
