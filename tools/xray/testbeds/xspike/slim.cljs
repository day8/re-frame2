(ns xspike.slim
  "SPIKE (rf2-k97c.1) — HOST = reagent-slim (`:rf.adapter/reagent-slim`).

  The ratom-family control arm: this is one of the two adapters Xray
  supports today, and the bead names it FIRST because it stresses
  demand-driven activation across different view machinery than stock
  Reagent.

  THROWAWAY."
  (:require [reagent2.dom.client :as rdc2]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as slim]
            [xspike.app :as app]
            [xspike.boot :as boot]
            [xspike.probe :as probe])
  (:require-macros [re-frame.core :refer [reg-view]]))

(reg-view frame-card [label]
  [:section {:data-testid (str "app-" (name label))
             :style {:border "1px solid #567" :margin "6px" :padding "6px"
                     :font "13px system-ui"}}
   [:h3 {:style {:margin "0 0 4px 0"}} (str (name label) " frame")]
   [:div {:data-testid (str "app-" (name label) "-label")}
    (str "label=" @(rf/subscribe [:xspike/label]))]
   [:div {:data-testid (str "app-" (name label) "-counter")}
    (str "counter=" @(rf/subscribe [:xspike/counter]))]])

(defn root []
  [:div
   [rf/frame-provider {:frame app/frame-above} [frame-card app/frame-above]]
   [rf/frame-provider {:frame app/frame-below} [frame-card app/frame-below]]])

(defonce app-root (atom nil))

(defn ^:export run []
  (probe/arm-render-counter!)
  (rf/init! slim/adapter)
  (app/seed!)
  (let [r (rdc2/create-root (.getElementById js/document "app"))]
    (reset! app-root r)
    (rdc2/render r [root]))
  (boot/install-xray!)
  (boot/control-strip! (.getElementById js/document "controls"))
  (boot/export!)
  (js/setTimeout (fn [] (boot/report!)) 100)
  nil)
