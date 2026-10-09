(ns re-frame.schemas.walker-literal-operand-fixtures
  "Shared corpus for the operator-aware opacity walk (`schema-has-opaque-child?`),
  asserted by the JVM and CLJS literal-operand tests so the classification
  cannot diverge by host.

  A Malli vector form's tail is a child schema only for structural ops. For
  literal and config ops (`:=`, `:enum`, comparators, `:re`) the tail is data,
  and recursing into it would false-flag an ordinary literal as an opaque
  compiled child."
  (:require [malli.core :as m]))

(def not-opaque-forms
  "Walkable schemas: `schema-has-opaque-child?` is false for each."
  [[:= 42]
   [:cat [:= :demo/e] [:= 42]]
   [:map [:code [:enum 200 404]] [:name :string]]
   ;; A `:map` entry KEYED `:ref` is data, not the reference form.
   [:map [:ref {:optional true} :string]]
   ;; A registry reference cannot be told from a primitive without a registry
   ;; consult, so a bare keyword stays walkable.
   :fixture/user])

(def opaque-forms
  "Schemas the walk cannot prove flag-free, so each fails closed (true): a
  compiled value at the root, in a later `:map` slot and as a container
  element; an unknown operator; and a local `{:registry ...}` at the root, on a
  `:map` (the check reads the props, not the op) and nested."
  [(m/schema [:string {:sensitive? true}])
   [:map [:a :int] [:b (m/schema [:int])]]
   [:vector (m/schema [:int])]
   [:my/custom-op [:string]]
   [:schema {:registry {:fixture/user [:map [:pw {:sensitive? true} :string]]}}
    :fixture/user]
   [:map {:registry {:fixture/pw [:string {:sensitive? true}]}} [:pw :fixture/pw]]
   [:map [:auth [:schema {:registry {:fixture/user [:map [:pw {:sensitive? true} :string]]}}
                 :fixture/user]]]])

(def registry-ref-forms
  "Explicit `[:ref ...]` forms, at the root and nested. The walk resolves the
  reference nowhere, so each fails closed (true)."
  [[:ref :fixture/user]
   [:map [:home [:ref :fixture/user]]]])
