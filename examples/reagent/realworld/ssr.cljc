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
  "Top-level **app-db** slices the SSR payload exports. These are
  application data only — the framework-owned subsystem trees (routing,
  machines, elision) live in the separate runtime-db partition, not here
  (EP-0001 two-partition frame), and ride the payload under :rf/runtime-db."
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
  ;; Secrets do not cross the SSR seam. The bearer JWT lives at
  ;; [:auth :token]; embedding it in server-rendered HTML would leak a
  ;; live credential into page source (view-source-visible, proxy-logged,
  ;; CDN-cacheable). Redact it at the payload boundary — the client
  ;; re-establishes [:auth :token] on hydrate via `:auth/initialise`
  ;; (auth.cljs), which folds the RECORDABLE+PROVIDED `:auth.session/token`
  ;; coeffect the boot boundary stamps from localStorage (EP-0017).
  ;; The durable token slot is thus a function of a recorded boot coeffect, not
  ;; an ambient write-site read, so dropping it from the payload costs nothing
  ;; and stays replay-sound.
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
   ;; EP-0002: the caller names the frame to hydrate explicitly —
   ;; no zero-arity `:rf/default` convenience. Under the carried invariant the
   ;; hydration target is a deliberate choice, not an inferred default; a
   ;; non-default / multi-frame client must pass its own frame-id.
   (defn hydrate-client! [frame-id]
     (when-let [payload (read-server-payload)]
       (rf/dispatch-sync [:rf/hydrate payload] {:frame frame-id})
       payload)))

