(ns re-frame.prod-elision-runner
  "Custom browser-test runner for the production-mode elision smoke builds
  (`:browser-test-prod-elision`).

  ## The one job

  Mirrors `re-frame.schemas-boundary-prod-runner` (Spec 010 prod-mode smoke
  runner): the default shadow-cljs `:browser-test` runner-ns
  `shadow.test.browser` uses `cljs-test-display.core/init!`, which does
  `(set! root-node-id …)` on a `(goog-define root-node-id …)`. Under
  `:advanced` Closure rejects re-assignment of a `@define`, so the build
  fails before a single test runs.

  This runner bypasses cljs-test-display and runs `cljs.test/run-tests`
  directly. The default cljs.test reporter writes the
  `Ran N tests containing M assertions.` summary to the browser console
  (via `*print-fn*` → `console.log`), which is what the Playwright
  orchestrator (scripts/run-browser-tests.cjs) watches for.

  ## What discovers the tests, and what does not

  `:ns-regexp` does, and this namespace has no say in it. The
  `:browser-test` target resolves the roster on every compile cycle
  (`shadow.build.targets.browser-test/test-resolve`) and injects EVERY
  match as a module entry:

      (assoc-in [::modules/config :test :entries]
        (-> '[shadow.test.env] (into test-namespaces) (conj runner-ns)))

  where `test-namespaces` comes from `shadow.build.test-util/find-test-
  namespaces` — the build's `:namespaces` when it declares any, and every
  classpath namespace matching `:ns-regexp` otherwise. This build declares
  no `:namespaces`, so `-elision-prod-test$` is the whole roster and a
  file is in the lane the moment it is named to match.

  So this runner deliberately requires NO test namespace. It used to
  require thirteen, under a comment saying the list was what put them in
  the test environment. It was not: the same run that loaded those
  thirteen ran 116 tests, and the thirteen carry 64 `deftest`s between
  them — the other 52 arrived by `:ns-regexp`, exactly as every one of the
  thirteen already had. A list that cannot omit anything cannot be read
  as authoritative either, and a maintainer who believed it would
  conclude that the files missing from it did not run.

  If a lane ever needs an authoritative roster, the place to put it is the
  build's `:namespaces` key, which `find-test-namespaces` honours AHEAD of
  `:ns-regexp` — there a missing entry is a real, detectable omission
  instead of a comment."
  (:require [shadow.test :as st]
            [shadow.test.env :as env]))

(defn ^:export init []
  (-> (env/get-test-data)
      (env/reset-test-data!))
  (st/run-all-tests))
