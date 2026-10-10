(ns re-frame.migration.fresco.newlines-test
  "**The fixer preserves the line endings it found.**

  rewrite-clj's parser normalizes every newline it reads to `\\n`, so a tool
  that hands `n/string` straight back rewrites EVERY LINE of a CRLF file — a
  whole-file diff at exactly the moment a migrator most needs to read one.
  Consumers on Windows (`core.autocrlf=true`) are a large fraction of the
  people this codemod exists for.

  The golden corpus cannot witness this: its fixtures are pinned to LF
  (`.gitattributes`) so a golden file's bytes do not depend on who checked
  the tree out. So the sources below are built IN MEMORY, from the same
  text with the endings swapped, and run identically on every platform."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.fresco.codemod :as rf.migration.fresco.codemod]))

(def ^:private lf-source
  (str/join "\n"
            ["(ns app.endings"
             "  (:require [reagent.core :as r]))"
             ""
             "(defn a-site []"
             "  ;; a comment, whose position is part of what is preserved"
             "  [:> Btn {:variant :primary"
             "           :opts {:page-size 10}"
             "           :on-pick (r/partial handler @cart)}"
             "   \"Save\"])"
             ""]))

(def ^:private crlf-source (str/replace lf-source "\n" "\r\n"))

(defn- endings
  "`[crlf-count bare-lf-count]` for a string — the two things a diff sees."
  [s]
  (let [crlf (count (re-seq #"\r\n" s))
        all  (count (re-seq #"\n" s))]
    [crlf (- all crlf)]))

(defn- rewritten [src] (:source (rf.migration.fresco.codemod/rewrite-string src "src/app/endings.cljs")))

(deftest a-crlf-file-stays-crlf
  (let [lf-out (rewritten lf-source)
        out    (rewritten crlf-source)]
    (is (not= lf-source lf-out)
        "the fixture must exercise a real rewrite, or every assertion below is vacuous")
    (is (= (str/replace lf-out "\n" "\r\n") out)
        "CRLF throughout, carrying exactly the rewrite the LF file gets: a fixer whose
         decisions depended on how the file was checked out would be the worse defect")
    (is (= out (rewritten out))
        "a second run changes neither its forms nor its line endings (§4.7)")))

(deftest a-file-with-no-newline-at-all-gains-none
  (testing "a one-liner has no convention to preserve, and the fixer must
            not invent one"
    (is (= "[:> Btn {:variant \"primary\"}]"
           (:source (rf.migration.fresco.codemod/rewrite-string "[:> Btn {:variant :primary}]"
                                                                "src/app/one.cljs"))))))

(deftest a-mixed-file-follows-its-majority
  (testing "a genuinely mixed file cannot be reproduced exactly — the parser
            discarded the distinction before the fixer saw it — so the rule
            is the majority: one odd line in either direction leaves no bare
            LF in a CRLF file and no CRLF in an LF one"
    (is (= [0 0]
           [(second (endings (rewritten (str/replace crlf-source "(defn a-site []\r\n" "(defn a-site []\n"))))
            (first (endings (rewritten (str/replace lf-source "(defn a-site []\n" "(defn a-site []\r\n"))))]))))
