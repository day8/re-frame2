(ns re-frame.bundle-isolation-positive-control.trace-tooling
  (:require [re-frame.trace.tooling :as rf.trace.tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationTraceTooling"
        #js [rf.trace.tooling/trace-buffer rf.trace.tooling/register-listener!]))
