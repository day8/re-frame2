(ns re-frame.prod-elision-runner
  "Custom browser-test runner for the production-mode elision smoke
  builds (rf2-2zdu trace listener elision; rf2-uwg5 source-coord
  DOM annotation elision; rf2-00li UIx + Helix wrap-view elision;
  rf2-hqbeh `:on-error` always-on survival).

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
            [re-frame.source-coord-dom-elision-prod-test]
            [re-frame.adapter.uix-source-coord-dom-elision-prod-test]
            [re-frame.adapter.helix-source-coord-dom-elision-prod-test]
            [re-frame.on-error-elision-prod-test]
            [re-frame.event-emit-elision-prod-test]
            ;; Per rf2-gmrks — `:rf.error/flow-eval-exception` rides
            ;; the always-on error-emit substrate (Spec 013 §Failure
            ;; semantics rule 4 + Resolved decisions, rf2-0q0du).
            ;; This prod-elision test pins the production-survival
            ;; contract under `:advanced` + `goog.DEBUG=false`.
            [re-frame.flow-eval-exception-elision-prod-test]
            ;; Per rf2-xxd6z — runtime prod-elision pins for the
            ;; routing / http / flows trace-emit surfaces. Companion
            ;; behavioural check to the string-grep sentinel sweep in
            ;; `scripts/check-elision.cjs`; pins that no `:rf.route/*`
            ;; / `:rf.http/*` / `:rf.flow/*` events are delivered to a
            ;; registered trace listener under `:advanced` +
            ;; `goog.DEBUG=false`.
            [re-frame.routing-trace-emit-elision-prod-test]
            [re-frame.http-trace-emit-elision-prod-test]
            [re-frame.flows-trace-emit-elision-prod-test]
            ;; Per rf2-l7hlm — `:advanced` prod-elision pins replicated
            ;; for the trace-bus (ring buffer), epoch (Tool-Pair
            ;; §Time-travel surface), and frame-provider / render-key
            ;; (Spec 004 §Render-tree primitives) machinery. Sibling
            ;; to the existing rf2-2zdu (trace listener),
            ;; rf2-hqbeh (`:on-error`) and rf2-uwg5 (source-coord DOM)
            ;; pins; together they cover every dev-only sub-surface
            ;; under `:advanced` + `goog.DEBUG=false`.
            [re-frame.trace-bus-elision-prod-test]
            [re-frame.epoch-elision-prod-test]
            [re-frame.frame-provider-render-key-elision-prod-test]))

(defn ^:export init []
  (-> (env/get-test-data)
      (env/reset-test-data!))
  (st/run-all-tests))
