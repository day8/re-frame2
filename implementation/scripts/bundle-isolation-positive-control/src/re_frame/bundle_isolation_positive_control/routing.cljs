(ns re-frame.bundle-isolation-positive-control.routing
  (:require [re-frame.routing.registry :as registry]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationRouting"
        #js [registry/reg-route registry/route-url]))
