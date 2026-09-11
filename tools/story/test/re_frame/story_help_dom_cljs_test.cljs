(ns re-frame.story-help-dom-cljs-test
  "Browser-lane half of the first-time-user help overlay's persistence
  (rf2-381i), promoted out of `re-frame.story-help-cljs-test` under
  rf2-r51p.

  ## What was wrong, and it was the file's LOCATION rather than its guard

  The `seen?` / `mark-seen!` round-trip sat inside `(when (browser?) ...)`
  in a namespace ending `-cljs-test`. `:node-test` selected it and the
  guard was false — this repo ships no jsdom, no happy-dom and no DOM
  shim in any dependency list, so `window.localStorage` is absent under
  Node — while `:browser-test` (`:ns-regexp \".*-dom-cljs-test$\"`) never
  loaded the file at all. Those assertions executed in NEITHER lane.

  The sibling's docstring said the guard was there because \"on node-test
  there's no `js/window`, so we guard those assertions on the runtime
  detection\". That much was true; what it did not say is that no other
  lane ever picked them up, so the guard was a permanent silence rather
  than a routing decision.

  ## THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES

  `:node-test`'s `cljs-test$` is a bare SUFFIX match that
  `-dom-cljs-test` satisfies, so moving a row here ADDS the browser lane
  and removes nothing. Each row answers the node lane with a VISIBLE
  marker assertion rather than a silent `when`.

  ## What deliberately did NOT move

  `open-then-close-toggles-atom` MIXED a live claim with a dead one: its
  two `open?` ratom assertions genuinely run on node, and only the
  trailing `seen?` assertion was guarded. Moving the row whole would have
  taken LIVE assertions OFF the node lane — this bug in reverse — so it
  was SPLIT: the ratom half stays in the sibling, and the persistence
  half is `close!-marks-the-overlay-seen` below.

  `seen-defaults-to-false` also stays: it asserts the NO-storage
  degradation path, so the node lane is exactly where it belongs."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story.ui.help :as rf.story.ui.help]))

;; ---- host predicate ------------------------------------------------------

(defn- browser?
  "True when a working `js/window.localStorage` is present. FALSE under
  `:node-test`, TRUE under `:browser-test`; both targets load this ns."
  []
  (and (exists? js/window) (.-localStorage js/window)))

(def ^:private skip-msg
  "skipped: no localStorage (node lane — see ns docstring)")

(defn- clear-flag! []
  (rf.story.ui.help/reset-seen!)
  (reset! @#'rf.story.ui.help/open? false))

(use-fixtures :each {:before clear-flag! :after clear-flag!})

;; ---- the seen? flag round-trip ------------------------------------------

(deftest mark-seen-persists
  (testing "mark-seen! flips seen? to true, and reset-seen! flips it back"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story.ui.help/mark-seen!)
        (is (true? (rf.story.ui.help/seen?)))
        (rf.story.ui.help/reset-seen!)
        (is (false? (rf.story.ui.help/seen?)))))))

(deftest close!-marks-the-overlay-seen
  (testing "closing the overlay persists the seen flag, so a returning
            user is not shown it again. The ratom half of this claim
            stays on the node lane in the sibling namespace."
    (if-not (browser?)
      (is true skip-msg)
      (do
        ;; Precondition, so the true below is evidence that `close!`
        ;; wrote rather than evidence that the flag was already set.
        (is (false? (rf.story.ui.help/seen?))
            "precondition: the fixture cleared the flag")
        (rf.story.ui.help/open!)
        (rf.story.ui.help/close!)
        (is (true? (rf.story.ui.help/seen?))
            "close! marked the overlay seen")))))
