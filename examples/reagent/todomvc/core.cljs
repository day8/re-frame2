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

;; Named handler so the listener install is idempotent: repeated `run`
;; (shadow hot reload, or a co-required test host invoking run twice)
;; must not stack duplicate hashchange listeners, mirroring the
;; `when-not @react-root` mount guard below. We remove-then-add the same
;; Var so the registration is deduped even when the Var is redefined on
;; reload.
(defn- on-hashchange [_]
  (rf/dispatch [:rf.route/handle-url-change (current-path)]))

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:todo/initialise])
  (rf/dispatch-sync [:rf.route/handle-url-change (current-path)])
  (.removeEventListener js/window "hashchange" on-hashchange)
  (.addEventListener js/window "hashchange" on-hashchange)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root [views/root-view])))
