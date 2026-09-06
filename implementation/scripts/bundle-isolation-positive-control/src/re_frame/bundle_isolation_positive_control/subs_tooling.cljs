(ns re-frame.bundle-isolation-positive-control.subs-tooling
  (:require [re-frame.subs.tooling :as rf.subs.tooling]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationSubsTooling"
        #js [rf.subs.tooling/sub-topology rf.subs.tooling/sub-algebra-view]))
