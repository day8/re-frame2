(ns day8.re-frame2-causa.panels.subscriptions
  "Subscriptions panel shell."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.subscriptions-events
             :as events]
            [day8.re-frame2-causa.panels.subscriptions-subs :as subs]
            [day8.re-frame2-causa.panels.subscriptions-views
             :as views]))

(rf/reg-view subscriptions-view
  "The Subscriptions panel's root view."
  []
  [views/subscriptions-panel])

(defn install!
  "Install the Subscriptions panel's Causa-side registrations."
  []
  (subs/install!)
  (events/install!))
