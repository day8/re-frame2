(ns re-frame.story-help-dom-cljs-test
  "The help overlay's persistence: the `seen?` / `mark-seen!` round trip and
  the seen flag `close!` writes. They need `window.localStorage`, which only
  the browser lane has; `:browser-test` loads namespaces ending
  `-dom-cljs-test`, which `:node-test`'s `cljs-test$` suffix match also
  catches. So each row answers the node lane with a visible marker assertion
  rather than a silent `when`. The `open?` ratom half and the no-storage
  `seen-defaults-to-false` run on node in `re-frame.story-help-cljs-test`."
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
  (testing "closing the overlay persists the seen flag, so a returning user is
            not shown it again"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (is (false? (rf.story.ui.help/seen?))
            "precondition: the fixture cleared the flag, so the true below is close!'s write")
        (rf.story.ui.help/open!)
        (rf.story.ui.help/close!)
        (is (true? (rf.story.ui.help/seen?)))))))
