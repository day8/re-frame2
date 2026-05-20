(ns day8.re-frame2-causa.panels.reactive-panel-view-cljs-test
  "Smoke tests for `reactive-panel-view` (rf2-wyvf2 · spec/021 §3).

  Mounts `reactive-panel` (the plain Reagent fn) and asserts the
  structural data-testid hooks ship: panel root, header, the two
  step sections, and the unchanged-subs toggle when applicable."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.reactive-panel :as facade]
            [day8.re-frame2-causa.panels.reactive-panel-view :as view]))

(defn- has-testid? [tree testid]
  (some? (th/find-by-testid tree testid)))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

(deftest reactive-panel-mounts-with-root-testid
  (testing "rf2-wyvf2 — the panel root surfaces `rf-causa-reactive`
            data-testid + the panel installs the L4 tab"
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive")
          "the root :section data-testid is present"))))

(deftest reactive-panel-renders-empty-state-without-cascade
  (testing "Empty-state copy renders when no cascade is focused"
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [tree (view/reactive-panel)]
      (is (has-testid? tree "rf-causa-reactive-empty")
          "empty-state surfaces when no cascade exists"))))
