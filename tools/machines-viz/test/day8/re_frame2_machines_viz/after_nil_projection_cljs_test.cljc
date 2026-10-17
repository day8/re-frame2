(ns day8.re-frame2-machines-viz.after-nil-projection-cljs-test
  "An `:after` or `:on` holding nil declares no delay or transition, so every
  surface projects a definition carrying one exactly as it projects the same
  definition without the key: the chart, Mermaid and SCXML, on a state, a
  compound, a region body, a flat root and a parallel root."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.mermaid :as mermaid]
            [day8.re-frame2-machines-viz.scxml :as scxml]))

(def ^:private pairs
  "Label → [a definition declaring `:after nil` or `:on nil`, the same
  definition without it]."
  {[:after :state]         [{:initial :a :states {:a {:after nil :on {:go :b}} :b {}}}
                            {:initial :a :states {:a {:on {:go :b}} :b {}}}]
   [:after :flat-root]     [{:initial :a :after nil :states {:a {:on {:go :b}} :b {}}}
                            {:initial :a :states {:a {:on {:go :b}} :b {}}}]
   [:after :parallel-root] [{:type    :parallel :after nil
                             :regions {:r {:initial :a :after nil :states {:a {:after nil :on {:go :b}} :b {}}}}}
                            {:type    :parallel
                             :regions {:r {:initial :a :states {:a {:on {:go :b}} :b {}}}}}]
   [:on :state]            [{:initial :a :states {:a {:on nil :after {1000 :b}} :b {}}}
                            {:initial :a :states {:a {:after {1000 :b}} :b {}}}]
   [:on :compound]         [{:initial :o :states {:o {:initial :a :on nil :states {:a {:on {:go :c}} :c {}}}}}
                            {:initial :o :states {:o {:initial :a :states {:a {:on {:go :c}} :c {}}}}}]
   [:on :flat-root]        [{:initial :a :on nil :states {:a {:on {:go :b}} :b {}}}
                            {:initial :a :states {:a {:on {:go :b}} :b {}}}]
   [:on :parallel-root]    [{:type    :parallel :on nil
                             :regions {:r {:initial :a :on nil :states {:a {:on nil :after {1000 :b}} :b {}}}}}
                            {:type    :parallel
                             :regions {:r {:initial :a :states {:a {:after {1000 :b}} :b {}}}}}]})

(deftest after-nil-projects-as-absent
  (doseq [[label [with-nil without]] pairs]
    (testing (str label ": the chart")
      (is (= (layout/project-definition without) (layout/project-definition with-nil))))
    (testing (str label ": Mermaid")
      (is (= (mermaid/emit without) (mermaid/emit with-nil))))
    (testing (str label ": SCXML")
      (is (= (scxml/spec->scxml without) (scxml/spec->scxml with-nil))))))
