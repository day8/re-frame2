(ns re-frame.story.backgrounds-storage-dom-cljs-test
  "Browser-lane home for background-selection persistence.

  ## Why these rows live in a `-dom-cljs-test` namespace

  Every row below is a genuine round-trip — `save-to-storage!` writes
  through `.setItem`, `load-from-storage` reads back through `.getItem`,
  `hydrate!` seeds the shell slot from what survived. That is real
  host-storage semantics, which needs a real `window.localStorage` rather
  than a stub.

  A namespace ending `-test` (such as `backgrounds_test.cljc`) is selected
  by neither CLJS build: `:node-test`'s `:ns-regexp` is `cljs-test$` and
  `:browser-test`'s is `.*-dom-cljs-test$`. A plain `-cljs-test` namespace
  loads only under `:node-test`, whose runtime has no
  `window.localStorage` — this repo ships no jsdom, no happy-dom and no
  DOM shim in any dependency list. A `-dom-cljs-test` namespace is the
  one the browser lane loads.

  ## THE GUARD, BECAUSE THIS FILE RUNS ON BOTH LANES

  `:node-test`'s `cljs-test$` is a bare SUFFIX match, which
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does, and
  `implementation/shadow-cljs.edn` records that overlap as deliberate.
  Each row answers the node lane with a VISIBLE marker assertion rather
  than a silent `when`, so no deftest here holds zero assertions — a
  bare `when` would leave a hollow deftest in the node lane.

  The cross-host rows (preset table, custom-colour validation, resolve
  precedence, `wrap-style`) are bare unconditional `deftest`s in
  `backgrounds_test.cljc`, which run under `clojure -M:test`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.backgrounds :as rf.story.backgrounds]
            [re-frame.story.ui.backgrounds-switcher
             :as rf.story.ui.backgrounds-switcher]
            [re-frame.story.ui.state :as rf.story.ui.state]))

;; ---- host predicate ------------------------------------------------------

(defn- browser?
  "True when a working `js/window.localStorage` is present.

  FALSE under `:node-test`, TRUE under `:browser-test`. Both targets load
  this namespace (see the ns docstring), so this predicate is what routes
  each row to the lane that can actually run it."
  []
  (and (exists? js/window) (.-localStorage js/window)))

(defn- clear-storage! []
  (when (browser?)
    (try (.removeItem (.-localStorage js/window) rf.story.backgrounds/ls-key)
         (catch :default _ nil))))

(def ^:private skip-msg
  "skipped: no localStorage (node lane — see ns docstring)")

;; ---- save / load round-trip ---------------------------------------------

(deftest storage-roundtrip-preset
  (testing "save → load returns the persisted preset id"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (clear-storage!)
        (rf.story.backgrounds/save-to-storage! :dark)
        (is (= :dark (rf.story.backgrounds/load-from-storage)))
        (clear-storage!)))))

(deftest storage-roundtrip-custom
  (testing "save → load returns the persisted custom hex colour"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (clear-storage!)
        (rf.story.backgrounds/save-to-storage! "#abc123")
        (is (= "#abc123" (rf.story.backgrounds/load-from-storage)))
        (clear-storage!)))))

(deftest storage-save-nil-clears
  (testing "save-to-storage! nil clears the persisted slot"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (clear-storage!)
        (rf.story.backgrounds/save-to-storage! :dark)
        ;; Precondition with teeth: prove something WAS persisted, so the
        ;; nil below is evidence that the clear ran.
        (is (some? (rf.story.backgrounds/load-from-storage)))
        (rf.story.backgrounds/save-to-storage! nil)
        (is (nil? (rf.story.backgrounds/load-from-storage)))
        (clear-storage!)))))

(deftest storage-rejects-invalid-on-save
  (testing "an invalid selection is DROPPED rather than persisted — and
            because `save-to-storage!` only writes when `coerce` yields a
            value, the previously persisted selection SURVIVES"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (clear-storage!)
        ;; Asserting `(nil? (load-from-storage))` straight after a
        ;; `clear-storage!` would pass against a storage that silently
        ;; swallows every write. Seed a VALID value first, so the assertions
        ;; below distinguish "the invalid save was refused" from "nothing
        ;; works".
        (rf.story.backgrounds/save-to-storage! :dark)
        (is (= :dark (rf.story.backgrounds/load-from-storage))
            "precondition: a valid selection really does persist")
        (rf.story.backgrounds/save-to-storage! :neon)          ;; unknown preset
        (is (= :dark (rf.story.backgrounds/load-from-storage))
            "an unknown preset id neither persists nor clobbers the slot")
        (rf.story.backgrounds/save-to-storage! "rgb(1,2,3)")   ;; bad shape
        (is (= :dark (rf.story.backgrounds/load-from-storage))
            "a non-hex colour string neither persists nor clobbers the slot")
        (clear-storage!)))))

;; ---- hydrate! from the persisted slot -----------------------------------

(deftest hydrate-from-storage-seeds-empty-slot
  (testing "hydrate! seeds an empty shell slot from persisted localStorage"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story.backgrounds/save-to-storage! :dark)
        (rf.story.ui.state/reset-shell-state!)
        (is (nil? (:background (rf.story.ui.state/get-state))))
        (rf.story.ui.backgrounds-switcher/hydrate!)
        (is (= :dark (:background (rf.story.ui.state/get-state))))
        (clear-storage!)))))

(deftest hydrate-skips-populated-slot
  (testing "hydrate is idempotent — an already-populated slot is left alone"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story.backgrounds/save-to-storage! :dark)
        (rf.story.ui.state/swap-state! assoc :background :midnight)
        (rf.story.ui.backgrounds-switcher/hydrate!)
        (is (= :midnight (:background (rf.story.ui.state/get-state)))
            "populated slot was preserved")
        (clear-storage!)))))
