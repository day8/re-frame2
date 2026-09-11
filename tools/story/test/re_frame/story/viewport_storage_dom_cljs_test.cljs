(ns re-frame.story.viewport-storage-dom-cljs-test
  "Browser-lane home for viewport-selection persistence (rf2-zll4h),
  assembled under rf2-r51p out of rows that ran in NO lane at all.

  ## Where these rows came from, and why neither source could run them

  `re-frame.story.viewport-test` (`viewport_test.cljc`) held four
  `#?(:cljs (deftest storage-* ...))` rows. Its namespace ends `-test`,
  not `cljs-test`, so `:node-test`'s `:ns-regexp` (`cljs-test$`) does not
  select it, `:browser-test`'s (`.*-dom-cljs-test$`) does not match it,
  and nothing else requires it. Those rows were not merely guarded-false
  — they were UNREACHABLE: no CLJS build compiled them at all.

  That file's docstring was candid about half of this and wrong about the
  rest. It said CLJS coverage \"comes from the parallel
  `viewport_switcher_cljs_test`\" — but the corresponding rows THERE were
  themselves dead, sitting inside `(when (browser?) ...)` in a namespace
  the browser lane never loads and a node runtime that has no
  `window.localStorage` (this repo ships no jsdom, no happy-dom and no
  DOM shim in any dependency list). Both layers of the intended coverage
  were inert, and each pointed at the other.

  Both defects have one repair, because both want the same thing: a real
  `window.localStorage`. Every row below is a genuine round-trip —
  `save-to-storage!` writes through `.setItem`, `load-from-storage` reads
  back through `.getItem`, `hydrate!` seeds the shell slot from what
  survived. That is real host-storage semantics, which rf2-r51p rules
  needs a real host rather than a stub.

  ## THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES

  `:node-test`'s `cljs-test$` is a bare SUFFIX match that
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does, and
  `implementation/shadow-cljs.edn` records that overlap as deliberate. So
  moving a row here ADDS the browser lane; it removes nothing. Each row
  answers the node lane with a VISIBLE marker assertion rather than a
  silent `when`, so no deftest here holds zero assertions — a bare `when`
  would merely relocate the hollow shape rf2-r51p exists to remove.

  The JVM half of both source files is untouched: neither carries a
  single `#?(:clj ...)` form, and every cross-host row in them (preset
  table, custom `{:width :height}` validation, resolve precedence,
  `wrap-style`) is a bare unconditional `deftest` that still runs under
  `clojure -M:test`.

  These assertions had never executed in ANY lane. A failure here is
  evidence about `save-to-storage!` / `load-from-storage` / `hydrate!`
  arriving for the first time, not a regression introduced by the move."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.viewport :as rf.story.viewport]
            [re-frame.story.ui.viewport-switcher
             :as rf.story.ui.viewport-switcher]
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
    (try (.removeItem (.-localStorage js/window) rf.story.viewport/ls-key)
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
        (rf.story.viewport/save-to-storage! :tablet)
        (is (= :tablet (rf.story.viewport/load-from-storage)))
        (clear-storage!)))))

(deftest storage-roundtrip-custom
  (testing "save → load returns the persisted custom {:width :height} map"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (clear-storage!)
        (rf.story.viewport/save-to-storage! {:width 800 :height 600})
        (is (= {:width 800 :height 600} (rf.story.viewport/load-from-storage)))
        (clear-storage!)))))

(deftest storage-save-nil-clears
  (testing "save-to-storage! nil clears the persisted slot"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (clear-storage!)
        (rf.story.viewport/save-to-storage! :tablet)
        ;; Precondition with teeth: prove something WAS persisted, so the
        ;; nil below is evidence that the clear ran.
        (is (some? (rf.story.viewport/load-from-storage)))
        (rf.story.viewport/save-to-storage! nil)
        (is (nil? (rf.story.viewport/load-from-storage)))
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
        (rf.story.viewport/save-to-storage! :tablet)
        (is (= :tablet (rf.story.viewport/load-from-storage))
            "precondition: a valid selection really does persist")
        (rf.story.viewport/save-to-storage! :phablet)        ;; unknown preset
        (is (= :tablet (rf.story.viewport/load-from-storage))
            "an unknown preset id neither persists nor clobbers the slot")
        (rf.story.viewport/save-to-storage! {:width "no"})   ;; bad shape
        (is (= :tablet (rf.story.viewport/load-from-storage))
            "a malformed custom map neither persists nor clobbers the slot")
        (clear-storage!)))))

;; ---- hydrate! from the persisted slot -----------------------------------

(deftest hydrate-from-storage-seeds-empty-slot
  (testing "hydrate! seeds an empty shell slot from persisted localStorage"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story.viewport/save-to-storage! :tablet)
        (rf.story.ui.state/reset-shell-state!)
        (is (nil? (:viewport (rf.story.ui.state/get-state))))
        (rf.story.ui.viewport-switcher/hydrate!)
        (is (= :tablet (:viewport (rf.story.ui.state/get-state))))
        (clear-storage!)))))

(deftest hydrate-skips-populated-slot
  (testing "hydrate is idempotent — an already-populated slot is left alone"
    (if-not (browser?)
      (is true skip-msg)
      (do
        (rf.story.viewport/save-to-storage! :tablet)
        (rf.story.ui.state/swap-state! assoc :viewport :mobile-portrait)
        (rf.story.ui.viewport-switcher/hydrate!)
        (is (= :mobile-portrait (:viewport (rf.story.ui.state/get-state)))
            "populated slot was preserved")
        (clear-storage!)))))
