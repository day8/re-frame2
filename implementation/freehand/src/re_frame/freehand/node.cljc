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
  (:require [re-frame.error :as error]
            [re-frame.freehand.conversion :as conv]))

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

(defn- opaque-child!
  "The rejection every child value that is not content lands on. `vector?`
  gets D010's ladder by name: a runtime value is not a template, and the
  compiled tier has no interpreter to hand it to."
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
    (and walk (vector? form)) (conj acc (walk form))
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
  "One author-space `:attrs` entry, or nil when the entry is dropped."
  [tag k v]
  (when (some? v)
    (let [semantic (conv/attr-value v)]
      (when (= :re-frame.freehand.conversion/reject semantic)
        (malformed!
          're-frame.freehand/render
          (str "The " k " attribute on " tag " carries a " (type-name v)
               ", which has no attribute spelling. An attribute value is a string, a "
               "keyword, a symbol, a number or a boolean; :class and :style have their "
               "own richer grammars.")
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

(defn classify-event
  "One `:events` entry value, classified BY THE VALUE PRESENT AT RENDER
  (Spec 004B §Element fields): a vector is a literal event intent, a map is
  an options map, a function is a site whose spelling is testable and whose
  behaviour is not, and nil drops the entry.

  An intent and an options map are recorded VERBATIM, so they must already
  be data — a function riding as an event argument would record an intent
  that cannot be compared, printed, or dispatched as the site's own
  (§Data)."
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
    (map? v)    (do (when-let [[path bad] (non-data v)]
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
                    v)
    (fn? v)     {:rf.ui/opaque :fn}
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
  [tag [attrs events class style] k v]
  (let [k (conv/attr-key k)]
    (cond
      (= :key k)            [attrs events class style]
      (= :class k)          [attrs events v style]
      (= :style k)          [attrs events class v]
      (conv/handler-key? k) [attrs (if-let [c (classify-event tag k v)]
                                     (assoc events k c)
                                     events)
                             class style]
      :else                 [(if-let [e (attr-entry tag k v)]
                               (conj attrs e)
                               attrs)
                             events class style])))

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
    `:children`   a thunk building the children, evaluated under this
                  element's namespace context

  Absent-when-empty throughout: an element with no attributes carries no
  `:attrs` key at all, so one semantic element has exactly one
  representation."
  [{:keys [tag attrs dyn class sugar style events key? key-val children]}]
  (let [ctx        (conv/enter-ns *ns-context* tag)
        ;; `:class` and `:style` ride the accumulator because an ALIASED
        ;; spelling of either arrives through `:dyn` — the front end
        ;; discriminates on the exact keyword — and has to reach the one
        ;; place each is composed rather than land beside it.
        [attrs es class style]
        (reduce-kv #(dyn-attr-entry tag %1 %2 %3) [(or attrs {}) {} class style] dyn)
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
  the tree has no site vocabulary to name (§Data)."
  [view-id k x]
  (if (fn? x)
    {:rf.ui/opaque :fn}
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
  plain, and is never adopted."
  [x]
  (and (map? x)
       (contains? x :children)
       (not (contains? x :tag))
       (not (contains? x :view-id))
       (not (contains? x :html))
       (not (contains? x :key))
       (not (contains? x :rf.ui/presence))))

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
