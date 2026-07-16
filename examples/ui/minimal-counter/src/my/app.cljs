(ns my.app
  "The smallest complete re-frame.ui app: dataflow, one view, one mount."
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview sub]]))

;; ---- dataflow: plain re-frame2 --------------------------------------------

(rf/reg-event :app/init  (fn [_ _] {:db {:count 0}}))
(rf/reg-event :count/inc (fn [{:keys [db]} _] {:db (update db :count inc)}))
(rf/reg-sub   :count     (fn [db _] (:count db)))

;; ---- view -----------------------------------------------------------------
;;
;; `(sub [:count])` is the value, not a ref — nothing to deref. When [:count]
;; changes, this view re-renders.

(defview counter []
  [:div
   [:span "Count: " (sub [:count])]
   [:button {:on-click [:count/inc]} "+"]])

;; ---- mount ----------------------------------------------------------------
;;
;; `frame-root` ensures frame :app exists and drains :initial-events once
;; before React renders. A hot reload finds the frame live and reuses it, so
;; your state survives edits.

(defn ^:export run []
  (rf/init! ui/adapter)
  (ui/mount [ui/frame-root {:id :app :initial-events [[:app/init]]}
             [counter]]
            (js/document.getElementById "root")))
