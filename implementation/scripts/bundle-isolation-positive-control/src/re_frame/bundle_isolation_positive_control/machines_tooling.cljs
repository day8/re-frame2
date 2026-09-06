(ns re-frame.bundle-isolation-positive-control.machines-tooling
  (:require [re-frame.machines.tooling :as rf.machines.tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationMachinesTooling"
        #js [rf.machines.tooling/machine-algebra-view
             rf.machines.tooling/machine-instance-algebra-view]))
