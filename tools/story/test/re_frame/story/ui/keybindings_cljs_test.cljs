(ns re-frame.story.ui.keybindings-cljs-test
  "The chrome hotkey registry: the `dispatch-key?` discrimination
  predicate, the bound-keys table, and each handler round-tripping its
  slot through shell state."
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.story.ui.keybindings :as rf.story.ui.keybindings]
            [re-frame.story.ui.state :as rf.story.ui.state]))

(use-fixtures :each
  {:before (fn [] (rf.story.ui.state/reset-shell-state!))})

;; ---- dispatch predicate -------------------------------------------------

(deftest dispatch-key-predicate
  (are [k modifier? editable? expected]
       (= expected (rf.story.ui.keybindings/dispatch-key? k modifier? editable?))
    "f"      false false true
    ;; modifier held — Cmd-K / Ctrl-S pass through
    "f"      true  false false
    ;; focus in an editable — typing in search must not toggle
    "f"      false true  false
    ;; multi-char keys (Arrow, Escape)
    "Escape" false false false
    nil      false false false))

;; ---- registry shape -----------------------------------------------------

(deftest bindings-table-shape
  (testing "canonical 4-key registry, sorted for the help overlay"
    (is (= ["a" "f" "s" "t"] (rf.story.ui.keybindings/shortcut-keys)))))

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
      (toggle!)
      (is (= (not default) (slot (visibility))))
      (toggle!)
      (is (= default (slot (visibility)))))))

(deftest exit-full-screen-clears
  (rf.story.ui.keybindings/full-screen-toggle!)
  (rf.story.ui.keybindings/exit-full-screen!)
  (is (false? (:full-screen? (visibility)))))
