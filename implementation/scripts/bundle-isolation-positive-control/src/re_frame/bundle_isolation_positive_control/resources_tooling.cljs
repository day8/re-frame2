(ns re-frame.bundle-isolation-positive-control.resources-tooling
  (:require [re-frame.resources.tooling :as tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationResourcesTooling"
        #js [tooling/resource-algebra-view
             tooling/resource-cache-algebra-view]))
