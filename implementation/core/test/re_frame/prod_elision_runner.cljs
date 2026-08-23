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
  instead of a comment.

  ## Why `{:dev/always true}` is load-bearing, in EVERY mode

  `env/get-test-data` is a MACRO. It expands, at the moment this namespace
  is compiled, into a literal map naming every test var the compiler knows
  about — so the roster is frozen into this file's compiled JS. shadow-cljs
  caches compiled namespaces per-namespace, and the cache key
  (`shadow.build.compiler/make-cache-key-map`) is built from
  `:immediate-deps`, which comes from the ns form's own requires. The test
  namespaces are attached to this runner as `:extra-requires`
  (`shadow.build.test-util/inject-extra-requires`), and those are used ONLY
  to order compilation — they are not in `:immediate-deps`. So the roster
  can change completely without invalidating this namespace's cache entry.

  A namespace LEAVING the roster is the destructive direction: the stale
  cached expansion still dereferences vars whose namespaces are no longer in
  the bundle, and under `:advanced` that lands as an uncaught
  `TypeError: Cannot read properties of undefined` inside `init` — before a
  single test runs, so the lane aborts with no `cljs.test` summary at all.
  That is rf2-2ohy: the Freehand / re-frame.ui removal took fourteen
  `*-elision-prod-test` namespaces out of this lane, and every nightly from
  2026-08-16 on restored a warm `.shadow-cljs` cache and served the
  pre-removal runner.

  `{:dev/always true}` is what stops it. Despite the name,
  `shadow.build.compiler/is-cache-blocked?` reads that key with no mode
  gate, so it blocks caching in `release` exactly as in `:dev`. The stock
  runners `shadow.test.browser` and `shadow.test.node` both carry it for
  this reason, as does this repo's own `re-frame.test-quiet.shadow-node`;
  this runner and `re-frame.schemas-boundary-prod-runner` were written from
  the stock runner's body without its ns metadata. Any namespace that
  expands `shadow.test.env/get-test-data` needs this."
  {:dev/always true}
  (:require [shadow.test :as st]
            [shadow.test.env :as env]))

(defn ^:export init []
  (-> (env/get-test-data)
      (env/reset-test-data!))
  (st/run-all-tests))
