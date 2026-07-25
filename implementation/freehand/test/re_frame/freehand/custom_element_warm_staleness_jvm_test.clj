(ns re-frame.freehand.custom-element-warm-staleness-jvm-test
  "A WARM build equals a CLEAN build after a declaration edit.

  A compiled view bakes its property/attribute classification at
  MACROEXPANSION, from the ambient build's effective declaration manifest.
  That is the right design — the verdict is settled before the render and
  is independent of what has loaded by then — and it has one consequence
  nothing else in the compiler has to worry about: the baked value depends
  on a source the consumer has NO `:require` edge to. Edit only the
  declaration, and Shadow recompiles the declaring file while the consumer
  is a cache hit, so the consumer keeps a lowering derived from a manifest
  that no longer exists. The warm build no longer equals a clean one, and
  nothing anywhere is red.

  The ruled fix is COARSE on purpose: when the harvested manifest changes
  in a pass, the whole Freehand literal-consumer set is rescheduled, so
  every consumer re-bakes against the new manifest — which is a clean build
  by definition. Not a per-tag dependency graph, whose cost is a real graph
  to maintain and whose benefit is recompiling fewer files in a case that
  arises when someone edits a declaration.

  So this suite drives the REAL hook across TWO passes and asserts the
  re-baked classification EQUALS a clean build of the edited source, in
  both directions — a manifest that SHRINKS (a property becomes an
  attribute) and one that GROWS. The third row is the other half of the
  rule and the one a coarse trigger gets wrong most easily: an edit with NO
  manifest delta must invalidate NOTHING, or every ordinary edit to a
  declaring file recompiles every consumer in the build.

  The observable is the emitted `:rf.ui/property-props`, never that
  something threw: the defect this pins away is silent and well-formed.

  Replaces the donor `re-frame.ui.custom-element-warm-staleness-jvm-test`."
  (:require [cljs.env :as cljs-env]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.freehand.compiler.build :as build]
            [re-frame.freehand.compiler.build-hook :as build-hook]
            [re-frame.freehand.compiler.harvest :as harvest]
            [re-frame.freehand.tree :as tree]))

(use-fixtures :each
  (fn [f]
    (build/reset-build!) (harvest/reset-harvested!)
    (try (f) (finally (build/reset-build!) (harvest/reset-harvested!)))))

(defn- decl-src [props]
  (str "(ns app.decl (:require [re-frame.freehand :as v]))\n"
       "(v/custom-element :ce-warm {:properties " (pr-str props) "})"))

(def ^:private view-src
  ;; The view names :model AND :size, so a manifest that grows or shrinks
  ;; EITHER is observable in the baked classification; :data-x is an attribute
  ;; throughout and is the control that says the whole map did not simply move.
  (str "(ns app.view (:require [re-frame.freehand :refer [defview]]))\n"
       "(defview card [_] [:ce-warm {:model \"m\" :size \"s\" :data-x \"d\"}])"))

(defn- sources [props]
  {'app.decl {:ns 'app.decl :provides #{'app.decl} :type :cljs
              :requires #{'re-frame.freehand} :source (decl-src props)}
   'app.view {:ns 'app.view :provides #{'app.view} :type :cljs
              :requires #{'re-frame.freehand} :source view-src}})

(defn- prepare-state [build-id props accepted output]
  (cond-> {:shadow.build/build-id build-id
           :shadow.build/stage :compile-prepare
           :compiler-env {}
           :executor (Object.)
           :analyzer-passes []
           :build-sources ['app.decl 'app.view]
           :sources (sources props)
           :output output}
    accepted (assoc-in [:compiler-env build/accepted-snapshot-key] accepted)))

(defn- scheduled [state] (#'build-hook/recompiled-member-nss state))

(defn- eval-source!
  "Macroexpand + eval a source's forms under `prepared`'s compiler-env — the
  REAL compile that bakes the view fn, because a harness that only inspected
  registries would prove the manifest moved and say nothing about the
  lowering that is the actual subject. Returns the mutated compiler-env
  carried back into the build state, as Shadow retains it."
  [prepared ns-sym src]
  (let [compiler (atom (assoc (:compiler-env prepared)
                              :shadow.build.cljs-bridge/state prepared))]
    (binding [cljs-env/*compiler* compiler
              *ns* (create-ns ns-sym)]
      (doseq [form (read-string (str "[" src "]"))]
        (eval form)))
    (assoc prepared :compiler-env (dissoc @compiler :shadow.build.cljs-bridge/state))))

(defn- run-pass
  "One faithful build pass: prepare (hook), compile every SCHEDULED source
  with fresh output and real macroexpansion, finish (hook). Answers the
  accepted snapshot, the scheduled set, and the consumer view's freshly
  baked `:rf.ui/property-props`."
  [{:keys [build-id props accepted output]}]
  (let [prepared (build-hook/hook (prepare-state build-id props accepted output))
        sched    (scheduled prepared)
        srcs     (sources props)
        with-out (reduce (fn [s n]
                           (assoc-in s [:output n]
                                     {:resource-id n :js "compiled" :cached false}))
                         prepared sched)
        compiled (reduce (fn [s n] (eval-source! s n (get-in srcs [n :source])))
                         with-out sched)
        finished (build-hook/hook (assoc compiled :shadow.build/stage :compile-finish))]
    {:accepted  (build/accepted-snapshot finished)
     :scheduled sched
     :bpp       (:rf.ui/property-props
                 (first (:children (tree/render [@(resolve 'app.view/card) {}]))))}))

(defn- clean-bpp
  "The consumer's baked classification in a FRESH build declaring `props` —
  the warm build's target value."
  [props]
  (build/reset-build!) (harvest/reset-harvested!)
  (:bpp (run-pass {:build-id :clean :props props :accepted nil :output {}})))

(defn- warm-bpp
  "Edit the declaration from `p1-props` to `p2-props` on a WARM build where
  the consumer is a retained cache hit — Shadow schedules the edited
  declaring file and nothing else."
  [p1-props p2-props]
  (build/reset-build!) (harvest/reset-harvested!)
  (let [p1 (run-pass {:build-id :warm :props p1-props :accepted nil :output {}})
        p2 (run-pass {:build-id :warm :props p2-props
                      :accepted (:accepted p1)
                      :output {'app.view {:resource-id 'app.view :js "cached"}}})]
    {:p1-bpp (:bpp p1) :p2-scheduled (:scheduled p2) :p2-bpp (:bpp p2)}))

(deftest a-warm-manifest-shrink-re-bakes-to-the-clean-value
  (testing "the declaration drops :model while the consumer is a cache hit —
            the consumer must re-bake it as an ATTRIBUTE, exactly as a clean
            build of the edited source does"
    (let [{:keys [p1-bpp p2-scheduled p2-bpp]} (warm-bpp #{:model} #{})]
      (is (= #{:model} p1-bpp) "pass 1 baked :model a property")
      (is (contains? p2-scheduled 'app.view)
          "the manifest change rescheduled the consumer (the coarse invalidation)")
      (is (= (clean-bpp #{}) p2-bpp) "warm re-bake equals a clean build")
      (is (nil? p2-bpp) "concretely: no property classification survives the edit"))))

(deftest a-warm-manifest-growth-re-bakes-to-the-clean-value
  (testing "and the other direction: :size joins the manifest, so the consumer
            must re-bake BOTH names as properties"
    (let [{:keys [p1-bpp p2-scheduled p2-bpp]} (warm-bpp #{:model} #{:model :size})]
      (is (= #{:model} p1-bpp))
      (is (contains? p2-scheduled 'app.view) "the consumer was rescheduled")
      (is (= (clean-bpp #{:model :size}) p2-bpp) "warm re-bake equals a clean build")
      (is (= #{:model :size} p2-bpp) "concretely: both are properties now"))))

(deftest a-warm-edit-with-no-manifest-delta-invalidates-nothing
  (testing "the trigger is a manifest CHANGE, not any edit to a declaring file.
            Re-saving an unchanged declaration must leave the warm consumer a
            cache hit, or the coarse rule's cost lands on every ordinary edit
            rather than on the rare one it exists for."
    (build/reset-build!) (harvest/reset-harvested!)
    (let [p1 (run-pass {:build-id :nodelta :props #{:model} :accepted nil :output {}})
          p2 (run-pass {:build-id :nodelta :props #{:model}
                        :accepted (:accepted p1)
                        :output {'app.view {:resource-id 'app.view :js "cached"}}})]
      (is (not (contains? (:scheduled p2) 'app.view))
          "an unchanged declaration leaves the warm consumer alone")
      (is (= #{:model} (:bpp p1)) "and :model is a property throughout"))))
