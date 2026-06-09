(ns re-frame.no-rf-default-floor-lint-test
  "EP-0002 (rf2-9o48ih) — the carried-invariant STATIC conformance lint.

  Appendix G of EP-0002 (\"shift detection left\") asks that the
  `:rf/default`-as-absence-repair sweep be promoted from a prose `rg`
  regex into a FIRST-CLASS conformance lint that runs in CI — part of the
  contract, not a comment a reviewer might forget to run. This is that
  lint.

  The carried invariant (`spec/002-Frames.md` §Frame target resolution):
  a frame-scoped operation reads its frame from the causal token it holds;
  absence is `:rf.error/no-frame-context`, NEVER repaired by synthesising
  `:rf/default`. The runtime must therefore carry NO

    - `:or {frame-id :rf/default}` destructuring default, and
    - `(or … :rf/default)` resolution floor

  in PRODUCTION source. `:rf/default` remains a perfectly legal EXPLICIT
  frame id (a migration may pick it, a test may register + select it) — the
  ban is on using it as an *absence repair*, not on the keyword itself.

  Scope: only `implementation/**/src/` (the production reference). Test
  fixtures legitimately register + select `:rf/default` and may carry
  `(or frame :rf/default)` in their OWN harness code, so `test/` is
  excluded. Docstrings / comments that DESCRIBE the absence of the floor
  (e.g. \"there is NO `(or frame-kw :rf/default)` floor\") are NOT
  offences — the scan strips line comments and skips backtick-quoted prose
  so it flags live code only.

  Walks the same source tree as `re-frame.late-bind-drift-test` /
  `re-frame.warn-once-clear-governance-test`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private repo-implementation-root
  (-> (io/file "..") .getCanonicalFile))

(defn- source-files
  "Every `.clj{,c,s}` under `implementation/**/src/` (skips `test/`)."
  []
  (->> (file-seq repo-implementation-root)
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

(defn- strip-line-comment
  "Drop the trailing `;`-comment from a line of Clojure source so a
  pattern mentioned only in a comment is not flagged. Best-effort: a `;`
  inside a string literal is rare in these files and would only ever
  REDUCE false positives, never mask a real floor (a real floor is code,
  not a string)."
  [^String line]
  (let [idx (.indexOf line ";")]
    (if (neg? idx) line (subs line 0 idx))))

(defn- backtick-quoted-mention?
  "True when the only `:rf/default` on the (comment-stripped) line sits
  inside a backtick-quoted docstring fragment — the convention these
  files use to TALK ABOUT a code form (e.g. \"NO `(or x :rf/default)`
  floor\"). Such prose is not a live floor."
  [^String code-line]
  (boolean
    (when-let [i (str/index-of code-line ":rf/default")]
      (let [before (subs code-line 0 i)]
        ;; An odd number of backticks before the token means we are
        ;; inside a backtick span (prose), not in live code.
        (odd? (count (filter #(= \` %) before)))))))

;; ---------------------------------------------------------------------------
;; The two banned absence-repair shapes (EP-0002 §12 + Appendix G).
;; ---------------------------------------------------------------------------

(def ^:private or-default-re
  "An `(or … :rf/default)` resolution FLOOR — `:rf/default` is the LAST
  alternative of an `or` over frame candidates, i.e. the synthesised
  default when every real source was absent. The `[^\\n]*` gap (rather than
  `[^()]*`) lets the candidates between `(or` and the `:rf/default)` tail
  carry their own parens (`(or (:frame opts) :rf/default)`)."
  #"\(or\s[^\n]*:rf/default\s*\)")

(def ^:private destructure-default-re
  "A `:or {frame-id :rf/default}` destructuring default — the same
  absence repair expressed through a `:keys` / `:or` binding."
  #":or\s*\{[^}]*:rf/default[^}]*\}")

(defn- offending-lines
  "Return `[line-no line]` pairs in `content` that carry a live (non-
  comment, non-prose) `:rf/default` absence-repair floor."
  [content]
  (->> (str/split-lines content)
       (map-indexed (fn [i line] [(inc i) line]))
       (keep (fn [[n raw]]
               (let [code (strip-line-comment raw)]
                 (when (and (str/includes? code ":rf/default")
                            (not (backtick-quoted-mention? code))
                            (or (re-find or-default-re code)
                                (re-find destructure-default-re code)))
                   [n (str/trim raw)]))))))

(deftest no-rf-default-absence-repair-in-production-source
  (testing "no production source synthesises `:rf/default` from missing
            frame context — neither `(or … :rf/default)` nor
            `:or {frame-id :rf/default}`. EP-0002 carried invariant: absence
            is `:rf.error/no-frame-context`, never an invented default
            (Appendix G — shift detection left into a CI lint)."
    (let [offenders
          (for [^java.io.File f (source-files)
                :let [content (slurp f)]
                [n line] (offending-lines content)]
            (str (str/replace (.getPath f) "\\" "/") ":" n "  " line))]
      (is (empty? offenders)
          (str "These production source lines carry a `:rf/default` "
               "ABSENCE-REPAIR floor, which the EP-0002 carried invariant "
               "forbids (use `require-current-frame!` / `require-frame-stamp!` "
               "and let absence raise `:rf.error/no-frame-context`; "
               "`:rf/default` is legal only as an EXPLICIT, registered + "
               "selected frame id):\n  "
               (str/join "\n  " offenders))))))
