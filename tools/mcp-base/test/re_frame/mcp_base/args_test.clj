(ns re-frame.mcp-base.args-test
  "Tests for the shared MCP argument-coercion helpers (rf2-vw4sq)."
  (:require [clojure.test :refer [deftest is testing]]
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
