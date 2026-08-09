(ns re-frame.migration.hicasso.corpus-test
  "**THE GOLDEN-FILE CORPUS IS THE SPEC** (design §5).

  Each case is a directory under `test/corpus/`:

      <case>/input.cljs           the consumer namespace, as written under Reagent
      <case>/expected.cljs        what the fixer writes
      <case>/expected-report.edn  what the fixer says

  and every case asserts four things:

  1. the rewrite — `input` transforms to `expected`, BYTE FOR BYTE, so a
     change in whitespace, comment placement or key order fails;
  2. the report — the entries over `input` equal `expected-report`,
     `:note` prose included, so **a change to what the tool SAYS is gated
     exactly as hard as a change to what it WRITES** (§7.1);
  3. **idempotence** — running the fixer on `expected` returns `expected`
     unchanged, which is §4.7's claim that every output is outside its own
     rewrite's input language;
  4. determinism — a second analysis of `input` reports identically.

  The report is compared as DATA read back from EDN rather than as
  characters. The claim being gated is what the tool says, and every field
  including the full `:note` participates in that comparison; pinning the
  pretty-printer's line breaks as well would red the corpus on a Clojure
  upgrade that changed nothing about the tool. The rewritten SOURCE is
  compared as characters, because there formatting is the whole point.

  Set `RF2_CODEMOD_REGEN=1` to rewrite the expectation files from the
  tool's current behaviour. Regenerating is how a case is authored; it is
  never how one is fixed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.hicasso.codemod :as cm]))

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
  "The comparable shape of a report: the entries, with the transient
  `:form` excerpt dropped (it is the input text, already asserted by the
  input file itself) and everything the tool DECIDED kept."
  [entries]
  (mapv #(select-keys % [:line :col :class :action :head :detail :note]) entries))

(defn- read-edn [f] (edn/read-string (slurp f)))

(defn- write-edn! [f data]
  (spit f (with-out-str (pp/pprint data))))

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
          result   (cm/rewrite-string src path)
          entries  (report-shape (:entries result))]

      (when regen?
        (spit exp-f (:source result))
        (write-edn! rep-f entries))

      (testing (str name* " — the rewrite")
        (is (.exists exp-f) (str "missing " exp-f))
        (is (= (slurp exp-f) (:source result))
            (str name* ": the fixer wrote something other than expected.cljs")))

      (testing (str name* " — the report")
        (is (.exists rep-f) (str "missing " rep-f))
        (is (= (read-edn rep-f) entries)
            (str name* ": the fixer SAID something other than expected-report.edn")))

      (testing (str name* " — idempotence (§4.7): a second run is a no-op")
        (let [again (cm/rewrite-string (:source result) path)]
          (is (= (:source result) (:source again))
              (str name* ": running the fixer on its own output changed it, so some "
                   "output is still inside its own rewrite's input language"))))

      (testing (str name* " — determinism")
        (is (= entries (report-shape (:entries (cm/rewrite-string src path))))
            (str name* ": two analyses of one input disagreed"))))))
