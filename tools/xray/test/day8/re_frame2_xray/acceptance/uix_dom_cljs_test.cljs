(ns day8.re-frame2-xray.acceptance.uix-dom-cljs-test
  "THE ELEMENT-SHAPED ARM of the epic's acceptance harness (rf2-k97c.3,
  slice D). The six criterion bodies live in
  `day8.re-frame2-xray.acceptance.test-helpers.criteria`; this namespace
  supplies ONE thing — the installed adapter — and wraps them in `deftest`.

  ## WHY UIx, AND NOT `test-react`

  The brief asked for reagent-slim plus ONE element-shaped adapter, chosen
  by reading what actually ships. `implementation/adapters/README.md`
  §'Adapters that ship today' rosters three — Reagent, UIx, reagent-slim —
  and of those UIx is the element-shaped one: its `:render` comes from
  `re-frame.substrate.spine/make-react-adapter` and takes substrate-native
  React ELEMENTS rather than hiccup — the property that made Xray
  unmountable on such a host until it owned its own root, and the reason
  this is the adapter worth running the six criteria under.

  `adapters/test-react/` was considered and REJECTED, on its own docstring
  rather than on its shipping status. It 'simulates the React class-3
  lifecycle in pure CLJC ... without React, a DOM, or jsdom', and states
  it performs 'no automatic rerender on app-db change, React context
  traversal, or DOM source annotation'. Every one of the six criteria
  needs at least one of those: criterion 1 needs a DOM, 2 and 3 need
  re-render on change, 4 needs React context traversal. A harness run on
  it would report green having exercised none of them — the fail-open
  shape this whole epic is trying not to ship.

  ## WHAT THIS ARM IS EVIDENCE FOR

  The epic's forward-looking clause: *a future non-React browser adapter
  supplying only the container quartet plus the listener API should get
  Xray with no Xray-side work*. The UIx adapter is INSTALLED here as the
  inspected application's substrate and is never called to paint anything
  — Xray owns its own root. Six green rows on a substrate whose `:render`
  Xray cannot use is what 'indifferent to the installed adapter' means in
  a form a row can witness.

  ## WHAT IT IS NOT EVIDENCE FOR

  The PUBLIC `open!` verb, which this file never calls: the six criteria
  run through Fresco's root directly
  (`test-helpers.criteria/mount-xray!`). That is deliberate — one body, N
  substrates — and it leaves the shipped door to its companion,
  `substrate_gap_dom_cljs_test`, which drives `open!` itself on this same
  adapter.

  This paragraph used to record the opposite conclusion, and the change is
  worth naming: `mount.cljs` REFUSED this adapter outright, `open!`
  returning the `:unsupported-substrate` diagnostic and mounting nothing,
  because the shell was painted through the installed adapter's `:render`.
  rf2-k97c.3 moved the shell onto Xray's own root and rf2-k97c.4 retired
  the refusal, so the six rows below are now evidence about a host Xray
  actually serves rather than about a root in isolation.

  ## NODE LANE

  `cljs-test$` matches `-dom-cljs-test` too, so the `:node-test` build
  compiles this namespace. Every row short-circuits there and reports the
  skip."
  (:require [cljs.test :refer-macros [async deftest testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.acceptance.test-helpers.criteria :as criteria]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private substrate
  {:id :uix :label "UIx — element-shaped"})

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     ;; `:ambient-frame nil` is load-bearing, not tidiness. There is NO
     ;; `:rf/default` fallback — a nil frame context RAISES
     ;; `:rf.error/no-frame-context` — so a dynamic-var frame left in
     ;; ambient scope would let a read that failed to resolve its own
     ;; context answer from somewhere else, which is a frame miss reading
     ;; as a green row. Criterion 4 is the row that would go quietly wrong.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the entry cache makes
                      ;; criterion 6's baseline read a residue that is not
                      ;; this chrome's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(deftest c1-first-display
  (testing "criterion 1 — first display, with an element-shaped adapter installed"
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
