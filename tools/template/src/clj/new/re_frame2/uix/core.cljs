(ns {{namespace}}.core
  "Entry point (UIx substrate). Boots the UIx adapter, seeds app-db, and
   mounts the root view."
  (:require [uix.core             :refer [$]]
            [uix.dom              :as uix-dom]
            [re-frame.core        :as rf]
            [re-frame.adapter.uix :as uix-adapter]
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.views  :as views]))

(defonce ^:private root
  (uix-dom/create-root (js/document.getElementById "app")))

(defn ^:export init
  "Called by shadow-cljs. Idempotent — re-invoked on each hot reload."
  []
  ;; rf2-agql: pass the adapter spec map directly — no registry.
  (rf/init! uix-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (uix-dom/render-root ($ views/counter-app) root))
