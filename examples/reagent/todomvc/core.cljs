(ns todomvc.core
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            [todomvc.db]
            [todomvc.events]
            [todomvc.subs]
            [todomvc.views :as views]))

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

;; ---- hash → Spec 012 path adapter -----------------------------------------
;;
;; TodoMVC uses hash-based URLs (#/, #/active, #/completed); the Spec 012
;; runtime routes path-strings. This tiny host-adapter strips the '#' (and an
;; optional '!' for the legacy hashbang form) and dispatches
;; :rf.route/handle-url-change so the registered routes in events.cljs match
;; cleanly. Per Spec 012 §URL changes are events, the runtime updates
;; app-db's :rf/route slice from there.

(defn- hash->path
  "Convert window.location.hash (e.g. \"#/active\", \"#!/completed\") to a
  Spec 012 path. An empty hash maps to \"/\"."
  [hash]
  (let [stripped (-> hash
                     (str/replace #"^#!?" "")
                     (str/replace #"^/+" "/"))]
    (if (str/blank? stripped) "/" stripped)))

(defn- current-path []
  (hash->path (.. js/window -location -hash)))

(defn ^:export run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:todo/initialise])
  (rf/dispatch-sync [:rf.route/handle-url-change (current-path)])
  (.addEventListener js/window "hashchange"
    (fn [_] (rf/dispatch [:rf.route/handle-url-change (current-path)])))
  (rdc/render react-root [views/root-view]))
