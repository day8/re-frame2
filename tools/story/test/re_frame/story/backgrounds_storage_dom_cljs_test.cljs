(ns re-frame.story.backgrounds-storage-dom-cljs-test
  "Browser-lane home for background-selection persistence (rf2-zll4h),
  assembled under rf2-r51p out of rows that ran in NO lane at all.

  ## Where these rows came from, and why neither source could run them

  `re-frame.story.backgrounds-test` (`backgrounds_test.cljc`) held four
  `#?(:cljs (deftest storage-* ...))` rows. Its namespace ends `-test`,
  not `cljs-test`, so `:node-test`'s `:ns-regexp` (`cljs-test$`) does not
  select it and `:browser-test`'s (`.*-dom-cljs-test$`) does not match it
  either, and nothing else requires it. Those rows were therefore not
  merely guarded-false — they were UNREACHABLE: no CLJS build compiled
  them at all. That file's own docstring said \"Runs on both JVM and CLJS
  — the CLJS arm exercises the localStorage round-trip\", which had never
  been true.

  `re-frame.story.ui.backgrounds-switcher-cljs-test` held the two
  `hydrate!` rows. That namespace IS selected by `:node-test`, but its
  rows sat inside `(when (browser?) ...)` and the node runtime has no
  `window.localStorage` — this repo ships no jsdom, no happy-dom and no
  DOM shim in any dependency list — while `:browser-test` never loads a
  plain `-cljs-test` file. So those executed in neither lane.

  Both defects have one repair, because both rows want the same thing: a
  real `window.localStorage`. Every row below is a genuine round-trip —
  `save-to-storage!` writes through `.setItem`, `load-from-storage` reads
  back through `.getItem`, `hydrate!` seeds the shell slot from what
  survived. That is real host-storage semantics, which is the case
  rf2-r51p rules needs a real host rather than a stub.

  ## THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES

  `:node-test`'s `cljs-test$` is a bare SUFFIX match, which
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does, and
  `implementation/shadow-cljs.edn` records that overlap as deliberate. So
  moving a row here ADDS the browser lane; it removes nothing. Each row
  answers the node lane with a VISIBLE marker assertion rather than a
  silent `when`, so no deftest here holds zero assertions — that hollow
  shape is what rf2-r51p exists to remove, and a bare `when` would
  merely relocate it.

  The JVM half of both source files is untouched: neither carries a
  single `#?(:clj ...)` form, and every cross-host row in them (preset
  table, custom-colour validation, resolve precedence, `wrap-style`) is
  a bare unconditional `deftest` that still runs under `clojure -M:test`.

  These assertions had never executed in ANY lane. A failure here is
  evidence about `save-to-storage!` / `load-from-storage` / `hydrate!`
  arriving for the first time, not a regression introduced by the move."
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
        ;; This row used to assert `(nil? (load-from-storage))` straight
        ;; after a `clear-storage!`, which passes against a storage that
        ;; silently swallows every write — the exact vacuity rf2-r51p
        ;; tightens for. Seed a VALID value first, so the assertions below
        ;; distinguish "the invalid save was refused" from "nothing works".
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
