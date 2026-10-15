(ns re-frame.schemas-walker-qualified-ref-test
  "The qualified-reference predicate the off-box HTTP export fails closed on: a
  schema naming a registry schema by qualified keyword, in a real child
  position or as an implicit `:map` entry, answers true; data positions and
  unqualified names answer false. The opacity walk does not consult it, so
  validation surfaces keep such a reference walkable."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.walker :as rf.schemas.walker]))

(def ^:private qualified-ref-forms
  [:fixture/user
   [:map [:items [:vector [:map [:owner :fixture/user]]]]]
   ;; Malli's implicit reference: a `:map` entry with no child schema.
   [:map :fixture/token]
   [:map [:fixture/token {:optional true}]]
   [:map [:fixture/token]]
   ;; No namespace is exempt: Malli's own `:malli.core/schema` cannot stand as
   ;; a bare child, so any `:malli.core/*` child is a user registration.
   [:map [:x :malli.core/token]]])

(def ^:private no-qualified-ref-forms
  [:string
   ;; An unqualified registry name cannot be told from a primitive.
   [:map [:user :user]]
   ;; A qualified KEY with an explicit child schema is data, and so are
   ;; literal operands and dispatch values.
   [:map [:user/id :int]]
   [:map [:user/id {:optional true} :int]]
   [:map [:s [:enum :status/a :status/b]]]
   [:multi {:dispatch :t} [:k/a [:map [:t :keyword]]]]
   [:malli.core/schema :string]])

(deftest qualified-ref-walk-classifies-the-corpus
  (doseq [s qualified-ref-forms]
    (is (true? (rf.schemas.walker/schema-has-qualified-ref? s)) (pr-str s)))
  (doseq [s no-qualified-ref-forms]
    (is (false? (rf.schemas.walker/schema-has-qualified-ref? s)) (pr-str s))))

(deftest a-qualified-reference-stays-walkable-for-validation
  (is (false? (rf.schemas/schema-has-opaque-child? [:map [:user :fixture/user]]))))
