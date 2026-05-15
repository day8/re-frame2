# Making tests silent on success

## The problem

You might be burning too many AI tokens. 

Default test runners are very chatty. Every namespace gets a `Testing <ns>` banner; every `deftest` may get its own line; some test bodies emit `println` traces. A green run of a moderately-sized suite is hundreds-to-thousands of lines of output that has no signal — agents (and humans skimming logs) have to scroll through it every time.

For agent-driven workflows the cost compounds: each agent run runs tests, each test run produces ~hundreds of lines of noise, and the agent has to read the lot to confirm a green outcome. A medium codebase saturating its 1000-event ring buffer can burn 10–25K tokens per agent run on output that says nothing more than "no failures."

**Failures need detail. Passes need a one-line summary.**

## The solution

Both `clojure.test` and `cljs.test` dispatch through a `report` multi-method. Override its success-path methods to no-op; leave its failure-path methods on the default verbose implementation.

### Clojure (`clojure.test`)

```clojure
(ns my.quiet
  (:require [clojure.test :as t]))

(defmethod t/report :begin-test-ns [_])
(defmethod t/report :end-test-ns [_])
(defmethod t/report :begin-test-var [_])
(defmethod t/report :end-test-var [_])
;; :fail, :error, :pass, :summary keep default behaviour
```

Load `my.quiet` from your test runner before `(run-tests ...)`.

### ClojureScript (`cljs.test`)

Same pattern; the dispatch keys are slightly different (`cljs.test` uses a vector `[reporter-tag method]` to allow multiple reporters):

```clojure
(ns my.quiet
  (:require [cljs.test :as t]))

(defmethod t/report [::t/default :begin-test-ns] [_])
(defmethod t/report [::t/default :end-test-ns] [_])
(defmethod t/report [::t/default :begin-test-var] [_])
(defmethod t/report [::t/default :end-test-var] [_])
```

Wire via shadow-cljs `:node-test` `:test-runner-ns` (or equivalent in your tool).

## The result

A green run collapses to its summary line:

```
Ran 504 tests containing 2588 assertions.
0 failures, 0 errors.
```

Failures still emit the full `Testing <ns>` banner + `FAIL in (...)` block + `expected:`/`actual:` lines, byte-for-byte unchanged. The agent reading the output sees three lines on a green pass; on a red run it sees exactly what it needs.

For scale: re-frame2's corpus has ~5,000 tests across 17 runners. Before quieting, a full green run buried the agent in several thousand lines of pass-noise per invocation. After quieting, a green pass per runner is three lines.

## Regression test

Pin the contract — capture a known-green suite's stdout and assert a line budget:

```clojure
(deftest green-runs-are-quiet
  (let [out (with-out-str (run-tests 'my.passing-suite))]
    (is (<= (count (filter seq (str/split-lines out))) 5))))
```

This prevents future `println` creep from re-polluting the output.

## Like JVM flags

Treat verbose output the way JVM flags treat verbose GC: it's something you opt into when you're debugging, not the default for every run.

## Opting Into Verbose Browser Diagnostics

Browser / Playwright harnesses follow the same rule. A green run prints
only a compact summary by default; browser console lines, navigation
details, and page errors are buffered and emitted on failure.

For local debugging, opt into the buffered green-path diagnostics:

```bash
RF2_VERBOSE_TESTS=1 npm run test:browser
RF2_VERBOSE_TESTS=1 npm run test:examples
```

## Opting Into Verbose JS Gate Diagnostics

Bundle / elision gate scripts follow the same contract. A green run
prints one compact `PASS <gate>:` line by default. Sentinel tables,
threshold details, bundle-grep counts, and remediation text are
buffered and emitted on failure.

For local investigation, opt into the green-path tables:

```bash
RF2_VERBOSE_TESTS=1 npm run test:elision
RF2_VERBOSE_TESTS=1 npm run test:bundle-isolation
RF2_VERBOSE_TESTS=1 npm run test:bundle-comparison
RF2_VERBOSE_TESTS=1 npm run test:schemas-bundle
RF2_VERBOSE_TESTS=1 npm run test:perf-bundle
```
