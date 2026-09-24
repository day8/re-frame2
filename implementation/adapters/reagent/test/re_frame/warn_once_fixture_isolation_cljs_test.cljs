(ns re-frame.warn-once-fixture-isolation-cljs-test
  "`make-reset-runtime-fixture` clears the per-adapter
  `warned-non-dom-roots` warn-once caches.

  Background. Two CLJS namespaces hold a `defonce ^:private
  warned-non-dom-roots` set that makes non-DOM-root warnings fire
  exactly once per id across the JS process:

    - re-frame.views               (Reagent path)
    - re-frame.adapter.uix         (UIx path)

  The per-process `defonce` is the right shape for the USER-facing
  warn-once UX (a long-running app must not spam the console on
  re-render). Left uncleared under cljs.test it would make sibling
  tests silently interfere: a test that asserts 'warning fires for id
  :foo' would pass green if an earlier test in the run had already
  emitted the same warning, because the cache would silence the second
  emission.

  Clearing. `make-reset-runtime-fixture` invokes the chained
  `:adapter/clear-warn-once-caches!` late-bind hook; each of the
  namespaces above contributes a clear-step at ns-load. This
  test pins that contract for the Reagent path (re-frame.views): emit
  the same warning twice across a fixture boundary and assert BOTH
  emissions land. Without the clear, the second emission would be
  silenced by the surviving cache and the test would fail; with it, the
  cache is reset between the two emissions and both fire.

  The test exercises the Reagent path because that is the one the
  node-test runner covers without a real browser; the UIx
  path shares the identical clear-step shape and is covered by the
  same `make-reset-runtime-fixture` step (its adapter ns publishes the
  chain step at load time, exercised by the parity tests).

  In production the warn-once `defonce` is per-process for users; the
  clearing happens at test time only."
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
  (testing "Emit the SAME `:rf.warning/non-dom-root` (via reg-view of a
            Fragment-rooted view, then render it) twice — once before
            and once after `make-reset-runtime-fixture` runs. The
            fixture clears the `warned-non-dom-roots` cache, so both
            emissions land. WITHOUT that clear the second emission would
            be silenced by the cache surviving the fixture and only ONE
            warning would land across the two phases — the
            sibling-test-swallow shape."

    ;; Use a stable id across both phases — the warn-once cache is keyed
    ;; by id, so reusing the same id is what proves the cache was
    ;; cleared. (A unique id would pass trivially because the cache
    ;; would never have seen it.)
    (let [shared-id  :rf.warn-once-fixture/shared
          phase-1-ws (with-captured-console-warn
                       (fn []
                         (rf/reg-view* shared-id
                                       (fn [] [:<> [:p "phase-1"]]))
                         (let [render (rf/view shared-id)]
                           ;; A few re-renders prove that within a phase
                           ;; the warn-once contract still holds.
                           (dotimes [_ 3] (render)))))]

      (is (= 1 (count phase-1-ws))
          (str "phase-1 sanity: warn-once still fires exactly once "
               "WITHIN a single test/phase; got " (count phase-1-ws)
               ": " (pr-str phase-1-ws)))
      (is (str/includes? (first phase-1-ws) (name shared-id))
          "phase-1 warning names the shared id")

      ;; ── Cross the fixture boundary. This is the production
      ;;    `make-reset-runtime-fixture` thunk — it snapshots/restores the
      ;;    registrar AND clears the warn-once caches.
      (let [phase-2-ws (with-captured-console-warn
                         (fn []
                           (run-fixture!
                             (fn []
                               (rf/reg-view* shared-id
                                             (fn [] [:<> [:p "phase-2"]]))
                               (let [render (rf/view shared-id)]
                                 (dotimes [_ 3] (render)))))))]

        ;; The load-bearing assertion. Without the clear, this would be
        ;; zero (the cache from phase-1 would silence the phase-2
        ;; emission). The fixture clears the cache between phases, so
        ;; the SAME id re-warns.
        (is (= 1 (count phase-2-ws))
            (str "phase-2 must re-emit the warning for the same id "
                 "AFTER `make-reset-runtime-fixture` clears the warn-once "
                 "cache. Got " (count phase-2-ws)
                 ": " (pr-str phase-2-ws)
                 ". If this is zero, "
                 "the per-adapter `warned-non-dom-roots` defonce is "
                 "not being cleared by make-reset-runtime-fixture, "
                 "and sibling tests can silently swallow each other's "
                 "warnings."))
        (is (str/includes? (first phase-2-ws) (name shared-id))
            "phase-2 warning still names the shared id")))))

;; ---- positive control: clear-warned-non-dom-roots! works directly ---------

(deftest clear-warned-non-dom-roots-resets-cache-directly
  (testing "Calling `re-frame.views/clear-warned-non-dom-roots!` directly
            also resets the cache — this is the public seam
            `make-reset-runtime-fixture` consumes through the chained hook.
            Test it independently so a future refactor that drops the
            chain wiring but keeps the fn name is caught here."
    (let [target-id :rf.warn-once-fixture/direct
          ws-1 (with-captured-console-warn
                 (fn []
                   (rf/reg-view* target-id
                                 (fn [] [:<> [:p "first"]]))
                   ((rf/view target-id))))]
      (is (= 1 (count ws-1)) "first emission fires")
      (re-frame.views/clear-warned-non-dom-roots!)
      (let [ws-2 (with-captured-console-warn
                   (fn []
                     ((rf/view target-id))))]
        (is (= 1 (count ws-2))
            (str "after `clear-warned-non-dom-roots!` the same id "
                 "re-emits. Got " (count ws-2) ": " (pr-str ws-2)))))))
