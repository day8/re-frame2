(ns re-frame.bundle-isolation-positive-control.derivation-egress
  (:require [re-frame.derivation.egress :as rf.derivation.egress]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationDerivationEgress"
        rf.derivation.egress/project-graph))
