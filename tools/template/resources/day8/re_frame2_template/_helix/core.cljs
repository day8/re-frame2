(ns {{namespace}}.core
  "Entry point (Helix substrate). Boots the Helix adapter, seeds app-db,
   and mounts the root view."
  (:require ["react-dom/client"      :as react-dom-client]
            [helix.core             :refer [$]]
            [re-frame.core          :as rf]
            [re-frame.adapter.helix :as helix-adapter]
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.schema   :as schema]
            [{{namespace}}.views    :as views]))

(defonce ^:private root
  (react-dom-client/createRoot (js/document.getElementById "app")))

(defn ^:export init
  "Called by shadow-cljs. Idempotent — re-invoked on each hot reload."
  []
  (rf/init! helix-adapter/adapter)
  ;; `init!` installs the adapter but does not create a frame. The
  ;; `frame-root` element below ENSURES the app frame at mount: it creates
  ;; `:rf/default` the first time (running the `:initial-events` seed
  ;; synchronously, so the initial render sees the seeded app-db), and REUSES
  ;; the live frame WITHOUT re-seeding on every hot-reload re-render.
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
  ;;
  ;; Schema attachment is frame-local; it names the app frame explicitly so
  ;; the `:initial-events` seed below is validated from the very first write.
  (schema/register-schema!)
  (.render root ($ helix-adapter/frame-root {:id             :rf/default
                                             :initial-events [[:counter/initialise]]}
                   ($ views/counter-app))))
