(ns re-frame.bench.hicasso.front.codec
  "THE HICCUP CODEC — deliverable 1 of the Wave-1 shared front half
  (rf2-2rtt6.8). Runtime interpretation of arbitrary hiccup into React
  elements, built by extracting reagent-slim's *measured* tag/prop/child
  plumbing.

  ## What was taken, and what was deliberately left

  Taken from `reagent2.impl.template`, because it is the plumbing the P0
  and HD-008 instruments actually clocked: the `#id.class` tag regex and
  its parse; the kebab→camel prop-name rule with its `aria`/`data`
  exemptions, its `--custom-property` passthrough and its three seeded
  entries; the `:style` map→JS-object conversion; the class coercion and
  the shorthand merge; the single-pass sequence expansion that does not
  truncate on an interior `nil`; and the 0/1/N `createElement` arms with
  the `.apply` path for long child lists.

  **Left behind, by ruling and on purpose**: the component protocol, the
  ratoms, the argv-equality memoization, and the scheduler. None of them
  is here, none of them is reachable from here, and the codec requires
  nothing from a donor. Reagent's equality semantics in particular are
  not recreated (HD-006): every default comparison is a cost every render
  pays, and narrow updates in this design come from boundary placement.

  Also absent, and worth naming so their absence reads as a decision
  rather than an omission: the `:r>` raw-props path, the class-component
  `__rfArgv` crossing, `defhost`/`[:>]` interop (HD-011 — its own
  surface), and the adapters' reserved-head and keyword-prop diagnostics
  (public-boundary policy for a shipped adapter; this codec has no public
  boundary, and the pre-alpha stance is to trust the programmer).

  ## Codec-work caching only (HD-004)

  Two caches, both keyed by the author's literal and both plain JS
  objects with an own-property guard so a hiccup tag or prop literally
  named `__proto__` cannot poison them:

    tag-cache    \"div#main.wide\" -> ParsedTag
    prop-cache   \"on-click\"      -> \"onClick\"

  That is the whole of the accelerant HD-004 permits in the lean arm.
  There is **no template extraction, no hole plan, no node reference, and
  no direct DOM write** — any of those *is* the PATCH strategy and has to
  say so. There is no memoization of converted props objects and no
  memoization of elements: `convert-props` mints a fresh object per
  element per render, exactly as the measured donor does, so the clock
  this codec is compared on is the clock that was measured.

  **The third cache HD-004 names — cached stable component heads — costs
  nothing here, and that is the interesting part.** reagent-slim needs
  one because `:f>` takes a *plain* function and must not mint a fresh
  React type per render. Hicasso's `defview` mints the function component
  once, at definition, and [[mark-boundary!]] records that on the fn
  itself; the head is then identity-stable by construction and the cache
  has nothing to do. This is also why HD-016 makes a plain function in
  head position a loud error rather than auto-wrapping it: auto-wrapping
  is precisely the thing that would need the cache back.

  ## The arm boundary

  Analysis — tag parse, prop names, class merge, child realization, head
  classification — is arm-neutral and is what both tournament arms share.
  **Emission is not**, and this file emits React elements, i.e. Arm 1's
  representation. Hicasso/PATCH (rf2-2rtt6.10) reuses the analysis and
  brings its own emitter; that is the honest shape of \"the arm's element
  representation\" in architecture.md, and it is the reason the two are
  kept visibly apart below rather than interleaved.

  ## The component ABI (HD-016)

  | Head | Props | Children | `:key` |
  |---|---|---|---|
  | Native tag | attr map | trailing forms; seqs realized once and flattened one level; `nil`/`false` render nothing, `true` errors | `:key` in the attr map |
  | Boundary (a marked `defview` product) | one props map, every lazy sequence in it realized ([[realize-deep]]) | trailing forms as `(:children props)`, a realized vector | `:key` in the props map, extracted before the body sees props |
  | Fragment `[:<> …]` | optional attr map | trailing forms | on the fragment's props map |

  A React element is a legal child anywhere. No metadata keys, no second
  calling convention, and a plain function in head position is a loud
  error.

  ## Two reserved attribute keys, and nothing else

  | Key | Meaning |
  |---|---|
  | `:&` | the caller's remainder map. One merge, one law: **the literal keys written in the map always win** (HD-023, [[merge-caller]]) |
  | `:ref` | a callback ref in v0; a **vector** is the reserved data spelling and is refused loudly (HD-022, [[check-ref!]]) |

  Both are attribute *keys*, not forms — so the merge survives into a
  structural test and into tooling, and neither adds a public concept in
  the K5 sense."
  (:require [clojure.string :as str]
            [re-frame.bench.hicasso.front.intent :as intent]
            ["react" :as react]))

(declare as-element)

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(defn- fail!
  "The codec's one refusal shape, matching the arm runtime's: an id a test
  can assert on, the position that refused, why, and what to do instead."
  [id where reason recovery extra]
  (throw (ex-info (str reason " [" id "]")
                  (merge {:rf.error/id id :where where
                          :reason reason :recovery recovery}
                         extra))))

;; ---------------------------------------------------------------------------
;; Cache hygiene — the own-property guard both caches share
;; ---------------------------------------------------------------------------

(def ^:private reserved-cache-keys
  "Never read from or written to a JS-object cache: a hiccup literal with
  one of these names would otherwise reach the object's prototype."
  #{"__proto__" "prototype" "constructor"})

(def ^:private has-own (.-hasOwnProperty (.-prototype js/Object)))

(defn- own-key?
  "`hasOwnProperty`, called off the prototype, so an object carrying its
  own `hasOwnProperty` property cannot shadow the check."
  [o k]
  (.call has-own o k))

;; ---------------------------------------------------------------------------
;; Tag parsing and its cache
;; ---------------------------------------------------------------------------

(def ^:private re-tag
  "`tag`, optional `#id`, optional `.class.class`. The `#id` must precede
  the classes, as in the donor and in stock Reagent."
  #"([^\s\.#]+)(?:#([^\s\.#]+))?(?:\.([^\s#]+))?")

(deftype ParsedTag [tag id className])

(defn parse-tag
  "Parse `:div#main.wide.tall` into its tag, id and class string."
  [hiccup-tag]
  (let [[_ tag id classes] (re-matches re-tag (name hiccup-tag))]
    (->ParsedTag tag id (when classes (str/replace classes #"\." " ")))))

(def ^:private tag-cache #js {})

(defn- cache-key [k]
  (if-let [ns' (namespace k)] (str ns' "/" (name k)) (name k)))

(defn cached-parse
  "[[parse-tag]] behind the codec-work cache (HD-004). One entry per
  distinct tag literal the build ever renders."
  [hiccup-tag]
  (let [k (cache-key hiccup-tag)]
    (if (reserved-cache-keys k)
      (parse-tag hiccup-tag)
      (if (own-key? tag-cache k)
        (unchecked-get tag-cache k)
        (let [parsed (parse-tag hiccup-tag)]
          (unchecked-set tag-cache k parsed)
          parsed)))))

;; ---------------------------------------------------------------------------
;; Prop names and their cache
;; ---------------------------------------------------------------------------

(def ^:private dont-camel-case
  "`aria-*` and `data-*` are HTML attribute names in React too, and
  camelCasing them would break them."
  #{"aria" "data"})

(defn- capitalize [s]
  (if (< (count s) 2) (str/upper-case s) (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn prop-name
  "The React prop name for a hiccup prop key. `:on-click` → `\"onClick\"`;
  `:aria-label` and `:data-index` pass through; a `--custom-property` is
  preserved verbatim."
  [k]
  (if (string? k)
    k
    (let [n (name k)]
      (if (str/starts-with? n "--")
        n
        (let [[start & parts] (str/split n #"-")]
          (if (dont-camel-case start)
            n
            (apply str start (map capitalize parts))))))))

(def ^:private prop-cache
  (doto #js {}
    (unchecked-set "class" "className")
    (unchecked-set "for" "htmlFor")
    (unchecked-set "charset" "charSet")))

(defn cached-prop-name
  "[[prop-name]] behind the codec-work cache (HD-004)."
  [k]
  (if-not (or (keyword? k) (symbol? k) (string? k))
    k
    (let [n (name k)]
      (if (reserved-cache-keys n)
        (prop-name k)
        (if (own-key? prop-cache n)
          (unchecked-get prop-cache n)
          (let [converted (prop-name k)]
            (unchecked-set prop-cache n converted)
            converted))))))

;; ---------------------------------------------------------------------------
;; Classes and the shorthand merge
;; ---------------------------------------------------------------------------

(defn class-names
  "Coerce a `:class` value — a string, a keyword, a symbol, or a
  collection of those, with nils dropped — to one space-joined string, or
  nil when nothing survives."
  ([] nil)
  ([class]
   (cond
     (nil? class)     nil
     (string? class)  (when (seq class) class)
     (or (keyword? class) (symbol? class)) (name class)
     (coll? class)    (let [joined (->> class (keep class-names) (str/join " "))]
                        (when (seq joined) joined))
     :else            (str class)))
  ([a b]
   (let [a (class-names a) b (class-names b)]
     (cond (nil? a) b (nil? b) a :else (str a " " b)))))

(defn- merge-shorthand
  "Fold the tag's `#id`/`.class` shorthand into the props map. An explicit
  `:id` wins over the shorthand; the shorthand class is *prepended* to an
  explicit class, so `[:div.a {:class \"b\"}]` is `\"a b\"`."
  [props ^ParsedTag parsed]
  (let [id        (.-id parsed)
        shorthand (.-className parsed)
        props     (if (and id (not (contains? props :id))) (assoc props :id id) props)
        declared  (or (:class props) (:className props))
        merged    (class-names shorthand declared)]
    (cond-> (dissoc props :className :class)
      merged (assoc :class merged))))

;; ---------------------------------------------------------------------------
;; Prop values
;; ---------------------------------------------------------------------------

(declare convert-prop-value)

(defn- nested-map->js
  "A nested map — `:style` and its kin — with camelCased keys."
  [m]
  (reduce-kv (fn [o k v] (unchecked-set o (cached-prop-name k) (convert-prop-value v)) o)
             #js {}
             m))

(defn convert-prop-value
  "A prop value in the shape React wants. Functions pass through **by
  identity**, deliberately: rewrapping them would defeat `React.memo` and
  every downstream bail-out that compares handler identity."
  [v]
  (cond
    (fn? v)                  v
    (map? v)                 (nested-map->js v)
    (or (keyword? v) (symbol? v)) (name v)
    (coll? v)                (clj->js v)
    :else                    v))

;; ---------------------------------------------------------------------------
;; `:&` — the one attribute merge, and the owned-literal law (HD-023)
;; ---------------------------------------------------------------------------

(def merge-key
  "`:&` — the reserved attribute key carrying a caller's remainder map.
  There is exactly one merge in Hicasso and this is it. It is DATA, not a
  call, so a forwarded remainder survives into a structural test and into
  tooling; and it cannot collide with a real DOM attribute."
  :&)

(def merge-structural-keys
  "Never taken from a `:&` map. `:key` and `:ref` address the ELEMENT the
  caller wrote, not the one it is forwarding onto — React's key contract
  and HD-016's ref rule are both about node identity, and a remainder map
  is about attributes. Dropping them is what makes a hostile or careless
  remainder unable to reach either."
  #{:key :ref})

(defn merge-caller
  "Fold a `:&` remainder into the attribute map, under **the owned-literal
  law, unconditionally**: the literal keys written in the map ALWAYS win.

  This is HD-010(a)'s law — `:key`, `:ref`, controlled `:value`/`:checked`
  and owned event handlers are unoverridable — applied to *every* merge
  rather than only under theming, and it is the whole point of the
  ruling. The predecessor needs the author to choose between three merge
  forms depending on where the target is, and the penalty for choosing
  wrong is SILENT: caret and IME protection simply stop, with no
  diagnostic anywhere. Making the law unconditional deletes that class —
  the controlled-input door cannot be forfeited by a merge at all,
  because a merge cannot reach an owned literal.

  The case where a caller override SHOULD win is spelled by **not writing
  the literal**, which is the honest way round: the dangerous default is
  the other one.

  Absent — the overwhelming case — this is one `contains?` and the map
  comes back by identity, allocating nothing. Present, it is one `merge`
  in the direction that makes the law true by construction."
  [props]
  (if-not (contains? props merge-key)
    props
    (let [caller (get props merge-key)
          owned  (dissoc props merge-key)]
      (cond
        (nil? caller) owned
        (map? caller) (merge (apply dissoc caller merge-structural-keys) owned)
        :else
        (fail! :rf.error/hicasso-merge-not-a-map
               'front.codec/merge-caller
               (str ":& carries a caller's attribute map and nothing else. It was "
                    "given " (pr-str (type caller)) ". Forward a map, or drop the key.")
               :forward-a-map-at-the-merge-key
               {:value caller})))))

;; ---------------------------------------------------------------------------
;; The reserved `:ref` value-space (HD-022)
;; ---------------------------------------------------------------------------

(defn- check-ref!
  "`:ref` takes a **function** in v0 — HD-003's honest escape hatch, and
  HD-016's callback-refs-only rule, both unchanged. A **vector** is the
  reserved spelling for the later data form, `{:ref [::autosize {:max-rows
  8}]}`, and v0 refuses it here rather than handing React an opaque array
  it would ignore in silence.

  One branch and one error id. The point is not the branch: it is that the
  value-space is claimed *now*, so the imperative escape can become data
  later without minting a second attribute name — and so that an author
  who writes tomorrow's spelling today learns it from a diagnostic rather
  than from a ref that never fires."
  [props]
  (when (vector? (:ref props))
    (fail! :rf.error/hicasso-ref-vector-reserved
           'front.codec/convert-props
           (str "A vector at :ref is RESERVED and is not a v0 surface. "
                "`{:ref [registered-id config]}` is the reserved spelling for "
                "registered node ownership; v0 accepts a callback ref (a "
                "function) only. Write the function, or move the mechanic to "
                "an event and an effect.")
           :use-a-callback-ref-or-an-effect
           {:ref (:ref props)}))
  props)

(defn convert-props
  "One pass over the attribute map: fold a `:&` remainder under the
  owned-literal law, refuse a reserved `:ref` value, fold the tag
  shorthand, drop `:key` (React's own contract — it is not an attribute),
  lower every intent, and set each converted value under its React prop
  name.

  Two things about the order. Intent lowering happens *inside* the single
  walk, so the codec does not traverse the props map a second time to
  find the event positions. And [[merge-caller]] runs FIRST — before the
  tag shorthand and before any prop-name conversion — which is what lets
  one rule cover the class the predecessor needs a third form for. A
  forwarded `:className` is merged as the key it was written as and then
  converted by *this position's* grammar; nothing canonicalises it into
  `:class` on the way through and hands the wrong name onward. It also
  puts a forwarded `:class` into the shorthand merge, so
  `[:input.form-control {:& caller}]` composes `\"form-control\"` with the
  caller's classes instead of one silently replacing the other."
  [props ^ParsedTag parsed]
  (let [props (-> props merge-caller check-ref! (merge-shorthand parsed) (dissoc :key))]
    (reduce-kv (fn [o k v]
                 (let [n (cached-prop-name k)]
                   (when-not (reserved-cache-keys n)
                     (unchecked-set o n (convert-prop-value (intent/lower-prop k v))))
                   o))
               #js {}
               props)))

;; ---------------------------------------------------------------------------
;; Boundary heads (HD-016 / HD-004)
;; ---------------------------------------------------------------------------

(defn mark-boundary!
  "Record that `f` — a React function component an arm's `defview` minted
  — is a legal hiccup head. Returns `f`, so a `defview` can end with it."
  [f]
  (unchecked-set f "hicassoBoundary" true)
  f)

(defn boundary-head?
  "Is `f` a marked boundary? One own-property read; no registry, no map."
  [f]
  (and (fn? f) (true? (unchecked-get f "hicassoBoundary"))))

;; ---------------------------------------------------------------------------
;; Hiccup shape
;; ---------------------------------------------------------------------------

(defn- props-map?
  "Slot `i` of a hiccup vector is the props map when it is a map. A seq
  there is a child, as is a string, as is another hiccup vector."
  [argv i]
  (map? (nth argv i nil)))

(defn realize-children
  "The trailing forms of `argv` from `first-child`, realized once into a
  vector and flattened exactly one level — a nested seq splices, a nested
  *vector* does not, because a vector is hiccup. Returns nil when there
  are none, so `(:children props)` is absent rather than empty."
  [argv first-child]
  (when (< first-child (count argv))
    (let [flat (reduce (fn [acc c] (if (seq? c) (into acc c) (conj acc c)))
                       []
                       (subvec argv first-child))]
      (when (seq flat) flat))))

(declare realize-deep)

;; The two reducing functions are top-level vars rather than literals
;; written at the reduce sites, and that is the whole of the walk's
;; allocation story: a `(fn …)` written inside [[realize-deep]] would
;; mint a fresh function object on every collection visited, and `run!`
;; mints one of its own. Named here, the walk allocates nothing at all.

(defn- realize-entry [_ _ x] (realize-deep x) nil)
(defn- realize-item  [_ x]   (realize-deep x) nil)

(defn realize-deep
  "Force every lazy sequence reachable from `v`, and return **`v` itself,
  by identity**.

  ## Why a boundary needs this and a native tag does not

  The codec is eager everywhere it walks, and that is what makes a `(sub
  …)` inside a `for` an edge of the body that wrote it rather than a
  silently missing one: `expand-seq` drives a child seq to exhaustion,
  [[realize-children]] folds one into a vector, and [[convert-prop-value]]
  sends any collection at a NATIVE prop position through `clj->js`.

  A boundary prop is the one position where none of that happens.
  [[boundary-element]] hands `body-props` across as a raw ClojureScript
  map and the shell reads it back as one — no conversion, and so no
  realisation. A lazy seq written in one body therefore arrives in
  another body unrealised, and is forced *there*, inside a render that
  did not write it. The runtime cannot refuse it — its render frame
  answers *is any body running*, and one is — so the read is attributed
  to the wrong boundary; and because a `LazySeq` caches, that boundary
  re-renders exactly once, reads nothing the second time, and drops the
  edges. The value is then correct on screen and frozen for the life of
  the mount. rf2-2rtt6.45.

  One walk at the hand-off closes it, and pays where the escape is: the
  read is forced by the same pass that turns hiccup into elements, inside
  the window of the body that wrote it.

  ## What it costs, measured

  Realising a `LazySeq` caches into the seq itself, so this **rebuilds
  nothing and copies nothing** — every branch returns the argument it was
  given, and with the two reducing functions named above the walk
  allocates nothing either. The traversal is the whole of the work, and
  the seq it forces was going to be walked anyway, one boundary later.

  Clocked rather than asserted (`:none` build, Node 22, best of five
  runs; the figures are a dev build's and are quoted for their ratios):

  | Boundary props | walk | whole element build | share |
  |---|---|---|---|
  | `{:id :title :done?}` — the dogfood row | 69 ns | 1089 ns | **6%** |
  | the same plus two hiccup children | 233 ns | 1344 ns | 17% |
  | a 100-row collection prop | 13.5 µs | 15.1 µs | 89% |

  The last row is the honest ceiling and it is the right one to compare
  outwards rather than inwards: **the same 100-row collection at a
  NATIVE prop position costs 70.7 µs**, because `clj->js` rebuilds it
  into JavaScript. The position whose eagerness the structural claim
  already rested on is 4.7x dearer than the position this walk repairs.

  Maps are reduced with `reduce-kv` rather than over their entries, so
  the walk allocates no `MapEntry`; their keys are not descended into
  because hashing a seq realises it, so a lazy seq cannot already be a
  key in a map. `coll?` is tested before `map?` so a scalar — the
  overwhelming case — costs exactly one predicate.

  A seq of unbounded length at a boundary prop position now diverges here
  rather than in the child. That is the same thing `clj->js` already does
  to one at a native prop position, and it must be: a deferred read
  cannot be both unbounded and attributable."
  [v]
  (if-not (coll? v)
    v
    (do (if (map? v)
          (reduce-kv realize-entry nil v)
          (reduce realize-item nil v))
        v)))

;; ---------------------------------------------------------------------------
;; Emission — React elements (Arm 1's representation)
;; ---------------------------------------------------------------------------

(defn- expand-seq
  "A seq of children as a JS array of elements. One pass, driven by the
  seq's own exhaustion rather than by each child's truthiness, so an
  interior `nil` — the `(for [x xs] (when pred [:li …]))` shape — does not
  truncate the list."
  [s]
  (let [a #js []]
    (loop [items (seq s)]
      (when items
        (.push a (as-element (first items)))
        (recur (next items))))
    a))

(defn- make-element
  "`createElement` with the donor's three arms: no children, one child,
  and the `.apply` path that builds an argument array for the rest. The
  loop is deliberate — a long keyed list is the hot case."
  [component js-props argv first-child]
  (let [n (count argv)]
    (case (- n first-child)
      0 (react/createElement component js-props)
      1 (react/createElement component js-props (as-element (nth argv first-child)))
      (let [args #js [component js-props]]
        (loop [i first-child]
          (when (< i n)
            (.push args (as-element (nth argv i)))
            (recur (inc i))))
        (.apply (.-createElement react) nil args)))))

(defn- native-element [argv]
  (let [parsed      (cached-parse (nth argv 0))
        has-props?  (props-map? argv 1)
        props       (if has-props? (nth argv 1) nil)
        js-props    (convert-props (or props {}) parsed)]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (make-element (.-tag parsed) js-props argv (if has-props? 2 1))))

(defn- boundary-element [argv]
  (let [has-props? (props-map? argv 1)
        ;; The SAME merge, at the crossing. This is the case the
        ;; predecessor needs a third rule for: its spread forms are
        ;; element forms whose content is the attribute grammar, so
        ;; sending a remainder through one on the way to a declared
        ;; foreign head rewrites `:className` into the `:class` slot and
        ;; the component never sees the prop it reads — which is why
        ;; "neither spread form is legal there" and an ordinary `merge`
        ;; is prescribed instead. `:&` has no such problem because it is
        ;; not a spread: it is a key in the props map, merged before any
        ;; conversion, and the conversion that follows is the POSITION's
        ;; own — a props map handed to a boundary or a declared foreign
        ;; head is passed through unrenamed. One rule, both positions,
        ;; and the owned-literal law holds identically at each.
        props      (merge-caller (if has-props? (nth argv 1) {}))
        children   (realize-children argv (if has-props? 2 1))
        ;; THE HAND-OFF, and the one position the eager codec did not
        ;; reach. Everywhere else a lazy read is forced by the pass that
        ;; turns hiccup into elements; here the map crosses untouched, so
        ;; a seq written in THIS body would be realised inside the
        ;; child's render and attributed to it — silently, and then
        ;; frozen, because a realised `LazySeq` is never walked a second
        ;; time. [[realize-deep]] returns the map by identity and covers
        ;; `:children` in the same pass, which is where the one-level
        ;; flatten leaves a nested seq. rf2-2rtt6.45.
        body-props (realize-deep (cond-> (dissoc props :key)
                                   children (assoc :children children)))
        js-props   #js {"rfProps" body-props}]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (react/createElement (nth argv 0) js-props)))

(defn- fragment-element [argv]
  (let [has-props? (props-map? argv 1)
        props      (if has-props? (nth argv 1) nil)
        js-props   #js {}]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (make-element (.-Fragment react) js-props argv (if has-props? 2 1))))

(defn- fragment-head?
  "Is this the fragment spelling? Compared with `=`, never `identical?`:
  ClojureScript keyword literals are shared constants only when the build
  interns them, so an identity comparison here works under `:advanced`
  and silently routes every fragment into the native-tag path everywhere
  else."
  [head]
  (= :<> head))

(defn- hiccup-tag? [head]
  (and (or (keyword? head) (symbol? head) (string? head))
       (not (fragment-head? head))))

(defn vec->element
  "Interpret one hiccup vector."
  [argv]
  (when (zero? (count argv))
    (throw (ex-info (str "Empty hiccup vector. [:rf.error/hicasso-empty-vector]")
                    {:rf.error/id :rf.error/hicasso-empty-vector
                     :where       'front.codec/vec->element
                     :reason      "A hiccup vector must have a head."
                     :recovery    :supply-a-hiccup-head})))
  (let [head (nth argv 0)]
    (cond
      (fragment-head? head)   (fragment-element argv)
      (hiccup-tag? head)      (native-element argv)
      (boundary-head? head)   (boundary-element argv)
      :else
      (throw (ex-info (str "Hiccup head " (pr-str head) " is not a valid element head; "
                           "use a tag keyword, :<>, or a view minted by defview. "
                           "A plain function in head position is never a silent "
                           "embedding — call it, or make it a view. "
                           "[:rf.error/hicasso-bad-head]")
                      {:rf.error/id :rf.error/hicasso-bad-head
                       :where       'front.codec/vec->element
                       :reason      (if (fn? head)
                                      "A plain function in head position is a loud error (HD-016)."
                                      "Hiccup head must be a tag keyword, :<>, or a defview product.")
                       :recovery    (if (fn? head) :call-it-or-make-it-a-view :supply-a-valid-hiccup-head)})))))

(defn as-element
  "Interpret any hiccup form. `nil` and `false` render nothing; `true` is
  an error (HD-016); an existing React element passes through."
  [x]
  (cond
    (nil? x)         nil
    (false? x)       nil
    (vector? x)      (vec->element x)
    (string? x)      x
    (number? x)      x
    (seq? x)         (expand-seq x)
    (true? x)        (throw (ex-info (str "`true` is not a renderable child. "
                                          "[:rf.error/hicasso-true-child]")
                                     {:rf.error/id :rf.error/hicasso-true-child
                                      :where       'front.codec/as-element
                                      :reason      "nil and false render nothing; true is an error (HD-016)."
                                      :recovery    :use-nil-or-false}))
    (react/isValidElement x) x
    (keyword? x)     (name x)
    (symbol? x)      (name x)
    :else            x))

;; ---------------------------------------------------------------------------
;; Cache observation — for the tests and the bench, never for the runtime
;; ---------------------------------------------------------------------------

(defn cache-sizes []
  {:tags  (count (js/Object.keys tag-cache))
   :props (count (js/Object.keys prop-cache))})

(defn reset-caches!
  "Empty both codec caches. The prop cache keeps its three seeded entries,
  because those are the rule and not a memo of one."
  []
  (doseq [k (js/Object.keys tag-cache)] (js-delete tag-cache k))
  (doseq [k (js/Object.keys prop-cache)] (js-delete prop-cache k))
  (unchecked-set prop-cache "class" "className")
  (unchecked-set prop-cache "for" "htmlFor")
  (unchecked-set prop-cache "charset" "charSet")
  nil)
