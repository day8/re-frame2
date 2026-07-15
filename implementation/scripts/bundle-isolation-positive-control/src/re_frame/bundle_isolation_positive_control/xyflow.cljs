(ns re-frame.bundle-isolation-positive-control.xyflow
  (:require ["@xyflow/react" :as xyflow]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationXyflow"
        #js [xyflow/ReactFlow xyflow/Handle]))
