(ns re-frame.freehand.compiled-react
  "The RUNTIME the compiled tier's browser lowering calls — the small,
  closed set of targets
  [[re-frame.freehand.compiler.emit-react]] emits, and nothing else.

  Every function here is a DOORWAY, not a system. A compiled element's
  class composition, style canonicalisation, attribute grammar,
  controlled-input door, boundary normalization and child classification
  are all the interpreted emitter's, reached from here with the shape
  already resolved at build time. There is no second conversion table, no
  second reactive path and no second definition of what a child is — so
  a compiled view and its interpreted twin cannot disagree about the DOM
  they produce, and the only difference between them is when the
  structure was worked out.

  It sits ABOVE [[re-frame.freehand.react]] and that namespace takes
  nothing back: the interpreted emitter does not know the compiled tier
  exists. The compiled tier's COMPONENT selection is the one thing that
  lives over there, beside the interpreted one, because choosing between
  the atomic shell and the elided plain component is the same decision
  `component-for` already makes for every other boundary.

  INTERNAL. Nothing here is application API.

  Normative owner:
  [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../spec/004D-Freehand-Compiled-Grammar.md);
  the conversion rules it reaches are
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md)."
  (:require ["react" :as react]
            [goog.object :as gobj]
            [re-frame.error :as error]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.react :as fr]))

;; ---------------------------------------------------------------------------
;; Elements
;; ---------------------------------------------------------------------------

(defn ^:no-doc el
  "One compiled host element. `kids` is the argument array the emitter
  built, or `nil` for a childless element.

  `createElement` with VARARG children, deliberately — it is what the
  interpreted walk produces for the same markup, and React treats a
  vararg run and a single array child differently (identity, and the
  dev-time key expectation). A compiled parent must not hand React a
  different child shape from the interpreted parent it was promoted
  from."
  [tag props kids]
  (if (nil? kids)
    (react/createElement tag props)
    (.apply react/createElement nil (.concat #js [tag props] kids))))

(defn ^:no-doc fragment
  "A compiled fragment. Key PRESENCE decides whether React is given one,
  because an explicit `nil` key is the ordinary identity `\"null\"` and a
  different authored fact from no key at all."
  [keyed? k kids]
  (let [props (when keyed? #js {:key k})]
    (if (nil? kids)
      (react/createElement react/Fragment props)
      (.apply react/createElement nil (.concat #js [react/Fragment props] kids)))))

;; ---------------------------------------------------------------------------
;; Props the compiler could not fold
;; ---------------------------------------------------------------------------

(defn ^:no-doc class!
  "Compose the element's `className` from the tag's sugar names and the
  AUTHORED `:class` value, and write it — the interpreted walk's `:class`
  arm, on the value the compiled body just produced."
  [o tag sugar raw]
  (fr/put-class! o tag sugar raw)
  o)

(defn ^:no-doc style!
  "Canonicalise a `:style` the compiler could not fold and write it. A nil
  value is an ABSENT attribute — the same nil-is-absent law the interpreted
  walk applies to every attribute, and `:style` has no controlled-slot
  exception — so a compiled `:style (when …)` that folds to nil writes no
  style, exactly as its interpreted twin does."
  [o tag raw]
  (when (some? raw)
    (fr/put-style! o tag raw))
  o)

(defn ^:no-doc attr!
  "Write one attribute the compiler did not fold, under the rules the
  interpreted walk applies to the same value — including the two it could
  not settle at build time: a nil value is an ABSENT attribute, except on
  a controlled input's own slots where absence is React's signal for
  UNCONTROLLED; and a `<select multiple>`'s EMPTY value is the empty
  ARRAY, not the empty string.

  `multiple?` is the element's multiple-select verdict, resolved at build
  time from the declaration's own `multiple` and passed as the constant it
  is."
  ([o tag k v] (fr/put-attr! o tag k v false) o)
  ([o tag k v multiple?] (fr/put-attr! o tag k v multiple?) o))

(defn ^:no-doc handler!
  "Attach a committed handler site's stable proxy, when the site carries
  one. An empty position writes nothing, and `v/render-fn` / `v/raw-fn`
  hand back the authored function — the site constructor's own answers,
  not this function's."
  [o slot proxy]
  (when (some? proxy) (gobj/set o slot proxy))
  o)

;; ---------------------------------------------------------------------------
;; Children
;; ---------------------------------------------------------------------------

(defn ^:no-doc child
  "Classify ONE runtime value in a child position and answer what React
  should render for it: `nil` for nothing, the child itself for one, and
  a JS array for a run.

  The classifier is the interpreted walk's — the ONE definition of what
  a child is (markup, text, a seq of children, nothing) — applied under
  the ambient candidate this render already established, so declarative
  intent inside a forwarded subtree is recorded on the same commit an
  interpreted body would record it on."
  [v]
  (let [ks (fr/child-elements v)]
    (case (count ks)
      0 nil
      1 (nth ks 0)
      (into-array ks))))

(defn ^:no-doc push!
  "Append a dynamic child to an element's argument array, SPLICING a run.

  A run reaches React as several arguments rather than as one nested
  array, which is what keeps a compiled parent's child sequence identical
  to the interpreted parent's — same identity, same dev-time key
  expectation. A JS array is unambiguously a run here: the classifier
  above refuses any other value that could be one."
  [a v]
  (cond
    (nil? v)   nil
    (array? v) (.apply (.-push a) a v)
    :else      (.push a v))
  a)

(defn ^:no-doc root
  "The value a compiled body hands back to React. A run becomes a
  fragment, exactly as the interpreted emitter's does, so a component
  always returns one renderable value."
  [v]
  (if (array? v)
    (.apply react/createElement nil (.concat #js [react/Fragment nil] v))
    v))

;; ---------------------------------------------------------------------------
;; Boundaries and keyed runs
;; ---------------------------------------------------------------------------

(defn ^:no-doc mount
  "Cross an internal boundary. The head is a Freehand DESCRIPTOR, so this
  resolves it to the ONE stable React component that view mounts through
  and normalizes the call through the shared boundary rules — the same
  `:key` stripping, reserved `:children` slot, children policy and props
  schema an interpreted crossing gets."
  [view props & children]
  (fr/mount-view view (cons props children)))

(defn ^:no-doc check-key!
  "Prove a compiled list site's keys, at the site. Keys compare after
  React's own string coercion, so `1` and `\"1\"` are one key — the
  structural tier's `node/keyed-run` rule, raised with the same
  diagnostic, at the same place."
  [seen k]
  (let [s (if (number? k) (conv/js-number-str k) (str k))]
    (when (gobj/containsKey seen s)
      (error/throw-error!
        :rf.error/ui-duplicate-key
        're-frame.freehand/render
        (str "Duplicate key " (pr-str k) " in a keyed list. Keys compare after "
             "React's string coercion, so the key 1 collides with the key \"1\"; "
             "a key must be unique per list site.")
        {:recovery :key-each-row-uniquely
         :extra    {:key (error/diag-value-summary k)}}))
    (gobj/set seen s true))
  nil)
