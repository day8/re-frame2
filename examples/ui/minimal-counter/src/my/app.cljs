(ns my.app
  "The smallest complete Freehand app: dataflow, one view, one mount."
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v :refer [sub]]))

;; ---- dataflow: plain re-frame2 --------------------------------------------

(rf/reg-event :app/init  (fn [_ _] {:db {:count 0}}))
(rf/reg-event :count/inc (fn [{:keys [db]} _] {:db (update db :count inc)}))
(rf/reg-sub   :count     (fn [db _] (:count db)))

;; ---- view -----------------------------------------------------------------
;;
;; `(sub [:count])` is the value, not a ref — nothing to deref. When [:count]
;; changes, this view re-renders. `{:on-click [:count/inc]}` is the intent as
;; data: no closure, and readable in the source before anything runs.
;;
;; `v/defview` takes one props map; this view uses none, so the parameter is
;; `_`. Mount it as `[counter {}]` — calling `(counter {})` is an error.

(v/defview counter [_]
  [:div
   [:span "Count: " (sub [:count])]
   [:button {:on-click [:count/inc]} "+"]])

;; ---- mount ----------------------------------------------------------------
;;
;; The mount's `:frame` ENSURES frame :app and drains :initial-events to
;; completion before React is handed anything, so the first paint already
;; carries the seeded count — no dispatch-sync, no empty-then-flash. The
;; root's identity is DERIVED from the mounted view's registered id, so a hot
;; reload finds the same root live and re-renders it in place: your state
;; survives edits.

(defn ^:export run []
  (rf/init! v/adapter)
  (v/mount [counter {}]
           (js/document.getElementById "root")
           {:frame {:id :app :initial-events [[:app/init]]}}))
