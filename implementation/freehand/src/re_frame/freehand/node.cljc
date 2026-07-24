(ns re-frame.freehand.node
  "The ONE canonicalizer for Freehand's structural tree — the builders that
  assemble a node in canonical form, and the mount seam a declared view is
  rendered through.

  Both execution modes reach the tree through this namespace, and that is
  the whole point. The interpreted walk ([[re-frame.freehand.tree]]) turns
  markup FORMS into child values and hands them here; a compiled view's
  emitted body computes its child values at run time and hands them here.
  Neither owns a canonical form of its own, so the two cannot disagree
  about what `[:section.panel {:class {:open true}} …]` denotes — the
  promotion of one declaration from interpreted to compiled cannot change
  the value, because only the front end changed.

  Nothing here walks a form. A vector is markup, and markup is the
  interpreted front end's business: the compiled front end resolved its
  structure at compile time and never produces one. That asymmetry is
  D010 made mechanical — see [[collect]].

  Layered BELOW `re-frame.freehand` deliberately: the public door requires
  this namespace, so a compiled declaration's emitted body reaches the
  builders through the same single require the declaration already has.
  That is what makes `{:compiled true}` a one-line change.

  Normative owners:
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md)
  (the node schema and the conversion table) and
  [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../spec/004D-Freehand-Compiled-Grammar.md)
  (the compiled tier that emits calls to these builders)."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.freehand.controlled :as controlled]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.events :as events]
            [re-frame.freehand.rules :as rules]
            [re-frame.freehand.top-layer :as top-layer]))

#?(:clj (set! *warn-on-reflection* true))

(defn- malformed!
  [where reason extra]
  (error/throw-error!
    :rf.error/ui-tree-malformed
    where
    reason
    {:recovery :no-recovery :extra extra}))

(defn- shape
  "A bounded SHAPE summary of an offending value — never the value itself
  (Spec 015 §Data-Classification)."
  [x]
  (error/diag-value-summary x))

(defn- type-name [x]
  (name (:type (shape x))))

;; ---------------------------------------------------------------------------
;; Data — what a recorded value may contain, at every depth
;; ---------------------------------------------------------------------------
;;
;; Spec 004B §The node schema: the tree is plain, serialisable data, and EDN
;; print/read round-trips it losslessly. Two slots record a value the tree did
;; not build — a view boundary's `:props` and an element's `:events` — and both
;; are exactly where a host value can walk in. The rule they share:
;;
;;   THE OPAQUE MARKER OCCUPIES A SITE, NEVER A VALUE INSIDE ONE.
;;
;; A prop or handler that IS a function records as `{:rf.ui/opaque :fn}`: the
;; site is named by the grammar, so its existence and spelling stay testable
;; while its behaviour does not. Below that key the grammar names no sites, so
;; a marker written there would claim one that does not exist and would quietly
;; replace a value the author will go looking for — and an event vector, which
;; is compared as data in a test and dispatched as data at run time, would
;; record an intent that no longer means what the site does. So a non-data
;; value nested inside a recorded value is REFUSED, at the site that recorded
;; it, exactly as every other value outside the tree's closed grammars is.

(defn- nan?
  "Is `x` `##NaN` — the one value in the scalar grammar below that prints
  and reads back and is STILL not itself?

  Asked through NUMERIC equality, not `=` and not a host `isNaN`. `(= x x)`
  cannot answer it: `clojure.lang.Util/equiv` returns true on reference
  identity before it looks at the values, so a value compared with itself
  is equal to itself by construction. A host `isNaN` cannot be asked
  either — the JVM's takes a `double`, and a Ratio or a BigDecimal cannot
  be handed to one. `==` is defined on every number on both hosts and
  compares numerically, which is the only comparison NaN fails."
  [x]
  (and (number? x) (not (== x x))))

(defn- edn-scalar?
  "Is `x` an EDN leaf — a value that prints and READS BACK EQUAL on the
  host it was written on?

  Equal, not merely readable, because the tree is an equality input: a
  fingerprint compares one, a structural test asserts on one, and a
  reconciler is handed one. `##NaN` is the single value that satisfies
  print/read and fails that: it is not equal to itself, so a tree holding
  one is not equal to itself either. It is refused with everything else
  the tree cannot promise."
  [x]
  (or (string? x) (keyword? x) (boolean? x) (nil? x) (symbol? x)
      (and (number? x) (not (nan? x)))
      (uuid? x)
      ;; A JVM character is ordinary EDN — `\\a` prints and reads back —
      ;; and the host it does not exist on has nothing to disagree about:
      ;; ClojureScript reads `\\a` as the one-character string it already
      ;; accepts, which is what `char?` answers there.
      (char? x)
      ;; EDN's other built-in tagged literal. `inst?` is NOT the predicate:
      ;; it answers true for `java.time.Instant`, which prints `#object[…]`
      ;; and does not read back. The instant that prints `#inst` is the
      ;; host's own, and it is the one both readers reconstruct.
      (instance? #?(:clj java.util.Date :cljs js/Date) x)))

(defn- non-data
  "`[path value]` for the FIRST value inside `x` that is not data, or nil
  when `x` is data all the way down. `path` is the map keys and vector
  indices walked to reach it, so a diagnostic names the offending SITE
  rather than the whole prop.

  DATA is the EDN value grammar exactly — the scalars above, and the four
  collections EDN spells: map, vector, set, list. The arms below name
  those four rather than asking `coll?`, because `coll?` is a question
  about a host INTERFACE and collections implement it with no EDN spelling
  at all: a `defrecord` value answers `map?` while printing `#user.R{…}`,
  which no EDN reader has a tag for, and a persistent QUEUE answers a
  collection predicate on either host while printing outside EDN on both.
  Such a value was accepted, printed, and failed to read back — the round
  trip this rule exists to promise.

  READ-ONLY and short-circuiting — nothing is rebuilt, and the walk stops
  at the first offender. The ordinary case (a scalar prop, or a small map
  of them) costs one predicate per value and allocates nothing, which is
  what lets the rule hold at every depth without a sanitizing copy of
  every prop on every render."
  [x]
  (cond
    (edn-scalar? x)
    nil

    ;; A QUEUE, named — the one collection whose EDN status differs BY
    ;; HOST, which is the sharpest reason the rule cannot be a host
    ;; predicate. On the JVM it prints `#object[…]` and no reader takes it;
    ;; in ClojureScript it satisfies `ISeq` and prints `#queue […]`, a tag
    ;; `clojure.edn` does not have. Accepting it where it happens to read
    ;; back would make one declaration answer a tree on one host and a
    ;; refusal on the other, and the tree is ONE value on two hosts.
    (instance? #?(:clj clojure.lang.PersistentQueue :cljs PersistentQueue) x)
    [[] x]

    ;; `reduce-kv` over a vector answers index/element — exactly the path
    ;; segment wanted — so maps and vectors share one arm.
    (or (and (map? x) (not (record? x))) (vector? x))
    (reduce-kv (fn [_ k v]
                 (if-let [found (or (non-data k)
                                    (when-let [[p bad] (non-data v)]
                                      [(into [k] p) bad]))]
                   (reduced found)
                   nil))
               nil
               x)

    ;; A set or a seq has no position worth naming, so the path stops here.
    ;; `seq?` rather than `list?`: EDN's list is anything that prints as
    ;; `(…)`, which is every `ISeq`, and `list?` additionally answers true
    ;; for a PersistentQueue.
    (or (set? x) (seq? x))
    (reduce (fn [_ v] (when-let [found (non-data v)] (reduced found))) nil x)

    :else
    [[] x]))

(defn- at-path
  "The ` at [:config :callback]` clause of a diagnostic, or nothing when the
  offending value IS the recorded value."
  [path]
  (if (seq path) (str " at " (pr-str path)) ""))

(defn- offender-name
  "How a refusal NAMES the value that may not be recorded. `##NaN` by its
  own spelling rather than as `number`, because the number grammar is
  otherwise wide open and `carries a number` would leave the refusal
  unreadable."
  [x]
  (if (nan? x) "##NaN" (type-name x)))

;; ---------------------------------------------------------------------------
;; The namespace context (SVG / MathML)
;; ---------------------------------------------------------------------------
;;
;; Spec 004B §Namespaces. A view is declared without knowing where it will be
;; mounted, so `<svg>`-ness cannot be a compile-time fact about a subtree that
;; crosses a view boundary: the context is established by the element that
;; opens it and read by the elements built underneath it, at build time.
;;
;; It is a dynamic binding rather than a threaded argument because BOTH front
;; ends have to see the same value across a boundary they do not share a call
;; frame with — a compiled `<svg>` may hold an interpreted child, and vice
;; versa. One binding, read by one rule, is the only shape that stays
;; consistent when the modes interleave.

(def ^:dynamic *ns-context*
  "The namespace context (`:svg`, `:mathml`, or nil for HTML) elements are
  currently being built under."
  nil)

;; ---------------------------------------------------------------------------
;; Children — canonical form
;; ---------------------------------------------------------------------------
;;
;; Spec 004B §Child normalization. One vector, document order, adjacent text
;; coalesced, empties dropped, runs spliced in place. The rules run HERE, once,
;; so no node can be built in a non-canonical shape: one semantic tree has
;; exactly one representation, and that is what makes the tree a legitimate
;; fingerprint and equality input.

(defn node?
  "Is `x` a built structural node? The four map-shaped variants are
  discriminated by their required fields; text nodes are host strings and
  are handled separately."
  [x]
  (and (map? x)
       (or (contains? x :tag)
           (contains? x :view-id)
           (contains? x :html)
           (contains? x :children))))

(defn- conj-text
  "Append text, coalescing it into the preceding run when there is one.

  An EMPTY string is dropped on arrival rather than collected and removed
  at the end. That is the same canonical form by a shorter route: `\"\"`
  contributes nothing to a coalesced run, so a run containing it and a run
  without it are the same string, and a body of nothing but empties
  accumulates nothing instead of accumulating strings to discard. The
  pass that removed them afterwards rebuilt every children vector in the
  tree — measured on B1 at 456 bytes per node, on nodes whose children
  were almost never empty."
  [acc s]
  (cond
    (= "" s)              acc
    (string? (peek acc))  (conj (pop acc) (str (peek acc) s))
    :else                 (conj acc s)))

(defn ^:no-doc opaque-child!
  "The rejection every child value that is not content lands on. `vector?`
  gets D010's ladder by name: a runtime value is not a template, and the
  compiled tier has no interpreter to hand it to.

  Public so the compiled tier's BROWSER child seam
  ([[re-frame.freehand.react/child-elements]]) refuses a runtime markup
  value with the SAME sentence the structural tier raises — one D010
  refusal, reached from both hosts, rather than a second one that could
  drift (rf2-drpa3.130)."
  [where form]
  (malformed!
    where
    (if (vector? form)
      (str "A compiled view produced a vector where a child was expected. Markup is "
           "compiled, not interpreted: a runtime value is never a template, and there "
           "is no interpreter inside a compiled view to walk one. Make the structure "
           "lexically visible, pass the computed value into visible structure, extract "
           "a declared child view (it may stay interpreted), or keep this view "
           "interpreted.")
      (str "A view body produced a " (type-name form) " where a child was expected. "
           "A child is markup, text (a string or a number), a run of children, or "
           "nothing (nil / false)."))
    {:value (shape form)}))

(defn refuse-metadata-key!
  "Refuse a markup vector carrying `^{:key …}` METADATA.

  Freehand reads no metadata-carried contract, so nothing on either
  interpreted walk looked for one and the key was simply GONE: the tree
  came out well-formed, unkeyed, and silent, and the only symptom was
  React reusing the wrong rows at run time. It is the Reagent spelling, so
  it is the one a hand arriving from re-frame v1 reaches for first, and
  the compiled tier had been refusing it as a build failure all along —
  one source, two answers.

  Both walks refuse it here instead, with the same sentence: a key is a
  PROPS slot in this substrate, and there is exactly one spelling of it.

  `where` names the raising site. Cheap enough to sit in the child fold:
  a vector with no metadata answers `nil` and the `contains?` never runs."
  [where form]
  (when (contains? (meta form) :key)
    (malformed!
      where
      (str "A child carries a :key in its METADATA — ^{:key …}. Freehand "
           "reads no metadata-carried contract, so this key would reach "
           "neither the tree nor React, and the list it belongs to would "
           "render silently unkeyed. A key is a PROPS slot here, in both "
           "modes and on both hosts: write [:li {:key k} …], or "
           "[:<> {:key k} …] around content that has no props map of its "
           "own. (Reagent honours the metadata spelling; this substrate "
           "has one spelling for a key, and refuses the other rather than "
           "dropping it.)")
      {:value (shape form)})))

(defn collect
  "Fold one child value into the canonical children accumulator `acc`.

  `walk` is the interpreted front end's markup walker — a one-argument fn
  turning a hiccup vector into a node — or **nil**, which is what a
  compiled body passes. That single argument is the whole language
  boundary: with a walker, a vector is markup; without one, a vector is a
  runtime value that a compiled template may not interpret, and it is
  rejected by name (D010). There is no third behaviour and no fallback
  arm, so a compiled view cannot acquire an interpreter by accident.

  `where` names the raising site for the diagnostic."
  [walk where acc form]
  (cond
    (nil? form)            acc
    (boolean? form)        acc
    (string? form)         (conj-text acc form)
    (number? form)         (conj-text acc (conv/js-number-str form))
    (conv/child-run? form) (reduce #(collect walk where %1 %2) acc form)
    (node? form)           (conj acc form)
    (seq? form)            (reduce #(collect walk where %1 %2) acc form)
    (and walk (vector? form)) (do (refuse-metadata-key! where form)
                                  (conj acc (walk form)))
    :else                  (opaque-child! where form)))

(defn children
  "The canonical children vector for already-evaluated child VALUES — the
  compiled front end's entry (no walker, so a vector is rejected). Empty
  when the values carry no content."
  [& xs]
  (reduce #(collect nil 're-frame.freehand/render %1 %2) [] xs))

(defn walked-children
  "The canonical children vector for markup FORMS, walked by `walk` — the
  interpreted front end's entry."
  [walk where forms]
  (reduce #(collect walk where %1 %2) [] forms))

;; ---------------------------------------------------------------------------
;; Runs
;; ---------------------------------------------------------------------------

(defn keyed-run
  "Tag a compiled list site's rows as a spliceable run, having proved every
  row carries a `:key` and that no two keys collide.

  Keys compare after React's string coercion, so `1` and `\"1\"` are one
  key: react-dom/server renders both silently and the client warns, which
  is the wrong end of the pipeline to learn it. Diagnosing at the
  compile-indexed list site is earlier and louder."
  [rows]
  (let [rows (vec rows)]
    (reduce (fn [seen row]
              (when-not (and (map? row) (contains? row :key))
                (malformed!
                  're-frame.freehand/render
                  "A list row lost its :key — a compiled keyed run carries one key per row."
                  {:row (shape row)}))
              (let [k (:key row)
                    s (if (number? k) (conv/js-number-str k) (str k))]
                (when (contains? seen s)
                  (error/throw-error!
                    :rf.error/ui-duplicate-key
                    're-frame.freehand/render
                    (str "Duplicate key " (pr-str k) " in a keyed list. Keys compare after "
                         "React's string coercion, so the key 1 collides with the key \"1\"; "
                         "a key must be unique per list site.")
                    {:recovery :key-each-row-uniquely
                     :extra    {:key (shape k)}}))
                (conj seen s)))
            #{}
            rows)
    (conv/mark-child-run rows)))

;; ---------------------------------------------------------------------------
;; Element nodes
;; ---------------------------------------------------------------------------

(defn- attr-entry
  "One author-space `:attrs` entry, or nil when the entry is dropped.

  A nil value is an ABSENT attribute — the law an author relies on to
  write a conditional value — with the ONE exception the controlled-input
  contract already states. On a supported native control, absence of a
  `value` / `checked` slot is the HOST's own signal that the node is
  UNCONTROLLED, so an explicitly present nil there is not nothing: it is a
  controlled field with nothing in it, and the door has already put the
  element's event sites on the synchronous lane for it. Dropping the entry
  would leave the structural tree saying the opposite of what the React
  emitter says about the same declaration, and would put a server render
  that omits the attribute against a client render that sets it — a
  hydration seam, from one authored word.

  So the entry is KEPT, carrying the same controlled-empty value the React
  emitter writes ([[re-frame.freehand.controlled/empty-control-slot]]) —
  one projection, so the two hosts describe one declaration the same way.
  Read as an ENTRY and not as a value, because the empty `checked` IS
  `false`: a truth test here would drop exactly the unchecked case.

  `multi?` is the element's
  [[re-frame.freehand.controlled/multiple-select?]] verdict, settled once
  for the whole element by [[element]] because it is a property of the
  element and not of the attribute being folded. It changes exactly one
  thing: what EMPTY means on a `<select multiple>`'s `value`, whose
  selection is a list of option values rather than a scalar."
  [tag multi? k v]
  (if (nil? v)
    (when-some [empty-slot (controlled/empty-control-slot tag k multi?)]
      [k (val empty-slot)])
    (let [select-value? (controlled/select-value-slot? tag k)
          semantic      (if select-value? (conv/select-value v) (conv/attr-value v))]
      (when (= :re-frame.freehand.conversion/reject semantic)
        (malformed!
          're-frame.freehand/render
          (str "The " k " attribute on " tag " carries a " (type-name v)
               ", which has no attribute spelling. An attribute value is a string, a "
               "keyword, a symbol, a number or a boolean; :class and :style have their "
               "own richer grammars."
               (when select-value?
                 (str " A <select>'s :value additionally takes a SEQUENTIAL collection "
                      "of them, which is what a multiple select's selection is; a set "
                      "is not one, because its order is not a value the two hosts "
                      "agree on.")))
          {:attr k :value (shape v)}))
      [k semantic])))

(defn style-map
  "One authored `:style` value in canonical semantic space."
  [tag v]
  (when-not (map? v)
    (malformed!
      're-frame.freehand/render
      (str "The :style value on " tag " is a " (type-name v)
           "; :style is a map of CSS property to value.")
      {:attr :style :value (shape v)}))
  (reduce-kv (fn [m k x]
               (if (nil? x)
                 m
                 (let [css-name (name k)]
                   (assoc m (keyword css-name) (conv/css-value css-name x)))))
             {} v))

(defn class-string
  "The canonical `:class` string for `tag`: `sugar` classes FIRST in source
  order, then the authored `:class` form's classes (Spec 004B §`.class#id`
  sugar vs explicit `:class`)."
  [tag sugar v]
  (let [parts (conv/class-parts v)]
    (when (= :re-frame.freehand.conversion/reject parts)
      (malformed!
        're-frame.freehand/render
        (str "The :class value on " tag " is outside the class grammar. Write a string, "
             "a keyword, a vector of them in order, or a flag map whose truthy entries "
             "name classes.")
        {:attr :class :value (shape v)}))
    ;; An element with sugar and no authored `:class` is the common case,
    ;; and composing it with nothing produced a copy of the sugar vector.
    (conv/class-string (if (zero? (count parts)) sugar (into (vec sugar) parts)))))

(defn- fn-carried?
  "Is `x` a value whose SPELLING is testable and whose BEHAVIOUR is not — a
  bare function, or one of the declared roster callbacks (`v/event`,
  `v/handler`, `v/raw-fn`)?

  A roster callback carries a function rather than being one, so `fn?`
  alone answers false for it. Asking both questions in one place is what
  keeps the whole-handler position and the key-condition BRANCH position
  answering the same thing about the same value."
  [x]
  (or (fn? x) (events/callback? x)))

(defn- key-condition-map?
  "Is this map at an event position the exact-key CONDITION form rather than
  the listener OPTIONS map?

  Decided by the PRESENCE of a string key, which is exactly how
  [[re-frame.freehand.events/event-plan]] decides it at render and how the
  compiled analyzer decides it at build: an options map's keys are the
  closed keyword roster, so one string key already means the map is not
  one. A map MIXING the two is refused at both of those places as the
  authoring error it is; here it takes the branch reading, because this
  function records what the element declared and does not re-police a
  grammar two front ends already hold."
  [m]
  (boolean (some string? (keys m))))

(defn- key-branch
  "One BRANCH of a key-condition map, as the tree records it.

  A branch is a SITE, not a value inside one. The closed exact-key form
  (Spec 004 §Key-condition event maps) NAMES each branch and admits there
  what the whole handler position admits, minus the roster forms that do
  not dispatch: an event vector, an options map carrying `:event`, a
  `v/event`, or nil. So the site rule reaches one level down with it — a
  branch that IS a callback records the mode-neutral marker, exactly as a
  whole handler carrying one does, while a non-data value nested INSIDE a
  branch is still refused, at the branch that recorded it.

  Walking a branch as ordinary data instead refused the whole element:
  `{\"Enter\" (v/event [e] …)}` is a spelling the compiled tier deliberately
  lowers to the dispatching roster callback, so the structural host answered
  `:rf.error/ui-tree-malformed` for it in BOTH modes — no SSR and no
  headless render for a form the substrate itself writes."
  [tag k key-str branch]
  (if (fn-carried? branch)
    {:rf.ui/opaque :fn}
    (do (when-let [[path bad] (non-data branch)]
          (let [path (into [key-str] path)]
            (malformed!
              're-frame.freehand/render
              (str "The " k " handler on " tag " carries a " (offender-name bad)
                   (at-path path)
                   " inside a key-condition branch. A branch names ONE intent and is "
                   "recorded verbatim, so it is data through and through — a test "
                   "compares it as data and the event system dispatches it as data. A "
                   "function records as the opaque marker when it IS the branch; it "
                   "cannot ride inside one.")
              {:attr k :path path :value (shape bad)})))
        branch)))

(defn classify-event
  "One `:events` entry value, classified BY THE VALUE PRESENT AT RENDER
  (Spec 004B §Element fields): a vector is a literal event intent, a map is
  an options map or the exact-key condition form, a FN-CARRIED SITE is one
  whose spelling is testable and whose behaviour is not, and nil drops the
  entry.

  A fn-carried site is a bare function OR one of the declared roster
  callbacks — `v/event`, `v/handler`, `v/raw-fn` — exactly as 004B
  §Element fields enumerates them. A roster callback is a record rather
  than an `IFn`, so `fn?` alone answers false for it and the site would
  fall to the malformed arm: `[:div {:on-scroll (v/event [e] …)}]` would
  render in React and be REFUSED by the structural tree, which is a
  cross-host divergence over one declaration. It records under the
  mode-neutral `:fn` member, which is the member 004B says the
  interpreted walk produces and the one the compiled emitters already
  emit at a DOM site — so promotion moves nothing.

  An intent and an options map are recorded VERBATIM, so they must already
  be data — a function riding as an event argument would record an intent
  that cannot be compared, printed, or dispatched as the site's own
  (§Data). A key-condition map is recorded branch by branch, because the
  form names each branch as a site of its own ([[key-branch]])."
  [tag k v]
  (cond
    (nil? v)    nil
    (vector? v) (do (when-let [[path bad] (non-data v)]
                      (malformed!
                        're-frame.freehand/render
                        (str "The " k " handler on " tag " carries a " (offender-name bad)
                             (at-path path)
                             " inside its event intent. An event vector is recorded "
                             "verbatim and is data through and through — a test compares "
                             "it as data and the event system dispatches it as data, so "
                             "an argument inside it has to print and read back EQUAL. A "
                             "function records as the opaque marker when it IS the handler "
                             "value; it cannot ride inside the intent.")
                        {:attr k :path path :value (shape bad)}))
                    v)
    (map? v)    (if (key-condition-map? v)
                  (reduce-kv (fn [m key-str branch]
                               (assoc m key-str (key-branch tag k key-str branch)))
                             {}
                             v)
                  (do (when-let [[path bad] (non-data v)]
                        (malformed!
                          're-frame.freehand/render
                          (str "The " k " handler on " tag " carries a " (offender-name bad)
                               (at-path path)
                               " inside its options map. An options map is recorded verbatim "
                               "and is data through and through — the structural tree prints "
                               "and reads back EQUAL, so a value inside one has to survive "
                               "that round trip. A function records as the opaque marker "
                               "when it IS the handler value; it cannot ride inside the "
                               "options.")
                          {:attr k :path path :value (shape bad)}))
                      v))
    (fn-carried? v)
    {:rf.ui/opaque :fn}

    :else
    (malformed!
      're-frame.freehand/render
      (str "The " k " handler site on " tag " carries a " (type-name v)
           ". A handler is an event vector, an options map carrying one, or a function.")
      {:attr k :value (shape v)})))

(defn- dyn-attr-entry
  "Fold one author-space attribute entry into the
  `[attrs events class style]` accumulator through the rule table: `on-*`
  routes to `:events`, `:key` is structural and never an attribute,
  `:class` and `:style` route to the SLOTS that compose them, and
  everything else normalizes into semantic space.

  The key is read in its canonical author spelling
  ([[re-frame.freehand.conversion/attr-key]]) first, because an alias of a
  slot-owning key is that key written differently: `:x/class` composes into
  the class string beside the `.class#id` sugar rather than landing next to
  it as an ordinary attribute. This is the RENDER-time half of one rule —
  the compiled analyzer applies the same canonicalization to a literal props
  map at build time, so a promoted declaration answers the same tree
  (rf2-drpa3.93).

  An ordinary key passes through untouched, namespace and all: the
  structural tree carries authored names."
  [tag multi? [attrs events class style] k v]
  (let [k (conv/attr-key k)]
    (cond
      (= :key k)            [attrs events class style]
      (= :class k)          [attrs events v style]
      (= :style k)          [attrs events class v]
      (conv/handler-key? k) [attrs (if-let [c (classify-event tag k v)]
                                     (assoc events k c)
                                     events)
                             class style]
      :else                 [(if-let [e (attr-entry tag multi? k v)]
                               (conj attrs e)
                               attrs)
                             events class style])))

;; ---------------------------------------------------------------------------
;; Props forwarding — `v/spread` and `v/spread-safe`
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Props forwarding. Two runtime attr maps, two policies, one seam.
;; Both constructors live HERE, below the door, for the reason every other
;; builder does: the compiled emitter and the interpreted walk have to reach
;; the same rule, and an author-space attr map assembled at RUNTIME is exactly
;; the value the compiler cannot see and must therefore not own alone.
;;
;; `v/spread` is the VISIBLE-COST forward: whatever the two maps carry lands on
;; the element, later-arg-wins, and the author has said so at the site.
;; `v/spread-safe` is the BOUNDED one a component library forwards a consumer's
;; attrs through: the owned/structural deny law runs in every build, the
;; surviving keys fold UNDER the component's own, and only `:class` composes.

(def ^:private spread-refusal-key
  "The one authored key `v/spread` refuses on its own account. Every other
  refusal is [[re-frame.freehand.conversion/attr-key-refusal]]'s — the same
  sentence the direct attribute path states, asked of the same emitted slot —
  but `:key` reaches that path as an exact match and is DROPPED there, because
  the interpreted walk has already read it as the element's key. A runtime map
  is not a site: a key decides which element React considers the same element
  across renders, and a compiled spread cannot see one to settle it at the
  site. So it is refused rather than silently honoured in one mode and dropped
  in the other."
  :key)

(defn- assert-forwardable-attrs!
  "Refuse every key `where`'s runtime attr map may not carry, before the map
  reaches the element. The refusals are `attr-key-refusal`'s, so a forwarded
  map is judged by exactly the rule a literal one is."
  [where m]
  (when-not (or (nil? m) (map? m))
    (malformed!
      where
      (str "(" (name where) " …) takes author-space attribute MAPS (or nil). A "
           (type-name m) " cannot be checked against the forwarding rules or "
           "folded onto an element.")
      {:value (shape m)}))
  (reduce-kv
    (fn [_ k _]
      (when (= spread-refusal-key k)
        (malformed!
          where
          (str "A forwarded attribute map carries :key. A key is not an attribute — "
               "the reconciler consumes it and it never reaches the DOM — and it is "
               "LITERAL at the element that carries it, so a map assembled at run "
               "time cannot supply one. Write :key on the element.")
          {:attr k}))
      (when-some [refusal (conv/attr-key-refusal k)]
        (malformed!
          where
          (str "A forwarded attribute map carries " k ". " refusal)
          {:attr k}))
      nil)
    nil
    m)
  m)

(defn- canonical-slot-keys
  "`m` with every key in the CANONICAL author spelling
  [[re-frame.freehand.conversion/attr-key]] projects it onto.

  Identity for all but the two SLOT-OWNING keys: `:class`, which composes
  with the `.class` sugar, and `:style`, which carries the CSS grammar.
  Those two own a place in the element rather than a place in the
  attribute map, so `:x/class`, `\"class\"` and `'class` are one key
  spelled four ways. Everything else — `:x/title`, `:on-click`, a
  `data-*` — is left in author space, because the structural tree carries
  authored names and rewriting one would edit the tree for no gain.

  Remembered per key, so this is a map rebuild and not a re-derivation."
  [m]
  (if (seq m)
    (persistent! (reduce-kv (fn [acc k v] (assoc! acc (conv/attr-key k) v))
                            (transient {})
                            m))
    {}))

(defn spread-attrs
  "`(v/spread base overrides)` — the author-space attribute map the element
  receives. `overrides` wins every collision (later-arg-wins), both maps are
  judged key-by-key against the rules a LITERAL attribute map is judged by,
  and the result folds onto the element through the ordinary rule table.

  The one seam both modes reach: an interpreted body calls it through the
  public `v/spread`, a compiled body's emitted lowering calls it directly, so
  the forwarded map cannot mean one thing before promotion and another after.

  A collision is judged on the CANONICAL slot, not on the authored
  spelling, which is why both maps are projected before the merge rather
  than after. `:class` and `:style` own a slot in the element, so
  `{:x/class \"base\"}` and `{:class \"override\"}` are the same key twice —
  but a raw `merge` sees two distinct map keys, keeps both, and leaves
  the winner to whatever the downstream fold happens to visit last. The
  two front ends fold in different orders, so that is exactly a
  spelling-dependent split: the interpreted walk answered the BASE and
  the compiled one the override, for one declaration whose only stated
  rule is later-arg-wins. Projecting first makes the collision visible to
  the merge, which is the one place the rule is written down."
  [base overrides]
  (assert-forwardable-attrs! 're-frame.freehand/spread base)
  (assert-forwardable-attrs! 're-frame.freehand/spread overrides)
  ;; Always a MAP, so `(v/spread nil)` is an element with no attributes in
  ;; both modes rather than an attribute map in one and an absent child in
  ;; the other — the two happen to render the same tree, and agreement by
  ;; coincidence is the thing this whole slice is written against.
  (let [merged (merge (canonical-slot-keys base) (canonical-slot-keys overrides))]
    ;; The id-slot CARDINALITY, over the MERGED map both front ends fold onto
    ;; the element. A spread carries no tag, so its only id ambiguity is two
    ;; distinct spellings surviving the merge — `(v/spread {:id "a"} {:x/id
    ;; "b"})`; two exact `:id` writes are later-arg-wins and merge to one, no
    ;; conflict. This is the ONE seam BOTH modes reach, so the compiled spread
    ;; — which bypasses the literal analyzer's id guard — refuses the pair
    ;; here at runtime exactly as the interpreted walk does (rf2-5r1af).
    (when-some [{:keys [message]} (conv/id-conflict nil nil (keys merged))]
      (malformed! 're-frame.freehand/spread message {:value (shape merged)}))
    merged))

(defn- owned-handler-keys
  "The `on-*` keys of a `v/spread-safe` OWNED props map — the handler families
  the caller may not install either phase of."
  [owned]
  (into #{}
        (filter #(when-some [n (rules/caller-key-name %)]
                   (str/starts-with? n "on-")))
        (keys owned)))

(defn safe-caller-attrs
  "The GUARDED, canonicalized `caller` map of a `(v/spread-safe owned caller)`
  — [[re-frame.freehand.rules/assert-safe-caller!]]'s every-build owned-key
  deny, then the same forwarding refusals `v/spread` applies. `nil` when the
  caller is nil.

  The deny is the whole point of the form and it is NOT dev-gated: a component
  library forwards a consumer's attrs onto an element it owns, and the
  structural/controlled keys plus its own handler families are what it cannot
  let the consumer clobber. Everything else passes."
  [caller owned-handler-key-set]
  (some->> (rules/assert-safe-caller! caller owned-handler-key-set)
           (assert-forwardable-attrs! 're-frame.freehand/spread-safe)))

(def caller-attrs-key
  "The reserved key `v/spread-safe` hands the guarded caller map to the
  interpreted walk under, riding IN the owned attr map the element already
  reads. Visible in the value rather than hidden in metadata a `merge` would
  quietly drop.

  A carried marker rather than a pre-merged map because the fold is
  [[element]]'s: the compiled front end reaches it with the owned props it
  analysed at build time and the caller map it could not, and the interpreted
  front end has to reach the SAME fold or the two modes would be two
  implementations of one policy.

  Reserved-namespaced, but a reserved NAMESPACE is not on its own a reserved
  key: Freehand's namespaced-attribute law makes `:rf.ui/caller` a perfectly
  ordinary authored attribute. What makes the carrier unforgeable is the
  VALUE mark alone ([[carry-caller]]): the transport is a [[CarriedCaller]]
  and an authored value never is one, so [[split-caller]] consumes only the
  mark and leaves an authored `:rf.ui/caller` in the attribute map to be
  emitted — or refused — as the ordinary attribute it is. The NAME is not
  reserved (rf2-drpa3.132): reserving it as well would silently narrow the
  pass-through attribute law and burn the emitted `caller` slot."
  :rf.ui/caller)

;; The transport MARK. An authored `:rf.ui/caller` cannot be one of these,
;; whatever it carries, so the splitter below never has to guess whether the
;; map it found is `v/spread-safe`'s or the author's — which is the guess that
;; folded an authored string as a map (a raw class cast) and an authored map as
;; a caller (forged attributes on an element that never asked for them).
(deftype CarriedCaller [attrs])

(defn carry-caller
  "Mark the guarded caller map `m` as `v/spread-safe`'s TRANSPORT, so
  [[split-caller]] can tell it from an authored attribute of the same name."
  [m]
  (CarriedCaller. m))

(defn split-caller
  "Split an element's authored props map into the attributes it folds and the
  guarded `v/spread-safe` caller map it may be carrying — `[attrs caller]`.

  The one splitter both interpreted front ends call, because the fold on the
  other side of it (caller under owned, `:class` composing owned-first) is one
  policy and reading the carrier is half of applying it.

  A `caller-attrs-key` entry that is NOT [[carry-caller]]'s mark is left in the
  attribute map, where it is treated as the ordinary authored attribute it is
  — emitted as the `caller` prop for a scalar value, refused by the generic
  attribute-value grammar for a map (as any map-valued attribute is), never
  folded as transport. That is the whole forgery answer, and it needs no
  reserved name: the transport is identified only by the mark, so an authored
  value — which is never the mark — can never be read as one (rf2-drpa3.132)."
  [authored]
  (let [carried (get authored caller-attrs-key)]
    (if (instance? CarriedCaller carried)
      [(dissoc authored caller-attrs-key) (.-attrs ^CarriedCaller carried)]
      [authored nil])))

(defn spread-safe-attrs
  "`(v/spread-safe owned caller)` — the owned props map carrying its guarded
  caller under [[caller-attrs-key]], ready for an element's props position.
  The caller's owned-key deny runs HERE, at the call, so an offending key is
  refused whether or not the element ever renders."
  [owned caller]
  (when-not (or (nil? owned) (map? owned))
    (malformed!
      're-frame.freehand/spread-safe
      (str "(v/spread-safe owned caller) — `owned` is the component's OWN "
           "author-space props map (or nil), not a " (type-name owned) ".")
      {:value (shape owned)}))
  (let [caller* (safe-caller-attrs caller (owned-handler-keys owned))]
    (cond-> (or owned {})
      (some? caller*) (assoc caller-attrs-key (carry-caller caller*)))))

(defn- fold-caller
  "Fold a guarded `v/spread-safe` caller map UNDER the element's already-final
  owned `attrs` / `events`. Owned wins every collision — the caller can carry
  no owned or structural key, so what is left to collide is an ordinary
  attribute — with `:class` the ONE exception: the two class values COMPOSE,
  owned classes first, because a caller passing `.mt-4` is adding a class and
  not replacing the component's own."
  [tag multi? attrs es caller]
  (let [[c-attrs c-es c-class c-style]
        (reduce-kv #(dyn-attr-entry tag multi? %1 %2 %3) [{} {} nil nil] caller)
        c-attrs (if-let [c (class-string tag nil c-class)]
                  (assoc c-attrs :class c)
                  c-attrs)
        c-attrs (if-let [s (and (some? c-style) (not-empty (style-map tag c-style)))]
                  (assoc c-attrs :style s)
                  c-attrs)
        merged  (merge c-attrs attrs)
        merged  (if (and (:class c-attrs) (:class attrs))
                  (assoc merged :class (str (:class attrs) " " (:class c-attrs)))
                  merged)]
    [merged (merge c-es es)]))

(defn element
  "Build an element node in canonical form. Called by a compiled view's
  emitted body, with everything the compiler could settle already settled:

    `:tag`        the element keyword, sugar already stripped
    `:sugar`      the `.class` sugar names, in source order
    `:attrs`      compile-time-normalized literal attributes (semantic space)
    `:dyn`        author-space attribute values only known at run time,
                  normalized here through the rule table the literals took
    `:class`      the authored `:class` value, composed AFTER the sugar
    `:style`      the authored `:style` value
    `:events`     handler values, classified here by the value present
    `:key?`/`:key-val`  key presence and value
    `:caller`     an already-guarded `v/spread-safe` caller attr map,
                  folded UNDER everything above (owned wins; `:class`
                  composes owned-first)
    `:children`   a thunk building the children, evaluated under this
                  element's namespace context

  Absent-when-empty throughout: an element with no attributes carries no
  `:attrs` key at all, so one semantic element has exactly one
  representation."
  [{:keys [tag attrs dyn class sugar style events key? key-val children caller]}]
  (let [ctx        (conv/enter-ns *ns-context* tag)
        ;; The COLLECTION-shaped `value` verdict is a property of the whole
        ;; element, exactly as the controlled-input door's element half is,
        ;; so it is settled once — over both attribute sources, because a
        ;; compiled element's `multiple` may be a literal the compiler
        ;; folded or a value only this render knows. A `v/spread-safe` caller
        ;; may legally carry `:multiple`, and it folds UNDER the owned props
        ;; only after this verdict decides what an owned nil `value` means, so
        ;; the caller is a THIRD source settled here — before the fold, not
        ;; after it (rf2-sf9n5).
        multi?     (or (controlled/multiple-select? tag attrs dyn)
                       (and (some? caller)
                            (controlled/multiple-select? tag caller nil)))
        ;; `:class` and `:style` ride the accumulator because an ALIASED
        ;; spelling of either arrives through `:dyn` — the front end
        ;; discriminates on the exact keyword — and has to reach the one
        ;; place each is composed rather than land beside it.
        [attrs es class style]
        (reduce-kv #(dyn-attr-entry tag multi? %1 %2 %3) [(or attrs {}) {} class style] dyn)
        attrs      (if-let [c (class-string tag sugar class)]
                     (assoc attrs :class c)
                     attrs)
        attrs      (if-let [s (and (some? style) (not-empty (style-map tag style)))]
                     (assoc attrs :style s)
                     attrs)
        es         (reduce-kv (fn [m k v]
                                (if-let [c (classify-event tag k v)]
                                  (assoc m k c)
                                  m))
                              es events)
        ;; `v/spread-safe`: the guarded caller attrs fold UNDER everything the
        ;; component owns, which is why it happens once the owned class and
        ;; style have already resolved. The deny law ran at the call, so what
        ;; arrives here cannot carry a structural or owned key.
        [attrs es] (if (some? caller) (fold-caller tag multi? attrs es caller) [attrs es])
        ;; The DOM top layer's desired-state pair is Freehand vocabulary, not
        ;; attributes: it leaves `:attrs` here and becomes the reserved
        ;; structural fact below. Extracting it at the ONE canonicaliser both
        ;; modes reach is what makes the validity rules and the recorded fact
        ;; the same in an interpreted and a compiled declaration.
        [attrs top] (top-layer/extract tag attrs)
        el-ns      (conv/element-ns ctx)
        kids       (when children
                     ;; Pushing a thread binding costs a frame and a map
                     ;; assoc, and an HTML element under HTML — every
                     ;; element in an ordinary page — would push the value
                     ;; that is already there. So the push happens only
                     ;; when the context actually CHANGES, which is at
                     ;; `<svg>`, `<math>`, `<foreignObject>` and the HTML
                     ;; island inside `<annotation-xml>`. Measured on B1 at
                     ;; 752 bytes per element with children.
                     (let [kid-ctx (conv/child-ns ctx tag attrs)]
                       (if (= kid-ctx *ns-context*)
                         (children)
                         (binding [*ns-context* kid-ctx]
                           (children)))))]
    (when (and (contains? conv/children-rejected-tags tag) (pos? (count kids)))
      (malformed!
        're-frame.freehand/render
        (str "The element " tag " cannot have children — it is a void element, and React "
             "throws rather than render one. Put the content in an attribute, or use an "
             "element that takes children.")
        {:tag tag :children-count (count kids)}))
    ;; `pos? count` rather than `seq`: asking a collection for a seq
    ;; ALLOCATES one, and these five questions are asked of every node in
    ;; the tree only to be thrown away. Measured on B1 at 40 bytes each.
    (cond-> {:tag tag}
      el-ns                (assoc :ns el-ns)
      (pos? (count attrs)) (assoc :attrs attrs)
      (pos? (count es))    (assoc :events es)
      key?                 (assoc :key key-val)
      ;; The desired state as a FACT, never as a claim: a structural host has
      ;; no top layer to promote anything into, so the tree says what was
      ;; asked for and stops there.
      (some? top)          (assoc (top-layer/fact-key) top)
      (pos? (count kids))  (assoc :children kids))))

;; ---------------------------------------------------------------------------
;; Fragment nodes
;; ---------------------------------------------------------------------------

(defn fragment
  "Build a fragment node from an already-canonical children vector.
  `:children` is RETAINED when empty — it is the fragment's required
  discriminator, so `{}` would be a malformed node rather than an empty
  fragment (Spec 004B §Canonical uniqueness)."
  [key? key-val kids]
  (cond-> {:children (vec kids)}
    key? (assoc :key key-val)))

;; ---------------------------------------------------------------------------
;; View-boundary nodes
;; ---------------------------------------------------------------------------

(defn- recorded-prop
  "A prop value as the boundary records it. A prop that IS a function
  becomes the opaque marker — its existence and spelling stay testable,
  its behaviour does not (Spec 004B §The opaque marker). A prop that
  CONTAINS one is refused: the marker occupies a site, and below a prop key
  the tree has no site vocabulary to name (§Data).

  A DECLARED roster callback records under the marker member that names
  its authoring form — `{:rf.ui/opaque :v/render-fn}` for the render slot a
  library seam invokes through `v/slot`, and the matching member for
  `v/event` / `v/handler` / `v/raw-fn`. The form is worth naming where the
  bare fn is not: a slot-carrying prop is a CONTRACT between the caller and
  the seam, so a structural test asserting the caller supplied a render-fn
  is asserting something the mode-neutral `:fn` cannot say. Both modes reach
  this arm with the same value — the compiled emitter lowers a render-fn to
  the same roster callback the interpreted macro expands to — so the record
  is the same in either."
  [view-id k x]
  (cond
    (events/callback? x)
    {:rf.ui/opaque (keyword "v" (name (events/callback-role x)))}

    (fn? x)
    {:rf.ui/opaque :fn}

    :else
    (do (when-let [[path bad] (non-data x)]
          (malformed!
            're-frame.freehand/render
            (str "The " k " prop on " view-id " carries a " (offender-name bad)
                 (at-path path)
                 ". A recorded prop is data through and through — the structural "
                 "tree prints and reads back EQUAL on both hosts, so a value inside "
                 "one has to survive that round trip. A callback records as the "
                 "opaque marker when it IS the prop; pass it as its own prop rather "
                 "than nesting it in data.")
            {:prop k :path path :value (shape bad)}))
        x)))

(defn- plain-fragment?
  "Is `x` a fragment node carrying nothing but its children?

  A `:rf.ui/presence` node is a fragment-shaped map (children, no tag), but its
  marker is a LOAD-BEARING child node that must survive as its own node — a
  presence-rooted view is a boundary wrapping a presence node, not a boundary
  that adopted the presence children. So a marker-bearing fragment is never
  plain, and is never adopted. `:rf.ui/boundary` — the marker a `v/client-only`
  fallback wears — is the same kind of fact for the same reason: a view whose
  whole body IS a client-only site would otherwise adopt the fallback's
  children and lose the one thing that says they are a fallback."
  [x]
  (and (map? x)
       (contains? x :children)
       (not (contains? x :tag))
       (not (contains? x :view-id))
       (not (contains? x :html))
       (not (contains? x :key))
       (not (contains? x :rf.ui/presence))
       (not (contains? x :rf.ui/boundary))))

(defn boundary
  "Assemble one internal-view expansion into a boundary node. The boundary
  is a REAL node, nesting recursively, so a view stays addressable in the
  tree whatever it renders — including a view that renders nothing.

  `props` are the props the body received; `:children` is dropped from the
  record because children are structural and already visible as the
  expansion the body built from them. `rendered` is the view's output as
  the mode that ran it produced it: markup already walked, or a compiled
  body's node.

  A fragment-rooted view is ADOPTED — its children become the boundary's
  children rather than sitting inside a redundant fragment — so a
  fragment-rooted view is a boundary with several children and a
  nil-rooted view is a boundary with none. Both stay matchable, which is
  what makes a `:view-id` predicate a total selector (Spec 004B
  §Coverage Q12).

  `key?` and `key-val` are key PRESENCE and key VALUE, threaded separately
  exactly as [[element]] and [[fragment]] thread them, because `:key` is
  present on the node iff the call was explicitly keyed (Spec 004B). An
  explicit `nil` key is legal authored presence — React string-coerces a
  supplied key, so it is the identity `\"null\"` — and asking `(some? key)`
  instead would answer the same for an explicit nil and an absent key,
  flattening a fact the caller established into a value that cannot carry
  it. `v/normalize-call` reports the presence as `:keyed?` for this reason."
  [view-id props key? key-val kids]
  (let [recorded (reduce-kv (fn [m k x] (assoc m k (recorded-prop view-id k x)))
                            {} (dissoc props :children))
        kids     (if (and (= 1 (count kids)) (plain-fragment? (first kids)))
                   (vec (:children (first kids)))
                   (vec kids))]
    (cond-> {:view-id view-id}
      (pos? (count recorded)) (assoc :props recorded)
      key?                    (assoc :key key-val)
      (pos? (count kids))     (assoc :children kids))))

;; ---------------------------------------------------------------------------
;; The mount seam
;; ---------------------------------------------------------------------------
;;
;; A compiled body reaches a child boundary through exactly one call, and that
;; call may not know — and deliberately does not ask — which mode the child was
;; declared in. `[row {:key i}]` is one spelling with one meaning; promoting or
;; demoting `row` is that declaration's business and no caller's.
;;
;; The seam is a protocol so this namespace can stay BELOW the descriptor: the
;; structural renderer extends it, which is also the moment structural rendering
;; becomes possible at all.

(defprotocol IMount
  "Mount a declared view as a structural boundary node."
  (-mount [view args]
    "`args` is the rest of the call vector — the props map followed by any
    trailing children (already-built child values in compiled markup)."))

(defn mount
  "Mount `view` — a declared view descriptor — with call `args`, answering
  its boundary node. The ONE call a compiled body emits for an internal
  boundary, in either direction across the mode boundary."
  [view args]
  (-mount view args))
