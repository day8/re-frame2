(ns re-frame.warn-once-fixture-isolation-cljs-test
  "`make-reset-runtime-fixture` clears the per-adapter
  `warned-non-dom-roots` warn-once caches.

  `re-frame.views` and `re-frame.adapter.uix` each hold a per-process
  `defonce` set so a non-DOM-root warning fires once per id, which is right
  for users and wrong for a test run: a test asserting the warning would
  pass on an earlier test's emission. The fixture runs the chained
  `:adapter/clear-warn-once-caches!` hook, to which each contributes a
  clear-step at ns-load. This pins it for the Reagent path, the one the
  node runner reaches without a browser: the same warning, emitted on
  either side of a fixture boundary, lands both times."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; ---- helper: capture console.warn calls (mirrors source-coord-warn-once) -

(defn- with-captured-console-warn
  "Replace js/console.warn with a recording shim around `thunk`. Returns
  the vector of joined-message strings observed. Restores the original
  on the way out, even if thunk throws."
  [thunk]
  (let [calls    (atom [])
        original (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args]
              (swap! calls conj (apply str args))))
      (thunk)
      @calls
      (finally
        (set! (.-warn js/console) original)))))

(defn- run-fixture!
  "Invoke `make-reset-runtime-fixture` as a single call against a thunk.
  Matches the production fixture path (registrar snapshot/restore,
  trace listener clear, adapter dispose/re-install, AND the
  warn-once cache clear) so the assertion below tests the production
  surface — not a private fn."
  [thunk]
  (let [fixture (rf.test-support/make-reset-runtime-fixture
                  {:adapter rf.adapter.reagent/adapter})]
    (fixture thunk)))

;; ---- the fixture clears the warn-once cache between phases ---------------

(deftest warn-once-cache-resets-across-make-reset-runtime-fixture
  (testing "the SAME `:rf.warning/non-dom-root` (a Fragment-rooted view,
            rendered) lands once before and once after
            `make-reset-runtime-fixture` runs"
    ;; The cache is keyed by id, so only reusing the id proves the clear.
    (let [shared-id  :rf.warn-once-fixture/shared
          phase-1-ws (with-captured-console-warn
                       (fn []
                         (rf/reg-view* shared-id
                                       (fn [] [:<> [:p "phase-1"]]))
                         (let [render (rf/view shared-id)]
                           ;; A few re-renders prove that within a phase
                           ;; the warn-once contract still holds.
                           (dotimes [_ 3] (render)))))]

      (is (and (= 1 (count phase-1-ws)) (str/includes? (first phase-1-ws) (name shared-id)))
          (str "phase-1 sanity: warn-once fires exactly once WITHIN a phase, "
               "naming the shared id; got " (pr-str phase-1-ws)))
      (let [phase-2-ws (with-captured-console-warn
                         (fn []
                           (run-fixture!
                             (fn []
                               (rf/reg-view* shared-id
                                             (fn [] [:<> [:p "phase-2"]]))
                               (let [render (rf/view shared-id)]
                                 (dotimes [_ 3] (render)))))))]

        (is (and (= 1 (count phase-2-ws)) (str/includes? (first phase-2-ws) (name shared-id)))
            (str "phase-2 must re-emit the warning for the same id "
                 "AFTER `make-reset-runtime-fixture` clears the warn-once "
                 "cache. Got " (count phase-2-ws)
                 ": " (pr-str phase-2-ws)
                 ". If this is zero, "
                 "the per-adapter `warned-non-dom-roots` defonce is "
                 "not being cleared by make-reset-runtime-fixture, "
                 "and sibling tests can silently swallow each other's "
                 "warnings."))))))
