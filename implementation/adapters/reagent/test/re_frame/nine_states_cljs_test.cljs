(ns re-frame.nine-states-cljs-test
  "Integration test: drives the nine-states example's headless test
   fixtures. Each fixture spins a fresh frame via `make-frame`, drives
   `app-db` into one of the canonical UI states, and asserts the
   matching tag union + `:ui/render` keyword.

   The fixtures live under examples/reagent/nine_states/test/nine_states/ — extracted
   from the example's `core.cljs` so the production source stays
   test-free (mirrors the realworld layout).

   The example registers its handlers / subs / views / machines at
   namespace-load time. CLJS has no runtime (require :reload), so this
   test relies on those registrations staying live for the test run.
   Each test-state-N fixture uses make-frame to spin up a fresh frame,
   so per-test isolation comes from frame creation, not registry resets.

   Per rf2-am9d the fixture uses snapshot/restore via
   re-frame.test-support so the contract is uniform across CLJS
   fixtures — the snapshot captures the example's ns-load
   registrations, and the restore on the way out leaves them intact for
   any subsequent test ns."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures]]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]
            [nine-states.core]
            [nine-states.core-test :as ns-t]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(deftest nine-states-runs-end-to-end
  (testing "state 1 — nothing (never fetched)"
    (ns-t/test-state-1-nothing))
  (testing "state 2 — loading (fetch in flight)"
    (ns-t/test-state-2-loading))
  (testing "state 3 — empty (fetched, zero results)"
    (ns-t/test-state-3-empty))
  (testing "state 4 — one (single-item layout)"
    (ns-t/test-state-4-one))
  (testing "state 5 — some (small, manageable list)"
    (ns-t/test-state-5-some))
  (testing "state 6 — too-many (overwhelming list)"
    (ns-t/test-state-6-too-many))
  (testing "state 7 — incorrect (form validation error)"
    (ns-t/test-state-7-incorrect))
  (testing "state 8 — correct (form submit happy path)"
    (ns-t/test-state-8-correct))
  (testing "state 9 — done (terminal, read-only)"
    (ns-t/test-state-9-done)))
