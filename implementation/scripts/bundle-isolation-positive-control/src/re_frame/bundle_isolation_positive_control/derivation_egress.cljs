(ns re-frame.bundle-isolation-positive-control.derivation-egress
  (:require [re-frame.derivation.egress :as egress]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationDerivationEgress"
        egress/project-graph))
