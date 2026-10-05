(ns re-frame.mcp-base.args-test
  "Tests for the shared MCP argument-coercion helpers."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.mcp-base.args :as rf.mcp-base.args]))

;; ---------------------------------------------------------------------------
;; parse-boolean
;; ---------------------------------------------------------------------------

(deftest parse-boolean-coerces-wire-values
  ;; Booleans pass through; the recognised string spellings (any case)
  ;; and the keywords `:true` / `:false` coerce; nil and anything
  ;; unrecognised take the default.
  (testing "resolves true"
    (are [raw default] (true? (rf.mcp-base.args/parse-boolean raw default))
      true    false
      nil     true
      "true"  false
      "TRUE"  false
      "1"     false
      "yes"   false
      "on"    false
      :true   false
      "maybe" true
      42      true))
  (testing "resolves false"
    (are [raw default] (false? (rf.mcp-base.args/parse-boolean raw default))
      false   true
      nil     false
      "false" true
      "0"     true
      "no"    true
      "off"   true
      :false  true
      "maybe" false)))

;; ---------------------------------------------------------------------------
;; parse-positive-int
;; ---------------------------------------------------------------------------

(deftest parse-positive-int-nil-returns-default
  (is (= 50 (rf.mcp-base.args/parse-positive-int nil 50))))

;; ---------------------------------------------------------------------------
;; Cross-host strict-parse contract.
;;
;; Pin the trailing-garbage case where the hosts could diverge: JVM
;; `Long/parseLong` throws on a non-numeric tail and falls back to the
;; default, while raw CLJS `js/parseInt` parses a numeric PREFIX
;; (`"12abc"` ⇒ 12). The strict `int-string-re` guard makes both hosts
;; fall back to `default`. The mirror CLJS assertions live in
;; `re-frame.mcp-base.cljs-branches-cljs-test` so the same expectations
;; are pinned on both platforms.
;; ---------------------------------------------------------------------------

(deftest parse-positive-int-rejects-trailing-garbage
  (is (= 50 (rf.mcp-base.args/parse-positive-int "12abc" 50))
      "trailing garbage falls back to default (a raw js/parseInt would read 12)")
  (is (= 50 (rf.mcp-base.args/parse-positive-int "5xyz" 50)))
  (is (= 50 (rf.mcp-base.args/parse-positive-int "12 34" 50)) "internal whitespace rejected")
  (is (= 50 (rf.mcp-base.args/parse-positive-int "0x10" 50)) "hex-prefixed string rejected")
  (is (= 50 (rf.mcp-base.args/parse-positive-int "1.5" 50)) "decimal string rejected")
  (is (= 50 (rf.mcp-base.args/parse-positive-int "1e3" 50)) "scientific notation rejected"))

(deftest parse-positive-int-accepts-clean-and-signed
  (is (= 12 (rf.mcp-base.args/parse-positive-int "12" 50)))
  (is (= 12 (rf.mcp-base.args/parse-positive-int "  12  " 50)) "surrounding whitespace trimmed")
  (is (= 12 (rf.mcp-base.args/parse-positive-int "+12" 50)) "leading plus accepted")
  (is (= 1 (rf.mcp-base.args/parse-positive-int "-5" 50)) "negative parses then clamps to floor"))

(deftest parse-positive-int-rejects-out-of-long-range
  ;; A digit string that overflows a JVM long is a parse failure →
  ;; default. The CLJS arm mirrors this via Number.isSafeInteger so the
  ;; two hosts agree on the rejection (not a lossy truncation).
  (is (= 50 (rf.mcp-base.args/parse-positive-int "99999999999999999999999999" 50))))

;; ---------------------------------------------------------------------------
;; Cross-runtime finite/range guard.
;;
;; A bare `(long raw)` with no finite/range guard is unsafe. On the
;; JVM `(long ##Inf)` / `(long 1.0E20)` THROW IllegalArgumentException and
;; `(long ##NaN)` truncates to a real `0` — neither the recoverable
;; default nor a crash-free, host-consistent result; and the string arm
;; would diverge across hosts at the JS safe-integer ceiling. Both arms
;; route through one safe-integer-windowed guard so an out-of-domain
;; numeric / string DEFAULTS (never throws, never truncates to a real
;; value) identically on JVM and CLJS. The CLJS mirror lives in
;; cljs_branches_cljs_test.
;; ---------------------------------------------------------------------------

(deftest parse-positive-int-out-of-domain-numerics-default-not-throw
  (is (= 50 (rf.mcp-base.args/parse-positive-int ##Inf 50)) "##Inf defaults (not IllegalArgumentException)")
  (is (= 50 (rf.mcp-base.args/parse-positive-int ##-Inf 50)) "##-Inf defaults")
  (is (= 50 (rf.mcp-base.args/parse-positive-int ##NaN 50)) "##NaN defaults (not a real floor of 1)")
  (is (= 50 (rf.mcp-base.args/parse-positive-int 1.0E20 50)) "1.0E20 defaults (not IllegalArgumentException)")
  (is (= 50 (rf.mcp-base.args/parse-positive-int -1.0E20 50)) "-1.0E20 defaults"))

(deftest parse-positive-int-in-domain-numerics-still-parse
  ;; The guard must not regress the legitimate small-int surface.
  (is (= 5 (rf.mcp-base.args/parse-positive-int 5 50)))
  (is (= 2 (rf.mcp-base.args/parse-positive-int 2.9 50)) "in-range fractional floors (benign)")
  (is (= 1 (rf.mcp-base.args/parse-positive-int 0.5 50)) "sub-1 positive floors to 0 then clamps to the floor 1")
  (is (= 9007199254740991 (rf.mcp-base.args/parse-positive-int 9007199254740991 50))
      "the safe-integer ceiling itself is in-domain"))

(deftest parse-positive-int-string-threshold-aligns-to-safe-integer
  ;; The string arm could diverge: the JVM `Long/parseLong` accepts
  ;; "9007199254740992" (a valid long, just past the JS safe-integer
  ;; ceiling) while CLJS rejects it via Number.isSafeInteger. The JVM
  ;; arm is held to the SAME safe-integer window so the two hosts agree
  ;; (the cross-runtime contract). The CLJS mirror asserts the identical
  ;; value in cljs_branches_cljs_test.
  (is (= 50 (rf.mcp-base.args/parse-positive-int "9007199254740992" 50))
      "one past the safe-integer ceiling defaults on BOTH hosts, the JVM included")
  (is (= 9007199254740991 (rf.mcp-base.args/parse-positive-int "9007199254740991" 50))
      "exactly the safe-integer ceiling is accepted on both hosts"))

;; ---------------------------------------------------------------------------
;; fresh-keyword — positive-named intern for operator-gated write paths.
;; ---------------------------------------------------------------------------

(deftest fresh-keyword-coerces-agent-ids
  ;; The contract is "agent-supplied id": a keyword passes through, a
  ;; string (bare or namespaced, with or without a leading colon) becomes
  ;; a keyword, and nil, a blank string or any other shape returns nil
  ;; rather than coercing.
  (are [raw expected] (= expected (rf.mcp-base.args/fresh-keyword raw))
    :foo                     :foo
    :ns/foo                  :ns/foo
    ":foo"                   :foo
    "rf.assert/path-equals"  :rf.assert/path-equals
    ":rf.assert/path-equals" :rf.assert/path-equals
    nil                      nil
    ""                       nil
    ":"                      nil
    42                       nil
    [:foo]                   nil
    {:k :v}                  nil))

;; ---------------------------------------------------------------------------
;; fresh-keyword-checked — grammar-gated intern. Validates the
;; STRING shape BEFORE interning so a rejected id leaves NO keyword.
;; ---------------------------------------------------------------------------

(deftest fresh-keyword-checked-interns-only-on-valid-shape
  ;; A `[ns name]` predicate that demands a `story.`-prefixed namespace.
  (let [shape-ok? (fn [[ns nm]] (and (string? ns)
                                     (string? nm)
                                     (pos? (count nm))
                                     (= ns "story.x")))]
    (testing "a valid-shape novel id interns and returns the keyword"
      ;; The name is built at run time and never spelled as a keyword
      ;; literal: the reader interns a literal when this namespace loads,
      ;; so the precondition would answer whether that intern had been
      ;; garbage-collected yet rather than what `fresh-keyword-checked` did.
      (let [nm    (str (gensym "tag30h-checked-valid-"))
            valid (str "story.x/" nm)]
        (is (nil? (find-keyword "story.x" nm))
            "precondition: not interned")
        (let [kw (rf.mcp-base.args/fresh-keyword-checked valid shape-ok?)]
          (is (some? (find-keyword "story.x" nm))
              "a valid id DOES intern — the gate only blocks the invalid ones")
          (is (= (keyword "story.x" nm) kw)))))
    (testing "an invalid-shape novel id returns nil and interns NOTHING"
      (let [invalid "not-story/tag30h-checked-invalid"]
        (is (nil? (find-keyword "not-story" "tag30h-checked-invalid"))
            "precondition: not interned")
        (is (nil? (rf.mcp-base.args/fresh-keyword-checked invalid shape-ok?)))
        (is (nil? (find-keyword "not-story" "tag30h-checked-invalid"))
            "a rejected id MUST NOT leave an interned keyword")))))

(deftest fresh-keyword-checked-length-cap-rejects-without-interning
  (let [shape-ok? (constantly true)               ; grammar permissive
        long-name (apply str "story.x/" (repeat 600 "z"))]
    (is (nil? (rf.mcp-base.args/fresh-keyword-checked long-name shape-ok? 512))
        "an over-long id is rejected by the length cap")))

(deftest fresh-keyword-checked-nil-and-non-string-return-nil
  (let [shape-ok? (constantly true)]
    (is (nil? (rf.mcp-base.args/fresh-keyword-checked nil shape-ok?)))
    (is (nil? (rf.mcp-base.args/fresh-keyword-checked 42 shape-ok?)))
    (is (nil? (rf.mcp-base.args/fresh-keyword-checked [:foo] shape-ok?)))))

(deftest fresh-keyword-checked-passes-through-valid-keyword
  (let [shape-ok? (fn [[ns _]] (= ns "story.x"))]
    (is (= :story.x/already (rf.mcp-base.args/fresh-keyword-checked :story.x/already shape-ok?)))
    (is (nil? (rf.mcp-base.args/fresh-keyword-checked :not-story/already shape-ok?))
        "a keyword failing the shape predicate is rejected too")))

;; ---------------------------------------------------------------------------
;; parse-mode
;; ---------------------------------------------------------------------------

(deftest parse-mode-resolves-against-the-allowlist
  ;; A keyword or string naming an allowed mode resolves to it; nil and
  ;; anything outside the allowlist take the default.
  (are [raw expected] (= expected (rf.mcp-base.args/parse-mode raw :diff #{:diff :full}))
    :diff    :diff
    :full    :full
    "diff"   :diff
    "full"   :full
    nil      :diff
    "maybe"  :diff
    :unknown :diff))

(deftest parse-mode-strips-leading-colon
  ;; Regression pin: `parse-mode` must accept
  ;; agent-supplied `":diff"` the same way the read path accepts
  ;; `":foo"`. Without the leading-colon strip this would silently
  ;; default-fall-back — the agent would see `:diff` returned but the
  ;; value would be the function's default, not a recognised match.
  (is (= :diff (rf.mcp-base.args/parse-mode ":diff" :full #{:diff :full})))
  (is (= :full (rf.mcp-base.args/parse-mode ":full" :diff #{:diff :full})))
  (is (= :rf/foo (rf.mcp-base.args/parse-mode ":rf/foo" :default #{:rf/foo :rf/bar}))
      "namespaced keywords also strip the leading colon"))

;; ---------------------------------------------------------------------------
;; safe-keyword — bounded-allowlist gate.
;; ---------------------------------------------------------------------------

(deftest safe-keyword-admits-only-allowlisted-ids
  ;; A keyword or string (bare or namespaced, with or without a leading
  ;; colon) naming an allowlisted id resolves to it. A disallowed id —
  ;; even one whose keyword already exists — nil, a blank string and any
  ;; non-string non-keyword return nil.
  (are [raw allowed expected] (= expected (rf.mcp-base.args/safe-keyword raw allowed))
    :diff      #{:diff :full}     :diff
    :rf/foo    #{:rf/foo :rf/bar} :rf/foo
    "diff"     #{:diff :full}     :diff
    ":diff"    #{:diff :full}     :diff
    "rf/foo"   #{:rf/foo :rf/bar} :rf/foo
    ":rf/foo"  #{:rf/foo :rf/bar} :rf/foo
    :other     #{:diff :full}     nil
    :rf/baz    #{:rf/foo :rf/bar} nil
    nil        #{:diff :full}     nil
    ""         #{:diff :full}     nil
    ":"        #{:diff :full}     nil
    42         #{:diff :full}     nil
    [:diff]    #{:diff :full}     nil
    {:k :diff} #{:diff :full}     nil))

(deftest safe-keyword-disallowed-string-returns-nil-and-does-not-intern
  ;; The load-bearing contract: a string outside the allowlist MUST
  ;; NOT intern a fresh JVM keyword. We probe `find-keyword` after
  ;; the rejection — if it returns nil, no intern happened. Pick a
  ;; near-random name to avoid colliding with any literal in source.
  (let [novel-name "rf2-ih7g4-novel-keyword-name-do-not-intern"]
    (is (nil? (find-keyword novel-name))
        "precondition: the novel name is not in the keyword table")
    (is (nil? (rf.mcp-base.args/safe-keyword novel-name #{:diff :full})))
    (is (nil? (find-keyword novel-name))
        "safe-keyword MUST NOT intern a fresh keyword on rejection — DoS gate")))

(deftest safe-keyword-disallowed-NAMESPACED-string-returns-nil-and-does-not-intern
  ;; The companion no-intern pin
  ;; (`safe-keyword-disallowed-string-returns-nil-and-does-not-intern`)
  ;; exercises the BARE-name arm (`find-keyword name-part`). The
  ;; NAMESPACED arm (`find-keyword ns-part name-part`) is a distinct
  ;; branch in `normalise-keyword-string` → `safe-keyword`, and it is
  ;; the branch the registry-backed frame-id / `:rf.assert/*` coercions
  ;; actually hit — those keys are namespaced. The DoS-gate guarantee
  ;; (a rejected agent string MUST NOT intern a fresh JVM keyword) has
  ;; to hold on the namespaced arm too, or every arbitrary `"ns/name"` an
  ;; agent sends costs a JVM keyword intern.
  (let [novel-ns   "rf2-ynjts-novel-ns-do-not-intern"
        novel-name "rf2-ynjts-novel-name-do-not-intern"
        novel-kw   "rf2-ynjts-novel-ns-do-not-intern/rf2-ynjts-novel-name-do-not-intern"]
    (is (nil? (find-keyword novel-ns novel-name))
        "precondition: the novel namespaced keyword is not in the table")
    (is (nil? (rf.mcp-base.args/safe-keyword novel-kw #{:rf/foo :rf/bar}))
        "out-of-allowlist namespaced string ⇒ nil")
    (is (nil? (rf.mcp-base.args/safe-keyword (str ":" novel-kw) #{:rf/foo :rf/bar}))
        "leading-colon form also rejected")
    (is (nil? (find-keyword novel-ns novel-name))
        "safe-keyword MUST NOT intern a fresh NAMESPACED keyword on rejection — DoS gate")))

;; ---------------------------------------------------------------------------
;; parse-mode no-intern on rejection.
;; ---------------------------------------------------------------------------

(deftest parse-mode-unknown-string-does-not-intern
  ;; Regression pin: `parse-mode` routes string input so the membership
  ;; check happens BEFORE any intern. A rejected string MUST leave the
  ;; keyword table untouched — routing through an interning helper first
  ;; and membership-checking after would grow the table on every typo.
  (let [novel-name "rf2-ih7g4-parse-mode-novel-name-do-not-intern"]
    (is (nil? (find-keyword novel-name))
        "precondition: the novel name is not in the keyword table")
    (is (= :diff (rf.mcp-base.args/parse-mode novel-name :diff #{:diff :full})))
    (is (nil? (find-keyword novel-name))
        "parse-mode MUST NOT intern a fresh keyword for an out-of-allowlist input")))
