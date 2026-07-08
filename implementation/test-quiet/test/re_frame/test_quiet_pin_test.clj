(ns re-frame.test-quiet-pin-test
  "Stdout-line-count pin for the silent-on-success contract (rf2-try1x).

  Drives a tiny known-green sub-suite (a single `is (= 1 1)` deftest
  in a sibling namespace) through `clojure.test/run-tests` while
  capturing stdout, then asserts the captured stdout has AT MOST 5
  non-blank lines.

  The canonical green shape is 3 lines:

      Ran 1 tests containing 1 assertions.
      0 failures, 0 errors.

  The 5-line ceiling tolerates a single extra heading or marker
  without making this pin flaky on slight reporter tweaks.  A future
  `(println ...)` creep — in test bodies, fixture setups, the
  reporter itself, or any downstream code path the test traverses —
  immediately fails this pin with the captured stdout in the
  assertion message so triage is one-glance.

  Runs ONLY under test-quiet's OWN `:test` alias — NOT once per
  dependent artefact.  A `:local/root` dep exposes only the
  dependency's top-level `:paths` (here `[src]`); these pin namespaces
  live under `test/` (the `:test` alias's `:extra-paths`), which a
  `:local/root` never transitively exposes, so a dependent artefact's
  `:test` classpath carries `.../test-quiet/src` but never
  `.../test-quiet/test` — cognitect's test-runner cannot discover this
  ns from another artefact's suite.  The alias is wired into the JVM
  gate by `scripts/test-jvm-implementation.sh` and the `jvm-test-quiet`
  CI job (`.github/workflows/test.yml`, added by rf2-jg1ag2)."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [re-frame.test-quiet]
            [re-frame.test-quiet-pin-passing-test]))

(defn- with-captured-stdout
  "Run `f` with `*out*` rebound to a StringWriter.  Returns the
  captured text.  Test-reporter writes go through
  `clojure.test/*test-out*` which is captured at ns-load time;
  rebinding `*test-out*` for the duration is what carries the
  reporter's writes into our buffer."
  [f]
  (let [sw (java.io.StringWriter.)
        pw (java.io.PrintWriter. sw)]
    (binding [*out*                   pw
              clojure.test/*test-out* pw]
      (f)
      (.flush pw))
    (.toString sw)))

(deftest stdout-line-count-pin
  ;; The pin: a known-green sub-suite (a single `is (= 1 1)` deftest
  ;; in `re-frame.test-quiet-pin-passing-test`) emits AT MOST 5
  ;; non-blank lines on stdout when run through `clojure.test/run-tests`.
  ;; The canonical shape is 3 lines; the 5-line ceiling tolerates a
  ;; single extra heading or marker without making this pin flaky.
  (testing "known-green sub-suite emits the canonical silent-on-success shape"
    (let [captured  (with-captured-stdout
                      #(run-tests 're-frame.test-quiet-pin-passing-test))
          non-blank (->> (str/split-lines captured)
                         (remove str/blank?))]
      (is (<= (count non-blank) 5)
          (str "Green sub-suite stdout has " (count non-blank)
               " non-blank lines; the silent-on-success contract"
               " caps it at 5 (canonical shape is 3). New println"
               " creep?  Captured stdout follows:\n"
               (str/join "\n" non-blank))))))
