(ns re-frame.bundle-isolation-positive-control.zprint
  (:require [zprint.core :as zprint]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationZprint"
        zprint/zprint-file-str))
