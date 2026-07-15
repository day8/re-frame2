(ns re-frame.bundle-isolation-positive-control.elkjs
  (:require ["elkjs/lib/elk.bundled.js" :as elkjs]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationElkjs"
        (or (.-default elkjs) (.-ELK elkjs) elkjs)))
