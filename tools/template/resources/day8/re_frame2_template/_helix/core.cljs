(ns {{namespace}}.core
  "Entry point (Helix substrate). Boots the Helix adapter, seeds app-db,
   and mounts the root view."
  (:require ["react-dom/client"      :as react-dom-client]
            [helix.core             :refer [$]]
            [re-frame.core          :as rf]
            [re-frame.adapter.helix :as helix-adapter]
            [{{namespace}}.events]
            [{{namespace}}.subs]
            ;; Frame-local schema registration — called from `init` under the
            ;; app's frame scope (see register-schema! below).
            [{{namespace}}.schema   :as schema]
            [{{namespace}}.views    :as views]))

(defonce ^:private root
  (react-dom-client/createRoot (js/document.getElementById "app")))

(defn ^:export init
  "Called by shadow-cljs. Idempotent — re-invoked on each hot reload."
  []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! helix-adapter/adapter)
  ;; EP-0002 carried-frame invariant (Spec 002 §Frame target resolution):
  ;; the runtime never synthesises a frame from absence. `init!` installs the
  ;; adapter only; this app registers `:rf/default` as its app frame, then
  ;; runs its frame-local boot work (schema attach + seed dispatch) inside a
  ;; `with-frame :rf/default` scope, and wraps the render in the Helix
  ;; `frame-provider` so the `use-subscribe` / `frame-handle` reads inside the
  ;; view tree resolve to `:rf/default`.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (schema/register-schema!)
    (rf/dispatch-sync [:counter/initialise]))
  (.render root ($ helix-adapter/frame-provider {:frame :rf/default} ($ views/counter-app))))
