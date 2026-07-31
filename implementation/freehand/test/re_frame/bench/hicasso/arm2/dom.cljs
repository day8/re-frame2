(ns re-frame.bench.hicasso.arm2.dom
  "THE OWN EMITTER — hiccup analysis in, real DOM out (rf2-2rtt6.10).

  The shared front half's codec ends its docstring with the honest
  statement of where the two tournament arms part: *analysis — tag parse,
  prop names, class merge, child realization, head classification — is
  arm-neutral; emission is not.* Arm 1 emits React elements. This file is
  Arm 2's emitter, and it emits `Element`, `Text` and `Comment`.

  It reuses the codec's analysis by requiring it ([[codec/cached-parse]],
  [[codec/class-names]], [[codec/boundary-head?]]) so the two arms parse
  `:div#main.wide` with one regex and one cache, and a tournament result
  cannot turn on one arm having a cheaper tag parser.

  ## Names: attributes are not React props

  The codec's kebab→camel rule is React's. The DOM's rule is different
  and simpler, and getting this wrong is silent — `setAttribute
  \"readOnly\"` happens to work (HTML attribute names are
  case-insensitive) while `setAttribute \"read-only\"` silently does
  nothing. One rule, no roster:

  | prop | attribute |
  |---|---|
  | `:data-i`, `:aria-label`, `--custom` | verbatim |
  | `:read-only`, `:tab-index`, `:col-span` | dashes stripped, lowercased |
  | `:readOnly`, `:tabIndex` | lowercased |
  | `:className`, `:htmlFor` | `class`, `for` (the two seeded aliases) |

  Every multi-word HTML attribute is squashed (`readonly`, `tabindex`,
  `colspan`, `maxlength`, `contenteditable`, `novalidate`, `crossorigin`,
  `datetime`), which is why the strip-and-lowercase rule needs no table:
  the exceptions are `aria-*`/`data-*`, and those are exactly the ones
  React exempts too.

  ## Properties, not attributes, for the controlled pair

  `value`, `checked`, `selected`, `indeterminate` and `muted` are set as
  **DOM properties**. `setAttribute(\"value\", …)` writes the *default*
  value and does not move a live input; a renderer that sets the
  attribute has no controlled input at all. This is the first half of the
  controlled-restore obligation and the reason
  [[re-frame.bench.hicasso.arm2.controlled]] can do its work at all.

  ## The event trampoline

  A lowered intent is a fresh closure per render (the front half's
  `intent/lower-prop` builds it). Naively that is one `removeEventListener`
  plus one `addEventListener` per prop per render — on the 100-cell grid,
  200 listener mutations per keystroke.

  Instead each node carries one own-property register, `__hicassoOn`, and
  the *first* handler at a type installs one permanent listener that
  reads the register at dispatch time. Re-renders overwrite a slot. The
  cost of a re-render is one property write; the cost of an event is one
  own-property read. Retention is one small JS object per node that has
  any handler at all, and it dies with the node.

  This is deliberately **not** root delegation. Delegation is the bigger
  win and it is also where a renderer inherits a synthetic event system —
  retargeting, non-bubbling types (`focus`, `blur`), portal semantics, and
  a `stopPropagation` story that differs from the platform's. HD-019's
  door needs the *real* discrete event, so the arm keeps real listeners on
  real nodes and states delegation as an unexplored optimization rather
  than paying for a synthetic layer to get it.

  ## The 1:1 law

  **Every hiccup child occupies exactly one DOM node.** A string or number
  is a `Text`; `nil` and `false` are a `<!--h-->` comment anchor; a vector
  is one element or one boundary root. Seqs and fragments are spliced
  into the parent's child vector *before* diffing, so they contribute
  children rather than nodes of their own.

  The comment anchor is what makes a conditional child free of index
  arithmetic: `[:div (when x [:b])]` keeps one child slot whether or not
  `x` holds, so the differ never has to decide whether child 2 of the old
  tree is child 1 or child 2 of the new one. The cost is one comment node
  per absent child, which is the trade every anchor-based renderer makes
  and it is worth naming rather than discovering in a diff."
  (:require [clojure.string :as str]
            [re-frame.bench.hicasso.arm2.controlled :as controlled]
            [re-frame.bench.hicasso.front.codec :as codec]))

;; ---------------------------------------------------------------------------
;; Own-property hygiene — the same guard the codec's caches use
;; ---------------------------------------------------------------------------

(def ^:private has-own (.-hasOwnProperty (.-prototype js/Object)))

(defn- own-key? [o k] (.call has-own o k))

(def ^:private reserved-cache-keys #{"__proto__" "prototype" "constructor"})

;; ---------------------------------------------------------------------------
;; Attribute names
;; ---------------------------------------------------------------------------

(defn attr-name
  "The DOM attribute name for a hiccup prop key. See the namespace
  docstring's table — one rule and two seeded aliases."
  [k]
  (if (string? k)
    k
    (let [n (name k)]
      (cond
        (str/starts-with? n "--")     n
        (str/starts-with? n "aria-")  n
        (str/starts-with? n "data-")  n
        (= n "className")             "class"
        (= n "htmlFor")               "for"
        :else                         (str/lower-case (str/replace n "-" ""))))))

(def ^:private attr-cache #js {})

(defn cached-attr-name
  "[[attr-name]] behind a codec-work cache (HD-004). One entry per
  distinct prop literal the build ever renders."
  [k]
  (if-not (or (keyword? k) (symbol? k) (string? k))
    (attr-name k)
    (let [n (name k)]
      (if (reserved-cache-keys n)
        (attr-name k)
        (if (own-key? attr-cache n)
          (unchecked-get attr-cache n)
          (let [converted (attr-name k)]
            (unchecked-set attr-cache n converted)
            converted))))))

(def ^:private dom-properties
  "Written as properties, never attributes. The controlled pair plus the
  three other live-state properties whose attribute is only a default."
  #{"value" "checked" "selected" "indeterminate" "muted"})

(defn property-prop?
  "Does this prop address live element state rather than markup?"
  [k]
  (contains? dom-properties (cached-attr-name k)))

;; ---------------------------------------------------------------------------
;; Events
;; ---------------------------------------------------------------------------

(def ^:private event-prop-re #"^on-[a-z]|^on[A-Z]")

(defn event-prop?
  "Is `k` an event position? The same two spellings the front half's
  intent lowering accepts, so a prop it lowered is a prop this emitter
  binds."
  [k]
  (boolean (and (or (keyword? k) (string? k))
                (re-find event-prop-re (name k)))))

(defn event-type
  "`:on-click` → `\"click\"`, `:onKeyDown` → `\"keydown\"`. Dashes
  stripped and lowercased, which is the DOM's own naming for every type
  the census writes."
  [k]
  (-> (name k) (subs 2) (str/replace "-" "") str/lower-case))

(def ^:private on-key "__hicassoOn")

(defn- handler-register
  "The node's handler register, created on first use."
  [node]
  (or (unchecked-get node on-key)
      (let [o #js {}] (unchecked-set node on-key o) o)))

(defn set-handler!
  "Install (or with `nil`, clear) the handler at `type` on `node`. The
  first handler at a type installs one permanent listener that reads the
  register; later renders only write the slot."
  [node type f]
  (let [reg (handler-register node)]
    (when-not (own-key? reg type)
      (.addEventListener node type
                         (fn [e] (when-some [h (unchecked-get reg type)] (h e)))))
    (unchecked-set reg type f)
    nil))

(defn handler-at
  "The handler currently bound at `type`, or nil. For the witnesses."
  [node type]
  (when-some [reg (unchecked-get node on-key)]
    (unchecked-get reg type)))

;; ---------------------------------------------------------------------------
;; Style
;; ---------------------------------------------------------------------------

(defn- css-name
  "`:font-size` → `\"font-size\"`, `:fontSize` → `\"font-size\"`, a custom
  property verbatim. `setProperty` wants the CSS spelling, not the
  camelCase one the `style` object exposes."
  [k]
  (let [n (name k)]
    (if (str/starts-with? n "--")
      n
      (str/lower-case (str/replace n #"([a-z0-9])([A-Z])" "$1-$2")))))

(defn- apply-style!
  "Set the declarations in `new-style` that differ from `old-style`, and
  remove the ones that went away. Numbers are stringified as-is: this
  renderer appends no implicit `px` — an implicit unit is a rule the
  author has to learn and a value the author cannot express."
  [node old-style new-style]
  (let [style (.-style node)]
    (doseq [[k v] new-style]
      (when-not (= v (get old-style k))
        (if (nil? v)
          (.removeProperty style (css-name k))
          (.setProperty style (css-name k) (str v)))))
    (doseq [[k _] old-style]
      (when-not (contains? new-style k)
        (.removeProperty style (css-name k))))
    nil))

;; ---------------------------------------------------------------------------
;; One prop
;; ---------------------------------------------------------------------------

(defn set-prop!
  "Apply one already-lowered prop to `node`. `old` is the value the
  previous render set at this key, used only by `:style` (which patches
  declaration-wise) — every other kind writes unconditionally, because
  the caller has already established that the value changed."
  [node k v old]
  (let [n (cached-attr-name k)]
    (cond
      (event-prop? k)          (set-handler! node (event-type k) (when (fn? v) v))
      (= "style" n)            (apply-style! node (when (map? old) old) (when (map? v) v))
      ;; The controlled pair goes through the one code path that owns the
      ;; caret and the composition fence — never a bare property write.
      (= "value" n)            (controlled/write-value! node v)
      (= "checked" n)          (controlled/write-checked! node v)
      (dom-properties n)       (unchecked-set node n (if (nil? v) "" v))
      (or (nil? v) (false? v)) (.removeAttribute node n)
      (true? v)                (.setAttribute node n "")
      (keyword? v)             (.setAttribute node n (name v))
      :else                    (.setAttribute node n (str v)))
    nil))

(defn clear-prop!
  "Undo a prop that the new render does not carry."
  [node k]
  (let [n (cached-attr-name k)]
    (cond
      (event-prop? k)    (set-handler! node (event-type k) nil)
      (= "style" n)      (apply-style! node nil nil)
      (= "value" n)      (controlled/write-value! node "")
      (= "checked" n)    (controlled/write-checked! node false)
      (dom-properties n) (unchecked-set node n "")
      :else              (.removeAttribute node n))
    nil))

;; ---------------------------------------------------------------------------
;; Props maps
;; ---------------------------------------------------------------------------

(defn merge-shorthand
  "Fold a parsed tag's `#id`/`.class` shorthand into the props map, drop
  `:key` (identity, not an attribute), and normalize `:className` onto
  `:class`. The codec's private counterpart, restated here because the
  emitter needs the *merged map itself* — the codec's version is fused
  into its React props walk."
  [props parsed]
  (let [id        (.-id parsed)
        shorthand (.-className parsed)
        declared  (or (:class props) (:className props))
        merged    (codec/class-names shorthand declared)]
    (cond-> (dissoc props :key :className :class)
      (and id (not (contains? props :id))) (assoc :id id)
      merged                               (assoc :class merged))))

(defn apply-props!
  "Write every prop in `props` onto a freshly created `node`."
  [node props]
  (reduce-kv (fn [_ k v] (set-prop! node k v nil) nil) nil props)
  nil)

(defn diff-props!
  "Patch `node` from `old-props` to `new-props`. Only the keys whose
  values are not `=` are written, and only the keys that went away are
  cleared — the ordinary re-render of an unchanged element touches the
  DOM zero times.

  Event positions are the deliberate exception: a lowered intent is a
  fresh closure every render and is never `=` to the last one, so its
  slot is rewritten. That write is one property assignment on the node's
  handler register (see the namespace docstring), not a listener
  mutation."
  [node old-props new-props]
  (when-not (identical? old-props new-props)
    (reduce-kv (fn [_ k v]
                 (let [o (get old-props k ::absent)]
                   (when-not (= o v) (set-prop! node k v (when (not= o ::absent) o))))
                 nil)
               nil
               new-props)
    (reduce-kv (fn [_ k _]
                 (when-not (contains? new-props k) (clear-prop! node k))
                 nil)
               nil
               old-props))
  nil)

;; ---------------------------------------------------------------------------
;; Node construction
;; ---------------------------------------------------------------------------

(defn create-anchor
  "The comment node standing in for a `nil` or `false` child — the 1:1
  law's placeholder."
  []
  (js/document.createComment "h"))

(defn create-text [x] (js/document.createTextNode (str x)))

(defn anchor?  [node] (identical? 8 (.-nodeType node)))
(defn text?    [node] (identical? 3 (.-nodeType node)))
(defn element? [node] (identical? 1 (.-nodeType node)))

(defn create-element
  "Create the element for a parsed tag and write its merged props."
  [parsed props]
  (let [node (js/document.createElement (.-tag parsed))]
    (apply-props! node props)
    node))

;; ---------------------------------------------------------------------------
;; Placement
;; ---------------------------------------------------------------------------

(defn insert!
  "Put `node` into `parent` before `anchor` (or at the end when `anchor`
  is nil). The one placement primitive the differ uses — `appendChild` is
  `insertBefore` with a nil anchor, and having one call site is what
  keeps the reorder path honest."
  [parent node anchor]
  (.insertBefore parent node (or anchor nil))
  nil)

(defn remove! [parent node] (.removeChild parent node) nil)

(defn replace! [parent old-node new-node] (.replaceChild parent new-node old-node) nil)
