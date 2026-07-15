(ns re-frame.bundle-isolation-positive-control.flows
  (:require [re-frame.flows.registry :as registry]
            [re-frame.flows.topo :as topo]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationFlows"
        #js [registry/reg-flow topo/topo-sort]))
