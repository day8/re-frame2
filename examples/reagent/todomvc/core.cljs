(ns todomvc.core
  (:require [clojure.string :as str]
            [reagent2.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [todomvc.db]
            [todomvc.events]
            [todomvc.subs]
            [todomvc.views :as views]))

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

;; ---- hash → Spec 012 path adapter -----------------------------------------
;;
;; TodoMVC uses hash-based URLs (#/, #/active, #/completed); the Spec 012
;; runtime routes path-strings. This tiny host-adapter strips the '#' (and an
;; optional '!' for the legacy hashbang form) and dispatches
;; :rf.route/handle-url-change so the registered routes in events.cljs match
;; cleanly. Per Spec 012 §URL changes are events, the runtime updates
;; app-db's [:rf/runtime :routing :current] slice from there.

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

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-slim-adapter/adapter)
  (rf/dispatch-sync [:todo/initialise])
  (rf/dispatch-sync [:rf.route/handle-url-change (current-path)])
  (.addEventListener js/window "hashchange"
    (fn [_] (rf/dispatch [:rf.route/handle-url-change (current-path)])))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root [views/root-view])))
