(ns example.realworld.ssr
  "RealWorld-specific SSR helpers.

   The generic runtime SSR walkthrough lives in `examples/ssr/core.cljc`.
   This namespace is the app-specific bridge:
   - choose which slices are safe to embed in the hydration payload
   - preserve the route slice so the client starts from the server route
   - provide a small client bootstrap helper for `:rf/hydrate`

   That keeps the reusable SSR mechanics in the generic example while
   still showing how a larger app would define its own payload boundary."
  (:require [re-frame.core :as rf]
            #?(:cljs [cljs.reader :as reader])))

(def ssr-slice-keys
  [:route
   :auth
   :articles
   :article
   :comments
   :tags
   :feed
   :profile
   :profile.articles
   :profile.favorites
   :editor
   :settings
   :comment-form
   :rf/machines])

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

(defn hydration-payload-test []
  (let [db {:route {:id :route/home}
            :auth {:user {:username "alice"} :token "jwt"}
            :articles {:status :loaded :data [] :error nil :loaded-at 1 :attempt 1}
            :transient {:popup true}}
        payload (hydration-payload db [:div "hello"])]
    (assert (= #{:route :auth :articles}
               (set (keys (:rf/app-db payload)))))))
