(ns re-frame.story.ui.toolbar-storage-dom-cljs-test
  "Browser-lane half of the toolbar's mode-persistence round-trip and
  hydrate precedence, promoted out of `re-frame.story.ui.toolbar-cljs-test`
  under rf2-r51p.

  ## What was wrong, and it was the file's LOCATION rather than its guard

  These rows sat inside `(when (browser?) ...)` in a namespace ending
  `-cljs-test`. `:node-test` selected it and the guard was false — this
  repo ships no jsdom, no happy-dom and no DOM shim in any dependency
  list, so `window.localStorage` is absent under Node — while
  `:browser-test` (`:ns-regexp \".*-dom-cljs-test$\"`) never loaded the
  file at all. They executed in NEITHER lane.

  Every row is a real `.setItem` / `.getItem` round-trip through
  `save-modes-to-storage!` / `load-modes-from-storage`, which is real
  host-storage semantics — the case rf2-r51p rules needs a real host
  rather than a stub.

  ## Why this is a SECOND dom namespace for the toolbar

  `re-frame.story.ui.toolbar-persistence-dom-cljs-test` already exists and
  owns the reload-survival scenarios (rf2-jpi7n): set modes, simulate a
  reload, assert rehydration, plus URL-beats-storage ordering. What lives
  HERE is the narrower contract its docstring names as its pair and does
  not itself cover — the bare save/load round-trip, and hydrate's
  precedence and pruning rules. Keeping them apart keeps each file's
  narrative intact.

  ## THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES

  `:node-test`'s `cljs-test$` is a bare SUFFIX match that
  `-dom-cljs-test` satisfies, so moving a row here ADDS the browser lane
  and removes nothing. Each row answers the node lane with a VISIBLE
  marker assertion rather than a silent `when`, so no deftest here holds
  zero assertions — a bare `when` would relocate the hollow shape
  rf2-r51p exists to remove rather than fix it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.toolbar :as rf.story.ui.toolbar]))

;; ---- host predicate ------------------------------------------------------

(defn- browser?
  "True when a working `js/window.localStorage` is present. FALSE under
  `:node-test`, TRUE under `:browser-test`; both targets load this ns."
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

;; ---- save → load round-trip ---------------------------------------------

(deftest storage-roundtrip
  (testing "save-modes-to-storage! + load-modes-from-storage round-trip"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story.ui.toolbar/save-modes-to-storage!
          [:Mode.app/dark :Mode.app/light])
        (is (= [:Mode.app/dark :Mode.app/light]
               (rf.story.ui.toolbar/load-modes-from-storage)))
        (rf.story.ui.toolbar/save-modes-to-storage! [])
        (is (= [] (rf.story.ui.toolbar/load-modes-from-storage)))))))

(deftest reset-modes-persists-empty
  (testing "reset-modes! persists the empty vector, not just the ratom.

            The node half of this row (shell state emptied) stays in the
            sibling; only the storage claim lives here."
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.app/x {:args {:k 1}})
        (rf.story.ui.toolbar/toggle-mode! :Mode.app/x)
        ;; Teeth: the old row asserted `(= [] (load-modes-from-storage))`
        ;; after the reset, which passes just as happily against a storage
        ;; that never held anything. Prove the toggle PERSISTED first, so
        ;; the empty read below is evidence that reset-modes! cleared it.
        (is (= [:Mode.app/x] (rf.story.ui.toolbar/load-modes-from-storage))
            "precondition: toggle-mode! really did persist the mode")
        (rf.story.ui.toolbar/reset-modes!)
        (is (= [] (rf.story.ui.toolbar/load-modes-from-storage))
            "reset-modes! persisted the empty vector")))))

;; ---- hydrate precedence + pruning ---------------------------------------

(deftest hydrate-from-storage-only-when-empty
  (testing "hydrate skips when the shell slot is already populated"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.app/x {:args {}})
        (rf.story/reg-mode :Mode.app/y {:args {}})
        (rf.story.ui.toolbar/save-modes-to-storage! [:Mode.app/x])
        ;; Teeth: without this, an unwritten slot would leave hydrate with
        ;; nothing to find, and the populated value below would survive for
        ;; the wrong reason — passing while proving nothing about
        ;; precedence.
        (is (= [:Mode.app/x] (rf.story.ui.toolbar/load-modes-from-storage))
            "precondition: storage really holds a COMPETING value")
        (rf.story.ui.state/swap-state!
          rf.story.ui.state/set-active-modes [:Mode.app/y])
        (rf.story.ui.toolbar/hydrate-modes-from-storage!)
        (is (= [:Mode.app/y] (:active-modes (rf.story.ui.state/get-state)))
            "non-empty slot is preserved")))))

(deftest hydrate-from-storage-prunes-stale
  (testing "hydrate drops mode ids that are not in the registrar"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story/reg-mode :Mode.app/x {:args {}})
        (rf.story.ui.toolbar/save-modes-to-storage!
          [:Mode.app/x :Mode.app/zzz])
        (rf.story.ui.toolbar/hydrate-modes-from-storage!)
        (is (= [:Mode.app/x] (:active-modes (rf.story.ui.state/get-state)))
            "the unregistered id was pruned at hydrate")))))
