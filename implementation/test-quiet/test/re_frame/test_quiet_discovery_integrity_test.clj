(ns re-frame.test-quiet-discovery-integrity-test
  "The rule `re-frame.test-quiet.runner/discovery-defects` states. The wiring
  (`-main` refuses the run and names the file) is pinned across a process
  boundary in `re-frame.test-quiet-runner-contract-test`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [re-frame.test-quiet.runner :as rf.test-quiet.runner]))

(defn- with-tree
  "Make a temp directory, write `files` (relative path -> source) into it,
  hand it to `f`, then delete it."
  [files f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                       "tq-discovery"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[path source] files]
        (let [file (io/file dir path)]
          (.mkdirs (.getParentFile file))
          (spit file source)))
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete ^java.io.File file))))))

(defn- defect-paths [defects]
  (mapv (comp #(last (str/split % #"/")) first) defects))

(defn- complaint-for [defects stem]
  (some (fn [[path complaint]]
          (when (str/ends-with? path stem) complaint))
        defects))

(deftest discovery-dirs-are-cognitects-own
  ;; No `-d` is how every default lane is guarded.
  (is (= #{"test"} (rf.test-quiet.runner/discovery-dirs []))))

(def ^:private duplicate-body
  (str "  (:require [clojure.test :refer [deftest is]]))\n"
       "(deftest f (is (= 1 1)))\n"))

(deftest a-clj-and-cljc-sibling-declaring-one-namespace-are-named
  ;; Each spells its own path under its own extension, yet only the `.clj`
  ;; ever loads, so both are named.
  (with-tree {"probe/duplicate_test.clj"  (str "(ns probe.duplicate-test\n" duplicate-body)
              "probe/duplicate_test.cljc" (str "(ns probe.duplicate-test\n" duplicate-body)}
    (fn [dir]
      (let [defects (rf.test-quiet.runner/discovery-defects [(.getPath dir)])]
        (is (= ["duplicate_test.clj" "duplicate_test.cljc"] (defect-paths defects)))
        (is (str/includes? (complaint-for defects "duplicate_test.clj")
                           "duplicate_test.cljc"))))))

(deftest a-nested-discovery-directory-is-not-a-defect
  ;; `-d` chooses where cognitect scans, not a classpath root, so a directory
  ;; below the root `test` is an ordinary selection.
  (is (= [] (rf.test-quiet.runner/discovery-defects ["test/re_frame"]))))

(deftest a-file-the-classpath-answers-with-another-file-is-still-named
  ;; A stray copy of a namespace never runs: `require` loads the real one.
  (with-tree {"shadow.clj" "(ns re-frame.test-quiet.runner)\n"}
    (fn [dir]
      (let [defects (rf.test-quiet.runner/discovery-defects [(.getPath dir)])]
        (is (= ["shadow.clj"] (defect-paths defects)))
        (is (str/includes? (complaint-for defects "shadow.clj")
                           "src/re_frame/test_quiet/runner.clj"))))))
