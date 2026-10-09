(ns re-frame.no-rf-default-floor-lint-test
  "The carried-frame invariant as a CI lint (`spec/002-Frames.md` §Frame target
  resolution): absence of frame context is `:rf.error/no-frame-context`, never
  repaired by synthesising `:rf/default`. No production source under
  `implementation/**/src/` or `tools/**/src/` may carry an `(or … :rf/default)`
  floor, an `:or {frame-id :rf/default}` default, or a positional
  `[:rf/default <sym>]` floor. `:rf/default` as an EXPLICIT frame id is legal.

  The scan reads files as text, so `tools/` adds no classpath edge. `test/` is
  excluded, and line comments, string literals and backtick-quoted prose are
  stripped, so only live code is flagged."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private repo-implementation-root (-> (io/file "..") .getCanonicalFile))
(def ^:private repo-tools-root (-> (io/file "../../tools") .getCanonicalFile))

(defn- source-files []
  (->> (concat (file-seq repo-implementation-root)
               (file-seq repo-tools-root))
       (filter #(.isFile ^java.io.File %))
       (filter (fn [^java.io.File f]
                 (let [n (.getName f)]
                   (or (str/ends-with? n ".clj")
                       (str/ends-with? n ".cljc")
                       (str/ends-with? n ".cljs")))))
       (filter (fn [^java.io.File f]
                 (let [norm (str/replace (.getPath f) "\\" "/")]
                   (and (str/includes? norm "/src/")
                        (not (str/includes? norm "/test/"))))))))

(defn- strip-line-comment [^String line]
  (let [idx (.indexOf line ";")]
    (if (neg? idx) line (subs line 0 idx))))

;; Possessive on purpose: a greedy group recurses once per character of a
;; quoted span and throws StackOverflowError on a long line, which would read
;; as a lint finding. The two branches are disjoint, so possessive matching
;; accepts exactly the same spans.
(def ^:private string-literal-re #"\"(?:[^\"\\]++|\\.)*+\"")

(defn- strip-string-literals [^String line]
  (str/replace line string-literal-re " "))

(defn- backtick-quoted-mention?
  "True when the `:rf/default` on the line sits inside a backtick-quoted prose
  fragment (an odd number of backticks before it)."
  [^String code-line]
  (boolean
    (when-let [i (str/index-of code-line ":rf/default")]
      (odd? (count (filter #(= \` %) (subs code-line 0 i)))))))

(def ^:private or-default-re #"\(or\s[^\n]*:rf/default\s*\)")
(def ^:private destructure-default-re #":or\s*\{[^}]*:rf/default[^}]*\}")

;; `:rf/default` must be followed by a further arg, so an explicit one-element
;; `[:rf/default]` frame-id vector is not flagged.
(def ^:private positional-default-re #"\[\s*:rf/default\s+[^]\n]+\]")

(defn- offending-lines [content]
  (->> (str/split-lines content)
       (map-indexed (fn [i line] [(inc i) line]))
       (keep (fn [[n raw]]
               (let [code (-> raw strip-line-comment strip-string-literals)]
                 (when (and (str/includes? code ":rf/default")
                            (not (backtick-quoted-mention? code))
                            (or (re-find or-default-re code)
                                (re-find destructure-default-re code)
                                (re-find positional-default-re code)))
                   [n (str/trim raw)]))))))

(deftest no-rf-default-absence-repair-in-production-source
  (let [offenders
        (for [^java.io.File f (source-files)
              [n line] (offending-lines (slurp f))]
          (str (str/replace (.getPath f) "\\" "/") ":" n "  " line))]
    (is (empty? offenders)
        (str "These production source lines repair an absent frame with "
             "`:rf/default`. Use `require-current-frame!` / "
             "`require-frame-stamp!` and let absence raise "
             "`:rf.error/no-frame-context`:\n  "
             (str/join "\n  " offenders)))))

(deftest string-literal-strip-preserves-the-lint-contract
  ;; The positive control: stripping may only reduce false positives, never
  ;; mask a live floor — including one after a long string span on its line.
  (doseq [line ["(defwrapper foo ([id] [:rf/default id]))"
                "(let [f (or (:frame opts) :rf/default)] f)"
                "(defn g [{:keys [frame-id] :or {frame-id :rf/default}}] frame-id)"
                (str "(def hint \"" (str/join (repeat 3000 "x")) "\") [:rf/default id]")]]
    (is (seq (offending-lines line)) (subs line 0 (min 60 (count line))))))
