(ns {{namespace}}.core
  "Entry point (Helix substrate). Boots the Helix adapter, seeds app-db,
   and mounts the root view."
  (:require ["react-dom/client"      :as react-dom-client]
            [helix.core             :refer [$]]
            [re-frame.core          :as rf]
            [re-frame.adapter.helix :as helix-adapter]
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.views    :as views]))

(defonce ^:private root
  (react-dom-client/createRoot (js/document.getElementById "app")))

(defn ^:export init
  "Called by shadow-cljs. Idempotent — re-invoked on each hot reload."
  []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! helix-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (.render root ($ views/counter-app)))
