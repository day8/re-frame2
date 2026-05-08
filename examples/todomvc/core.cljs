(ns todomvc.core
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.substrate.reagent :as reagent-adapter]
            [todomvc.db]
            [todomvc.events]
            [todomvc.subs]
            [todomvc.views :as views]))

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defonce router-installed? (atom false))

(defn current-hash []
  (let [hash (.. js/window -location -hash)]
    (if (seq hash) hash "#/")))

(defn install-router! []
  (when-not @router-installed?
    (.addEventListener js/window "hashchange"
      (fn [_]
        (rf/dispatch [:todo/url-changed (current-hash)])))
    (reset! router-installed? true)))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:todo/initialise (current-hash)])
  (install-router!)
  (rdc/render react-root [views/root-view]))
