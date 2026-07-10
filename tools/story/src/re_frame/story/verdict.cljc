(ns re-frame.story.verdict
  "The ONE cycle-free owner of the Story run-result verdicts (spec/017
  §Run result). A pure leaf: it depends on `clojure.core` ONLY, so any
  sibling — the runtime-coupled `re-frame.story.result`, the pure
  shell-state helpers in `re-frame.story.ui.state.tests`, and the
  test-mode pure helpers in `re-frame.story.ui.test-mode.pure` — can
  `:require` it without a cycle.

  ## Why a leaf

  `re-frame.story.result` is the canonical assembly, but it pulls in
  `re-frame.story.assertions` (and through it `re-frame.core`, the
  runtime). A pure UI-state / test-mode helper cannot require `result`
  without dragging the runtime onto its require path, so the four-verdict
  set and the record-normalization rule used to be COPIED by hand into
  those leaves — a copy that had already drifted once and mis-classified
  a thrown-handler record as a false-green `:pass` (rf2-fslmh /
  rf2-o6toyw).

  This ns lifts those two things — `statuses` and `record-status` — to a
  single owner every side can consume directly. `result` re-exports the
  vars to preserve its public contract; the UI-state + test-mode helpers
  consume the leaf. One implementation, no mirror, no parity test.")

;; ===========================================================================
;; STATUS — the four verdicts (spec/017 §Run result)
;; ===========================================================================

(def statuses
  "The four run / assertion / check verdicts (spec/017 §Run result):

  - `:pass`       — every expectation the runner could attempt held;
  - `:fail`       — at least one expectation failed (or the tape shows
                    unconsumed failure evidence);
  - `:cannot-run` — the ONLY unmet expectations were ones the runner could
                    not even attempt (the distinct THIRD status, §`:cannot-run`);
  - `:error`      — a handler / fx / step threw."
  #{:pass :fail :cannot-run :error})

;; ===========================================================================
;; RECORD NORMALIZATION — derive ONE assertion record's verdict
;; ===========================================================================

(defn record-status
  "Derive the `:status` for ONE assertion record from its outcome fields.
  Pure data → data. An explicit `:status` already on the record wins (the
  record was minted by a status-aware path); otherwise:

  - `:cannot-run?` true   → `:cannot-run` (the runner could not prove it);
  - `:skipped?` true      → `:cannot-run` (§`:cannot-run` generalizes the
                            shipping `:skipped?`);
  - `:exception` / `:error` truthy → `:error` (a thrown handler / fx / step
                            — a derived record carrying `:exception`/`:error`
                            but no explicit `:status` counts as `:error`,
                            never a false-green `:pass`);
  - `:passed?` true       → `:pass`;
  - `:passed?` false      → `:fail`;
  - `:passed?` nil        → `:pass` (a non-assertion / vacuous record does
                            not fail the run — the /spec/007-Stories.md §Story-as-test duality)."
  [{:keys [status passed? cannot-run? skipped? exception error] :as _record}]
  (cond
    (contains? statuses status) status
    cannot-run?                 :cannot-run
    skipped?                    :cannot-run   ; §`:cannot-run` generalizes the shipping :skipped?
    (or exception error)        :error
    (true? passed?)             :pass
    (false? passed?)            :fail
    :else                       :pass))
