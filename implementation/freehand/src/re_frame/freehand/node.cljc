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
  "Append text, coalescing it into the preceding run when there is one."
  [acc s]
  (if (string? (peek acc))
    (conj (pop acc) (str (peek acc) s))
    (conj acc s)))

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

(defn- finish
  "Drop the empty strings left by coalescing and answer the canonical
  children vector."
  [acc]
  (into [] (remove #(and (string? %) (= "" %))) acc))

(defn children
  "The canonical children vector for already-evaluated child VALUES — the
  compiled front end's entry (no walker, so a vector is rejected). Empty
  when the values carry no content."
  [& xs]
  (finish (reduce #(collect nil 're-frame.freehand/render %1 %2) [] xs)))

(defn walked-children
  "The canonical children vector for markup FORMS, walked by `walk` — the
  interpreted front end's entry."
  [walk where forms]
  (finish (reduce #(collect walk where %1 %2) [] forms)))

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
    (conv/class-string (into (vec sugar) parts))))

(defn classify-event
  "One `:events` entry value, classified BY THE VALUE PRESENT AT RENDER
  (Spec 004B §Element fields): a vector is a literal event intent, a map is
  an options map, a function is a site whose spelling is testable and whose
  behaviour is not, and nil drops the entry."
  [tag k v]
  (cond
    (nil? v)    nil
    (vector? v) v
    (map? v)    v
    (fn? v)     {:rf.ui/opaque :fn}
    :else
    (malformed!
      're-frame.freehand/render
      (str "The " k " handler site on " tag " carries a " (type-name v)
           ". A handler is an event vector, an options map carrying one, or a function.")
      {:attr k :value (shape v)})))

(defn- dyn-attr-entry
  "Fold one author-space attribute entry into the `[attrs events]`
  accumulator through the rule table: `on-*` routes to `:events`, `:key`
  is structural and never an attribute, everything else normalizes into
  semantic space. `:class` and `:style` are NOT folded here — they carry
  richer grammars and have their own slots, so there is exactly one place
  each is composed."
  [tag [attrs events] k v]
  (cond
    (= :key k)            [attrs events]
    (conv/handler-key? k) [attrs (if-let [c (classify-event tag k v)]
                                   (assoc events k c)
                                   events)]
    :else                 [(if-let [e (attr-entry tag k v)]
                             (conj attrs e)
                             attrs)
                           events]))

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
        [attrs es] (reduce-kv #(dyn-attr-entry tag %1 %2 %3) [(or attrs {}) {}] dyn)
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
                     (binding [*ns-context* (conv/child-ns ctx tag attrs)]
                       (children)))]
    (when (and (seq kids) (contains? conv/children-rejected-tags tag))
      (malformed!
        're-frame.freehand/render
        (str "The element " tag " cannot have children — it is a void element, and React "
             "throws rather than render one. Put the content in an attribute, or use an "
             "element that takes children.")
        {:tag tag :children-count (count kids)}))
    (cond-> {:tag tag}
      el-ns       (assoc :ns el-ns)
      (seq attrs) (assoc :attrs attrs)
      (seq es)    (assoc :events es)
      key?        (assoc :key key-val)
      (seq kids)  (assoc :children kids))))

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
  "A prop value as the boundary records it. Non-data values become the
  opaque marker — their existence and spelling stay testable, their
  behaviour does not (Spec 004B §The opaque marker)."
  [x]
  (if (fn? x) {:rf.ui/opaque :fn} x))

(defn- plain-fragment?
  "Is `x` a fragment node carrying nothing but its children?"
  [x]
  (and (map? x)
       (contains? x :children)
       (not (contains? x :tag))
       (not (contains? x :view-id))
       (not (contains? x :html))
       (not (contains? x :key))))

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
  §Coverage Q12)."
  [view-id props key kids]
  (let [recorded (reduce-kv (fn [m k x] (assoc m k (recorded-prop x)))
                            {} (dissoc props :children))
        kids     (if (and (= 1 (count kids)) (plain-fragment? (first kids)))
                   (vec (:children (first kids)))
                   (vec kids))]
    (cond-> {:view-id view-id}
      (seq recorded) (assoc :props recorded)
      (some? key)    (assoc :key key)
      (seq kids)     (assoc :children kids))))

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
