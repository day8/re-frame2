(ns re-frame.schemas-boundary-prod-runner
  "Custom browser-test runner for the production-mode schemas boundary
  smoke (Spec 010 §Production builds, rf2-r2uh / rf2-84e9).

  The default shadow-cljs `:browser-test` runner-ns `shadow.test.browser`
  uses `cljs-test-display.core/init!`, which does `(set! root-node-id …)`
  on a `(goog-define root-node-id …)`. Under `:advanced` Closure rejects
  the re-assignment of a `@define` — the build fails with
  `@define cljs_test_display.core.root_node_id has already been set`.

  This runner bypasses cljs-test-display and runs `cljs.test/run-tests`
  directly. The default cljs.test reporter writes the
  `Ran N tests containing M assertions.` summary to the browser console
  (via `*print-fn*` → `console.log`), which is exactly what the
  Playwright runner (scripts/run-browser-tests.cjs) watches for. No DOM
  reporter is needed because the orchestrator polls the console stream."
  (:require [cljs.test :as test]
            [shadow.test :as st]
            [shadow.test.env :as env]
            ;; Pull the prod-mode boundary smoke into the test
            ;; environment so shadow.test/run-all-tests discovers it.
            [re-frame.schemas-boundary-prod-test]))

(defn ^:export init []
  (-> (env/get-test-data)
      (env/reset-test-data!))
  ;; shadow.test/run-all-tests delegates to cljs.test/run-tests with the
  ;; default reporter — which prints the summary to *print-fn*, mapped
  ;; under shadow-cljs's :browser-test target to console.log. The
  ;; Playwright orchestrator scans the captured console stream for the
  ;; "Ran N tests" line.
  (st/run-all-tests))
