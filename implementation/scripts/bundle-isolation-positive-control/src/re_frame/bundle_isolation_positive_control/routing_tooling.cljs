(ns re-frame.bundle-isolation-positive-control.routing-tooling
  (:require [re-frame.routing.tooling :as tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationRoutingTooling"
        #js [tooling/route-algebra-view tooling/route-slice-algebra-view]))
