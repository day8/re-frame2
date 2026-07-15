(ns re-frame.bundle-isolation-positive-control.trace-cascade
  (:require [re-frame.trace.cascade :as cascade]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationTraceCascade"
        #js [cascade/aggregate-cascade cascade/capture-for-epoch!]))
