(ns re-frame.story.test-helpers.image-behaviour-v2
  "Behaviour-variant IMAGE fixture v2 (EP-0023 §Stories, rf2-fpr0b5).

  Sibling of `image-behaviour-v1`: registers the SAME event id
  `:img.counter/step` but adds 100 (the DIFFERENT behaviour). Selected into a
  variant's `:images` via `(rf/image {:select-ns {:include
  [\"re-frame.story.test-helpers.image-behaviour-v2\"]}})`. See the v1 sibling's
  docstring for the 'behavior variant -> image' rationale."
  (:require [re-frame.core :as rf]))

(rf/reg-event
  :img.counter/step
  (fn [{:keys [db]} _]
    {:db (-> db
             (update :n (fnil + 0) 100)
             (assoc :behaviour :v2-add-hundred))}))
