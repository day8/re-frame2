(ns re-frame.freehand.check-splicing-map-jvm-test
  "The checker's own read mode has exactly one blind spot, and it must be
  ANSWERED rather than crashed through.

  Reading with `:read-cond :preserve` is what lets the checker reach the
  `:cljs` branch at all (`check-host-divergent-jvm-test` pins that). It costs
  one shape: Clojure's map reader counts the forms it read BEFORE splicing is
  applied, and a preserved `#?@` is one form, so a map literal containing a
  splicing conditional reads for every compile and not for the checker.

  Before this suite, that shape made `check-file` throw
  `clojure.lang.LispReader$ReaderException` — \"Map literal must contain an
  even number of forms\" — a false-ERROR, and one whose message says nothing
  about the checker, names no workaround, and blames a declaration that is
  perfectly legal. Four things are asserted:

    1. the fixture really is legal — a reader told to read it for EITHER
       target reads it, so the failure is the checker's read mode and not the
       source (the oracle is `clojure.tools.reader`, independent of the code
       under test);
    2. `check-file` answers a checker REFUSAL: an `ex-info` naming the file,
       the shape and the workaround, carrying the read mode in its data —
       never a raw reader exception;
    3. a read failure the checker did NOT cause is re-thrown untouched, so
       the refusal cannot become a blanket \"unreadable\" that hides real
       syntax errors behind advice about conditionals;
    4. the fixture is byte-identical after the refused run — refusing is
       still read-only."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as rt]
            [re-frame.freehand.check-splicing-map-views]
            [re-frame.freehand.compiler.check :as check]))

(def ^:private fixture-resource
  "re_frame/freehand/check_splicing_map_views.cljc")

(def ^:private fixture-path
  (.getPath (io/file (io/resource fixture-resource))))

(defn- thrown-by
  "The exception `f` throws, or nil."
  [f]
  (try (f) nil (catch Throwable t t)))

;; ---------------------------------------------------------------------------
;; 1 — the source is legal; the read mode is the limitation
;; ---------------------------------------------------------------------------

(defn- attrs-read-for
  "The attribute map of the fixture's declaration, as a REAL reader reads the
  file for `features`. `clojure.tools.reader` honours `:features` exactly and
  splices before pairing, exactly as the compile's reader does — so this is
  the independent statement of what the file MEANS."
  [features]
  (let [r (rt/indexing-push-back-reader (slurp (io/resource fixture-resource)))]
    (loop []
      (let [form (rdr/read {:eof ::eof :read-cond :allow :features features} r)]
        (cond
          (= ::eof form)                        nil
          (and (seq? form) (= 'v/defview (first form)))
          (first (filter map? (tree-seq coll? seq form)))
          :else                                 (recur))))))

(deftest the-fixture-is-legal-for-both-targets
  (testing "a reader told to read the file for either target splices the
            conditional into the map and produces a well-formed map — the
            declaration is not the problem"
    (is (= {:class "card" :title "JVM"}     (attrs-read-for #{:clj})))
    (is (= {:class "card" :title "browser"} (attrs-read-for #{:cljs}))))

  (testing "and the same map is unreadable with conditionals PRESERVED, which
            is the mode the checker must use to reach the :cljs branch — the
            limitation the refusal reports"
    (let [read-all (fn []
                     (with-open [r (clojure.lang.LineNumberingPushbackReader.
                                    (java.io.StringReader.
                                     (slurp (io/resource fixture-resource))))]
                       (loop []
                         (when-not (= ::eof (read {:eof ::eof :read-cond :preserve} r))
                           (recur)))))
          ex       (thrown-by read-all)]
      (is (some? ex))
      (is (re-find #"Map literal must contain an even number of forms" (str ex))))))

;; ---------------------------------------------------------------------------
;; 2 — check-file refuses; it does not raise the reader's exception
;; ---------------------------------------------------------------------------

(deftest check-file-refuses-rather-than-crashing
  (let [ex (thrown-by #(check/check-file fixture-path))]
    (is (instance? clojure.lang.ExceptionInfo ex)
        "a checker refusal, not the clojure.lang.LispReader$ReaderException
         the preserved read raises — that was the pre-fix behaviour, and it
         reported a reader's private difficulty as if the declaration were
         at fault")
    (is (not (instance? clojure.lang.LispReader$ReaderException ex)))

    (testing "the sentence names the checker, the file, the shape that stopped
              it, and what to write instead"
      (let [msg (ex-message ex)]
        (is (re-find #"^re-frame\.freehand check: " msg))
        (is (.contains msg fixture-path))
        (is (.contains msg "#?@(:clj [:b 2])") "the shape is shown, not described")
        (is (.contains msg "PRESERVED") "and why the checker reads that way")
        (is (.contains msg "Write the conditional around the whole map")
            "the workaround is the payload")))

    (testing "and the data says where and why, so a tool can render it"
      (is (= {:file fixture-path :read-cond :preserve}
             (select-keys (ex-data ex) [:file :read-cond])))
      (is (integer? (:line (ex-data ex))))
      (is (integer? (:column (ex-data ex)))))))

;; ---------------------------------------------------------------------------
;; 3 — a failure the checker did not cause stays the reader's
;; ---------------------------------------------------------------------------

(deftest an-unrelated-read-failure-is-not-reframed
  (testing "the refusal is narrow: a file that no reader can read is answered
            by the reader's own message, the same one the compile gives, not
            by advice about splicing conditionals"
    (let [f (doto (java.io.File/createTempFile "check-splicing-map" ".cljc") .deleteOnExit)]
      (spit f "(ns re-frame.freehand.unbalanced-views\n")
      (let [ex (thrown-by #(check/check-file (.getPath f)))]
        (is (instance? clojure.lang.LispReader$ReaderException ex)
            "re-thrown untouched")
        (is (not (re-find #"re-frame\.freehand check:" (str (ex-message ex)))))))))

;; ---------------------------------------------------------------------------
;; 4 — read-only
;; ---------------------------------------------------------------------------

(deftest the-checker-never-writes
  (testing "a refused run is still read-only — the source it was pointed at is
            byte-identical afterwards"
    (let [before (java.nio.file.Files/readAllBytes (.toPath (io/file fixture-path)))
          _      (thrown-by #(check/check-file fixture-path))
          after  (java.nio.file.Files/readAllBytes (.toPath (io/file fixture-path)))]
      (is (pos? (alength before)) "the fixture file is not empty")
      (is (java.util.Arrays/equals ^bytes before ^bytes after)))))
