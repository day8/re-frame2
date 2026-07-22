(ns re-frame.ui.custom-element-conflict-jvm-test
  "THE ONE cross-source custom-element declaration law (rf2-vxgfnd.143;
  delegated ruling 2026-07-15, Option A — fail atomically).

  Before this law the same tag had TWO different winner rules — direct
  registration published the LAST evaluated call, while an aggregate rebuild
  merged ledger rows in sorted OWNER order — so an unrelated reload could flip
  the effective property manifest without either conflicting declaration
  changing, and the compiler's own aggregate merged with no conflict check at
  all. Three silent-precedence paths, each keyed on incidental order.

  The law, applied identically wherever declarations are aggregated:

    - `rf=`-equal declarations of one tag MAY co-exist across sources
      (idempotent — two namespaces are allowed to state the same fact);
    - a NON-`rf=`-equal declaration of the same tag from a DIFFERENT source is
      a contradiction: it fails atomically, carries BOTH `[build-id ns-sym]`
      anchors, and leaves the last-known-good aggregate unchanged;
    - a source never conflicts with ITSELF across passes (re-declaration
      replaces its own row), but two contradictory declarations inside ONE
      pass of one namespace are two LIVE declarations and do fail;
    - no path ever picks a winner — not last-call, not sorted owner, not
      compile order.

  THE DETERMINISM DISCIPLINE (why these tests are permutation folds, not
  single happy-path cases): a law that merely *fails somewhere* is not the
  same as a law that fails IDENTICALLY. Every conflict test below drives BOTH
  contribution orders and asserts the two runs produce byte-identical
  `:declarations` evidence. Under the old last-writer/sorted-merge behaviour a
  permutation changed the WINNER; under a naive fix a permutation still
  changes the reported EVIDENCE. Both are caught here."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ui.compiler :as compiler]
            [re-frame.ui.compiler.build :as build]))

(use-fixtures :each
  (fn [f] (build/reset-build!) (try (f) (finally (build/reset-build!)))))

;; ---------------------------------------------------------------------------
;; Harness — the REAL `ui/custom-element` macro body, with a controllable
;; declaring namespace (the ledger's source key).
;; ---------------------------------------------------------------------------

(defn- declare-element!
  "Run the real `ui/custom-element` macro body with `ns-sym` bound as the
  declaring namespace. Returns nil on success; the thrown ExceptionInfo on a
  compile error."
  [ns-sym tag properties]
  (binding [*ns* (create-ns ns-sym)]
    (try
      (compiler/custom-element*
       (with-meta (list 'custom-element tag {:properties properties})
         {:line 1})
       {} tag {:properties properties})
      nil
      (catch clojure.lang.ExceptionInfo ex ex))))

(defn- declare-ok!
  "Declare, asserting it was ADMITTED (the law must not reject this)."
  [ns-sym tag properties]
  (let [ex (declare-element! ns-sym tag properties)]
    (is (nil? ex)
        (str ns-sym " declaring " tag " " (pr-str properties)
             " must be admitted — got " (some-> ex ex-message)))
    ex))

(defn- props [tag] (build/element-properties tag))

(defn- conflict-data
  "The conflict ex-data of a rejected declaration, or nil when admitted."
  [ex]
  (when ex
    (let [d (ex-data ex)]
      (when (= :rf.ui.compile/custom-element-conflict (:rf.ui.compile/error d))
        (select-keys d [:tag :declarations])))))

;; ---------------------------------------------------------------------------
;; rf=-equal duplicates co-exist
;; ---------------------------------------------------------------------------

(deftest rf-equal-duplicates-from-two-sources-coexist
  ;; Two namespaces stating the SAME fact is not a contradiction — the law
  ;; rejects disagreement, not redundancy.
  (declare-ok! 'a.ns :x-card #{:model :help-text})
  (declare-ok! 'z.ns :x-card #{:model :help-text})
  (is (= #{:model :help-text} (props :x-card))
      "an rf=-equal duplicate leaves the manifest intact"))

(deftest rf-equal-duplicate-admission-is-order-independent
  (doseq [order [['a.ns 'z.ns] ['z.ns 'a.ns]]]
    (build/reset-build!)
    (doseq [ns-sym order]
      (declare-ok! ns-sym :x-card #{:model}))
    (is (= #{:model} (props :x-card))
        (str "duplicates co-exist under order " (pr-str order)))))

;; ---------------------------------------------------------------------------
;; The headline: conflicting declarations fail atomically + identically
;; ---------------------------------------------------------------------------

(deftest conflicting-declarations-fail-atomically
  (declare-ok! 'z.ns :x-el #{:z})
  (let [ex (declare-element! 'a.ns :x-el #{:a})
        data (conflict-data ex)]
    (is (some? data) "the contradicting declaration is REJECTED, not merged")
    (is (= :x-el (:tag data)) "evidence names the tag")
    (is (= [{:build :re-frame.ui.compiler.build/default :ns 'a.ns :properties #{:a}}
            {:build :re-frame.ui.compiler.build/default :ns 'z.ns :properties #{:z}}]
           (:declarations data))
        "evidence carries BOTH [build-id ns-sym] anchors, sorted")
    (is (= #{:z} (props :x-el))
        "the last-known-good aggregate is UNCHANGED — the loser never entered")))

(deftest conflict-evidence-is-identical-under-every-permutation
  ;; THE determinism proof. Under the retired last-call-wins / sorted-merge
  ;; behaviour, swapping these two lines swapped the WINNER. The law must make
  ;; the permutation unobservable: same verdict, same evidence, both ways.
  (let [run (fn [[first-ns first-props second-ns second-props]]
              (build/reset-build!)
              (declare-ok! first-ns :x-el first-props)
              (conflict-data (declare-element! second-ns :x-el second-props)))
        forward (run ['z.ns #{:z} 'a.ns #{:a}])
        reverse (run ['a.ns #{:a} 'z.ns #{:z}])]
    (is (some? forward) "z-then-a conflicts")
    (is (some? reverse) "a-then-z conflicts")
    (is (= forward reverse)
        (str "conflict evidence must be IDENTICAL under source permutation — "
             "which source expands second may decide only WHERE the error is "
             "anchored, never the verdict or the evidence"))))

(deftest conflict-is-not-decided-by-sorted-owner-order
  ;; The retired `rebuild-live!` merged ledger rows in sorted owner order, so
  ;; the alphabetically-LAST source silently won. Both directions must now
  ;; fail, and neither source's properties may become "the answer" by winning
  ;; a sort: the tag keeps exactly the FIRST admitted declaration and the
  ;; contradiction is surfaced, never resolved.
  (testing "alphabetically-earlier source declares first"
    (build/reset-build!)
    (declare-ok! 'a.ns :x-el #{:a})
    (is (some? (conflict-data (declare-element! 'z.ns :x-el #{:z})))
        "a-then-z must fail (sorted-merge would have let z win silently)")
    (is (= #{:a} (props :x-el)) "no sorted-order winner"))
  (testing "alphabetically-later source declares first"
    (build/reset-build!)
    (declare-ok! 'z.ns :x-el #{:z})
    (is (some? (conflict-data (declare-element! 'a.ns :x-el #{:a})))
        "z-then-a must fail (last-call-wins would have let a win silently)")
    (is (= #{:z} (props :x-el)) "no last-call winner")))

(deftest conflict-message-names-both-sources-and-the-escape
  (declare-ok! 'z.ns :x-el #{:z})
  (let [msg (ex-message (declare-element! 'a.ns :x-el #{:a}))]
    (doseq [fragment ["a.ns" "z.ns" ":x-el" "IDENTICAL" "will not pick a winner"]]
      (is (.contains msg fragment)
          (str "conflict message must name " (pr-str fragment) " — got: " msg)))))

(deftest disjoint-tags-never-conflict
  (declare-ok! 'a.ns :x-card #{:model})
  (declare-ok! 'z.ns :x-badge #{:count})
  (is (= #{:model} (props :x-card)))
  (is (= #{:count} (props :x-badge))))

;; ---------------------------------------------------------------------------
;; Self-replacement vs. two live declarations
;; ---------------------------------------------------------------------------

(deftest same-source-redeclaration-replaces-and-never-conflicts
  ;; A source never conflicts with itself: the REPL/no-pass path upserts, so
  ;; re-evaluating a changed declaration must replace its own row.
  (declare-ok! 'a.ns :x-el #{:a})
  (declare-ok! 'a.ns :x-el #{:b})
  (is (= #{:b} (props :x-el))
      "a source re-declaring its own tag replaces its own row"))

(deftest same-source-redeclaration-across-passes-replaces
  (build/begin-build! ::default)
  (declare-ok! 'a.ns :x-el #{:a})
  (build/commit-build! ::default)
  (is (= #{:a} (props :x-el)))
  ;; A hot-reload pass: the same source re-declares a CHANGED manifest. Its
  ;; committed row is being replaced wholesale — not a contradiction.
  (build/begin-build! ::default)
  (declare-ok! 'a.ns :x-el #{:b})
  (build/commit-build! ::default)
  (is (= #{:b} (props :x-el))
      "the source's own committed row is replaced, not conflicted"))

(deftest two-contradictory-declarations-in-one-pass-of-one-ns-fail
  ;; Distinct from replacement: both declarations are LIVE in the same compile
  ;; of the same file, so the file contradicts itself.
  (build/begin-build! ::default)
  (declare-ok! 'a.ns :x-el #{:a})
  (let [data (conflict-data (declare-element! 'a.ns :x-el #{:b}))]
    (is (some? data)
        "two live contradictory declarations in one pass of one ns must fail")
    (is (= #{:a} (props :x-el)) "the first admitted declaration stands"))
  (build/abort-build! ::default))

(deftest rf-equal-redeclaration-in-one-pass-is-idempotent
  (build/begin-build! ::default)
  (declare-ok! 'a.ns :x-el #{:a})
  (declare-ok! 'a.ns :x-el #{:a})
  (build/commit-build! ::default)
  (is (= #{:a} (props :x-el))))

;; ---------------------------------------------------------------------------
;; Build isolation — the law is per-build, never process-global
;; ---------------------------------------------------------------------------

(deftest contradictory-declarations-in-different-builds-do-not-conflict
  ;; Two builds legitimately compile the same tag with different manifests.
  ;; The law is scoped to [build-id tag]; a sibling build is not a conflicting
  ;; "source".
  (binding [build/*build-id* :build-a]
    (declare-ok! 'app.ns :x-el #{:a}))
  (binding [build/*build-id* :build-b]
    (declare-ok! 'app.ns :x-el #{:b}))
  (is (= #{:a} (build/element-properties :x-el :build-a)))
  (is (= #{:b} (build/element-properties :x-el :build-b))))

;; ---------------------------------------------------------------------------
;; The pure aggregation detector (defence in depth)
;; ---------------------------------------------------------------------------

(defn- registries
  "A per-source registries map: `{ns-sym {tag properties}}` -> the slice shape."
  [m]
  (reduce-kv (fn [acc ns-sym decls]
               (assoc acc ns-sym
                      {build/elements
                       (reduce-kv (fn [d tag ps] (assoc d tag {:properties ps}))
                                  {} decls)}))
             {} m))

(deftest elements-conflict-detects-cross-source-contradiction
  (is (nil? (build/elements-conflict
             ::b (registries {'a.ns {:x-el #{:a}} 'z.ns {:x-el #{:a}}})))
      "rf=-equal rows across sources are not a conflict")
  (is (nil? (build/elements-conflict
             ::b (registries {'a.ns {:x-el #{:a}} 'z.ns {:x-badge #{:a}}})))
      "different tags are not a conflict")
  (let [c (build/elements-conflict
           ::b (registries {'a.ns {:x-el #{:a}} 'z.ns {:x-el #{:z}}}))]
    (is (= :x-el (:tag c)) "the contradiction is detected from finalized rows")
    (is (= [{:build ::b :ns 'a.ns :properties #{:a}}
            {:build ::b :ns 'z.ns :properties #{:z}}]
           (:declarations c)))))

(deftest elements-conflict-evidence-is-permutation-stable
  ;; A merge-order-sensitive detector would report whichever row it happened to
  ;; visit second. Clojure's small-map ordering makes that easy to miss by
  ;; accident, so assert it directly: the evidence must not depend on which
  ;; source is enumerated first.
  (let [a-first (build/elements-conflict
                 ::b (registries {'a.ns {:x-el #{:a}} 'z.ns {:x-el #{:z}}}))
        z-first (build/elements-conflict
                 ::b (registries {'z.ns {:x-el #{:z}} 'a.ns {:x-el #{:a}}}))]
    (is (= a-first z-first)
        "the detector's evidence is identical under source permutation")))

;; ---------------------------------------------------------------------------
;; Harvested-triple composition (PR #6646 audit rider) — the Shadow-path
;; all-members harvest feeds `element-manifest` RAW `[source tag decl]`
;; triples, so the law must fire BEFORE per-source grouping collapses a
;; same-source contradiction last-wins. On a Shadow build the macro is
;; validation/reporting-only, so this is the ONLY barrier for two
;; contradictory live declarations of one tag in one namespace.
;; ---------------------------------------------------------------------------

(deftest same-source-unequal-harvested-declarations-fail
  ;; The rider's exact probe: two non-rf=-equal declarations of one tag from
  ;; the SAME source in ONE harvest previously folded to a silent source-order
  ;; winner ({:x-tag {:properties #{:b}}}). They are two live declarations —
  ;; the same law violation as a cross-source contradiction — and must throw.
  (let [e (try (build/element-manifest
                :probe [['app.a :x-tag {:properties #{:a}}]
                        ['app.a :x-tag {:properties #{:b}}]])
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "a same-source contradiction must throw, never last-wins")
    (let [{:keys [tag declarations] :as data} (ex-data e)]
      (is (= ::build/custom-element-conflict (::build/error data)))
      (is (= :x-tag tag))
      (is (= [{:build :probe :ns 'app.a :properties #{:a}}
              {:build :probe :ns 'app.a :properties #{:b}}]
             declarations)
          "both same-source declarations are reported as evidence"))))

(deftest same-source-equal-harvested-declarations-fold-idempotently
  ;; rf=-equal duplicates remain idempotent — two literal statements of the
  ;; same fact in one source are not a contradiction.
  (is (= {:x-tag {:properties #{:a}}}
         (build/element-manifest
          :probe [['app.a :x-tag {:properties #{:a}}]
                  ['app.a :x-tag {:properties #{:a}}]]))))
