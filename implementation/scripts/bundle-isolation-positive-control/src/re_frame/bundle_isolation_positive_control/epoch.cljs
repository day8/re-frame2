(ns re-frame.bundle-isolation-positive-control.epoch
  (:require [re-frame.epoch :as epoch]
            [re-frame.epoch.assembly :as assembly]
            [re-frame.epoch.listeners :as listeners]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationEpoch"
        #js [epoch/restore-epoch!
             epoch/replace-frame-state!
             assembly/build-record
             listeners/notify-listeners!
             listeners/on-frame-destroyed!]))
