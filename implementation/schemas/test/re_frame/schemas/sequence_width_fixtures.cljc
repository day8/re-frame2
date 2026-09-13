(ns re-frame.schemas.sequence-width-fixtures
  "Shared, host-agnostic corpus for sensitive-value redaction across
  VARIABLE-WIDTH `:cat` / `:catn` sequences (rf2-gwye.11 / rf2-fzbj.25).
  Both the JVM test (`re-frame.schemas-sensitive-test`) and the CLJS test
  (`re-frame.schemas-sequence-width-cljs-test`) register each schema at
  `[:items]`, validate `{:items value}` through `validate-app-schema!` and
  read the real trace recorder, so the redaction cannot diverge by host.

  The defect: the walker read a `:cat` / `:catn` input index as its schema
  child index. A regex element (`:*`, `:?`, a nested `:cat`) consumes zero or
  many values, so the failing input element could belong to a DIFFERENT child
  than the one the index named, and a sensitive payload's value — or a
  sensitive `:map-of` key riding `:path` — shipped verbatim.

  Every case is pure vector-form data, so the corpus loads identically on the
  JVM and under `:node-test`.")

(def secret
  "The value that must appear in NO slot of a `leak-cases` trace."
  "SEQ-WIDTH-SECRET-gwye11")

(def ^:private sensitive-map
  [:map [:token {:sensitive? true} :int]])

(def leak-cases
  "Each `{:desc :schema :value}` must reject, stamp `:sensitive?`, redact
  `:value`, and carry `secret` in no trace slot."
  [{:desc   "zero-width :* prefix in a :cat"
    :schema [:cat [:* :int] sensitive-map]
    :value  [{:token secret}]}

   {:desc   "zero-width :* prefix in a :catn"
    :schema [:catn [:prefix [:* :int]] [:payload sensitive-map]]
    :value  [{:token secret}]}

   {:desc   "empty :? optional prefix"
    :schema [:cat [:? :int] sensitive-map]
    :value  [{:token secret}]}

   {:desc   "nonzero :* repetition shifts the payload onto a later schema child"
    :schema [:cat [:* :int] sensitive-map [:* :int]]
    :value  [1 2 {:token secret}]}

   {:desc   "a nested :cat expands to two values, shifting the payload"
    :schema [:cat [:cat :int :int] sensitive-map :string]
    :value  [1 2 {:token secret} "x"]}

   {:desc   "a sensitive :map-of KEY after a nested :cat — the key would ride
             :path and :reason"
    :schema [:cat [:cat :int :int]
             [:map-of [:string {:sensitive? true}] :int]
             :string :string]
    :value  [1 2 {secret "not-an-int"} "a" "b"]}])

(def precise-cases
  "Controls: each `{:desc :schema :value :expected}` must reject and report
  `:expected` VERBATIM in `:value` — the fix is scoped to the ambiguous node
  and does not blank ordinary failures."
  [{:desc     "a variable-width :cat declaring nothing sensitive reports its value"
    :schema   [:cat [:* :int] [:map [:name :string]]]
    :value    [{:name 5}]
    :expected {:name 5}}

   {:desc     "a fixed-width :cat keeps sibling precision beside a sensitive element"
    :schema   [:cat :int sensitive-map]
    :value    ["not-an-int" {:token 1}]
    :expected "not-an-int"}

   {:desc     "a slot outside a variable-width sequence stays precise"
    :schema   [:map [:seq [:cat [:* :int] sensitive-map]] [:label :string]]
    :value    {:seq [{:token 1}] :label 42}
    :expected 42}])
