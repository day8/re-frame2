(ns {{namespace}}.core
  "Entry point (UIx substrate). Boots the UIx adapter, seeds app-db, and
   mounts the root view."
  (:require [uix.core             :refer [$]]
            [uix.dom              :as uix-dom]
            [re-frame.core        :as rf]
            [re-frame.adapter.uix :as uix-adapter]
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.schema :as schema]
            [{{namespace}}.views  :as views]))

(defonce ^:private root
  (uix-dom/create-root (js/document.getElementById "app")))

(defn ^:export init
  "Called by shadow-cljs. Idempotent — re-invoked on each hot reload."
  []
  (rf/init! uix-adapter/adapter)
  ;; `init!` installs the adapter but does not create a frame. Register the
  ;; app frame before frame-local boot work and provide it to the view tree.
  ;;
  ;; Schemas validate shape; they do not classify durable app-db egress.
  ;; Classify a path from the event that writes it by returning `:sensitive`
  ;; or `:large` alongside `:db`:
  ;;   (rf/reg-event :auth/init
  ;;     (fn [{:keys [db]} _]
  ;;       {:db        (assoc db :auth {})
  ;;        :sensitive [[:auth :token]]
  ;;        :large     [[:documents :upload]]}))
  ;; See the README's privacy section for HTTP carriers and response bodies.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (schema/register-schema!)
    (rf/dispatch-sync [:counter/initialise]))
  (uix-dom/render-root
    ($ uix-adapter/frame-provider {:frame :rf/default} ($ views/counter-app))
    root))
