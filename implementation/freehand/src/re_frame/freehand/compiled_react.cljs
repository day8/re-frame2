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
            [re-frame.freehand.controlled :as controlled]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.reactive :as reactive]
            [re-frame.freehand.top-layer :as top-layer]))

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
;; Props forwarding — the attribute map only the render knows
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Props forwarding. A forwarded map is exactly the value the
;; compiler cannot see, so the compiled tier settles what it can — the tag, the
;; `.class` sugar, the element's own literal props, the site ids — and hands the
;; map itself to the SAME rules the interpreted walk applies entry by entry. The
;; merge, the deny law and the canonicalization already ran in
;; `re-frame.freehand.node`, which both front ends call; what is left here is
;; the write, and the write is `re-frame.freehand.react`'s.

(defn- forward-entry!
  "Write ONE entry of a forwarded author-space attribute map.
  `sugar` composes with a forwarded `:class` — `put-attr!`'s own `:class`
  arm composes with nothing, which is right for a per-attribute write and
  wrong here, where the element's `.class` sugar has to survive the map.

  `multiple?` is the WHOLE forwarded map's multiple-select verdict, settled
  once by the caller — the one fact that decides what an EMPTY `<select>`
  value is."
  [o tag sugar site-id controlled? multiple? k raw]
  (let [k (conv/attr-key k)]
    (cond
      (conv/handler-key? k)
      ;; A forwarded handler is a committed SITE like any other, keyed by the
      ;; spread's own lexical id plus the slot it lands in — so the map's
      ;; `:on-click` owns one stable proxy across re-renders, and two forwarded
      ;; handlers on one element never share a key.
      (let [slot (conv/react-event-name k)]
        (when-some [proxy (reactive/event-site (str site-id "/" slot) raw
                                               {:tag         tag
                                                :controlled? controlled?
                                                :slot        slot})]
          (gobj/set o slot proxy)))

      (= :class k) (fr/put-class! o tag sugar raw)
      :else        (fr/put-attr! o tag k raw multiple?)))
  o)

(defn ^:no-doc spread!
  "Fold `(v/spread base overrides)`'s merged map onto a compiled element's
  props object.

  A `v/spread` IS the element's whole attribute map — the analyzer records
  no literal attrs beside it — so every rule applies to the forwarded
  values and nothing is overwritten: the `.class` sugar composes with a
  forwarded `:class`, a forwarded `:on-*` becomes a committed site, and
  whether the element is CONTROLLED is decided from the keys that actually
  arrived, exactly as the interpreted walk decides it."
  [o tag sugar site-id m]
  (let [attrs       (top-layer/without m)
        controlled? (controlled/controlled-props? (keys attrs))
        ;; The multiple-select verdict is a property of the WHOLE forwarded
        ;; map, settled over it once — the interpreted walk settles it over
        ;; the same merged map before writing any attribute. Without it a
        ;; forwarded `<select multiple>`'s explicitly nil `value` writes the
        ;; scalar empty string React reports as a shape error (rf2-sf9n5).
        multiple?   (controlled/multiple-select? tag attrs nil)]
    (reduce-kv (fn [_ k raw]
                 (forward-entry! o tag sugar site-id controlled? multiple? k raw) nil)
               nil attrs)
    ;; The DOM top layer's desired-state pair is Freehand vocabulary, not an
    ;; attribute, so a forwarded one becomes the commit-time host call here
    ;; rather than a garbage prop — the interpreted walk's own arm, over the
    ;; map it was handed.
    (top-layer/install! o tag m)))

(defn ^:no-doc caller-spread!
  "Fold a GUARDED `(v/spread-safe owned caller)` caller map UNDER the owned
  props already written onto `o` — [[re-frame.freehand.react/put-caller!]],
  the ONE browser fold, with the compiled tier's own site keying.

  Only the handler keying differs, and it differs the way every compiled
  site does: the key is the spread's proven lexical id plus the slot,
  rather than this render's ordinal."
  [o tag site-id controlled? m]
  (fr/put-caller! o tag
                  (fn [slot raw]
                    (reactive/event-site (str site-id "/" slot) raw
                                         {:tag tag :controlled? controlled? :slot slot}))
                  m))

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
  diagnostic, at the same place.

  `seen` is a `js/Set`, which is prototype-free by construction: it holds
  exactly what was put in it. An object used as a map would not be —
  `\"toString\"`, `\"constructor\"`, `\"hasOwnProperty\"` and `\"__proto__\"`
  are inherited from `Object.prototype`, so the FIRST row keyed by one of
  them would read as already present and be refused. The structural
  tier's Clojure set accepts those keys, and a view that renders under
  `node/keyed-run` has to render in a browser too."
  [seen k]
  (let [s (if (number? k) (conv/js-number-str k) (str k))]
    (when (.has ^js seen s)
      (error/throw-error!
        :rf.error/ui-duplicate-key
        're-frame.freehand/render
        (str "Duplicate key " (pr-str k) " in a keyed list. Keys compare after "
             "React's string coercion, so the key 1 collides with the key \"1\"; "
             "a key must be unique per list site.")
        {:recovery :key-each-row-uniquely
         :extra    {:key (error/diag-value-summary k)}}))
    (.add ^js seen s))
  nil)
