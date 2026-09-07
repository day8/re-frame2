(ns re-frame.story.ui.cofx-cljs-test
  "Regression guard for rf2-gg4dz — the `:story/active-modes` and
  `:story/active-args` cofx suppliers must RETURN the right value
  (EP-0017 value-returning `reg-cofx`). The runtime delivers a supplier's
  return value flat under the declared cofx-id; if a supplier regressed to
  returning the wrong shape the consuming handler binds nil silently and
  the chrome's mode/arg awareness disappears.

  EP-0017 retired the ctx→ctx shape: both suppliers are now value-returning
  nullary fns (ambient grade — a snapshot of shell UI state, never
  recorded). The runtime does the `[:coeffects <id>]` placement; the
  supplier just returns the value."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.story.ui.cofx :as rf.story.ui.cofx]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.story.ui.cofx/install-canonical-cofx!))}))

(deftest story-active-modes-cofx-returns-vector
  (testing ":story/active-modes supplier returns the active-modes vector"
    (let [meta     (rf/handler-meta {:source :store :kind :cofx :id :story/active-modes})
          supplier (:handler-fn meta)
          result   (supplier)]
      (is (vector? result)
          "the supplier returns the snapshot vector directly (default empty)"))))

(deftest story-active-args-cofx-returns-map
  (testing ":story/active-args supplier returns the deep-merged args map"
    (let [meta     (rf/handler-meta {:source :store :kind :cofx :id :story/active-args})
          supplier (:handler-fn meta)
          result   (supplier)]
      (is (map? result)
          "the supplier returns the merged :args map directly (default empty)"))))
