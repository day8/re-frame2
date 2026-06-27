(ns realworld-http.ssr
  "RealWorld-specific SSR helpers.

   The general how-SSR-works walkthrough lives in
   `examples/capabilities/ssr/ssr/core.cljc`. This file is the app-specific half of the
   story — the part every real app has to write for itself:
   - decide which slices are safe to ship in the hydration payload
   - carry the route slice across so the client wakes up on the server's route
   - offer a small client-side bootstrap for `:rf/hydrate`

   Splitting it this way keeps the reusable SSR machinery in the generic
   example, while this one shows where a larger app draws its own payload
   boundary — which is to say, what it's willing to send down the wire."
  (:require [re-frame.core :as rf]
            #?(:cljs [cljs.reader :as reader])))

(def ssr-app-slice-keys
  "The top-level app-db slices the SSR payload ships. Application data only —
  the framework's own subsystem trees (routing, machines, elision) live in the
  separate runtime-db partition and travel under :rf/runtime-db instead."
  [:auth
   :articles
   :article
   :comments
   :feed
   :profile
   :profile.articles
   :profile.favorites
   :editor
   :comment-form])

(def ssr-runtime-keys
  "The serializable **runtime-db** children the SSR payload ships, so the client
  resumes on the server's route and machine state. The current route and the
  machine snapshots are durable facts worth sending; the transient stuff (host
  handles, in-flight HTTP) is left behind — it wouldn't survive the trip
  anyway."
  [:rf.runtime/routing
   :rf.runtime/machines])

(defn exportable-app-db [app-db]
  ;; No secrets in the SSR payload — full stop. The bearer JWT sits at
  ;; [:auth :token], and baking it into server-rendered HTML would spill a live
  ;; credential into the page source: visible in view-source, logged by
  ;; proxies, possibly cached by a CDN. So we redact it right at the boundary.
  ;; And it costs nothing, because the client puts [:auth :token] back on
  ;; hydrate via `:auth/initialise` (auth.cljs), reading it from localStorage
  ;; through the `:auth.session/token` recordable coeffect. Dropping it here is
  ;; free and stays replay-sound.
  (cond-> (select-keys app-db ssr-app-slice-keys)
    (contains? app-db :auth) (update :auth dissoc :token)))

(defn exportable-runtime-db [runtime-db]
  ;; Keep only the durable, serializable runtime-db children — the route slice
  ;; and the machine snapshots — so the client picks up on the server's route
  ;; with any machines still mid-flow.
  (select-keys runtime-db ssr-runtime-keys))

(defn hydration-payload
  "Assemble the two-partition hydration payload from a frame-state value
  (`{:rf.db/app … :rf.db/runtime …}`, e.g. `(rf/frame-state-value frame-id)`).
  This is what the server embeds and the client reads back."
  [{:rf.db/keys [app runtime]} render-tree]
  {:rf/version     1
   :rf/app-db      (exportable-app-db app)
   :rf/runtime-db  (exportable-runtime-db runtime)
   :rf/render-hash (rf/render-tree-hash render-tree)})

#?(:cljs
   (defn read-server-payload []
     (when-let [el (.getElementById js/document "__rf_payload")]
       (reader/read-string (.-textContent el)))))

#?(:cljs
   ;; The caller names which frame to hydrate. Being explicit about it means a
   ;; non-default or multi-frame client can hydrate exactly the right frame,
   ;; just by passing its own frame-id.
   (defn hydrate-client! [frame-id]
     (when-let [payload (read-server-payload)]
       (rf/dispatch-sync [:rf/hydrate payload] {:frame frame-id})
       payload)))

