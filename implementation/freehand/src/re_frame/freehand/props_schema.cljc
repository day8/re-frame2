(ns re-frame.freehand.props-schema
  "THE PROPS-SCHEMA DECISION — one function, consulted by both execution
  modes (D011).

  A declaration MAY carry a props schema:

      (v/defview todo-row
        {:compiled true
         :props    [:map [:id :int] [:text :string]]}
        [{:keys [id text]}]
        [:li.row {:data-id id} text])

  It is OPTIONAL in `:re-frame.freehand/v1`, for compiled and interpreted
  declarations alike. The compiler rejects what it cannot lower, never what
  lacks documentation, and a substrate that demanded an annotation on every
  boundary would be charging ceremony for the promotion of one measured hot
  view. Where a schema IS mandatory is a matter of build and catalogue
  policy — public library surfaces, and any view whose report claims
  generated coverage — not of the grammar.

  ## What a schema decides

  Exactly one thing, and the same thing in both modes: **a declared schema
  CLOSES the props map**. Declaring `:props` states which props a caller may
  supply; supplying a key it does not name is an error rather than a value
  the view will silently never read. An absent schema leaves the map OPEN,
  which is why absence is reported as absence and never as `:any` — an
  undeclared contract and a deliberately permissive one are different facts.

  Closure is the default because the alternative is silent tolerance. A view
  that really does forward arbitrary attributes says so, once, in the schema
  itself:

      [:map {:closed false} [:to :keyword]]

  ## Why the decision lives here and not in each mode

  [[closing-keys]] and [[undeclared]] are pure, host-neutral and
  mode-neutral, and both modes call them rather than each implementing the
  rule. That is what makes a schema mean ONE thing: the compiled analyzer
  applies them to the literal keys at a call site it can see at build time,
  and the boundary applies them to the props map a call actually delivers.
  Static knowledge changes WHEN a violation can be reported, never WHICH
  props are legal — so promotion cannot change a view's contract, only the
  moment its breach surfaces.

  `:key` and `:children` are RESERVED slots and never schema entries. `:key`
  selects sibling identity and is stripped before props delivery; children
  arrive as trailing forms and are governed by `:children-policy`, which is
  descriptor metadata. Encoding either as an ordinary prop would give it two
  contracts that could disagree. A literal schema that names one is refused at
  the DECLARATION — [[reserved-declared]] and
  [[reserved-declaration-message]] — because the breach is in the contract
  itself and no call could repair it.

  The schema is Malli-shaped vector data, the repository's existing
  convention (Spec 010). It is held as inert data: nothing here validates a
  VALUE, so no schema library is dragged onto the classpath by declaring
  one. Inert also means AUTHORED — a declaration publishes the form it was
  given and never the value an expression would evaluate to ([[inert-form]]),
  which is what keeps an OPAQUE schema open at every surface instead of open
  where it is read as a form and closed where it is read as a value. Value validation, when a port wants it, goes through Spec 010's
  validator seam.

  Per [Spec 004D §Props schemas](../../../../spec/004D-Freehand-Compiled-Grammar.md#props-schemas)."
  (:require [clojure.string :as str]))

(def reserved-keys
  "The two props keys no schema may govern, because each already has a
  contract of its own: `:key` is stripped by the call ABI before props are
  delivered, and `:children` arrives as trailing forms under
  `:children-policy`."
  #{:key :children})

(defn- map-schema-properties
  "The optional properties map of a literal `[:map {…} …]` schema."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (let [p (second schema)]
      (when (map? p) p))))

(defn map-schema?
  "True when `schema` is a LITERAL `[:map …]` — the only shape whose top-level
  keys can be read at compile time. A schema behind a registry reference or a
  runtime expression is opaque here, and an opaque schema closes nothing: a
  guess about keys nobody can see would be worse than the open map it
  replaced."
  [schema]
  (and (vector? schema) (= :map (first schema))))

(defn inert-form
  "The form a DECLARATION emits to carry `schema` — inert data that yields the
  authored schema itself and never something the author did not write.

  A literal `[:map …]` is already inert: it evaluates to itself, and it stays
  unwrapped because a compiled parent reads a child's schema off Var metadata
  at BUILD time, where that metadata is a FORM rather than a value.

  Anything else is an EXPRESSION, and evaluating it is how the one decision
  becomes two. The compiled front end can only ever see the expression, so it
  reads an opaque schema and leaves the map open; an evaluated expression hands
  the runtime descriptor a literal `[:map …]` and closes the very same map at
  render. Quoting keeps ONE schema at every surface — and evaluates the
  author's expression exactly zero times, which is what makes a schema inert
  data rather than a value the declaration computes."
  [schema]
  (if (map-schema? schema) schema (list 'quote schema)))

(defn declared-keys
  "The top-level prop keys a literal `[:map …]` schema names, in declaration
  order; `nil` when the schema is absent or opaque.

  This is the roster for ORDER-sensitive uses — slot layout, comparator
  order, catalogue listings. [[closing-keys]] answers the different question
  of what the map is closed against."
  [schema]
  (when (map-schema? schema)
    (let [entries (rest schema)
          entries (if (map? (first entries)) (rest entries) entries)]
      (into []
            (keep #(when (and (vector? %) (keyword? (first %))) (first %)))
            entries))))

(defn reserved-declared
  "The [[reserved-keys]] a literal `[:map …]` schema NAMES, in declaration
  order; empty when it names none, and empty for every opaque schema, whose
  entries nobody can read.

  This is the declaration-time question, and the opposite one to
  [[undeclared]]. `undeclared` never REPORTS a reserved key, because the call
  ABI governs it and sending an author to a schema that was not allowed to
  mention it would be a wrong instruction. That silence is only honest if no
  schema mentions one — a schema that did would advertise to a catalogue a prop
  the ABI can never deliver, and would be a second contract for something that
  already has one."
  [schema]
  (into [] (filter reserved-keys) (declared-keys schema)))

(defn open-map?
  "True when a map schema opts OUT of closure with an explicit
  `[:map {:closed false} …]`.

  The escape is deliberately explicit and deliberately spelled in the schema
  rather than as a second `defview` option: a view that forwards arbitrary
  attributes — `v/route-link` passing every unrecognised key to its `<a>` —
  is making a statement about its props, and that statement belongs where
  the rest of them are."
  [schema]
  (false? (:closed (map-schema-properties schema))))

(defn closing-keys
  "The key roster `schema` closes a props map against, or `nil` when it
  closes nothing.

  `nil` — meaning the map stays OPEN — for all three honest reasons: no
  schema was declared, the schema is opaque so its keys cannot be read, or
  it says `{:closed false}` and means it. A caller of this function needs no
  further branch: a roster means close against it, `nil` means do not."
  [schema]
  (when (and (some? schema) (not (open-map? schema)))
    (declared-keys schema)))

(defn undeclared
  "The props keys `prop-keys` carries that `closing` does not admit, sorted;
  empty when the call conforms.

  `closing` is a [[closing-keys]] roster, so `nil` yields nothing — an open
  map admits everything. Reserved keys are never reported: they are governed
  elsewhere, and naming them here would send an author to edit a schema that
  was never allowed to mention them."
  [closing prop-keys]
  (if (nil? closing)
    []
    (let [admitted (set closing)]
      (into []
            (comp (remove admitted)
                  (remove reserved-keys)
                  (distinct))
            (sort-by str prop-keys)))))

(def ^:private reserved-recovery
  "How the call ABI already delivers each reserved slot — the sentence a
  refused declaration ends on, because an author who is told only that a key
  is reserved still has to go and find out where it went."
  {:key      (str ":key selects sibling identity and is stripped before props "
                  "are delivered, so callers pass it at the call site and the "
                  "view body never sees it")
   :children (str ":children arrives as the trailing forms of a call and is "
                  "governed by :children-policy, which is a declaration option "
                  "rather than a prop")})

(defn reserved-declaration-message
  "The sentence a declaration is refused with when its schema names a
  [[reserved-keys]] slot — the view, the offending keys, and the ABI each one
  already has."
  [view reserved]
  (str view " declares " (str/join ", " (map pr-str reserved))
       " in its props schema, and " (if (next reserved) "those keys are" "that key is")
       " reserved by the call ABI: "
       (str/join "; " (map reserved-recovery reserved))
       ". Remove the schema " (if (next reserved) "entries" "entry")
       " — a schema that named " (if (next reserved) "them" "it")
       " would advertise a prop no call can deliver."))

(defn violation-message
  "The one sentence both modes report for the same breach — the compiled
  analyzer at build time and the boundary at render — so an author who meets
  it once recognises it in either mode and never has to learn that promoting
  a view changed what its props mean."
  [view-id closing bad]
  (str view-id " declares a props schema, which closes its props map to "
       (str/join ", " (map pr-str closing))
       ". The call supplies undeclared prop" (when (next bad) "s") " "
       (str/join ", " (map pr-str bad))
       ". Add " (if (next bad) "them" "it") " to the schema, correct the "
       "spelling, or declare the view open with [:map {:closed false} …] if "
       "it really does forward arbitrary props."))
