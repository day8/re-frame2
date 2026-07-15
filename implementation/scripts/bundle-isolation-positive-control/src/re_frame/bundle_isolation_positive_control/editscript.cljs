(ns re-frame.bundle-isolation-positive-control.editscript
  (:require [editscript.core :as editscript]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationEditscript"
        editscript/diff))
