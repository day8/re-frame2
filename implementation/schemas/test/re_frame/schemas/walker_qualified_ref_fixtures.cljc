(ns re-frame.schemas.walker-qualified-ref-fixtures
  "Shared corpus for `re-frame.schemas.walker/schema-has-qualified-ref?`,
  asserted by the JVM and CLJS qualified-reference tests so the classification
  cannot diverge by host.

  A qualified keyword in a real child-schema position names a registry schema,
  and so does a `:map` entry whose qualified key stands alone. Map keys with an
  explicit child, literal operands and dispatch values are data, and an
  unqualified name cannot be told from a primitive.")

(def qualified-ref-forms
  "Each names a registry schema by qualified keyword: true."
  [:fixture/user
   [:map [:user :fixture/user]]
   [:vector :fixture/user]
   [:map-of :keyword :fixture/user]
   [:map [:items [:vector [:map [:owner :fixture/user]]]]]
   [:multi {:dispatch :t} [:k/a :fixture/user]]
   ;; Malli's implicit reference: a `:map` entry with no child schema.
   [:map :fixture/token]
   [:map [:fixture/token {:optional true}]]
   [:map [:fixture/token]]
   ;; No namespace is exempt: Malli's own `:malli.core/schema` cannot stand as
   ;; a bare child, so any `:malli.core/*` child is a user registration.
   [:map [:x :malli.core/token]]])

(def no-qualified-ref-forms
  "None names a registry schema by qualified keyword: false."
  [:string
   ;; An unqualified registry name cannot be told from a primitive.
   :user
   [:map [:user :user]]
   [:map [:user :string]]
   ;; A qualified KEY with an explicit child schema is data.
   [:map [:user/id :int]]
   [:map [:user/id {:optional true} :int]]
   [:map [:s [:enum :status/a :status/b]]]
   [:map [:s [:= :status/a]]]
   [:multi {:dispatch :t} [:k/a [:map [:t :keyword]]]]
   [:malli.core/schema :string]
   [:map [:user [:map [:id :int] [:token {:sensitive? true} :string]]]]])
