(ns re-frame.story.bench-bundle-size-pointer-test
  "`tools/story/bench/bundle-size.cjs` closes its report by pointing the
  reader at the Storybook 9 comparison. The pointer must name a TRACKED
  document: the `ai/` tree is local-only, so a pointer there dangles in
  every checkout but the one that wrote it.

  The script is read as text, so this runs under `clojure -M:test` with
  no Node and no build."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- artefact-root
  "Story's artefact root — the directory holding `deps.edn` — derived from
  the classpath location of a known Story source file, so the test does
  not depend on the JVM working directory."
  []
  (let [res (io/resource "re_frame/story/config.cljc")]
    (assert res "re_frame/story/config.cljc must be on the classpath")
    ;; <root>/src/re_frame/story/config.cljc → up 4 → <root>
    (->> (iterate #(.getParentFile ^java.io.File %) (io/file res))
         (drop 4)
         first)))

(defn- repo-root
  "The repository root: Story's artefact root is `<repo>/tools/story`."
  []
  (-> (artefact-root) .getParentFile .getParentFile))

(deftest bench-points-at-a-tracked-comparison
  (let [script        (slurp (io/file (artefact-root) "bench" "bundle-size.cjs"))
        [_ path head] (re-find #"See (\S+\.md)(?: §([^'\n]+?))? for " script)]
    (testing "control: the script prints a `See <doc>.md … for` pointer"
      (is (some? path)))
    (testing "the pointer names no path under the local-only ai/ tree"
      (is (not (str/starts-with? (str path) "ai/"))))
    (testing "the named document exists at that repo-relative path"
      (is (.isFile (io/file (repo-root) (str path)))))
    (testing "the named section is a heading in that document"
      (is (some? head) "control: the pointer names a section")
      (let [doc (io/file (repo-root) (str path))]
        (is (and (.isFile doc)
                 (some #(and (str/starts-with? % "#") (str/includes? % (str head)))
                       (str/split-lines (slurp doc)))))))))
