(ns re-frame.adapter.reagent-slim-source-coord-form3-cljs-test
  "A real reagent-slim `create-class` (Form-3) through
  `re-frame.views.source-coord-annotation/inject-source-coord-attr`. A class
  root has no DOM node to annotate, so the wrapper must pass it through
  UNCHANGED. A slim class carries `cljsReagentClass` and `cljsReagentRender`,
  never stock Reagent's `prototype.reagentRender`, so a predicate keyed only
  on the stock marker would wrap it as a Form-2 fn and lose its React
  lifecycle."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.views.source-coord-annotation :as rf.views.source-coord-annotation]
            [reagent2.core :as r2]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter}))

;; ---- class root passes through -------------------------------------------

(deftest slim-form-3-class-root-passes-through
  (testing "a Form-3 class root is a non-DOM root: the wrapper hands the class
            back UNCHANGED on every pass, so its React lifecycle is reagent2's
            own"
    (let [slim-class (r2/create-class {:reagent-render (fn [] [:div])})
          outs       (repeatedly 5 #(rf.views.source-coord-annotation/inject-source-coord-attr
                                      :rf.slim-src-coord/class-root
                                      "rf.slim-src-coord:class-root:1:1"
                                      slim-class))]
      (is (every? #(identical? slim-class %) outs)
          "the class passes through unchanged on every pass"))))
