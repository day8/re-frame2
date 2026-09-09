(ns xspike.app
  "SPIKE (rf2-k97c.1) — the APPLICATION half of the Xray-substrate spike.

  Two frames, `:above` and `:below`, carrying deliberately distinguishable
  values so wrong frame targeting is observable at a glance. Registered
  once, globally; the per-host mount lives in `xspike.slim` / `xspike.hic`.

  THROWAWAY. Not part of any shipped surface."
  (:require [re-frame.core :as rf]))

(def frame-above :above)
(def frame-below :below)

(def above-db {:label "ABOVE" :counter 100 :notes ["seed-above"]
               :nested {:depth-1 {:depth-2 {:leaf :above-leaf}}}})
(def below-db {:label "BELOW" :counter 900 :notes ["seed-below"]
               :nested {:depth-1 {:depth-2 {:leaf :below-leaf}}}})

(rf/reg-event :xspike/reset
  {:doc "Seed the frame's app-db with its distinguishable baseline."}
  (fn handler-reset [_ [_ which]]
    {:db (if (= which :above) above-db below-db)}))

(rf/reg-event :xspike/bump
  {:doc "Real dependency change: bump this frame's counter and append a note."}
  (fn handler-bump [{:keys [db]} _]
    {:db (-> db
             (update :counter inc)
             (update :notes conj (str "bump-" (inc (:counter db)))))}))

(rf/reg-sub :xspike/counter (fn [db _] (:counter db)))
(rf/reg-sub :xspike/label   (fn [db _] (:label db)))

(defn seed!
  "Create both frames with their seeds. Called by each host entry after
  `rf/init!`."
  []
  (rf/make-frame {:id frame-above :initial-events [[:xspike/reset :above]]})
  (rf/make-frame {:id frame-below :initial-events [[:xspike/reset :below]]}))
