(ns re-frame.story-help-dom-cljs-test
  "Browser-lane half of the first-time-user help overlay's persistence:
  the `seen?` / `mark-seen!` round-trip, and the seen flag `close!`
  writes.

  ## Why these rows live here and not in `re-frame.story-help-cljs-test`

  They need `window.localStorage`. This repo ships no jsdom, no
  happy-dom and no DOM shim in any dependency list, so
  `window.localStorage` is absent under Node, and `:browser-test`
  (`:ns-regexp \".*-dom-cljs-test$\"`) loads only namespaces ending
  `-dom-cljs-test`. In a namespace ending `-cljs-test` a
  `(when (browser?) ...)` row would execute in NEITHER lane — a
  permanent silence rather than a routing decision.

  ## Each row keeps its guard, because this file runs on BOTH lanes

  `:node-test`'s `cljs-test$` is a bare SUFFIX match that
  `-dom-cljs-test` satisfies, so this file runs on the browser lane AND
  the node lane. Each row answers the node lane with a VISIBLE marker
  assertion rather than a silent `when`.

  ## What stays in the sibling

  `open-then-close-toggles-atom` makes the two `open?` ratom assertions,
  which run on node; the persistence half of that claim is
  `close!-marks-the-overlay-seen` below.

  `seen-defaults-to-false` asserts the NO-storage degradation path, so
  the node lane is exactly where it belongs."
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
            is asserted on the node lane in the sibling namespace."
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
