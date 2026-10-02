(ns re-frame.story.ui.keybindings-cljs-test
  "CLJS-side regression net for the chrome-level hotkey registry.

  Surface covered:

  - `dispatch-key?`     — discrimination predicate (modifier + editable)
  - `bindings`          — canonical key → handler map shape
  - `shortcut-keys`     — sorted key list
  - Each handler fn round-trips through the shell-state-atom"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story.ui.keybindings :as rf.story.ui.keybindings]
            [re-frame.story.ui.state :as rf.story.ui.state]))

(use-fixtures :each
  {:before (fn [] (rf.story.ui.state/reset-shell-state!))})

;; ---- dispatch predicate -------------------------------------------------

(deftest dispatch-key-predicate
  (testing "single lowercase char, no modifier, not editable → true"
    (is (true? (rf.story.ui.keybindings/dispatch-key? "f" false false))))
  (testing "modifier held → false (Cmd-K / Ctrl-S etc. pass through)"
    (is (false? (rf.story.ui.keybindings/dispatch-key? "f" true false))))
  (testing "focused input → false (typing in search shouldn't toggle)"
    (is (false? (rf.story.ui.keybindings/dispatch-key? "f" false true))))
  (testing "multi-char key (Arrow, Escape) → false"
    (is (false? (rf.story.ui.keybindings/dispatch-key? "Escape" false false))))
  (testing "nil / non-string → false"
    (is (false? (rf.story.ui.keybindings/dispatch-key? nil false false)))))

;; ---- registry shape -----------------------------------------------------

(deftest bindings-table-shape
  (testing "canonical 4-key registry: f / s / a / t"
    (is (= #{"f" "s" "a" "t"} (set (keys rf.story.ui.keybindings/bindings))))
    (is (= ["a" "f" "s" "t"]  (rf.story.ui.keybindings/shortcut-keys)))))

;; ---- handler round-trip -------------------------------------------------

(defn- visibility []
  (rf.story.ui.state/chrome-visibility (rf.story.ui.state/get-state)))

(deftest each-handler-round-trips-its-chrome-slot
  (doseq [[label toggle! slot default]
          [["full-screen" rf.story.ui.keybindings/full-screen-toggle! :full-screen? false]
           ["sidebar"     rf.story.ui.keybindings/sidebar-toggle!     :sidebar?     true]
           ["rhs"         rf.story.ui.keybindings/rhs-toggle!         :rhs?         true]
           ["toolbar"     rf.story.ui.keybindings/toolbar-toggle!     :toolbar?     true]]]
    (testing (str label ": default → flipped → default")
      (rf.story.ui.state/reset-shell-state!)
      (is (= default (slot (visibility))))
      (toggle!)
      (is (= (not default) (slot (visibility))))
      (toggle!)
      (is (= default (slot (visibility)))))))

(deftest exit-full-screen-clears
  (testing "exit handler always clears full-screen regardless of prior"
    (rf.story.ui.keybindings/full-screen-toggle!)         ;; on
    (is (true? (:full-screen? (visibility))))
    (rf.story.ui.keybindings/exit-full-screen!)            ;; off
    (is (false? (:full-screen? (visibility))))
    ;; idempotent: calling again leaves it off
    (rf.story.ui.keybindings/exit-full-screen!)
    (is (false? (:full-screen? (visibility))))))
