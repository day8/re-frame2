(ns re-frame.ui.custom-element-warm-staleness-jvm-test
  "Declaration-edit WARM STALENESS — warm build equals clean build
  (rf2-vxgfnd.141, dimension 3).

  A literal view bakes its property/attribute classification at COMPILE from the
  effective manifest. A warm edit that changes ONLY a declaration recompiles the
  declaring source but NOT a consumer view Shadow does not see as affected (no
  `:require` edge), so the consumer keeps a STALE baked lowering: the warm build
  no longer equals a clean build. The ruled fix (DECISION, coarse per Codex) is
  NOT a per-tag dependency graph — when the harvested manifest changes this pass,
  `build-hook` recompiles the whole re-frame.ui literal-consumer set, so every
  consumer re-bakes against the new manifest = a clean build.

  This file drives the REAL hook across two passes: a clean pass, then a warm
  pass that edits the declaration while the consumer is a cache hit, and asserts
  the consumer's re-baked classification EQUALS a clean build of the edited
  source — the warm=clean law. It also pins that an edit with NO manifest delta
  invalidates nothing (the coarse trigger is a manifest CHANGE, not any UI edit).

  RED-BEFORE LEVER (this file's own): revert `reconcile-declaration-edits` in
  `build-hook/hook` and the consumer is not rescheduled on a warm declaration
  edit — `consumer-is-rescheduled...` and the warm=clean shrink/grow assertions
  go red (the consumer keeps its stale baked lowering), while every other proof
  stays green. The observable is the emitted `:rf.ui/property-props`, not a throw."
  (:require [cljs.env :as cljs-env]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.build-hook :as build-hook]
            [re-frame.ui.compiler.harvest :as harvest]
            [re-frame.ui.tree :as tree]))

(use-fixtures :each
  (fn [f]
    (build/reset-build!) (harvest/reset-harvested!)
    (try (f) (finally (build/reset-build!) (harvest/reset-harvested!)))))

(defn- decl-src [props]
  (str "(ns app.decl (:require [re-frame.ui :as ui]))\n"
       "(ui/custom-element :x-card {:properties " (pr-str props) "})"))

(def ^:private view-src
  ;; The view references :model AND :size so a manifest grow/shrink of EITHER is
  ;; observable in its baked classification; :data-x is always a plain attribute.
  (str "(ns app.view (:require [re-frame.ui :refer [defview]]))\n"
       "(defview card [] [:x-card {:model \"m\" :size \"s\" :data-x \"d\"}])"))

(defn- sources [props]
  {'app.decl {:ns 'app.decl :provides #{'app.decl} :type :cljs
              :requires #{'re-frame.ui} :source (decl-src props)}
   'app.view {:ns 'app.view :provides #{'app.view} :type :cljs
              :requires #{'re-frame.ui} :source view-src}})

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
  "Macroexpand + eval a source's forms under `prepared`'s compiler-env — the real
  compile that bakes the view fn — returning the mutated compiler-env carried
  back into the build-state (as Shadow retains it)."
  [prepared ns-sym src]
  (let [compiler (atom (assoc (:compiler-env prepared)
                              :shadow.build.cljs-bridge/state prepared))]
    (binding [cljs-env/*compiler* compiler
              *ns* (create-ns ns-sym)]
      (doseq [form (read-string (str "[" src "]"))]
        (eval form)))
    (assoc prepared :compiler-env (dissoc @compiler :shadow.build.cljs-bridge/state))))

(defn- run-pass
  "One faithful build pass: prepare (hook), compile every SCHEDULED source (fresh
  output + real macroexpansion), finish (hook). Returns the
  accepted snapshot, the scheduled set, and the consumer view's freshly baked
  `:rf.ui/property-props`."
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
                 (first (:children (tree/render @(resolve 'app.view/card) {}))))}))

(defn- clean-bpp
  "The consumer's baked classification in a fresh clean build with declared
  `props` — the warm-build target."
  [props]
  (build/reset-build!) (harvest/reset-harvested!)
  (:bpp (run-pass {:build-id :clean :props props :accepted nil :output {}})))

(defn- warm-bpp
  "Edit the declaration from `p1-props` to `p2-props` on a WARM build where the
  consumer `app.view` is a cache hit (retained output). Returns the pass-2
  scheduled set and the consumer's re-baked classification."
  [p1-props p2-props]
  (build/reset-build!) (harvest/reset-harvested!)
  (let [p1 (run-pass {:build-id :warm :props p1-props :accepted nil :output {}})
        p2 (run-pass {:build-id :warm :props p2-props
                      :accepted (:accepted p1)
                      ;; consumer retained (warm cache hit); declaring source
                      ;; reset (Shadow schedules the edited file).
                      :output {'app.view {:resource-id 'app.view :js "cached"}}})]
    {:p1-bpp (:bpp p1) :p2-scheduled (:scheduled p2) :p2-bpp (:bpp p2)}))

(deftest warm-declaration-shrink-equals-clean
  ;; Remove :model from the manifest on a warm build; the consumer must re-bake
  ;; :model as an ATTRIBUTE, exactly as a clean build of the edited source does.
  (let [{:keys [p1-bpp p2-scheduled p2-bpp]} (warm-bpp #{:model} #{})]
    (is (= #{:model} p1-bpp) "pass 1 baked :model a property")
    (is (contains? p2-scheduled 'app.view)
        "the warm declaration edit rescheduled the consumer (coarse invalidation)")
    (is (= (clean-bpp #{}) p2-bpp)
        "warm re-bake equals a clean build — :model is now an attribute")
    (is (nil? p2-bpp) "concretely: no property classification survives")))

(deftest warm-declaration-grow-equals-clean
  ;; Add :size to the manifest on a warm build; the consumer must re-bake both
  ;; :model and :size as properties.
  (let [{:keys [p1-bpp p2-scheduled p2-bpp]} (warm-bpp #{:model} #{:model :size})]
    (is (= #{:model} p1-bpp))
    (is (contains? p2-scheduled 'app.view) "the consumer was rescheduled")
    (is (= (clean-bpp #{:model :size}) p2-bpp)
        "warm re-bake equals a clean build")
    (is (= #{:model :size} p2-bpp) "concretely: both are properties now")))

(deftest a-warm-edit-with-no-manifest-delta-invalidates-nothing
  ;; Re-saving the declaration UNCHANGED must NOT reschedule the consumer — the
  ;; coarse trigger is a manifest CHANGE, not any UI edit (so an ordinary edit
  ;; does not needlessly recompile every consumer).
  (build/reset-build!) (harvest/reset-harvested!)
  (let [p1 (run-pass {:build-id :nodelta :props #{:model} :accepted nil :output {}})
        p2 (run-pass {:build-id :nodelta :props #{:model}
                      :accepted (:accepted p1)
                      :output {'app.view {:resource-id 'app.view :js "cached"}}})]
    (is (not (contains? (:scheduled p2) 'app.view))
        "an unchanged declaration leaves the warm consumer a cache hit")
    (is (= #{:model} (:bpp p1)) "still a property throughout")))
