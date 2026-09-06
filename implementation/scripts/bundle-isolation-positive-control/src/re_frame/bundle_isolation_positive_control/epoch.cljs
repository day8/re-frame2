(ns re-frame.bundle-isolation-positive-control.epoch
  (:require [re-frame.epoch :as rf.epoch]
            [re-frame.epoch.assembly :as rf.epoch.assembly]
            [re-frame.epoch.listeners :as rf.epoch.listeners]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationEpoch"
        #js [rf.epoch/restore-epoch!
             rf.epoch/replace-frame-state!
             rf.epoch.assembly/build-record
             rf.epoch.listeners/notify-listeners!
             rf.epoch.listeners/on-frame-destroyed!]))
