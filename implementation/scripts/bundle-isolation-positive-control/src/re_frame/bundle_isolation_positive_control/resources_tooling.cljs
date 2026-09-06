(ns re-frame.bundle-isolation-positive-control.resources-tooling
  (:require [re-frame.resources.tooling :as rf.resources.tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationResourcesTooling"
        #js [rf.resources.tooling/resource-algebra-view
             rf.resources.tooling/resource-cache-algebra-view]))
