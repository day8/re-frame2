(ns day8.re-frame2-xray.acceptance.reagent-slim-dom-cljs-test
  "THE SLIM RATOM-FAMILY ARM of the epic's acceptance harness (rf2-now5,
  taking the addition `acceptance.reagent-dom-cljs-test`'s docstring
  offered). The six criterion bodies live in
  `day8.re-frame2-xray.acceptance.test-helpers.criteria`; this namespace
  supplies ONE thing — the installed adapter — and wraps them in `deftest`.

  Three arms that differ in exactly one thing is what makes the set
  evidence. Any arm alone says 'Xray works here'; the three together say
  'Xray does not care which one is installed', across BOTH adapter
  families and across both ratom runtimes within the ratom-family one.

  ## THIS FILE IS THE REAGENT ARM WITH ONE `:require` AND ONE `:adapter`
  ## CHANGED, WHICH IS EXACTLY WHAT THAT ARM SAID IT WOULD BE

  `acceptance.reagent-dom-cljs-test` named reagent-slim for itself and
  could not take it: measured at trunk 1bf7106126, Xray's chrome DID NOT
  PAINT under the reagent-slim adapter on either mount path, because
  `frame-switcher/frame-switcher-view` raised `:rf.error/no-frame-context`
  during render and React took the whole subtree down. That arm ran on
  full Reagent instead and recorded the reason.

  WHAT CHANGED UNDER IT, and neither half is this bead's work:

  * rf2-7ds8 (PR #9686) made `day8.re-frame2-xray.substrate/as-element`
    read the hiccup->React walk off the INSTALLED adapter rather than
    naming stock Reagent's statically, so the crossing stopped losing the
    frame under reagent-slim.
  * rf2-k97c.3 then removed the two crossings that arm names outright, by
    making `frame-switcher/frame-switcher-view` and `mode-pill/mode-pill`
    boundaries.

  The reagent arm's docstring records the consequence as a 2026-09-12
  MEASUREMENT — its own six criteria run with this adapter substituted,
  all six green, green at rf2-k97c.3's merge-base too, which is what
  attributes the repair to rf2-7ds8 and not to the island deletion. This
  file is the difference between a measurement somebody made once and a
  row that runs on every PR.

  ## WHY A THIRD ARM IS COVERAGE RATHER THAN CEREMONY

  reagent-slim is not a cosmetic variant of Reagent. It is a separate
  ratom runtime, and it is the adapter `tools/xray/deps.edn` carries as
  the DEFAULT transitive one — so it is the ratom build a host most
  easily ends up on without choosing it. The nine static
  `reagent.core/as-element` sites rf2-7ds8 retired were invisible under
  stock Reagent and blanked Xray under this adapter; that failure mode
  was specific to the pairing, and only a row that installs THIS adapter
  can witness its repair.

  ## WHAT THIS ARM IS NOT EVIDENCE FOR

  The PUBLIC `open!` verb, which this file never calls — the six criteria
  mount Fresco's root directly (`test-helpers.criteria/mount-xray!`), by
  design: one body, N substrates. The public door is
  `acceptance.substrate-gap-dom-cljs-test`, which drives `open!` itself
  and, since rf2-now5, carries a reagent-slim row of its own beside its
  Reagent and UIx ones.

  ## NODE LANE

  `cljs-test$` matches `-dom-cljs-test` too, so the `:node-test` build
  compiles this namespace. Every row short-circuits there and reports the
  skip."
  (:require [cljs.test :refer-macros [async deftest testing use-fixtures]]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.acceptance.test-helpers.criteria :as criteria]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private substrate
  {:id :reagent-slim :label "reagent-slim — ratom-family, slim runtime"})

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent-slim/adapter
     ;; `:ambient-frame nil` is load-bearing, not tidiness — see the UIx
     ;; arm's note. A dynamic-var frame left in ambient scope would let a
     ;; read that failed to resolve its own context answer from somewhere
     ;; else, which is a frame miss reading as a green row. That matters
     ;; more here than anywhere: the defect this arm exists to pin was
     ;; precisely a LOST FRAME at the hiccup->React crossing.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(deftest c1-first-display
  (testing "criterion 1 — first display, with the slim ratom-family adapter installed"
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
