(ns story.test-helpers.runtime-shadow
  "Runtime-image-last shadow fixture (rf2-14gqim, EP-0026 §Layered Resolution).

  This app-image fixture deliberately registers a handler under the SAME
  `[kind id]` as a Story RUNTIME registration — the `:rf.assert/path-equals`
  assertion handler (registered by `re-frame.story.assertions`, selected by the
  canonical runtime image's `re-frame.story.**` provenance glob). It does so
  from an APP provenance (`story.test-helpers.runtime-shadow`), so the app image
  `:select-ns {:include [\"story.test-helpers.runtime-shadow\"]}` selects THIS
  (broken) version while the runtime image selects the REAL one.

  It models the rare app-author-error overlap the runtime image exists to
  survive: `allocate!` composes `[<app-image> runtime-image]` with the runtime
  image LAST, so the runtime's `:rf.assert/path-equals` shadows the app's and
  the Story assertion machinery is never shadowed out. If the composition put
  the runtime image FIRST, THIS broken handler would win — the shadow test's
  teeth.

  The broken `:rf.assert/path-equals` here does NOT append a proper assertion
  record; it writes a `[:shadow/app-assert-ran true]` sentinel instead. So when
  the runtime image correctly wins, the sentinel is ABSENT and the real
  assertion passes; when (with a broken composition) the app wins, the sentinel
  appears and the checkpoint cannot pass.

  `:shadow.app/seed-count` is co-located under the same provenance so the
  scoped app image also selects the event the variant's `:setup` dispatches
  (a scoped image sees ONLY its own selection — the seed event must be in it).

  Lives OUTSIDE the reserved `re-frame.story.*` root (like the
  `image-behaviour-v{1,2}` siblings) precisely so the runtime glob does NOT pull
  it in — the overlap must be CROSS-image (app image vs. runtime image), not a
  within-runtime-image collision."
  (:require [re-frame.core :as rf]))

(rf/reg-event
  :shadow.app/seed-count
  (fn [{:keys [db]} _]
    {:db (assoc db :count 1)}))

(rf/reg-event
  :rf.assert/path-equals
  (fn [{:keys [db]} _]
    ;; Deliberately WRONG: a real `:rf.assert/*` handler appends an assertion
    ;; record; this app shadow just drops a sentinel. Observable only if this
    ;; version (mistakenly) wins the composition.
    {:db (assoc db :shadow/app-assert-ran true)}))
