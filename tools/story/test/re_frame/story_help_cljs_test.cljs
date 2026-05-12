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
            [re-frame.story.ui.help :as help]))

;; ---- runtime detection ---------------------------------------------------

(defn- browser? []
  (and (exists? js/window) (.-localStorage js/window)))

;; ---- fixtures ------------------------------------------------------------

(defn clear-flag! []
  (help/reset-seen!)
  (reset! @#'help/open? false))

(use-fixtures :each {:before clear-flag! :after clear-flag!})

;; ---- localStorage round-trip ---------------------------------------------

(deftest seen-defaults-to-false
  (testing "seen? is false when localStorage has never been touched"
    (is (false? (help/seen?)))))

(deftest mark-seen-persists
  (testing "mark-seen! flips seen? to true (browser only)"
    (when (browser?)
      (help/mark-seen!)
      (is (true? (help/seen?)))
      (help/reset-seen!)
      (is (false? (help/seen?))))))

;; ---- hiccup shape --------------------------------------------------------

(deftest help-content-is-hiccup
  (testing "help-content returns a hiccup vector rooted at :div"
    (let [out (help/help-content)]
      (is (vector? out))
      (is (= :div (first out))))))

;; ---- open / close behaviour ----------------------------------------------

(deftest open-then-close-toggles-atom
  (testing "open! flips the atom to true; close! flips it back"
    (help/open!)
    (is (true? @@#'help/open?))
    (help/close!)
    (is (false? @@#'help/open?))
    (when (browser?)
      (is (true? (help/seen?))))))
