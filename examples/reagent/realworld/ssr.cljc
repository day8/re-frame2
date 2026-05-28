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

(def ssr-slice-keys
  "Top-level user-domain slices the SSR payload exports. `:rf/runtime`
  carries the framework-owned subsystem trees (routing, machines, elision)
  — its current-route slice + machine snapshots are part of the hydration
  payload per rf2-eguy4 phase-A."
  [:rf/runtime
   :auth
   :articles
   :article
   :comments
   :feed
   :profile
   :profile.articles
   :profile.favorites
   :editor
   :comment-form])

(defn exportable-db [db]
  (select-keys db ssr-slice-keys))

(defn hydration-payload [db render-tree]
  {:rf/version     1
   :rf/app-db      (exportable-db db)
   :rf/render-hash (rf/render-tree-hash render-tree)})

#?(:cljs
   (defn read-server-payload []
     (when-let [el (.getElementById js/document "__rf_payload")]
       (reader/read-string (.-textContent el)))))

#?(:cljs
   (defn hydrate-client!
     ([] (hydrate-client! :rf/default))
     ([frame-id]
      (when-let [payload (read-server-payload)]
        (rf/dispatch-sync [:rf/hydrate payload] {:frame frame-id})
        payload))))

