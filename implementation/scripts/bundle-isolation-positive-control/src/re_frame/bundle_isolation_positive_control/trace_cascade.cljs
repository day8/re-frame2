(ns re-frame.bundle-isolation-positive-control.trace-cascade
  (:require [re-frame.trace.cascade :as rf.trace.cascade]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationTraceCascade"
        #js [rf.trace.cascade/aggregate-cascade rf.trace.cascade/capture-for-epoch!]))
