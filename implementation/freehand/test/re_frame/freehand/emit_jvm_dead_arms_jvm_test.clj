(ns re-frame.freehand.emit-jvm-dead-arms-jvm-test
  "rf2-drpa3.174 — the JVM emitter's two below-v1 arms that have no honest JVM
  form (`:raw`, and a NON-lazy `:foreign`) refuse the shape loudly instead of
  emitting a call to `re-frame.freehand.tree/jvm-host-op!`, a var defined
  nowhere.

  The claim has two halves, and this suite pins both so a later grammar
  widening cannot silently re-expose an unresolved symbol:

    - GRAMMAR — `:raw` and `:foreign` are outside `:re-frame.freehand/v1`, so
      `grammar/check!` refuses a body carrying either before any emitter sees
      it. That is what makes these emitter arms unreachable in the normal
      pipeline (they are established DEAD, not merely suspected so).

    - JVM EMITTER — the arm itself, reached across a weaker seam (`emit-node`
      called directly) or after a future admission, raises an intentional
      Freehand compile diagnostic. It emits no form, so there is no undefined
      var for expansion to trip over.

  Expansion is driven directly rather than through `defview` source, because a
  reached arm is a build failure — a suite that placed one in source could not
  compile."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand.compiler.emit-jvm :as emit-jvm]
            [re-frame.freehand.compiler.grammar :as grammar]))

(defn- syms-in [form]
  (let [hits (atom #{})]
    (walk/postwalk (fn [x] (when (symbol? x) (swap! hits conj x)) x) form)
    @hits))

(defn- emit-outcome
  "`emit-node`'s outcome for `node`: the diagnostic ex-data when it refuses,
  or `[::emitted form]` when it produces a form. `e` is nil, as the defview
  driver passes it (the emitter fails with a nil env exactly as its literal
  attr-value refusal already does)."
  [node]
  (try [::emitted (emit-jvm/emit-node nil node)]
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; ---------------------------------------------------------------------------
;; The arms are dead — the grammar refuses both node kinds before emission
;; ---------------------------------------------------------------------------

(deftest raw-and-foreign-are-outside-the-v1-grammar
  (testing "Neither :raw nor :foreign is an admitted v1 op, so a body carrying
            one never reaches the JVM emitter in the normal pipeline — the
            positive establishment that these arms are dead code."
    (is (not (contains? grammar/admitted-ops :raw))
        ":raw is not admitted by :re-frame.freehand/v1")
    (is (not (contains? grammar/admitted-ops :foreign))
        ":foreign is not admitted by :re-frame.freehand/v1")))

;; ---------------------------------------------------------------------------
;; The JVM emitter arm fails loud — an intentional diagnostic, no emitted var
;; ---------------------------------------------------------------------------

(deftest a-raw-node-reaching-the-emitter-refuses-with-a-diagnostic
  (testing "Reached across a weaker seam, the :raw arm raises the emitter's own
            compile diagnostic and emits nothing — so there is no form naming
            an undefined var for expansion to trip over."
    (let [d (emit-outcome {:op :raw})]
      (is (map? d) ":raw refuses, it does not emit a form")
      (is (= :rf.ui.compile/unsupported-form (:rf.ui.compile/error d))
          "under the emitter's compile diagnostic id")
      (is (= :raw (:op d)) "naming the op it refused"))))

(deftest a-non-lazy-foreign-node-reaching-the-emitter-refuses-with-a-diagnostic
  (testing "Same for a non-lazy foreign component: it has no JVM structural
            form, so the arm refuses rather than emitting one."
    (let [d (emit-outcome {:op :foreign :lazy? false :sym 'some.ns/Widget})]
      (is (map? d) "a non-lazy :foreign refuses, it does not emit a form")
      (is (= :rf.ui.compile/unsupported-form (:rf.ui.compile/error d))
          "under the emitter's compile diagnostic id")
      (is (= 'some.ns/Widget (:sym d)) "naming the foreign head it refused"))))

(deftest the-refusal-is-a-diagnostic-not-an-unresolved-symbol
  (testing "The exact regression rf2-drpa3.174 names: these arms used to emit
            a form naming re-frame.freehand.tree/jvm-host-op!, a var defined
            nowhere, so a reached arm expanded to an unresolved symbol rather
            than a diagnostic. The arms now raise ExceptionInfo — the proof
            they no longer produce an emittable form at all."
    (is (thrown? clojure.lang.ExceptionInfo (emit-jvm/emit-node nil {:op :raw})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (emit-jvm/emit-node nil {:op :foreign :lazy? false :sym 'x/Y})))))

;; ---------------------------------------------------------------------------
;; The lazy :foreign arm is untouched — it still emits its callable form
;; ---------------------------------------------------------------------------

(deftest a-lazy-foreign-node-still-emits-its-callable-form
  (testing "The fix scopes to the NON-lazy foreign arm. A lazy foreign
            component is callable on the JVM structural render (it renders its
            fallback), so the lazy arm must still emit an ordinary call — and,
            like every other live arm, name no jvm-host-op! var."
    (let [[tag form] (emit-outcome {:op :foreign :lazy? true :sym 'some.ns/HeavyChart})
          syms       (syms-in form)]
      (is (= ::emitted tag) "a lazy :foreign emits rather than refusing")
      (is (contains? syms 'some.ns/HeavyChart) "the lazy arm invokes the component")
      (is (not-any? #(= "jvm-host-op!" (name %)) syms)
          "and no emitted form names the undefined host-op var"))))
