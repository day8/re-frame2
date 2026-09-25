(ns day8.re-frame2-xray.acceptance.reagent-dom-cljs-test
  "THE RATOM-FAMILY ARM of the epic's acceptance harness, on stock
  Reagent. Same six criterion bodies as the UIx arm beside it, same
  subject, same mount verb; the ONLY variable is the installed adapter.

  Two arms that differ in exactly one thing is what makes the pair
  evidence. Either arm alone says 'Xray works here'; the pair says 'Xray
  does not care which one is installed', which is the claim Xray's own
  React root makes and the property this harness keeps true.

  ## WHAT THIS ARM IS AND IS NOT EVIDENCE FOR

  It mounts through [[mount-xray!]] — Xray's OWN React root, mounting the
  Static surface — and never calls `open!`. The public `open!` rows live
  with the other public-door rows in `acceptance.substrate-gap-dom-cljs-test`.

  `acceptance.reagent-slim-dom-cljs-test` is this file with one `:require`
  and one `:adapter` changed, so the six criteria run on reagent-slim
  every PR as well. They pass there because
  `day8.re-frame2-xray.substrate/as-element` reads the hiccup->React walk
  off the INSTALLED adapter rather than naming stock Reagent's statically,
  so a crossing keeps its frame under reagent-slim, and because
  `frame-switcher/frame-switcher-view` and `mode-pill/mode-pill` are Fresco
  boundaries rather than Reagent islands.

  ## NODE LANE

  Every row short-circuits and reports the skip; see the UIx arm's
  docstring."
  (:require [cljs.test :refer-macros [async deftest testing use-fixtures]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.acceptance.test-helpers.criteria :as criteria]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private substrate
  {:id :reagent :label "Reagent — ratom-family"})

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (rf.fresco.impl.collector/reset-runtime!))}))

(deftest c1-first-display
  (testing "criterion 1 — first display, with a ratom-family adapter installed"
    (async done (criteria/c1-first-display! substrate done))))

(deftest c2-updates-as-the-app-changes
  (testing "criterion 2 — Xray follows a real change in the inspected app"
    (async done (criteria/c2-updates-as-the-app-changes! substrate done))))

(deftest c3-xrays-own-interactions
  (testing "criterion 3 — a real click on Xray's own control works end to end"
    (async done (criteria/c3-xrays-own-interactions! substrate done))))

(deftest c4-tool-local-state-and-frame-targeting
  (testing "criterion 4 — tool state stays out of the app, commands route where told"
    (async done (criteria/c4-tool-local-state-and-frame-targeting! substrate done))))

(deftest c5-no-masquerading-as-application-evidence
  (testing "criterion 5 — Xray's activity is absent from the app's own evidence"
    (async done (criteria/c5-no-masquerading-as-application-evidence! substrate done))))

(deftest c6-clean-teardown-and-reopen
  (testing "criterion 6 — teardown returns everything, and the host is undisturbed"
    (async done (criteria/c6-clean-teardown-and-reopen! substrate done))))
