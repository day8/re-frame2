(ns re-frame.story.test-helpers.image-behaviour-v1
  "Behaviour-variant IMAGE fixture v1 (EP-0023 §Stories, rf2-fpr0b5).

  Registers the SAME event id `:img.counter/step` as the v2 sibling but with a
  DIFFERENT meaning: v1 adds 1. A behaviour-variant test selects this namespace
  into a variant's `:images` via `(rf/image {:select-ns {:include
  [\"re-frame.story.test-helpers.image-behaviour-v1\"]}})` so the variant frame
  resolves `:img.counter/step` to THIS handler — proving 'behavior variant ->
  image': two variants reuse the same global id with different behaviour purely
  by mounting under different images.

  The id is namespaced under `:img.counter/*` (NOT a reserved `:rf.*` root) per
  the feature-modularity id-prefix convention."
  (:require [re-frame.core :as rf]))

(rf/reg-event
  :img.counter/step
  (fn [{:keys [db]} _]
    {:db (-> db
             (update :n (fnil + 0) 1)
             (assoc :behaviour :v1-add-one))}))
