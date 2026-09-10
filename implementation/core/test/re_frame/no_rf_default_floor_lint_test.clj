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

    - `:or {frame-id :rf/default}` destructuring default,
    - `[:rf/default <sym>]` POSITIONAL floor — synthesising `:rf/default`
      as the frame argument of a delegated/recursive call (e.g. a
      `defwrapper` single-arity that recurses `([id] [:rf/default id])`,
      injecting the default the impl would otherwise resolve from the
      carried scope — the rf2-vl5xsp floor), and
    - `(or… :rf/default)` resolution floor

  in PRODUCTION source. `:rf/default` remains a perfectly legal EXPLICIT
  frame id (a migration may pick it, a test may register + select it) — the
  ban is on using it as an *absence repair*, not on the keyword itself.

  Scope (rf2-wwt8a3): both `implementation/**/src/` (the production
  reference) AND `tools/**/src/` — Xray, story, story-mcp, the pair-MCP,
  machines-viz, the template + testbed-support. EP-0002's own Audit
  Evidence named the tool tree as the DENSEST `:rf/default` surface and
  the most likely place ambient frame assumptions creep back, yet the
  shipped lint covered one tree of five (SS-12's sweep ambition was
  \"docs skills tools implementation spec\"). The tools are clean today;
  this lint is the guard that keeps them clean. Both trees are reached by
  filesystem walk — the lint reads files as text (`slurp`), so no
  classpath dependency on `tools/` is introduced. `test/` is excluded in
  BOTH trees: test fixtures legitimately register + select `:rf/default`
  and may carry `(or frame :rf/default)` in their OWN harness code
  (e.g. `tools/xray/test/.../sub_reactivity.cljs` has a
  `:or {frame :rf/default}` helper default — a test-helper, consistently
  exempt). Docstrings / comments that DESCRIBE the absence of the floor
  (e.g. \"there is NO `(or frame-kw :rf/default)` floor\") are NOT
  offences — the scan strips line comments, double-quoted string literals
  (MCP descriptor / hint prose like \"-> {:frames [:rf/default :stories]}\"
  is data, not a live floor), and backtick-quoted prose, so it flags live
  code only.

  Walks the production source the same way as
  `re-frame.late-bind-drift-test` / `re-frame.warn-once-clear-governance-test`,
  extended to also cover the sibling `tools/` tree."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private repo-implementation-root
  "Absolute path to `implementation/`. Tests run from
  `implementation/core/`, so `..` reaches it."
  (-> (io/file "..") .getCanonicalFile))

(def ^:private repo-tools-root
  "Absolute path to the sibling `tools/` tree (rf2-wwt8a3). From
  `implementation/core/`, `../../tools` reaches it. The walk is pure
  text — `slurp` over the files — so this adds no classpath edge from
  the core test artefact to `tools/`."
  (-> (io/file "../../tools") .getCanonicalFile))

(defn- source-files
  "Every `.clj{,c,s}` under `implementation/**/src/` AND `tools/**/src/`
  (skips `test/` in both)."
  []
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

(defn- strip-line-comment
  "Drop the trailing `;`-comment from a line of Clojure source so a
  pattern mentioned only in a comment is not flagged. Best-effort: a `;`
  inside a string literal is rare in these files and would only ever
  REDUCE false positives, never mask a real floor (a real floor is code,
  not a string)."
  [^String line]
  (let [idx (.indexOf line ";")]
    (if (neg? idx) line (subs line 0 idx))))

;; POSSESSIVE ON PURPOSE (rf2-ep7u) — do not "simplify" this back to the
;; natural greedy spelling #"\"(?:\\.|[^\"\\])*\"".
;;
;; THE DEFECT. Java compiles a quantified group to a `Loop` node and matches a
;; greedy loop by RECURSING once per iteration, so scanning an N-character
;; quoted span costs N stack frames. Past a threshold set by the thread's stack
;; size the greedy form throws `StackOverflowError` — and because the throw
;; happens inside the `for` that builds `offenders`, it surfaces as
;; `expected: (empty? offenders)` beneath this lint's own `:rf/default` prose,
;; i.e. it reads exactly like a genuine floor finding on a PR that introduced
;; none. It is DETERMINISTIC in span length; what varies between runs is the
;; stack, which is why re-running "fixed" it and why re-running is not a remedy.
;; This lint's step also runs FIRST in test.yml's `jvm-repo-source-walks` job,
;; whose seven walks are each guarded to run even after an earlier one fails
;; (rf2-gf3y), so the crash reds THIS step alone while the six behind it still
;; run — which makes it easier to read, not harder.
;;
;; THE REPAIR IS THE SAME LANGUAGE, not merely a faster one. The two branches
;; are DISJOINT — `[^\"\\]` excludes both the backslash and the quote, `\\.`
;; requires a backslash — so at most one applies at any position and the loop
;; never has a choice to backtrack into. Undoing an iteration could only matter
;; if it let the closing `\"` match, and it cannot: undoing a `\\.` leaves the
;; cursor on a backslash, undoing a `[^\"\\]` leaves it on a non-quote
;; character, and `\"` matches neither. Possessive quantifiers compile to a node
;; that ITERATES, so the scan is constant-stack at any length. Checked
;; empirically as well: over the 371,268 lines of the real corpus the two forms
;; produce identical output on every line, with zero disagreements.
(def ^:private string-literal-re
  "A double-quoted Clojure string span, `\\\"`-escapes included. Possessive so
  the scan cannot overflow the stack on a long line — see the comment above."
  #"\"(?:[^\"\\]++|\\.)*+\"")

(defn- strip-string-literals
  "Replace every double-quoted string span on the line with a space, so a
  `:rf/default` that appears only inside string DATA — an MCP tool's
  descriptor / hint prose like `\"-> {:frames [:rf/default :stories]}\"`
  (rf2-wwt8a3, dense in `tools/re-frame2-pair-mcp`) — is not mistaken for
  a live `[:rf/default …]` positional floor. A real floor is code, never
  string content, so this can only REDUCE false positives, never mask a
  genuine floor."
  [^String line]
  (str/replace line string-literal-re " "))

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

(def ^:private positional-default-re
  "A `[:rf/default <sym>]` POSITIONAL floor — `:rf/default` synthesised as
  the leading (frame) element of a vector literal that is then passed
  positionally to a delegated/recursive call, ahead of one or more
  further args. This is the rf2-vl5xsp shape: a `defwrapper` single-arity
  whose recursion body is `[:rf/default id]`, injecting the default frame
  the late-bound impl would otherwise resolve from the carried scope.

  Requires `:rf/default` to be FOLLOWED by further content before the
  closing `]` (a trailing arg), so a bare `[:rf/default]` — an explicit
  one-element frame-id vector, not an absence repair of a frame ARGUMENT —
  is not flagged. The `[^]\\n]+` tail keeps the match on one line."
  #"\[\s*:rf/default\s+[^]\n]+\]")

(defn- offending-lines
  "Return `[line-no line]` pairs in `content` that carry a live (non-
  comment, non-prose) `:rf/default` absence-repair floor."
  [content]
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
  (testing "no production source — `implementation/**/src/` OR
            `tools/**/src/` (rf2-wwt8a3) — synthesises `:rf/default` from
            missing frame context — none of `(or … :rf/default)`,
            `:or {frame-id :rf/default}`, or the positional
            `[:rf/default <sym>]` floor. EP-0002 carried invariant: absence
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

;; ---------------------------------------------------------------------------
;; rf2-ep7u — rows that grade THE INSTRUMENT rather than the corpus.
;;
;; The lint above walks a corpus that changes under it, so it cannot pin its own
;; scanner. These two do. The first is the crash; the second is the contract the
;; crash fix must not buy its way out of.
;; ---------------------------------------------------------------------------

(defn- long-span
  "`n` characters of string CONTENT — the thing whose length drove the old
  pattern's recursion depth."
  [n]
  (str/join (repeat n "x")))

(deftest string-literal-strip-is-constant-stack
  (testing "a long quoted span is STRIPPED rather than overflowing the stack
            (rf2-ep7u). The superseded greedy group recursed once per character
            of span, so this threw `StackOverflowError` — and did it under
            `expected: (empty? offenders)`, reading as a floor finding. 20000 is
            an order of magnitude past the longest span in the real corpus and
            far past any plausible thread stack, so restoring the greedy form
            turns this row RED rather than leaving it a hollow pass."
    (is (= "(def x  )"
           (strip-string-literals (str "(def x \"" (long-span 20000) "\")")))))

  (testing "the escape branch is constant-stack too — a long span made ENTIRELY
            of `\\\\x` escapes exercises the other alternative of the group."
    (is (= "(def x  )"
           (strip-string-literals
             (str "(def x \"" (str/join (repeat 10000 "\\x")) "\")"))))))

(deftest string-literal-strip-preserves-the-lint-contract
  (testing "`:rf/default` inside string DATA is stripped, so it is not read as a
            live positional floor — the false positive the strip exists to
            remove (rf2-wwt8a3 MCP descriptor prose)."
    (is (empty? (offending-lines
                  "(def hint \"-> {:frames [:rf/default :stories]}\")"))))

  (testing "and it is still stripped when the span is LONG. A repair that bought
            constant stack by declining to scan long lines would pass the crash
            row above and let this false positive straight back in."
    (is (empty? (offending-lines
                  (str "(def hint \"" (long-span 3000)
                       " [:rf/default :stories]\")")))))

  (testing "a live floor is still REPORTED — all three banned shapes. The strip
            may only reduce false positives, never mask a genuine floor."
    (is (seq (offending-lines "(defwrapper foo ([id] [:rf/default id]))")))
    (is (seq (offending-lines "(let [f (or (:frame opts) :rf/default)] f)")))
    (is (seq (offending-lines
               "(defn g [{:keys [frame-id] :or {frame-id :rf/default}}] frame-id)"))))

  (testing "a live floor sitting AFTER a long string span on the same line is
            still reported — the strip must run to completion and then leave the
            code outside it intact."
    (is (seq (offending-lines
               (str "(def hint \"" (long-span 3000) "\") [:rf/default id]"))))))
