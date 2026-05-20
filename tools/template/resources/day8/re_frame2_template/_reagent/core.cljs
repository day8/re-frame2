(ns {{namespace}}.core
  "Entry point. Boots the Reagent adapter, seeds app-db, and mounts the
   root view.

   This file follows the same shape as the canonical counter example in
   the re-frame2 reference implementation
   (examples/reagent/counter/core.cljs) — keep it small; everything
   interesting lives in events.cljs / subs.cljs / views.cljs."
  (:require [reagent.dom.client       :as rdc]
            [re-frame.core            :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Side-effecting requires — register the events and subs.
            [{{namespace}}.events]
            [{{namespace}}.subs]
            [{{namespace}}.views     :as views]))

(defonce ^:private root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export init
  "Called by shadow-cljs (see :init-fn in shadow-cljs.edn). Idempotent —
   shadow's hot-reload pipeline re-invokes it on each rebuild."
  []
  ;; `rf/init!` takes the adapter spec map directly — pass the adapter
  ;; var explicitly (there is no default-adapter registry).
  (rf/init! reagent-adapter/adapter)
  ;; dispatch-sync so the initial render sees the seeded app-db rather
  ;; than a transient empty frame.
  (rf/dispatch-sync [:counter/initialise])
  (rdc/render root [views/counter-app]))
