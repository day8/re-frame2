(ns re-frame.bundle-isolation-positive-control.flows
  (:require [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.flows.topo :as rf.flows.topo]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationFlows"
        #js [rf.flows.registry/reg-flow rf.flows.topo/topo-sort]))
