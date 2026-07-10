(ns {{namespace}}.core
  "Entry point. Boots the Reagent adapter, seeds app-db, and mounts the
   root view.

   This file follows the same shape as the canonical counter example in
   the re-frame2 reference implementation
   (examples/core/counter/core.cljs) — keep it small; everything
   interesting lives in events.cljs / subs.cljs / views.cljs."
  (:require [reagent.dom.client       :as rdc]
            [re-frame.core            :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Requiring these namespaces installs their registrations.
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.schema    :as schema]
            [{{namespace}}.views     :as views]))

(defonce ^:private root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export init
  "Called by shadow-cljs (see :init-fn in shadow-cljs.edn). Idempotent —
   shadow's hot-reload pipeline re-invokes it on each rebuild."
  []
  (rf/init! reagent-adapter/adapter)
  ;; `init!` installs the adapter but does not create a frame. Register the
  ;; app frame before running frame-local boot work, and provide that same
  ;; frame to the rendered tree below.
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
    ;; Schema attachment is frame-local and must run after reg-frame.
    (schema/register-schema!)
    ;; dispatch-sync so the initial render sees the seeded app-db rather
    ;; than a transient empty frame.
    (rf/dispatch-sync [:counter/initialise]))
  (rdc/render root [rf/frame-provider {:frame :rf/default} [views/counter-app]]))
