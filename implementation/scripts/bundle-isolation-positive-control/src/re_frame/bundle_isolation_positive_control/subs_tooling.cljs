(ns re-frame.bundle-isolation-positive-control.subs-tooling
  (:require [re-frame.subs.tooling :as tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationSubsTooling"
        #js [tooling/sub-topology tooling/sub-algebra-view]))
