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
            ;; Frame-local schema registration — called from `init` under the
            ;; app's frame scope (see register-schema! below).
            [{{namespace}}.schema    :as schema]
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
  ;; EP-0002 carried-frame invariant (Spec 002 §Frame target resolution):
  ;; the runtime never synthesises a frame from absence. `init!` installs the
  ;; adapter only; this app registers `:rf/default` as its app frame, then
  ;; runs its frame-local boot work (schema attach + seed dispatch) inside a
  ;; `with-frame :rf/default` scope, and wraps the render in a `frame-provider`
  ;; so every in-tree dispatch/subscribe resolves to `:rf/default`.
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
    ;; Frame-local schema attach — must run under a live frame scope (it
    ;; cannot register at ns-load; see {{namespace}}.schema/register-schema!).
    (schema/register-schema!)
    ;; dispatch-sync so the initial render sees the seeded app-db rather
    ;; than a transient empty frame.
    (rf/dispatch-sync [:counter/initialise]))
  (rdc/render root [rf/frame-provider-existing {:frame :rf/default} [views/counter-app]]))
