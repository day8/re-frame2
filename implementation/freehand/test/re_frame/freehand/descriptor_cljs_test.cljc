(ns re-frame.freehand.descriptor-cljs-test
  "FH-CALL-001 and FH-CALL-003 — the descriptor value and its public
  inspection projection.

  Dual-runtime by construction: one `.cljc` suite, one fixture, run by
  this artefact's JVM `:test` alias AND by the ClojureScript node builds.
  A claim green on only one host is a gap, not a pass."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
;; FH-CALL-001 — a declared view cannot be successfully called
;; ---------------------------------------------------------------------------

(def call-001 (conf/fixture :FH-CALL-001))

(def ^:private predicate-fns
  {:view? v/view?
   :ifn?  ifn?
   :fn?   fn?
   :map?  map?
   :coll? coll?})

(defn- invoke-as-fn
  "Invoke `f` as a function with `args` — the `(the-view props)` mistake.
  The head is bound as a local so neither compiler can fold the call site
  into a compile-time diagnostic; the emitted call, and therefore the
  runtime behaviour, is the one a literal direct call produces."
  [f & args]
  (apply f args))

(deftest fh-call-001-declared-view-answers-the-predicates
  (testing "Per FH-CALL-001: the var holds a descriptor value, and
            decisively NOT a map. A map-shaped descriptor would answer
            `(the-view props)` as a LOOKUP: nil, rendered as nothing,
            silently. `ifn?` is TRUE and says nothing about mountability —
            the descriptor implements the call protocol only in order to
            throw, so head classification and tooling ask `view?`."
    (doseq [[pred expected] (:predicates call-001)]
      (let [f (get predicate-fns pred)]
        (is (some? f) (str "fixture names a predicate this suite knows: " pred))
        (is (= expected (boolean (f subject)))
            (str "(" (name pred) " subject) is " expected))))))

(deftest fh-call-001-a-direct-call-is-didactic-on-both-hosts
  (testing "Per FH-CALL-001: invoking a declared view raises the typed
            diagnostic — not the host's own cast failure. `(my-view {…})`
            is idiomatic Reagent and trained muscle memory in every v1
            codebase being migrated, so it is the mistake most likely to
            be a programmer's FIRST encounter with the substrate."
    (let [expected (:direct-call call-001)]
      (is (true? (:raises expected)))
      (is (= (:error-id expected)
             (conf/caught-id #(invoke-as-fn subject {})))
          "the same typed id on the JVM and in ClojureScript"))))

(deftest fh-call-001-the-message-names-the-three-recoveries
  (testing "Per FH-CALL-001: the diagnostic names the view and all THREE
            legal recoveries. A message is stable in meaning rather than
            in bytes, so what is pinned is that each recovery is
            individually findable — mount it, inline it, or extract a
            helper."
    (let [message (conf/caught-message #(invoke-as-fn subject {}))]
      (is (str/includes? message "subject")
          "the message names the offending view")
      (doseq [anchor (:recovery-anchors (:direct-call call-001))]
        (is (str/includes? message anchor)
            (str "the message names the " anchor " recovery"))))))

(deftest fh-call-001-no-arity-escapes-the-diagnostic
  (testing "Per FH-CALL-001: the call protocol implements the host's WHOLE
            roster, not the one or two arities a realistic mistake uses.
            A skipped arity would not fall through to nothing — it would
            fall through to the host's own `AbstractMethodError` /
            `Invalid arity`, which is the poor first-encounter message
            this law exists to remove, reintroduced at a different arity."
    (doseq [n (:didactic-arities (:direct-call call-001))]
      (is (= (:error-id (:direct-call call-001))
             (conf/caught-id #(apply invoke-as-fn subject (repeat n :x))))
          (str "arity " n " raises the didactic diagnostic")))
    (is (not= conf/no-throw
              (conf/caught-id #(apply invoke-as-fn subject (repeat 40 :x))))
        "past the host's own call-protocol ceiling the call still cannot
         SUCCEED — ClojureScript's IFn declares no variadic arity, so
         beyond twenty arguments the host answers with its own error")))

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

#?(:clj
   (deftest defview-requires-a-body
     (testing "A declaration with a parameter vector and nothing after it
               expands today into a view that quietly returns nil — the
               omission produces working code with no output and no
               complaint. It is rejected at expansion instead; a view that
               deliberately renders nothing says so with an explicit nil."
       (is (= :rf.error/defview-bad-args
              (conf/caught-id
                #(v/expand-defview nil "t.cljc" 'app.t 'bodyless '([p]))))
           "no body at all")
       (is (= :rf.error/defview-bad-args
              (conf/caught-id
                #(v/expand-defview nil "t.cljc" 'app.t 'bodyless
                                   '({:children-policy :none} [p]))))
           "options, a parameter vector, and no body")
       (is (str/includes? (conf/caught-message
                            #(v/expand-defview nil "t.cljc" 'app.t 'bodyless '([p])))
                          "bodyless")
           "the diagnostic names the offending declaration")
       (is (map? (v/parse-defview-args '([p])))
           "the SHAPE still parses — the rejection is the body law, not the spelling")
       (is (some? (v/expand-defview nil "t.cljc" 'app.t 'renders-nothing '([p] nil)))
           "an explicit nil body remains a legal no-output view"))))

#?(:clj
   (deftest defview-rejects-an-unknown-option-key
     (testing "An option key outside the closed roster is discarded today,
               so a one-character typo produces valid code with different
               semantics. Every unknown key is rejected at expansion and
               NAMED, so the declaration cannot silently mean something
               other than it says."
       (is (= :rf.error/defview-bad-args
              (conf/caught-id
                #(v/expand-defview nil "t.cljc" 'app.t 'misspelled
                                   '({:chilren-policy :none} [p] [:div]))))
           "a misspelled option key")
       (let [message (conf/caught-message
                       #(v/expand-defview nil "t.cljc" 'app.t 'misspelled
                                          '({:chilren-policy :none} [p] [:div])))]
         (is (str/includes? message ":chilren-policy")
             "the diagnostic names the OFFENDING key")
         (is (str/includes? message ":children-policy")
             "and the roster it should have been"))
       (is (= [:chilren-policy]
              (:unknown-options
                (try (v/expand-defview nil "t.cljc" 'app.t 'misspelled
                                       '({:chilren-policy :none} [p] [:div]))
                     nil
                     (catch Throwable e (ex-data e)))))
           "the offending keys ride the ex-data, so a tool renders them unparsed"))))

#?(:clj
   (deftest defview-rejects-a-reserved-but-unimplemented-option
     (testing "A design-documented option whose owning slice has NOT landed
               is rejected, not ignored. Accepted-and-ignored, the
               declaration reads as one thing and reports itself as
               another — the failure mode that would have quietly
               undermined the compiled-selection slice had `{:compiled
               true}` been discarded before F3b implemented it. The props
               schema options are the standing case: F3f owns them."
       (is (= :rf.error/defview-bad-args
              (conf/caught-id
                #(v/expand-defview nil "t.cljc" 'app.t 'schematised
                                   '({:props-schema [:map]} [p] [:div]))))
           "a reserved future option is never silently ignored")
       (is (str/includes? (conf/caught-message
                            #(v/expand-defview nil "t.cljc" 'app.t 'schematised
                                               '({:props-schema [:map]} [p] [:div])))
                          ":props-schema")
           "and the diagnostic names it")
       (is (some? (v/expand-defview nil "t.cljc" 'app.t 'promoted
                                    '({:compiled false} [p] [:div])))
           "an option the roster CARRIES is accepted — `:compiled` joined it
            when the compiled tier landed, which is how the roster grows"))))

#?(:clj
   (deftest defview-accepts-every-legal-declaration
     (testing "The rejections above must not over-tighten: all four
               documented spellings, every children policy, destructuring,
               and a docstring still expand."
       (doseq [[label more]
               [["minimal"                 '([p] [:div])]
                ["docstring"               '("doc" [p] [:div])]
                ["options"                 '({:children-policy :none} [p] [:div])]
                ["docstring and options"   '("doc" {:children-policy :required} [{:keys [children]}] [:div children])]
                ["policy :optional"        '({:children-policy :optional} [p] [:div])]
                ["explicit :compiled false" '({:compiled false} [p] [:div])]
                ["multi-form body"         '([p] (println p) [:div])]
                ["empty options map"       '({} [p] [:div])]]]
         (is (some? (v/expand-defview nil "t.cljc" 'app.t 'ok more)) label)))))
