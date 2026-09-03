(ns reagent2.dom.error-boundary-dom-cljs-test
  "rf2-6r9j.31 — the MOUNTED React error-boundary proof for
  `reagent2.core/create-class`'s `:component-did-catch` cap key, under a
  React 19 `createRoot`.

  WHAT IT PROVES. A descendant that throws during RENDER, and a
  descendant that throws during COMMIT (`:component-did-mount`), are
  both routed by React itself to the NEAREST enclosing slim boundary:

    - the throwing descendant's path actually RAN (a render / mount
      counter advanced), so a subtree React never reached cannot read
      green;
    - that boundary's `:component-did-catch` fired, carrying the thrown
      error;
    - the default `getDerivedStateFromError` marker reached the public
      Reagent state atom and the boundary's FALLBACK committed to the
      DOM;
    - an OUTER boundary wrapping it did NOT fire.

  WHY A DOM FILE, AND WHY THE OUTER-ONLY ARM. The sibling unit tests in
  `reagent2.impl.component-cljs-test` invoke `componentDidCatch` on the
  prototype directly — full control over the payload, but no reconciler,
  so nothing there can observe React's own propagation. Until rf2-6r9j.31
  this file did not exist, and the nested-isolation claim was carried by
  a unit test that constructed an outer boundary, never mounted it, and
  then asserted its counter was still zero — an assertion nothing could
  have made fail. `outer-boundary-catches-when-inner-is-absent` below
  exists so that cannot recur: it mounts the SAME throwing descendant
  under the outer boundary ALONE and proves that counter does fire, so
  the zero read by the nested arm is a measurement rather than a
  tautology.

  TEST-ONLY. ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test`
  discovers it for the real-DOM assertion; the `:node-test` runner also
  loads it (matches `cljs-test$`), where the body gates on `(browser?)`
  and no-ops cleanly (no DOM).

  CONSOLE. React 19 reports a boundary-caught error through the root's
  `onCaughtError` option, which defaults to `console.error`. Each root
  below supplies its own, so the expected report is captured as evidence
  instead of printed as noise. The browser runner treats console output
  as diagnostic, but an error that reached the PAGE uncaught would be
  fatal to the lane — every error raised here is caught by a boundary
  under test, and the captured report is asserted non-empty, which is a
  second, independent witness that React (not the test) did the routing."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.core :as r]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]))

(def ^:private render-boom "child-render-boom")
(def ^:private commit-boom "child-commit-boom")

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- root-opts
  "React 19 root options that divert the boundary-caught error report
  from `console.error` into `reports`."
  [reports]
  #js {:onCaughtError (fn [error _info]
                        (swap! reports conj (.-message ^js error)))})

(defn- boundary-class
  "A slim Form-3 error boundary named `label`.

  Renders `(child-fn)` until a descendant throws; `:component-did-catch`
  appends the error message to `fired`; after React's default
  `getDerivedStateFromError` patch is bridged into the public Reagent
  state atom (`:cljsHasError`), it renders `\"<label>:fallback\"` — the
  documented slim fallback contract (IMPL-SPEC §6.5, rf2-ygknv)."
  [label fired child-fn]
  (r/create-class
    {:display-name        (str label "-boundary")
     :component-did-catch (fn [_this ^js error _info]
                            (swap! fired conj (.-message error)))
     :reagent-render
     (fn []
       (let [this (r/current-component)]
         (if (:cljsHasError @(r/state-atom this))
           [:div {:class (str label "-fallback")} (str label ":fallback")]
           (child-fn))))}))

(defn- render-thrower
  "A Form-1 slim component whose RENDER throws, counting its own runs."
  [runs]
  (fn []
    (swap! runs inc)
    (throw (js/Error. render-boom))))

(defn- commit-thrower
  "A slim Form-3 component that renders normally and then throws from
  `:component-did-mount` — React's COMMIT phase."
  [mounts]
  (r/create-class
    {:display-name        "commit-thrower"
     :component-did-mount (fn [_this]
                            (swap! mounts inc)
                            (throw (js/Error. commit-boom)))
     :reagent-render      (fn [] [:span "child"])}))

(deftest nested-boundaries-inner-catches-outer-does-not
  (testing "a descendant RENDER throw is caught by the NEAREST slim
            boundary; the enclosing outer boundary does not fire, and
            the inner boundary's fallback commits (rf2-6r9j.31)"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises the assertion")
      (let [outer-fired   (atom [])
            inner-fired   (atom [])
            child-runs    (atom 0)
            reports       (atom [])
            thrower       (render-thrower child-runs)
            inner         (boundary-class "inner" inner-fired (fn [] [thrower]))
            outer         (boundary-class "outer" outer-fired (fn [] [inner]))
            mount-node    (make-mount-node!)
            root          (rdc/create-root mount-node (root-opts reports))]
        (try
          (react-dom/flushSync (fn [] (rdc/render root [outer])))
          ;; Non-vacuity: React really rendered the throwing descendant.
          ;; (React 19 may re-run a failed render once to recover a better
          ;; stack, so this is a "ran at least once" check, not a count.)
          (is (pos? @child-runs)
              "the throwing descendant's render actually ran")
          (is (pos? (count @inner-fired))
              "the INNER boundary's :component-did-catch fired")
          (is (= #{render-boom} (set @inner-fired))
              "the inner boundary received the error the descendant threw")
          (is (= [] @outer-fired)
              "the OUTER boundary did not fire (React stopped at the nearest boundary)")
          (is (= "inner:fallback" (.-textContent mount-node))
              "the inner boundary's fallback committed to the real DOM")
          (is (pos? (count @reports))
              "React reported the caught error through the root's onCaughtError")
          (finally
            (rdc/unmount root)))))))

(deftest outer-boundary-catches-when-inner-is-absent
  (testing "the SAME throwing descendant, mounted with no inner
            boundary, IS caught by the outer boundary — so the zero
            read above is a measurement, not a tautology (rf2-6r9j.31)"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises the assertion")
      (let [outer-fired (atom [])
            child-runs  (atom 0)
            reports     (atom [])
            thrower     (render-thrower child-runs)
            outer       (boundary-class "outer" outer-fired (fn [] [thrower]))
            mount-node  (make-mount-node!)
            root        (rdc/create-root mount-node (root-opts reports))]
        (try
          (react-dom/flushSync (fn [] (rdc/render root [outer])))
          (is (pos? @child-runs)
              "the throwing descendant's render actually ran")
          (is (pos? (count @outer-fired))
              "the outer boundary's :component-did-catch fired when it WAS the nearest one")
          (is (= #{render-boom} (set @outer-fired))
              "the outer boundary received the error the descendant threw")
          (is (= "outer:fallback" (.-textContent mount-node))
              "the outer boundary's fallback committed to the real DOM")
          (is (pos? (count @reports))
              "React reported the caught error through the root's onCaughtError")
          (finally
            (rdc/unmount root)))))))

(deftest commit-phase-child-did-mount-throw-reaches-boundary
  (testing "a descendant that throws from :component-did-mount — React's
            COMMIT phase — reaches the enclosing slim boundary, which
            commits its fallback (rf2-6r9j.31)"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises the assertion")
      (let [fired      (atom [])
            mounts     (atom 0)
            reports    (atom [])
            child      (commit-thrower mounts)
            boundary   (boundary-class "commit" fired (fn [] [child]))
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node (root-opts reports))]
        (try
          (react-dom/flushSync (fn [] (rdc/render root [boundary])))
          ;; Non-vacuity: the throwing LIFECYCLE actually ran. Without
          ;; this the test could pass on a tree React never committed.
          (is (pos? @mounts)
              "the descendant's :component-did-mount actually ran")
          (is (pos? (count @fired))
              "the boundary's :component-did-catch fired for the commit-phase error")
          (is (= #{commit-boom} (set @fired))
              "the boundary received the error the commit lifecycle threw")
          (is (= "commit:fallback" (.-textContent mount-node))
              "the boundary's fallback committed to the real DOM")
          (is (pos? (count @reports))
              "React reported the caught error through the root's onCaughtError")
          (finally
            (rdc/unmount root)))))))
