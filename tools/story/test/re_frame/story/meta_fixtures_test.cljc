(ns re-frame.story.meta-fixtures-test
  "JVM meta-check guarding against the map-form `use-fixtures` silent-skip
  trap in dual-target `.cljc` tests (rf2-k2g3i; surfaced in PR #2420 review,
  refs rf2-qkltn + rf2-5x1wt.25).

  THE TRAP. The JVM `clojure.test` runner does NOT support the map-form
  fixture

      (use-fixtures :each {:before f :after g})

  — it treats the map as a fixture FUNCTION, calls it, and the map (not
  being a fn) never invokes the wrapped test thunk. The upshot: every
  `deftest` in that namespace is SILENTLY SKIPPED on the JVM — no error,
  zero assertions run. The `cljs.test` runtime DOES honour the map form,
  so a `.cljc` test using it passes on node but is a no-op on the JVM half
  of `clojure -M:test`. That asymmetric silent skip is exactly the
  rf2-gc7la failure mode.

  In a pure-`.cljs` test the map form is LEGITIMATE (cljs.test supports it)
  and common — so this guard scopes itself to `.cljc` files ONLY. The
  cross-platform-safe idiom for a `.cljc` test is the fn form:

      (use-fixtures :each (fn [t] (reset-all!) (t)))
      ;; or a named fn that ends by calling its `t` argument

  This is a PREVENTATIVE guard: as of 2026-05-30 zero tracked `.cljc`
  tests use the map form (verified via `git grep`). It exists to catch a
  future author who copies the map idiom from a `.cljs` sibling and
  silently loses JVM coverage.

  This is a `.cljc` file so the guard travels with the artefact's
  dual-target test set, but the scan itself is JVM-only — it reads source
  bytes off disk, which CLJS cannot do — hence the `#?(:clj ...)` reader
  conditional around the scanning machinery. On CLJS the namespace is an
  empty no-op."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.string :as str])))

#?(:clj
   (do
     (def ^:private this-file
       "This meta-check's own filename. The guard's docstring + failure
       message necessarily quote the forbidden map form verbatim, so the
       scan must skip itself or it would flag its own illustrative prose."
       "meta_fixtures_test.cljc")

     (defn- cljc-test-files
       "Walk `test/` and return every `.cljc` file as a `java.io.File`,
       excluding this meta-check (which quotes the forbidden form in its
       own docs/message). The classpath element `test` resolves to the same
       directory whether tests run via `clojure -M:test` from `tools/story`
       or under the top-level build, mirroring `story-substrate-isolation-test`."
       []
       (let [root (io/file "test")]
         (when (.isDirectory root)
           (->> (file-seq root)
                (filter #(.isFile ^java.io.File %))
                (filter (fn [^java.io.File f]
                          (let [n (.getName f)]
                            (and (str/ends-with? n ".cljc")
                                 (not= n this-file)))))))))

     (def ^:private map-form-use-fixtures
       "Matches `use-fixtures :each|:once {` — the map-form fixture
       declaration that silently skips on the JVM. Allows arbitrary
       whitespace/newlines between tokens; the leading `(` (with optional
       namespace alias, e.g. `t/use-fixtures`) anchors it to the call
       site, not a docstring mention of the symbol."
       #"\(\s*(?:[\w.-]+/)?use-fixtures\s+:(?:each|once)\s+\{")

     (defn- offences
       "Return `{:file path :match line}` for each `.cljc` file whose body
       contains a map-form `use-fixtures` call."
       [^java.io.File f]
       (let [body (slurp f)]
         (when-let [m (re-find map-form-use-fixtures body)]
           {:file (.getPath f) :match m})))

     (deftest no-map-form-use-fixtures-in-cljc-tests
       (testing "no tracked .cljc test may use the map form
(use-fixtures :each {:before ...}) — it SILENTLY SKIPS every deftest in
the ns on the JVM half of `clojure -M:test` (rf2-k2g3i / rf2-gc7la)"
         (let [files     (cljc-test-files)
               offending (keep offences files)]
           (is (seq files)
               "expected to find .cljc test files under tools/story/test/")
           (is (empty? offending)
               (str "Map-form `use-fixtures` found in .cljc test files — these "
                    "SILENTLY SKIP every deftest in the namespace on the JVM "
                    "(clojure.test treats the map as a fixture fn that never "
                    "invokes the test thunk):\n"
                    (str/join "\n" (map (fn [{:keys [file match]}]
                                          (str "  " file
                                               "  (matched " (pr-str match) ")"))
                                        offending))
                    "\n\nUse the cross-platform fn form instead:\n"
                    "  (use-fixtures :each (fn [t] (reset-all!) (t)))\n"
                    "The map form is legitimate ONLY in pure-.cljs tests, "
                    "where cljs.test honours it."))))))

   :cljs
   ;; CLJS half is a no-op: the guard reads source bytes off disk, which
   ;; the cljs.test runtime cannot do. The scan runs on the JVM only.
   (deftest map-form-guard-is-jvm-only
     (is true "map-form use-fixtures meta-check runs on the JVM only")))
