(ns re-frame.ui.custom-element-harvest-hook-jvm-test
  "Clean-build ORDER DETERMINISM across TWO SOURCES on the Shadow build path
  (rf2-vxgfnd.141, dimension 2).

  Two source files with no `:require` edge compile in an arbitrary order, so a
  consumer view could analyze before the declaring source's macro ran and lower a
  declared property to an attribute. The build hook closes this at
  `:compile-prepare` by harvesting every recompiled UI source's top-level literal
  `(ui/custom-element …)` forms and staging them into the open pass BEFORE any
  view analyzes. This file drives the REAL hook over a two-source graph in BOTH
  `:build-sources` orders and asserts the consumer view emits the SAME
  classification — declaration source first or consumer source first.

  The declaring source's own macro is never run here; the ONLY thing that can
  declare `:ce-two` is the prepare-time harvest reading its `:source`. So this
  is a clean red-before lever of its own: revert the
  `build/set-shadow-element-manifest` call in `build-hook/hook` and both orders
  classify `:model` as an attribute (the emitted node carries no
  `:rf.ui/property-props`), while every other proof stays green. The observable
  is the emitted node, not merely that nothing threw."
  (:require [cljs.env :as cljs-env]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ui.compiler :as compiler]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.build-hook :as build-hook]
            [re-frame.ui.compiler.harvest :as harvest]
            [re-frame.ui.tree :as tree]))

(use-fixtures :each
  (fn [f]
    (build/reset-build!)
    (harvest/reset-harvested!)
    (try (f) (finally (build/reset-build!) (harvest/reset-harvested!)))))

(def ^:private decl-source
  "(ns app.decl (:require [re-frame.ui :as ui]))
   (ui/custom-element :ce-two {:properties #{:model}})")

(defn- graph
  "A clean-build (version-0, all-scheduled) two-source graph: `app.decl`
  declares `:ce-two`, `app.view` consumes it. `order` fixes which resource is
  first in `:build-sources`."
  [build-id order]
  {:shadow.build/build-id build-id
   :shadow.build/stage :compile-prepare
   :compiler-env {}
   :executor (Object.)
   :analyzer-passes []
   :build-sources (vec order)
   :sources {'app.decl {:ns 'app.decl :provides #{'app.decl} :type :cljs
                        :requires #{'re-frame.ui} :source decl-source}
             'app.view {:ns 'app.view :provides #{'app.view} :type :cljs
                        :requires #{'re-frame.ui} :source ""}}
   :output {}})

(defn- strip-view-evidence
  "Drop the DEV host-root view-evidence annotation (:data-rf2-source-coord /
  :data-rf-view) the compiler stamps on a view's compiler-owned root element
  (rf2-hac8p). This suite asserts custom-element property classification, not the
  host-root annotation (own coverage: the emit-annotation tests, the parity
  corpus, test:elision)."
  [node]
  (if-let [a (:attrs node)]
    (let [a (dissoc a :data-rf2-source-coord :data-rf-view)]
      (if (seq a) (assoc node :attrs a) (dissoc node :attrs)))
    node))

(defn- classify-consumer
  "Under `prepared`'s compiler-env (which the hook seeded at prepare),
  macroexpand + eval a consumer defview using `:ce-two` and render it; return the
  emitted element node. `view-ns` is a fresh namespace so repeated runs never
  collide."
  [prepared view-ns]
  (let [compiler (atom (assoc (:compiler-env prepared)
                              :shadow.build.cljs-bridge/state prepared))
        template [:ce-two {:model "m" :data-x "d"}]]
    (binding [cljs-env/*compiler* compiler
              *ns* (create-ns view-ns)]
      (eval (compiler/defview*
             (with-meta (list 'defview 'probe [] template) {:line 1})
             {} 'probe (list [] template)))
      (strip-view-evidence
       (first (:children (tree/render @(resolve (symbol (str view-ns) "probe")) {})))))))

(deftest two-source-order-permutations-classify-identically
  (let [decl-first (do (build/reset-build!) (harvest/reset-harvested!)
                       (classify-consumer
                        (build-hook/hook (graph :order-a ['app.decl 'app.view]))
                        'probe.decl-first))
        view-first (do (build/reset-build!) (harvest/reset-harvested!)
                       (classify-consumer
                        (build-hook/hook (graph :order-b ['app.view 'app.decl]))
                        'probe.view-first))]
    (testing "declaration source compiled first: :model is a property"
      (is (= #{:model} (:rf.ui/property-props decl-first)))
      (is (= {:model "m" :data-x "d"} (:attrs decl-first))))
    (testing "consumer source compiled first: :model is STILL a property (harvest)"
      (is (= #{:model} (:rf.ui/property-props view-first))))
    (testing "the two orders emit an identical property classification — the AC"
      (is (= (:rf.ui/property-props decl-first)
             (:rf.ui/property-props view-first))))))

(deftest an-undeclared-tag-in-neither-source-stays-an-attribute
  ;; The harvest seeds declared tags only; a tag no source declares must remain
  ;; all-attributes (this is what makes "consulted-and-missing = undeclared"
  ;; sound — after harvest, the absent classification is genuine, not a race).
  (let [prepared (build-hook/hook (graph :order-c ['app.decl 'app.view]))
        compiler (atom (assoc (:compiler-env prepared)
                              :shadow.build.cljs-bridge/state prepared))
        template [:ce-missing {:model "m"}]]
    (binding [cljs-env/*compiler* compiler
              *ns* (create-ns 'probe.undeclared)]
      (eval (compiler/defview*
             (with-meta (list 'defview 'probe [] template) {:line 1})
             {} 'probe (list [] template)))
      (let [node (strip-view-evidence
                  (first (:children (tree/render
                                     @(resolve 'probe.undeclared/probe) {}))))]
        (is (nil? (:rf.ui/property-props node))
            "an undeclared tag is not seeded -> attribute")
        (is (= {:model "m"} (:attrs node)))))))
