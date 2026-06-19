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
  ;;
  ;; The empty `{}` config is also where frame-owned EGRESS CLASSIFICATION
  ;; lives (Spec 015 §The three-layer model). app-db SCHEMAS validate shape;
  ;; they do NOT classify durable app-db egress — the frame does. Once your
  ;; app-db carries sensitive paths (an `[:auth]` token) or large blobs,
  ;; declare them here so the framework redacts/elides at every observation
  ;; boundary (Xray, Story, error sink, off-box export):
  ;;   (rf/reg-frame :rf/default
  ;;     {:sensitive {:app-db [[:auth :token]]
  ;;                  :http   {:headers ["Authorization"]}}
  ;;      :large     {:app-db [[:documents :upload]]}})
  ;; See README §Privacy / egress classification. HTTP response BODIES are
  ;; classified on the request's `:decode` schema instead — see events.cljs.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (schema/register-schema!)
    (rf/dispatch-sync [:counter/initialise]))
  (.render root ($ helix-adapter/frame-provider-existing {:frame :rf/default} ($ views/counter-app))))
