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
  ;; `frame-provider` so the `use-subscribe` / `capture-frame` reads inside the
  ;; view tree resolve to `:rf/default`.
  ;;
  ;; EGRESS CLASSIFICATION (Spec 015 §The three-layer model): app-db SCHEMAS
  ;; validate shape; they do NOT classify durable app-db egress. Once your
  ;; app-db carries sensitive paths (an `[:auth]` token) or large blobs,
  ;; classify them from the event that writes them — return the `:sensitive` /
  ;; `:large` commit-plane effects alongside `:db` (EP-0025) so the framework
  ;; redacts/elides at every observation boundary (Xray, Story, error sink,
  ;; off-box export):
  ;;   (rf/reg-event :auth/init
  ;;     (fn [{:keys [db]} _]
  ;;       {:db        (assoc db :auth {})
  ;;        :sensitive [[:auth :token]]
  ;;        :large     [[:documents :upload]]}))
  ;; HTTP carrier names ride the `:rf.http/managed` reg-fx registration's
  ;; `:carriers` block (`(rf/reg-fx :rf.http/managed {:carriers {:headers ["X-My-Auth"]}} h)`).
  ;; See README §Privacy / egress classification. HTTP response BODIES are
  ;; classified on the request's `:decode` schema instead — see events.cljs.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (schema/register-schema!)
    (rf/dispatch-sync [:counter/initialise]))
  (.render root ($ helix-adapter/frame-provider {:frame :rf/default} ($ views/counter-app))))
