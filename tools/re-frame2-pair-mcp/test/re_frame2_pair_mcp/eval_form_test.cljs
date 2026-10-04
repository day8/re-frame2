(ns re-frame2-pair-mcp.eval-form-test
  "Tests for the mini-DSL that composes CLJS eval forms.

  Two assertion styles cover the surface:

  - **IR shape** — every constructor returns a tagged-vector data
    structure (`[::call sym [arg ...]]` etc.). Tests pin the shape;
    a rename of an internal tag would surface here, not as a regex
    drift downstream.
  - **Emit output** — `emit` renders to a CLJS source string.
    Tests pin the exact output for a handful of representative
    forms drawn from the six tool sites; this is the
    contract every tool body relies on."
  (:require [cljs.test :refer-macros [deftest is]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.eval-form :as ef]))

;; ---------------------------------------------------------------------------
;; rt-call — runtime-ns-relative call.
;; ---------------------------------------------------------------------------

(deftest rt-call-zero-args-emit
  (is (= "(re-frame2-pair.runtime/health)"
         (ef/emit (ef/rt-call 'health)))))

(deftest rt-call-scalar-args-emit
  (is (= "(re-frame2-pair.runtime/read-recording \"abc-123\")"
         (ef/emit (ef/rt-call 'read-recording "abc-123"))))
  (is (= "(re-frame2-pair.runtime/snapshot :rf/default)"
         (ef/emit (ef/rt-call 'snapshot :rf/default))))
  (is (= "(re-frame2-pair.runtime/dispatch-and-collect 42)"
         (ef/emit (ef/rt-call 'dispatch-and-collect 42)))))

              ; the map arg


;; ---------------------------------------------------------------------------
;; rt-raw — escape hatch for raw source.
;; ---------------------------------------------------------------------------

(deftest rt-raw-passes-through
  (is (= [::ef/raw "(:app-db snap)"] (ef/rt-raw "(:app-db snap)")))
  (is (= "(:app-db snap)" (ef/emit (ef/rt-raw "(:app-db snap)")))))

;; ---------------------------------------------------------------------------
;; rt-let — `let` block with bindings + body.
;; ---------------------------------------------------------------------------

(deftest rt-let-single-binding-single-body
  (is (= "(let [snap (re-frame2-pair.runtime/snapshot)] (:app-db snap))"
         (ef/emit (ef/rt-let ['snap (ef/rt-call 'snapshot)]
                             (ef/rt-raw "(:app-db snap)"))))))

(deftest rt-let-empty-body-emits-nil
  (is (= "(let [x 1] nil)"
         (ef/emit (ef/rt-let ['x 1])))))

(deftest rt-let-multi-body-wraps-in-do
  (is (= "(let [x 1] (do (foo) (bar)))"
         (ef/emit (ef/rt-let ['x 1]
                             (ef/rt-raw "(foo)")
                             (ef/rt-raw "(bar)"))))))

;; ---------------------------------------------------------------------------
;; Round-trip — a map arg reads back as the opts map it was built from.
;; ---------------------------------------------------------------------------

(deftest snapshot-state-form-is-edn-readable
  ;; The non-elision arm.
  (let [opts {:frames :all
              :include [:app-db :sub-cache :machines :epochs :traces]}
        form (ef/emit (ef/rt-call 'snapshot-state opts))
        edn  (cljs.reader/read-string form)]
    (is (= 're-frame2-pair.runtime/snapshot-state (first edn)))
    (is (= opts (second edn)))))

;; ---------------------------------------------------------------------------
;; Collection recursion.
;; ---------------------------------------------------------------------------

(deftest emit-arg-recurses-into-vectors-of-nodes
  ;; A vector mixing scalar data and IR nodes is walked element-wise,
  ;; so an IR node inside the vector emits as source rather than being
  ;; `pr-str`'d as a literal.
  (is (= "(re-frame2-pair.runtime/foo [1 bar])"
         (ef/emit (ef/rt-call 'foo [1 (ef/rt-raw "bar")])))))

(deftest emit-arg-passes-pure-scalar-vectors-unchanged
  ;; Pure-scalar vectors still go through `pr-str` byte-for-byte —
  ;; the recursion only triggers when the vector contains at least
  ;; one IR node. Pin so the contains-node check doesn't change
  ;; the output for scalar-only tool sites.
  (is (= "(re-frame2-pair.runtime/foo [1 2 3])"
         (ef/emit (ef/rt-call 'foo [1 2 3])))))

;; ---------------------------------------------------------------------------
;; rt-quote — the literal-data emission path.
;;
;; `pr-str` is not a data quotation. It renders a value as SOURCE, and
;; source is read as code: a nested EDN list becomes a function call and a
;; symbol becomes a name lookup. That is invisible for the scalar payloads
;; the internal tool sites compose — a keyword, a string, a number, a map of
;; those — and it is exactly wrong for EXTERNAL EDN parsed off the wire,
;; where lists and symbols are ordinary data the caller expects to reach
;; the handler unchanged.
;;
;; `(quote <datum>)` is the only emission that evaluates to arbitrary EDN
;; unchanged, so it is the one shape the data-only call sites take. The
;; node's payload is NEVER walked by `emit-arg`: a caller-supplied vector
;; that happens to wear an emitter tag is payload, not IR, and quoting it
;; must not hand it back the splice.
;; ---------------------------------------------------------------------------

(defn- quoted-datum
  "The datum a `(quote <datum>)` form evaluates to, or `::not-quoted` for
  anything else. Reading an emitted arg through this is the difference
  between pinning printed SYNTAX and pinning what evaluation yields."
  [form]
  (if (and (seq? form) (= 'quote (first form)) (= 2 (count form)))
    (second form)
    ::not-quoted))

(deftest rt-quote-emits-a-quoted-literal
  (is (= "(quote [:cart/checkout])"
         (ef/emit [::ef/quote [:cart/checkout]])))
  (is (= "(quote :bare)"
         (ef/emit [::ef/quote :bare]))))

(deftest rt-quote-keeps-nested-lists-as-lists
  ;; The hazard in one line: unquoted, `(inc 41)` inside the payload
  ;; evaluates to 42 and the handler never sees the list it was sent.
  (let [datum [:cart/add '(inc 41)]
        src   (ef/emit (ef/rt-call 'dispatch-consequence! [::ef/quote datum] {}))
        arg   (second (cljs.reader/read-string src))]
    (is (= datum (quoted-datum arg))
        "the nested list survives as a list — it evaluates to itself")))

(deftest rt-quote-keeps-symbols-as-symbols
  (let [datum [:cart/add 'js/window]
        src   (ef/emit (ef/rt-call 'dispatch-consequence! [::ef/quote datum] {}))
        arg   (second (cljs.reader/read-string src))]
    (is (= datum (quoted-datum arg))
        "a symbol-valued event stays a symbol rather than resolving")))

(deftest rt-quote-does-not-splice-an-emitter-shaped-payload
  ;; A caller-supplied vector wearing the emitter's own `::raw` tag is
  ;; PAYLOAD. Unquoted it would be recognised as IR and its string spliced
  ;; in as raw source, replacing the event outright.
  (let [datum [::ef/raw "(inc 41)"]
        src   (ef/emit (ef/rt-call 'dispatch-consequence! [::ef/quote datum] {}))
        arg   (second (cljs.reader/read-string src))]
    (is (= datum (quoted-datum arg))
        "the tagged vector rides through as the vector it is")
    (is (not (str/includes? src "dispatch-consequence! (inc 41)"))
        "and its string is never spliced into the runtime call's arg position")))
