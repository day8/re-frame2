(ns re-frame.freehand.pilot-field-compiled
  "The [[re-frame.freehand.pilot-field]] declarations, PROMOTED.

  Every declaration below is its twin in [[re-frame.freehand.pilot-field]]
  with `{:compiled true}` added to the options map and NOTHING else
  changed — same docstring-free body text, same parameter vector, same
  props schema, same part addresses, same event sites. The call sites
  inside `invoice-line` are byte-identical to the interpreted file's;
  they resolve to this namespace's twins because that is where they
  lexically sit, which is the only difference living in another file
  makes.

  Separate namespaces because a view id is derived from where a
  declaration LIVES, so two declarations of one name cannot share a
  namespace. The parity suite rewrites exactly that one namespace
  component and asserts everything else is equal.

  The library's dataflow is NOT re-registered here: `pilot-field/register!`
  and `pilot-field/register-app!` are ordinary re-frame registrations that
  know nothing about execution mode, which is itself part of what
  promotion parity means."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.pilot-field :refer [buffered-kind error-region-id]]))

(v/defview field
  {:compiled true
   :props    [:map
              [:name :string]
              [:label :string]
              [:value :string]
              [:on-input :vector]
              [:on-blur {:optional true} [:maybe :vector]]
              [:error {:optional true} [:maybe :string]]
              [:busy? {:optional true} :boolean]
              [:columns {:optional true} :int]
              [:instance {:optional true} :any]]}
  [{:keys [name label value on-input on-blur error busy? columns instance]}]
  (let [error-id (error-region-id "acme-field" instance name)]
    [:label {:data-component "acme/field"
             :data-part      "root"
             :data-field     name
             :class          "acme-field"
             :style          (when columns {:--acme-field-columns columns})}
     [:span {:data-part "label"} label]
     [:input {:data-part        "control"
              :type             "text"
              :value            value
              :disabled         busy?
              :aria-invalid     (when error "true")
              :aria-describedby (when error error-id)
              :on-input         [:acme.ui.field/changed on-input ::v/value]
              :on-blur          on-blur}]
     (when error
       [:span {:data-part "error" :id error-id :role "alert"} error])]))

(v/defview buffered-field
  {:compiled true
   :props    [:map
              [:name :string]
              [:label :string]
              [:control :some]
              [:value :string]
              [:reset-key :some]
              [:on-commit :vector]
              [:error {:optional true} [:maybe :string]]
              [:busy? {:optional true} :boolean]]}
  [{:keys [name label value on-commit error busy?] :as props}]
  (let [k        (v/controller-key buffered-kind props)
        g        (v/controller-revision buffered-kind props)
        error-id (error-region-id "acme-buffered" (:control props))]
    [:label {:data-component "acme/buffered-field"
             :data-part      "root"
             :data-field     name
             :class          "acme-buffered-field"}
     [:span {:data-part "label"} label]
     [:input {:data-part        "control"
              :type             "text"
              :value            (v/sub [:acme.ui.buffered/text k g value])
              :disabled         busy?
              :aria-invalid     (when error "true")
              :aria-describedby (when error error-id)
              :on-input         [:acme.ui.buffered/edited k g ::v/value]
              :on-key-down      [:acme.ui.buffered/key-pressed k g on-commit ::v/key]
              :on-blur          [:acme.ui.buffered/committed k g on-commit]}]
     (when error
       [:span {:data-part "error" :id error-id :role "alert"} error])]))

(v/defview invoice-line
  {:compiled true
   :props    [:map [:id :any]]}
  [{:keys [id]}]
  (let [line    (v/sub [:acme.invoice/line id])
        busy?   (v/sub [:acme.invoice/normalising? id])
        gate    (v/sub [:acme.invoice/can-submit? id])
        total   (v/sub [:acme.invoice/line-total id])
        theme   (v/sub [:acme.invoice/theme])]
    [:form {:data-component "acme/invoice-line"
            :data-theme     theme
            :on-submit      {:event [:acme.invoice/submit-attempted id]
                             :prevent-default true}}
     [field {:name     "description"
             :label    "Description"
             :instance id
             :value    (or (:description line) "")
             :columns  40
             :error    (v/sub [:acme.invoice/field-error id :description])
             :on-input [:acme.invoice/field-edited id :description]
             :on-blur  [:acme.invoice/field-blurred id :description]}]
     [field {:name     "quantity"
             :label    "Quantity"
             :instance id
             :value    (or (:quantity line) "")
             :error    (v/sub [:acme.invoice/field-error id :quantity])
             :on-input [:acme.invoice/field-edited id :quantity]
             :on-blur  [:acme.invoice/field-blurred id :quantity]}]
     [field {:name     "unit-price"
             :label    "Unit price"
             :instance id
             :value    (or (:unit-price line) "")
             :error    (v/sub [:acme.invoice/field-error id :unit-price])
             :on-input [:acme.invoice/field-edited id :unit-price]
             :on-blur  [:acme.invoice/field-blurred id :unit-price]}]
     [field {:name     "account"
             :label    "Account"
             :instance id
             :value    (or (:account line) "")
             :error    (v/sub [:acme.invoice/field-error id :account])
             :on-input [:acme.invoice/field-edited id :account]
             :on-blur  [:acme.invoice/field-blurred id :account]}]
     [buffered-field {:name      "reference"
                      :label     "Reference"
                      :control   [:invoice id :reference]
                      :value     (or (v/sub [:acme.invoice/reference id]) "")
                      :reset-key (v/sub [:acme.invoice/reference-revision id])
                      :busy?     busy?
                      :error     (v/sub [:acme.invoice/reference-error id])
                      :on-commit [:acme.invoice/reference-accepted id]}]
     [:output {:data-part "total"} (str total)]
     [:button {:data-part "submit" :type "submit" :disabled (not gate)} "Save"]]))

(v/defview invoice-lines
  {:compiled true
   :props    [:map [:ids [:vector :any]]]}
  [{:keys [ids]}]
  [:div {:data-component "acme/invoice-lines"}
   (for [id ids]
     [invoice-line {:key id :id id}])])

(def by-name
  "Fixture view-name keyword -> the promoted declaration. Keyed
  identically to `pilot-field`'s roster, so one table drives both modes."
  {:field          field
   :buffered-field buffered-field
   :invoice-line   invoice-line
   :invoice-lines  invoice-lines})
