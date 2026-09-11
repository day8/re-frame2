(ns day8.re-frame2-xray.acceptance.substrate-gap-dom-cljs-test
  "THE GAP THE ROOT SWAP CLOSES, pinned at the exact seam it changes
  (rf2-k97c.3, slice D).

  The two arms beside this file — `acceptance.uix-dom-cljs-test` and
  `acceptance.reagent-dom-cljs-test` — witness all six behavioural criteria
  through Xray's own React root, and they are green on a ratom-family
  adapter and on an element-shaped one alike. This file says what is still
  NOT true, so the pair is not read as 'the epic is done'.

  ## THE CLAIM

  `mount.cljs`'s PUBLIC `open!` — the door a host app actually uses —
  still paints through the INSTALLED adapter's `:render`, so it refuses an
  element-shaped substrate outright rather than mounting. That is the
  epic's coupling (1), still unsevered, and `react-element-render-kinds`
  is the denylist child .4 deletes once the swap is proven.

  [[the-public-mount-refuses-an-element-shaped-substrate]] asserts that
  refusal against literals — `false`, `:unsupported-substrate`,
  `:rf.adapter/uix` — and then measures that the HOST is undisturbed by it,
  which is the half of criterion 6 a refusal still has to satisfy.
  [[the-public-mount-succeeds-on-a-ratom-family-substrate]] is the control
  that makes the first row a claim about the SUBSTRATE rather than about
  this file's setup: same verb, same host element, same code path, and it
  mounts.

  ## THIS ROW IS MEANT TO GO RED

  When the swap lands and the denylist goes, `open!` stops refusing and the
  first row reddens. That red is the signal, not a regression: it means the
  element-shaped arm's six criteria are now evidence about the SHIPPED
  mount path rather than about Xray's root in isolation. Delete this row
  then, and say in the PR that you did.

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

  ## FIXTURE

  No `:adapter` in the fixture: each row installs its own with `rf/init!`,
  so both arms sit in ONE namespace and the control is a control rather
  than a second file that might differ in some other way."
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
;; The gap — the public mount still cannot paint on an element-shaped adapter
;; ===========================================================================

(deftest the-public-mount-refuses-an-element-shaped-substrate
  (testing "rf2-k97c.3 — with an element-shaped adapter installed, Xray's
            PUBLIC `open!` refuses rather than mounting, names the adapter
            kind it refused, and leaves the host's own element exactly as it
            found it.

            This is the epic's coupling (1) still unsevered: `open!` paints
            through the installed adapter's `:render`, and an element-shaped
            `:render` takes substrate-native React elements rather than the
            hiccup shell. The refusal is the DESIGNED behaviour — a clean
            diagnostic instead of an uncaught React child error — so the row
            asserts it is clean, not merely that it happened."
    (if-not (browser?)
      (is true "skipped: the :browser-test runner drives the real mount")
      (do
        (rf/init! rf.adapter.uix/adapter)
        (registry/register-xray-handlers!)
        (with-layout-host
          (fn [host]
            (let [ret (xray-mount/open!)]
              (is (= false (:ok? ret))
                  (str "`open!` reports failure rather than a mount. Got: "
                       (pr-str ret)))
              (is (= :unsupported-substrate (:reason ret))
                  (str "and names the reason. Got: " (pr-str (:reason ret))))
              (is (= :rf.adapter/uix (:adapter ret))
                  (str "and names the adapter kind it refused, so the message "
                       "tells the developer which install did it. Got: "
                       (pr-str (:adapter ret))))
              (is (= false (xray-mount/mounted?))
                  "and nothing was mounted")
              (is (= ret (:diagnostic (xray-mount/status)))
                  (str "and the same diagnostic is readable through the public "
                       "`status` surface, so a host can inspect it rather than "
                       "having to catch a return value. Got: "
                       (pr-str (:diagnostic (xray-mount/status)))))
              ;; ---- the host is undisturbed by the refusal -----------------
              (is (nil? (.getElementById js/document "rf-xray-root"))
                  "no Xray mount root was created in the document")
              (is (= 0 (.-childElementCount host))
                  (str "the host's own slot is still empty — Xray put nothing "
                       "in it. Got: " (.-childElementCount host)))
              (is (= "" (.-display (.-style host)))
                  (str "and its inline `display` is untouched, so a refused "
                       "open leaves no layout residue behind. Got: "
                       (pr-str (.-display (.-style host))))))))))))

;; ===========================================================================
;; The control — the same verb, on a substrate Xray can paint through
;; ===========================================================================

(deftest the-public-mount-succeeds-on-a-ratom-family-substrate
  (testing "rf2-k97c.3 — CONTROL for the row above. Same `open!`, same host
            element, same registrations: on a ratom-family adapter it
            MOUNTS. Without this, the refusal above is satisfied by any
            setup defect that stops Xray mounting for some other reason —
            a missing host, an unregistered handler, a frame that was never
            made — and the row would pass while measuring nothing about
            substrates at all."
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
