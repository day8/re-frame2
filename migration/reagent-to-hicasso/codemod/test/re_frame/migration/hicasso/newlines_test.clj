(ns re-frame.migration.hicasso.newlines-test
  "**The fixer preserves the line endings it found.**

  rewrite-clj's parser normalizes every newline it reads to `\\n`. A tool
  that hands `n/string` straight back therefore rewrites EVERY LINE of a
  CRLF file — a whole-file diff at exactly the moment a migrator most
  needs to read one, and a direct contradiction of the
  formatting-preservation posture that justifies doing this through
  rewrite-clj at all. Consumers on Windows (`core.autocrlf=true`) are a
  large fraction of the people this codemod exists for, so this is not an
  edge.

  The golden corpus cannot witness this. Its fixtures are pinned to LF
  (`.gitattributes`) precisely so that a golden file's bytes do not depend
  on who checked the tree out — which is the right call for a byte-exact
  corpus, and which leaves the CRLF path uncovered there. So the sources
  below are built IN MEMORY, from the same text with the endings swapped,
  and they run identically on every platform including the Linux CI lane."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.migration.hicasso.codemod :as cm]))

;; ---------------------------------------------------------------------------
;; The rig
;; ---------------------------------------------------------------------------

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

(defn- rewritten [src] (:source (cm/rewrite-string src "src/app/endings.cljs")))

;; ---------------------------------------------------------------------------
;; The rewrite fires — otherwise every assertion below is vacuous
;; ---------------------------------------------------------------------------

(deftest the-fixture-actually-gets-rewritten
  (testing "a source the fixer leaves alone would satisfy every
            preservation claim in this namespace and mean nothing"
    (is (not= lf-source (rewritten lf-source))
        "the LF fixture must exercise a real rewrite")
    (is (not= crlf-source (rewritten crlf-source))
        "and so must the CRLF one")))

;; ---------------------------------------------------------------------------
;; Preservation
;; ---------------------------------------------------------------------------

(deftest an-lf-file-stays-lf
  (testing "the LF case, which is what every corpus fixture is"
    (let [out (rewritten lf-source)]
      (is (zero? (first (endings out)))
          "not one CR was introduced")
      (is (pos? (second (endings out)))
          "and the file still has lines"))))

(deftest a-crlf-file-stays-crlf
  (testing "THE AUDIT'S DEFECT. Before the repair the fixer answered this
            file in LF, so every one of its lines showed up in the
            migration diff — 9 changed lines to express a 3-line rewrite."
    (let [out (rewritten crlf-source)]
      (is (zero? (second (endings out)))
          "not one bare LF survives: every line ending is still CRLF")
      (is (= (first (endings crlf-source)) (first (endings out)))
          "and there are exactly as many lines as went in"))))

(deftest the-two-conventions-carry-the-same-rewrite
  (testing "line endings are the ONLY difference between the two outputs.
            The rewrite itself must not vary with them — a fixer whose
            decisions depend on how the file was checked out would be a
            worse defect than the one being repaired here."
    (is (= (rewritten lf-source)
           (str/replace (rewritten crlf-source) "\r\n" "\n")))))

(deftest the-untouched-lines-are-untouched
  (testing "the point of preserving endings is that a reviewer sees only
            the lines the tool decided to change. The `ns` form and the
            comment are not rewrite sites, so they must come back
            byte-for-byte in both conventions."
    (doseq [[label src] [["LF" lf-source] ["CRLF" crlf-source]]]
      (let [out   (rewritten src)
            head  (fn [s] (->> (str/split-lines s) (take 5) vec))]
        (is (= (head src) (head out))
            (str label ": the ns form and the comment must survive verbatim"))))))

;; ---------------------------------------------------------------------------
;; Idempotence, in both conventions (§4.7)
;; ---------------------------------------------------------------------------

(deftest a-second-run-is-a-no-op-in-both-conventions
  (testing "§4.7's claim is that every output is outside its own rewrite's
            input language. An output whose ENDINGS changed on the second
            pass would break that claim just as surely as one whose forms
            did — and would do it on the pass a migrator runs to check
            nothing is left."
    (doseq [[label src] [["LF" lf-source] ["CRLF" crlf-source]]]
      (let [once  (rewritten src)
            twice (rewritten once)]
        (is (= once twice)
            (str label ": running the fixer on its own output changed it"))
        (is (= (endings once) (endings twice))
            (str label ": the second pass changed the line endings"))))))

;; ---------------------------------------------------------------------------
;; The convention is read off the input, not off the platform
;; ---------------------------------------------------------------------------

(deftest a-file-with-no-newline-at-all-gains-none
  (testing "a one-liner has no convention to preserve, and the fixer must
            not invent one"
    (let [src "[:> Btn {:variant :primary}]"
          out (:source (cm/rewrite-string src "src/app/one.cljs"))]
      (is (= "[:> Btn {:variant \"primary\"}]" out))
      (is (= [0 0] (endings out))))))

(deftest a-mixed-file-follows-its-majority
  (testing "a genuinely mixed file cannot be reproduced exactly — the
            parser discarded the distinction before the fixer saw it — so
            the rule is the majority, stated plainly rather than left to
            whatever rewrite-clj happens to emit. Here CRLF is the
            majority, so the output is CRLF throughout."
    (let [src (str/replace crlf-source "(defn a-site []\r\n" "(defn a-site []\n")
          out (rewritten src)]
      (is (zero? (second (endings out))))))

  (testing "and with the majority the other way, LF throughout"
    (let [src (str/replace lf-source "(defn a-site []\n" "(defn a-site []\r\n")
          out (rewritten src)]
      (is (zero? (first (endings out)))))))
