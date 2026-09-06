(ns re-frame.test-quiet-discovery-integrity-test
  "The rule `re-frame.test-quiet.runner/discovery-defects` states, and the
  defect it exists to make impossible (rf2-vruo9).

  A test file whose `(ns ...)` FORM the reader cannot read is INVISIBLE to
  `cognitect.test-runner`: discovery reads each file's form and
  `clojure.tools.namespace.find/find-ns-decls-in-dir` is a `keep` over a
  helper called `ignore-reader-exception`, so an unreadable file simply
  contributes no namespace. Measured on this repo: one stray unescaped
  quote in the ns docstring of `late_bind_drift_test.clj` took
  `implementation/core` from 2190 tests to 2182 — exactly that file's eight
  deftests — reporting `0 failures, 0 errors.` and exiting 0.

  A file can also fail to run while spelling its own path PERFECTLY, which
  is the second door and the one a per-file check cannot see. Discovery
  returns a namespace once per FILE, so when two files declare the same one
  — a `.clj` beside a `.cljc`, or one relative path repeated under two `-d`
  roots — `require` loads whichever the classpath resolves, skips the rest
  as already loaded, and hands `run-tests` the symbol twice. One file runs
  TWICE and the other never runs. The tally goes UP, so no coverage floor
  can see the substitution either. Hence two rules, not one: own-path, and
  global uniqueness across every selected root.

  EVERY TEST HERE PINS THE DEFECT BEFORE IT PINS THE GUARD. Each fixture is
  handed to `find/find-namespaces-in-dir` FIRST, and the assertion is on
  what discovery does or does not return; only then is `discovery-defects`
  asked to name it. A guard tested against nothing but its own output would
  go on passing after the library it mirrors changed underneath it — which
  is the shape of every check this one replaces.

  The wiring — that `-main` actually calls the rule, exits 1, and says so on
  stderr — is pinned across a process boundary in
  `re-frame.test-quiet-runner-contract-test`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.namespace.find :as ns-find]
            [re-frame.test-quiet.runner :as rf.test-quiet.runner]))

;; ----------------------------------------------------------------------
;; Fixtures

(defn- with-tree
  "Make a temp directory, write `files` (a map of relative path -> source)
  into it, hand it to `f`, then delete it."
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

(def ^:private well-formed
  "An ordinary suite: readable ns form, name spelling its own path."
  (str "(ns probe.good-test\n"
       "  \"An ordinary suite.\"\n"
       "  (:require [clojure.test :refer [deftest is]]))\n"
       "(deftest a (is (= 1 1)))\n"))

(def ^:private unreadable
  "The measured defect: one unescaped `\"` inside the ns docstring. The
  reader consumes the rest of the file as a string and hits EOF."
  (str "(ns probe.unreadable-test\n"
       "  \"A docstring with a stray \" quote in it.\"\n"
       "  (:require [clojure.test :refer [deftest is]]))\n"
       "(deftest b (is (= 1 1)))\n"))

(defn- defect-paths [defects]
  (mapv (comp #(last (str/split % #"/")) first) defects))

(defn- complaint-for [defects stem]
  (some (fn [[path complaint]]
          (when (str/ends-with? path stem) complaint))
        defects))

;; ----------------------------------------------------------------------
;; The dirs the rule is applied to

(deftest discovery-dirs-are-cognitects-own
  (testing "the guard reads `-d` with cognitect's own option spec and
            default, so there is no second reading to disagree with the
            first"
    (is (= #{"test"} (rf.test-quiet.runner/discovery-dirs []))
        "no -d means cognitect's `(or (:dir options) #{\"test\"})`")
    (is (= #{"test" "extra"} (rf.test-quiet.runner/discovery-dirs ["-d" "test" "-d" "extra"]))
        "-d accumulates, exactly as cognitect's :assoc-fn does")
    (is (= #{"other"} (rf.test-quiet.runner/discovery-dirs ["--dir=other"]))
        "the long form with = is the same option")
    (is (= #{"test"} (rf.test-quiet.runner/discovery-dirs ["-n" "some.ns-test" "-e" ":slow"]))
        "selectors narrow what RUNS, never what must be discoverable"))

  (testing "unparseable args stand the guard down: cognitect owns its parse
            diagnostics and exits before discovery, so there is nothing yet
            to guard"
    (is (nil? (rf.test-quiet.runner/discovery-dirs ["--no-such-flag"])))))

;; ----------------------------------------------------------------------
;; The rule

(deftest an-unreadable-ns-form-is-invisible-and-is-named
  (with-tree {"probe/good_test.clj"       well-formed
              "probe/unreadable_test.clj" unreadable}
    (fn [dir]
      (testing "THE DEFECT: discovery silently drops the file it cannot read"
        (let [discovered (set (ns-find/find-namespaces-in-dir dir))]
          (is (contains? discovered 'probe.good-test))
          (is (not (contains? discovered 'probe.unreadable-test))
              (str "the whole bug: an unreadable `(ns ...)` form yields no"
                   " namespace at all, so nothing downstream — not the"
                   " summary, not the RF2_MIN_TESTS floor — has anything"
                   " left to notice. Discovered: " (pr-str discovered)))))

      (testing "THE GUARD: it is named, and only it"
        (let [defects (rf.test-quiet.runner/discovery-defects [(.getPath dir)])]
          (is (= ["unreadable_test.clj"] (defect-paths defects)))
          (is (str/includes? (complaint-for defects "unreadable_test.clj")
                             "the reader cannot read it")
              "the complaint must say the form is unreadable")
          (is (str/includes? (complaint-for defects "unreadable_test.clj")
                             "EOF")
              (str "and must carry the reader's OWN message, which"
                   " `ignore-reader-exception` throws away — that is the"
                   " difference between a red an operator can act on and"
                   " one they must bisect")))))))

(deftest a-file-declaring-another-namespace-is-named
  (with-tree {"probe/good_test.clj"        well-formed
              "probe/mislabelled_test.clj" (str "(ns probe.elsewhere-test\n"
                                                "  (:require [clojure.test"
                                                " :refer [deftest is]]))\n"
                                                "(deftest c (is (= 1 1)))\n")}
    (fn [dir]
      (testing "THE DEFECT: discovery returns a name whose file is not this
                one, so `require` loads whatever lives at THAT path and this
                file's tests run nowhere"
        (is (contains? (set (ns-find/find-namespaces-in-dir dir))
                       'probe.elsewhere-test))
        (is (not (.exists (io/file dir "probe/elsewhere_test.clj")))
            "and nothing lives there"))

      (testing "THE GUARD: named, with the path its declaration resolves to"
        (let [defects (rf.test-quiet.runner/discovery-defects [(.getPath dir)])]
          (is (= ["mislabelled_test.clj"] (defect-paths defects)))
          (is (str/includes? (complaint-for defects "mislabelled_test.clj")
                             "probe/elsewhere_test.clj")
              "the complaint names the file discovery will look for"))))))

(deftest two-files-declaring-one-namespace-are-not-two-suites
  (with-tree {"probe/good_test.clj"   well-formed
              "probe/shadow_test.clj" (str "(ns probe.good-test\n"
                                           "  (:require [clojure.test"
                                           " :refer [deftest is]]))\n"
                                           "(deftest d (is (= 1 1)))\n")}
    (fn [dir]
      (testing "THE DEFECT: one namespace, twice. `require` loads whichever
                file the classpath resolves and the other never loads —
                while the tally goes UP, not down, so no coverage floor can
                see it"
        (is (= ['probe.good-test 'probe.good-test]
               (vec (ns-find/find-namespaces-in-dir dir)))))

      (testing "THE GUARD: two files cannot both spell one namespace,
                because their paths differ"
        (is (= ["shadow_test.clj"]
               (defect-paths (rf.test-quiet.runner/discovery-defects [(.getPath dir)]))))))))

(def ^:private duplicate-body
  (str "  (:require [clojure.test :refer [deftest is]]))\n"
       "(deftest f (is (= 1 1)))\n"))

(deftest a-clj-and-cljc-sibling-declaring-one-namespace-are-named
  (with-tree {"probe/good_test.clj"       well-formed
              "probe/duplicate_test.clj"  (str "(ns probe.duplicate-test\n"
                                               duplicate-body)
              "probe/duplicate_test.cljc" (str "(ns probe.duplicate-test\n"
                                               duplicate-body)}
    (fn [dir]
      (testing "THE DEFECT: each sibling spells its OWN path under its own
                extension, so the per-file rule clears both — yet discovery
                returns the one name twice and only the `.clj` ever loads"
        (is (= ['probe.duplicate-test 'probe.duplicate-test]
               (vec (filter #{'probe.duplicate-test}
                            (ns-find/find-namespaces-in-dir dir))))
            "one namespace, two files, two entries in the discovered seq"))

      (testing "THE GUARD: both files are named, each pointing at the other"
        (let [defects (rf.test-quiet.runner/discovery-defects [(.getPath dir)])]
          (is (= ["duplicate_test.clj" "duplicate_test.cljc"]
                 (defect-paths defects))
              "every colliding file is named, not just one of them")
          (is (str/includes? (complaint-for defects "duplicate_test.clj")
                             "duplicate_test.cljc")
              "the .clj complaint names the .cljc it shadows")
          (is (str/includes? (complaint-for defects "duplicate_test.cljc")
                             "duplicate_test.clj")
              "and the .cljc complaint names the .clj that shadows it"))))))

(deftest one-relative-path-under-two-roots-is-named
  (with-tree {"probe/duplicate_test.clj" (str "(ns probe.duplicate-test\n"
                                              duplicate-body)}
    (fn [a]
      (with-tree {"probe/duplicate_test.clj" (str "(ns probe.duplicate-test\n"
                                                  duplicate-body)}
        (fn [b]
          (testing "THE DEFECT: each root's file spells its own relative path,
                    so per-file checking clears both, and the collision only
                    exists ACROSS roots"
            (is (= ['probe.duplicate-test 'probe.duplicate-test]
                   (vec (mapcat #(ns-find/find-namespaces-in-dir (io/file %))
                                [(.getPath a) (.getPath b)])))))

          (testing "THE GUARD: uniqueness is global to the selected roots"
            (is (= ["duplicate_test.clj" "duplicate_test.clj"]
                   (defect-paths (rf.test-quiet.runner/discovery-defects
                                   [(.getPath a) (.getPath b)])))
                "both copies are named")
            (is (= [] (rf.test-quiet.runner/discovery-defects [(.getPath a)]))
                "and either root ALONE is clean: the defect is the pairing,
                 so a single-root lane must not be reddened by it")))))))

(deftest one-directory-reached-by-two-spellings-is-not-a-collision
  (testing "a lane naming one directory twice — `-d test -d ./test` — walks
            each file twice, and the same file is one file. Deduplicating by
            canonical path is what keeps this guard from reddening a lane
            over its alias spelling rather than over its sources"
    (with-tree {"probe/good_test.clj" well-formed}
      (fn [dir]
        (let [p (.getPath dir)]
          (is (= [] (rf.test-quiet.runner/discovery-defects [p (str p "/.")]))))))))

(deftest a-well-formed-tree-has-nothing-to-say
  (testing "silent on success: the rule is a refusal, not a report"
    (with-tree {"probe/good_test.clj"    well-formed
                "probe/nested/more_test.clj"
                (str "(ns probe.nested.more-test\n"
                     "  (:require [clojure.test :refer [deftest is]]))\n"
                     "(deftest e (is (= 1 1)))\n")
                "probe/not_a_test.clj"
                (str "(ns probe.not-a-test)\n")}
      (fn [dir]
        (is (= [] (rf.test-quiet.runner/discovery-defects [(.getPath dir)]))
            "nested paths, and a file the `-test` selector will skip, are
             both ordinary"))))

  (testing "a directory that does not exist contributes nothing, exactly as
            it does to cognitect"
    (is (= [] (rf.test-quiet.runner/discovery-defects ["no-such-directory-anywhere"]))))

  (testing "and this artefact's own test tree is clean"
    (is (= [] (rf.test-quiet.runner/discovery-defects ["test"])))))
