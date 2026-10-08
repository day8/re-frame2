(ns re-frame.story.ui.toolbar-storage-dom-cljs-test
  "The toolbar's reset persistence and its hydrate precedence rule;
  `re-frame.story.ui.toolbar-persistence-dom-cljs-test` owns the
  reload-survival scenarios.

  Every row is a real `.setItem` / `.getItem` round-trip, so the
  namespace ends `-dom-cljs-test` to reach `:browser-test`. `:node-test`
  loads it too (its `cljs-test$` regexp matches the suffix), so each row
  answers the node lane with a stated skip assertion rather than running
  empty."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.toolbar :as rf.story.ui.toolbar]))

;; ---- host predicate ------------------------------------------------------

(defn- browser?
  "True under `:browser-test`, false under `:node-test`."
  []
  (and (exists? js/window) (.-localStorage js/window)))

(def ^:private skip-msg
  "skipped: no localStorage (node lane — see ns docstring)")

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.story.ui.state/reset-shell-state!)
  ;; The active-modes slot is chrome-wide, and the browser lane runs every
  ;; namespace on ONE page — so a leftover value here would be in storage
  ;; when the next namespace hydrates.
  (when (browser?)
    (try (.removeItem (.-localStorage js/window) rf.story.ui.toolbar/ls-key)
         (catch :default _ nil)))
  (rf.story/install-canonical-vocabulary!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- reset ---------------------------------------------------------------

(deftest reset-modes-persists-empty
  (testing "reset-modes! empties the shell slot AND persists the empty
            vector"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.app/x {:args {:k 1}})
        (rf.story.ui.toolbar/toggle-mode! :Mode.app/x)
        ;; Teeth: an empty read after the reset alone would pass against a
        ;; storage that never held anything.
        (is (= [:Mode.app/x] (rf.story.ui.toolbar/load-modes-from-storage))
            "precondition: toggle-mode! really did persist the mode")
        (rf.story.ui.toolbar/reset-modes!)
        (is (= [] (:active-modes (rf.story.ui.state/get-state)))
            "reset-modes! emptied the shell slot")
        (is (= [] (rf.story.ui.toolbar/load-modes-from-storage))
            "reset-modes! persisted the empty vector")))))

;; ---- hydrate precedence --------------------------------------------------

(deftest hydrate-from-storage-only-when-empty
  (testing "hydrate skips when the shell slot is already populated"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.app/x {:args {}})
        (rf.story/reg-mode :Mode.app/y {:args {}})
        (rf.story.ui.toolbar/save-modes-to-storage! [:Mode.app/x])
        ;; Teeth: an unwritten slot would leave hydrate nothing to find,
        ;; and the populated value below would survive for the wrong reason.
        (is (= [:Mode.app/x] (rf.story.ui.toolbar/load-modes-from-storage))
            "precondition: storage really holds a COMPETING value")
        (rf.story.ui.state/swap-state!
          rf.story.ui.state/set-active-modes [:Mode.app/y])
        (rf.story.ui.toolbar/hydrate-modes-from-storage!)
        (is (= [:Mode.app/y] (:active-modes (rf.story.ui.state/get-state)))
            "non-empty slot is preserved")))))
