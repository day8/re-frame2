(ns re-frame2-pair-mcp.args-test
  "Unit tests for the table-driven boolean-arg parser (rf2-c4fmh).

  Pins the four boolean MCP args shared across re-frame2-pair-mcp tools to the
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
            [re-frame2-pair-mcp.tools.args :as args]))

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
  ;; The catalogued arg keys; their default postures are the cross-MCP
  ;; convention. Drift here = drift across consumers. `:include-values`
  ;; was added by rf2-qicji for the reactive `list-subscriptions` tool;
  ;; `:drain` / `:stop` by rf2-zo4b9 for `read-recording`.
  (is (= #{:dedup :elision :cache :include-sensitive :include-values
           :drain :stop}
         (set (keys args/bool-args))))
  (is (true?  (get-in args/bool-args [:dedup             :default])))
  (is (true?  (get-in args/bool-args [:elision           :default])))
  (is (false? (get-in args/bool-args [:cache             :default])))
  (is (false? (get-in args/bool-args [:include-sensitive :default])))
  (is (false? (get-in args/bool-args [:include-values    :default])))
  (is (false? (get-in args/bool-args [:drain             :default])))
  (is (false? (get-in args/bool-args [:stop              :default]))))

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

;; ---------------------------------------------------------------------------
;; read-edn-arg — the [:ok parsed] / [:err reason] EDN-arg helper
;; (rf2-jkake.19). Factored out of reset-frame-db (:db),
;; restore-epoch (:epoch-id) and handler-meta (:id); each passes its own
;; per-tool reason keywords so the error envelope stays specific. The
;; three outcomes (missing / invalid / ok) were only exercised
;; indirectly via the conformance corpus; pin the helper directly so a
;; regression surfaces at the unit it lives in (rf2-ynjts.19).
;; ---------------------------------------------------------------------------

(deftest read-edn-arg-absent-returns-missing-reason
  ;; nil / blank value yields the caller's `missing` reason keyword.
  (is (= [:err :missing-db] (args/read-edn-arg nil :missing-db :invalid-db)))
  (is (= [:err :missing-db] (args/read-edn-arg "" :missing-db :invalid-db)))
  (is (= [:err :missing-db] (args/read-edn-arg "   " :missing-db :invalid-db))))

(deftest read-edn-arg-unreadable-returns-invalid-reason
  ;; Unbalanced delimiters → the caller's `invalid` reason keyword, NOT
  ;; the missing one (the discriminator is read-string success/failure).
  (is (= [:err :invalid-db] (args/read-edn-arg "{:k" :missing-db :invalid-db)))
  (is (= [:err :invalid-db] (args/read-edn-arg "(((" :missing-db :invalid-db))))

(deftest read-edn-arg-parses-valid-edn
  ;; A readable value rides back under [:ok parsed] with the EDN shape
  ;; preserved — maps, vectors, scalars, keywords.
  (is (= [:ok {:counter 0}] (args/read-edn-arg "{:counter 0}" :missing :invalid)))
  (is (= [:ok 7] (args/read-edn-arg "7" :missing :invalid)))
  (is (= [:ok 7] (args/read-edn-arg "  7  " :missing :invalid))
      "leading/trailing whitespace trimmed before read")
  (is (= [:ok :user/login] (args/read-edn-arg ":user/login" :missing :invalid)))
  (is (= [:ok [:a :b 0]] (args/read-edn-arg "[:a :b 0]" :missing :invalid))))

(deftest read-edn-arg-reason-keywords-are-caller-specific
  ;; Each consumer passes distinct reason keywords; the helper forwards
  ;; them verbatim so the envelope stays per-tool specific (the whole
  ;; point of taking them as args rather than hard-coding).
  (is (= [:err :missing-epoch-id]
         (args/read-edn-arg nil :missing-epoch-id :invalid-epoch-id-edn)))
  (is (= [:err :invalid-epoch-id-edn]
         (args/read-edn-arg "{:unterminated" :missing-epoch-id :invalid-epoch-id-edn))))

(deftest read-edn-arg-reads-only-the-first-form
  ;; read-string reads exactly ONE form and stops — trailing tokens are
  ;; NOT a parse error. `"nope("` reads as the symbol `nope` (the
  ;; dangling `(` is never consumed), so the helper reports [:ok nope],
  ;; not an :invalid error. Pinning this documents the read-string
  ;; contract the helper inherits: only an UNTERMINATED first form
  ;; (`"{:k"`, `"((("`) trips the :invalid arm.
  (is (= [:ok 'nope] (args/read-edn-arg "nope(" :missing :invalid))))

;; ---------------------------------------------------------------------------
;; parse-filter-arg — the streaming filter map (rf2-hq49). The nil /
;; string / map arms are covered in subscribe_test; the `:else` arm
;; (a JS object that is neither a string nor a CLJS map — the shape an
;; MCP host sends a structured filter as) routes through
;; `js->clj :keywordize-keys true` and was untested. Pin it (rf2-ynjts.19).
;; ---------------------------------------------------------------------------

(deftest parse-filter-arg-js-object-keywordizes
  (let [obj #js {"op-type" "error" "frame" "rf/default"}
        out (args/parse-filter-arg obj)]
    (is (= {:op-type "error" :frame "rf/default"} out)
        "JS-object keys keywordized; values left as-is")))

(deftest parse-filter-arg-nested-js-object-keywordizes-deep
  ;; `:keywordize-keys true` recurses — a nested JS object's keys are
  ;; keywordized too.
  (let [obj #js {"touches-path" #js ["cart" "items"]
                 "meta" #js {"min-ms" 50}}
        out (args/parse-filter-arg obj)]
    (is (= {:touches-path ["cart" "items"] :meta {:min-ms 50}} out))))
