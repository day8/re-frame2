(ns re-frame.story.meta-fixtures-test
  "JVM meta-check against the map-form `use-fixtures` silent-skip trap in
  dual-target `.cljc` tests.

  `clojure.test` treats `(use-fixtures :each {:before f :after g})`'s map as
  a fixture FUNCTION; invoking a map looks its argument up instead of calling
  the test thunk, so every `deftest` in the namespace is silently skipped on
  the JVM while `cljs.test` (which honours the map form) runs them. The map
  form is legitimate in pure-`.cljs` tests, so the scan covers `.cljc` files
  only; the cross-platform idiom is the fn form,
  `(use-fixtures :each (fn [t] (reset-all!) (t)))`.

  The scan reads source off disk, so it is JVM-only: on CLJS the namespace is
  empty, and no CLJS build selects it (its name ends `-test`, not
  `cljs-test`)."
  #?(:clj (:require [clojure.test :refer [deftest is testing]]
                    [clojure.java.io :as io]
                    [clojure.string :as str])))

#?(:clj
   (do
     (def ^:private this-file
       "Skipped by the scan: its own docs and message quote the forbidden form."
       "meta_fixtures_test.cljc")

     (defn- test-root
       "`tools/story/test/`, resolved from this file's classpath resource so the
       walk does not depend on the working directory."
       []
       (let [marker (io/resource "re_frame/story/meta_fixtures_test.cljc")]
         (if (and marker (= "file" (.getProtocol marker)))
           ;; .../test/re_frame/story/meta_fixtures_test.cljc → up to .../test
           (-> (io/file (.toURI marker)) .getParentFile .getParentFile .getParentFile)
           (io/file "test"))))

     (defn- cljc-test-files []
       (let [root (test-root)]
         (when (.isDirectory root)
           (->> (file-seq root)
                (filter #(.isFile ^java.io.File %))
                (filter (fn [^java.io.File f]
                          (let [n (.getName f)]
                            (and (str/ends-with? n ".cljc")
                                 (not= n this-file)))))))))

     (def ^:private map-form-use-fixtures
       "`(use-fixtures :each|:once {`, optionally alias-qualified; the leading
       `(` anchors it to a call site rather than a docstring mention."
       #"\(\s*(?:[\w.-]+/)?use-fixtures\s+:(?:each|once)\s+\{")

     (defn- offences [^java.io.File f]
       (when-let [m (re-find map-form-use-fixtures (slurp f))]
         {:file (.getPath f) :match m}))

     (deftest no-map-form-use-fixtures-in-cljc-tests
       (testing "no tracked .cljc test may use the map form
(use-fixtures :each {:before ...}) — it SILENTLY SKIPS every deftest in
the ns on the JVM half of `clojure -M:test`"
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
                    "where cljs.test honours it.")))))))
