(ns re-frame.freehand.props-schema-cljs-test
  "PROPS SCHEMAS — optional in the grammar, and ONE decision where declared.

  `FH-PROPS-004`. Two claims that pull in opposite directions, which is why
  both are proven here rather than only the convenient one.

  1. **A schema is OPTIONAL.** An ordinary application view that declares
     none compiles, mounts and renders, accepts whatever a caller passes,
     and reports its schema as ABSENT — never as a permissive `:any`, which
     would erase the difference between an undeclared contract and a
     deliberately open one. This is the trust-the-programmer arm: a
     substrate that demanded an annotation on every boundary would charge
     ceremony for the promotion of one measured hot view.

  2. **A declared schema closes the props map, identically in both modes.**
     The fixture's `:calls` table is one matrix of props maps, each with the
     verdict it must receive, driven against the SAME schema declared once
     interpreted and once compiled. A verdict that differed between modes
     would mean promotion silently renegotiated the view's contract, which
     is the one thing `{:compiled true}` promises it cannot do.

  ## Three surfaces, one decision

  The matrix is run three times over the same table:

  - at the **boundary**, on an interpreted declaration;
  - at the **boundary**, on a compiled declaration — `v/normalize-call` is
    the normalizer both emitters share, so this is the delivered-props arm
    for both modes;
  - at the **analyzer**, over a literal call site, which is where a compiled
    parent learns about the breach at BUILD time.

  Static knowledge moves when a violation is reported and never which props
  are legal, so the third surface must agree with the first two on every
  row. It reports a different diagnostic id because it reports at a
  different time — `:rf.ui.compile/undeclared-prop` against
  `:rf.error/view-bad-props` — and the fixture names both.

  Host-neutral throughout: the boundary is shared machinery and the analyzer
  is pure with resolution injected, so every row runs under
  `clojure -M:test` and `npm run test:freehand` alike."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.props-schema :as props-schema]))

(def props-004 (conf/fixture :FH-PROPS-004))

(def ^:private schema (:schema props-004))
(def ^:private open-schema (:open-schema props-004))

;; ---------------------------------------------------------------------------
;; The declarations — the SAME schema, once per mode
;; ---------------------------------------------------------------------------

(v/defview interpreted-row
  "The interpreted arm of the parity claim."
  {:props [:map [:id :int] [:label :string]]}
  [{:keys [id label]}]
  [:li.row {:data-id id} label])

(v/defview compiled-row
  "The compiled arm. Byte-for-byte the same schema and the same body — the
  ONLY variable between the two declarations is `{:compiled true}`, which is
  exactly the control a promotion claim needs."
  {:compiled true
   :props    [:map [:id :int] [:label :string]]}
  [{:keys [id label]}]
  [:li.row {:data-id id} label])

(v/defview open-row
  "The explicit escape. A view that really does forward arbitrary props says
  so once, in the schema."
  {:props [:map {:closed false} [:id :int]]}
  [{:keys [id]}]
  [:li.row {:data-id id}])

(v/defview schema-less-row
  "The optional arm: an ordinary application view, no schema, no ceremony."
  [{:keys [id label]}]
  [:li.row {:data-id id} label])

(v/defview compiled-schema-less-row
  "And the same, compiled. Promotion must not acquire a schema requirement:
  a body the grammar can lower is a body the grammar lowers."
  {:compiled true}
  [{:keys [id label]}]
  [:li.row {:data-id id} label])

(def ^:private by-mode
  {:interpreted interpreted-row
   :compiled    compiled-row})

;; ---------------------------------------------------------------------------
;; Surface 1 + 2 — the boundary, in both modes
;; ---------------------------------------------------------------------------

(defn- boundary-verdict
  "`:accept` or `:reject` for one props map at the shared call normalizer."
  [view props]
  (if (= (:reject-id props-004)
         (conf/caught-id #(descriptor/normalize-call view [props])))
    :reject
    :accept))

(deftest the-matrix-is-not-degenerate
  (testing "A parity claim over a table of all-accepts, or all-rejects, would
            hold for a decision that never looked at anything. The table must
            carry both verdicts before agreeing about it means something."
    (let [verdicts (set (map :verdict (:calls props-004)))]
      (is (= #{:accept :reject} verdicts)
          "the matrix exercises both verdicts")
      (is (<= 2 (count (filter #(= :reject (:verdict %)) (:calls props-004))))
          "and more than one way to be rejected"))))

(deftest the-boundary-returns-the-declared-verdict-in-both-modes
  (testing "Per FH-PROPS-004: the same schema, the same props, the same
            answer — whichever mode the declaration selected. `normalize-call`
            is the normalizer both emitters share, so this is the delivered-
            props arm for the compiled tier as much as the interpreted one."
    (doseq [{:keys [props verdict why]} (:calls props-004)
            [mode view] by-mode]
      (is (= verdict (boundary-verdict view props))
          (str mode " — " (pr-str props) " is " (name verdict)
               (when why (str ": " why)))))))

(deftest the-two-modes-never-disagree
  (testing "Stated as its own row rather than inferred from the two above: a
            row that passed for one mode and failed for the other would leave
            two red assertions and no statement of what they mean. THIS is the
            promotion claim — `{:compiled true}` changes the lowering, never
            the contract."
    (doseq [{:keys [props]} (:calls props-004)]
      (is (= (boundary-verdict interpreted-row props)
             (boundary-verdict compiled-row props))
          (str "interpreted and compiled agree about " (pr-str props))))))

(deftest a-rejection-names-the-undeclared-props-and-the-declared-roster
  (testing "A rejection that only said `no` would send an author looking. The
            message names every offending key — not just the first — and the
            roster the schema declared, so the fix is visible without opening
            the declaration."
    (let [msg (conf/caught-message
               #(descriptor/normalize-call compiled-row [{:extra 1 :other 2}]))]
      (is (some? msg) "the breach raises")
      (doseq [k [":extra" ":other" ":id" ":label"]]
        (is (re-find (re-pattern k) msg)
            (str "the message names " k))))))

;; ---------------------------------------------------------------------------
;; Surface 3 — the analyzer, at build time
;; ---------------------------------------------------------------------------

(def ^:private resolver
  "A resolved child view carrying the schema on its var — the shape
  `v/defview` stamps. The compiled parent derives the closing roster from it
  through the same function the boundary uses, which is the whole reason the
  var carries the SCHEMA and not a precomputed key list."
  (fn [sym]
    (case sym
      schema-child {:fqn  'app.views/schema-child
                    :meta {:re-frame.freehand/view          true
                           :re-frame.freehand/props-schema  [:map [:id :int] [:label :string]]}}
      open-child   {:fqn  'app.views/open-child
                    :meta {:re-frame.freehand/view         true
                           :re-frame.freehand/props-schema [:map {:closed false} [:id :int]]}}
      plain-child  {:fqn  'app.views/plain-child
                    :meta {:re-frame.freehand/view true}}
      nil)))

(defn- analyzer-verdict
  "`:accept` or `:reject` for one LITERAL call site, at build time.

  A compile diagnostic carries its id under `:rf.ui.compile/error` rather
  than `:rf.error/id`: the two axes report at different times and are read
  through different keys, which is exactly the difference this suite exists
  to bound. The VERDICT either surface returns must still be the same."
  [head props]
  (let [e (-> (env/make-env {:host     #?(:clj :clj :cljs :cljs)
                             :ns-sym   'app.test
                             :self     'parent
                             :self-id  :app.test/parent
                             :resolver resolver})
              (assoc :self-children? false :self-closed-keys nil))]
    (try
      (ana/analyze e [head props])
      :accept
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
        (if (= (:compiled-reject-id props-004) (:rf.ui.compile/error (ex-data ex)))
          :reject
          :accept)))))

(deftest the-analyzer-returns-the-same-verdict-the-boundary-does
  (testing "Per FH-PROPS-004: a compiled call site's prop keys are literal and
            so knowable at BUILD time, and that is the only thing the compiled
            tier adds. Every row of the matrix gets the verdict the boundary
            gives it — a schema that admitted a prop at render and refused it
            at build would be two contracts wearing one name."
    (doseq [{:keys [props verdict why]} (:calls props-004)]
      (is (= verdict (analyzer-verdict 'schema-child props))
          (str "analyzer — " (pr-str props) " is " (name verdict)
               (when why (str ": " why)))))))

(deftest an-unschemad-child-closes-nothing-at-a-compiled-call-site
  (testing "The optional arm, at the surface that could most easily forget it:
            a compiled parent mounting a child that declares no schema accepts
            whatever the call passes. Absent `:props` is an OPEN map, and a
            compiled parent may not invent a roster for a child that declared
            none."
    (doseq [{:keys [props]} (:calls props-004)]
      (is (= :accept (analyzer-verdict 'plain-child props))
          (str "no schema, no closure: " (pr-str props))))))

;; ---------------------------------------------------------------------------
;; The escape, and the reserved slots
;; ---------------------------------------------------------------------------

(deftest the-open-escape-admits-what-the-closed-schema-refused
  (testing "Per FH-PROPS-004: closure is what the declaration ASKED for, and
            `{:closed false}` is how a view opts out — in the schema, where
            the rest of its props contract already is, rather than as a second
            declaration option. Proven at both surfaces, because an escape
            honoured at render and ignored at build would be worse than none."
    (doseq [{:keys [props verdict]} (:open-calls props-004)]
      (is (= verdict (boundary-verdict open-row props))
          (str "boundary, open schema — " (pr-str props)))
      (is (= verdict (analyzer-verdict 'open-child props))
          (str "analyzer, open schema — " (pr-str props))))))

(deftest the-reserved-slots-are-never-schema-slots
  (testing "Per FH-PROPS-004: `:key` is stripped by the call ABI before props
            are delivered and `:children` arrives as trailing forms under
            `:children-policy`. Each already has a contract, so a schema that
            had to name them would be a second one that could disagree — the
            decision therefore never reports them, whatever the roster says."
    (is (= (:reserved props-004) props-schema/reserved-keys)
        "the fixture and the implementation name the same reserved slots")
    (doseq [k (:reserved props-004)]
      (is (empty? (props-schema/undeclared [:id :label] [k]))
          (str k " is admitted by every closed roster")))))

;; ---------------------------------------------------------------------------
;; The optional arm, and the projection tools read
;; ---------------------------------------------------------------------------

(deftest a-view-without-a-schema-declares-render-and-mount-normally
  (testing "Per FH-PROPS-004: the trust-the-programmer arm. A schema-less
            declaration is an ordinary view in both modes — it accepts every
            props map the matrix carries, including the ones the schema'd view
            refuses, because it made no statement about them."
    (doseq [view [schema-less-row compiled-schema-less-row]
            {:keys [props]} (:calls props-004)]
      (is (= :accept (boundary-verdict view props))
          (str (:view-id (v/describe view)) " accepts " (pr-str props))))))

(deftest absence-is-reported-as-absence-never-as-any
  (testing "Per FH-PROPS-004: an undeclared contract and a deliberately
            permissive one are different facts, and the projection keeps them
            apart. A default of `:any` would make a tool unable to tell a view
            that stated nothing from one that stated everything."
    (doseq [view [schema-less-row compiled-schema-less-row]]
      (is (not (contains? (v/describe view) :props-schema))
          (str (:view-id (v/describe view))
               " — the key is ABSENT, not present-and-permissive")))
    (is (true? (:describe-omits-props-schema? (:schema-less props-004)))
        "the fixture states the same expectation the rows above assert")))

(deftest the-schema-reaches-tooling-through-the-registry-projection
  (testing "Per FH-PROPS-004: a schema that no tool can read buys catalogue
            entries, editor help and agent context nothing. It rides the
            descriptor's public inspection projection VERBATIM — the same data
            the declaration wrote, in both modes, so a catalogue reads one
            contract rather than a mode-dependent rendering of one."
    (doseq [[mode view] by-mode]
      (is (= schema (:props-schema (v/describe view)))
          (str mode " — the projection carries the declared schema verbatim")))
    (is (= open-schema (:props-schema (v/describe open-row)))
        "including the open escape, which a catalogue must be able to SEE")
    (is (= (:lowering (v/describe interpreted-row)) :interpreted))
    (is (= (:lowering (v/describe compiled-row)) :compiled)
        "the two really are different lowerings, so the agreement above is
         a parity result rather than the same value read twice")))
