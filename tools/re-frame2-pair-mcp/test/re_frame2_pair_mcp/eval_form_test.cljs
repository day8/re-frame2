(ns re-frame2-pair-mcp.eval-form-test
  "Unit tests for the eval-form DSL's emitter. The conformance corpus matches
  the emitted runtime calls through the tools, mostly by substring; these pin
  a string arg's quoting, the `let` and raw-source shapes, and the
  literal-data quote."
  (:require [cljs.test :refer-macros [deftest is]]
            [cljs.reader]
            [re-frame2-pair-mcp.tools.eval-form :as ef]))

(deftest rt-call-scalar-args-emit
  ;; A string arg must print quoted; unquoted it would read as a symbol.
  (is (= "(re-frame2-pair.runtime/read-recording \"abc-123\")"
         (ef/emit (ef/rt-call 'read-recording "abc-123")))))

(deftest rt-let-single-binding-single-body
  (is (= "(let [snap (re-frame2-pair.runtime/snapshot)] (:app-db snap))"
         (ef/emit (ef/rt-let ['snap (ef/rt-call 'snapshot)]
                             (ef/rt-raw "(:app-db snap)"))))))

;; ---------------------------------------------------------------------------
;; rt-quote. `pr-str` renders a value as SOURCE, so in external EDN a list
;; would evaluate as a call and a symbol as a name lookup; `(quote <datum>)`
;; evaluates to the datum unchanged. Its payload is never walked as IR: a
;; caller vector that wears an emitter tag is data, and walking it would
;; splice its string in as raw source.
;; ---------------------------------------------------------------------------

(defn- quoted-datum
  "The datum a `(quote <datum>)` form evaluates to, or `::not-quoted`."
  [form]
  (if (and (seq? form) (= 'quote (first form)) (= 2 (count form)))
    (second form)
    ::not-quoted))

(deftest rt-quote-does-not-splice-an-emitter-shaped-payload
  (doseq [datum [[:cart/add '(inc 41) 'js/window]   ; a list stays a list, a symbol a symbol
                 [::ef/raw "(inc 41)"]]]             ; a payload wearing the emitter's own tag
    (let [src (ef/emit (ef/rt-call 'dispatch-consequence! (ef/rt-quote datum) {}))]
      (is (= datum (quoted-datum (second (cljs.reader/read-string src))))
          (pr-str datum)))))
