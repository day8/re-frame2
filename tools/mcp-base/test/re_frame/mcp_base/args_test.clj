(ns re-frame.mcp-base.args-test
  "Tests for the shared MCP argument-coercion helpers (rf2-vw4sq)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.mcp-base.args :as args]))

;; ---------------------------------------------------------------------------
;; parse-boolean
;; ---------------------------------------------------------------------------

(deftest parse-boolean-passthrough
  (is (true? (args/parse-boolean true false)))
  (is (false? (args/parse-boolean false true))))

(deftest parse-boolean-nil-returns-default
  (is (true? (args/parse-boolean nil true)))
  (is (false? (args/parse-boolean nil false))))

(deftest parse-boolean-recognises-truthy-strings
  (is (true? (args/parse-boolean "true" false)))
  (is (true? (args/parse-boolean "TRUE" false)))
  (is (true? (args/parse-boolean "1" false)))
  (is (true? (args/parse-boolean "yes" false)))
  (is (true? (args/parse-boolean "on" false))))

(deftest parse-boolean-recognises-falsy-strings
  (is (false? (args/parse-boolean "false" true)))
  (is (false? (args/parse-boolean "0" true)))
  (is (false? (args/parse-boolean "no" true)))
  (is (false? (args/parse-boolean "off" true))))

(deftest parse-boolean-recognises-keywords
  (is (true? (args/parse-boolean :true false)))
  (is (false? (args/parse-boolean :false true))))

(deftest parse-boolean-unrecognised-falls-back
  (is (true? (args/parse-boolean "maybe" true)))
  (is (false? (args/parse-boolean "maybe" false)))
  (is (true? (args/parse-boolean 42 true))))

;; ---------------------------------------------------------------------------
;; parse-positive-int
;; ---------------------------------------------------------------------------

(deftest parse-positive-int-passes-through-ints
  (is (= 5 (args/parse-positive-int 5 50)))
  (is (= 100 (args/parse-positive-int 100 50))))

(deftest parse-positive-int-nil-returns-default
  (is (= 50 (args/parse-positive-int nil 50))))

(deftest parse-positive-int-clamps-non-positive
  (is (= 1 (args/parse-positive-int 0 50)))
  (is (= 1 (args/parse-positive-int -5 50))))

(deftest parse-positive-int-parses-strings
  (is (= 12 (args/parse-positive-int "12" 50)))
  (is (= 1 (args/parse-positive-int "0" 50))))

(deftest parse-positive-int-non-numeric-string-falls-back
  (is (= 50 (args/parse-positive-int "abc" 50)))
  (is (= 50 (args/parse-positive-int "" 50))))

;; ---------------------------------------------------------------------------
;; parse-non-negative-int
;; ---------------------------------------------------------------------------

(deftest parse-non-negative-int-admits-zero
  (is (zero? (args/parse-non-negative-int 0 5000)))
  (is (zero? (args/parse-non-negative-int "0" 5000))))

(deftest parse-non-negative-int-clamps-negative-to-zero
  (is (zero? (args/parse-non-negative-int -5 5000))))

;; ---------------------------------------------------------------------------
;; Cross-host strict-parse contract (rf2-ee38b.19).
;;
;; Pin the trailing-garbage case that previously diverged: JVM
;; `Long/parseLong` threw on a non-numeric tail and fell back to the
;; default, while raw CLJS `js/parseInt` parsed a numeric PREFIX
;; (`"12abc"` ⇒ 12). The strict `int-string-re` guard makes both hosts
;; fall back to `default`. The mirror CLJS assertions live in
;; `re-frame.mcp-base.cljs-branches-cljs-test` so the same expectations
;; are pinned on both platforms.
;; ---------------------------------------------------------------------------

(deftest parse-positive-int-rejects-trailing-garbage
  (is (= 50 (args/parse-positive-int "12abc" 50))
      "trailing garbage falls back to default (was 12 on CLJS before the fix)")
  (is (= 50 (args/parse-positive-int "5xyz" 50)))
  (is (= 50 (args/parse-positive-int "12 34" 50)) "internal whitespace rejected")
  (is (= 50 (args/parse-positive-int "0x10" 50)) "hex-prefixed string rejected")
  (is (= 50 (args/parse-positive-int "1.5" 50)) "decimal string rejected")
  (is (= 50 (args/parse-positive-int "1e3" 50)) "scientific notation rejected"))

(deftest parse-positive-int-accepts-clean-and-signed
  (is (= 12 (args/parse-positive-int "12" 50)))
  (is (= 12 (args/parse-positive-int "  12  " 50)) "surrounding whitespace trimmed")
  (is (= 12 (args/parse-positive-int "+12" 50)) "leading plus accepted")
  (is (= 1 (args/parse-positive-int "-5" 50)) "negative parses then clamps to floor"))

(deftest parse-non-negative-int-rejects-trailing-garbage
  (is (= 5000 (args/parse-non-negative-int "12abc" 5000)))
  (is (= 5000 (args/parse-non-negative-int "100x" 5000))))

(deftest parse-positive-int-rejects-out-of-long-range
  ;; A digit string that overflows a JVM long is a parse failure →
  ;; default. The CLJS arm mirrors this via Number.isSafeInteger so the
  ;; two hosts agree on the rejection (not a lossy truncation).
  (is (= 50 (args/parse-positive-int "99999999999999999999999999" 50))))

;; ---------------------------------------------------------------------------
;; fresh-keyword — positive-named intern for operator-gated write paths
;; (rf2-xxtrz, the successor to the retired `parse-keyword`).
;; ---------------------------------------------------------------------------

(deftest fresh-keyword-passes-through-keywords
  (is (= :foo (args/fresh-keyword :foo)))
  (is (= :ns/foo (args/fresh-keyword :ns/foo))))

(deftest fresh-keyword-nil-returns-nil
  (is (nil? (args/fresh-keyword nil))))

(deftest fresh-keyword-strips-leading-colon
  (is (= :foo (args/fresh-keyword ":foo")))
  (is (= :ns/foo (args/fresh-keyword ":ns/foo"))))

(deftest fresh-keyword-parses-namespaced
  (is (= :rf.assert/path-equals (args/fresh-keyword "rf.assert/path-equals")))
  (is (= :rf.assert/path-equals (args/fresh-keyword ":rf.assert/path-equals"))))

(deftest fresh-keyword-blank-returns-nil
  (is (nil? (args/fresh-keyword "")))
  (is (nil? (args/fresh-keyword ":"))))

(deftest fresh-keyword-rejects-non-string-non-keyword-input
  ;; The contract is "agent-supplied id" — anything other than the two
  ;; admitted shapes (string, keyword) returns nil rather than coercing.
  (is (nil? (args/fresh-keyword 42)))
  (is (nil? (args/fresh-keyword [:foo])))
  (is (nil? (args/fresh-keyword {:k :v}))))

(deftest fresh-keyword-interns-on-fresh-input
  ;; The defining contract: `fresh-keyword` INTERNS by design (the call
  ;; site is allocating a new identifier rather than resolving an
  ;; existing one). Pin the intern so a future refactor that swaps the
  ;; body for a `safe-keyword`-only path trips this gate before
  ;; reaching the operator-gated write callers (story-mcp's
  ;; register-variant, record-as-variant).
  (let [novel-name "rf2-xxtrz-fresh-keyword-intern-pin"]
    (is (nil? (find-keyword novel-name))
        "precondition: the novel name is not in the keyword table")
    (is (= (keyword novel-name) (args/fresh-keyword novel-name)))
    (is (some? (find-keyword novel-name))
        "fresh-keyword MUST intern — that's the entire point of the primitive")))

;; ---------------------------------------------------------------------------
;; parse-mode
;; ---------------------------------------------------------------------------

(deftest parse-mode-recognised-keyword
  (is (= :diff (args/parse-mode :diff :diff #{:diff :full})))
  (is (= :full (args/parse-mode :full :diff #{:diff :full}))))

(deftest parse-mode-recognised-string
  (is (= :diff (args/parse-mode "diff" :diff #{:diff :full})))
  (is (= :full (args/parse-mode "full" :diff #{:diff :full}))))

(deftest parse-mode-strips-leading-colon
  ;; Regression pin (rf2-wnyy9 / round-2 F2): `parse-mode` must accept
  ;; agent-supplied `":diff"` the same way the read path accepts
  ;; `":foo"`. Before the fix this silently default-fell-back — the
  ;; agent saw `:diff` returned but the value was the function's
  ;; default, not a recognised match.
  (is (= :diff (args/parse-mode ":diff" :full #{:diff :full})))
  (is (= :full (args/parse-mode ":full" :diff #{:diff :full})))
  (is (= :rf/foo (args/parse-mode ":rf/foo" :default #{:rf/foo :rf/bar}))
      "namespaced keywords also strip the leading colon"))

(deftest parse-mode-nil-returns-default
  (is (= :diff (args/parse-mode nil :diff #{:diff :full}))))

(deftest parse-mode-unrecognised-returns-default
  (is (= :diff (args/parse-mode "maybe" :diff #{:diff :full})))
  (is (= :diff (args/parse-mode :unknown :diff #{:diff :full}))))

;; ---------------------------------------------------------------------------
;; safe-keyword — bounded-allowlist gate (rf2-ih7g4).
;; ---------------------------------------------------------------------------

(deftest safe-keyword-allowed-keyword-passes
  (is (= :diff (args/safe-keyword :diff #{:diff :full})))
  (is (= :rf/foo (args/safe-keyword :rf/foo #{:rf/foo :rf/bar}))))

(deftest safe-keyword-disallowed-keyword-returns-nil
  ;; The keyword exists (literal in source), but the membership check
  ;; rejects it.
  (is (nil? (args/safe-keyword :other #{:diff :full})))
  (is (nil? (args/safe-keyword :rf/baz #{:rf/foo :rf/bar}))))

(deftest safe-keyword-allowed-string-resolves
  (is (= :diff (args/safe-keyword "diff" #{:diff :full})))
  (is (= :diff (args/safe-keyword ":diff" #{:diff :full})))
  (is (= :rf/foo (args/safe-keyword "rf/foo" #{:rf/foo :rf/bar})))
  (is (= :rf/foo (args/safe-keyword ":rf/foo" #{:rf/foo :rf/bar}))))

(deftest safe-keyword-disallowed-string-returns-nil-and-does-not-intern
  ;; The load-bearing contract: a string outside the allowlist MUST
  ;; NOT intern a fresh JVM keyword. We probe `find-keyword` after
  ;; the rejection — if it returns nil, no intern happened. Pick a
  ;; near-random name to avoid colliding with any literal in source.
  (let [novel-name "rf2-ih7g4-novel-keyword-name-do-not-intern"]
    (is (nil? (find-keyword novel-name))
        "precondition: the novel name is not in the keyword table")
    (is (nil? (args/safe-keyword novel-name #{:diff :full})))
    (is (nil? (find-keyword novel-name))
        "safe-keyword MUST NOT intern a fresh keyword on rejection — DoS gate")))

(deftest safe-keyword-blank-and-nil-input-returns-nil
  (is (nil? (args/safe-keyword nil #{:diff :full})))
  (is (nil? (args/safe-keyword "" #{:diff :full})))
  (is (nil? (args/safe-keyword ":" #{:diff :full}))))

(deftest safe-keyword-disallowed-NAMESPACED-string-returns-nil-and-does-not-intern
  ;; Coverage gap (rf2-ynjts.17): the existing no-intern pin
  ;; (`safe-keyword-disallowed-string-returns-nil-and-does-not-intern`)
  ;; exercises only the BARE-name arm (`find-keyword name-part`). The
  ;; NAMESPACED arm (`find-keyword ns-part name-part`) is a distinct
  ;; branch in `normalise-keyword-string` → `safe-keyword`, and it is
  ;; the branch the registry-backed frame-id / `:rf.assert/*` coercions
  ;; actually hit — those keys are namespaced. The DoS-gate guarantee
  ;; (a rejected agent string MUST NOT intern a fresh JVM keyword) has
  ;; to hold on the namespaced arm too, or the never-shrinking keyword
  ;; table grows one slot per arbitrary `"ns/name"` an agent sends.
  (let [novel-ns   "rf2-ynjts-novel-ns-do-not-intern"
        novel-name "rf2-ynjts-novel-name-do-not-intern"
        novel-kw   "rf2-ynjts-novel-ns-do-not-intern/rf2-ynjts-novel-name-do-not-intern"]
    (is (nil? (find-keyword novel-ns novel-name))
        "precondition: the novel namespaced keyword is not in the table")
    (is (nil? (args/safe-keyword novel-kw #{:rf/foo :rf/bar}))
        "out-of-allowlist namespaced string ⇒ nil")
    (is (nil? (args/safe-keyword (str ":" novel-kw) #{:rf/foo :rf/bar}))
        "leading-colon form also rejected")
    (is (nil? (find-keyword novel-ns novel-name))
        "safe-keyword MUST NOT intern a fresh NAMESPACED keyword on rejection — DoS gate")))

(deftest safe-keyword-resolves-pre-interned-namespaced-keyword
  ;; The positive companion: a namespaced keyword that DOES exist in the
  ;; allowlist (and was therefore interned at allowlist-definition time)
  ;; resolves from its string form via the namespaced `find-keyword`
  ;; arm. Pins that the namespaced arm isn't merely a rejection path —
  ;; it correctly returns the interned member when the input matches.
  (is (= :rf.assert/path-equals
         (args/safe-keyword "rf.assert/path-equals"
                            #{:rf.assert/path-equals :rf.assert/path-absent}))
      "an in-allowlist namespaced string resolves to its interned keyword")
  (is (= :rf.assert/path-equals
         (args/safe-keyword ":rf.assert/path-equals"
                            #{:rf.assert/path-equals :rf.assert/path-absent}))
      "leading-colon namespaced form resolves too"))

(deftest safe-keyword-non-keyword-non-string-input-returns-nil
  (is (nil? (args/safe-keyword 42 #{:diff :full})))
  (is (nil? (args/safe-keyword [:diff] #{:diff :full})))
  (is (nil? (args/safe-keyword {:k :diff} #{:diff :full}))))

;; ---------------------------------------------------------------------------
;; parse-mode no-intern on rejection (rf2-ih7g4).
;; ---------------------------------------------------------------------------

(deftest parse-mode-unknown-string-does-not-intern
  ;; Regression pin (rf2-ih7g4): `parse-mode` previously routed every
  ;; string input through an interning helper and then membership-
  ;; checked the result. With the fix, the membership check happens
  ;; BEFORE intern. A rejected string MUST leave the keyword table
  ;; untouched.
  (let [novel-name "rf2-ih7g4-parse-mode-novel-name-do-not-intern"]
    (is (nil? (find-keyword novel-name))
        "precondition: the novel name is not in the keyword table")
    (is (= :diff (args/parse-mode novel-name :diff #{:diff :full})))
    (is (nil? (find-keyword novel-name))
        "parse-mode MUST NOT intern a fresh keyword for an out-of-allowlist input")))
