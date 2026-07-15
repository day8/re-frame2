(ns re-frame.bundle-isolation-positive-control.machines-tooling
  (:require [re-frame.machines.tooling :as tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationMachinesTooling"
        #js [tooling/machine-algebra-view
             tooling/machine-instance-algebra-view]))
