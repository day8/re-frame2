(ns re-frame.bundle-isolation-positive-control.epoch
  (:require [re-frame.epoch :as rf.epoch]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationEpoch"
        #js [rf.epoch/epoch-history rf.epoch/restore-epoch!]))
