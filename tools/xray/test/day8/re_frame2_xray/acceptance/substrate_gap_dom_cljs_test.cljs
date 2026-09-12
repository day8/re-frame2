(ns day8.re-frame2-xray.acceptance.substrate-gap-dom-cljs-test
  "THE GAP THE ROOT SWAP CLOSED — now pinned CLOSED, at the same seam
  (rf2-k97c.3 slice D, discharged by rf2-k97c.4).

  The two arms beside this file — `acceptance.uix-dom-cljs-test` and
  `acceptance.reagent-dom-cljs-test` — witness all six behavioural criteria
  through Xray's own React root, on a ratom-family adapter and on an
  element-shaped one alike. This file covers the one thing they do not:
  the PUBLIC `open!` verb, the door a host app actually uses.

  ## THE FILE KEEPS ITS NAME ON PURPOSE

  There is no gap left to name, and the name is kept anyway: this file IS
  the record of the gap closing, and its two rows are the before and after
  of the same assertion at the same seam. Renaming it would leave the
  closure legible only in version history.

  ## WHAT WAS HERE, AND WHY IT WENT

  `the-public-mount-refuses-an-element-shaped-substrate` asserted, against
  the literals `false`, `:unsupported-substrate` and `:rf.adapter/uix`,
  that `open!` REFUSED an element-shaped host — because `mount.cljs`
  painted through the INSTALLED adapter's `:render` and a
  `react-element-render-kinds` denylist refused the kinds whose `:render`
  could not take the hiccup shell. Its own docstring promised the ending:
  *'THIS ROW IS MEANT TO GO RED. When the swap lands and the denylist goes,
  `open!` stops refusing and the first row reddens. That red is the signal,
  not a regression ... Delete this row then, and say in the PR that you
  did.'* rf2-k97c.3 (PR #9708) landed the root swap; rf2-k97c.4 retired the
  denylist and deleted that row, and its PR said so.

  ## WHAT REPLACED IT

  [[the-public-mount-succeeds-on-an-element-shaped-substrate]] is the same
  seam, same verb, same host element, same adapter — asserting the
  OPPOSITE outcome. A retirement that deleted the row and stopped there
  would have left the new truth unpinned, which is the shape in which a
  deliberate behaviour change becomes an accidental one later.

  [[the-public-mount-succeeds-on-a-ratom-family-substrate]] STAYS, and its
  job has changed. It was the control that made the refusal a claim about
  the SUBSTRATE rather than about this file's setup; with no refusal left
  there is nothing for it to discriminate, so that job is discharged. What
  it is now is the second FAMILY: the two rows together say the public
  `open!` mounts on a ratom-family host and on an element-shaped one, with
  the installed adapter the only difference between them, which is
  'indifferent to the installed adapter' stated where a host app can see
  it. Nothing else in the suite covers the public verb — the six-criteria
  arms mount Fresco's root directly (`test-helpers.criteria/mount-xray!`),
  never `open!` — so it is coverage rather than ceremony.

  ## A SECOND GAP, MEASURED AND DELIBERATELY NOT PINNED HERE

  Measured at trunk 1bf7106126 across three adapters in one browser run:
  Xray's chrome DOES NOT PAINT under the reagent-slim adapter, on either
  mount path — the public `open!` or Xray's own React root. In both cases
  `frame-switcher/frame-switcher-view` raises `:rf.error/no-frame-context`
  during render and React takes the whole subtree down. Full Reagent was
  green on both paths in that same run, so it is a statement about
  reagent-slim. `mount.cljs`'s own `unsupported-substrate-diagnostic` tells
  the user Xray's shell 'is hiccup rendered through the ratom-family
  adapters (Reagent / reagent-slim)', which that measurement contradicts.

  There is NO ROW FOR IT HERE, on purpose. Reproducing it means provoking
  an uncaught render-phase throw, and the browser runner fails a whole run
  on an uncaught `pageerror` INDEPENDENTLY of the cljs.test summary — so a
  row pinning it would red the lane for every unrelated suite in the build.
  The finding is recorded in the bead and in
  `acceptance.reagent-dom-cljs-test`'s docstring, which is why that arm
  runs on Reagent rather than on the reagent-slim the brief named. Fixing
  it is a production change and slice D is test-only.

  THAT SECOND GAP IS CLOSED. The paragraph above is kept as history.
  rf2-7ds8 (PR #9686) made `substrate/as-element` read the walk off the
  INSTALLED adapter instead of naming stock Reagent's statically, and
  `static.shell-reagent-slim-crossing-dom-cljs-test` is its witness — an
  error boundary above the crossing, which is the instrument this
  paragraph correctly judged a naked mount could not supply. rf2-k97c.3
  then deleted the two crossings it names, making
  `frame-switcher/frame-switcher-view` and `mode-pill/mode-pill`
  boundaries.

  MEASURED 2026-09-12: the six acceptance criteria run green with
  reagent-slim installed. Xray's OWN React root only — the public `open!`
  path was not re-measured there. The paragraph above cited
  `mount.cljs`'s `unsupported-substrate-diagnostic` for a claim about which
  adapters can host the shell; rf2-k97c.4 deleted that diagnostic's
  producer along with the denylist, so the claim has no author any more and
  the question it left open is not reopened by this file.

  ## FIXTURE

  No `:adapter` in the fixture: each row installs its own with `rf/init!`,
  so both rows sit in ONE namespace and the pair differs in the installed
  adapter and in nothing else — which is the entire content of the claim."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.mount :as xray-mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:ambient-frame nil
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      (xray-mount/reset-for-test!)
                      (when (exists? js/globalThis)
                        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false))
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser? [] (rf.fresco.impl.mount/browser?))

(defn- with-layout-host
  "Give `f` a real `[data-rf-xray-host]` element — the host app's own slot,
  which is what `open!` looks for — and take it away afterwards however `f`
  ends. The host is returned to `f` so a row can measure it."
  [f]
  (let [host (.createElement js/document "div")]
    (.setAttribute host "data-rf-xray-host" "")
    (.appendChild (.-body js/document) host)
    (try
      (f host)
      (finally
        (xray-mount/teardown!)
        (.remove host)))))

;; ===========================================================================
;; The gap, CLOSED — the public mount paints on an element-shaped adapter
;; ===========================================================================

(deftest the-public-mount-succeeds-on-an-element-shaped-substrate
  (testing "rf2-k97c.4 — with an element-shaped adapter installed, Xray's
            PUBLIC `open!` MOUNTS: a real root in the document, a node in
            the host's own slot, and a `status` reporting health rather
            than a refusal.

            This row stands where
            `the-public-mount-refuses-an-element-shaped-substrate` stood,
            asserting the opposite outcome at the same seam. That row was
            written to go red here and was deleted rather than repaired.
            What changed under it: rf2-k97c.3 stopped `open!` painting
            through the INSTALLED adapter's `:render` — the shell is a
            `re-frame.fresco` boundary on a root Xray owns — so an
            element-shaped `:render` is never handed hiccup and the
            `react-element-render-kinds` denylist rf2-k97c.4 deleted had
            no precondition left to guard."
    (if-not (browser?)
      (is true "skipped: the :browser-test runner drives the real mount")
      (do
        (rf/init! rf.adapter.uix/adapter)
        (registry/register-xray-handlers!)
        (with-layout-host
          (fn [host]
            ;; Caught for the same reason the deleted row caught: an
            ;; element-shaped host is exactly where a regression in the
            ;; owned-root path surfaces as a render-phase THROW rather
            ;; than as a false return, and an uncaught one would swallow
            ;; every assertion below it.
            (let [ret (try (xray-mount/open!)
                           (catch :default e
                             (is false
                                 (str "`open!` THREW on an element-shaped "
                                      "substrate: "
                                      (pr-str (:rf.error/id (ex-data e)
                                                            (ex-message e)))
                                      ". Xray is meant to own its own root "
                                      "here and never touch the host's "
                                      ":render — see rf2-k97c.3."))
                             ::threw))]
              (is (= true (xray-mount/mounted?))
                  (str "`open!` mounted on an element-shaped substrate. "
                       "Returned: " (pr-str (if (map? ret)
                                              (dissoc ret :node :unmount)
                                              ret))))
              (is (= :inline (:mode ret))
                  (str "on the inline surface. Got: " (pr-str (:mode ret))))
              ;; ---- and the refusal is gone, read through the PUBLIC
              ;; ---- surface a host would inspect -------------------------
              (is (= true (:ok? (:diagnostic (xray-mount/status))))
                  (str "the status diagnostic reports health. Got: "
                       (pr-str (:diagnostic (xray-mount/status)))))
              (is (nil? (:reason (:diagnostic (xray-mount/status))))
                  (str "naming no refusal reason — `:unsupported-substrate` "
                       "stays a reserved id in that vocabulary and no longer "
                       "fires. Got: "
                       (pr-str (:reason (:diagnostic (xray-mount/status))))))
              ;; ---- and it really painted --------------------------------
              (is (some? (.getElementById js/document "rf-xray-root"))
                  "a real mount root is in the document")
              (is (= 1 (.-childElementCount host))
                  (str "inside the host's own slot, which now holds exactly "
                       "the one node Xray put there. Got: "
                       (.-childElementCount host))))))))))

;; ===========================================================================
;; The second family — the same verb, on a ratom-family substrate
;; ===========================================================================

(deftest the-public-mount-succeeds-on-a-ratom-family-substrate
  (testing "rf2-k97c.4 — the OTHER adapter family through the same public
            verb, same host element, same registrations. Until the denylist
            went this was the CONTROL that made the refusal above a claim
            about the SUBSTRATE rather than about this file's setup; with
            no refusal left, that job is discharged. What it does now is
            pair with the row above to say the public `open!` mounts on
            both families with the installed adapter the only difference
            between the two — and it remains the only place the ratom-family
            host is driven through the public verb at all, the six-criteria
            arms mounting Fresco's root directly instead."
    (if-not (browser?)
      (is true "skipped: the :browser-test runner drives the real mount")
      (do
        (rf/init! rf.adapter.reagent/adapter)
        (registry/register-xray-handlers!)
        (with-layout-host
          (fn [host]
            (let [ret (xray-mount/open!)]
              (is (= true (xray-mount/mounted?))
                  (str "`open!` mounted on a ratom-family substrate. Returned: "
                       (pr-str (dissoc ret :node :unmount))))
              ;; `clear-diagnostic!` leaves an `{:ok? true}` marker rather
              ;; than nil, so the literal to compare against is the FLAG,
              ;; not the slot's emptiness.
              (is (= true (:ok? (:diagnostic (xray-mount/status))))
                  (str "with the diagnostic slot reporting health rather than a "
                       "refusal. Got: "
                       (pr-str (:diagnostic (xray-mount/status)))))
              (is (nil? (:reason (:diagnostic (xray-mount/status))))
                  (str "and naming no refusal reason. Got: "
                       (pr-str (:reason (:diagnostic (xray-mount/status))))))
              (is (some? (.getElementById js/document "rf-xray-root"))
                  "and a real mount root is in the document")
              (is (= 1 (.-childElementCount host))
                  (str "inside the host's own slot, which now holds exactly the "
                       "one node Xray put there. Got: "
                       (.-childElementCount host))))))))))
