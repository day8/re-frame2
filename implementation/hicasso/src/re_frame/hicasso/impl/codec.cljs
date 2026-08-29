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
;; Errors — `fail!` is `re-frame.hicasso.impl.error`'s
;; ---------------------------------------------------------------------------
;;
;; One constructor for the whole package: the same id / position / reason
;; a test can assert on, plus the ambient view and source coordinate no
;; call site is in a position to supply.

;; ---------------------------------------------------------------------------
;; Cache hygiene — the own-property guard both caches share
;; ---------------------------------------------------------------------------

(defn- reserved-name?
  "Is `n` — a tag or prop name, or an emitted prop slot — one of the
  three names that must never be written to a JS object, or emitted
  through one? A hiccup literal named `__proto__`, `prototype` or
  `constructor` would otherwise reach an object's prototype. That roster
  is the whole predicate.

  Three `===` string compares rather than a set lookup, because a set
  lookup pays a string hash for a roster of three —
  36.9 ns against 9.6 on the census page's own literals, measured by
  `walk_profile_app`'s micro table.

  **It is asked on the cache MISS path only**. The caches
  below carry no prototype, so a hostile literal cannot make a lookup
  answer wrongly and the guard has only one job left: keep the three
  names out of the caches, and out of the emitted props object — which
  DOES carry `Object.prototype`, and is where [[PropSlot]]'s `reserved?`
  field answers instead. A miss happens once per distinct literal for
  the life of the build; a hit happens once per element per mount."
  [n]
  (or (identical? "__proto__" n)
      (identical? "prototype" n)
      (identical? "constructor" n)))

(defn- empty-cache
  "A codec cache: a JS object with **no prototype at all**.

  Both caches are keyed by the author's literal, so both must answer
  two hostile questions on every lookup: could a literal named
  `__proto__` poison a write, and could a literal named `toString` or
  `constructor` hit an INHERITED property and be served a value nobody
  cached. `Object.create(null)` answers the second one structurally —
  there is no prototype chain, so a lookup can only ever return an own
  property or `undefined` — and demotes the first to the miss path,
  where [[reserved-name?]] still refuses the write.

  What that buys, measured on the census page's own literal roster
  (`walk_vs_reagent_app`, 1,489 prop occurrences and 1,202 tag
  occurrences, quiet-ish box, in-page clock): the prop lookup 18.1 ->
  11.1 ns/op and the tag lookup 16.6 -> 11.2, against the
  `hasOwnProperty.call` + three-compare shape they replace. The costed
  alternative — keep `#js {}` in V8's fast mode and validate the hit by
  TYPE (`instance? PropSlot`), since nothing on `Object.prototype` is
  one — read 13.8 and 12.1 and is DECLINED on both margin and
  construction: a cache with no prototype cannot serve an inherited
  value at all, where a type check is a promise that it will be noticed."
  []
  (js/Object.create nil))

;; ---------------------------------------------------------------------------
;; Tag parsing and its cache
;; ---------------------------------------------------------------------------

(def ^:private re-tag
  "`tag`, optional `#id`, optional `.class.class`. The `#id` must precede
  the classes, as in `reagent2.impl.template` and in stock Reagent."
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
  "[[parse-tag]] behind the codec-work cache (HD-004). One entry per
  distinct tag literal the build ever renders.

  One property read answers the hit, because [[empty-cache]] has no
  prototype to serve a wrong one. The three poisoning names never
  reach the cache — the refusal sits on the miss branch, which is the
  only branch that writes."
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

;; The slot rule itself is NOT here. It is
;; [[re-frame.hicasso.impl.slot/prop-name]], in `.cljc`, because
;; the `[:>]` migration codemod has to ask the same question on the JVM
;; and a tool that reimplemented it would reproduce — inside the tool —
;; the silent divergence the codemod exists to delete. Only
;; the CACHING of its answers is codec work, and that is what follows.

(deftype PropSlot [js-name reserved? event? ref? class?]
  ;; What the prop cache holds for one prop literal: the React
  ;; name, PLUS the four classifications a per-prop walk would
  ;; otherwise re-derive per element per render — is the
  ;; emitted slot reserved, is the position an event position, is it the
  ;; ref slot, is it the class slot. Each is a pure function of the
  ;; literal's NAME, so caching them beside the name changes what a lookup
  ;; ANSWERS and nothing about when it is valid: there is still exactly
  ;; one entry per distinct literal, minted on first sight, correct for
  ;; the life of the build.
  ;;
  ;; `event?` is the name-string's answer (`intent/event-prop?` on the
  ;; NAME), and the consumer must gate it on `keyword?` — a symbol
  ;; spelled `on-click` shares the entry but is NOT an event position
  ;; (event-prop? answers false for symbols), which is why the flag is
  ;; stored spelling-neutral and applied spelling-aware. That is the same
  ;; discipline as the string/keyword split in [[cached-prop-name]]: the
  ;; cache is keyed by name, so anything the name does not determine must
  ;; be decided at the call site.
  )

(defn- mint-slot
  "The [[PropSlot]] for a keyword or symbol prop literal whose name is
  `n`. Every field a pure function of the literal, per the deftype note."
  [k n]
  (let [js-name (slot/prop-name k)]
    (->PropSlot js-name
                (reserved-name? js-name)
                ;; asked of the NAME, not of `k` — a symbol shares the
                ;; entry, and `event-prop?` already answers a boolean
                (intent/event-prop? n)
                ;; "ref" — [[ref-slot]], spelled literally because the def
                ;; sits below; `identical?` on primitive strings is value
                ;; comparison.
                (identical? "ref" js-name)
                ;; "className" — [[class-slot]], likewise. The class slot
                ;; is a position too: its value is coerced by
                ;; [[class-names]] rather than by [[convert-prop-value]],
                ;; and two spellings of it COMPOSE instead of one
                ;; overwriting the other.
                (identical? "className" js-name))))

(defn- seed-prop-cache!
  "Pre-warm `cache` with the three React renames, and return it.

  **The seeded entries are the RULE, not memos of one** — so each slot
  name is ASKED of [[re-frame.hicasso.impl.slot/prop-name]]
  rather than written out again here. A hand-spelled seed is the one
  place this file could answer a slot the shared rule would not; and a
  seed written out twice, here and in [[reset-caches!]], gives a drift
  two chances, with the suites' `:each` fixture making the second copy
  the live one. There is ONE copy, and it cannot disagree with the rule
  because it does not restate it.

  None of the three is reserved, an event position, or the ref slot;
  `class` IS the class slot, which is the whole reason the rule is
  stated on the emitted name rather than on the key."
  [cache]
  (doto cache
    (unchecked-set "class" (->PropSlot (slot/prop-name :class) false false false true))
    (unchecked-set "for" (->PropSlot (slot/prop-name :for) false false false false))
    (unchecked-set "charset" (->PropSlot (slot/prop-name :charset) false false false false))))

(def ^:private prop-cache (seed-prop-cache! (empty-cache)))

(defn- prop-slot
  "The [[PropSlot]] for keyword/symbol `k` with name `n` — the caller has
  already checked the spelling.

  One property read answers the hit ([[empty-cache]]). A reserved name
  is minted on every sight rather than cached: the
  refusal sits on the miss branch, which is the only branch that writes,
  and the slot it mints carries `reserved?` so the emitted props object
  — which DOES have a prototype — never receives the name either."
  ^PropSlot [k n]
  (let [hit (unchecked-get prop-cache n)]
    (if (undefined? hit)
      (let [s (mint-slot k n)]
        (when-not (reserved-name? n)
          (unchecked-set prop-cache n s))
        s)
      hit)))

(defn cached-prop-name
  "[[re-frame.hicasso.impl.slot/prop-name]] behind the codec-work
  cache (HD-004).

  **Only a keyword or a symbol is cached**, which is
  `reagent2.impl.template`'s shape and
  is load-bearing here. The cache is keyed by `(name k)` while the rule
  answers a STRING differently from the keyword of the same
  name — a string is already a React name, so `\"on-input\"` stays
  `\"on-input\"` where `:on-input` becomes `\"onInput\"`. Sharing one cache
  entry between them lets either poison the other: the first
  `{\"on-input\" f}` rendered anywhere would answer every later
  `:on-input` with `\"on-input\"` for the life of the build, silently
  emitting every handler written the taught way into a slot React ignores
  — and, worse, would make [[canonical-slot]] answer differently
  depending on what had rendered first, which is exactly the order
  dependence the owned-literal law exists to remove."
  [k]
  (if-not (or (keyword? k) (symbol? k))
    (if (string? k) (slot/prop-name k) k)
    (let [^PropSlot s (prop-slot k (name k))]
      (.-js-name s))))

;; ---------------------------------------------------------------------------
;; The canonical structural-slot filter
;; ---------------------------------------------------------------------------

(def canonical-slot
  "**The one slot resolver.** The React prop slot a hiccup attribute key
  actually emits into — which is [[cached-prop-name]] itself, and that
  identity is the whole mechanism: the thing a deny asks is the thing the
  emitter will do.

  A props map reaches React through exactly one canonicalisation, so a
  rule written against the RAW map key is a rule that any other spelling
  of the same slot walks straight past. `\"key\"`, `:x/key` and `'key` are
  all React's key; `:on-input` and `:onInput` are one handler; `:class`,
  `:className` and `\"class\"` are one `className`. Every deny, every
  dissoc and every check in this codec and in
  [[re-frame.hicasso.impl.presence]] asks THIS, and never the key
  it was written as."
  cached-prop-name)

(def structural-slots
  "The two React slots that address the ELEMENT rather than its
  attributes. `key` is React's own identity contract and `ref` is
  HD-016's node handle; neither is an attribute, and neither is ever
  taken from a map that is *about* attributes — a presence phase override
  ([[re-frame.hicasso.impl.presence/with-phase]]).

  Held as canonical SLOTS rather than as keys, because the spelling is
  exactly what a hostile or careless map would vary."
  #{"key" "ref"})

(def ^:private ref-slot "ref")

;; The two slots the TAG can write into, named because the shorthand fold
;; asks the emitted object for them rather than asking the props map for a
;; key ([[convert-props]]). `#tag` is the weakest source of an id there is
;; — it loses to any explicit one, in any spelling — and `.foo` composes
;; with whatever the map emitted into `className`, in any spelling.
(def ^:private id-slot "id")
(def ^:private class-slot "className")

(defn structural-slot?
  "Does `k` — in any spelling — land on `key` or `ref`?"
  [k]
  (contains? structural-slots (canonical-slot k)))

(defn- without-slots
  "`m` minus every key whose canonical slot is in `denied`. Returns `m`
  itself, by identity, when nothing is denied — which is the ordinary
  case, so the filter allocates nothing on a map that was already legal."
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
  emitted, and return it.

  **On the emitted object, and that placement carries the rule.** The
  rule is *an explicit id wins over the shorthand, and the shorthand
  class is prepended to a declared one*; stated over the props MAP it
  reads `:id`, `:class` and `:className` and sees exactly three of the
  spellings this codec accepts. `[:div#tag.foo {\"id\" \"caller\"
  \"className\" \"bar\"}]` walks straight past that form of it: neither
  key is seen, the shorthand is added as a second entry landing on the
  same React slot, and which one survives is decided by the order the
  props map happens to iterate in — the explicit id can lose to `#tag`,
  and the caller's class can replace `.foo` instead of composing with it.

  Asked of the emitted object there is nothing left to resolve. Every
  spelling has already been through [[canonical-slot]] on its way into
  this object, so `id` present means *the author or their caller wrote an
  id*, however they spelled it, and `className` holds the composed class
  ([[convert-entry]]) whatever it was written as. One `undefined?` test
  and one `class-names` answer both halves for every spelling at once.

  It also avoids the map surgery a map-level fold needs — the
  `dissoc`/`assoc` pair rebuilding the attribute map of every element
  carrying a shorthand, which the walk profile prices at most of
  [[convert-props]]'s cost — and with it any fast lane to dodge it."
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
  "A prop value in the shape React wants. Functions pass through **by
  identity**, deliberately: rewrapping them would defeat `React.memo` and
  every downstream bail-out that compares handler identity.

  `string?` is asked first: a string is the overwhelming prop
  value on a census page — `href`, `class`, `data-testid`, `src`, `type`
  — and asked any later it would prove itself *not* a fn, map, keyword,
  symbol or collection on its way to `:else`, two of those being the dear
  native-satisfies? protocol checks. One `typeof` answers it; every other
  branch pays that one `typeof` and keeps its order, so the answer is
  the same for every input."
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
;; The presence override keys (HD-025), and why they live HERE
;; ---------------------------------------------------------------------------
;;
;; They are `re-frame.hicasso.impl.presence`'s vocabulary and they are
;; DEFINED here, for [[revision-key]]'s own reason one section up: this
;; walk has to recognise them, and `presence` requires this namespace
;; rather than the other way round. Writing the two literals a second
;; time in the walk would leave a keyword with two homes — which is why
;; `naming-ledger.md` row 31's respelling to `::motion/…` (ruled, and
;; executed under rf2-hg3q) could land whole: one home, and `presence`
;; reads them from it.

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
  "**Dev only.** Record the body function a minted head runs, ON the head,
  and return the head.

  ## What it is for, and what it deliberately is not

  A boundary head is a React component: it runs its body inside
  [[re-frame.hicasso.impl.collector/shell]], under two React hooks, and
  the body is otherwise reachable only through that shell. Without this,
  the L0–L2 test kit — which mounts nothing and runs no hook — would take
  a minted `h/defview` head and have no route back to the function the
  author wrote.

  The kit renders a minted head, so the head carries its body. **One own
  property, no registry and no map** — the same shape as
  [[mark-boundary!]] beside it, and the reason
  the memo contract is untouched: the head is still the function, still
  what a `defview` hands back, and no new object escapes.

  ## It is not there in production, and that is the point

  The one call site ([[re-frame.hicasso.impl.collector/mint-view!]]) sits
  inside `(when ^boolean js/goog.DEBUG …)`, so under `:advanced` +
  `goog.DEBUG=false` the Closure compiler removes the call and this fn
  with it, and a production head carries nothing. That erasure is
  asserted rather than asserted-about:
  `re-frame.hicasso.view-body-retention-elision-prod-test` reads
  [[retained-body]] off a head minted in the real advanced bundle and
  requires nil."
  [head body-fn]
  (unchecked-set head body-slot body-fn)
  head)

(defn retained-body
  "The body function [[retain-body!]] recorded, or nil — which is what a
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
;; `defhost` is the door, and the only form taught. The declaration is a
;; VALUE — the foreign component, its options, and a name for the
;; crossing — minted once and legal as a hiccup head anywhere. The `[:>]`
;; raw escape below is HD-011's explicitly secondary form, for the cases
;; a static declaration cannot express; it is the SAME crossing with the
;; declaration erased, and what erasing the declaration costs is exactly
;; what the declaration carried.
;;
;; A callback contract is INFERRED from the prop's position, exactly as
;; the native walk infers it ([[re-frame.hicasso.impl.intent/lower-prop]]):
;; an `on*`-spelled prop is an event position, any other walked prop a
;; render position, `:ref` React's. `:callbacks` is an optional OVERRIDE
;; — `{:on-render-item :render}` — for the on*-named render props some
;; vendors ship; it takes `:event` or `:render`, is keyed on the
;; CANONICAL slot like every other rule in this codec, and outranks the
;; spelling. `:slots` stays declared, because a vector is legal data and
;; nothing can infer a markup position.
;;
;; ## The `:server` policy — HD-011's placeholder, activated
;;
;; HD-011 lists "SSR placeholder" among `defhost`'s strong defaults, and
;; SSR is required scope, so the placeholder is real. TWO POLICIES, and a
;; sibling option that belongs to one of them:
;;
;;     :server :client-only     ; THE DEFAULT — omit :server and this is it
;;     :server :render          ; "this component is safe on the server"
;;     :fallback <hiccup>       ; Client-only's placeholder, and only its
;;
;; `:client-only` renders NOTHING where the host sits until the client
;; has adopted the markup, or the declared `:fallback` hiccup if there is
;; one; `:render` runs the component itself, server-side, with its real
;; props and its real children. Anything else is refused at the
;; declaration, `:fallback` under `:render` included. The DEFAULT is the
;; conservative one because a foreign React component is exactly the node
;; whose render may reach for `window` — the door cannot know, so it does
;; not guess. `:render` is the AUTHOR saying, which is a different thing
;; from the door guessing.
;;
;; **The key is `:server` and the values are the two sides that render**
;; (naming-ledger row 21). `:ssr` would name the TECHNIQUE
;; and admit the fallback as a third value shape — an enum sometimes
;; replaced by a nested map. One policy concept, one spelling; and a
;; fallback reads as what it is, markup at the crossing rather than a
;; policy value.
;;
;; **`:render` exists for the context PROVIDER case**: a transparent
;; wrapper that contributes no markup of its own and exists solely to
;; carry a subtree. Under Client-only the unadopted arm returns something that
;; is not the component, so the crossing's
;; CHILDREN are dropped and the provider deletes the whole application
;; from the server response — silently, because the server snapshot and
;; hydration's first client pass agree by construction. `:render` is the
;; one policy under which the children reach the server at all.
;;
;; ## Two shapes, and which one a declaration mints
;;
;; For `:client-only`, with or without a fallback, **ONE mechanism
;; serves the server, the hydration pass and the fresh mount**, and it is
;; [[mint-host-gate!]]: a one-hook component whose `useSyncExternalStore`
;; answers `false` from its SERVER snapshot and `true` from its client
;; one. React reads the server snapshot under `renderToString` and again
;; on hydration's first client pass, then re-renders with the client
;; snapshot once adoption completes — so the server HTML and the first
;; client pass agree BY CONSTRUCTION (no mismatch to reconcile), and a
;; `createRoot` mount, which never consults a server snapshot at all,
;; renders the foreign component on its very first pass with no
;; placeholder flash. A server walk therefore does not have to consult
;; the policy separately; it consults it by rendering.
;;
;; For `:render` there is NO GATE — the head's `gate` slot carries the
;; foreign component itself, which is HD-011's original zero-wrapper,
;; zero-fiber, zero-hook shape restored for the hosts that can take it.
;; One tree everywhere: the server render, hydration's first pass and a
;; fresh `createRoot` mount all render the SAME element type with the
;; same props, the same context and the same children. So there is zero
;; mismatch by identity, no snapshot pair, no adoption event to wait
;; for, and — the fact that separates this policy from every rejected
;; candidate — NO REMOUNT.
;;
;; **Why a remount is the law and not a detail.** React reconciles a
;; position by element TYPE, and under a gate the gate IS the type. Any
;; policy whose unadopted branch returns something other than the
;; component therefore pays a full subtree destroy-and-rebuild the moment
;; adoption swaps the type. That is free when the thing torn down is an
;; inert skeleton; it is not free when it is the application, and it is
;; why `:server :children` — "render `props.children` in place of the
;; component" — is refused rather than adopted: it restores the markup
;; without the provider above it, so every consumer below reads the
;; context DEFAULT server-side (silent-absent becomes silent-wrong), and
;; then remounts the whole just-hydrated subtree at adoption.
;;
;; **The price, stated so nobody assumes it is zero**: a gated
;; declaration mints ONE gate, so a
;; Client-only crossing costs one fiber and one hook; a `:render`
;; crossing costs neither — there the foreign component is the
;; element's own type, the zero-wrapper, zero-fiber, zero-hook shape.
;; HD-020(b)'s ≤2 budget is a statement
;; about Hicasso's BOUNDARY shells and is untouched either way: the gate
;; is not a boundary, holds no subscription, and reads no frame.

(def ^:private host-marker "hicassoHost")

;; [[host-head?]] is the predicate over that marker and reads naturally
;; beside [[host-server]], at the end of this section. The fallback walk
;; below needs it three definitions earlier.
(declare host-head?)

(def ^:private callback-contracts
  "The two contracts a `:callbacks` override may name — the two
  [[re-frame.hicasso.impl.intent/lower-prop]] infers by spelling. The
  migration codemod's `shared_rule_test` reads this set out of the source
  text and prints it to migrators, so it stays a literal."
  #{:event :render})

(def ^:private host-options
  "Every key a declaration may carry. A door that read `:callbacks` and
  SILENTLY IGNORED everything else would make a misspelled `:server`, or
  a policy invented by an author reading the wrong docstring, a no-op
  that looked like a setting. That is the same defect class as an intent
  crossing as inert data, and it gets the same treatment: refused, at the
  declaration.

  `:ssr` is not in the roster and is not aliased to `:server`: this is
  pre-alpha and a rename is a rename, so the retired spelling
  lands on [[mint-host!]]'s unknown-option refusal, which names the four
  keys that exist."
  #{:callbacks :slots :server :fallback})

;; --- The gate -------------------------------------------------------------
;;
;; Three module-level functions rather than literals written at the call
;; site: `useSyncExternalStore` compares the subscribe function by
;; identity and re-subscribes when it changes, and a fresh closure per
;; render would make every host re-subscribe on every render for no
;; reason at all. There is nothing to subscribe TO — adoption happens
;; once and never un-happens — so the subscribe function's whole body is
;; the unsubscribe it must return.

(def ^:private gate-no-subscribe (fn [_] (fn [] nil)))
(def ^:private gate-adopted (fn [] true))
(def ^:private gate-unadopted (fn [] false))

(defn ^boolean adopted?
  "**THE ADOPTION READ** — `false` while React is producing server bytes
  and again on hydration's FIRST client pass, `true` on every render
  after the client has adopted that markup, and `true` on the very first
  pass of a fresh `createRoot` mount, which consults no server snapshot
  at all.

  One React hook, and the whole of the client-only mechanism above: the
  server snapshot and hydration's first pass answer the same thing BY
  CONSTRUCTION, so there is no mismatch for React to reconcile, and a
  fresh mount never shows a placeholder it would immediately replace.

  A hook, so it is legal only from inside a component's own render.
  [[mint-host-gate!]] is one caller; the portal helper
  ([[re-frame.hicasso.impl.portal]]) is the other, and the second caller
  is why the triple above is read through a name rather than written
  inline. Three module-level functions compared by identity are exactly
  the kind of thing a second copy gets subtly wrong — a fresh closure
  per render re-subscribes every host on every render — and one name
  makes that unrepresentable rather than merely unlikely."
  []
  (react/useSyncExternalStore gate-no-subscribe gate-adopted gate-unadopted))

;; ---------------------------------------------------------------------------
;; The adoption crossing, observed once — `:rf.ssr/host-adopted`
;; ---------------------------------------------------------------------------
;;
;; [[adopted?]] above is the whole client-only mechanism, and it is
;; INVISIBLE: React swaps the placeholder for the foreign component on its
;; own post-hydration pass, and nothing in the instrumentation stream says
;; it happened. So the debugging question a reader actually asks of a
;; hydrated page — *is this region showing its fallback or its live
;; subtree?* — had no answer at all.
;;
;; The three vars below give it one, and the shape is chosen to say what
;; HICASSO itself does:
;;
;;   - **Per DECLARATION, not per root and not per site.** The crossing
;;     state is closed over by [[mint-host-gate!]]'s gate, and `mint-host!`
;;     mints exactly one gate per `defhost`. A page with twenty sites of one
;;     host therefore emits ONE trace, not twenty. There is no root-scoped
;;     phase value here and no re-frame-level flip to report — React owns
;;     the swap, and this only witnesses it.
;;
;;   - **Only a real CROSSING, never a fresh mount.** `adopted?` answers
;;     `true` on the very first pass of a `createRoot` mount, which consults
;;     no server snapshot and shows no placeholder to replace. Emitting
;;     there would report a transition that did not occur, so the announce
;;     is armed only once the gate has actually rendered its placeholder.
;;
;;   - **At most once.** `announce-adoption!` disarms the crossing before it
;;     emits, so React's re-renders (and a Strict-Mode double render) cannot
;;     produce a second event.
;;
;; A render-phase emit is legal HERE for a reason particular to this hook,
;; and it is not a licence to emit from renders generally: `adopted?` reads
;; a store whose client snapshot is the constant `true`, so a render that
;; observes adoption cannot be observing a fact that a discarded concurrent
;; render would falsify. There is no false positive available.
;;
;; `interop/debug-enabled?` gates the allocation as well as the emit, so
;; `:advanced` + `goog.DEBUG=false` folds the whole crossing away and a
;; production gate is the two-branch `if` it always was.

(defn- mint-adoption-crossing
  "The per-declaration crossing cell [[mint-host-gate!]] closes over, or
  `nil` in a production build where nothing observes it.

  TWO BOOLEANS AND NOT A THREE-STATE KEYWORD, which is not a style
  preference — a keyword cell with transitions guarded by `identical?`
  silently never fires. `identical?`
  is reference equality: keyword literals are only the same OBJECT when
  the build emits them through a shared constants table, and a dev build
  does not, so `(identical? :fresh @cell)` compares two distinct
  `Keyword` instances and answers `false` at every site. The cell stays
  on its initial value for the whole run, the announce never arms, and
  nothing is emitted — silently, because the failure of a trace to fire
  looks exactly like a page with no crossing on it. Booleans have no
  identity to get wrong."
  []
  (when interop/debug-enabled? #js {:armed false :announced false}))

(defn- note-unadopted!
  "ARM the crossing — this gate has now rendered its placeholder, so the
  next adopted render is a genuine transition rather than a fresh mount."
  [crossing]
  (when crossing
    (unchecked-set crossing "armed" true)))

(defn- announce-adoption!
  "Spec 009's `:rf.ssr/host-adopted` — ONE `:info` trace, the first time an
  ARMED gate renders adopted. Disarms before emitting, so it fires at most
  once per declaration.

  `:info` and not `:warning`: nothing is wrong. This is the normal, correct
  behaviour of `:server :client-only` reporting that it completed, and it
  rides the instrumentation channel rather than the console."
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
  behind its `:server` policy. `:client-only` mints one of these, with
  or without a declared `:fallback`; `:server :render` mints none, and
  the head's `gate` slot carries the foreign component itself.

  `fallback` is walked into an element HERE, at the declaration —
  which is where every other host refusal fires, so a fallback that is
  not hiccup fails with the author's own stack rather than one render
  into a server response. It is walked ONCE and the element is reused
  at every site of the host: React elements are immutable values, and a
  placeholder that differs per site is not a placeholder.

  ## And that is ENFORCED rather than merely stated

  The corollary the guide teaches — *\"a fallback is inert markup\"* —
  holds only halfway if the walk refuses just what it can EVALUATE (an
  intent vector, a `sub` call in the form, hiccup that is not hiccup) and
  never looks inside a head whose body runs later.
  [[refuse-deferring-heads-in-fallback!]] closes that, structurally and
  ahead of the walk, so the sentence is true as written. Its docstring
  carries the two measurements that decided it;
  `re-frame.hicasso.fallback-contents-cljs-test` is the contract.

  The gate hands its own props straight through to the foreign
  component, so the crossing's props object is exactly the one
  [[host-element]] built — `ref` included, which React 19 carries as an
  ordinary prop — and `:key` never reaches here, because
  `createElement` took it off the gate's own element."
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
  "The one server-policy refusal — for a `:server` value outside the two
  and for a `:fallback` that cannot belong to the policy beside it.
  `why` completes the sentence *\"defhost NAME …\"*.

  ONE id for both, because they are one fault: *this declaration does not
  name a server policy the door can honour*. It is the shape
  [[refuse-bad-slots!]] has, for the same reason."
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
  "The `:server` policy this declaration carries, validated — one of the
  TWO the SSR matrix admits. Absent
  means `:client-only` — the ruled default, so an author who writes
  nothing gets the conservative answer and an author who writes the
  default explicitly gets the same one.

  `:render` is an ASSERTION: *this component is safe to render on the
  server*. The spellings an
  author reaches for instead — `:children`, `:transparent`,
  `:passthrough` — stay refused. They assert a structural property nobody
  can check and deliver the subtree under the WRONG context value;
  `:render` names both the conduct (the component renders) and the claim
  (it is safe to)."
  [host-name opts]
  (let [policy (get opts :server :client-only)]
    (if (or (keyword-identical? :client-only policy)
            (keyword-identical? :render policy))
      policy
      (refuse-server-policy! host-name
        (str "declares :server " (pr-str policy) ", which is neither.")
        {:server policy}))))

(defn- declared-fallback
  "The hiccup this declaration carries at `:fallback`, or nil when it
  carries none. A sibling option rather than a policy VALUE:
  `:server` answers which arm applies and `:fallback` is the Client-only
  arm's payload. That is the split the guide teaches, and it is what
  makes the policy displayable — an enum, rather than an enum sometimes
  replaced by a nested map.

  `contains?` and not `if-some`, exactly as [[declared-slots]] draws it:
  an explicit `nil` is a VALUE and the default belongs to an ABSENT key,
  so `{:fallback nil}` is a placeholder that renders nothing written by
  an author who believes they wrote one."
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
;; A foreign component's props are DATA and a host prop is converted
;; shallowly ([[host-prop-value]]); but a modal's `title` or `Suspense`'s
;; `fallback` is a MARKUP position, and hiccup written there would cross
;; as a nested JS array and render as nothing. `:slots` is the
;; declaration that names those positions — `(h/defhost modal Modal
;; {:slots #{:title :footer}})` — and hiccup at a declared slot is lowered
;; by [[as-element]] under the render window of the boundary that wrote
;; the crossing, so an intent inside it fires into that boundary's frame.
;; Everything undeclared stays data: which prop is markup is a fact about
;; the foreign ABI, and only the author knows it. Argument in
;; docs/design/hicasso/decisions.md, HD-011.

(defn- slot-key-name
  "The prop name a `:slots` entry spells, or nil when the entry cannot
  name a prop at all. Keywords, symbols and strings are the three
  spellings [[cached-prop-name]] accepts; a number, a vector or a nested
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
  by [[vec->element]]'s fourth branch.

  `component` is anything React accepts as an element type; `nil` is
  refused here because it is the broken-import symptom (`:default`
  against a library with no default export). `opts` is a map of
  `:callbacks` — an optional override from prop name to `:event` or
  `:render`, for a prop whose spelling infers the wrong contract —
  `:slots`, the set of ReactNode positions ([[declared-slots]]),
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
  `:client-only` it is [[mint-host-gate!]]'s product (one fiber, one
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
  no registry, no map — the same shape as [[boundary-head?]]."
  [v]
  (and (some? v) (true? (unchecked-get v host-marker))))

(defn host-server
  "The `:server` policy `head` was declared with — `:client-only` or
  `:render`. The
  declaration read back as data, for a server walk that wants to state
  the policy it is honouring and for the witnesses that
  assert on it. Nothing on the render path reads it: the policy is
  enforced by WHICH TYPE the declaration mints — a gate for Client-only,
  the foreign component itself for `:render`. A declared `:fallback` is
  not read back: it is markup the gate already closed over, and the
  policy is the two-value fact this answers."
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
;; React already warns about an unkeyed list and this runtime adds nothing
;; to that. What React is silent on is a CONTENT-DERIVED key — a map, a
;; date, a JS object — which coerces to a string per member, collides with
;; nothing, and remounts the row the moment the author edits the entity.

(def ^:private keywarn
  "The sites that have already spoken: a `Map` of member head -> the kinds
  reported for it. `nil` in production — every reader sits behind
  `goog.DEBUG`, so under `:advanced` the object and every message string
  fold away with the branches that reach them. Plain `def`, so a page
  reload resets it, which is React's own semantics for the same dedupe.
  Keyed on the head object rather than a joined site string because an
  already-warned site is re-encountered on every render of the list the
  author has not fixed yet, and a string built per member per render is
  what that lookup would cost."
  (when ^boolean js/goog.DEBUG (js/Map.)))

(defn- ^boolean plain-key?
  "Is this `:key` value one React can coerce to a stable string without
  reading anything the author will edit? Asked FIRST of every member of
  every seq in a dev build, so it is three `typeof`-class tests and
  nothing dearer — deliberately not `coll?`, which for anything without
  the `ICollection` marker falls through to `native-satisfies?` and is
  the dearest predicate on this path (the same accounting as
  [[realize-entry]]'s `keyword?` short-circuit)."
  [k]
  (or (string? k) (number? k) (keyword? k)))

(defn- ^boolean stable-object-key?
  "The two non-primitive `:key` values deliberately classified SAFE.

  A `uuid` and a `symbol` are objects, so [[plain-key?]] rejects both —
  but each string-coerces to its own NAME, which is the identity the
  author means rather than the content of anything they will edit. A
  `uuid` in particular is the canonical entity identifier: warning on
  `{:key (:id entity)}` because that id happens to be a UUID would be
  the false positive that teaches authors to ignore the warning, and a
  guard everyone routes around is worse than the silence this check
  closes. A `symbol` coerces exactly as the `keyword` [[plain-key?]]
  already admits does.

  Asked only inside [[check-member-key!]]'s classification, never on the
  keyed walk — see that docstring's ordering note."
  [k]
  (or (uuid? k) (symbol? k)))

(defn- key-shape
  "What the author put at `:key`, named rather than printed. The VALUE
  never reaches the console: a foreign or cyclic value would blow
  `pr-str` inside a diagnostic, and the author already knows what they
  wrote — what they need is the view, the child and the hazard.

  TOTAL over everything [[check-member-key!]] rejects. The strings below are the ONLY text
  this diagnostic can produce, so the totality and the never-print
  guarantee are one property: no arm falls through to the value.
  `coll?` sits here rather than at the call site because it is the
  dearest predicate on the path and this function runs on detection
  rather than on the walk."
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
  "One member of a lowered child seq, at index `i`. Warns when it is a
  boundary-headed vector whose `:key` is a value React cannot coerce to
  a stable identity; an absent key is React's own warning and is left to
  it. The classification is TOTAL: every non-nil value [[plain-key?]]
  rejects is either classified safe by [[stable-object-key?]] or named by
  [[key-shape]], so a foreign entity object — which `createElement`
  coerces to `[object Object]` for every member — cannot fall out of the
  check in silence. Predicate order is the whole cost: `vector?`, the
  `:key` read, then [[plain-key?]], where a keyed member leaves on a
  `typeof`; [[boundary-head?]] and the classification run only past
  that, so the keyed steady state executes no classification
  instruction, and `coll?` — the dearest predicate on the path — lives in
  [[key-shape]], on detection. Nested seqs need no code: a seq member is
  not a vector, and its own expansion checks its own members."
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
  "[[check-member-key!]] over a whole seq, for the one caller that has no
  loop of its own to ride: [[realize-children]]'s one-level flatten, the
  crossing INTO a boundary. `into` walks the seq there rather than
  stepping it, so a traversal is unavoidable — and it is the rare path,
  taken only by a seq-valued trailing form of a boundary element."
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
  *vector* does not, because a vector is hiccup. Returns nil when there
  are none, so `(:children props)` is absent rather than empty.

  The dev-only [[check-seq-keys!]] call in the `seq?` branch is the one
  place the CROSSING shape is visible. `[a-view {…} (for …)]` hands a
  dynamic list to a view that will splice it, and the flatten below turns
  those members into direct arguments — which React marks validated and
  therefore never warns about. This branch, where the seq is still in
  hand, is the only chance anything has to say so."
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
  "A seq of children as a JS array of elements. One pass, driven by the
  seq's own exhaustion rather than by each child's truthiness, so an
  interior `nil` — the `(for [x xs] (when pred [:li …]))` shape — does not
  truncate the list.

  The dev-only [[check-member-key!]] call RIDES this loop rather than
  pre-scanning the seq, which costs the predicates and no second spine
  traversal (the fn's docstring carries the numbers).

  **The index it reports is `(.-length a)`, not a loop variable**, and
  that is the reason the loop still has exactly the shape it had: one
  element is pushed per member, so the array's length IS the index of the
  member about to be pushed. Threading an `i` would have put an increment
  per child on the production path for a dev message's benefit. As
  written, `goog.DEBUG` folds to `false` under `:advanced`, the whole line
  goes, and what is left is character for character the loop with no
  check in it. `(first items)` is read twice in a dev build
  and once in production for the same reason — it is a field read on a seq
  the loop has already forced, while `next` is the step that allocates."
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
  "One native tag as a React element.

  [[re-frame.hicasso.impl.controlled/install!]] is the only thing
  here that is not a translation of what the author wrote. It runs on
  the converted props, because the condition it tests is about the
  EMITTED element — a controlled `value`, a change handler, a type with
  a caret — and those are canonical slots rather than spellings. It is
  one JS `switch` on the tag for every element that is not an `:input`
  or a `:textarea`, which is nearly all of them.

  It also **answers what to render the props as**, which is the tag for
  everything except a controlled `input`/`textarea` —
  those get the composition shadow's component, and the tag it renders
  is the tag parsed here. The codec asks one question and takes one
  answer; which of the two it is belongs entirely to that namespace.

  ## The revision is read here

  [[revision-key]] is taken off `props` — the author's attribute map —
  with the same expression shape the `:key` read below uses, and for the
  same reason: `key` and the revision are triggers rather than
  attributes, read once at the element and never emitted.

  What lands on the emitted object is a marker under a private slot, and
  [[re-frame.hicasso.impl.controlled/install!]] deletes it as it
  reads it — so nothing named `revision` survives to React, to the DOM,
  or to the server bytes, by construction rather than by a special case
  at each of the three."
  [argv]
  (let [parsed      (cached-parse (nth argv 0))
        has-props?  (props-map? argv 1)
        props       (if has-props? (nth argv 1) nil)
        ;; nil, not `(or props {})` — the absent attribute map is
        ;; [[convert-props]]'s first lane, and wrapping it in an empty
        ;; map was the whole cost of telling it so.
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
        ;; THE HAND-OFF, and the one position the eager codec did not
        ;; reach. Everywhere else a lazy read is forced by the pass that
        ;; turns hiccup into elements; here the map crosses untouched, so
        ;; a seq written in THIS body would be realised inside the
        ;; child's render and attributed to it — silently, and then
        ;; frozen, because a realised `LazySeq` is never walked a second
        ;; time. [[realize-deep]] returns the map by identity and covers
        ;; `:children` in the same pass, which is where the one-level
        ;; flatten leaves a nested seq.
        ;;
        ;; The same pass refuses the one carrier it may not repair — an
        ;; unforced `delay`, whose meaning is precisely that it is not
        ;; forced here. The refusal fires inside THIS body's render, so
        ;; the author who wrote the crossing is the one who sees it.
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
  slot is claimed for MARKUP — it lowers hiccup ([[as-element]]) — so it
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
  "ONE host prop into the emitted object — [[host-element]]'s reducing
  function, [[convert-entry]]'s sibling at the second prop door. Same
  lookups as the native walk: `:key` skipped in-loop, a keyword or symbol
  key answered by its cached [[prop-slot]], a reserved emitted slot never
  written because the props object handed to React has a prototype.

  The arms, in precedence order: `:ref` is React's and crosses untouched; a
  `:callbacks` override applies its declared contract
  ([[re-frame.hicasso.impl.intent/lower-declared-prop]]); a declared
  `:slots` position lowers markup through [[as-element]] and refuses the
  marked form; the class slot takes [[class-names]], the slot's own
  coercion, so the two crossings agree at `className` for every value
  shape; everything else is INFERRED from the spelling by the native
  walk's own classifier ([[re-frame.hicasso.impl.intent/lower-prop]]) and
  then converted shallowly by [[host-prop-value]] — so an intent vector,
  key-map or `h/event` at an `on*` prop lowers exactly as at a native
  tag, an `h/event` at any other prop takes the render wrapper, and a
  vector at a render position crosses as data. The invariant worth
  knowing: the reserved skip sits ABOVE every declaration arm, so no
  declaration can talk the codec into poisoning the prototype. Argument
  in docs/design/hicasso/decisions.md, HD-011's 2026-08-29 addendum."
  [^js head declared slots o k v]
  (cond
    ;; `:key` is React's, and a presence override belongs to a tray —
    ;; neither is a prop the component should see ([[convert-entry]]).
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
  (HD-011). One pass over the attr map through [[host-entry]], `:key`
  extracted onto the element, children lowered hiccup→element in the
  same render window as the props — so a callback lowered at any prop, an
  intent inside a declared slot and an intent inside a child all close
  over the frame of the boundary that wrote the crossing. The element's
  TYPE is the head's `gate` slot, which is where the `:server` policy
  lives ([[mint-host!]]): a gate is not a boundary — no frame, no
  subscription, no body — so HD-020's hook budget is untouched by it.
  Argument in docs/design/hicasso/decisions.md, HD-011."
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
;; `[:> Component props & children]` is HD-011's explicitly SECONDARY
;; form, for the cases a static declaration cannot express: a component
;; selected at runtime, a `memo`/`lazy` value, a component a render prop
;; handed you, a provider an ecosystem library handed you, a one-off
;; migration site. The guide's rule is unchanged — *declare what you use
;; twice* — and bare-head auto-hosting stays rejected.
;;
;; **The model, in one sentence: `[:>]` is `defhost` with the
;; declaration erased, and what erasing the declaration costs you is
;; exactly what the declaration carried.** No author-chosen crossing
;; name; no `:callbacks` contracts, so every prop here is UNCLAIMED; no
;; `:server` policy, so the crossing is hard `:client-only` and that is
;; unspellable; refusals at render time rather than once at a
;; declaration; one generic marker for every crossing instead of a
;; minted identity; and the JS require lands in the view namespace
;; rather than in a `.cljc`-quarantined host one. Every remedy for every
;; one of those is `defhost`. That is the design, not a coincidence.
;;
;; Three things follow, and they are the whole of the mechanism:
;;
;; 1. **The props walk is [[host-entry]], unchanged, with an EMPTY
;;    declared roster.** Not a branch of its own — the same function,
;;    reading a [[raw-crossing]] stand-in for the declaration it does not
;;    have. That is what makes `[:> X …]` → `(defhost x X {})` a
;;    behaviour-preserving rewrite, which is the whole theorem of the
;;    migration codemod. A refusal that is right in
;;    isolation and wrong in composition is wrong: whatever is ruled at
;;    the door, the escape does the same thing.
;;
;; 2. **SSR is hard `:client-only`, through ONE shared module-level
;;    gate** ([[raw-gate]]), reusing the snapshot triple `mint-host-gate!`
;;    uses with the placeholder fixed at `nil`. There is no declaration
;;    to carry a policy and no inline spelling for one — a fallback is
;;    POLICY, and policy lives on declarations. The server emits nothing,
;;    hydration's first client pass emits nothing, and adoption swaps the
;;    component in: server-absent and first-pass-absent are not two facts
;;    kept in step, they are ONE fact and React chooses it. A per-site
;;    placeholder is still reachable with no new escape surface, by
;;    wrapping the escape in a declared host that has one.
;;
;; 3. **The Component value is refused AT THE CROSSING**, in the owner's
;;    render ([[raw-component]]). React's own "Element type is invalid"
;;    is minted at fiber creation, which behind the gate is
;;    post-adoption and client-only, and for any ClojureScript value it
;;    reads *"got: object"* because it names `typeof` — naming nothing
;;    the author wrote.
;;
;; What is NOT reduced: the canonical DOM. The gate contributes no node
;; of its own and `lane/canonical` serialises element and text nodes, so
;; a `[:>]` and a `defhost` on the same component produce the same DOM in
;; every phase. The hiccup data lane is reduced at exactly ONE slot —
;; slot 1 holds a JS value compared by identity — and that is the whole
;; of HD-011's "reduced structural identity". The fiber lane carries one
;; shared gate type named by the constant `"[:>]"`; no name is derived
;; from the component, because React resolves `displayName || name ||
;; null`, Closure renames `.name` under `:advanced`, and foreign
;; production bundles routinely ship without `displayName` — a derived
;; identity would be build-dependent. `defhost` has no such problem
;; because its name is authored DATA. The component's own fiber sits
;; directly beneath the gate and React names it there, so a tree reads
;; `<[:>]>` → `<DatePicker>`: one greppable frame naming the form the
;; author wrote, and the component naming itself one level down.

(def ^:private raw-crossing
  "What [[host-entry]] reads at a `[:>]` prop in place of a declaration:
  a `displayName` for its refusal messages and EMPTY rosters. The escape
  has no `mint-host!` and mints nothing per site — this is one
  module-level value, and `callbacks` is a ClojureScript map because
  that is what the refusals project into their `:declared` ex-data.

  `slots` is empty for the reason every other erasure is empty: a
  ReactNode position is DECLARED, and the escape is the door with the
  declaration erased. Hiccup at a `[:>]` prop is therefore data, and
  `h/as-element` is the per-site spelling that crosses one element
  through it."
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
  "One `[:> Component props & children]` vector as a React element.

  The indices are the door's, shifted by one: the component is at 1, the
  attribute map at 2 when there is one, children from 3 or from 2.
  `[:> Component]` with neither is legal.

  Everything after the component is [[host-element]]'s own body:
  extract `:key` onto the crossing's outer element, and walk every prop
  through [[host-entry]] against an EMPTY declared roster. Children lower
  eagerly, here, inside the render window of the boundary that wrote the
  crossing — so an intent closure in a `[:>]` child captures that
  boundary's frame-locked dispatch and fires into the right frame however
  much later the foreign component renders it.

  The element's TYPE is always [[raw-gate]]; the component rides in the
  carrier's `c` slot.

  **The Component slot is already validated.** [[vector-kind]] runs
  [[raw-component]] as part of answering `:raw`, and [[vec->element]] —
  this fn's only caller, which is why it is private — dispatches on that
  answer. So position 1 is read here rather than re-derived, and the
  escape's grammar is enforced in exactly one place for the runtime and
  for `re-frame.hicasso.test` alike."
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
  "Is this the fragment spelling? Compared with `=`, never `identical?`:
  ClojureScript keyword literals are shared constants only when the build
  interns them, so an identity comparison here works under `:advanced`
  and silently routes every fragment into the native-tag path everywhere
  else."
  [head]
  (= :<> head))

(defn- raw-head?
  "Is this the raw-escape spelling? Compared with `=` for
  [[fragment-head?]]'s own reason, and it is sharper here: a
  [[hiccup-tag?]] accepting any keyword that is not `:<>` would let
  `[:> Foo {}]` ask React for an element literally named `<>`. An
  `identical?` test would work under
  `:advanced`, where the build interns keyword literals, and silently
  route every escape back into that native path everywhere else."
  [head]
  (= :> head))

(defn- hiccup-tag?
  "Is this head a native tag? Asked AFTER the fragment and raw arms in
  [[head-kind]]'s `cond`, which is its only caller — so the
  `(not (fragment-head? head))` this body could re-ask is provably dead,
  and asking it would make every native tag on every page pay the
  fragment `=` twice. Its absence is what pays for [[raw-head?]] exactly:
  a keyword tag pays two `=` and one type predicate either way. This codec has costed a `keyword?`
  short-circuit at ±51–67% of a walk, so a new head test is not free by
  assertion on this surface — it is free by accounting."
  [head]
  (or (keyword? head) (symbol? head) (string? head)))

;; ---------------------------------------------------------------------------
;; The discriminations, each named once
;; ---------------------------------------------------------------------------
;;
;; [[vec->element]] and [[as-element]] each answer a question before they do
;; anything: WHAT KIND of thing is this. Both questions have a second asker.
;; `re-frame.hicasso.test`'s L2 walk records what the runtime WOULD render,
;; as data, and it cannot inspect a React element to find out — so it has to
;; discriminate the author's hiccup itself.
;;
;; A classifier duplicated in a `cond` of its own drifts: `:<>` recorded as
;; an element named `:<>`, a keyword child refused where the runtime renders
;; it as text, a `true` child dropped where the runtime raises — each one a
;; branch that exists twice and agrees once.
;;
;; So each question is asked in exactly one place — here — and its answer is
;; a keyword both callers dispatch on. The arms below keep the COSTED ORDER
;; the walk is tuned to (see [[as-element]]'s accounting); moving a test
;; here moves it for the runtime and the kit together, which is the whole
;; point.
;;
;; Sharing the ANSWERS is not enough on its own, because [[vec->element]]
;; does not only classify: it REFUSES two shapes before and around the
;; classification — an empty vector, which has no head to classify, and a
;; raw escape whose Component slot holds something React will not mint a
;; fiber for. Left to the runtime alone, the kit meets the same two forms
;; and answers with ids of its own: a generic malformed head for `[]`, and
;; an L3 opacity pointer for `[:> :div]` — telling the programmer to go
;; mount, at L3, a form that cannot mount anywhere. Wrong advice, not merely
;; a different id.
;;
;; [[vector-kind]] is where that stops. It is the discrimination WITH the
;; guards that have to pass before there is anything to discriminate, so a
;; malformed vector raises ONE refusal, from one guard, whichever side asked.

(defn head-kind
  "WHICH KIND OF HEAD a hiccup vector has — `:fragment`, `:raw`, `:tag`,
  `:boundary`, `:host` or `:invalid`.

  The head discrimination, named once. [[vec->element]] dispatches on it
  to build a React element; the test kit dispatches on it to build a Spec
  004B node. A head kind that exists here exists for both."
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
  "Interpret one hiccup vector. [[vector-kind]] is the preflight: it has
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
    ;; `:invalid` implies a head, because [[vector-kind]] refused the
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
  "WHICH KIND OF THING a hiccup value in child position is — `:nothing`,
  `:text`, `:markup`, `:splice`, `:true-child`, `:react-element`,
  `:named` or `:foreign`.

  The child discrimination, named once. [[as-element]] dispatches on it
  to build React's child; the test kit dispatches on it to build a Spec
  004B child. Asked separately, the two drift apart over keywords,
  symbols and `true`.

      :nothing        renders nothing — `nil`, `false`
      :text           renders as itself — a string or a number
      :markup         a hiccup vector
      :splice         a seq, whose members splice in document order
      :true-child     `true`, which is a loud error (HD-016)
      :react-element  React's own value, passed through untouched
      :named          a keyword or symbol, which renders as its `name`
      :foreign        anything else, which React is handed as it stands

  **The arm order is [[as-element]]'s costed order and not a taxonomy.**
  `vector?` is `IVector` satisfaction, which for anything without the
  marker falls through to `native-satisfies?`, so the cheap populations
  are asked first — the accounting is in [[as-element]]'s docstring."
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
  "[[as-element]] for a hiccup form written OUTSIDE any boundary body —
  the root, or an outward React bridge that mounts Hicasso from foreign
  code.

  Every other element in the tree is created by an ancestor body, which
  is already running inside
  [[re-frame.hicasso.impl.intent/with-frame]]. This is the one
  creator that is not, so it is the one creator that has to NAME the
  frame — which an outward bridge takes explicitly anyway. Binding it
  here rather than in the package's mount keeps the reason next to the
  mechanism.

  `*dispatch*` is deliberately NOT bound: the frame is an identity the
  root genuinely has, while a frame-locked dispatch is what makes an
  intent vector legal, and an intent written outside a boundary stays the
  loud `:rf.error/hicasso-intent-outside-boundary` it was."
  [frame-kw hiccup]
  (binding [intent/*frame* frame-kw]
    (as-element hiccup)))

;; ---------------------------------------------------------------------------
;; THE OUTWARD BRIDGE — the codec's other half
;; ---------------------------------------------------------------------------
;;
;; Every other function in this file ENCODES: a hiccup form goes in and a
;; React element comes out. The bridge is the one place the traffic runs
;; the other way — a React parent holds a props OBJECT and wants a
;; Hicasso element — so the decode belongs here, beside the vocabulary it
;; is the inverse of, and nowhere else.
;;
;; **The bridge mints no crossing of its own.** It converts the props and
;; then calls [[vec->element]], which is the same entry every hiccup
;; vector in every body already goes through. That is what makes "one
;; props/children ABI" (native-boundary law, clause 5) true by
;; construction rather than by inspection: there is no second element
;; builder to keep in step, `rfProps` is written in exactly one place
;; ([[boundary-element]]), and the bridge inherits every refusal the codec
;; already raises at the coordinates the complaint register already
;; records — a head outside the closed set refuses as
;; `:rf.error/hicasso-bad-head` from [[vec->element]], from out here as
;; from a body.

(def ^:private slot-keys
  "The three React spellings read back to the hiccup key they came from.

  [[re-frame.hicasso.impl.slot/prop-name]] is deliberately NOT injective
  — `:class`, `:className`, `\"class\"` and `:x/class` are one slot — so
  an inverse has to CHOOSE, and it chooses the spelling the guide
  teaches. `className` therefore arrives at a body as `:class`, which is
  the key that body's author would have written."
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
  "React's `children` slot, in the CONTAINER [[realize-children]] hands a
  hiccup body: a vector, or nil when there are none.

  React's slot is not one shape but three — absent for no children, the
  child ITSELF for one, a JavaScript array for several — and that shape
  is React's calling convention rather than a value an author asked for.
  Copied through unchanged it would make `:children` mean something
  different depending on how many the parent wrote, so
  `(into [:ul] (:children props))` would work at two children and throw
  at one. One spelling has to mean one thing, and the thing it already
  means on the hiccup side is a vector.

  The members are not touched: each child crosses by identity, exactly
  as every other prop value does, and nothing here walks INTO one. So a
  nested array — JSX's `<ul>{head}{rows}</ul>` — stays a single member
  rather than splicing, which is the boundary this normalisation stops
  at on purpose: React flattens it at render, so it renders correctly as
  a member, and flattening it here would be the second level of walk
  that [[outward-props]] refuses everywhere else.

  An EMPTY array answers nil, so a parent whose dynamic list came back
  empty leaves `:children` absent rather than present-and-empty —
  [[realize-children]]'s own answer for `(for [x []] …)`, and the
  difference a body sees as `(when-some [cs (:children props)] …)`."
  [c]
  (if (array? c)
    (when (pos? (alength c)) (vec c))
    [c]))

(defn- outward-props
  "A React props object, decoded into the ordinary ClojureScript props map
  a boundary body destructures. Shallow, by identity, own properties
  only — the mirror of what [[boundary-element]] does on the way out,
  and no deeper for the same reason: the values are the parent's, and
  converting them would be the deep conversion HD-011 refuses in the
  other direction.

  React's children arrive at the `children` slot and therefore land at
  `:children`, which is where [[boundary-element]] puts a hiccup body's
  children too — one spelling, whichever side of the bridge filled it,
  and by [[outward-children]] one SHAPE as well."
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
  parent (UIx or plain JavaScript) renders without a second root, a
  second frame, or a sight of the internal `rfProps` ABI.

      (def article-card* (h/as-component article-card))
      ;; on the React side: <ArticleCard articleId={7} />

  The parent's props arrive as the view's ordinary props map (`prop-key`
  on each slot, values by identity) and React's `children` at `:children`
  as the vector a hiccup caller's would be (`outward-children`). The
  element is built by `vec->element` from `[head props]`, so the view
  keeps its memo wrapper, its reads, its teardown and its refusals; the
  frame is the surrounding React context's, and outside every Hicasso
  root the shell refuses with `:rf.error/no-frame-context`. Neither
  `*frame*` nor `*dispatch*` is bound — this is not a boundary body, so
  an intent vector in a `[:div]` handed through here stays the loud
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
  "Empty both codec caches. The prop cache keeps its three seeded entries,
  because those are the rule and not a memo of one — and it re-seeds
  through [[seed-prop-cache!]], the same one the `def` uses, so a
  suite's `:each` fixture cannot leave the cache holding a different
  spelling from a cold build's."
  []
  (doseq [k (js/Object.keys tag-cache)] (js-delete tag-cache k))
  (doseq [k (js/Object.keys prop-cache)] (js-delete prop-cache k))
  (seed-prop-cache! prop-cache)
  nil)
