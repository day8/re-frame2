(ns re-frame.hicasso.impl.codec
  "The hiccup codec: arbitrary hiccup in, React elements out, over
  reagent-slim's measured tag/prop/child plumbing and nothing else of
  reagent-slim's — no component protocol, no ratoms, no scheduler, and
  no runtime require of it. The outward bridge at the end of the file
  (`as-component`) runs the other way: a React parent's props object
  into a hiccup body's props map.

  The component ABI (HD-016, HD-011):

  | Head | Props | Children | `:key` |
  |---|---|---|---|
  | Native tag | attr map, each key emitted under one canonical slot, the tag's `#id.class` shorthand folded onto the emitted object | trailing forms; a seq realized once and spliced; `nil`/`false` render nothing, `true` is an error | literal `:key` in the attr map |
  | Boundary (a marked `defview` head) | one props map crossing as a CLJS value, every lazy seq in it forced and an unforced `delay` refused (`realize-deep`) | trailing forms as `(:children props)`, a realized vector | literal `:key`, taken off before the body sees props |
  | Host (a `defhost` head), or `[:> Component …]` | attr map converted shallowly (`host-entry`): declared `:callbacks` contracts applied, declared `:slots` lowered to elements, `:ref` untouched, the class slot coerced and composed, the rest inferred by position | trailing forms lowered to elements in the writing boundary's render window, handed on as React children | literal `:key` |
  | Fragment `[:<> …]` | optional attr map | trailing forms | on the fragment |

  A React element is a legal child anywhere; a plain function in head
  position is a loud error. Five keys are never emitted as attributes:
  `:key` and `:ref` are React's (the structural slots, denied in every
  spelling); `:re-frame.hicasso/revision` is a controlled element's
  reset trigger, read off the map by `native-element`; the two presence
  override keys belong to a tray and are skipped by every walk.

  Every rule about WHICH attribute a value is — the structural deny,
  the ref position's exclusion from intent lowering, the class slot's
  coercion, the shorthand fold — is asked of `canonical-slot`, the
  React name the value is emitted under, never of the key it was
  written as: a rule written against the spelling is one that `\"key\"`,
  `:x/ref` and `:onInput` walk past. The rule itself is
  `re-frame.hicasso.impl.slot/prop-name`, in `.cljc`, because the
  migration codemod decides the same slots on the JVM; only the caching
  of its answers lives here. `:key` and the revision key are the two
  exact-keyword exceptions, because they are triggers rather than
  attributes, and claiming the slot `revision` would make bare
  `:revision` mean *reset* in some positions and an attribute in others.

  Two caches, both keyed by the author's literal and both prototype-free
  JS objects, are the whole of the accelerant HD-004 permits: no
  template, no hole plan, no element or props-object memo. Analysis
  (tag parse, prop names, class merge, child realization, head
  classification) is emitter-neutral and kept apart from emission,
  which is React's. The one behaviour emission adds beyond translation
  is the controlled-input converge (`re-frame.hicasso.impl.controlled`),
  installed at the element so the boundary shell spends no hook on it.

  Design record: docs/design/hicasso/architecture.md (the codec's place
  in the arm, the controlled door, memoization);
  docs/design/hicasso/decisions.md HD-004 (caching), HD-011 (the host
  door and `[:>]`), HD-016 (the ABI), HD-023 (the canonical slot),
  HD-025 (the presence keys), HD-028 (the boundary memo); measured in
  docs/design/hicasso/studio/our-walk-against-reagents.md and
  docs/design/hicasso/studio/the-interpreter-walk-profiled-and-cheapened.md."
  (:require [clojure.string :as str]
            [re-frame.hicasso.impl.controlled :as controlled]
            [re-frame.hicasso.impl.error :refer [fail!]]
            [re-frame.hicasso.impl.intent :as intent]
            [re-frame.hicasso.impl.slot :as slot]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]
            ["react" :as react]))

(declare as-element)

;; ---------------------------------------------------------------------------
;; Cache hygiene — the own-property guard both caches share
;; ---------------------------------------------------------------------------

(defn- reserved-name?
  "Is `n` — a tag or prop name, or an emitted prop slot — one of the three
  names that must never be written to a JS object or emitted through
  one: `__proto__`, `prototype`, `constructor`. Asked on the cache MISS
  path only — the caches carry no prototype, so a hostile literal cannot
  make a hit answer wrongly — and of the emitted props object, which does
  (`PropSlot`'s `reserved?`). Three `===` compares rather than a set
  lookup: 9.6 ns against 36.9 on the census page's literals
  (docs/design/hicasso/studio/the-interpreter-walk-profiled-and-cheapened.md)."
  [n]
  (or (identical? "__proto__" n)
      (identical? "prototype" n)
      (identical? "constructor" n)))

(defn- empty-cache
  "A codec cache: a JS object with no prototype at all. Keyed by the
  author's literal, a cache must answer two hostile questions on every
  lookup — can `__proto__` poison a write, can `toString` be served an
  inherited value — and `Object.create(null)` answers the second
  structurally and demotes the first to the miss path, where
  `reserved-name?` refuses the write. Measured against the guarded
  `#js {}` it replaced, and against a type-checked hit that was declined:
  docs/design/hicasso/studio/our-walk-against-reagents.md §4(a)."
  []
  (js/Object.create nil))

;; ---------------------------------------------------------------------------
;; Tag parsing and its cache
;; ---------------------------------------------------------------------------

(def ^:private re-tag
  "`tag`, optional `#id`, optional `.class.class`; the `#id` precedes the
  classes, as in reagent-slim and stock Reagent."
  #"([^\s\.#]+)(?:#([^\s\.#]+))?(?:\.([^\s#]+))?")

(deftype ParsedTag [tag id className])

(defn parse-tag
  "Parse `:div#main.wide.tall` into its tag, id and class string."
  [hiccup-tag]
  (let [[_ tag id classes] (re-matches re-tag (name hiccup-tag))]
    (->ParsedTag tag id (when classes (str/replace classes #"\." " ")))))

(def ^:private tag-cache (empty-cache))

(defn- cache-key [k]
  (if-let [ns' (namespace k)] (str ns' "/" (name k)) (name k)))

(defn cached-parse
  "`parse-tag` behind the codec-work cache (HD-004): one entry per
  distinct tag literal, one property read per hit, the reserved-name
  refusal on the miss branch, which is the only branch that writes."
  [hiccup-tag]
  (let [k   (cache-key hiccup-tag)
        hit (unchecked-get tag-cache k)]
    (if (undefined? hit)
      (let [parsed (parse-tag hiccup-tag)]
        (when-not (reserved-name? k)
          (unchecked-set tag-cache k parsed))
        parsed)
      hit)))

;; ---------------------------------------------------------------------------
;; Prop names and their cache
;; ---------------------------------------------------------------------------

(deftype PropSlot [js-name reserved? event? ref? class?]
  ;; What the prop cache holds for one literal: the React name plus the
  ;; four classifications that are pure functions of the NAME — reserved
  ;; slot, event position, ref slot, class slot — so one lookup answers
  ;; everything a per-prop walk would re-derive per element per render.
  ;; `event?` is the name's answer; the consumer gates it on `keyword?`,
  ;; because a symbol spelled `on-click` shares the entry and is not an
  ;; event position.
  )

(defn- mint-slot
  "The `PropSlot` for a keyword or symbol prop literal whose name is `n`."
  [k n]
  (let [js-name (slot/prop-name k)]
    (->PropSlot js-name
                (reserved-name? js-name)
                (intent/event-prop? n)
                (identical? "ref" js-name)
                (identical? "className" js-name))))

(defn- seed-prop-cache!
  "Pre-warm `cache` with the three React renames and return it. Each slot
  name is ASKED of `re-frame.hicasso.impl.slot/prop-name` rather than
  spelled here, so the seed cannot disagree with the rule; `reset-caches!`
  re-seeds through this same function, so a suite's `:each` fixture
  cannot leave the cache holding a different spelling from a cold build's."
  [cache]
  (doto cache
    (unchecked-set "class" (->PropSlot (slot/prop-name :class) false false false true))
    (unchecked-set "for" (->PropSlot (slot/prop-name :for) false false false false))
    (unchecked-set "charset" (->PropSlot (slot/prop-name :charset) false false false false))))

(def ^:private prop-cache (seed-prop-cache! (empty-cache)))

(defn- prop-slot
  "The `PropSlot` for keyword/symbol `k` with name `n`. One property read
  per hit; a reserved name is minted on every sight and never cached, and
  the slot it mints carries `reserved?` so the emitted props object never
  receives the name either."
  ^PropSlot [k n]
  (let [hit (unchecked-get prop-cache n)]
    (if (undefined? hit)
      (let [s (mint-slot k n)]
        (when-not (reserved-name? n)
          (unchecked-set prop-cache n s))
        s)
      hit)))

(defn cached-prop-name
  "`re-frame.hicasso.impl.slot/prop-name` behind the codec-work cache
  (HD-004). Only a keyword or symbol is cached; a string is answered by
  the rule directly and anything else verbatim. The cache is keyed by
  name while the rule answers a string differently from the keyword of
  the same name (`\"on-input\"` stays, `:on-input` becomes `\"onInput\"`),
  so sharing an entry would let whichever rendered first answer for both
  — an order dependence the owned-literal law exists to remove
  (docs/design/hicasso/decisions.md HD-023 (c′))."
  [k]
  (if-not (or (keyword? k) (symbol? k))
    (if (string? k) (slot/prop-name k) k)
    (let [^PropSlot s (prop-slot k (name k))]
      (.-js-name s))))

;; ---------------------------------------------------------------------------
;; The canonical structural-slot filter
;; ---------------------------------------------------------------------------

(def canonical-slot
  "The one slot resolver: the React prop slot a hiccup attribute key
  emits into, which is `cached-prop-name` itself — the thing a deny asks
  is the thing the emitter will do. Every deny, dissoc and check in this
  codec and in `re-frame.hicasso.impl.presence` asks this, never the key
  as written (docs/design/hicasso/decisions.md HD-023 (c′))."
  cached-prop-name)

(def structural-slots
  "The two React slots that address the ELEMENT rather than its
  attributes — `key`, React's identity contract, and `ref`, HD-016's node
  handle. Held as canonical slots, not keys, because the spelling is what
  a careless map varies; never taken from a presence phase override
  (`re-frame.hicasso.impl.presence/with-phase`)."
  #{"key" "ref"})

(def ^:private ref-slot "ref")

;; The two slots the TAG can write into; the shorthand fold asks the
;; emitted object for them rather than the props map for a key.
(def ^:private id-slot "id")
(def ^:private class-slot "className")

(defn structural-slot?
  "Does `k` — in any spelling — land on `key` or `ref`?"
  [k]
  (contains? structural-slots (canonical-slot k)))

(defn- without-slots
  "`m` minus every key whose canonical slot is in `denied`; `m` itself, by
  identity, when nothing is denied, so a legal map allocates nothing."
  [m denied]
  (reduce-kv (fn [acc k _] (if (denied (canonical-slot k)) (dissoc acc k) acc)) m m))

(defn without-structural
  "`m` with every structural slot removed, in every spelling."
  [m]
  (without-slots m structural-slots))

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

(defn- fold-shorthand!
  "Fold the tag's `#id`/`.class` shorthand into the object the walk just
  emitted, and return it: an explicit id in any spelling wins over `#tag`,
  and `.foo` is prepended to whatever the map emitted into `className`,
  however it was spelled. On the EMITTED object because every spelling
  has already been through `canonical-slot` on its way in, so `id`
  present means an id was written; stated over the props map the rule
  sees three spellings of the keys this codec accepts and lets iteration
  order decide the rest — and it needs the `dissoc`/`assoc` surgery the
  walk profile priced at most of `convert-props`'s cost
  (docs/design/hicasso/decisions.md HD-023 (c″))."
  [^js o ^ParsedTag parsed]
  (when-some [id (.-id parsed)]
    (when (undefined? (unchecked-get o id-slot))
      (unchecked-set o id-slot id)))
  (when-some [shorthand (.-className parsed)]
    (let [declared (unchecked-get o class-slot)]
      (unchecked-set o class-slot
                     (if (undefined? declared)
                       shorthand
                       (class-names shorthand declared)))))
  o)

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
  "A prop value in the shape React wants: a string as itself, a function
  BY IDENTITY (rewrapping would defeat `React.memo` and every
  handler-identity bail-out), a map as a camelCased JS object, a keyword
  or symbol as its `name`, any other collection through `clj->js`.
  `string?` is asked first because a string is the overwhelming prop
  value on a census page, and asked later it would pay the protocol
  checks on its way to `:else`
  (docs/design/hicasso/studio/the-interpreter-walk-profiled-and-cheapened.md)."
  [v]
  (cond
    (string? v)              v
    (fn? v)                  v
    (map? v)                 (nested-map->js v)
    (or (keyword? v) (symbol? v)) (name v)
    (coll? v)                (clj->js v)
    :else                    v))

;; ---------------------------------------------------------------------------
;; `::h/revision` — the controlled element's reset trigger
;; ---------------------------------------------------------------------------

(def revision-key
  "`:re-frame.hicasso/revision` — authoring.md's `::h/revision`, a
  controlled text element's reset trigger. A change to its value (CLJS
  `=`) re-baselines the field to the model without remounting it: node
  kept, focus kept, caret at end-of-model on the commit that carries the
  reset. Resets are by explicit caller revision and never by value
  equality (HD-019's reset law), so a `:value` that changes under an
  unchanged revision continues the draft. Matched as the EXACT keyword
  and never slot-claimed; any other spelling is an ordinary attribute.
  Read off the author's map by `native-element`, never emitted.

  There is no transport to build: the codec mints a fresh props object
  per element per render and React re-commits on props identity, so the
  re-run re-asserts the model against the DOM — which makes HD-004's
  no-props-memo posture a correctness dependency of the reset and not
  only a measurement stance. Spec and argument:
  docs/design/hicasso/studio/revision-prop-spec.md."
  :re-frame.hicasso/revision)

;; ---------------------------------------------------------------------------
;; The presence override keys (HD-025)
;; ---------------------------------------------------------------------------
;;
;; `re-frame.hicasso.impl.presence`'s vocabulary, DEFINED here because this
;; walk has to recognise them and `presence` requires this namespace, not
;; the other way round — one home for each keyword.

(def mounting-key
  "`::motion/mounting` — the attribute overrides applied while a presence
  child is entering."
  :re-frame.hicasso.motion/mounting)

(def unmounting-key
  "`::motion/unmounting` — the attribute overrides applied while a
  presence child is being retained on its way out."
  :re-frame.hicasso.motion/unmounting)

(defn ^boolean override-key?
  "Is `k` one of the two presence override keys? One pointer compare
  each, on the exact keyword — these are hicasso's own private keys, not
  React positions, so a bare `:mounting` is an author's attribute. A
  presence tray strips both off its direct children; anywhere else the
  prop walks skip them, so neither ever reaches the DOM as an attribute."
  [k]
  (or (keyword-identical? mounting-key k)
      (keyword-identical? unmounting-key k)))

(defn- convert-entry
  "One prop into the emitted object — `convert-props`'s reducing function,
  a named var so the walk allocates no closure. The literal `:key`, the
  revision key and the two presence override keys are skipped in-loop:
  four private keys, none a DOM attribute, the revision already read off
  the map by `native-element`. A keyword or symbol key is answered by its
  cached `prop-slot`: the ref slot crosses untouched; the class slot is
  coerced by `class-names` and COMPOSED with whatever the slot already
  holds; a vector, map or fn at an event position (`event?` gated on
  `keyword?`, since a symbol is never an event position) and a marked
  callback anywhere go through `intent/lower-prop`; everything else goes
  straight to `convert-prop-value`. A string key keeps reagent-slim's
  uncached path. A reserved emitted name is never written.

  The class slot composes rather than overwrites because two spellings of
  one element's class are two map keys and one React slot, and
  last-write-wins would drop a class silently — the failure HD-023 exists
  to delete (docs/design/hicasso/decisions.md HD-023 (c′))."
  [o k v]
  (cond
    ;; The four private keys, none a DOM attribute.
    (or (keyword-identical? :key k)
        (keyword-identical? revision-key k)
        (override-key? k))
    o

    (or (keyword? k) (symbol? k))
    (let [^PropSlot s (prop-slot k (name k))]
      (when-not (.-reserved? s)
        (unchecked-set o (.-js-name s)
                       (convert-prop-value
                         (cond
                           (.-ref? s)
                           v

                           (.-class? s)
                           (class-names (unchecked-get o class-slot) v)

                           (and (.-event? s) (keyword? k))
                           (if (or (vector? v) (map? v) (fn? v))
                             (intent/lower-prop k v)
                             v)

                           :else
                           (if (intent/callback? v)
                             (intent/lower-prop k v)
                             v)))))
      o)

    :else
    (let [n (cached-prop-name k)]
      (when-not (reserved-name? n)
        (unchecked-set o n (convert-prop-value
                            (cond
                              (identical? ref-slot n)   v
                              (identical? class-slot n) (class-names (unchecked-get o class-slot) v)
                              :else                     (intent/lower-prop k v)))))
      o)))

(defn convert-props
  "The attribute map as a fresh JS props object: every key emitted under
  its canonical slot with its value converted, every intent lowered inside
  the same single walk, the literal `:key` and the private keys dropped,
  and the tag's `#id.class` shorthand folded LAST onto the emitted object
  (`fold-shorthand!`), so an explicit id wins over `#tag` and `.foo`
  composes with a declared or forwarded class in every spelling. With no
  attribute map the object is built directly from the shorthand. A
  caller's attributes reach this map through an ordinary `merge`, owned
  keys last; the codec asks no provenance question of it.

  One walk and one object per element per render, exactly as the
  measured reagent-slim plumbing does (HD-004 refuses a props-object
  memo). The prop pipeline is 67.5% of the interpreter walk, and the
  shorthand merge was most of that until it was folded onto the emitted
  object instead of the map
  (docs/design/hicasso/studio/the-interpreter-walk-profiled-and-cheapened.md)."
  [props ^ParsedTag parsed]
  (if (nil? props)
    (let [o #js {}]
      (when-some [id (.-id parsed)] (unchecked-set o id-slot id))
      (when-some [c (.-className parsed)] (unchecked-set o class-slot c))
      o)
    (fold-shorthand! (reduce-kv convert-entry #js {} props) parsed)))

;; ---------------------------------------------------------------------------
;; Boundary heads (HD-016 / HD-004)
;; ---------------------------------------------------------------------------

(defn mark-boundary!
  "Record that `f` — a React function component `defview` minted
  — is a legal hiccup head. Returns `f`, so a `defview` can end with it."
  [f]
  (unchecked-set f "hicassoBoundary" true)
  f)

(defn boundary-head?
  "Is `f` a marked boundary? One own-property read; no registry, no map."
  [f]
  (and (fn? f) (true? (unchecked-get f "hicassoBoundary"))))

(def ^:private body-slot "hicassoBody")

(defn retain-body!
  "Dev only. Record the body function a minted head runs ON the head, and
  return the head — one own property, no registry, so the memo contract
  is untouched. The L0–L2 test kit (`re-frame.hicasso.test`) mounts
  nothing and runs no hook, and without this it would have no route from
  a minted head back to the function the author wrote. The one call site
  sits inside `(when ^boolean js/goog.DEBUG …)`, so a production head
  carries nothing; `re-frame.hicasso.view-body-retention-elision-prod-test`
  reads `retained-body` off a head minted in the advanced bundle and
  requires nil."
  [head body-fn]
  (unchecked-set head body-slot body-fn)
  head)

(defn retained-body
  "The body function `retain-body!` recorded, or nil — which is what a
  production head answers, because nothing wrote the slot there.

  One own-property read; the caller decides what nil means. The test kit
  reads it to render a minted head AS WRITTEN, and refuses when it is
  nil."
  [head]
  (unchecked-get head body-slot))

(defn- boundary-props=
  "React's `areEqual` for a memoized boundary: CLJS `=` over the whole
  `rfProps` value — the one slot a boundary's props occupy, minted fresh
  per render so `Object.is` alone would never bail out. Every prop counts,
  function-valued ones comparing conservatively unequal. FAILS OPEN: a
  throwing `-equiv` or a foreign object answers `false` (re-render, with
  a dev warning) rather than escaping React's comparator as a render
  crash, because an extra render is always the safe branch. The frame is
  not compared and need not be: it arrives through React context, which
  React propagates ahead of the comparator and through a memo.

  Stock Reagent's `functional-render-memo-fn` shape exactly; prior-art
  audit and the polarity argument: docs/design/hicasso/decisions.md
  HD-028."
  [^js prev ^js next]
  (try
    (= (unchecked-get prev "rfProps") (unchecked-get next "rfProps"))
    (catch :default _e
      (when ^boolean js/goog.DEBUG
        (when (exists? js/console)
          (.warn js/console
                 (str "[hicasso] boundary props `=` comparison threw; "
                      "re-rendering this boundary (fail-open)."))))
      false)))

(defn memoize-boundary!
  "Give a marked head ONE stable internal `React.memo` wrapper
  (`boundary-props=`) and return the head, still the function it was. The
  wrapper is attached to the head rather than returned because a minted
  head must BE a function and `React.memo` returns an object;
  `boundary-element` creates elements from it (`element-type`). Minted
  once per head, never per element — a fresh wrapper per render is a
  fresh element type, and React remounts the subtree instead of bailing
  out. Opt-in at the mint site, so heads that are not reactive boundaries
  keep the semantics they were written with. Why the bail-out is the
  boundary default: docs/design/hicasso/architecture.md, Memoization;
  docs/design/hicasso/decisions.md HD-028."
  [f]
  (let [memo (react/memo f boundary-props=)]
    (unchecked-set memo "displayName" (unchecked-get f "displayName"))
    (unchecked-set f "hicassoMemo" memo)
    f))

(defn- element-type
  "The React type a boundary head's elements are created from: its stable
  memo wrapper when it has one, and otherwise the head itself. One
  own-property read on a path that already reads one."
  [head]
  (or (unchecked-get head "hicassoMemo") head))

;; ---------------------------------------------------------------------------
;; Host heads (HD-011) — the declared door for a foreign React component
;; ---------------------------------------------------------------------------
;;
;; `defhost` is the door and the only form taught: a VALUE — the foreign
;; component, its options, a name for the crossing — minted once and legal
;; as a hiccup head anywhere. Its `:server` policy is expressed by WHICH
;; TYPE the declaration mints: `:client-only` (the default) mints one gate
;; (`mint-host-gate!`), `:render` mints none and the foreign component is
;; the element's own type, so nothing remounts at adoption. The two
;; policies, why `:fallback` is a sibling option and why a remount is the
;; law: docs/design/hicasso/decisions.md HD-011 (the 2026-08-04, 08-05 and
;; 08-12 addenda) and docs/design/hicasso/defhost-ssr-provider-costing.md.

(def ^:private host-marker "hicassoHost")

(declare host-head?)

(def ^:private callback-contracts
  "The two contracts a `:callbacks` override may name — the two
  `re-frame.hicasso.impl.intent/lower-prop` infers by spelling. The
  migration codemod's `shared_rule_test` reads this set out of the source
  text, so it stays a literal."
  #{:event :render})

(def ^:private host-options
  "Every key a declaration may carry; anything else is refused at the
  declaration, because a door that silently ignored a misspelled
  `:server` would make a policy that was never applied look like a
  setting. `:ssr` is not aliased: pre-alpha, a rename is a rename."
  #{:callbacks :slots :server :fallback})

;; --- The gate -------------------------------------------------------------
;;
;; Module-level functions, not literals at the call site:
;; `useSyncExternalStore` compares the subscribe function by identity and
;; re-subscribes when it changes, and a fresh closure per render would
;; re-subscribe every host on every render. Nothing is subscribed TO —
;; adoption happens once — so the subscribe function's body is the
;; unsubscribe it must return.

(def ^:private gate-no-subscribe (fn [_] (fn [] nil)))
(def ^:private gate-adopted (fn [] true))
(def ^:private gate-unadopted (fn [] false))

(defn ^boolean adopted?
  "THE ADOPTION READ: `false` while React produces server bytes and on
  hydration's first client pass, `true` on every render after the client
  has adopted the markup and on the very first pass of a fresh
  `createRoot` mount, which consults no server snapshot. One hook, legal
  only inside a component's render. The server snapshot and hydration's
  first pass agree BY CONSTRUCTION, so there is no mismatch to reconcile
  and a fresh mount never shows a placeholder it would replace. Read
  through a name because `re-frame.hicasso.impl.portal` is a second
  caller, and the identity-compared triple is what a second copy gets
  subtly wrong."
  []
  (react/useSyncExternalStore gate-no-subscribe gate-adopted gate-unadopted))

;; ---------------------------------------------------------------------------
;; The adoption crossing, observed once — `:rf.ssr/host-adopted`
;; ---------------------------------------------------------------------------
;;
;; `adopted?` is invisible: React swaps the placeholder for the component
;; on its own post-hydration pass and nothing in the instrumentation
;; stream says so. The three vars below emit Spec 009's
;; `:rf.ssr/host-adopted` once per DECLARATION, only for a real crossing
;; (never a fresh mount) and at most once; the row in
;; spec/009-Instrumentation.md is the contract. A render-phase emit is
;; legal HERE and is not a licence generally: the client snapshot is the
;; constant `true`, so a render observing adoption cannot be observing a
;; fact a discarded concurrent render would falsify.

(defn- mint-adoption-crossing
  "The per-declaration crossing cell `mint-host-gate!` closes over, or
  `nil` in a production build, where `interop/debug-enabled?` folds the
  whole crossing away. TWO BOOLEANS, not a keyword cell guarded by
  `identical?`: keyword literals are the same object only when the build
  emits them through a shared constants table, which a dev build does
  not, so such a cell would never transition and the trace would never
  fire — silently, since a trace that does not fire looks like a page with
  no crossing on it."
  []
  (when interop/debug-enabled? #js {:armed false :announced false}))

(defn- note-unadopted!
  "Arm the crossing: this gate has rendered its placeholder, so the next
  adopted render is a genuine transition rather than a fresh mount."
  [crossing]
  (when crossing
    (unchecked-set crossing "armed" true)))

(defn- announce-adoption!
  "One `:info` trace the first time an ARMED gate renders adopted; disarms
  before emitting, so at most once per declaration. `:info` because
  nothing is wrong — this is Client-only reporting that it completed."
  [host-name crossing]
  (when (and crossing
             (unchecked-get crossing "armed")
             (not (unchecked-get crossing "announced")))
    (unchecked-set crossing "announced" true)
    (trace/emit! :info :rf.ssr/host-adopted
                 {:host  host-name
                  :where 're-frame.hicasso.impl.codec/mint-host-gate!})))

(defn- deferring-head-kind
  "Which DEFERRING head `x` is — the door that minted it, named the way
  an author wrote it — or `nil` if it is not one.

  A deferring head is one whose body does not run when its element is
  created: `defview`'s product runs inside its own boundary's
  `with-frame`, and `defhost`'s runs behind its own gate. Those are
  exactly the two heads a hiccup walk cannot see through, which is why
  they are the two heads a fallback may not contain."
  [x]
  (cond
    (boundary-head? x) "defview"
    (host-head? x)     "defhost"
    :else              nil))

(defn- head-name
  "The `displayName` both mints stamp on their product, for a message
  that has to name the offending head. Never assumed present."
  [x]
  (or (unchecked-get x "displayName") "<unnamed>"))

(defn- refuse-deferring-heads-in-fallback!
  "Walk a declared `:fallback` STRUCTURALLY and refuse a `defview` or
  `defhost` head at any position
  (`:rf.error/hicasso-host-fallback-boundary-head`, naming the host, the
  head and `path` — the index route into the form: `[]` the fallback
  itself, `[0]` its head, `[2 0]` the head of its third element). Asks
  the marker, never the mint, so it holds for every head the mint door
  produces.

  Structural because a deferring head is exactly what evaluation cannot
  see: `as-element` refuses what it evaluates (an intent, a `sub` call)
  and never looks inside a head whose body runs later, so left to the
  walk a declared placeholder could render a different document per
  frame and per write. `:server :render` is the honest recovery for a
  provider. Ruling and the two measurements behind it:
  docs/design/hicasso/decisions.md HD-011, \"The fallback half\";
  witness `re-frame.hicasso.fallback-contents-cljs-test`."
  [host-name path form]
  (if-some [kind (deferring-head-kind form)]
    (fail! :rf.error/hicasso-host-fallback-boundary-head
           're-frame.hicasso.impl.codec/mint-host!
           (str "defhost " host-name " declares a :fallback carrying the "
                kind " head " (head-name form) " at position " (pr-str path)
                ". A fallback is INERT MARKUP: it is walked into ONE element "
                "at the declaration, outside any frame, and that element is "
                "reused at every site of the host — so a head whose body runs "
                "later makes one declared placeholder render a different "
                "document per frame and per write, which is not a "
                "placeholder. Write plain hiccup there, or declare "
                ":server :render and render the real subtree on the server.")
           {:host host-name :head (head-name form) :position path :kind kind})
    (cond
      (vector? form)
      (dotimes [i (count form)]
        (refuse-deferring-heads-in-fallback! host-name (conj path i) (nth form i)))

      (seq? form)
      (loop [i 0 s (seq form)]
        (when s
          (refuse-deferring-heads-in-fallback! host-name (conj path i) (first s))
          (recur (inc i) (next s))))

      :else nil)))

(defn- mint-host-gate!
  "The one component a GATED declaration mints: the foreign component
  behind its `:server :client-only` policy, with or without a declared
  `:fallback`. The fallback is walked into an element HERE, at the
  declaration — where every other host refusal fires, so a fallback that
  is not hiccup fails with the author's own stack — once, and reused at
  every site, because a placeholder that differs per site is not a
  placeholder; `refuse-deferring-heads-in-fallback!` enforces that
  structurally first. The gate hands its own props straight through, so
  the crossing's props object is exactly the one `host-element` built
  (`ref` included, an ordinary prop in React 19); `:key` never reaches
  here."
  [host-name component fallback]
  (when (some? fallback)
    (refuse-deferring-heads-in-fallback! host-name [] fallback))
  (let [placeholder (when (some? fallback) (as-element fallback))
        crossing    (mint-adoption-crossing)
        gate        (fn [props]
                      (if (adopted?)
                        (do (announce-adoption! host-name crossing)
                            (react/createElement component props))
                        (do (note-unadopted! crossing)
                            placeholder)))]
    (unchecked-set gate "displayName" host-name)
    gate))

(defn- refuse-server-policy!
  "The one server-policy refusal (`:rf.error/hicasso-host-bad-ssr-policy`)
  for a `:server` value outside the two and for a `:fallback` that cannot
  belong to the policy beside it — one id because they are one fault: the
  declaration names no policy the door can honour. `why` completes the
  sentence *\"defhost NAME …\"*."
  [host-name why data]
  (fail! :rf.error/hicasso-host-bad-ssr-policy
         're-frame.hicasso.impl.codec/mint-host!
         (str "defhost " host-name " " why " There are TWO policies: "
              ":server :client-only — the default, meaning the host region "
              "renders nothing until the client adopts it — and "
              ":server :render, meaning the component itself is safe to run "
              "on the server and does. :fallback is the sibling option, "
              "hiccup that renders in the host's place until adoption, and "
              "it belongs to Client-only alone: under :render the component "
              "renders, so there is nothing for a placeholder to stand in "
              "for.")
         (assoc data :host host-name)))

(defn- declared-server
  "The `:server` policy this declaration carries, validated: `:client-only`
  (the default when absent) or `:render`, the author's assertion that the
  component is safe to render on the server. Spellings that assert a
  structural property nobody can check — `:children`, `:transparent` —
  stay refused."
  [host-name opts]
  (let [policy (get opts :server :client-only)]
    (if (or (keyword-identical? :client-only policy)
            (keyword-identical? :render policy))
      policy
      (refuse-server-policy! host-name
        (str "declares :server " (pr-str policy) ", which is neither.")
        {:server policy}))))

(defn- declared-fallback
  "The hiccup at `:fallback`, or nil when the key is absent. A sibling
  option, not a policy value, so the policy stays a displayable enum.
  `contains?` and not `if-some`: an explicit nil is a VALUE and the
  default belongs to an absent key, so `{:fallback nil}` is refused rather
  than read as a placeholder that renders nothing."
  [host-name opts server]
  (when (contains? opts :fallback)
    (let [fallback (:fallback opts)]
      (when (keyword-identical? :render server)
        (refuse-server-policy! host-name
          "declares :fallback beside :server :render."
          {:server server :fallback fallback}))
      (when (nil? fallback)
        (refuse-server-policy! host-name
          (str "declares :fallback nil, and an explicit nil is a value "
               "rather than an absence.")
          {:server server :fallback nil}))
      fallback)))

;; --- The declared ReactNode positions ------------------------
;;
;; A host prop is DATA and converts shallowly; a modal's `title` or
;; `Suspense`'s `fallback` is a MARKUP position, and hiccup there would
;; cross as a nested array and render nothing. `:slots` names those
;; positions, and hiccup at one is lowered by `as-element` under the
;; writing boundary's render window. Only the author knows which props
;; are markup, so nothing is inferred. docs/design/hicasso/decisions.md
;; HD-024, 2026-08-11 addendum.

(defn- slot-key-name
  "The prop name a `:slots` entry spells, or nil when the entry cannot
  name a prop at all. Keywords, symbols and strings are the three
  spellings `cached-prop-name` accepts; a number, a vector or a nested
  set is not a prop position and is refused rather than normalised into
  some slot nobody wrote."
  [k]
  (when (or (keyword? k) (symbol? k) (string? k))
    (cached-prop-name k)))

(defn- refuse-bad-slots!
  "The `:slots` arm of the one declaration refusal. `why` completes the
  sentence *\"defhost NAME declares :slots …\"*, and the ex-data carries
  the whole set beside the offending entry, because a malformed set is
  read by looking at what else is in it."
  [host-name slots why data]
  (fail! :rf.error/hicasso-bad-host-declaration
         're-frame.hicasso.impl.codec/mint-host!
         (str "defhost " host-name " declares :slots " (pr-str slots) ", and "
              why " :slots is a SET of ordinary prop names — the positions "
              "where the foreign component takes markup rather than data, "
              "such as a modal's title or a Suspense fallback — and hiccup "
              "written at one of them is lowered under the frame that wrote "
              "the crossing. Every other prop stays data.")
         (assoc data :host host-name :slots slots)))

(defn- declared-slots
  "The canonical slots this declaration names as ReactNode positions, as
  a set of emitted slot names — or the empty set, which is what a
  declaration that says nothing gets and what the `[:>]` escape has
  forever.

  Normalised at MINT, like `:callbacks`, so `{:slots #{:on-empty}}` and a
  call site writing `:onEmpty` name the one position and the lookup the
  crossing performs per prop is one `contains?`.

  `callbacks` is the already-normalised contract map, and the collision
  against it is checked HERE rather than in either walk because the two
  declarations are read together exactly once. A slot that is also a
  declared callback is not a resolvable position: `:render` invokes the
  value and a slot lowers it, and no order of those two is the one the
  author meant."
  [host-name opts callbacks]
  ;; `contains?` and not `if-some`: an explicit `nil` is a VALUE, and the
  ;; default belongs to an ABSENT key. `:fallback` draws the line in the
  ;; same place and for the same reason — inferring the default from a nil is
  ;; how a typo becomes a setting.
  (if (contains? opts :slots)
    (let [slots (:slots opts)]
      (when-not (set? slots)
        (refuse-bad-slots! host-name slots "that is not a set." {}))
      (reduce
        (fn [acc k]
          (let [slot (slot-key-name k)]
            (when (nil? slot)
              (refuse-bad-slots! host-name slots
                (str (pr-str k) " does not name a prop.") {:position k}))
            (when (structural-slot? k)
              (refuse-bad-slots! host-name slots
                (str (pr-str k) " is a structural slot: `key` is React's "
                     "identity contract and `ref` is HD-016's node handle, "
                     "and neither carries markup.")
                {:position k}))
            (when (contains? acc slot)
              (refuse-bad-slots! host-name slots
                (str "two spellings land on the one slot " (pr-str slot)
                     " — declare it once.")
                {:position k :slot slot}))
            (when (contains? callbacks slot)
              (refuse-bad-slots! host-name slots
                (str (pr-str k) " is also declared in :callbacks, where it "
                     "carries the " (pr-str (get callbacks slot)) " contract. "
                     "A position is a callback or it is markup; it cannot be "
                     "both, and nothing decides which the value meant.")
                {:position k :slot slot :contract (get callbacks slot)}))
            (conj acc slot)))
        #{}
        slots))
    #{}))

(defn mint-host!
  "THE ONE-LINE DECLARATION (HD-011): returns the host HEAD for
  `component` — a marked carrier legal in hiccup head position, rendered
  by `vec->element`'s fourth branch.

  `component` is anything React accepts as an element type; `nil` is
  refused here because it is the broken-import symptom (`:default`
  against a library with no default export). `opts` is a map of
  `:callbacks` — an optional override from prop name to `:event` or
  `:render`, for a prop whose spelling infers the wrong contract —
  `:slots`, the set of ReactNode positions (`declared-slots`),
  `:server` (`:client-only` by default, or `:render`) and `:fallback`,
  Client-only's placeholder markup. Callback and slot names are
  normalised to their canonical slot at mint, so the crossing's per-prop
  lookup is one `get` and one `contains?`. Refused at the declaration: a
  `nil` component (`:rf.error/hicasso-host-no-component`); as
  `:rf.error/hicasso-bad-host-declaration`, with the fault named in the
  reason, a non-map `opts`, an option outside the four, a contract
  outside the two, a malformed `:slots` set and a position that is both a
  slot and a callback; a `:server` value outside the two or a `:fallback`
  beside `:render` (`:rf.error/hicasso-host-bad-ssr-policy`); and a
  boundary head inside a fallback
  (`:rf.error/hicasso-host-fallback-boundary-head`).

  The head's `gate` slot is the React TYPE every crossing is created
  from, and the `:server` policy is expressed by choosing it: under
  `:client-only` it is `mint-host-gate!`'s product (one fiber, one
  hook); under `:render` it is the foreign component itself, so server,
  hydration and fresh mount render one tree and nothing remounts at
  adoption. Argument in docs/design/hicasso/decisions.md, HD-011."
  ([host-name component] (mint-host! host-name component {}))
  ([host-name component opts]
   (when (nil? component)
     (fail! :rf.error/hicasso-host-no-component
            're-frame.hicasso.impl.codec/mint-host!
            (str "defhost " host-name " was given nil as its component. The "
                 "usual cause is a JS import that resolved nothing — e.g. "
                 "`:default` against a library with no default export.")
            {:host host-name}))
   ;; The shape before the roster, so a non-map never reaches `keys`;
   ;; `nil` is *no options*, which is what the two-arity call means.
   (when-not (or (nil? opts) (map? opts))
     (fail! :rf.error/hicasso-bad-host-declaration
            're-frame.hicasso.impl.codec/mint-host!
            (str "defhost " host-name " was given " (pr-str opts) " as its "
                 "options, and a declaration's options are a MAP of "
                 ":callbacks, :slots, :server and :fallback. The commonest way "
                 "to arrive here is a docstring written AFTER the component "
                 "instead of before it, which leaves the real options map as "
                 "a trailing form nothing reads.")
            {:host host-name :options opts}))
   (doseq [k (keys opts)]
     (when-not (contains? host-options k)
       (fail! :rf.error/hicasso-bad-host-declaration
              're-frame.hicasso.impl.codec/mint-host!
              (str "defhost " host-name " was declared with " (pr-str k)
                   ", which is not an option. A declaration carries "
                   ":callbacks, :slots, :server and :fallback. Reading past an "
                   "option it does not know is how a policy comes to be set "
                   "and never applied.")
              {:host host-name :option k :options host-options})))
   (let [server   (declared-server host-name opts)
         fallback (declared-fallback host-name opts server)
         declared
         (reduce-kv
           (fn [m k contract]
             (when-not (contains? callback-contracts contract)
               (fail! :rf.error/hicasso-bad-host-declaration
                      're-frame.hicasso.impl.codec/mint-host!
                      (str "defhost " host-name " declares " (pr-str k) " with "
                           "the callback contract " (pr-str contract)
                           ". The contracts are :event and :render, and a "
                           "declaration only needs one where the prop's spelling "
                           "infers the wrong one — an on*-named render prop.")
                      {:host host-name :position k :contract contract}))
             (assoc m (cached-prop-name k) contract))
           {}
           (or (:callbacks opts) {}))
         ;; After `declared`, because a slot that is also a declared
         ;; callback is refused and the contract map is what says so.
         slots (declared-slots host-name opts declared)
         ;; The policy IS the type. `:render` mints no gate at all —
         ;; the foreign component is the element's own type, so the
         ;; server render, hydration's first pass and a fresh mount are
         ;; one tree and there is nothing to swap at adoption.
         ^js head #js {"component"   component
                       "gate"        (if (keyword-identical? :render server)
                                       component
                                       (mint-host-gate! host-name component
                                                        fallback))
                       "callbacks"   declared
                       "slots"       slots
                       "server"      server
                       "displayName" host-name}]
     (unchecked-set head host-marker true)
     head)))

(defn refuse-host-extra-forms!
  "Refuse a `defhost` FORM that carries anything after its options map,
  naming the forms that would have been discarded. The macro destructures
  `[component opts]` off a variadic tail, so unrefused, a second options
  map is read by nothing and the markup declared in it never arrives.
  Raised at namespace LOAD inside the declaration extent rather than at
  expansion, so it carries the declaration's file and line like every
  other `defhost` refusal, and so a CLJS suite can witness it — a throw
  at expansion stops the build compiling."
  [host-name extra]
  (fail! :rf.error/hicasso-bad-host-declaration
         're-frame.hicasso/defhost
         (str "defhost " host-name " was written with " (count extra)
              " form(s) after its options map, and nothing reads them: "
              (pr-str (vec extra)) ". A declaration is (defhost name "
              "component) or (defhost name component opts), each with an "
              "optional docstring in SECOND position — before the component, "
              "never after it. Two options maps are not merged.")
         {:host host-name :extra (vec extra)}))

(defn host-head?
  "Is `v` a minted host head? One own-property read behind a nil guard;
  no registry, no map — the same shape as `boundary-head?`."
  [v]
  (and (some? v) (true? (unchecked-get v host-marker))))

(defn host-server
  "The `:server` policy `head` was declared with — `:client-only` or
  `:render` — read back as data for a server walk and the witnesses.
  Nothing on the render path reads it: the policy is enforced by which
  type the declaration minted. The fallback is not read back; it is
  markup the gate already closed over."
  [^js head]
  (unchecked-get head "server"))

;; ---------------------------------------------------------------------------
;; Hiccup shape
;; ---------------------------------------------------------------------------

(defn- props-map?
  "Slot `i` of a hiccup vector is the props map when it is a map. A seq
  there is a child, as is a string, as is another hiccup vector."
  [argv i]
  (map? (nth argv i nil)))

;; ---------------------------------------------------------------------------
;; The entity-key warning — DEVELOPMENT ONLY
;; ---------------------------------------------------------------------------
;;
;; React warns about an unkeyed list; it is silent on a CONTENT-DERIVED
;; key — a map, a date, a JS object — which coerces to a string per
;; member and remounts the row the moment the author edits the entity.
;; Why this warning rather than `for`-lowering sugar:
;; docs/design/hicasso/decisions.md HD-016.

(def ^:private keywarn
  "The sites that have already warned: a `Map` of member head -> the kinds
  reported for it; `nil` in production, where every reader sits behind
  `goog.DEBUG` and the whole thing folds away. A plain `def`, so a page
  reload resets it — React's own semantics for the same dedupe. Keyed on
  the head object rather than a joined site string, because an unfixed
  site is re-encountered on every render and a string per member per
  render is what the lookup would cost."
  (when ^boolean js/goog.DEBUG (js/Map.)))

(defn- ^boolean plain-key?
  "Is this `:key` value one React coerces to a stable string without
  reading anything the author will edit? Asked first of every member of
  every seq in a dev build, so three `typeof`-class tests and nothing
  dearer — not `coll?`, the dearest predicate on the path."
  [k]
  (or (string? k) (number? k) (keyword? k)))

(defn- ^boolean stable-object-key?
  "The two non-primitive `:key` values classified SAFE: a `uuid` and a
  `symbol` are objects, but each string-coerces to its own name, which is
  the identity the author means. A `uuid` is the canonical entity id, and
  warning on `{:key (:id entity)}` would be the false positive that
  teaches authors to ignore the warning. Asked only on detection, never
  on the keyed walk."
  [k]
  (or (uuid? k) (symbol? k)))

(defn- key-shape
  "What the author put at `:key`, named rather than printed — the VALUE
  never reaches the console, because a foreign or cyclic value would blow
  `pr-str` inside a diagnostic. Total over everything `check-member-key!`
  rejects, so no arm falls through to the value; `coll?` sits here
  because this runs on detection, not on the walk."
  [k]
  (cond (map? k)     "a map"
        (vector? k)  "a vector"
        (set? k)     "a set"
        (coll? k)    "a collection"
        (boolean? k) "a boolean"
        (fn? k)      "a function"
        :else        "a foreign object"))

(defn- warn-entity-key!
  "One console line per site, where a site is *(member head, which
  hazard)*. Built only on detection, never on the render path."
  [head kind i]
  (let [kinds (or (.get keywarn head)
                  (let [o #js {}] (.set keywarn head o) o))]
    (when-not (unchecked-get kinds kind)
      (unchecked-set kinds kind true)
      (when (exists? js/console)
        (.warn js/console
               (str "[hicasso] Entity-valued :key on boundary children: a seq of "
                    (head-name head) " members carries " kind
                    " at :key (first at index " i ")."
                    " React coerces a key to a string, so a value like this"
                    " keys the child by its CONTENT — edit the entity and"
                    " the child silently remounts, losing focus, scroll"
                    " position and any presence retention. A foreign object"
                    " is the sharper case: every one of them coerces to the"
                    " same `[object Object]`, so distinct children collapse"
                    " onto a single key. Key on a stable identifier instead"
                    " — [child {:key (:id entity), …}]. Warned once per"
                    " site, in development builds only."
                    " [:rf.warning/hicasso-entity-key]")))))
  nil)

(defn- check-member-key!
  "One member of a lowered child seq, at index `i`: warn when it is a
  boundary-headed vector whose `:key` React cannot coerce to a stable
  identity (an absent key is React's own warning). The classification is
  TOTAL — every non-nil value `plain-key?` rejects is either safe by
  `stable-object-key?` or named by `key-shape`. Predicate order is the
  cost: `vector?`, the `:key` read, then `plain-key?`, where a keyed
  member leaves on a `typeof`; the classification runs only past that. A
  seq member is not a vector, and its own expansion checks its members."
  [m i]
  (when (vector? m)
    (let [p (nth m 1 nil)
          k (when (map? p) (:key p))]
      (when (and (some? k) (not (plain-key? k)))
        (let [h (nth m 0 nil)]
          (when (and (boundary-head? h) (not (stable-object-key? k)))
            (warn-entity-key! h (key-shape k) i))))))
  nil)

(defn- check-seq-keys!
  "`check-member-key!` over a whole seq, for `realize-children`'s
  one-level flatten — the crossing INTO a boundary, where `into` walks the
  seq and there is no loop of its own to ride. The rare path."
  [s]
  (loop [items (seq s)
         i     0]
    (when items
      (check-member-key! (first items) i)
      (recur (next items) (inc i))))
  nil)

(defn realize-children
  "The trailing forms of `argv` from `first-child`, realized once into a
  vector and flattened exactly one level — a nested seq splices, a nested
  vector is hiccup and does not. Nil when there are none, so
  `(:children props)` is absent rather than empty. The dev-only
  `check-seq-keys!` in the `seq?` branch is the one place the crossing
  shape is visible: the flatten turns a seq's members into direct
  arguments, which React marks validated and never warns about."
  [argv first-child]
  (when (< first-child (count argv))
    (let [flat (reduce (fn [acc c]
                         (if (seq? c)
                           (do (when ^boolean js/goog.DEBUG (check-seq-keys! c))
                               (into acc c))
                           (conj acc c)))
                       []
                       (subvec argv first-child))]
      (when (seq flat) flat))))

(declare realize-deep)

;; The two reducing functions are named vars rather than literals at the
;; reduce sites, so the walk allocates nothing per collection visited.
;; A map entry is TWO reachable positions: a `delay` hashes by object
;; identity and an array map compares keys with `=`, so neither hashing
;; nor construction realises a key — the key half goes through the same
;; walk. The `keyword?` short-circuit skips a provable no-op (a Keyword is
;; neither a collection nor a Delay) because proving it costs `coll?`,
;; the dearest predicate on the path; see `realize-deep`.
(defn- realize-entry [_ k x]
  (when-not (keyword? k) (realize-deep k))
  (realize-deep x)
  nil)
(defn- realize-item  [_ x]   (realize-deep x) nil)

(defn- refuse-deferred!
  "Refuse an unforced `delay` at the boundary crossing
  (`:rf.error/hicasso-deferred-read-at-boundary`). A `delay` is the
  author's statement that a computation happens later, so the walk may
  not force it; and the refusal is raised inside the render of the body
  that wrote it, because `realize-deep` runs at the crossing, so the
  stack names the author's call site rather than the child."
  [v]
  (fail! :rf.error/hicasso-deferred-read-at-boundary
         're-frame.hicasso.impl.codec/boundary-element
         (str "An unforced `delay` reached a boundary's props. It would be "
              "forced inside the CHILD's render, so any subscription it reads "
              "becomes the child's edge, is cached by the delay, and is then "
              "dropped the next time the child renders — a value correct on "
              "screen, frozen thereafter, and attributable to nothing. Hicasso "
              "will not force it for you: that would change what your `delay` "
              "means. Hand a FUNCTION instead — the child calls it on every "
              "render, so its reads are the child's edges and are kept — or "
              "deref the delay in the body that wrote it.")
         {:value v}))

(defn realize-deep
  "Force every lazy sequence reachable from `v`, refuse any unforced
  `delay` reachable from it (`refuse-deferred!`), and return `v` itself
  by identity — realising a `LazySeq` caches into the seq, so nothing is
  rebuilt or copied. Descends into collections only, both halves of a
  map entry included; a mutable reference (an atom, a var) is not
  descended into, which is a declared limit. A seq of unbounded length
  diverges here rather than in the child, as `clj->js` already makes one
  do at a native prop position.

  Run once at the boundary hand-off (`boundary-element`), the one
  position the eager codec's walk did not reach: a lazy seq that
  crossed unrealised would be forced inside the child's render,
  attributed to the child, and — because a `LazySeq` caches — frozen
  after the child's first re-render. A lazy seq is structure and may be
  forced; a `delay` is an explicit deferral and may not. Cost: 6% of the
  dogfood row's element build, 89% at a 100-row collection prop and
  still 4.7x cheaper than that collection's `clj->js` at a native prop;
  walking keys unconditionally added 51–67% to the walk, the `keyword?`
  short-circuit 0.2–2.8% of the element build
  (docs/design/hicasso/studio/the-boundary-crossing-walk-priced.md).
  Argument: docs/design/hicasso/studio/arm1-lean-react-dogfood-judgement.md."
  [v]
  (if-not (coll? v)
    (if (and (delay? v) (not (realized? v)))
      (refuse-deferred! v)
      v)
    (do (if (map? v)
          (reduce-kv realize-entry nil v)
          (reduce realize-item nil v))
        v)))

;; ---------------------------------------------------------------------------
;; Emission — React elements
;; ---------------------------------------------------------------------------

(defn- expand-seq
  "A seq of children as a JS array of elements, one pass driven by the
  seq's exhaustion rather than each child's truthiness, so an interior
  `nil` does not truncate the list. The dev-only `check-member-key!`
  rides this loop and reports `(.-length a)` as the index rather than a
  threaded counter: one element is pushed per member, so the length IS
  the index, and under `:advanced` the whole line folds away leaving the
  loop character for character as it was — an increment per child on the
  production path for a dev message's benefit is the edit this shape
  refuses."
  [s]
  (let [a #js []]
    (loop [items (seq s)]
      (when items
        (when ^boolean js/goog.DEBUG (check-member-key! (first items) (.-length a)))
        (.push a (as-element (first items)))
        (recur (next items))))
    a))

(defn- make-element
  "`createElement` with `reagent2.impl.template`'s three arms: no
  children, one child,
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

(defn- native-element
  "One native tag as a React element. `controlled/install!` runs on the
  converted props — the condition it tests (a controlled `value`, a
  change handler, a type with a caret) is about the EMITTED element, so
  canonical slots rather than spellings — and answers what to render the
  props as: the tag, or for a controlled `input`/`textarea` the
  composition shadow's component. The revision key is read off the
  author's map with the same shape as the `:key` read, both triggers
  rather than attributes; what lands on the emitted object is a marker
  under a private slot that `install!` deletes as it reads it, so nothing
  named `revision` reaches React, the DOM or the server bytes."
  [argv]
  (let [parsed      (cached-parse (nth argv 0))
        has-props?  (props-map? argv 1)
        props       (if has-props? (nth argv 1) nil)
        ;; nil, not `(or props {})`: the absent map is `convert-props`'s
        ;; direct lane.
        js-props    (convert-props props parsed)
        _           (when-some [r (get props revision-key)]
                      (unchecked-set js-props controlled/revision-slot r))
        component   (controlled/install! (.-tag parsed) js-props)]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (make-element component js-props argv (if has-props? 2 1))))

(defn- boundary-element [argv]
  (let [has-props? (props-map? argv 1)
        props      (if has-props? (nth argv 1) {})
        children   (realize-children argv (if has-props? 2 1))
        ;; THE HAND-OFF: the map crosses as a CLJS value, so this is the
        ;; one position the eager walk did not reach; `realize-deep`
        ;; forces every lazy seq (`:children` included) and refuses an
        ;; unforced `delay`, inside THIS body's render.
        body-props (realize-deep (cond-> (dissoc props :key)
                                   children (assoc :children children)))
        head       (nth argv 0)
        js-props   #js {"rfProps" body-props}]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (react/createElement (element-type head) js-props)))

(def ^:private html-attr-slots
  "The emitted slots at a foreign crossing whose value is bound for an
  HTML attribute wherever the component passes it on, so has no
  representation but a string; `data-*` and `aria-*` join them by prefix.
  Named as SLOTS, not keys, so `:class`, `:className` and `\"class\"` are
  one position. The roster is the one reagent-slim narrowed this seam to
  (implementation/adapters/reagent-slim/IMPL-SPEC.md §7.2)."
  #{"className" "id" "role"})

(defn- ^boolean html-attr-slot?
  "Is `slot` an HTML-attribute position? A non-string slot never is."
  [slot]
  (and (string? slot)
       (or (contains? html-attr-slots slot)
           (str/starts-with? slot "data-")
           (str/starts-with? slot "aria-"))))

(defn host-prop-value
  "A host prop value converted SHALLOWLY for the emitted `slot` — HD-011's
  default. A function crosses by identity, so `React.memo` and every
  handler-identity bail-out keep working; a collection through `clj->js`,
  its nested map keys spelled as the author wrote them; a keyword or
  symbol BY IDENTITY, except at an HTML-attribute slot (`html-attr-slot?`)
  where it takes `(name v)`, the native walk's answer at the same names;
  anything else as it stands. The caller (`host-entry`) camelCases the
  top-level KEY; nothing inside the value is renamed. `className` never
  arrives — the class slot takes `class-names` ahead of this function —
  and stays on the roster so the answer is right if asked directly.

  Stock Reagent's `(name v)` at every host prop is deliberately not
  taken: it hands `:theme/dark` and `:other/dark` to a provider as one
  string, silently, at the crossing where a namespaced identity is most
  often the point. Ruling: docs/design/hicasso/decisions.md HD-011,
  2026-08-30 addendum."
  [slot v]
  (cond
    (fn? v)                       v
    (or (keyword? v) (symbol? v)) (if (html-attr-slot? slot) (name v) v)
    (coll? v)                     (clj->js v)
    :else                         v))

(defn- refuse-unclaimed-host-callback!
  "An `h/event` at a position the declaration named a ReactNode slot. The
  slot is claimed for MARKUP — it lowers hiccup (`as-element`) — so it
  has no contract to give a function, and a function crossing as a
  ReactNode renders nothing, silently. A PLAIN function is untouched; the
  marked form asked for a contract, and this refuses the unanswered
  request. Argument in docs/design/hicasso/decisions.md, HD-024's
  2026-08-11 addendum."
  [^js head k]
  (fail! :rf.error/hicasso-host-unclaimed-callback
         're-frame.hicasso.impl.codec/host-element
         (str "The host " (unchecked-get head "displayName") " was handed an "
              "h/event at " (pr-str k) ", which its declaration names a "
              "ReactNode slot — a markup position that lowers hiccup and has "
              "no callback contract to give a function. Write the markup "
              "there, or take " (pr-str k) " out of :slots.")
         {:host     (unchecked-get head "displayName")
          :position k
          :slots    (unchecked-get head "slots")}))

(defn- host-entry
  "One host prop into the emitted object — `host-element`'s reducing
  function, `convert-entry`'s sibling at the second prop door: `:key` and
  the presence overrides skipped in-loop, a keyword or symbol key
  answered by its cached `prop-slot`, a reserved emitted slot never
  written. Arms in precedence order: `:ref` crosses untouched; a
  `:callbacks` override applies its declared contract
  (`intent/lower-declared-prop`); a declared `:slots` position lowers
  markup through `as-element` and refuses the marked form; the class slot
  takes `class-names`; everything else is inferred from the spelling by
  `intent/lower-prop` and converted shallowly by `host-prop-value`. The
  reserved skip sits ABOVE every declaration arm, so no declaration can
  talk the codec into poisoning the prototype.
  docs/design/hicasso/decisions.md HD-011, 2026-08-29 addendum."
  [^js head declared slots o k v]
  (cond
    ;; `:key` is React's, and a presence override belongs to a tray —
    ;; neither is a prop the component should see (`convert-entry`).
    (or (keyword-identical? :key k) (override-key? k))
    o

    :else
    (let [keyword-ish? (or (keyword? k) (symbol? k))
          ^PropSlot s  (when keyword-ish? (prop-slot k (name k)))
          slot         (if keyword-ish? (.-js-name s) (cached-prop-name k))
          reserved?    (if keyword-ish? (.-reserved? s) (reserved-name? slot))
          ref?         (if keyword-ish? (.-ref? s) (= ref-slot slot))
          class?       (if keyword-ish? (.-class? s) (identical? class-slot slot))]
      (when-not reserved?
        (unchecked-set o slot
          (cond
            ref?
            v

            ;; The override outranks the spelling. Through
            ;; `host-prop-value` afterwards, so a value the contract did
            ;; not claim — a vector at `:render` — crosses as data
            ;; exactly as it would at an inferred position.
            (contains? declared slot)
            (host-prop-value slot (intent/lower-declared-prop k v (get declared slot)))

            (contains? slots slot)
            (do (when (intent/callback? v)
                  (refuse-unclaimed-host-callback! head k))
                (as-element v))

            class?
            (class-names (unchecked-get o class-slot) v)

            ;; The SLOT, never the key, for the conversion: `:class` and
            ;; `:className` are one position, and the named-value rule is
            ;; written against where the value lands. The KEY for the
            ;; classifier, because the spelling is what it reads.
            :else
            (host-prop-value slot (intent/lower-prop k v)))))
      o)))

(defn- host-element
  "One declared foreign component as a React element — THE CROSSING
  (HD-011): one pass over the attr map through `host-entry`, `:key`
  extracted onto the element, children lowered hiccup→element in the same
  render window as the props, so every callback, slot and child closes
  over the frame of the boundary that wrote the crossing. The element's
  TYPE is the head's `gate` slot, where the `:server` policy lives
  (`mint-host!`); a gate is not a boundary, so HD-020's hook budget is
  untouched. docs/design/hicasso/decisions.md HD-011."
  [argv]
  (let [^js head   (nth argv 0)
        declared   (unchecked-get head "callbacks")
        slots      (unchecked-get head "slots")
        has-props? (props-map? argv 1)
        props      (if has-props? (nth argv 1) {})
        js-props   (reduce-kv (partial host-entry head declared slots) #js {} props)]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (make-element (unchecked-get head "gate") js-props argv (if has-props? 2 1))))

;; ---------------------------------------------------------------------------
;; The `[:>]` raw escape (HD-011) — the door with the declaration erased
;; ---------------------------------------------------------------------------
;;
;; `[:> Component props & children]` is `defhost` with the declaration
;; erased, and what erasing it costs is exactly what the declaration
;; carried: no crossing name, no `:callbacks`, no `:server` policy (hard
;; `:client-only` through one shared gate), refusals at render time, one
;; generic marker. The props walk is `host-entry` against an empty
;; declared roster, which is what makes `[:> X …]` -> `(defhost x X {})`
;; a behaviour-preserving rewrite. Design record:
;; docs/design/hicasso/decisions.md HD-011 (2026-08-07 addendum) and
;; docs/design/hicasso/studio/raw-escape-spec.md.

(def ^:private raw-crossing
  "What `host-entry` reads at a `[:>]` prop in place of a declaration: a
  `displayName` for its refusal messages and EMPTY rosters — one
  module-level value, nothing minted per site. `callbacks` is a CLJS map
  because the refusals project it into their ex-data; `slots` is empty
  because a ReactNode position is DECLARED, so hiccup at a `[:>]` prop is
  data and `h/as-element` is the per-site spelling that crosses one."
  #js {"displayName" "[:>]"
       "callbacks"   {}
       "slots"       #{}})

(def ^:private raw-gate
  "THE ONE GATE every `[:>]` crossing renders through: a one-hook component
  whose `useSyncExternalStore` answers `false` from the server snapshot and
  `true` from the client one — `mint-host-gate!`'s triple with the
  placeholder fixed at `nil`, which is what `:client-only` compiles to at
  the door. The component rides in the carrier's `c` slot and the
  converted props in `p`, so the object the foreign component receives is
  exactly the one `raw-element` built, with nothing of ours in it;
  children ride the outer element and are forwarded as `createElement`'s
  third argument, and the childless case is its own branch because an
  `undefined` third argument WRITES `children` onto the inner props.

  Shared rather than minted per component: a component-keyed cache is the
  identity-keyed auto-hosting HD-011 rejected, and cannot be built anyway
  (React's built-in wrapper types are registered symbols, which `WeakMap`
  excludes as keys). Argument:
  docs/design/hicasso/studio/raw-escape-spec.md."
  (let [gate (fn [^js props]
               (if (react/useSyncExternalStore
                     gate-no-subscribe gate-adopted gate-unadopted)
                 (let [component (unchecked-get props "c")
                       js-props  (unchecked-get props "p")
                       kids      (unchecked-get props "children")]
                   (if (undefined? kids)
                     (react/createElement component js-props)
                     (react/createElement component js-props kids)))
                 nil))]
    (unchecked-set gate "displayName" "[:>]")
    gate))

(defn- raw-component
  "The Component slot of a `[:>]` vector, or a loud refusal. React mints
  the fiber and reports a bad type itself; refused HERE, in the owner's
  render window and on the server too, are the three values React would
  accept or misreport into silence: `nil` — the broken-import symptom,
  `:default` against a library with no default export, which React names
  only at fiber creation, post-adoption and client-only — and a `defview`
  or `defhost` head, which React would mount raw: a `defview` product is
  `fn?`-true, so its shell would run with `rfProps` undefined and the
  body would receive nil props. Design record:
  docs/design/hicasso/decisions.md, HD-011."
  [argv]
  (let [c (nth argv 1 nil)]
    (when (or (nil? c) (boundary-head? c) (host-head? c))
      (fail! :rf.error/hicasso-raw-not-a-component
             're-frame.hicasso.impl.codec/raw-element
             (str "[:>] was handed "
                  (cond
                    (nil? c)           (if (< (count argv) 2) "no component at all" "nil")
                    (boundary-head? c) "a defview product"
                    :else              "a defhost product")
                  " in the Component position. "
                  (if (nil? c)
                    (str "The usual cause is a JS import that resolved nothing "
                         "— e.g. `:default` against a library with no default "
                         "export. Write [:> Component props & children], or "
                         "declare the crossing with defhost.")
                    (str "A Hicasso head is a head in its own right — write "
                         "[my-view …] or [my-host …]; mounted raw, a view's body "
                         "would receive nil props, silently.")))
             {:component c :argv-count (count argv)}))
    c))

(defn- raw-element
  "One `[:> Component props & children]` vector as a React element: the
  door's indices shifted by one (component at 1, attr map at 2 when there
  is one, children from 3 or 2; `[:> Component]` is legal), `:key` onto
  the crossing's outer element, every prop through `host-entry` against
  an empty declared roster, children lowered here inside the writing
  boundary's render window so a child intent fires into the right frame
  however much later the foreign component renders it. The TYPE is always
  `raw-gate`; the component rides in the carrier's `c` slot. The
  Component slot is validated once, in `vector-kind`, and read here
  without re-derivation."
  [argv]
  (let [component  (nth argv 1)
        has-props? (props-map? argv 2)
        props      (if has-props? (nth argv 2) {})
        js-props   (reduce-kv (partial host-entry raw-crossing {} #{}) #js {} props)
        carrier    #js {"c" component "p" js-props}]
    (when-some [k (:key props)] (unchecked-set carrier "key" k))
    (make-element raw-gate carrier argv (if has-props? 3 2))))

(defn- fragment-element [argv]
  (let [has-props? (props-map? argv 1)
        props      (if has-props? (nth argv 1) nil)
        js-props   #js {}]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (make-element (.-Fragment react) js-props argv (if has-props? 2 1))))

(defn- fragment-head?
  "Is this the fragment spelling? `=`, never `identical?`: keyword
  literals are shared constants only when the build interns them, so an
  identity test works under `:advanced` and silently routes every
  fragment into the native-tag path everywhere else."
  [head]
  (= :<> head))

(defn- raw-head?
  "Is this the raw-escape spelling? `=` for `fragment-head?`'s reason,
  sharper here: routed into the native path, `[:> Foo {}]` would ask
  React for an element literally named `>`."
  [head]
  (= :> head))

(defn- hiccup-tag?
  "Is this head a native tag? Asked AFTER the fragment and raw arms in
  `head-kind`, its only caller, so the `(not (fragment-head? head))` this
  body could re-ask is dead — and asking it would make every native tag
  on every page pay the fragment `=` twice."
  [head]
  (or (keyword? head) (symbol? head) (string? head)))

;; ---------------------------------------------------------------------------
;; The discriminations, each named once
;; ---------------------------------------------------------------------------
;;
;; `vec->element` and `as-element` each ask WHAT KIND of thing this is
;; before they act, and each question has a second asker: the test kit's
;; L2 walk (`re-frame.hicasso.test`) records what the runtime would render
;; as data and cannot inspect a React element to find out. A classifier
;; duplicated in a `cond` of its own drifts, so each question is asked in
;; exactly one place — here — in the costed order the walk is tuned to,
;; and both callers dispatch on the answer.

(defn head-kind
  "WHICH KIND OF HEAD a hiccup vector has — `:fragment`, `:raw`, `:tag`,
  `:boundary`, `:host` or `:invalid`. Named once: `vec->element` dispatches
  on it to build a React element, the test kit to build a Spec 004B node."
  [head]
  (cond
    (fragment-head? head) :fragment
    (raw-head? head)      :raw
    (hiccup-tag? head)    :tag
    (boundary-head? head) :boundary
    (host-head? head)     :host
    :else                 :invalid))

(defn vector-kind
  "`head-kind`'s answer for this vector's head, after the two checks every
  reader must pass first: an empty vector is refused
  (`:rf.error/hicasso-empty-vector`) because every arm reads position 0,
  and a `:raw` vector's Component slot goes through `raw-component`, so
  `[:>]`, `[:> nil]` and `[:> a-view]` are refused by the runtime's own
  guard whichever side asked. `vec->element` runs it before it builds and
  the test kit's L2 walk (`re-frame.hicasso.test`) before it records — one
  preflight, so a malformed vector raises one refusal under one id. Both
  ids name the door whose contract is enforced as `:where` (`vec->element`,
  `raw-element`), the spellings Spec 009 pins. Costs one call and one
  keyword `=` beyond what every vector already pays; only the `:raw`
  population pays `raw-component`'s `cond`, once."
  [argv]
  (when (zero? (count argv))
    (fail! :rf.error/hicasso-empty-vector
           're-frame.hicasso.impl.codec/vec->element
           "A hiccup vector must have a head."
           {}))
  (let [kind (head-kind (nth argv 0))]
    (when (= :raw kind)
      (raw-component argv))
    kind))

(defn vec->element
  "Interpret one hiccup vector. `vector-kind` is the preflight: it has
  refused an empty vector and a malformed escape before any arm below
  runs, so every arm here reads a vector it knows is well formed."
  [argv]
  (case (vector-kind argv)
    :fragment (fragment-element argv)
    :raw      (raw-element argv)
    :tag      (native-element argv)
    :boundary (boundary-element argv)
    :host     (host-element argv)
    :invalid
    ;; `:invalid` implies a head, because `vector-kind` refused the
    ;; headless vector — so this reads position 0 without a default, and
    ;; reads it on the cold arm only.
    (let [head (nth argv 0)]
      (fail! :rf.error/hicasso-bad-head
             're-frame.hicasso.impl.codec/vec->element
             (if (fn? head)
               (str "A plain function in head position is a loud error (HD-016). "
                    "Hiccup head " (pr-str head) " is not a valid element head; use a "
                    "tag keyword, :<>, :>, a view minted by defview, or a host minted "
                    "by defhost. A plain function in head position is never a silent "
                    "embedding — call it, or make it a view.")
               (str "Hiccup head must be a tag keyword, :<>, :>, a defview product, "
                    "or a defhost product. Hiccup head " (pr-str head) " is none of "
                    "them. A raw JS component is never a silent embedding — declare "
                    "it with defhost, or write the escape [:> Component …] (HD-011)."))
             {:head head}))))

(defn child-kind
  "WHICH KIND OF THING a hiccup value in child position is. Named once:
  `as-element` dispatches on it to build React's child, the test kit to
  build a Spec 004B child.

      :nothing        renders nothing — `nil`, `false`
      :text           renders as itself — a string or a number
      :markup         a hiccup vector
      :splice         a seq, whose members splice in document order
      :true-child     `true`, which is a loud error (HD-016)
      :react-element  React's own value, passed through untouched
      :named          a keyword or symbol, which renders as its `name`
      :foreign        anything else, which React is handed as it stands

  The arm order is `as-element`'s costed order and not a taxonomy."
  [x]
  (cond
    (nil? x)                 :nothing
    (false? x)               :nothing
    (string? x)              :text
    (vector? x)              :markup
    (number? x)              :text
    (seq? x)                 :splice
    (true? x)                :true-child
    (react/isValidElement x) :react-element
    (keyword? x)             :named
    (symbol? x)              :named
    :else                    :foreign))

(defn as-element
  "Interpret any hiccup form: `nil` and `false` render nothing, a string
  or number is itself, a vector goes to `vec->element`, a seq splices
  (`expand-seq`), `true` is an error (HD-016), a React element passes
  through, a keyword or symbol renders as its `name`, anything else is
  handed to React as it stands.

  The branch order is `child-kind`'s, and it is a costed order: the
  branches are mutually exclusive, so order changes only what each
  population pays, and `vector?` is the dear test (`native-satisfies?`
  for anything without the `IVector` marker) — asking `string?` first
  took the census page's child roster from 22.5 to 8.9 ns/child
  (docs/design/hicasso/studio/our-walk-against-reagents.md §4(b))."
  [x]
  (case (child-kind x)
    :nothing        nil
    :text           x
    :markup         (vec->element x)
    :splice         (expand-seq x)
    :true-child     (fail! :rf.error/hicasso-true-child
                           're-frame.hicasso.impl.codec/as-element
                           "nil and false render nothing; true is an error (HD-016)."
                           {})
    :react-element  x
    :named          (name x)
    :foreign        x))

(defn root-element
  "`as-element` for a hiccup form written OUTSIDE any boundary body — the
  root, or an outward React bridge. Every other element is created by an
  ancestor body already inside `intent/with-frame`; this is the one
  creator that has to NAME the frame. `*dispatch*` is deliberately not
  bound: the frame is an identity the root has, while a frame-locked
  dispatch is what makes an intent vector legal, and an intent outside a
  boundary stays the loud `:rf.error/hicasso-intent-outside-boundary`."
  [frame-kw hiccup]
  (binding [intent/*frame* frame-kw]
    (as-element hiccup)))

;; ---------------------------------------------------------------------------
;; THE OUTWARD BRIDGE — the codec's other half
;; ---------------------------------------------------------------------------
;;
;; Everything else here encodes hiccup into React elements; the bridge
;; decodes a React parent's props object into a hiccup body's props and
;; then calls `vec->element`, the same entry every hiccup vector goes
;; through. No second element builder: `rfProps` is written in exactly
;; one place (`boundary-element`) and the bridge inherits every refusal
;; the codec already raises.

(def ^:private slot-keys
  "The three React renames read back to the hiccup key they came from.
  `prop-name` is not injective — `:class`, `:className` and `\"class\"`
  are one slot — so the inverse chooses the spelling the guide teaches."
  {"className" :class "htmlFor" :for "charSet" :charset})

(defn prop-key
  "The hiccup prop key a React slot name came from —
  `re-frame.hicasso.impl.slot/prop-name` read backwards. `\"onClick\"` →
  `:on-click`; `\"aria-*\"`, `\"data-*\"` and a `--custom-property` pass
  through; the three React renames go back to their HTML spellings
  (`slot-keys`); a slot still carrying a hyphen was never camelCased and
  is answered verbatim. The round trip is the contract: every decoded key
  emits back into the same slot, so an author writing one keyword on both
  sides of a crossing reads it in the body.

  `prop-name`'s STRING arm is the one non-correspondence, by design: a
  string key means *emit exactly this name*, so `\"on-input\"` decodes to
  `:on-input`, whose slot is `onInput` — the right answer, because a body
  handed `foo-bar` by a JavaScript parent wants `:foo-bar`."
  [s]
  (or (slot-keys s)
      (if (str/includes? s "-")
        (keyword s)
        (keyword (str/replace s #"[A-Z]" #(str "-" (str/lower-case %)))))))

(defn- outward-children
  "React's `children` slot in the container `realize-children` hands a
  hiccup body: a vector, or nil when there are none. React's slot is
  three shapes — absent, the child itself, an array — and copied through
  it would make `:children` mean different things by count; one spelling
  means one thing. Members cross by identity and are not walked into: a
  nested array (JSX's `{head}{rows}`) stays one member, which React
  flattens at render. An empty array answers nil, so `:children` is
  absent rather than present-and-empty, as `realize-children` answers
  for `(for [x []] …)`."
  [c]
  (if (array? c)
    (when (pos? (alength c)) (vec c))
    [c]))

(defn- outward-props
  "A React props object decoded into the ordinary props map a boundary
  body destructures: shallow, by identity, own properties only — the
  mirror of `boundary-element`'s hand-off, and no deeper for the reason
  HD-011 refuses deep conversion the other way. `children` lands at
  `:children`, one spelling and one shape whichever side filled it."
  [^js js-props]
  (if (nil? js-props)
    {}
    (persistent!
      (reduce (fn [m s]
                (if (identical? "children" s)
                  (if-some [cs (outward-children (unchecked-get js-props s))]
                    (assoc! m :children cs)
                    m)
                  (assoc! m (prop-key s) (unchecked-get js-props s))))
              (transient {})
              (js/Object.keys js-props)))))

(defn as-component
  "Hand a hiccup head to React: a real React function component a React
  parent (UIx, Reagent or plain JavaScript) renders without a second
  root, a second frame, or a sight of the internal `rfProps` ABI.

      (def article-card* (h/as-component article-card))
      ;; on the React side: <ArticleCard articleId={7} />

  The parent's props arrive as the view's ordinary props map (`prop-key`
  on each slot) and React's `children` at `:children` as the vector a
  hiccup caller's would be (`outward-children`). That decode is SHALLOW:
  it reverses the camelCasing on each key and takes each value as it
  finds it. Values therefore cross BY IDENTITY here, which is a fact
  about this decode rather than a promise about what arrives — a Reagent
  parent's `[:>]` runs its own `convert-prop-value` first, so a keyword
  reaches the body as its name and a map as a camelCased object. Names
  round-trip across a crossing; values do not.

  The element is built by `vec->element` from `[head props]`, so the
  view keeps its memo wrapper, its reads, its teardown and its refusals;
  the frame is the surrounding React context's — the one
  `re-frame.adapter.context/frame-context` that `h/mount!`,
  `rf/frame-provider` and `rf/frame-root` all write — so what is
  required is a frame from any React-shaped adapter, not a Hicasso root,
  and outside every frame the shell refuses with
  `:rf.error/no-frame-context`. Neither `*frame*` nor `*dispatch*` is
  bound — this is not a boundary body, so an intent vector in a `[:div]`
  handed through here stays the loud
  `:rf.error/hicasso-intent-outside-boundary`. Call it ONCE, at top
  level: it allocates a component, and a fresh one per render is a fresh
  element type that remounts the subtree, `React.memo`'s own law."
  [head]
  (let [named     (when (fn? head) (unchecked-get head "displayName"))
        component (fn hicasso-as-component [js-props]
                    (vec->element [head (outward-props js-props)]))]
    (unchecked-set component "displayName"
                   (str "hicasso/as-component" (when named (str "(" named ")"))))
    component))

;; ---------------------------------------------------------------------------
;; Cache observation — for the tests and the bench, never for the runtime
;; ---------------------------------------------------------------------------

(defn cache-sizes []
  {:tags  (count (js/Object.keys tag-cache))
   :props (count (js/Object.keys prop-cache))})

(defn reset-caches!
  "Empty both codec caches, re-seeding the prop cache through
  `seed-prop-cache!` so a suite's `:each` fixture cannot leave it holding
  a different spelling from a cold build's."
  []
  (doseq [k (js/Object.keys tag-cache)] (js-delete tag-cache k))
  (doseq [k (js/Object.keys prop-cache)] (js-delete prop-cache k))
  (seed-prop-cache! prop-cache)
  nil)
