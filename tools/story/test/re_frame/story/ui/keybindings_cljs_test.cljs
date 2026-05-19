(ns re-frame.story.ui.keybindings-cljs-test
  "CLJS-side regression net for the chrome-level hotkey registry
  (rf2-g8l8x / rf2-p3i0t).

  Surface covered:

  - `dispatch-key?`     — discrimination predicate (modifier + editable)
  - `bindings`          — canonical key → handler map shape
  - `shortcut-keys`     — sorted key list
  - Each handler fn round-trips through the shell-state-atom"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story.ui.keybindings :as kb]
            [re-frame.story.ui.state :as state]))

(use-fixtures :each
  {:before (fn [] (state/reset-shell-state!))})

;; ---- dispatch predicate -------------------------------------------------

(deftest dispatch-key-predicate
  (testing "single lowercase char, no modifier, not editable → true"
    (is (true? (kb/dispatch-key? "f" false false))))
  (testing "modifier held → false (Cmd-K / Ctrl-S etc. pass through)"
    (is (false? (kb/dispatch-key? "f" true false))))
  (testing "focused input → false (typing in search shouldn't toggle)"
    (is (false? (kb/dispatch-key? "f" false true))))
  (testing "multi-char key (Arrow, Escape) → false"
    (is (false? (kb/dispatch-key? "Escape" false false))))
  (testing "nil / non-string → false"
    (is (false? (kb/dispatch-key? nil false false)))))

;; ---- registry shape -----------------------------------------------------

(deftest bindings-table-shape
  (testing "canonical 4-key registry: f / s / a / t"
    (is (= #{"f" "s" "a" "t"} (set (keys kb/bindings))))
    (is (= ["a" "f" "s" "t"]  (kb/shortcut-keys)))))

;; ---- handler round-trip -------------------------------------------------

(defn- visibility []
  (state/chrome-visibility (state/get-state)))

(deftest full-screen-toggle-round-trip
  (testing "default off → on → off"
    (is (false? (:full-screen? (visibility))))
    (kb/full-screen-toggle!)
    (is (true?  (:full-screen? (visibility))))
    (kb/full-screen-toggle!)
    (is (false? (:full-screen? (visibility))))))

(deftest sidebar-toggle-round-trip
  (testing "default on → off → on"
    (is (true?  (:sidebar? (visibility))))
    (kb/sidebar-toggle!)
    (is (false? (:sidebar? (visibility))))
    (kb/sidebar-toggle!)
    (is (true?  (:sidebar? (visibility))))))

(deftest rhs-toggle-round-trip
  (testing "default on → off → on"
    (is (true?  (:rhs? (visibility))))
    (kb/rhs-toggle!)
    (is (false? (:rhs? (visibility))))
    (kb/rhs-toggle!)
    (is (true?  (:rhs? (visibility))))))

(deftest toolbar-toggle-round-trip
  (testing "default on → off → on"
    (is (true?  (:toolbar? (visibility))))
    (kb/toolbar-toggle!)
    (is (false? (:toolbar? (visibility))))
    (kb/toolbar-toggle!)
    (is (true?  (:toolbar? (visibility))))))

(deftest exit-full-screen-clears
  (testing "exit handler always clears full-screen regardless of prior"
    (kb/full-screen-toggle!)         ;; on
    (is (true? (:full-screen? (visibility))))
    (kb/exit-full-screen!)            ;; off
    (is (false? (:full-screen? (visibility))))
    ;; idempotent: calling again leaves it off
    (kb/exit-full-screen!)
    (is (false? (:full-screen? (visibility))))))
