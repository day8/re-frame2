(ns re-frame.resource-lease-owner
  "Process-wide identity for framework-minted resource lease owners.

  This namespace deliberately owns only identity. It has no React, adapter,
  router, or Resources dependency, so every view family can share one mint
  without coupling the first-party UI substrate to a legacy adapter or to the
  optional Resources artefact.")

(defonce ^:private owner-counter
  ;; One monotone source for the whole CLJS runtime. The canonical vector is
  ;; public protocol data (Spec 016); the counter and this namespace are not a
  ;; public application API.
  (atom 0))

(defn mint!
  "Mint a fresh process-unique framework resource owner `[:lease n]`."
  []
  [:lease (swap! owner-counter inc)])
