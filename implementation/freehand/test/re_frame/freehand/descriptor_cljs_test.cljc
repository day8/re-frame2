(ns re-frame.freehand.descriptor-cljs-test
  "FH-CALL-001 and FH-CALL-003 — the descriptor value and its public
  inspection projection.

  Dual-runtime by construction: one `.cljc` suite, one fixture, run by
  this artefact's JVM `:test` alias AND by the ClojureScript node builds.
  A claim green on only one host is a gap, not a pass."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]))

(v/defview subject
  "A declared view — the subject of FH-CALL-001 and FH-CALL-003."
  [{:keys [title]}]
  [:section.subject title])

(v/defview childless
  {:children-policy :none}
  [_]
  [:hr])

;; ---------------------------------------------------------------------------
;; FH-CALL-001 — the non-IFn descriptor value
;; ---------------------------------------------------------------------------

(def call-001 (conf/fixture :FH-CALL-001))

(def ^:private predicate-fns
  {:view? v/view?
   :ifn?  ifn?
   :fn?   fn?
   :map?  map?
   :coll? coll?})

(defn- invoke-as-fn
  "Invoke `f` as a function — the `(the-view props)` mistake. The head is
  bound as a local so neither compiler can fold the call site into a
  compile-time diagnostic; the emitted call, and therefore the runtime
  behaviour, is the one a literal direct call produces."
  [f props]
  (f props))

(deftest fh-call-001-declared-view-is-a-non-ifn-descriptor
  (testing "Per FH-CALL-001: the var holds a descriptor value, not a
            callable function — and, decisively, not a map. A map-shaped
            descriptor is IFn on both hosts, so `(the-view props)` would
            answer as a LOOKUP: nil, rendered as nothing, silently. The
            ruling exists to make that outcome unreachable."
    (doseq [[pred expected] (:predicates call-001)]
      (let [f (get predicate-fns pred)]
        (is (some? f) (str "fixture names a predicate this suite knows: " pred))
        (is (= expected (boolean (f subject)))
            (str "(" (name pred) " subject) is " expected))))))

(deftest fh-call-001-a-direct-call-raises-on-both-hosts
  (testing "Per FH-CALL-001: invoking a declared view raises. The host
            refuses the call before any framework code runs, so the
            exception is the host's own (a cast failure on the JVM, a
            type error in ClojureScript) — what the law fixes is that the
            call can never SUCCEED."
    (is (true? (:raises (:direct-call call-001))))
    (is (thrown? #?(:clj Throwable :cljs js/Error)
                 (invoke-as-fn subject {})))))

;; ---------------------------------------------------------------------------
;; FH-CALL-003 — the inspection projection
;; ---------------------------------------------------------------------------

(def call-003 (conf/fixture :FH-CALL-003))

(deftest fh-call-003-projection-carries-exactly-the-public-abi
  (testing "Per FH-CALL-003: `describe` projects the public ABI slots and
            nothing else. The key roster is closed in both directions —
            an extra key is as much a defect as a missing one, because
            the projection is what registries and catalogues read."
    (let [projection (v/describe subject)
          marker     (:marker call-003)]
      (is (= (:value marker) (get projection (:key marker)))
          "the projection is self-identifying")
      (is (= (set (:public-keys call-003)) (set (keys projection)))
          "exactly the public keys, no more and no less")
      (is (= :re-frame.freehand.descriptor-cljs-test/subject (:view-id projection))
          "the view id is the qualified declaration name"))))

(deftest fh-call-003-private-entries-are-not-projected
  (testing "Per FH-CALL-003: the render body and the host mount / tree
            entries stay private. A consumer able to reach them through
            the projection would be depending on a shape no slice
            promises to keep."
    (let [projection (v/describe subject)]
      (doseq [k (:private-keys call-003)]
        (is (not (contains? projection k))
            (str k " is a private descriptor entry and is not projected"))))))

(deftest fh-call-003-absent-schema-is-absent-not-any
  (testing "Per FH-CALL-003: a missing props schema is reported as ABSENT
            rather than as `:any`. Reporting `:any` would make an
            undeclared schema indistinguishable from a declared
            permissive one — exactly the distinction a coverage report
            needs."
    (let [projection (v/describe subject)]
      (doseq [k (:absent-when-undeclared call-003)]
        (is (not (contains? projection k))
            (str k " is absent when the declaration carries none"))))))

(deftest fh-call-003-lowering-and-children-policy
  (testing "Per FH-CALL-003: `:lowering` reports which mode the
            declaration selected — inspection data, never a dispatch
            surface, because every view is mounted the same way — and the
            children policy defaults to `:optional`."
    (is (= (:lowering call-003) (:lowering (v/describe subject))))
    (is (= (:default-children-policy call-003)
           (:children-policy (v/describe subject))))
    (is (= :none (:children-policy (v/describe childless)))
        "a declared policy is carried on the descriptor")))

(deftest fh-call-003-source-coords-are-captured
  (testing "Per FH-CALL-003: the declaration captures source coordinates
            at its own call site, per Spec 001's capture rules, so a tool
            can jump from a view id to its declaration."
    (let [source (:source (v/describe subject))]
      (is (map? source))
      (doseq [k (:source-keys call-003)]
        (is (contains? source k) (str "source carries " k))))))

;; ---------------------------------------------------------------------------
;; The declaration form itself
;; ---------------------------------------------------------------------------

(deftest descriptor-prints-recognisably
  (testing "A descriptor prints as itself. Test failures, REPL sessions
            and error messages all render view values; an opaque
            `#object[…]` blob there is a real ergonomic cost for a value
            the programmer meets constantly."
    (is (= "#re-frame.freehand/view :re-frame.freehand.descriptor-cljs-test/subject"
           (str subject)))
    (is (= (str subject) (pr-str subject)))))

;; `defview` expands on the JVM for BOTH compilation targets, so the
;; expander is exercised JVM-side — that IS the cross-host proof for a
;; macro-expansion-time rejection. `macroexpand-1` cannot reach a
;; ClojureScript macro from a running ClojureScript test.
#?(:clj
   (deftest defview-rejects-a-malformed-declaration
     (testing "Per Spec 004 §The descriptor and `v/defview`: a view takes
               exactly one argument — its props map. There are no
               positional view arguments, so a multi-parameter
               declaration is rejected at expansion time rather than
               becoming a boundary that can never be called correctly."
       (is (= :rf.error/defview-bad-args
              (conf/caught-id
                #(v/expand-defview nil "t.cljc" 'app.t 'two-params
                                   '([a b] [:div]))))
           "two parameters")
       (is (= :rf.error/defview-bad-args
              (conf/caught-id
                #(v/expand-defview nil "t.cljc" 'app.t 'no-params
                                   '("a docstring and nothing else"))))
           "no parameter vector at all")
       (is (= :rf.error/defview-bad-args
              (conf/caught-id
                #(v/expand-defview nil "t.cljc" 'app.t 'bad-policy
                                   '({:children-policy :some} [p] [:div]))))
           "a children policy outside the closed roster")
       (is (= {:docstring "doc"
               :opts      {:children-policy :none}
               :params    '[p]
               :body      '([:div])}
              (v/parse-defview-args '("doc" {:children-policy :none} [p] [:div])))
           "the full spelling — name, docstring, options, params, body — parses")
       (is (= {:docstring nil :opts nil :params '[p] :body '([:div])}
              (v/parse-defview-args '([p] [:div])))
           "the minimal spelling parses"))))
