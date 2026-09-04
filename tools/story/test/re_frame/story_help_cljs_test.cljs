(ns re-frame.story-help-cljs-test
  "CLJS smoke tests for rf2-381i — first-time-user help overlay.

  Covers:

  - `seen?` / `mark-seen!` round-trip against localStorage (browser only).
  - `reset-seen!` clears the flag.
  - `help-content` renders as hiccup.
  - `open!` / `close!` toggle the local open atom.

  The localStorage round-trip is browser-only — on node-test there's no
  `js/window`, so we guard those assertions on the runtime detection."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story.ui.help :as rf.story.ui.help]))

;; ---- runtime detection ---------------------------------------------------

(defn- browser? []
  (and (exists? js/window) (.-localStorage js/window)))

;; ---- fixtures ------------------------------------------------------------

(defn clear-flag! []
  (rf.story.ui.help/reset-seen!)
  (reset! @#'rf.story.ui.help/open? false))

(use-fixtures :each {:before clear-flag! :after clear-flag!})

;; ---- localStorage round-trip ---------------------------------------------

(deftest seen-defaults-to-false
  (testing "seen? is false when localStorage has never been touched"
    (is (false? (rf.story.ui.help/seen?)))))

(deftest mark-seen-persists
  (testing "mark-seen! flips seen? to true (browser only)"
    (when (browser?)
      (rf.story.ui.help/mark-seen!)
      (is (true? (rf.story.ui.help/seen?)))
      (rf.story.ui.help/reset-seen!)
      (is (false? (rf.story.ui.help/seen?))))))

;; ---- hiccup shape --------------------------------------------------------

(deftest help-content-is-hiccup
  (testing "help-content returns a hiccup vector rooted at :div"
    (let [out (rf.story.ui.help/help-content)]
      (is (vector? out))
      (is (= :div (first out))))))

;; ---- open / close behaviour ----------------------------------------------

(deftest open-then-close-toggles-atom
  (testing "open! flips the atom to true; close! flips it back"
    (rf.story.ui.help/open!)
    (is (true? @@#'rf.story.ui.help/open?))
    (rf.story.ui.help/close!)
    (is (false? @@#'rf.story.ui.help/open?))
    (when (browser?)
      (is (true? (rf.story.ui.help/seen?))))))
