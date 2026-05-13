(ns re-frame-pair2-mcp.eval-form-test
  "Tests for the mini-DSL that composes CLJS eval forms (rf2-dpzpe).

  Two assertion styles cover the surface:

  - **IR shape** — every constructor returns a tagged-vector data
    structure (`[::call sym [arg ...]]` etc.). Tests pin the shape;
    a rename of an internal tag would surface here, not as a regex
    drift downstream.
  - **Emit output** — `emit` renders to a CLJS source string.
    Tests pin the exact output for a handful of representative
    forms drawn from the six migrated tool sites; this is the
    contract every tool body relies on."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [re-frame-pair2-mcp.tools.eval-form :as ef]))

;; ---------------------------------------------------------------------------
;; rt-call — runtime-ns-relative call.
;; ---------------------------------------------------------------------------

(deftest rt-call-zero-args-ir
  (is (= [::ef/call 'health []] (ef/rt-call 'health))))

(deftest rt-call-zero-args-emit
  (is (= "(re-frame-pair2.runtime/health)"
         (ef/emit (ef/rt-call 'health)))))

(deftest rt-call-scalar-args-emit
  (is (= "(re-frame-pair2.runtime/unsubscribe! \"abc-123\")"
         (ef/emit (ef/rt-call 'unsubscribe! "abc-123"))))
  (is (= "(re-frame-pair2.runtime/snapshot :rf/default)"
         (ef/emit (ef/rt-call 'snapshot :rf/default))))
  (is (= "(re-frame-pair2.runtime/dispatch-and-collect 42)"
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
    (is (= "re-frame-pair2.runtime" ef/runtime-ns))
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
    (is (= "(re-frame-pair2.runtime/foo x)" via-raw))
    (is (= "(re-frame-pair2.runtime/foo \"x\")" via-string))))

;; ---------------------------------------------------------------------------
;; rt-let — `let` block with bindings + body.
;; ---------------------------------------------------------------------------

(deftest rt-let-single-binding-single-body
  (is (= "(let [snap (re-frame-pair2.runtime/snapshot)] (:app-db snap))"
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
;; Migration round-trips — the six tool sites pinned at the wire shape.
;; ---------------------------------------------------------------------------

(deftest subscribe-form-shape
  ;; Migrated from subscribe.cljs:69. Pin the wire shape — the runtime
  ;; sees `(re-frame-pair2.runtime/subscribe! {opts-map})`.
  (let [opts {:topic :trace
              :max-buffered-events 500
              :max-buffered-bytes  5000000}
        form (ef/emit (ef/rt-call 'subscribe! opts))]
    (is (= opts
           (-> form
               cljs.reader/read-string
               second)))))

(deftest subscribe-form-carries-both-budgets
  ;; rf2-ho4ve: regression — both budgets must round-trip.
  (let [opts {:topic :trace
              :max-buffered-events 1000
              :max-buffered-bytes  2000000}
        form (ef/emit (ef/rt-call 'subscribe! opts))
        edn  (cljs.reader/read-string form)]
    (is (= 1000    (-> edn second :max-buffered-events)))
    (is (= 2000000 (-> edn second :max-buffered-bytes)))))

(deftest unsubscribe-form-shape
  (is (= "(re-frame-pair2.runtime/unsubscribe! \"abc-123-uuid\")"
         (ef/emit (ef/rt-call 'unsubscribe! "abc-123-uuid")))))

(deftest drain-form-shape
  (is (= "(re-frame-pair2.runtime/drain-subscription! \"sub-xyz\")"
         (ef/emit (ef/rt-call 'drain-subscription! "sub-xyz")))))

(deftest precheck-form-shape-no-frame
  (is (= "(hash (re-frame-pair2.runtime/snapshot))"
         (ef/emit
           (ef/rt-call* 'hash (ef/rt-call 'snapshot))))))

(deftest precheck-form-shape-with-frame
  (is (= "(hash (re-frame-pair2.runtime/snapshot :rf/default))"
         (ef/emit
           (ef/rt-call* 'hash (ef/rt-call 'snapshot :rf/default))))))

(deftest snapshot-state-form-is-edn-readable
  ;; Migrated from snapshot.cljs:62. The non-elision arm.
  (let [opts {:frames :all
              :include [:app-db :sub-cache :machines :epochs :traces]}
        form (ef/emit (ef/rt-call 'snapshot-state opts))
        edn  (cljs.reader/read-string form)]
    (is (= 're-frame-pair2.runtime/snapshot-state (first edn)))
    (is (= opts (second edn)))))
