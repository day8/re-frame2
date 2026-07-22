(ns {{namespace}}.core
  "Entry point (re-frame.ui substrate — EXPERIMENTAL). Boots the
   first-party compiled-view adapter, attaches the app-db schema, and
   mounts the root view.

   Keep this file small; everything interesting lives in events.cljs /
   subs.cljs / views.cljs."
  (:require [re-frame.core :as rf]
            [re-frame.ui   :as ui]
            ;; Requiring these namespaces installs their registrations.
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.schema :as schema]
            [{{namespace}}.views  :as views]))

(defn ^:export init
  "Called by shadow-cljs (see :init-fn in shadow-cljs.edn). Idempotent —
   shadow's hot-reload pipeline re-invokes it on each rebuild. `ui/mount`
   is idempotent per root, so a re-run re-renders into the same root."
  []
  (rf/init! ui/adapter)
  ;; `init!` installs the adapter but does not create a frame. The
  ;; `ui/frame-root` element below ENSURES the app frame at mount: it
  ;; creates `:rf/default` the first time (running the `:initial-events`
  ;; seed synchronously, so the initial render sees the seeded app-db),
  ;; and REUSES the live frame WITHOUT re-seeding on every hot-reload
  ;; re-render — durable app state survives edits.
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
  (ui/mount [ui/frame-root {:id             :rf/default
                            :initial-events [[:counter/initialise]]}
             [views/counter-app]]
            (js/document.getElementById "app")))
