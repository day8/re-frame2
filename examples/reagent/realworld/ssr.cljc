(ns realworld.ssr
  "RealWorld-specific SSR helpers.

   The generic runtime SSR walkthrough lives in `examples/reagent/ssr/core.cljc`.
   This namespace is the app-specific bridge:
   - choose which slices are safe to embed in the hydration payload
   - preserve the route slice so the client starts from the server route
   - provide a small client bootstrap helper for `:rf/hydrate`

   That keeps the reusable SSR mechanics in the generic example while
   still showing how a larger app would define its own payload boundary."
  (:require [re-frame.core :as rf]
            #?(:cljs [cljs.reader :as reader])))

(def ssr-app-slice-keys
  "Top-level app-db slices the SSR payload exports. These are application
  data only — the framework's subsystem trees (routing, machines, elision)
  live in the separate runtime-db partition and ride the payload under
  :rf/runtime-db."
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
  "The serializable **runtime-db** children the SSR payload exports so the
  client starts from the server's route + machine state. The current-route
  slice + machine snapshots are durable runtime-db facts; transient runtime
  state (host handles, in-flight HTTP) is excluded."
  [:rf.runtime/routing
   :rf.runtime/machines])

(defn exportable-app-db [app-db]
  ;; Keep secrets out of the SSR payload. The bearer JWT lives at
  ;; [:auth :token]; embedding it in server-rendered HTML would leak a live
  ;; credential into page source (view-source-visible, proxy-logged,
  ;; CDN-cacheable). Redact it at the payload boundary. The client
  ;; re-establishes [:auth :token] on hydrate via `:auth/initialise`
  ;; (auth.cljs), which reads it back from localStorage through the
  ;; `:auth.session/token` recordable coeffect. So dropping the token from
  ;; the payload costs nothing and stays replay-sound.
  (cond-> (select-keys app-db ssr-app-slice-keys)
    (contains? app-db :auth) (update :auth dissoc :token)))

(defn exportable-runtime-db [runtime-db]
  ;; Project only the durable, serializable runtime-db children — route slice
  ;; and machine snapshots — so the client resumes from the server's route
  ;; and any machines mid-flow.
  (select-keys runtime-db ssr-runtime-keys))

(defn hydration-payload
  "Build the two-partition hydration payload from a frame-state value
  (`{:rf.db/app … :rf.db/runtime …}`, e.g. `(rf/frame-state-value frame-id)`)."
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
   ;; The caller names the frame to hydrate. Making the target explicit lets
   ;; a non-default or multi-frame client hydrate the right frame by passing
   ;; its own frame-id.
   (defn hydrate-client! [frame-id]
     (when-let [payload (read-server-payload)]
       (rf/dispatch-sync [:rf/hydrate payload] {:frame frame-id})
       payload)))

