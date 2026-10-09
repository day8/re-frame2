(ns re-frame.schemas-walker-operators-test
  "The per-slot flag walker's path rules for the operator families the
  `extract-*` tests in `schemas_sensitive_test` do not reach: dispatch-bearing
  branches, variable-width and spliced sequences, and opaque forms."
  (:require [clojure.test :refer [are deftest]]
            [re-frame.schemas :as rf.schemas]))

(deftest dispatch-bearing-branches-claim-the-parent-path
  ;; A dispatch value is not a path segment.
  (are [schema expected]
       (= expected (rf.schemas/extract-sensitive-paths-from-schema schema [:value]))
    [:orn [:secret {:sensitive? true} :string] [:public :string]]
    {[:value] {:sensitive? true :source :schema}}

    [:orn [:authed [:map [:token {:sensitive? true} :string]]] [:anon [:map [:guest :string]]]]
    {[:value :token] {:sensitive? true :source :schema}}))

(deftest variable-width-sequence-descends-index-free
  ;; Without a fixed width an input index cannot be mapped to an element, so
  ;; elements descend at the shared base-path, as under :sequential.
  (are [schema expected]
       (= expected (rf.schemas/extract-sensitive-paths-from-schema schema [:ev]))
    [:cat [:* :int] [:map [:tok {:sensitive? true} :string]]]
    {[:ev :tok] {:sensitive? true :source :schema}}

    [:catn [:head [:? :int]] [:tail {:sensitive? true} :string]]
    {[:ev] {:sensitive? true :source :schema}}

    ;; a fixed-width :cat spliced into a regex op owns no positions
    [:* [:cat :int [:string {:sensitive? true}]]]
    {[:ev] {:sensitive? true :source :schema}}))

(deftest opaque-and-degenerate-forms-yield-no-declarations
  (are [schema] (= {} (rf.schemas/extract-sensitive-paths-from-schema schema []))
    'malli/AnyMap
    (fn [_] true)
    [:string]))
