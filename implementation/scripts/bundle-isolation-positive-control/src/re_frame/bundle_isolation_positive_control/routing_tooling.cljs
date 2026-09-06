(ns re-frame.bundle-isolation-positive-control.routing-tooling
  (:require [re-frame.routing.tooling :as rf.routing.tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationRoutingTooling"
        #js [rf.routing.tooling/route-algebra-view rf.routing.tooling/route-slice-algebra-view]))
