(ns re-frame.bundle-isolation-positive-control.routing
  (:require [re-frame.routing.registry :as rf.routing.registry]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationRouting"
        #js [rf.routing.registry/reg-route rf.routing.registry/route-url]))
