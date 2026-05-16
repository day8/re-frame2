(ns reagent2.impl.template
  "Hiccup → React-element translation for the day8/reagent-slim artefact
  (rf2-6hyy Stage 4-D).

  Per IMPL-SPEC §7. The pipeline:

      hiccup form
          │
          ▼
      as-element
          │
          ├── (string? f)              → text node
          ├── (number? f)              → text node
          ├── (vector? f)              → vec-to-elem
          ├── (seq? f)                 → array of elements (with key warnings)
          └── (nil? f)                 → React null

  vec-to-elem dispatches on the head:

    | head      | meaning                   |
    | :>        | React component interop   |
    | :<>       | React Fragment            |
    | :r>       | raw React.createElement   |
    | :f>       | function-component        |
    | DOM tag   | parse-tag + DOM element   |
    | user fn   | reagent component         |
    | class     | reagent class component   |

  Public surface (consumed within the artefact + by the adapter Var):

    as-element        — top-level entry; hiccup → React element
    vec-to-elem       — vector dispatch (the head-test)
    parse-tag         — :div.cls#id → {:tag :id :class}
    convert-prop-value — narrowed per DECISION-2 (§7.2)
    cached-prop-name  — kebab→camel cache (kept; same as stock)
    expand-seq        — sequence-as-children + key-warnings

  D2 narrowed `convert-prop-value` (per IMPL-SPEC §7.2): keyword values
  pass through unchanged for non-HTML-attribute prop names. The audit-
  driven set `html-attr-names` plus `data-*`/`aria-*` prefix-matched
  names get the stringification path. Other prop names (e.g. user-
  defined React-component props, `:value` on a React-context Provider)
  see the keyword preserved. This deletes the rf2-d4sf coercion seam
  that the bridge needed to undo over-stringification.

  React 19 strictness (§7.6): refs continue as JS-shape `ref`; no
  `defaultProps` emission for function components; no
  `React.Children.only` invocation."
  (:require [clojure.string :as str]
            [reagent2.impl.component :as component]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Tag parsing — :div.cls#id shorthand
;;
;; Per IMPL-SPEC §7.3: same regex stock Reagent has used for years. We
;; lift the pattern byte-for-byte and cache parses on first sight.
;; ---------------------------------------------------------------------------

(def ^:private re-tag
  "Regex for parsing CSS-style id and class from a Reagent tag keyword.
   `[#.]?[^#.]+` matched repeatedly captures `tag`, optional `#id`,
   and any number of `.class` segments."
  #"([^\s\.#]+)(?:#([^\s\.#]+))?(?:\.([^\s#]+))?")

;; Note on field name `className` (rather than `class`): in JS-target
;; CLJS, `class` is a reserved word and the compiler munges it to
;; `class$` on the deftype's instance fields, while `(.-class obj)`
;; reads from the unmangled `.class` slot — so the read silently
;; returns undefined. We use `className` to match React's prop name
;; and to dodge the munging entirely.
(deftype ^:private HiccupTag [tag id className])

(defn parse-tag
  "Parse a hiccup tag keyword into its `:tag` / `:id` / `:class` parts.

  Returns a `HiccupTag` record. The `class` field carries whitespace-
  joined classes when the tag has `.foo.bar` shorthand; nil when there
  are no class shorthand parts. The `id` field is non-nil only when the
  tag has a `#id` shorthand part. `tag` is the bare element name string.

  Examples:

    (parse-tag :div)         → HiccupTag{tag \"div\" id nil class nil}
    (parse-tag :div.cls)     → HiccupTag{tag \"div\" id nil class \"cls\"}
    (parse-tag :div#id)      → HiccupTag{tag \"div\" id \"id\" class nil}
    (parse-tag :div.a.b#id)  → HiccupTag{tag \"div\" id \"id\" class \"a b\"}"
  [hiccup-tag]
  (let [[_ tag id class-shorthand]
        (re-matches re-tag (name hiccup-tag))
        class (when class-shorthand
                (str/replace class-shorthand #"\." " "))]
    (->HiccupTag tag id class)))

;; ---------------------------------------------------------------------------
;; Cache + props-object safety (rf2-dwds9 MEDIUM)
;;
;; Hiccup keys reaching `aset` are user-controlled. A literal
;; `{:__proto__ x}` or `{:constructor x}` in a prop map would, on a
;; plain `#js {}` target, write to the prototype chain — mutating
;; `Object.prototype` (or our shared caches' prototype) and leaking
;; inherited slots across every subsequent render.
;;
;; Strategy: BLOCK the reserved key trio (`__proto__`, `prototype`,
;; `constructor`) before any `aset`. Two enforcement points:
;;
;;   - `cached-prop-name` — never caches a reserved name; returns it
;;     verbatim. (Belt: keeps the shared cache map clean.)
;;   - `kv-conv` — drops reserved camelCased names before writing to
;;     the per-render JS props object. (Braces: the actual prototype-
;;     pollution chokepoint, because the props object is what flows
;;     into `React.createElement`.)
;;
;; Why NOT null-prototype objects? React's renderer calls
;; `styles.hasOwnProperty(...)` on nested objects like `:style` when
;; diffing inline styles (`react-dom` ReactDOMHostConfig). A
;; null-proto object throws `TypeError: styles.hasOwnProperty is not
;; a function`. So props objects stay on the default prototype, and
;; the reserved-key filter is the sole defence — sufficient on its
;; own because every prototype-pollution path runs through the filtered
;; `aset` chokepoints.
;; ---------------------------------------------------------------------------

(def ^:private reserved-prop-keys
  "JS property keys that must NEVER be `aset` from user-controlled
  input. Writing to these mutates the prototype of the object instead
  of creating an own property, which leaks inherited slots across
  every subsequent prop-map conversion. Per rf2-dwds9 MEDIUM."
  #{"__proto__" "prototype" "constructor"})

(defn- ^boolean reserved-prop-key? [n]
  (contains? reserved-prop-keys n))

(def ^:private tag-name-cache #js {})

(defn- cached-parse [k]
  (let [n (name k)]
    (if (and (not (reserved-prop-key? n))
             (.hasOwnProperty tag-name-cache n))
      (aget tag-name-cache n)
      (let [v (parse-tag k)]
        (when-not (reserved-prop-key? n)
          (aset tag-name-cache n v))
        v))))

;; ---------------------------------------------------------------------------
;; Prop-name cache (kebab → camel)
;;
;; React expects camelCased prop names (`className`, `htmlFor`,
;; `tabIndex`, ...). Hiccup convention is kebab-cased keywords. We
;; cache the conversion so each unique keyword pays the dash-to-camel
;; cost once.
;;
;; `data-*` and `aria-*` are NOT camelCased — React passes them through
;; as DOM attributes verbatim. The dont-camel-case starter prefix list
;; per stock Reagent.
;; ---------------------------------------------------------------------------

(def ^:private dont-camel-case #{"aria" "data"})

(defn- capitalize [s]
  (if (< (count s) 2)
    (str/upper-case s)
    (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn- dash-to-prop-name [dashed]
  (if (string? dashed)
    dashed
    (let [name-str (name dashed)
          [start & parts] (str/split name-str #"-")]
      (if (dont-camel-case start)
        name-str
        (apply str start (map capitalize parts))))))

(def ^:private prop-name-cache
  (doto #js {}
    (aset "class" "className")
    (aset "for" "htmlFor")
    (aset "charset" "charSet")))

(defn cached-prop-name
  "Look up the React prop-name string for hiccup-keyword `k`.
  Caches kebab→camel conversion across the build.

  React conventions:
    :class    → \"className\"
    :for      → \"htmlFor\"
    :charset  → \"charSet\"
    :tab-index → \"tabIndex\"
    :data-foo → \"data-foo\"  (data-* not camelCased)
    :aria-label → \"aria-label\" (aria-* not camelCased)

  Per rf2-dwds9 MEDIUM: reserved JS keys (`__proto__`, `prototype`,
  `constructor`) are never cached and are returned verbatim. The
  downstream `convert-props` writes drop these too (see `kv-conv`),
  so a malicious key cannot reach the React props object."
  [k]
  (if (or (keyword? k) (symbol? k))
    (let [n (name k)]
      (cond
        ;; Reserved keys: skip the cache entirely, return the raw name.
        ;; (Downstream kv-conv drops the aset; the name is harmless here.)
        (reserved-prop-key? n) n

        :else
        (if-some [cached (when (.hasOwnProperty prop-name-cache n)
                           (aget prop-name-cache n))]
          cached
          (let [v (dash-to-prop-name k)]
            (aset prop-name-cache n v)
            v))))
    k))

;; ---------------------------------------------------------------------------
;; Narrowed convert-prop-value (per DECISION-2 + IMPL-SPEC §7.2)
;;
;; Keyword values stringify only for HTML attribute names. For non-
;; HTML prop names (e.g. `:value` on a React-context Provider, custom
;; component props), the keyword passes through unchanged. This deletes
;; the bridge's coerce-context-value seam (rf2-d4sf) — there's no
;; over-stringification to undo because we never broadly stringified.
;; ---------------------------------------------------------------------------

(def html-attr-names
  "HTML-attribute prop names whose keyword values stringify. Plus
  the prefix-matched `data-*` / `aria-*` family. Per IMPL-SPEC §7.2."
  #{:class :id :role})

(defn- ^boolean html-attr-name? [k]
  (or (contains? html-attr-names k)
      (let [n (name k)]
        (or (str/starts-with? n "data-")
            (str/starts-with? n "aria-")))))

;; Dev-only one-shot warning cache. Keyed on `[k name-of-v]`. The audit
;; (rf2-cgcv + rf2-kfpf) showed a small number of legitimate non-HTML
;; keyword props in production code; the warning is informational, not
;; a deprecation. Per IMPL-SPEC §7.2.
(defonce ^:private ^{:doc "[k v-name] → true once warned."}
  warned-keyword-prop (atom #{}))

(defn- warn-once-keyword-prop! [k v]
  (let [key [(name k) (name v)]]
    (when-not (contains? @warned-keyword-prop key)
      (swap! warned-keyword-prop conj key)
      (when (exists? js/console)
        (.warn js/console
               (str "[reagent-slim] keyword value " (pr-str v)
                    " on non-HTML prop " (pr-str k)
                    " passes through unchanged. If you intended a string,"
                    " call (name v) at the call site;"
                    " otherwise the keyword is preserved (rf2-6hyy §7.2 D2)."))))))

(defn- ^boolean named?* [x]
  (or (keyword? x) (symbol? x)))

(defn- ^boolean js-val? [x]
  (not (identical? "object" (goog/typeOf x))))

(declare convert-prop-value)

(defn- kv-conv
  "Reduce-kv step: convert one [k v] pair into the JS props object `o`.

  Per rf2-dwds9 MEDIUM: reserved JS keys (`__proto__`, `prototype`,
  `constructor`) are dropped silently. `aset o \"__proto__\" v` would
  invoke the prototype-setter on the props object — replacing its
  prototype chain with whatever `v` is, leaking inherited slots into
  every subsequent property lookup. The key has no legitimate React
  meaning, so dropping is the only correct behaviour."
  [o k v]
  (let [k' (cached-prop-name k)]
    (if (and (string? k') (reserved-prop-key? k'))
      o
      (let [v' (convert-prop-value k v)]
        (aset o k' v')
        o))))

(defn convert-prop-value
  "Convert a hiccup prop-map value `v` for prop-name `k` to a React-
  shaped JS value.

  Per IMPL-SPEC §7.2 (DECISION-2): narrowed keyword stringification.
  Keywords stringify only for HTML-attribute prop names
  (`:class`, `:id`, `:role`, `:data-*`, `:aria-*`).
  Other named values pass through unchanged.

  Other rules:

    - JS values pass through.
    - Maps recursively convert (style maps + custom-component prop maps).
    - Coll? values become JS arrays via clj->js (children, vector classes).
    - Fn values pass through verbatim — referentially stable across renders.
      Event handlers and ref callbacks reach React with the SAME identity
      the caller supplied, so `React.memo` / `shouldComponentUpdate`
      bail-outs work. Wrapping fns in a fresh closure per render would
      silently defeat memoisation.
    - Non-fn `IFn` values (keywords, maps, sets used as fns; vectors used
      as positional lookups) are wrapped in a variadic shim so the React
      side can invoke them as plain JS functions.
    - Everything else passes through unchanged.

  HOT PATH — this runs once per prop on every render. The `(fn? v)`
  test sits before `(ifn? v)` so the common case (event-handler fn) does
  not allocate a wrapper."
  ([v]
   ;; 1-arg form: used recursively for map values where there's no
   ;; outer prop-name context (e.g. nested style objects). Treat
   ;; named? values as not-an-HTML-attr (they're map values, not
   ;; outer props). This matches the stringification choices users
   ;; expect for `:style {:cursor :pointer}` etc. — :cursor is the
   ;; CSS prop name, not an HTML attr.
   (cond
     (js-val? v)  v
     (named?* v)  (name v)         ; nested map values: stringify (CSS values)
     (map? v)     (reduce-kv kv-conv #js {} v)
     (coll? v)    (clj->js v)
     (fn? v)      v                ; pass through — preserves identity
     (ifn? v)     (fn [& args] (apply v args))
     :else        v))
  ([k v]
   (cond
     (js-val? v)  v
     (named?* v)  (if (html-attr-name? k)
                    (name v)
                    (do
                      (when ^boolean js/goog.DEBUG
                        (warn-once-keyword-prop! k v))
                      v))
     (map? v)     (reduce-kv kv-conv #js {} v)
     (coll? v)    (clj->js v)
     (fn? v)      v                ; pass through — preserves identity
     (ifn? v)     (fn [& args] (apply v args))
     :else        v)))

;; ---------------------------------------------------------------------------
;; set-id-class — merge :div.foo#bar parts into the prop map
;;
;; Per IMPL-SPEC §7.3: user :id wins over parsed shorthand id;
;; user :class is **prepended with** parsed shorthand class
;; (matching stock Reagent: `[:div.foo {:class "bar"}]` → "foo bar").
;; ---------------------------------------------------------------------------

(defn class-names
  "Coerce a class-attribute value to its space-joined string form.

  Shapes accepted:
    - 0-arity: nil.
    - 1-arity: nil / keyword / symbol / string / coll-of-those.
    - 2-arity: two values; joined with a space when both are non-nil.

  Returns nil when the result is empty (suppresses redundant
  `class=\"\"` emissions at call sites). Shared between the template
  (React-element) and server (HTML-string) paths — both artefacts
  ship in the same bundle, so a single helper avoids drift."
  ([] nil)
  ([class]
   (if (coll? class)
     (let [classes (keep (fn [c]
                           (when c
                             (if (named?* c) (name c) c)))
                         class)]
       (when (seq classes)
         (str/join " " classes)))
     (if (named?* class)
       (name class)
       class)))
  ([a b]
   (if a
     (if b (str (class-names a) " " (class-names b)) (class-names a))
     (class-names b))))

(defn- set-id-class [props ^HiccupTag parsed]
  (let [id    (.-id parsed)
        class (.-className parsed)]
    (cond-> props
      (and (some? id) (nil? (:id props)))
      (assoc :id id)

      class
      (assoc :class (class-names class
                                 (or (:class props)
                                     (:className props)))))))

(defn- convert-props
  "Convert a hiccup prop map `props` to a React-shape JS props object.
  `parsed` is the HiccupTag with id/class shorthand merged in.

  Returns nil for empty input."
  [props ^HiccupTag parsed]
  (let [class       (:class props)
        normalised  (cond-> props
                      class (assoc :class (class-names class)))
        with-shorthand (set-id-class normalised parsed)
        ^js js-props (when (seq with-shorthand)
                       (reduce-kv kv-conv #js {} with-shorthand))]
    js-props))

;; ---------------------------------------------------------------------------
;; React-key extraction (per stock Reagent)
;;
;; A user can attach a key via `^{:key "k"}` meta on the hiccup vector,
;; OR via `:key` in the props map. We honour the meta first, falling
;; back to the props map.
;; ---------------------------------------------------------------------------

(defn- get-react-key [v]
  (let [k (when (vector? v) (some-> (meta v) :key))]
    (or k
        (case (when (vector? v) (nth v 0 nil))
          (:> :f>) (when (map? (nth v 2 nil)) (:key (nth v 2 nil)))
          :r>      (some-> (nth v 2 nil) (.-key))
          (when (map? (nth v 1 nil)) (:key (nth v 1 nil)))))))

;; ---------------------------------------------------------------------------
;; Source-coord stamping (per IMPL-SPEC §5.4 + §9.4)
;;
;; The renderer's source-coord stamping is gated on this dynamic var.
;; re-frame.views/reg-view*'s wrapper binds it to the formatted attr
;; value when interop/debug-enabled? is true. The first DOM-tag root
;; encountered in as-element gets the attr merged in inline; nested
;; elements see *source-coord* nil (rebound for Form-2 inner-fn calls).
;;
;; Production elision: under :advanced + goog.DEBUG=false, the
;; reg-view* wrapper never binds this var (the wrapper itself sits
;; inside an interop/debug-enabled? gate). The (when *source-coord*
;; ...) check at the as-element entry compiles to `(when nil ...)` and
;; DCEs the entire stamp branch.
;; ---------------------------------------------------------------------------

(def ^:dynamic *source-coord* nil)

(defn- merge-source-coord-attr [props]
  (if-some [coord *source-coord*]
    (do
      ;; Consume the binding so nested DOM elements don't get stamped.
      (set! *source-coord* nil)
      (assoc props :data-rf2-source-coord coord))
    props))

;; ---------------------------------------------------------------------------
;; Hiccup → React element pipeline
;;
;; as-element is the entry. It dispatches on the shape of the form:
;;   - vector  → vec-to-elem
;;   - seq     → expand-seq (children flattening)
;;   - nil     → nil (React renders nothing)
;;   - string/number/JS-value → pass through (text node)
;;
;; vec-to-elem reads the head and dispatches:
;;   - :>  → React-component interop (head supplies the component)
;;   - :<> → React Fragment
;;   - :r> → raw React.createElement
;;   - :f> → function-component dispatch
;;   - keyword DOM tag → parse-tag + DOM element
;;   - reagent class → instantiate as React class
;;   - user fn → wrap via fn-to-class
;; ---------------------------------------------------------------------------

(declare as-element)

(def void-tags
  "HTML5 elements that self-close and have no closing tag. React rejects
  children passed to these; the SSR walker emits bare `<br>` etc. and
  skips the close tag. The list is fixed in HTML5 — no maintenance
  burden. Shared between template (React) and server (HTML string)
  paths to keep one source of truth across the artefact.

  Lockstep with `re-frame.ssr.emit/void-elements` (keyword form, same
  membership). Bundle isolation forbids `:require` across artefacts
  (per rf2-6phn + IMPL-SPEC §14.3), so the set is duplicated by
  intent. If HTML5 ever extends the void element list (extraordinarily
  unlikely), update both copies."
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link"
    "meta" "param" "source" "track" "wbr"})

(defn- ^boolean void-tag? [tag-str]
  (contains? void-tags tag-str))

(defn- make-element
  "Construct a React element via React.createElement.

  `argv` — the original hiccup vector (for child positions).
  `component` — the React component (string for DOM, fn/class for components).
  `js-props` — the converted JS prop object (or nil).
  `first-child` — index in argv of the first child (1 for a no-prop-map
                  vector, 2 if the second element is the prop map)."
  [argv component js-props first-child]
  (let [n-children (- (count argv) first-child)
        ;; Void elements: skip child translation; React rejects them.
        void-elem? (and (string? component) (void-tag? component))]
    (cond
      void-elem?
      (react/createElement component js-props)

      (== n-children 0)
      (react/createElement component js-props)

      (== n-children 1)
      (react/createElement component js-props
                           (as-element (nth argv first-child nil)))

      :else
      ;; Loop from first-child rather than reduce-kv over the whole
      ;; argv (which would test the predicate at every k=0..first-child-1
      ;; before the children start). Hot-path at large children counts
      ;; (1000-row tables, dynamic lists).
      (let [args #js [component js-props]
            n    (count argv)]
        (loop [i first-child]
          (when (< i n)
            (.push args (as-element (nth argv i nil)))
            (recur (inc i))))
        (.apply (.-createElement react) nil args)))))

(defn expand-seq
  "Expand a sequence of hiccup forms (e.g. `(map ...)` children) to a
  JS array of React elements. Per IMPL-SPEC §7.4 — emits a one-shot
  dev warning per surrounding component when a child vector lacks a
  `:key` meta or `:key` in its prop map.

  Single-pass: the DEBUG key-check runs inline with the as-element
  conversion (was two passes over the same lazy seq); production
  builds (`goog.DEBUG=false`) DCE the inner branch."
  [s]
  (let [arr #js []]
    (loop [items s]
      (when-let [el (first items)]
        (when ^boolean js/goog.DEBUG
          (when (and (vector? el)
                     (not (get-react-key el))
                     (exists? js/console))
            (.warn js/console
                   (str "[reagent-slim] each child in a list should have a unique"
                        " :key prop; saw " (pr-str el)))))
        (.push arr (as-element el))
        (recur (rest items))))
    arr))

(defn- ^boolean hiccup-tag? [x]
  (or (keyword? x) (symbol? x) (string? x)))

;; ---------------------------------------------------------------------------
;; Shared hiccup-shape detection
;;
;; Five sites (native-element, fragment-element, emit-dom-vector,
;; emit-fragment, plus the make-element consumer) ask the same shape
;; question: "is the slot at `first-pos` a props map, and where do
;; children start?" One helper, one shape — drift-proof.
;; ---------------------------------------------------------------------------

(defn hiccup-shape
  "Inspect `argv` starting at index `first-pos`. Returns a 3-element
  vector `[head has-props? first-child]` where:

    - `head` is `(nth argv first-pos nil)`.
    - `has-props?` is true when `head` is nil or a map (the
      props-map slot is the conventional Reagent shape).
    - `first-child` is the argv index where children begin
      (`first-pos + 1` if a props map is present, else `first-pos`)."
  [argv first-pos]
  (let [head      (nth argv first-pos nil)
        has-props (or (nil? head) (map? head))
        first-child (+ first-pos (if has-props 1 0))]
    [head has-props first-child]))

(defn- native-element
  "Emit a DOM element. `parsed` is the parsed HiccupTag; `argv` the
  full hiccup vector; `first-pos` the index of the first arg position
  (1 for `:div ...`, 2 for `:> Component ...` etc.).

  Source-coord stamping (§5.4 + §9.4): the first DOM-tag root inside
  a reg-view'd render gets the *source-coord* dynamic var merged in
  as `:data-rf2-source-coord`. The merge happens before
  prop-conversion so the attr name flows through cached-prop-name."
  [^HiccupTag parsed argv first-pos]
  (let [component (.-tag parsed)
        [head has-props first-child] (hiccup-shape argv first-pos)
        props     (cond-> (when has-props head)
                    *source-coord* merge-source-coord-attr)
        js-props  (or (convert-props props parsed) #js {})]
    (when-some [key (get-react-key argv)]
      (set! (.-key js-props) key))
    (make-element argv component js-props first-child)))

(defn- react-component-element
  "Emit a React element where `component` is a Reagent / arbitrary
  React component head (function or class). `argv` is the full hiccup
  vector; `first-pos` is the position of the props map (or 1 for
  user-fn calls — the head IS the user fn at index 0).

  For Reagent-style user fns we wrap via `fn-to-class` so the class
  has reactive subscription (deref-capture) wired in render. The argv
  travels through React's props as `__rfArgv`."
  [component argv]
  (let [klass    (cond
                   (component/reagent-class? component) component
                   (component/react-class? component)   component
                   :else                                 (component/fn-to-class component))
        js-props #js {:__rfArgv argv}]
    (when-some [key (get-react-key argv)]
      (set! (.-key js-props) key))
    (react/createElement klass js-props)))

(defn- raw-element
  "Emit `:r>` — raw React.createElement passthrough.
  `[:r> Component js-props & children]` translates to
  `React.createElement(Component, js-props, ...children)` with
  no prop conversion."
  [argv]
  (let [component (nth argv 1 nil)
        js-props  (or (nth argv 2 nil) #js {})]
    (when-some [key (-> (meta argv) :key)]
      (set! (.-key js-props) key))
    (make-element argv component js-props 3)))

(defn- function-element
  "Emit `:f>` — function-component dispatch.
  `[:f> some-fn args...]` wraps `some-fn` via `fn-to-class` so that
  Reagent-shaped reactivity (deref-capture) wires through. We treat
  this identically to `react-component-element` because all our user
  fns reach the renderer via fn-to-class."
  [argv]
  (let [component (nth argv 1 nil)
        ;; Reconstruct the argv as if the fn were the head:
        ;; [head & user-args] becomes [some-fn & user-args].
        ;; Preserve the original argv's :key meta so React keys flow.
        synth-argv (with-meta (into [component] (subvec argv 2))
                              (meta argv))]
    (react-component-element component synth-argv)))

(defn- fragment-element
  "Emit `:<>` — React.Fragment.
  `[:<> & children]` or `[:<> {:key k} & children]`. Props map (if
  present) is JS-converted; only `:key` is meaningful on Fragments."
  [argv]
  (let [[head has-props first-child] (hiccup-shape argv 1)
        js-props  (or (when (and has-props (some? head))
                        (convert-prop-value head))
                      #js {})]
    (when-some [key (get-react-key argv)]
      (set! (.-key js-props) key))
    (make-element argv (.-Fragment react) js-props first-child)))

(defn- interop-element
  "Emit `:>` — arbitrary React component interop.
  `[:> Component {:prop ...} & children]`. The component is the second
  element; standard prop conversion applies."
  [argv]
  (let [component (nth argv 1 nil)
        ;; Build a synthetic HiccupTag that names the component by
        ;; its component slot. We pass nil for id/class — :> doesn't
        ;; use the shorthand; the tag is a foreign component.
        synth-tag (->HiccupTag component nil nil)]
    (native-element synth-tag argv 2)))

(defn vec-to-elem
  "Dispatch on a hiccup vector's head and emit the React element."
  [argv]
  (when (zero? (count argv))
    (throw (ex-info ":rf.error/template-empty-vector"
                    {:type :rf.error/template-empty-vector
                     :reason "Hiccup vector cannot be empty."})))
  (let [tag (nth argv 0 nil)]
    (cond
      ;; Interop heads — checked first so they don't get treated as
      ;; user fns or DOM tags.
      (= tag :>)  (interop-element argv)
      (= tag :<>) (fragment-element argv)
      (= tag :r>) (raw-element argv)
      (= tag :f>) (function-element argv)

      ;; DOM-tag head — keyword, symbol, or string.
      (hiccup-tag? tag)
      (native-element (cached-parse tag) argv 1)

      ;; Reagent / React class head — instantiate directly.
      (component/reagent-class? tag)
      (react-component-element tag argv)

      (component/react-class? tag)
      (react-component-element tag argv)

      ;; Plain user fn head — wrap via fn-to-class for reactive
      ;; subscription wiring.
      (fn? tag)
      (react-component-element tag argv)

      :else
      (throw (ex-info ":rf.error/template-bad-tag"
                      {:type   :rf.error/template-bad-tag
                       :tag    tag
                       :argv   argv
                       :reason "Hiccup head must be a keyword (DOM tag or :>/:<>/:r>/:f>),
                                a Reagent component class, a React component class, or a fn."})))))

(defn as-element
  "Top-level hiccup → React element conversion.

  Dispatches per IMPL-SPEC §7.1:

    - string / number / JS value → pass through (text node)
    - vector → vec-to-elem (handles all the hiccup head cases)
    - seq → expand-seq (sequence-as-children with key warnings)
    - keyword / symbol → name (text node)
    - nil → nil (React renders nothing)
    - everything else → pass through (let React surface its own error
      if it's not renderable)"
  [x]
  (cond
    (nil? x)        nil
    (js-val? x)     x
    (vector? x)     (vec-to-elem x)
    (seq? x)        (expand-seq x)
    (named?* x)     (name x)
    (satisfies? IPrintWithWriter x) (pr-str x)
    :else           x))

;; Per rf2-08t0: register `as-element` with `reagent2.impl.component`
;; so the class's render() method can convert hiccup (returned by
;; `wrap-render` per IMPL-SPEC §5.1) into React elements before
;; handing back to React. Statically `:require`ing this ns from
;; component.cljs would induce a cycle (template already requires
;; component for `fn-to-class` / `reagent-class?`), so the seam is a
;; one-shot `set-as-element-fn!` at template's ns-load — same pattern
;; as `re-frame.late-bind`.
(component/set-as-element-fn! as-element)
