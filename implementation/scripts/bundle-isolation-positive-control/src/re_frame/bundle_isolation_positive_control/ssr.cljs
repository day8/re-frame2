(ns re-frame.bundle-isolation-positive-control.ssr
  (:require [re-frame.ssr.hydrate :as hydrate]
            [re-frame.ssr.response :as response]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationSsr"
        #js [hydrate/hydrate-event-handler
             hydrate/verify-hydration!
             response/default-response
             response/set-status-fx]))
