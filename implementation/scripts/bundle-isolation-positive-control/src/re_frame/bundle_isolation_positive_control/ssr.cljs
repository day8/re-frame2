(ns re-frame.bundle-isolation-positive-control.ssr
  (:require [re-frame.ssr.hydrate :as rf.ssr.hydrate]
            [re-frame.ssr.response :as rf.ssr.response]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationSsr"
        #js [rf.ssr.hydrate/hydrate-event-handler
             rf.ssr.hydrate/verify-hydration!
             rf.ssr.response/default-response
             rf.ssr.response/set-status-fx]))
