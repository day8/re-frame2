(ns xspike.hic
  "SPIKE (rf2-k97c.1) — HOST = Hicasso (`:rf.adapter/hicasso`).

  The ELEMENT-SHAPED arm: `re-frame.hicasso.substrate/adapter` is built
  from `re-frame.substrate.spine/make-react-adapter`, so its `:render`
  slot takes React elements and Xray's production mount refuses it
  (mount.cljs's `react-element-render-kinds`). This entry is the proof
  that a tool owning its own root escapes that refusal.

  THROWAWAY."
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.substrate :as hic-substrate]
            [xspike.app :as app]
            [xspike.boot :as boot]
            [xspike.probe :as probe]))

(h/defview frame-card [{:keys [label]}]
  [:section {:data-testid (str "app-" (name label))
             :style {:border "1px solid #675" :margin "6px" :padding "6px"
                     :font "13px system-ui"}}
   [:h3 {:style {:margin "0 0 4px 0"}} (str (name label) " frame")]
   [:div {:data-testid (str "app-" (name label) "-label")}
    (str "label=" (h/sub [:xspike/label]))]
   [:div {:data-testid (str "app-" (name label) "-counter")}
    (str "counter=" (h/sub [:xspike/counter]))]])

(defonce app-root (h/client-root))

(defn ^:export run []
  (probe/arm-render-counter!)
  (rf/init! hic-substrate/adapter)
  (app/seed!)
  (h/render! app-root
             [:div
              [h/frame-provider {:frame app/frame-above}
               [frame-card {:label app/frame-above}]]
              [h/frame-provider {:frame app/frame-below}
               [frame-card {:label app/frame-below}]]]
             (.getElementById js/document "app"))
  (boot/install-xray!)
  (boot/control-strip! (.getElementById js/document "controls"))
  (boot/export!)
  (js/setTimeout (fn [] (boot/report!)) 100)
  nil)
