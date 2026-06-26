(ns story.test-helpers.image-behaviour-v1
  "Behaviour-variant IMAGE fixture v1 (EP-0023 §Stories).

  Registers the SAME event id `:img.counter/step` as the v2 sibling but with a
  DIFFERENT meaning: v1 adds 1. A behaviour-variant test selects this namespace
  into a variant's `:images` via `(rf/image {:select-ns {:include
  [\"story.test-helpers.image-behaviour-v1\"]}})` so the variant frame resolves
  `:img.counter/step` to THIS handler — proving 'behavior variant -> image': two
  variants reuse the same global id with different behaviour purely by mounting
  under different images.

  ## Why this lives OUTSIDE `re-frame.story.*`

  This is an APP-image fixture (it registers an application-level handler), NOT
  part of Story's own runtime. The canonical Story RUNTIME IMAGE
  (`re-frame.story.runtime-image`) selects `re-frame.story.**` by provenance, so
  any app/test handler authored under that reserved root would be pulled into
  every variant frame's runtime image — and a v1/v2 pair registering the SAME
  `:img.counter/step` under two `re-frame.story.*` provenances would collide
  inside the runtime image itself (`:rf.error/image-duplicate-id`). An app image
  and the runtime image must select DISJOINT namespaces, so these app fixtures
  live under `story.test-helpers.*`, OUTSIDE the `re-frame.story.**` runtime glob.

  The id is namespaced under `:img.counter/*` (NOT a reserved `:rf.*` root) per
  the feature-modularity id-prefix convention."
  (:require [re-frame.core :as rf]))

(rf/reg-event
  :img.counter/step
  (fn [{:keys [db]} _]
    {:db (-> db
             (update :n (fnil + 0) 1)
             (assoc :behaviour :v1-add-one))}))
