(ns re-frame.bundle-isolation-positive-control.trace-tooling
  (:require [re-frame.trace.tooling :as tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationTraceTooling"
        #js [tooling/trace-buffer tooling/register-listener!]))
