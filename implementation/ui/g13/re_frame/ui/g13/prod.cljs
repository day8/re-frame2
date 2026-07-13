(ns re-frame.ui.g13.prod
  "Advanced-build companion: ordinary public mount of the shared view shape.
  No ui.test or gate instrumentation is reachable from this entry point."
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui]
            [re-frame.ui.g13.fixture :as fixture]))

(defn -main []
  (fixture/register!)
  (rf/init! ui/adapter)
  (let [frame (rf/make-frame {:initial-events [[:rf/set-db (fixture/seed 100)]]})
        host  (.getElementById js/document "app")]
    (ui/mount [ui/frame-provider {:frame frame}
               [fixture/app {:v 100}]]
              host
              {:root-id :g13/advanced-companion})))
