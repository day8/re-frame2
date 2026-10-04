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
  (:require [cljs.test :refer-macros [deftest is]]
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

(deftest parse-bool-arg-nil-or-undefined-args-uses-table-default
  ;; A nil or JS-undefined args object collapses to the table default —
  ;; the dispatcher can pass a missing args slot without a defensive guard.
  (doseq [[label missing] [["nil" nil] ["undefined" js/undefined]]]
    (is (true?  (args/parse-bool-arg missing :dedup)) (str label " args"))
    (is (false? (args/parse-bool-arg missing :cache)) (str label " args"))))

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

;; ---------------------------------------------------------------------------
;; read-edn-arg — the [:ok parsed] / [:err reason] EDN-arg helper.
;; Shared by replace-app-db (:db), restore-epoch (:epoch-id) and
;; handler-meta (:id); each passes its own per-tool reason keywords so
;; the error envelope stays specific. The three outcomes (missing /
;; invalid / ok) are pinned directly here so a regression surfaces at
;; the unit it lives in.
;; ---------------------------------------------------------------------------

(deftest read-edn-arg-err-arms-forward-the-callers-reasons
  ;; nil / blank value yields the caller's `missing` reason keyword;
  ;; unbalanced delimiters yield the caller's `invalid` one, NOT the
  ;; missing one (the discriminator is read-string success/failure). Each
  ;; consumer passes distinct reason keywords and the helper forwards them
  ;; verbatim, so the envelope stays per-tool specific (the whole point of
  ;; taking them as args rather than hard-coding).
  (doseq [[raw missing invalid expected]
          [[nil :missing-db :invalid-db [:err :missing-db]]
           ["" :missing-db :invalid-db [:err :missing-db]]
           ["   " :missing-db :invalid-db [:err :missing-db]]
           ["{:k" :missing-db :invalid-db [:err :invalid-db]]
           ["(((" :missing-db :invalid-db [:err :invalid-db]]
           [nil :missing-epoch-id :invalid-epoch-id-edn [:err :missing-epoch-id]]
           ["{:unterminated" :missing-epoch-id :invalid-epoch-id-edn [:err :invalid-epoch-id-edn]]]]
    (is (= expected (args/read-edn-arg raw missing invalid)) (pr-str raw))))

(deftest read-edn-arg-parses-valid-edn
  ;; A readable value rides back under [:ok parsed] with the EDN shape
  ;; preserved — maps, vectors, scalars, keywords.
  (is (= [:ok {:counter 0}] (args/read-edn-arg "{:counter 0}" :missing :invalid)))
  (is (= [:ok 7] (args/read-edn-arg "7" :missing :invalid)))
  (is (= [:ok 7] (args/read-edn-arg "  7  " :missing :invalid))
      "leading/trailing whitespace trimmed before read")
  (is (= [:ok :user/login] (args/read-edn-arg ":user/login" :missing :invalid)))
  (is (= [:ok [:a :b 0]] (args/read-edn-arg "[:a :b 0]" :missing :invalid))))

(deftest read-edn-arg-reads-only-the-first-form
  ;; read-string reads exactly ONE form and stops — trailing tokens are
  ;; NOT a parse error. `"nope("` reads as the symbol `nope` (the
  ;; dangling `(` is never consumed), so the helper reports [:ok nope],
  ;; not an :invalid error. Pinning this documents the read-string
  ;; contract the helper inherits: only an UNTERMINATED first form
  ;; (`"{:k"`, `"((("`) trips the :invalid arm.
  (is (= [:ok 'nope] (args/read-edn-arg "nope(" :missing :invalid))))

;; ---------------------------------------------------------------------------
;; parse-event-arg — the shared event-vector parse seam.
;;
;; `dispatch` and `dispatch-dry-run` both route through
;; `args/parse-event-arg`, passing only their distinct missing-value hint. This is where the
;; EXHAUSTIVE parse matrix lives — nil / blank / unreadable / map / keyword
;; / list / symbol / scalar / vector inputs and the parsed-type
;; classification. The tool suites keep only the narrow integration
;; assertions (distinct hint + no-eval-on-error).
;;
;; The parser is the SECURITY GATE: a host-form source string
;; (`"(println :pwn)"`) reads as a list and is rejected as
;; `:not-an-event-vector` — never spliced into the runtime eval as code.
;; ---------------------------------------------------------------------------

(def ^:private dispatch-hint
  "usage: dispatch {event '[:ev/id ...]' [sync true] [trace true] [frame :foo] [fx-overrides {...}] [interceptor-overrides {...}]}")

(deftest parse-event-arg-missing-uses-caller-hint-verbatim
  ;; nil / blank / whitespace → :missing-event, and the caller-specific
  ;; hint rides through as DATA. This is the ONLY arm that consults the
  ;; hint — the distinct dispatch / dispatch-dry-run usage strings differ
  ;; here and nowhere else.
  (doseq [blank [nil "" "   " "\t\n"]]
    (let [[tag env] (args/parse-event-arg blank dispatch-hint)]
      (is (= :err tag) (str (pr-str blank) " is a missing event"))
      (is (false? (:ok? env)))
      (is (= :missing-event (:reason env)))
      (is (= dispatch-hint (:hint env))
          "the caller's missing-value hint is threaded through verbatim"))))

(deftest parse-event-arg-unreadable-is-invalid-event-edn
  ;; A reader failure (unbalanced brackets, lone reader macro) → the
  ;; :invalid-event-edn reason, distinct from :missing / :not-a-vector.
  (doseq [bad ["[:foo" "{:k" "#" "(((" "]"]]
    (let [[tag env] (args/parse-event-arg bad dispatch-hint)]
      (is (= :err tag) (str (pr-str bad) " fails to read"))
      (is (= :invalid-event-edn (:reason env)))
      (is (= bad (:event env)) "the offending raw string is echoed for a corrective retry"))))

(deftest parse-event-arg-non-vector-shapes-classified
  ;; Valid EDN of the WRONG shape → :not-an-event-vector with a
  ;; :parsed-type classification the agent can act on. Host-form source is
  ;; the headline case (`"(println :pwn)"` reads as a list) — the security
  ;; gate that keeps arbitrary CLJS out of the runtime eval.
  (doseq [[in kind] [["{:id :foo}"       :map]
                     [":cart/checkout"   :keyword]
                     ["(println :pwn)"   :list]
                     ["some-symbol"      :symbol]
                     ["42"               :scalar]
                     ["\"a string\""     :scalar]]]
    (let [[tag env] (args/parse-event-arg in dispatch-hint)]
      (is (= :err tag) (str (pr-str in) " is not a vector"))
      (is (= :not-an-event-vector (:reason env)))
      (is (= kind (:parsed-type env)) (str (pr-str in) " classified as " kind))
      (is (= in (:event env))))))

(deftest parse-event-arg-accepts-vectors
  ;; The happy path — a vector reads back as DATA (not source). Bare,
  ;; with-payload, and the empty vector all pass; whitespace is trimmed
  ;; before the read.
  (is (= [:ok [:cart/checkout]] (args/parse-event-arg "[:cart/checkout]" dispatch-hint)))
  (is (= [:ok [:cart/add {:sku "abc"}]]
         (args/parse-event-arg "[:cart/add {:sku \"abc\"}]" dispatch-hint)))
  (is (= [:ok []] (args/parse-event-arg "[]" dispatch-hint)))
  (is (= [:ok [:cart/checkout]] (args/parse-event-arg "  [:cart/checkout]  " dispatch-hint))
      "leading/trailing whitespace trimmed before read"))

(deftest parse-event-arg-non-missing-envelope-independent-of-hint
  ;; Byte-equivalence: for every NON-missing input the envelope is
  ;; IDENTICAL regardless of the missing-hint (the hint is consulted only
  ;; on the missing arm). This is precisely why dispatch and
  ;; dispatch-dry-run yield byte-equivalent envelopes for unreadable / map
  ;; / keyword / list / vector inputs even though they advertise different
  ;; usage hints.
  (doseq [in ["(println :pwn)" ":kw" "{:a 1}" "[:foo" "[:ok]" "42"]]
    (is (= (args/parse-event-arg in "hint-A")
           (args/parse-event-arg in "hint-B"))
        (str "envelope for " (pr-str in) " is independent of the missing-hint"))))

;; ---------------------------------------------------------------------------
;; parse-timeout-arg — positive-millisecond deadline knobs.
;;
;; `tail-build :wait-ms` and `eval-cljs` / `dispatch :await-render`
;; `:timeout-ms` thread their value straight into an `(>= elapsed
;; deadline)` poll comparison. A non-numeric value made `(>= n NaN)`
;; never true ⇒ unbounded background polling; a zero / negative value
;; timed out immediately. These deadlines must be POSITIVE-ms integers
;; (>= 1); 0 is NOT an unbounded sentinel here.
;; ---------------------------------------------------------------------------

(deftest parse-timeout-arg-accepts-absent-and-positive-integers
  (doseq [[label arg-name v expected]
          [["absent ⇒ caller falls back to the documented default" "wait-ms" nil [:ok nil]]
           ["a positive integer"                                   "timeout-ms" 5000 [:ok 5000]]
           ["the smallest deadline"                                "wait-ms" 1 [:ok 1]]
           ["MCP hosts sometimes stringify numbers"                "timeout-ms" "250" [:ok 250]]]]
    (is (= expected (args/parse-timeout-arg arg-name v)) label)))

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

(deftest parse-timeout-arg-rejects-zero-negative-and-fractional
  (doseq [[label arg-name v]
          [["zero is not a meaningful wait/await deadline"                      "wait-ms" 0]
           ["a negative deadline would time out immediately / negative setTimeout" "timeout-ms" -100]
           ["a fractional millisecond is not a valid integer deadline"           "timeout-ms" 12.5]]]
    (is (= :err (first (args/parse-timeout-arg arg-name v))) label))
  (is (re-find #"positive integer" (:hint (second (args/parse-timeout-arg "timeout-ms" -100))))))

;; ---------------------------------------------------------------------------
;; fx-overrides parse — over JSON-MCP the override VALUE
;; arrives as a string. The documented colon-prefixed form coerces to a
;; keyword (so core honours it as an id-redirect); any other value is
;; rejected rather than silently falling through to the real fx.
;; ---------------------------------------------------------------------------

(deftest parse-fx-overrides-nil-and-absent
  (is (= [:ok nil] (args/parse-fx-overrides nil)) "absent ⇒ ok nil")
  (is (= [:ok nil] (args/parse-fx-overrides js/undefined)) "undefined ⇒ ok nil"))

(deftest parse-fx-overrides-coerces-colon-string-targets-to-keywords
  (doseq [[label o expected]
          [["colon-prefixed target string ⇒ keyword id-redirect"
            #js {":http" ":stub-http"} {:http :stub-http}]
           ["every entry of a multi-target map coerces"
            #js {":http" ":stub-http" ":navigate" ":noop-nav"} {:http :stub-http :navigate :noop-nav}]
           ["null ⇒ documented no-op placeholder, not a reject"
            #js {":http" nil} {:http nil}]]]
    (is (= [:ok expected] (args/parse-fx-overrides o)) label)))

(deftest parse-fx-overrides-rejects-non-colon-and-non-string-targets
  (doseq [[label o] [["a non-colon string would silently fall through to the real fx"
                      #js {":http" "stub-http"}]
                     ["number target rejected"  #js {":http" 42}]
                     ["boolean target rejected" #js {":http" true}]]]
    (is (= :err (first (args/parse-fx-overrides o))) label)))

;; ---------------------------------------------------------------------------
;; `:rf/fn-override` sentinel (Tool-Pair §Replay) — a recorded
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
;; `parse-interceptor-overrides` — the ref-shaped sibling of
;; `parse-fx-overrides`. Keys/values are EITHER a bare colon-tolerant
;; keyword id OR a bracket-shaped EDN `"[id arg]"` string (a parameterized
;; ref); a `null` value is the documented remove sentinel.
;; ---------------------------------------------------------------------------

(deftest parse-interceptor-overrides-nil-and-absent
  (is (= [:ok nil] (args/parse-interceptor-overrides nil)) "absent ⇒ ok nil")
  (is (= [:ok nil] (args/parse-interceptor-overrides js/undefined)) "undefined ⇒ ok nil"))

(deftest parse-interceptor-overrides-coerces-ref-shaped-keys-and-values
  ;; A JS object literal can only carry string keys, so a parameterized
  ;; [id arg] ref rides as its bracket-shaped EDN string. "Colon-tolerant":
  ;; a bare name WITHOUT a leading colon is ALSO a valid keyword id,
  ;; mirroring `->frame-keyword`'s tolerance (NOT `parse-fx-overrides`'s
  ;; stricter colon-REQUIRED contract, which exists for a distinct reason —
  ;; see that fn's docstring).
  (doseq [[label o expected]
          [["colon-prefixed bare keyword key/value coerce to keyword refs"
            #js {":auth/required" ":story/skip-auth"} {:auth/required :story/skip-auth}]
           ["null ⇒ the documented remove-this-interceptor sentinel"
            #js {":audit/record-event" nil} {:audit/record-event nil}]
           ["bracket-shaped EDN strings coerce to [id arg] 2-vector refs"
            #js {"[:rf.interceptor/path [:cart]]" "[:rf.interceptor/path [:cart :items]]"}
            {[:rf.interceptor/path [:cart]] [:rf.interceptor/path [:cart :items]]}]
           ["every entry of a multi-entry map coerces"
            #js {":auth/required" ":story/skip-auth" ":audit/record-event" nil}
            {:auth/required :story/skip-auth :audit/record-event nil}]
           ["bare (no leading colon) key/value still coerce to keyword refs"
            #js {"auth/required" "story/skip-auth"} {:auth/required :story/skip-auth}]]]
    (is (= [:ok expected] (args/parse-interceptor-overrides o)) label)))

(deftest parse-interceptor-overrides-rejects-non-ref-input
  ;; Every rejection carries the runtime's own chain-assembly reason, so a
  ;; wire-parse rejection and a deep-runtime one read identically. A blank
  ;; string is not a keyword id (`fresh-keyword` returns nil, which
  ;; `interceptor-ref-token` maps to `::invalid`), and an unreadable or
  ;; wrong-shaped bracket string is rejected rather than treated as a bare
  ;; string id.
  (doseq [[label o] [["blank replacement"                          #js {":auth/required" "   "}]
                     ["number replacement"                         #js {":auth/required" 42}]
                     ["boolean replacement"                        #js {":auth/required" true}]
                     ["unreadable bracket EDN"                     #js {"[:bad" ":story/skip-auth"}]
                     ["a 3-vector isn't a valid [id arg] ref"      #js {"[:a :b :c]" ":story/skip-auth"}]
                     ["a non-keyword-headed vector isn't a valid ref" #js {"[1 2]" ":story/skip-auth"}]
                     ["not an object"                              "not-an-object"]]]
    (let [[tag m] (args/parse-interceptor-overrides o)]
      (is (= [:err :rf.error/interceptor-override-invalid] [tag (:reason m)]) label))))
