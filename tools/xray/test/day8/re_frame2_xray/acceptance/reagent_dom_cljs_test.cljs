(ns day8.re-frame2-xray.acceptance.reagent-dom-cljs-test
  "THE RATOM-FAMILY ARM of the epic's acceptance harness (rf2-k97c.3,
  slice D). Same six criterion bodies as the UIx arm beside it, same
  subject, same mount verb; the ONLY variable is the installed adapter.

  Two arms that differ in exactly one thing is what makes the pair
  evidence. Either arm alone says 'Xray works here'; the pair says 'Xray
  does not care which one is installed', which is the claim the root swap
  makes and the thing the swap PR has to keep true.

  ## WHY REAGENT RATHER THAN reagent-slim, AND IT IS A FINDING

  Slice D's brief named reagent-slim for this arm. Measured at trunk
  1bf7106126, Xray's chrome DOES NOT PAINT under the reagent-slim adapter
  — on either mount path, the production one or this one — because
  `frame-switcher/frame-switcher-view` raises `:rf.error/no-frame-context`
  during render and React takes the whole subtree down with it. Reagent is
  green on both paths in the same run, which is what makes that a
  statement about reagent-slim rather than about this harness.

  That defect is a PRODUCTION one and this slice is test-only, so it is
  recorded rather than repaired: `substrate_gap_dom_cljs_test` pins it with
  the Reagent control beside it, and this arm carries the criteria on the
  ratom-family adapter that can hold them today. When the defect is fixed,
  a reagent-slim arm is this file with one `:require` and one `:adapter`
  changed.

  ## THAT DEFECT IS FIXED, AND THE PARAGRAPH ABOVE IS KEPT AS HISTORY

  rf2-7ds8 repaired it (PR #9686): `day8.re-frame2-xray.substrate/as-element`
  now reads the hiccup->React walk off the INSTALLED adapter rather than
  naming stock Reagent's statically, so the crossing no longer loses the
  frame under reagent-slim. rf2-k97c.3 then removed the two crossings this
  file names outright, by making `frame-switcher/frame-switcher-view` and
  `mode-pill/mode-pill` boundaries.

  MEASURED 2026-09-12 rather than inferred, by running this file's own six
  criteria with `re-frame.adapter.reagent-slim/adapter` substituted: all six
  PASS. They pass at rf2-k97c.3's merge-base too, which is what attributes
  the repair to rf2-7ds8 and not to the island deletion.

  WHAT THAT MEASUREMENT DID NOT COVER: it was [[mount-xray!]]'s path only
  — Xray's OWN React root, mounting the Static surface — and the PUBLIC
  `open!` path, the other half of the original three-adapter finding, was
  NOT re-measured with it.

  BOTH GAPS ARE NOW CLOSED, and this paragraph is kept because it says
  what the arm below is and is not evidence for. rf2-now5 took the
  ~10-line addition this docstring offered: `acceptance.reagent-slim-dom-cljs-test`
  is this file with one `:require` and one `:adapter` changed, so the six
  criteria now RUN on reagent-slim every PR rather than having been
  measured once. The public `open!` path was measured under the same
  adapter in the same pass and is green; its row lives with the other two
  public-door rows in `acceptance.substrate-gap-dom-cljs-test`, not here,
  because this file never calls `open!`.

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
