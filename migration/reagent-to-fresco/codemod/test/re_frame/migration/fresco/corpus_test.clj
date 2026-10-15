(ns re-frame.migration.fresco.corpus-test
  "Golden source, report data, idempotence and determinism are the migration contract."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.fresco.codemod :as rf.migration.fresco.codemod]))

(def ^:private corpus-root (io/file "test" "corpus"))

(def ^:private regen? (= "1" (System/getenv "RF2_CODEMOD_REGEN")))

(defn- cases []
  (->> (.listFiles corpus-root)
       (filter #(.isDirectory ^java.io.File %))
       (sort-by #(.getName ^java.io.File %))))

(defn- input-file [dir]
  (first (filter #(str/starts-with? (.getName ^java.io.File %) "input.")
                 (.listFiles ^java.io.File dir))))

(defn- report-shape
  [entries]
  (mapv #(select-keys % [:line :col :class :action :head :detail :note]) entries))

(defn- read-edn [f] (edn/read-string (slurp f)))

(defn- write-edn!
  [f data]
  (spit f (str/replace (with-out-str (pp/pprint data)) "\r\n" "\n")))

(deftest corpus-is-not-empty
  (testing "a corpus that silently found no cases would pass every
            assertion below and mean nothing"
    (is (<= 8 (count (cases)))
        "the corpus must cover every rewrite, every preserve class and
         every report class — see this namespace's docstring")))

(deftest ^:corpus golden-corpus
  (doseq [dir (cases)]
    (let [name*    (.getName ^java.io.File dir)
          in-f     (input-file dir)
          exp-f    (io/file dir (str "expected" (subs (.getName ^java.io.File in-f)
                                                      (str/index-of (.getName ^java.io.File in-f) "."))))
          rep-f    (io/file dir "expected-report.edn")
          src      (slurp in-f)
          path     (str "src/app/" (.getName ^java.io.File in-f))
          result   (rf.migration.fresco.codemod/rewrite-string src path)
          entries  (report-shape (:entries result))]

      (when regen?
        (spit exp-f (:source result))
        (write-edn! rep-f entries))

      (testing (str name* " — the rewrite")
        (is (= (slurp exp-f) (:source result))
            (str name* ": the fixer wrote something other than expected.cljs")))

      (testing (str name* " — the report")
        (is (= (read-edn rep-f) entries)
            (str name* ": the fixer SAID something other than expected-report.edn")))

      (testing (str name* " — idempotence (§4.7): a second run is a no-op")
        (let [again (rf.migration.fresco.codemod/rewrite-string (:source result) path)]
          (is (= (:source result) (:source again))
              (str name* ": running the fixer on its own output changed it, so some "
                   "output is still inside its own rewrite's input language"))))

      (testing (str name* " — determinism")
        (is (= entries (report-shape (:entries (rf.migration.fresco.codemod/rewrite-string src path))))
            (str name* ": two analyses of one input disagreed"))))))
