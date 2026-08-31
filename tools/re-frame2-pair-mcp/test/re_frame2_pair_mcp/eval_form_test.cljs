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
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [re-frame2-pair-mcp.tools.eval-form :as ef]))

;; ---------------------------------------------------------------------------
;; rt-call — runtime-ns-relative call.
;; ---------------------------------------------------------------------------

(deftest rt-call-zero-args-ir
  (is (= [::ef/call 'health []] (ef/rt-call 'health))))

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

(deftest rt-call-map-arg-roundtrips
  (let [opts {:frames :all :include [:app-db :sub-cache]}
        form (ef/emit (ef/rt-call 'snapshot-state opts))]
    (is (= opts
           (-> form
               cljs.reader/read-string  ; outer list
               second)))))              ; the map arg

(deftest rt-call-runtime-ns-centralised
  (testing "the runtime-ns constant prefixes every rt-call"
    (is (= "re-frame2-pair.runtime" ef/runtime-ns))
    (is (clojure.string/starts-with?
          (ef/emit (ef/rt-call 'foo))
          (str "(" ef/runtime-ns "/foo")))))

;; ---------------------------------------------------------------------------
;; rt-call* — fully-qualified call (no runtime-ns prefix).
;; ---------------------------------------------------------------------------

(deftest rt-call*-emits-verbatim
  (is (= "(re-frame.core/elide-wire-value db)"
         (ef/emit (ef/rt-call* 're-frame.core/elide-wire-value
                               (ef/rt-raw "db"))))))

;; ---------------------------------------------------------------------------
;; rt-raw — escape hatch for raw source.
;; ---------------------------------------------------------------------------

(deftest rt-raw-passes-through
  (is (= [::ef/raw "(:app-db snap)"] (ef/rt-raw "(:app-db snap)")))
  (is (= "(:app-db snap)" (ef/emit (ef/rt-raw "(:app-db snap)")))))

(deftest rt-raw-arg-not-pr-stred
  ;; Without rt-raw, a string would be pr-str'd (quoted). With rt-raw
  ;; the source-fragment passes through unquoted — the escape hatch.
  (let [via-raw    (ef/emit (ef/rt-call 'foo (ef/rt-raw "x")))
        via-string (ef/emit (ef/rt-call 'foo "x"))]
    (is (= "(re-frame2-pair.runtime/foo x)" via-raw))
    (is (= "(re-frame2-pair.runtime/foo \"x\")" via-string))))

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

(deftest rt-let-binding-name-must-be-symbol
  (is (thrown? :default
        (ef/emit (ef/rt-let ["snap" (ef/rt-call 'snapshot)]
                            (ef/rt-raw "snap"))))))

;; ---------------------------------------------------------------------------
;; Round-trips — the six tool sites pinned at the wire shape.
;; ---------------------------------------------------------------------------

(deftest opts-map-form-shape
  ;; Pin the wire shape for an opts-map call — the runtime sees
  ;; `(re-frame2-pair.runtime/<fn> {opts-map})` with the map
  ;; round-tripping through the emitted source unchanged.
  (let [opts {:signals [{:app-db [:cart]}]
              :stop    {:ms 5000}}
        form (ef/emit (ef/rt-call 'start-recording! opts))]
    (is (= opts
           (-> form
               cljs.reader/read-string
               second)))))

(deftest precheck-form-shape-no-frame
  ;; Precheck routes through the O(1) cached accessor.
  (is (= "(re-frame2-pair.runtime/app-db-hash)"
         (ef/emit (ef/rt-call 'app-db-hash)))))

(deftest precheck-form-shape-with-frame
  ;; Explicit-frame arm names the frame on the cheap accessor.
  (is (= "(re-frame2-pair.runtime/app-db-hash :rf/default)"
         (ef/emit (ef/rt-call 'app-db-hash :rf/default)))))

(deftest snapshot-state-form-is-edn-readable
  ;; The non-elision arm.
  (let [opts {:frames :all
              :include [:app-db :sub-cache :machines :epochs :traces]}
        form (ef/emit (ef/rt-call 'snapshot-state opts))
        edn  (cljs.reader/read-string form)]
    (is (= 're-frame2-pair.runtime/snapshot-state (first edn)))
    (is (= opts (second edn)))))

;; ---------------------------------------------------------------------------
;; `::call*` qsym handling + collection-recursion.
;; ---------------------------------------------------------------------------

(deftest rt-call*-symbol-qsym-emits-fully-qualified
  ;; Symbol qsyms emit via `(str sym)` — the namespace prefix is
  ;; included verbatim.
  (is (= "(re-frame.core/elide-wire-value)"
         (ef/emit (ef/rt-call* 're-frame.core/elide-wire-value)))))

(deftest rt-call*-bare-symbol-emits-verbatim
  ;; A bare symbol (no namespace) emits as just the name. The precheck
  ;; routes through `app-db-hash` (the cached O(1) accessor), but the
  ;; bare-symbol arm remains useful for other call sites that need an
  ;; unqualified host fn.
  (is (= "(hash)" (ef/emit (ef/rt-call* 'hash)))))

(deftest rt-call*-string-qsym-emits-verbatim
  ;; A string qsym renders the same way the symbol does (verbatim, no
  ;; auto-quoting), so a caller can pass a pre-qualified string and get
  ;; correct source.
  (is (= "(some.ns/foo 1)"
         (ef/emit (ef/rt-call* "some.ns/foo" 1)))))

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
  ;; the output for the existing tool sites.
  (is (= "(re-frame2-pair.runtime/foo [1 2 3])"
         (ef/emit (ef/rt-call 'foo [1 2 3])))))
