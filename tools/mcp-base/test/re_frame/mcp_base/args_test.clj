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
;; parse-keyword
;; ---------------------------------------------------------------------------

(deftest parse-keyword-passes-through-keywords
  (is (= :foo (args/parse-keyword :foo)))
  (is (= :ns/foo (args/parse-keyword :ns/foo))))

(deftest parse-keyword-nil-returns-nil
  (is (nil? (args/parse-keyword nil))))

(deftest parse-keyword-strips-leading-colon
  (is (= :foo (args/parse-keyword ":foo")))
  (is (= :ns/foo (args/parse-keyword ":ns/foo"))))

(deftest parse-keyword-parses-namespaced
  (is (= :rf.assert/path-equals (args/parse-keyword "rf.assert/path-equals")))
  (is (= :rf.assert/path-equals (args/parse-keyword ":rf.assert/path-equals"))))

(deftest parse-keyword-blank-returns-nil
  (is (nil? (args/parse-keyword "")))
  (is (nil? (args/parse-keyword ":"))))

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
  ;; agent-supplied `":diff"` the same way `parse-keyword` accepts
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

(deftest safe-keyword-non-keyword-non-string-input-returns-nil
  (is (nil? (args/safe-keyword 42 #{:diff :full})))
  (is (nil? (args/safe-keyword [:diff] #{:diff :full})))
  (is (nil? (args/safe-keyword {:k :diff} #{:diff :full}))))

;; ---------------------------------------------------------------------------
;; parse-mode no-intern on rejection (rf2-ih7g4).
;; ---------------------------------------------------------------------------

(deftest parse-mode-unknown-string-does-not-intern
  ;; Regression pin (rf2-ih7g4): `parse-mode` previously routed every
  ;; string input through `parse-keyword` (which interns) and then
  ;; membership-checked the result. With the fix, the membership check
  ;; happens BEFORE intern. A rejected string MUST leave the keyword
  ;; table untouched.
  (let [novel-name "rf2-ih7g4-parse-mode-novel-name-do-not-intern"]
    (is (nil? (find-keyword novel-name))
        "precondition: the novel name is not in the keyword table")
    (is (= :diff (args/parse-mode novel-name :diff #{:diff :full})))
    (is (nil? (find-keyword novel-name))
        "parse-mode MUST NOT intern a fresh keyword for an out-of-allowlist input")))
