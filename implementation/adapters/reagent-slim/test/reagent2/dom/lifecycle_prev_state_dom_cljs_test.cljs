(ns reagent2.dom.lifecycle-prev-state-dom-cljs-test
  "rf2-08hx1 — the mounted React update proof for the Form-3 paired
  update lifecycles, under a React 19 `createRoot`.

  WHAT IT PROVES. `reagent2.core/create-class`'s two paired update
  lifecycles receive the documented stock-Reagent argument shape on the
  REAL React lifecycle path (not just via direct prototype invocation):

    :get-snapshot-before-update  (fn [this prev-argv prev-state] ...) → snapshot
    :component-did-update        (fn [this prev-argv prev-state snapshot] ...)

  A changed-argv re-render commits; BOTH callbacks fire exactly once;
  the gSBU return value reaches :component-did-update's snapshot slot;
  React's prevState is forwarded verbatim (a cDM `setState` seeds a
  real non-nil state — the framework argv-equality sCU swallows that
  re-render, but React still commits the state, so the later
  changed-argv update observes it as prevState); and the update is
  asserted to have actually occurred (committed DOM advanced), so a
  skipped lifecycle cannot read green. The callbacks are FIXED-arity —
  the documented copy-pasteable shape — so a bridge that drops or
  shifts an argument fails on sentinel identity rather than being
  tolerated by a variadic probe.

  WHY A DOM FILE. The sibling unit tests in
  `reagent2.impl.component-cljs-test` invoke the prototype methods
  directly with sentinel React args — full control, but no reconciler.
  Only a real `react-dom/client` root proves React itself feeds the
  bridge (prevProps, prevState, snapshot) in the documented order
  across a genuine commit.

  TEST-ONLY. ns ends in `-dom-cljs-test` so shadow-cljs's
  `:browser-test` discovers it for the real-DOM assertion; the
  `:node-test` runner also loads it (matches `cljs-test$`), where the
  body gates on `(browser?)` and no-ops cleanly (no DOM)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(deftest mounted-update-forwards-prev-argv-prev-state-and-snapshot
  (testing "reagent-slim — a real createRoot update feeds fixed-arity gSBU/cDU the documented (this prev-argv prev-state snapshot) shape (rf2-08hx1)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [gsbu-calls (atom [])
            cdu-calls  (atom [])
            klass (r/create-class
                    {:display-name "prev-state-probe"
                     :reagent-render
                     (fn [v] [:div "v=" v])
                     ;; Seed a REAL React state before the argv-change
                     ;; update. The framework argv-equality sCU returns
                     ;; false for this setState re-render (argv is
                     ;; unchanged), so no render/gSBU/cDU fires for it —
                     ;; but React still commits the state, so the later
                     ;; changed-argv update sees it as prevState.
                     :component-did-mount
                     (fn [this]
                       (.setState ^js this #js {:probe "seeded-prev-state"}))
                     ;; The documented FIXED three-arg callback.
                     :get-snapshot-before-update
                     (fn [_this prev-argv prev-state]
                       (swap! gsbu-calls conj {:prev-argv  prev-argv
                                               :prev-state prev-state})
                       :snapshot-sentinel-42)
                     ;; The documented FIXED four-arg callback.
                     :component-did-update
                     (fn [_this prev-argv prev-state snapshot]
                       (swap! cdu-calls conj {:prev-argv  prev-argv
                                              :prev-state prev-state
                                              :snapshot   snapshot}))})
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          ;; Initial mount, committed synchronously. cDM seeds the state;
          ;; the sCU-swallowed setState pass must NOT fire the paired
          ;; update lifecycles.
          (react-dom/flushSync
            (fn [] (rdc/render root [klass "a"])))
          (is (= "v=a" (.-textContent mount-node))
              "initial mount committed v=a")
          (is (zero? (count @gsbu-calls))
              "no :get-snapshot-before-update on mount (or on the sCU-swallowed setState pass)")
          (is (zero? (count @cdu-calls))
              "no :component-did-update on mount (or on the sCU-swallowed setState pass)")

          ;; Changed argv → real reconciliation update: gSBU just before
          ;; commit, cDU just after, snapshot threaded between them.
          (react-dom/flushSync
            (fn [] (rdc/render root [klass "b"])))
          (is (= "v=b" (.-textContent mount-node))
              "the update actually occurred: committed DOM advanced to v=b")
          (is (= 1 (count @gsbu-calls)) ":get-snapshot-before-update fired exactly once")
          (is (= 1 (count @cdu-calls))  ":component-did-update fired exactly once")

          (let [{gsbu-prev-argv :prev-argv gsbu-prev-state :prev-state} (first @gsbu-calls)
                {cdu-prev-argv :prev-argv cdu-prev-state :prev-state
                 cdu-snapshot :snapshot} (first @cdu-calls)]
            ;; prev-argv: the PREVIOUS argv, translated from prevProps.
            (is (= "a" (second gsbu-prev-argv))
                "gSBU received the previous argv (arg still \"a\")")
            (is (= "a" (second cdu-prev-argv))
                "cDU received the previous argv (arg still \"a\")")
            ;; prev-state: React's own prevState object, forwarded
            ;; verbatim — carries the cDM-seeded marker.
            (is (and (some? gsbu-prev-state)
                     (= "seeded-prev-state" (.-probe ^js gsbu-prev-state)))
                "gSBU received React's real prevState verbatim (cDM-seeded marker present)")
            (is (and (some? cdu-prev-state)
                     (= "seeded-prev-state" (.-probe ^js cdu-prev-state)))
                "cDU received React's real prevState verbatim (cDM-seeded marker present)")
            ;; snapshot: gSBU's return value lands in cDU's 4th slot.
            (is (= :snapshot-sentinel-42 cdu-snapshot)
                "gSBU's return value reached cDU's snapshot slot through React"))
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))
