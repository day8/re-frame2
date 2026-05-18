(ns re-frame-pair2-mcp.args-test
  "Unit tests for the table-driven boolean-arg parser (rf2-c4fmh).

  Pins the four boolean MCP args shared across pair2-mcp tools to the
  single `args/bool-args` table:

    :dedup             ⇒ default true
    :elision           ⇒ default true
    :cache             ⇒ default false
    :include-sensitive ⇒ default false (rf2-ihq4d — wire-key drops `?`)

  Accept-shape coverage (true/false bools, `\"true\"`/`\"yes\"`/`\"1\"`
  string forms, `:true`/`:false` keywords, case-insensitivity,
  unrecognised-falls-back-to-default) lives in
  `re-frame.mcp-base.args-test/parse-boolean-*` — the cross-MCP base
  parser this wrapper delegates to. These tests verify the table lookup
  and the JS-args / nil handling on top."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [applied-science.js-interop :as j]
            [re-frame-pair2-mcp.tools.args :as args]))

(defn- args-js
  "Build a JS args object from a CLJS map. Keys are coerced to strings —
  the MCP wire ships JSON-object keys as strings."
  [m]
  (let [o #js {}]
    (doseq [[k v] m]
      (j/assoc! o (name k) v))
    o))

;; ---------------------------------------------------------------------------
;; Table — every boolean MCP arg + its default lives here.
;; ---------------------------------------------------------------------------

(deftest bool-args-table-shape
  ;; The four arg keys are catalogued; their default postures are the
  ;; cross-MCP convention. Drift here = drift across consumers.
  (is (= #{:dedup :elision :cache :include-sensitive}
         (set (keys args/bool-args))))
  (is (true?  (get-in args/bool-args [:dedup             :default])))
  (is (true?  (get-in args/bool-args [:elision           :default])))
  (is (false? (get-in args/bool-args [:cache             :default])))
  (is (false? (get-in args/bool-args [:include-sensitive :default]))))

;; ---------------------------------------------------------------------------
;; parse-bool-arg — table lookup + JS-args extraction.
;; ---------------------------------------------------------------------------

(deftest parse-bool-arg-absent-uses-table-default
  ;; The absent slot resolves to whatever the table says.
  (let [empty-args (args-js {})]
    (is (true?  (args/parse-bool-arg empty-args :dedup)))
    (is (true?  (args/parse-bool-arg empty-args :elision)))
    (is (false? (args/parse-bool-arg empty-args :cache)))
    (is (false? (args/parse-bool-arg empty-args :include-sensitive)))))

(deftest parse-bool-arg-nil-args-uses-table-default
  ;; A nil args object collapses to the table default — the dispatcher
  ;; can pass a missing args slot without a defensive guard.
  (is (true?  (args/parse-bool-arg nil :dedup)))
  (is (false? (args/parse-bool-arg nil :cache))))

(deftest parse-bool-arg-undefined-args-uses-table-default
  ;; JS undefined likewise collapses to the table default.
  (is (true?  (args/parse-bool-arg js/undefined :dedup)))
  (is (false? (args/parse-bool-arg js/undefined :cache))))

(deftest parse-bool-arg-explicit-boolean-overrides-default
  (let [on?  (args-js {:dedup false :cache true})
        off? (args-js {:dedup true  :cache false})]
    (is (false? (args/parse-bool-arg on?  :dedup)))
    (is (true?  (args/parse-bool-arg on?  :cache)))
    (is (true?  (args/parse-bool-arg off? :dedup)))
    (is (false? (args/parse-bool-arg off? :cache)))))

(deftest parse-bool-arg-string-forms-accepted-uniformly
  ;; Pre-rf2-c4fmh, `:cache "yes"` default-falsed because cache.cljs
  ;; hand-rolled a smaller parser. The unified table delegates to
  ;; `base-args/parse-boolean` for every key, so `"yes"` flips on
  ;; uniformly across all four args.
  (let [a (args-js {:dedup             "no"
                    :elision           "off"
                    :cache             "yes"
                    :include-sensitive "1"})]
    (is (false? (args/parse-bool-arg a :dedup)))
    (is (false? (args/parse-bool-arg a :elision)))
    (is (true?  (args/parse-bool-arg a :cache)))
    (is (true?  (args/parse-bool-arg a :include-sensitive)))))

(deftest parse-bool-arg-case-insensitive-strings
  (let [a (args-js {:cache "TRUE" :dedup "False"})]
    (is (true?  (args/parse-bool-arg a :cache)))
    (is (false? (args/parse-bool-arg a :dedup)))))

(deftest parse-bool-arg-keyword-forms-accepted
  (let [a (args-js {:cache :true :dedup :false})]
    (is (true?  (args/parse-bool-arg a :cache)))
    (is (false? (args/parse-bool-arg a :dedup)))))

(deftest parse-bool-arg-unrecognised-falls-back-to-table-default
  (let [a (args-js {:dedup "garbage" :cache "garbage"})]
    (is (true?  (args/parse-bool-arg a :dedup)))    ; default true
    (is (false? (args/parse-bool-arg a :cache)))))  ; default false

(deftest parse-bool-arg-include-sensitive-name-is-stringified
  ;; Post-rf2-ihq4d the keyword carries NO trailing `?`; the JS wire
  ;; key is `"include-sensitive"`. The name coercion in `parse-bool-arg`
  ;; round-trips the literal `(name k)` correctly — pin the contract so
  ;; a future drift either way (re-adding `?`, snake_case, etc.) breaks
  ;; here. Per Anthropic's tool-input-schema regex
  ;; `^[a-zA-Z0-9_.-]{1,64}$`, predicate-style `?` is rejected at the
  ;; agent host.
  (let [a (args-js {:include-sensitive "true"})]
    (is (true? (args/parse-bool-arg a :include-sensitive)))))
