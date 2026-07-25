(ns re-frame.freehand.custom-element-conflict-jvm-test
  "ONE tag has ONE property manifest — the cross-source declaration law at
  MACROEXPANSION (`:rf.ui.compile/custom-element-conflict`).

  The law is not about tidiness. A tag's property manifest decides what
  `v/spread` sends to the DOM and what the serialiser omits from markup, so
  two sources classifying one tag differently is two different pages
  depending on which source the compiler reached first. There is no
  defensible winner rule for that, which is why the framework picks none:
  `rf=`-equal duplicates CO-EXIST (several namespaces may legitimately
  state the same fact), a source REPLACES its own row (that is what a REPL
  re-eval and a hot reload do), and anything else is refused ATOMICALLY —
  the last-known-good manifest is exactly what it was, because a rejected
  declaration that had already half-written would leave the build
  classifying props against a manifest nobody authored.

  WHAT MAKES THIS SUITE MORE THAN A HAPPY-PATH CHECK is that two retired
  behaviours were each observationally fine on a single ordering. A
  last-call-wins write let whichever source expanded second silently take
  the tag; a sorted-owner merge let the alphabetically-later source take it.
  So the permutation rows are the point: swapping two declarations may
  decide only WHERE the failure is anchored, never the verdict and never
  the reported evidence.

  Two barriers, both here. The MACRO path (`compiler/custom-element*` ->
  `build/contribute-element-checked!`) is the plain-JVM / SSR / REPL door.
  The HARVESTED-TRIPLE path (`build/element-manifest`) is the Shadow
  door, where the macro is validation-only and the prepare-time all-members
  harvest is what actually admits — so a same-source contradiction has only
  that barrier, and it must fire BEFORE per-source grouping can collapse
  two live declarations into a source-order winner.

  JVM-only because macroexpansion is: this is the compile-time sibling of
  the runtime `:rf.error/custom-element-conflict` the registry raises.
  Replaces the donor `re-frame.ui.custom-element-conflict-jvm-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.build :as build]))

(use-fixtures :each
  (fn [f] (build/reset-build!) (try (f) (finally (build/reset-build!)))))

;; ---------------------------------------------------------------------------
;; Harness — the REAL macro body, with a controllable declaring namespace
;; ---------------------------------------------------------------------------

(defn- declare-element!
  "Run the real `v/custom-element` macro body with `ns-sym` bound as the
  declaring namespace (the ledger's source key). `nil` when admitted; the
  thrown `ExceptionInfo` when refused."
  [ns-sym tag properties]
  (binding [*ns* (create-ns ns-sym)]
    (try
      (compiler/custom-element*
       (with-meta (list 'custom-element tag {:properties properties}) {:line 1})
       {} tag {:properties properties})
      nil
      (catch clojure.lang.ExceptionInfo ex ex))))

(defn- declare-ok!
  "Declare, asserting the law ADMITS it. Redundancy is not disagreement, and
  a suite that only ever asserted refusals could not tell the two apart."
  [ns-sym tag properties]
  (let [ex (declare-element! ns-sym tag properties)]
    (is (nil? ex)
        (str ns-sym " declaring " tag " " (pr-str properties)
             " must be admitted — got " (some-> ex ex-message)))
    ex))

(defn- props [tag] (build/element-properties tag))

(defn- conflict-data
  "The conflict evidence of a refused declaration, or `nil` when admitted."
  [ex]
  (when ex
    (let [d (ex-data ex)]
      (when (= :rf.ui.compile/custom-element-conflict (:rf.ui.compile/error d))
        (select-keys d [:tag :declarations])))))

(def ^:private default-build :re-frame.freehand.compiler.build/default)

;; ---------------------------------------------------------------------------
;; Redundancy is admitted
;; ---------------------------------------------------------------------------

(deftest rf-equal-duplicates-from-two-sources-coexist
  (testing "two namespaces stating the SAME fact is not a contradiction — the
            law refuses disagreement, not repetition, and a component library
            re-declaring the element it wraps is the ordinary case"
    (declare-ok! 'a.ns :ce-card #{:model :help-text})
    (declare-ok! 'z.ns :ce-card #{:model :help-text})
    (is (= #{:model :help-text} (props :ce-card))
        "an rf=-equal duplicate leaves the manifest intact"))
  (testing "and admission does not depend on which source declares first"
    (doseq [order [['a.ns 'z.ns] ['z.ns 'a.ns]]]
      (build/reset-build!)
      (doseq [ns-sym order] (declare-ok! ns-sym :ce-card #{:model}))
      (is (= #{:model} (props :ce-card))
          (str "duplicates co-exist under order " (pr-str order))))))

(deftest disjoint-tags-never-conflict
  (declare-ok! 'a.ns :ce-card  #{:model})
  (declare-ok! 'z.ns :ce-badge #{:count})
  (is (= #{:model} (props :ce-card)))
  (is (= #{:count} (props :ce-badge))))

;; ---------------------------------------------------------------------------
;; Disagreement is refused, and refused whole
;; ---------------------------------------------------------------------------

(deftest a-contradiction-is-refused-atomically-with-both-anchors
  (declare-ok! 'z.ns :ce-el #{:z})
  (let [data (conflict-data (declare-element! 'a.ns :ce-el #{:a}))]
    (is (some? data) "the contradicting declaration is REFUSED, never merged")
    (is (= :ce-el (:tag data)) "the evidence names the tag")
    (is (= [{:build default-build :ns 'a.ns :properties #{:a}}
            {:build default-build :ns 'z.ns :properties #{:z}}]
           (:declarations data))
        "both [build-id ns-sym] anchors ride the error, in a deterministic sorted order")
    (is (= #{:z} (props :ce-el))
        "and the last-known-good manifest is UNCHANGED — the loser never entered")))

(deftest the-refusal-is-identical-under-source-permutation
  (testing "under the retired last-call-wins write, swapping these two lines
            swapped the WINNER; under a merge that sorted owners it swapped
            which source won silently. Which source expands second may decide
            only WHERE the failure is raised."
    (let [run (fn [[first-ns first-props second-ns second-props]]
                (build/reset-build!)
                (declare-ok! first-ns :ce-el first-props)
                (conflict-data (declare-element! second-ns :ce-el second-props)))
          forward (run ['z.ns #{:z} 'a.ns #{:a}])
          backward (run ['a.ns #{:a} 'z.ns #{:z}])]
      (is (some? forward) "z-then-a is a contradiction")
      (is (some? backward) "a-then-z is a contradiction")
      (is (= forward backward) "the verdict AND the evidence are permutation-stable"))))

(deftest neither-a-sort-nor-a-later-call-can-win-the-tag
  (testing "alphabetically-earlier source declares first (a sorted merge would have let z win)"
    (build/reset-build!)
    (declare-ok! 'a.ns :ce-el #{:a})
    (is (some? (conflict-data (declare-element! 'z.ns :ce-el #{:z}))))
    (is (= #{:a} (props :ce-el)) "no sorted-order winner"))
  (testing "alphabetically-later source declares first (last-call-wins would have let a win)"
    (build/reset-build!)
    (declare-ok! 'z.ns :ce-el #{:z})
    (is (some? (conflict-data (declare-element! 'a.ns :ce-el #{:a}))))
    (is (= #{:z} (props :ce-el)) "no last-call winner")))

(deftest the-refusal-names-both-sources-and-the-recovery
  (testing "a diagnostic that named only the arriving declaration would send
            the reader to the half of the contradiction they can already see"
    (declare-ok! 'z.ns :ce-el #{:z})
    (let [msg (ex-message (declare-element! 'a.ns :ce-el #{:a}))]
      (doseq [fragment ["a.ns" "z.ns" ":ce-el" "IDENTICAL"
                        "re-frame.freehand will not pick a winner"]]
        (is (.contains ^String msg fragment)
            (str "the message must name " (pr-str fragment) " — got: " msg))))))

;; ---------------------------------------------------------------------------
;; A source never conflicts with itself — but a source may contradict itself
;; ---------------------------------------------------------------------------

(deftest a-source-redeclaring-its-own-tag-replaces-its-own-row
  (testing "the REPL / no-pass path upserts: re-evaluating a CHANGED
            declaration is a replacement, which is what makes editing one
            possible at all"
    (declare-ok! 'a.ns :ce-el #{:a})
    (declare-ok! 'a.ns :ce-el #{:b})
    (is (= #{:b} (props :ce-el))))
  (testing "and across passes, which is what a hot reload is"
    (build/reset-build!)
    (build/begin-build! ::default)
    (declare-ok! 'a.ns :ce-el #{:a})
    (build/commit-build! ::default)
    (is (= #{:a} (props :ce-el)))
    (build/begin-build! ::default)
    (declare-ok! 'a.ns :ce-el #{:b})
    (build/commit-build! ::default)
    (is (= #{:b} (props :ce-el))
        "the source's own committed row is replaced, not conflicted")))

(deftest two-live-contradictory-declarations-in-one-pass-of-one-source-fail
  (testing "distinct from replacement: both declarations are LIVE in one
            compile of one file, so the file contradicts ITSELF and the
            self-replacement reading would silently pick the later line"
    (build/begin-build! ::default)
    (declare-ok! 'a.ns :ce-el #{:a})
    (is (some? (conflict-data (declare-element! 'a.ns :ce-el #{:b}))))
    (is (= #{:a} (props :ce-el)) "the first admitted declaration stands")
    (build/abort-build! ::default)))

(deftest rf-equal-redeclaration-in-one-pass-is-idempotent
  (build/begin-build! ::default)
  (declare-ok! 'a.ns :ce-el #{:a})
  (declare-ok! 'a.ns :ce-el #{:a})
  (build/commit-build! ::default)
  (is (= #{:a} (props :ce-el))))

;; ---------------------------------------------------------------------------
;; The law is per-BUILD, never process-global
;; ---------------------------------------------------------------------------

(deftest two-builds-may-classify-one-tag-differently
  (testing "separate builds are separate compilation units, not one realm: a
            sibling build is not a conflicting SOURCE, and a law scoped to the
            process would red a daemon compiling two apps"
    (binding [build/*build-id* :build-a] (declare-ok! 'app.ns :ce-el #{:a}))
    (binding [build/*build-id* :build-b] (declare-ok! 'app.ns :ce-el #{:b}))
    (is (= #{:a} (build/element-properties :ce-el :build-a)))
    (is (= #{:b} (build/element-properties :ce-el :build-b)))))

;; ---------------------------------------------------------------------------
;; The Shadow barrier — raw harvested triples, before per-source grouping
;; ---------------------------------------------------------------------------

(defn- registries
  "`{ns-sym {tag properties}}` -> the per-source registries slice shape."
  [m]
  (reduce-kv (fn [acc ns-sym decls]
               (assoc acc ns-sym
                      {build/elements
                       (reduce-kv (fn [d tag ps] (assoc d tag {:properties ps})) {} decls)}))
             {} m))

(deftest the-pure-detector-agrees-with-the-macro-and-is-permutation-stable
  (testing "defence in depth: the whole-build reconcile re-derives the verdict
            from finalized rows, so a contradiction that slipped past the
            write barrier is still refused"
    (is (nil? (build/elements-conflict
               ::b (registries {'a.ns {:ce-el #{:a}} 'z.ns {:ce-el #{:a}}})))
        "rf=-equal rows across sources are not a conflict")
    (is (nil? (build/elements-conflict
               ::b (registries {'a.ns {:ce-el #{:a}} 'z.ns {:ce-badge #{:a}}})))
        "different tags are not a conflict")
    (let [c (build/elements-conflict
             ::b (registries {'a.ns {:ce-el #{:a}} 'z.ns {:ce-el #{:z}}}))]
      (is (= :ce-el (:tag c)))
      (is (= [{:build ::b :ns 'a.ns :properties #{:a}}
              {:build ::b :ns 'z.ns :properties #{:z}}]
             (:declarations c)))))
  (testing "and its evidence does not depend on which source is enumerated
            first — Clojure's small-map ordering makes that easy to miss"
    (is (= (build/elements-conflict
            ::b (registries {'a.ns {:ce-el #{:a}} 'z.ns {:ce-el #{:z}}}))
           (build/elements-conflict
            ::b (registries {'z.ns {:ce-el #{:z}} 'a.ns {:ce-el #{:a}}}))))))

(deftest same-source-unequal-harvested-declarations-fail-before-grouping
  (testing "on a Shadow build the macro is validation-only, so the all-members
            harvest is the ONLY barrier: two non-rf=-equal declarations of one
            tag from one source previously folded to a silent source-order
            winner, which is the same law violation as a cross-source
            contradiction wearing one namespace"
    (let [e (try (build/element-manifest
                  :probe [['app.a :ce-tag {:properties #{:a}}]
                          ['app.a :ce-tag {:properties #{:b}}]])
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "a same-source contradiction throws, never last-wins")
      (let [{:keys [tag declarations] :as data} (ex-data e)]
        (is (= ::build/custom-element-conflict (::build/error data)))
        (is (= :ce-tag tag))
        (is (= [{:build :probe :ns 'app.a :properties #{:a}}
                {:build :probe :ns 'app.a :properties #{:b}}]
               declarations)
            "both same-source declarations are reported")))))

(deftest same-source-equal-harvested-declarations-fold-idempotently
  (is (= {:ce-tag {:properties #{:a}}}
         (build/element-manifest
          :probe [['app.a :ce-tag {:properties #{:a}}]
                  ['app.a :ce-tag {:properties #{:a}}]]))))
