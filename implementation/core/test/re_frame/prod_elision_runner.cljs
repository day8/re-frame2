(ns re-frame.prod-elision-runner
  "Custom browser-test runner for the production-mode elision smoke
  builds (rf2-2zdu trace listener elision; rf2-uwg5 source-coord
  DOM annotation elision).

  Mirrors `re-frame.schemas-boundary-prod-runner` (Spec 010 prod-mode
  smoke runner): the default shadow-cljs `:browser-test` runner-ns
  `shadow.test.browser` uses `cljs-test-display.core/init!`, which does
  `(set! root-node-id …)` on a `(goog-define root-node-id …)`. Under
  `:advanced` Closure rejects re-assignment of a `@define`.

  This runner bypasses cljs-test-display and runs `cljs.test/run-tests`
  directly. The default cljs.test reporter writes the
  `Ran N tests containing M assertions.` summary to the browser console
  (via `*print-fn*` → `console.log`), which is what the Playwright
  orchestrator (scripts/run-browser-tests.cjs) watches for."
  (:require [cljs.test :as test]
            [shadow.test :as st]
            [shadow.test.env :as env]
            ;; Pull each prod-mode smoke namespace into the test
            ;; environment so shadow.test/run-all-tests discovers it.
            [re-frame.trace-listener-elision-prod-test]
            [re-frame.source-coord-dom-elision-prod-test]))

(defn ^:export init []
  (-> (env/get-test-data)
      (env/reset-test-data!))
  (st/run-all-tests))
