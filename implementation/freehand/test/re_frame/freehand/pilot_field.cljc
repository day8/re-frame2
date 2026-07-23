(ns re-frame.freehand.pilot-field
  "THE FIRST COMPONENT PILOT — a controlled field and a buffered field,
  built the way a component-library author would build them, plus the
  application that calls them.

  Everything in this namespace is CONSUMER code. It declares views with
  `v/defview`, reads with `v/sub`, emits intent as event vectors, and
  moves state with ordinary `reg-event` / `reg-sub`. It adds no substrate
  machinery, and it is the artefact the fitness harness's CASE A
  requirements (`docs/design/freehand/studio/fitness-harness.md`, §A.2)
  are judged against — one requirement at a time, in
  `pilot-field-cljs-test` and `pilot-field-dom-cljs-test`.

  ## Two components, and why they are two

  A reusable view is PROPS-ONLY by default: value in, intent out. That is
  [[field]] — a label, a native control, an error region, and nothing it
  owns. It is the shape 77 of the corpus's controlled form controls take.

  A SEMANTIC CONTROLLER is the exception a library earns when a control
  owns a protocol spanning several interactions. That is
  [[buffered-field]] — it holds a DRAFT the user is editing and commits it
  on Enter or blur, reverts it on Escape, and lets the caller REJECT it by
  advancing a revision. Its state is ordinary frame data addressed by
  `(kind, :control)` and fenced by `(kind, :reset-key)`; there is no
  registry, no reducer, no second state system.

  Splitting them is the finding, not the ceremony: three quarters of the
  corpus's inputs need nothing a draft buys, and a control that holds a
  draft it cannot be told to discard is the re-com defect this whole
  design exists to remove.

  ## The library's public surface

  Three things, and a caller reads all three off the declaration:

  - the **props schema** on each `v/defview` (`{:props [:map …]}`), which
    CLOSES the props map — a misspelled prop is an error rather than a
    value the view will silently never read;
  - the **part addresses** in [[parts]], emitted as literal `data-part`
    values under a `data-component` scope, so a stylesheet reaches a
    named region without a class contract; and
  - the **tokens** — the cascade carries them (the application scopes a
    `data-theme`), and an inline namespaced custom property is reserved
    for the genuinely dynamic value a stylesheet cannot know
    (`:columns`).

  ## The one thing the library does NOT do

  It never forwards the caller's event vector into a controlled `:on-input`
  position. A forwarded vector is a DYNAMIC handler expression: the
  interpreted walk classifies it at render time and opens the synchronous
  door, but the compiled analyzer can only classify it `:dynamic`, which
  no door roster admits — so the identical library would take the
  synchronous round trip interpreted and batch once promoted. The library
  therefore owns a LITERAL vector at every door site and carries the
  caller's event as an ARGUMENT inside it. The site proof stays static,
  the prefix stays a runtime value, and promotion cannot move the field
  from synchronous to batched.

  ## The domain it is piloted against

  An invoice-line editor: four controlled fields (description, quantity,
  unit price, account) and one buffered field (the reference the server
  may normalise or refuse). Small enough to read, and it exercises every
  A.2 row — the draft/canonical split, touch-gated errors, a derived
  submit gate both the view and the submit handler read, per-instance
  async status, and N instances with no coordination."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.control :as control]))

;; ===========================================================================
;; THE COMPONENT LIBRARY — `acme.ui`
;; ===========================================================================
;;
;; `:acme.ui/*` is LIBRARY vocabulary. The substrate reserves no namespace
;; for controls and fixes no storage path: the root below, the record
;; shape and every id are this library's choices.

(def records-root
  "Where this library keeps its controller records. A library decision,
  not a substrate one — a second library would pick its own root and the
  two would never meet."
  :acme.ui/controllers)

(def buffered-kind
  "The buffered controller family. It is half of every record key this
  library writes, so a second family addressed at the SAME domain
  identity reads its own record rather than this one's."
  :acme.ui/buffered-field)

(def parts
  "The PUBLIC part addresses each component emits, by component id.

  A part id is API: renaming one is a breaking change to every stylesheet
  that reaches it, exactly as renaming a prop is. They are literal
  `data-part` values under a `data-component` scope, so `label` and
  `control` can be common names without colliding across libraries.

  Private implementation nodes carry no part id — the `<label>` wrapper is
  `root` because callers style it; nothing else is addressable."
  {"acme/field"          ["root" "label" "control" "error"]
   "acme/buffered-field" ["root" "label" "control" "error"]})

;; ---------------------------------------------------------------------------
;; The library's dataflow — ordinary re-frame, registered by the consumer
;; ---------------------------------------------------------------------------

(defn- commit-fx
  "The buffered commit, decided against COMMITTED state: a record that is
  still current retires and produces the caller's intent carrying the
  draft; a superseded one produces nothing at all.

  Shared by the blur site and the Enter branch so the two cannot drift —
  a control whose keyboard commit and whose blur commit disagreed about
  which generation is live would commit something the user cannot see."
  [db record-key revision on-commit]
  (let [record (get-in db [records-root record-key])]
    (if (control/current? (:reset-key record) revision)
      {:db (update db records-root dissoc record-key)
       :fx [[:dispatch (conj (vec on-commit) (:draft record))]]}
      {})))

(defn- cancel-fx
  "Escape, and the Revert affordance: clear the record so a blur already
  on its way consults committed state and finds no live edit. Escape
  BEATS a late blur rather than racing it."
  [db record-key revision]
  (if (control/current? (:reset-key (get-in db [records-root record-key])) revision)
    {:db (update db records-root dissoc record-key)}
    {}))

(defn register!
  "Register the library's whole dataflow: one read and four transitions.

  A library ships this as an ordinary function its consumer calls at
  boot. There is no registry to join, no component to instantiate, and
  nothing here that a `reg-sub` and a `reg-event` do not already do."
  []
  ;; --- The read ------------------------------------------------------
  ;;
  ;; THE FENCE, read side. The draft while it belongs to the caller's
  ;; current generation; the caller's baseline otherwise. A superseded
  ;; draft is not erased — it is INVISIBLE, which is why a reset costs no
  ;; write and no render-time mutation.
  (rf/reg-sub :acme.ui.buffered/text
    (fn [db [_ record-key revision baseline]]
      (let [record (get-in db [records-root record-key])]
        (if (control/current? (:reset-key record) revision)
          (:draft record)
          baseline))))

  ;; --- The controlled field's ONE transition -------------------------
  ;;
  ;; The library owns the SITE so the door's proof stays static; the
  ;; CALLER owns the state, so all this does is deliver the live value to
  ;; the caller's own event. It runs inside the synchronous drain the
  ;; door opened, so the caller's event settles before the listener
  ;; returns exactly as a direct dispatch would.
  (rf/reg-event :acme.ui.field/changed
    (fn [_ [_ on-input text]]
      {:fx [[:dispatch (conj (vec on-input) text)]]}))

  ;; --- The buffered field's transitions ------------------------------
  ;;
  ;; ATOMIC create-or-replace, stamped with the generation the render that
  ;; produced this edit displayed. An edit that lands after the caller has
  ;; moved on is BORN STALE rather than authoritative.
  (rf/reg-event :acme.ui.buffered/edited
    (fn [{:keys [db]} [_ record-key revision text]]
      {:db (assoc-in db [records-root record-key] {:reset-key revision :draft text})}))

  (rf/reg-event :acme.ui.buffered/committed
    (fn [{:keys [db]} [_ record-key revision on-commit]]
      (commit-fx db record-key revision on-commit)))

  (rf/reg-event :acme.ui.buffered/cancelled
    (fn [{:keys [db]} [_ record-key revision]]
      (cancel-fx db record-key revision)))

  ;; The keyboard protocol as DATA. `::v/key` carries the live
  ;; `KeyboardEvent.key`, so Enter-commits and Escape-reverts are one
  ;; registered event a structural test drives by equality — no callback
  ;; body, no `.-key` read, nothing a JVM host cannot run. The cost is
  ;; stated rather than hidden: this site fires on EVERY key, and every
  ;; key that is neither Enter nor Escape settles an event that changes
  ;; nothing (measured in `pilot-field-cljs-test`).
  (rf/reg-event :acme.ui.buffered/key-pressed
    (fn [{:keys [db]} [_ record-key revision on-commit pressed]]
      (cond
        (= "Enter" pressed)  (commit-fx db record-key revision on-commit)
        (= "Escape" pressed) (cancel-fx db record-key revision)
        :else                {}))))

;; ---------------------------------------------------------------------------
;; `field` — the controlled field. Props only: value in, intent out.
;; ---------------------------------------------------------------------------

(v/defview field
  "A controlled text field: a label, a native control bound to `:value`,
  and an error region that renders only when the caller supplies one.

  `:on-input` is the caller's event PREFIX — `[:invoice/quantity-edited
  id]` — and the live text arrives as its last argument. The caller never
  writes a projection marker: the library's own site carries it, which is
  also what keeps the site a literal vector and the synchronous door's
  proof static in both execution modes.

  `:columns` is the one value the cascade cannot know, so it is the one
  value that rides as an inline custom property; everything else is a
  `data-part` a stylesheet reaches."
  {:props [:map
           [:name :string]
           [:label :string]
           [:value :string]
           [:on-input :vector]
           [:on-blur {:optional true} [:maybe :vector]]
           [:error {:optional true} [:maybe :string]]
           [:busy? {:optional true} :boolean]
           [:columns {:optional true} :int]]}
  [{:keys [name label value on-input on-blur error busy? columns]}]
  (let [error-id (str "acme-field-" name "-error")]
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

;; ---------------------------------------------------------------------------
;; `buffered-field` — the semantic controller. A draft, and a generation.
;; ---------------------------------------------------------------------------

(v/defview buffered-field
  "A field that BUFFERS: the user's keystrokes become a draft, the draft
  commits on Enter or blur, Escape reverts it, and the caller can reject
  it by advancing `:reset-key`.

  It asks for its record key and its generation FIRST — that is what makes
  it a writable, buffered controller at all — and every intent it renders
  carries the generation the render that produced it displayed, so an
  intent fired after the caller has moved on speaks for a generation
  nobody is displaying and lands nowhere.

  `:control` is the domain identity that OWNS the draft
  (`[:invoice 42 :reference]`), not a render position: a sort, a view
  rename or a parent extraction leaves it exactly where it was.
  `:reset-key` is whatever revision the caller advances when it
  establishes a new baseline — and it is required, because a control with
  no generation buffers correctly right up to the first rejection and
  then loses it."
  {:props [:map
           [:name :string]
           [:label :string]
           [:control :any]
           [:value :string]
           [:reset-key :any]
           [:on-commit :vector]
           [:error {:optional true} [:maybe :string]]
           [:busy? {:optional true} :boolean]]}
  [{:keys [name label value on-commit error busy?] :as props}]
  (let [k        (control/record-key buffered-kind props)
        g        (control/reset-revision buffered-kind props)
        error-id (str "acme-buffered-" name "-error")]
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

;; ===========================================================================
;; THE APPLICATION — an invoice-line editor
;; ===========================================================================
;;
;; From here down is ordinary application code. It knows nothing about
;; controller records: it holds its own values, its own revisions, its own
;; touched set, and it reads the library's controls the way it reads any
;; other view.

(def ^:private text-fields
  "The four controlled fields, in form order. Data, so the error and
  validity rules below are one rule rather than four."
  [:description :quantity :unit-price :account])

(defn- blank? [s] (or (nil? s) (str/blank? s)))

(defn- parse-decimal
  "The CANONICAL value behind raw typed text, or nil when the text is not
  yet a number. Raw text and canonical value are DIFFERENT THINGS: the
  field being edited echoes `\"1.\"` verbatim, and every other reader sees
  nil until the text becomes a number — nothing reformats under the
  caret."
  [s]
  (when-not (blank? s)
    (let [t (str/trim s)]
      (when (re-matches #"-?\d+(\.\d+)?" t)
        #?(:clj (Double/parseDouble t) :cljs (js/parseFloat t))))))

(defn- field-problem
  "The problem with one field's value, or nil. Pure, and the ONE place the
  rule lives — the view does not restate it and neither does the submit
  handler."
  [line f]
  (let [raw (get line f)]
    (case f
      :description (when (blank? raw) "A description is required.")
      :quantity    (let [n (parse-decimal raw)]
                     (cond (blank? raw)  "A quantity is required."
                           (nil? n)      "Quantity must be a number."
                           (<= n 0)      "Quantity must be greater than zero."))
      :unit-price  (let [n (parse-decimal raw)]
                     (cond (blank? raw) "A unit price is required."
                           (nil? n)     "Unit price must be a number."))
      :account     (when (blank? raw) "An account is required.")
      nil)))

(defn- line-problems
  "Every field problem on a line, as a map. The submit gate is derived
  from exactly this."
  [line]
  (into {} (keep (fn [f] (when-let [p (field-problem line f)] [f p]))) text-fields))

(defn register-app!
  "The application's own registrations."
  []
  (rf/reg-sub :acme.invoice/line
    (fn [db [_ id]] (get-in db [:invoice id])))

  (rf/reg-sub :acme.invoice/field-value
    (fn [db [_ id f]] (get-in db [:invoice id f])))

  ;; R-A4. The canonical value, derived — never written back into the raw
  ;; text the user is typing.
  (rf/reg-sub :acme.invoice/quantity-canonical
    (fn [db [_ id]] (parse-decimal (get-in db [:invoice id :quantity]))))

  (rf/reg-sub :acme.invoice/line-total
    (fn [db [_ id]]
      (let [q (parse-decimal (get-in db [:invoice id :quantity]))
            p (parse-decimal (get-in db [:invoice id :unit-price]))]
        (when (and q p) (* q p)))))

  ;; R-A5. A problem is a fact about the value; whether it is DISPLAYED is
  ;; a fact about the interaction. Per field, per instance.
  (rf/reg-sub :acme.invoice/field-error
    (fn [db [_ id f]]
      (let [line (get-in db [:invoice id])
            ui   (get-in db [:ui id])]
        (when (or (contains? (:touched ui) f) (:submit-attempted? ui))
          (field-problem line f)))))

  ;; R-A6. The gate, derived once, read by the view AND by the submit
  ;; handler. Neither recomputes it.
  (rf/reg-sub :acme.invoice/can-submit?
    (fn [db [_ id]]
      (let [line (get-in db [:invoice id])]
        (and (empty? (line-problems line))
             (not (:normalising? line))))))

  ;; R-A10. Busy is the in-flight write's own state, not a local flag.
  (rf/reg-sub :acme.invoice/normalising?
    (fn [db [_ id]] (boolean (get-in db [:invoice id :normalising?]))))

  (rf/reg-sub :acme.invoice/reference
    (fn [db [_ id]] (get-in db [:invoice id :reference])))

  ;; Why the caller refused. Ordinary application state, displayed through
  ;; the control's own error part — the control neither owns the reason
  ;; nor decides when it is shown.
  (rf/reg-sub :acme.invoice/reference-error
    (fn [db [_ id]] (get-in db [:invoice id :reference-error])))

  (rf/reg-sub :acme.invoice/reference-revision
    (fn [db [_ id]] (get-in db [:invoice id :reference-revision])))

  (rf/reg-sub :acme.invoice/theme
    (fn [db _] (get db :theme "light")))

  ;; --- Transitions ---------------------------------------------------

  (rf/reg-event :acme.invoice/field-edited
    (fn [{:keys [db]} [_ id f text]]
      {:db (assoc-in db [:invoice id f] text)}))

  (rf/reg-event :acme.invoice/field-blurred
    (fn [{:keys [db]} [_ id f]]
      {:db (update-in db [:ui id :touched] (fnil conj #{}) f)}))

  (rf/reg-event :acme.invoice/submit-attempted
    (fn [{:keys [db]} [_ id]]
      (let [db' (assoc-in db [:ui id :submit-attempted?] true)]
        ;; The gate is READ, not recomputed: one definition, two readers.
        (if (rf/subscribe-once [:acme.invoice/can-submit? id])
          {:db (update db' ::submitted (fnil conj []) id)}
          {:db db'}))))

  ;; The buffered reference's accepted destination. The caller advances
  ;; the revision on every baseline decision it makes — that is what makes
  ;; a rejection that reasserts the SAME value visible to the control.
  (rf/reg-event :acme.invoice/reference-accepted
    (fn [{:keys [db]} [_ id text]]
      {:db (-> db
               (assoc-in [:invoice id :reference] text)
               (assoc-in [:invoice id :reference-error] nil)
               (update-in [:invoice id :reference-revision] inc)
               (update ::accepted (fnil inc 0)))}))

  ;; R-A9. An asynchronous normalisation of the committed text. The
  ;; request carries a token; only the settle that names the CURRENT token
  ;; may land, so a superseded reply is inert rather than racing.
  (rf/reg-event :acme.invoice/reference-submitted
    (fn [{:keys [db]} [_ id text token]]
      {:db (-> db
               (assoc-in [:invoice id :normalising?] true)
               (assoc-in [:invoice id :normalise-token] token)
               (assoc-in [:invoice id :submitted-reference] text))}))

  (rf/reg-event :acme.invoice/reference-normalised
    (fn [{:keys [db]} [_ id token normalised]]
      (if (= token (get-in db [:invoice id :normalise-token]))
        {:db (-> db
                 (assoc-in [:invoice id :normalising?] false)
                 (assoc-in [:invoice id :reference] normalised)
                 (update-in [:invoice id :reference-revision] inc)
                 (update ::normalised (fnil conj []) normalised))}
        {})))

  ;; THE REJECTION. The caller refuses the committed draft and stands by
  ;; the value it already had — advancing the revision is what says "this
  ;; is a NEW baseline decision" in the one case value-equality cannot
  ;; see.
  (rf/reg-event :acme.invoice/reference-refused
    (fn [{:keys [db]} [_ id reason]]
      {:db (-> db
               (assoc-in [:invoice id :normalising?] false)
               (assoc-in [:invoice id :reference-error] reason)
               (update-in [:invoice id :reference-revision] inc)
               (update ::refused (fnil inc 0)))}))

  (rf/reg-event :acme.invoice/theme-changed
    (fn [{:keys [db]} [_ theme]]
      {:db (assoc db :theme theme)})))

;; ---------------------------------------------------------------------------
;; The application's views
;; ---------------------------------------------------------------------------

(v/defview invoice-line
  "One invoice line: four controlled fields, one buffered field, a derived
  total, and a submit button disabled off the same gate its handler
  reads.

  The theme rides ONE `data-theme` on the form, so a theme switch is one
  attribute the cascade resolves for every descendant — no token
  subscription per leaf, and no remount."
  {:props [:map [:id :any]]}
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
             :value    (or (:description line) "")
             :columns  40
             :error    (v/sub [:acme.invoice/field-error id :description])
             :on-input [:acme.invoice/field-edited id :description]
             :on-blur  [:acme.invoice/field-blurred id :description]}]
     [field {:name     "quantity"
             :label    "Quantity"
             :value    (or (:quantity line) "")
             :error    (v/sub [:acme.invoice/field-error id :quantity])
             :on-input [:acme.invoice/field-edited id :quantity]
             :on-blur  [:acme.invoice/field-blurred id :quantity]}]
     [field {:name     "unit-price"
             :label    "Unit price"
             :value    (or (:unit-price line) "")
             :error    (v/sub [:acme.invoice/field-error id :unit-price])
             :on-input [:acme.invoice/field-edited id :unit-price]
             :on-blur  [:acme.invoice/field-blurred id :unit-price]}]
     [field {:name     "account"
             :label    "Account"
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
  "Two lines on one page — the same two components, two buffered records
  and eight controlled fields, with no coordination between them. Each
  control's state is addressed by the domain identity that owns it, so
  there is nothing for two instances to collide over."
  {:props [:map [:ids [:vector :any]]]}
  [{:keys [ids]}]
  [:div {:data-component "acme/invoice-lines"}
   (for [id ids]
     [invoice-line {:key id :id id}])])

(def by-name
  "Fixture view-name keyword -> the interpreted declaration. The promoted
  twins in `pilot-field-compiled` are keyed identically, so one table
  drives both modes."
  {:field          field
   :buffered-field buffered-field
   :invoice-line   invoice-line
   :invoice-lines  invoice-lines})
