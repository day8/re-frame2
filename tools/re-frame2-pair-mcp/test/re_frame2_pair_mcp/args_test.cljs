(ns re-frame2-pair-mcp.args-test
  "Unit tests for the table-driven boolean-arg parser.

  Pins the four boolean MCP args shared across re-frame2-pair-mcp tools to the
  single `args/bool-args` table:

    :dedup             ⇒ default true
    :elision           ⇒ default true
    :cache             ⇒ default false
    :include-sensitive ⇒ default false (wire-key carries no `?`)

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
  ;; serves the reactive `list-subscriptions` tool; `:drain` / `:stop`
  ;; serve `read-recording`; `:include-fx-args` serves `dispatch-dry-run`'s
  ;; fail-closed :would-fire-effects[*].args.
  (is (= #{:dedup :elision :cache :include-sensitive :include-fx-args
           :include-values :drain :stop}
         (set (keys args/bool-args))))
  (is (true?  (get-in args/bool-args [:dedup             :default])))
  (is (true?  (get-in args/bool-args [:elision           :default])))
  (is (false? (get-in args/bool-args [:cache             :default])))
  (is (false? (get-in args/bool-args [:include-sensitive :default])))
  (is (false? (get-in args/bool-args [:include-fx-args   :default])))
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
  ;; The unified table delegates to `base-args/parse-boolean` for every
  ;; key, so `"yes"` flips on uniformly across all four args.
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
  ;; The keyword carries NO trailing `?`; the JS wire key is
  ;; `"include-sensitive"`. The name coercion in `parse-bool-arg`
  ;; round-trips the literal `(name k)` correctly — pin the contract so
  ;; a future drift either way (adding `?`, snake_case, etc.) breaks
  ;; here. Per Anthropic's tool-input-schema regex
  ;; `^[a-zA-Z0-9_.-]{1,64}$`, predicate-style `?` is rejected at the
  ;; agent host.
  (let [a (args-js {:include-sensitive "true"})]
    (is (true? (args/parse-bool-arg a :include-sensitive)))))

;; ---------------------------------------------------------------------------
;; read-edn-arg — the [:ok parsed] / [:err reason] EDN-arg helper.
;; Shared by replace-app-db (:db), restore-epoch (:epoch-id) and
;; handler-meta (:id); each passes its own per-tool reason keywords so
;; the error envelope stays specific. The three outcomes (missing /
;; invalid / ok) are pinned directly here so a regression surfaces at
;; the unit it lives in.
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
;; parse-filter-arg — the streaming filter map. Returns the tagged
;; `[:ok m]` / `[:err :invalid-filter-edn]` shape (mirroring
;; `read-edn-arg`) so a bad filter EDN surfaces as an honest
;; `:ok? false` error rather than a nonsense filter the runtime applies.
;; The nil / string / map arms are covered in subscribe_test; the
;; `:else` arm (a JS object that is neither a string nor a CLJS map —
;; the shape an MCP host sends a structured filter as) routes through
;; `js->clj :keywordize-keys true` and is pinned here.
;; ---------------------------------------------------------------------------

(deftest parse-filter-arg-js-object-keywordizes
  (let [obj #js {"op-type" "error" "frame" "rf/default"}
        out (args/parse-filter-arg obj)]
    (is (= [:ok {:op-type "error" :frame "rf/default"}] out)
        "JS-object keys keywordized; values left as-is; wrapped in :ok tag")))

(deftest parse-filter-arg-nested-js-object-keywordizes-deep
  ;; `:keywordize-keys true` recurses — a nested JS object's keys are
  ;; keywordized too.
  (let [obj #js {"touches-path" #js ["cart" "items"]
                 "meta" #js {"min-ms" 50}}
        out (args/parse-filter-arg obj)]
    (is (= [:ok {:touches-path ["cart" "items"] :meta {:min-ms 50}}] out))))

(deftest parse-filter-arg-malformed-edn-is-err-tagged
  ;; Regression: an unparseable EDN string must NOT
  ;; become a `{:invalid-filter-edn raw}` map that rides into the runtime
  ;; `:filter` slot. It returns the honest `[:err :invalid-filter-edn]`
  ;; tag (mirroring read-edn-arg's :invalid arm) so the caller can
  ;; short-circuit to an `:ok? false` envelope.
  (is (= [:err :invalid-filter-edn]
         (args/parse-filter-arg "(((")) ;; unbalanced delimiters
      "unparseable filter EDN → honest :err tag, not a nonsense map"))

;; ---------------------------------------------------------------------------
;; parse-timeout-arg — positive-millisecond deadline knobs.
;;
;; `tail-build :wait-ms` and `eval-cljs` / `dispatch :await-render`
;; `:timeout-ms` thread their value straight into an `(>= elapsed
;; deadline)` poll comparison. A non-numeric value made `(>= n NaN)`
;; never true ⇒ unbounded background polling; a zero / negative value
;; timed out immediately. These deadlines must be POSITIVE-ms integers
;; (>= 1); 0 is NOT an unbounded sentinel here (unlike subscribe max-ms).
;; ---------------------------------------------------------------------------

(deftest parse-timeout-arg-absent-is-ok-nil
  (is (= [:ok nil] (args/parse-timeout-arg "wait-ms" nil))
      "absent ⇒ caller falls back to the documented default"))

(deftest parse-timeout-arg-accepts-positive-integer
  (is (= [:ok 5000] (args/parse-timeout-arg "timeout-ms" 5000)))
  (is (= [:ok 1]    (args/parse-timeout-arg "wait-ms" 1))))

(deftest parse-timeout-arg-accepts-numeric-string
  (is (= [:ok 250] (args/parse-timeout-arg "timeout-ms" "250"))
      "MCP hosts sometimes stringify numbers"))

(deftest parse-timeout-arg-rejects-non-numeric
  ;; Regression: "never" / "bogus" must NOT slip through as a deadline
  ;; that makes `(>= elapsed NaN)` never true (unbounded poll).
  (let [[tag env] (args/parse-timeout-arg "wait-ms" "never")]
    (is (= :err tag) "a non-numeric deadline is rejected, not threaded raw")
    (is (= :invalid-numeric-arg (:reason env)))
    (is (= "wait-ms" (:arg env)))
    (is (= "never" (:given env))))
  (let [[tag _] (args/parse-timeout-arg "timeout-ms" "bogus")]
    (is (= :err tag))))

(deftest parse-timeout-arg-rejects-zero-and-negative
  (let [[tag-z _] (args/parse-timeout-arg "wait-ms" 0)
        [tag-n env] (args/parse-timeout-arg "timeout-ms" -100)]
    (is (= :err tag-z) "zero is not a meaningful wait/await deadline")
    (is (= :err tag-n) "a negative deadline would time out immediately / negative setTimeout")
    (is (re-find #"positive integer" (:hint env)))))

(deftest parse-timeout-arg-rejects-fractional
  (let [[tag _] (args/parse-timeout-arg "timeout-ms" 12.5)]
    (is (= :err tag) "a fractional millisecond is not a valid integer deadline")))

;; ---------------------------------------------------------------------------
;; fx-overrides parse — over JSON-MCP the override VALUE
;; arrives as a string. The documented colon-prefixed form coerces to a
;; keyword (so core honours it as an id-redirect); any other value is
;; rejected rather than silently falling through to the real fx.
;; ---------------------------------------------------------------------------

(deftest parse-fx-overrides-nil-and-absent
  (is (= [:ok nil] (args/parse-fx-overrides nil)) "absent ⇒ ok nil")
  (is (= [:ok nil] (args/parse-fx-overrides js/undefined)) "undefined ⇒ ok nil"))

(deftest parse-fx-overrides-colon-string-coerces-to-keyword
  (let [[tag m] (args/parse-fx-overrides #js {":http" ":stub-http"})]
    (is (= :ok tag))
    (is (= {:http :stub-http} m)
        "colon-prefixed target string ⇒ keyword id-redirect")))

(deftest parse-fx-overrides-multiple-targets
  (let [[tag m] (args/parse-fx-overrides #js {":http" ":stub-http" ":navigate" ":noop-nav"})]
    (is (= :ok tag))
    (is (= {:http :stub-http :navigate :noop-nav} m))))

(deftest parse-fx-overrides-null-value-is-noop-placeholder
  (let [[tag m] (args/parse-fx-overrides #js {":http" nil})]
    (is (= :ok tag))
    (is (= {:http nil} m) "null ⇒ documented no-op placeholder, not a reject")))

(deftest parse-fx-overrides-bare-string-rejected
  (let [[tag _] (args/parse-fx-overrides #js {":http" "stub-http"})]
    (is (= :err tag) "a non-colon string would silently fall through to the real fx")))

(deftest parse-fx-overrides-non-string-rejected
  (is (= :err (first (args/parse-fx-overrides #js {":http" 42}))) "number target rejected")
  (is (= :err (first (args/parse-fx-overrides #js {":http" true}))) "boolean target rejected"))

;; ---------------------------------------------------------------------------
;; `:rf/fn-override` sentinel (rf2-m7x0qb / Tool-Pair §Replay) — a recorded
;; `:fx-overrides` entry carrying the opaque marker
;; (`re-frame.router/serializable-fx-overrides`'s stand-in for a fn-valued
;; override the router could not serialize) makes the run UNREPLAYABLE under
;; :strict. It is structurally a well-formed colon-prefixed keyword, so
;; without this check it would silently coerce as if it were a genuine
;; fx-id redirect.
;; ---------------------------------------------------------------------------

(deftest parse-fx-overrides-fn-override-sentinel-fails-loud
  (let [[tag m] (args/parse-fx-overrides #js {":http" ":rf/fn-override"})]
    (is (= :err tag) "the opaque sentinel is never a valid redirect target")
    (is (= :rf.error/unreplayable-fx-override (:reason m)))
    (is (= :http (:target m)) "the offending fx-id is named in the error")))

(deftest parse-fx-overrides-fn-override-sentinel-among-other-valid-entries
  ;; The sentinel check applies PER-ENTRY — a map with one valid override
  ;; and one sentinel-carrying entry still rejects the whole map (never a
  ;; partial success that silently drops just the bad entry).
  (let [[tag _] (args/parse-fx-overrides #js {":http" ":stub-http" ":navigate" ":rf/fn-override"})]
    (is (= :err tag))))

;; ---------------------------------------------------------------------------
;; `parse-interceptor-overrides` (rf2-m7x0qb) — the ref-shaped sibling of
;; `parse-fx-overrides`. Keys/values are EITHER a bare colon-tolerant
;; keyword id OR a bracket-shaped EDN `"[id arg]"` string (a parameterized
;; ref); a `null` value is the documented remove sentinel.
;; ---------------------------------------------------------------------------

(deftest parse-interceptor-overrides-nil-and-absent
  (is (= [:ok nil] (args/parse-interceptor-overrides nil)) "absent ⇒ ok nil")
  (is (= [:ok nil] (args/parse-interceptor-overrides js/undefined)) "undefined ⇒ ok nil"))

(deftest parse-interceptor-overrides-bare-keyword-ref
  (let [[tag m] (args/parse-interceptor-overrides #js {":auth/required" ":story/skip-auth"})]
    (is (= :ok tag))
    (is (= {:auth/required :story/skip-auth} m)
        "colon-prefixed bare keyword key/value coerce to keyword refs")))

(deftest parse-interceptor-overrides-null-value-removes
  (let [[tag m] (args/parse-interceptor-overrides #js {":audit/record-event" nil})]
    (is (= :ok tag))
    (is (= {:audit/record-event nil} m)
        "null ⇒ the documented remove-this-interceptor sentinel")))

(deftest parse-interceptor-overrides-parameterized-ref-key-and-value
  ;; A JS object literal can only carry string keys, so a parameterized
  ;; [id arg] ref rides as its bracket-shaped EDN string.
  (let [[tag m] (args/parse-interceptor-overrides
                  #js {"[:rf.interceptor/path [:cart]]" "[:rf.interceptor/path [:cart :items]]"})]
    (is (= :ok tag))
    (is (= {[:rf.interceptor/path [:cart]] [:rf.interceptor/path [:cart :items]]} m)
        "bracket-shaped EDN strings coerce to [id arg] 2-vector refs")))

(deftest parse-interceptor-overrides-multiple-entries
  (let [[tag m] (args/parse-interceptor-overrides
                  #js {":auth/required" ":story/skip-auth"
                       ":audit/record-event" nil})]
    (is (= :ok tag))
    (is (= {:auth/required :story/skip-auth :audit/record-event nil} m))))

(deftest parse-interceptor-overrides-bare-no-colon-key-and-value-tolerated
  ;; "colon-tolerant" per the bead: a bare name WITHOUT a leading colon is
  ;; ALSO a valid keyword id, mirroring `->frame-keyword`'s tolerance (NOT
  ;; `parse-fx-overrides`'s stricter colon-REQUIRED contract, which exists
  ;; for a distinct reason — see that fn's docstring).
  (let [[tag m] (args/parse-interceptor-overrides #js {"auth/required" "story/skip-auth"})]
    (is (= :ok tag))
    (is (= {:auth/required :story/skip-auth} m)
        "bare (no leading colon) key/value still coerce to keyword refs")))

(deftest parse-interceptor-overrides-blank-value-rejected
  ;; An empty/blank string is not a valid keyword id — `fresh-keyword`
  ;; returns nil for blank input, which `interceptor-ref-token` maps to
  ;; `::invalid` rather than silently dropping or coercing to a bogus
  ;; keyword.
  (let [[tag m] (args/parse-interceptor-overrides #js {":auth/required" "   "})]
    (is (= :err tag))
    (is (= :rf.error/interceptor-override-invalid (:reason m)))))

(deftest parse-interceptor-overrides-non-string-value-rejected
  (is (= :err (first (args/parse-interceptor-overrides #js {":auth/required" 42})))
      "number replacement rejected")
  (is (= :err (first (args/parse-interceptor-overrides #js {":auth/required" true})))
      "boolean replacement rejected"))

(deftest parse-interceptor-overrides-malformed-bracket-string-rejected
  ;; An unreadable / wrong-shaped bracket string is rejected, not
  ;; silently treated as a bare string id.
  (is (= :err (first (args/parse-interceptor-overrides #js {"[:bad" ":story/skip-auth"})))
      "unreadable bracket EDN rejected")
  (is (= :err (first (args/parse-interceptor-overrides #js {"[:a :b :c]" ":story/skip-auth"})))
      "a 3-vector isn't a valid [id arg] ref")
  (is (= :err (first (args/parse-interceptor-overrides #js {"[1 2]" ":story/skip-auth"})))
      "a non-keyword-headed vector isn't a valid ref"))

(deftest parse-interceptor-overrides-not-an-object-rejected
  (let [[tag m] (args/parse-interceptor-overrides "not-an-object")]
    (is (= :err tag))
    (is (= :rf.error/interceptor-override-invalid (:reason m)))))
