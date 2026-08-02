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
  ratoms, and the scheduler. None of them is here, none of them is
  reachable from here, and the codec requires nothing from a donor.

  The argv-equality memoization was on that list until rf2-2rtt6.52.
  HD-006 held that narrow updates come from boundary placement and that
  every default comparison is a cost every render pays — and it
  pre-registered its own reopen condition, keyed to broad-witness
  evidence. The tier-1 roster produced it: a page-chrome write re-ran the
  page and all 300 card boundaries beneath it with every card's inputs
  value-equal. So a value-equality bail-out is now the boundary
  **default**, as one stable internal memo wrapper per head
  ([[memoize-boundary!]]). It is a comparison of a boundary's props map
  and nothing else — the element and prop-object caches HD-004 refuses
  are still refused, and still absent below.

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
    prop-cache   \"on-click\"      -> PropSlot

  A [[PropSlot]] is the React name the cache always held plus the three
  classifications that are pure functions of the same literal — reserved
  slot, event position, ref slot (rf2-y1jkm). Same keys, same lifetime,
  same guard; one lookup now answers everything the per-prop walk used
  to re-derive per element per render.

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

  ## The one behaviour emission adds (rf2-fki5d)

  Everything else here translates what the author wrote. A controlled
  `<input>` or `<textarea>` gets one thing more: its change handler is
  wrapped so the field converges against the model **inside the discrete
  event, with the caret where the edit left it** — the half neither
  React nor UIx's port gives on its own (rf2-n3dxw).

  It belongs at emission rather than in a boundary because that is what
  makes it free at the authoring surface: the view writes an ordinary
  `:value` / `:on-input` pair, nothing is added to the boundary shell,
  and HD-020's ≤2-hook budget is untouched. See
  [[re-frame.bench.hicasso.front.controlled]], which owns the mechanism,
  its guards and its one dependency.

  ## The component ABI (HD-016)

  | Head | Props | Children | `:key` |
  |---|---|---|---|
  | Native tag | attr map | trailing forms; seqs realized once and flattened one level; `nil`/`false` render nothing, `true` errors | `:key` in the attr map |
  | Boundary (a marked `defview` product) | one props map, every lazy sequence in it realized and every unforced `delay` in it refused ([[realize-deep]]) | trailing forms as `(:children props)`, a realized vector | `:key` in the props map, extracted before the body sees props |
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
  the K5 sense.

  ## One canonical slot, and every rule asks it

  This codec accepts a prop key written as a keyword, a string, a symbol
  or a namespaced keyword, in kebab or in camel, and emits them all under
  **one** React name. So every rule about *which* attribute a value is —
  the owned-literal deny, the two structural exclusions, the `:ref`
  reservation, and the ref position's exclusion from intent lowering —
  is asked of [[canonical-slot]], the slot the value will actually be
  emitted into, and never of the key it happened to be written as. A rule
  written against the raw key is a rule that `\"key\"`, `:x/ref` and
  `:onInput` walk straight past."
  (:require [clojure.string :as str]
            [re-frame.bench.hicasso.front.controlled :as controlled]
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

(defn- reserved-name?
  "Is `n` — a tag or prop name, or an emitted prop slot — one of the
  three names that must never be read from, written to, or emitted
  through a JS-object cache? A hiccup literal named `__proto__`,
  `prototype` or `constructor` would otherwise reach an object's
  prototype. That roster is the whole predicate.

  Three `===` string compares rather than the set lookup this began as
  (rf2-y1jkm): the question is asked once per tag and once per prop on
  every element of every mount, and a set lookup pays a string hash each
  time for a roster of three — 36.9 ns against 9.6 on the census page's
  own literals, measured by `walk_profile_app`'s micro table."
  [n]
  (or (identical? "__proto__" n)
      (identical? "prototype" n)
      (identical? "constructor" n)))

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
    (if (reserved-name? k)
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

(def ^:private react-renames
  "The three attribute names React spells differently from HTML. They are
  the RULE rather than a memo of one, so they hold for every spelling of
  the same attribute: `:class`, `:className`, `\"class\"` and `:x/class`
  all name the one slot React calls `className`."
  {"class" "className" "for" "htmlFor" "charset" "charSet"})

(defn prop-name
  "The React prop name for a hiccup prop key — which, because it is the
  name the codec emits the value under, is that key's CANONICAL SLOT.
  `:on-click` → `\"onClick\"`; `:aria-label` and `:data-index` pass
  through; a `--custom-property` is preserved verbatim; a string is
  already a React name and is taken verbatim apart from the three renames
  above.

  **A pure function of the key**, which is what [[canonical-slot]] rests
  on. A slot that depended on what the build happened to have converted
  earlier would make the owned-literal law depend on render order."
  [k]
  (let [n (name k)]
    (or (react-renames n)
        (if (or (string? k) (str/starts-with? n "--"))
          n
          (let [[start & parts] (str/split n #"-")]
            (if (dont-camel-case start)
              n
              (apply str start (map capitalize parts))))))))

(deftype PropSlot [js-name reserved? event? ref?]
  ;; What the prop cache holds for one prop literal (rf2-y1jkm): the React
  ;; name the codec always cached, PLUS the three classifications the
  ;; per-prop walk used to re-derive per element per render — is the
  ;; emitted slot reserved, is the position an event position, is it the
  ;; ref slot. Each is a pure function of the literal's NAME, so caching
  ;; them beside the name changes what a lookup ANSWERS and nothing about
  ;; when it is valid: there is still exactly one entry per distinct
  ;; literal, minted on first sight, correct for the life of the build.
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
  (let [js-name (prop-name k)]
    (->PropSlot js-name
                (reserved-name? js-name)
                ;; asked of the NAME, not of `k` — a symbol shares the
                ;; entry, and `event-prop?` already answers a boolean
                (intent/event-prop? n)
                ;; "ref" — [[ref-slot]], spelled literally because the def
                ;; sits below; `identical?` on primitive strings is value
                ;; comparison.
                (identical? "ref" js-name))))

(def ^:private prop-cache
  ;; The three seeded entries are the RULE, not memos of one — see
  ;; [[react-renames]]. None is reserved, an event position, or the ref
  ;; slot.
  (doto #js {}
    (unchecked-set "class" (->PropSlot "className" false false false))
    (unchecked-set "for" (->PropSlot "htmlFor" false false false))
    (unchecked-set "charset" (->PropSlot "charSet" false false false))))

(defn- prop-slot
  "The [[PropSlot]] for keyword/symbol `k` with name `n` — the caller has
  already checked the spelling and that `n` is not a reserved name."
  ^PropSlot [k n]
  (if (own-key? prop-cache n)
    (unchecked-get prop-cache n)
    (let [s (mint-slot k n)]
      (unchecked-set prop-cache n s)
      s)))

(defn cached-prop-name
  "[[prop-name]] behind the codec-work cache (HD-004).

  **Only a keyword or a symbol is cached**, which is the donor's shape and
  is load-bearing here. The cache is keyed by `(name k)` while
  [[prop-name]] answers a STRING differently from the keyword of the same
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
    (if (string? k) (prop-name k) k)
    (let [n (name k)]
      (if (reserved-name? n)
        (prop-name k)
        (let [^PropSlot s (prop-slot k n)]
          (.-js-name s))))))

;; ---------------------------------------------------------------------------
;; The canonical structural-slot filter (rf2-2rtt6.36)
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
  [[re-frame.bench.hicasso.front.presence]] asks THIS, and never the key
  it was written as."
  cached-prop-name)

(def structural-slots
  "The two React slots that address the ELEMENT rather than its
  attributes. `key` is React's own identity contract and `ref` is
  HD-016's node handle; neither is an attribute, and neither is ever
  taken from a map that is *about* attributes — a `:&` remainder
  ([[merge-caller]]) or a presence phase override
  ([[re-frame.bench.hicasso.front.presence/with-phase]]).

  Held as canonical SLOTS rather than as keys, because the spelling is
  exactly what a hostile or careless map would vary."
  #{"key" "ref"})

(def ^:private ref-slot "ref")

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

(defn- denied-slots
  "The slots a `:&` remainder may not reach: the two structural ones, plus
  the slot of every literal the element wrote. One set, so one rule states
  both halves of the owned-literal law ([[merge-caller]])."
  [owned]
  (reduce-kv (fn [denied k _] (conj denied (canonical-slot k))) structural-slots owned))

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
  every downstream bail-out that compares handler identity.

  `string?` is asked first (rf2-y1jkm): a string is the overwhelming prop
  value on a census page — `href`, `class`, `data-testid`, `src`, `type`
  — and it previously proved itself *not* a fn, map, keyword, symbol or
  collection on its way to `:else`, two of those being the dear
  native-satisfies? protocol checks. One `typeof` answers it; every other
  branch pays that one `typeof` and keeps its old order, so the answer is
  unchanged for every input."
  [v]
  (cond
    (string? v)              v
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

  **The law is enforced on the CANONICAL SLOT, never on the map key.**
  The deny set is [[structural-slots]] seeded with the slot of every
  literal the element writes, so one rule says both halves of the law:
  nothing in a remainder may reach `key` or `ref`, and nothing in a
  remainder may reach a slot an owned literal already claims — however
  either is spelled. Without that, `:onInput` against an owned
  `:on-input` survives the merge as a distinct map key, both land on
  React's one `onInput` slot, and which handler wins is decided by the
  order the props map happens to iterate in. An unconditional law cannot
  be map-order-dependent, so the check is the emitted slot
  ([[canonical-slot]]) or it is not a law.

  Absent — the overwhelming case — this is one `contains?` and the map
  comes back by identity, allocating nothing. Present, it is one filter
  and one `merge` that, the filter having run, is a plain union."
  [props]
  (if-not (contains? props merge-key)
    props
    (let [caller (get props merge-key)
          owned  (dissoc props merge-key)]
      (cond
        (nil? caller) owned
        (map? caller) (merge (without-slots caller (denied-slots owned)) owned)
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
  than from a ref that never fires.

  Called from inside [[convert-props]]'s single walk, at the position
  whose CANONICAL SLOT is `ref` — not at the key `:ref`. This codec
  deliberately accepts string, symbol and namespaced prop spellings and
  emits them all under one React name, so a check that reads `(:ref
  props)` is a check `\"ref\"` and `:x/ref` walk past, on their way to
  React with the opaque value the reservation exists to stop."
  [k v]
  (when (vector? v)
    (fail! :rf.error/hicasso-ref-vector-reserved
           'front.codec/convert-props
           (str "A vector at " (pr-str k) " is RESERVED and is not a v0 surface. "
                "`{:ref [registered-id config]}` is the reserved spelling for "
                "registered node ownership; v0 accepts a callback ref (a "
                "function) only. Write the function, or move the mechanic to "
                "an event and an effect.")
           :use-a-callback-ref-or-an-effect
           {:ref v :position k}))
  v)

(defn- convert-entry
  "ONE prop into the emitted object — [[convert-props]]'s reducing
  function, hoisted as a named var so the walk allocates no closure (the
  same accounting as [[realize-entry]]).

  The literal `:key` is skipped here — the in-loop form of the `dissoc`
  the walk used to pay a map copy for; every other spelling flows
  through, exactly as it survived the `dissoc`. A keyword or symbol key
  is answered by its [[prop-slot]] — name and classification in one
  lookup, `event?` gated on `keyword?` because a symbol is never an
  event position — and a value that nothing claims (not a ref, not a
  lowerable value at an event position, not a marked callback) goes
  straight to [[convert-prop-value]] without entering the lowering at
  all, which is the branch nearly every attribute on a census page
  takes. The paths [[intent/lower-prop]] IS entered on reproduce its
  answers by construction: it re-asks `event-prop?` and re-takes the
  same branch this slot was minted from. A string key keeps the donor's
  uncached path, byte for byte."
  [o k v]
  (if (keyword-identical? :key k)
    o
    (if (or (keyword? k) (symbol? k))
      (let [n (name k)]
        (if (reserved-name? n)
          o
          (let [^PropSlot s (prop-slot k n)]
            (when-not (.-reserved? s)
              (unchecked-set o (.-js-name s)
                             (convert-prop-value
                               (cond
                                 (.-ref? s)
                                 (check-ref! k v)

                                 (and (.-event? s) (keyword? k))
                                 (if (or (vector? v) (map? v) (fn? v))
                                   (intent/lower-prop k v)
                                   v)

                                 :else
                                 (if (intent/callback? v)
                                   (intent/lower-prop k v)
                                   v)))))
            o)))
      (let [n (cached-prop-name k)]
        (when-not (reserved-name? n)
          (unchecked-set o n (convert-prop-value
                              (if (= ref-slot n)
                                (check-ref! k v)
                                (intent/lower-prop k v)))))
        o))))

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
  caller's classes instead of one silently replacing the other.

  The `:ref` reservation and the ref position's exclusion from intent
  lowering are both taken on the CANONICAL SLOT the walk has just
  computed, rather than on the key — which is what makes them hold for
  `\"ref\"` and `:x/ref` as well as for `:ref`, and costs the walk one
  comparison it already had the value for.

  ## The three lanes (rf2-y1jkm)

  The walk-cost profile (`walk_profile_app`, census page: 1,202
  elements, 567 of them with no attribute map, 924 with a `.class`
  shorthand, 71 with a declared `:class`) priced this function at 67.5%
  of the whole interpreter walk, most of it the map surgery
  [[merge-shorthand]] performs on elements whose only class IS the
  shorthand. So the general path keeps its exact shape and two lanes
  peel off the shapes that dominate a page, each producing the same
  emitted object the general path produces, property for property and in
  the same order:

  1. **No attribute map at all** (`props` nil): the emitted object is
     exactly the shorthand's `id`/`className`, so it is built directly.
     What the general path would do — an empty merge, the shorthand
     folded into a fresh map, one map iteration converting it back out —
     is the identity of that, at the cost of the map machinery.
  2. **Shorthand class, nothing merged against it** — no literal
     `:class`/`:className` (the two keys [[merge-shorthand]] itself
     consults), no `:&`, no `#id` shorthand: the merged class is the
     shorthand string verbatim (`class-names` of a string against nil),
     and the general path's re-`assoc` puts `:class` LAST in the map, so
     emitting the loop first and the shorthand's `className` after it
     writes the same slots in the same order — including the overwrite
     order for an exotic spelling (`:x/class`) that canonicalises onto
     the same slot.
  3. **Everything else** — a declared class, a `:&` remainder, an `#id`
     — takes the donor's path unchanged.

  React's own `key` contract: the LITERAL `:key` is dropped in-loop (one
  keyword-identity test) rather than by a `dissoc` that copies the map;
  any other spelling lands in the emitted props exactly as it always
  did, and [[native-element]] still reads the literal `:key` off the
  original map.

  Per prop, one [[prop-slot]] lookup answers the React name AND the
  classifications the walk used to re-derive per element — reserved slot,
  event position, ref slot — so a non-event prop no longer pays
  [[re-frame.bench.hicasso.front.intent/event-prop?]]'s regex, and only
  a lowerable value at an event position (a vector, a map, a function)
  enters [[re-frame.bench.hicasso.front.intent/lower-prop]] at all. The
  slot's `event?` flag is gated on `keyword?` at the call site — a
  symbol shares the cache entry but is not an event position — and a
  string-keyed prop takes the donor's uncached path unchanged."
  [props ^ParsedTag parsed]
  (cond
    (nil? props)
    (let [o #js {}]
      (when-some [id (.-id parsed)] (unchecked-set o "id" id))
      (when-some [c (.-className parsed)] (unchecked-set o "className" c))
      o)

    (and (some? (.-className parsed))
         (nil? (.-id parsed))
         (not (contains? props :class))
         (not (contains? props :className))
         (not (contains? props merge-key)))
    (let [o (reduce-kv convert-entry #js {} props)]
      (unchecked-set o "className" (.-className parsed))
      o)

    :else
    (reduce-kv convert-entry #js {} (-> props merge-caller (merge-shorthand parsed)))))

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

(defn- boundary-props=
  "React's `areEqual` for a memoized boundary — CLJS `=` over the complete
  `rfProps` value, which is Reagent's argv compare spelled on the one slot
  a boundary's props ever occupy.

  [[boundary-element]] hands every boundary a fresh `#js {\"rfProps\" …}`,
  so `Object.is` — what `React.memo` compares with when given no
  comparator — is false on every re-render and a bare memo would never
  once bail out. What has to match is the ClojureScript map inside, and
  `=` on it opens with an `identical?` test: a row whose parent passed the
  same value re-uses it and the comparison costs one pointer.

  **Every prop, including function-valued ones**, which compare
  conservatively unequal — correct, because distinct functions must not
  bail out. So a props map carrying a closure minted in the parent's
  render re-renders, which is the safe direction and the one Reagent's
  `shouldComponentUpdate` errs in too.

  **Fails OPEN, and that polarity is a ruling rather than a taste
  (rf2-5al9d7).** `=` over an app-owned value can throw — a type with a
  throwing `-equiv`, a foreign object mutated in place — and this runs
  inside React's comparator, where an escaping throw is a render crash and
  not a slow render. reagent-slim met the identical hazard on the
  identical comparison and ruled: stock Reagent fails CLOSED (skips), we
  fail OPEN (render), because skipping on a failed comparison risks a
  stale UI and an extra render is always the safe branch. `areEqual`
  inverts that polarity, so failing open here is answering **false**.

  **This is the incumbent's shape, not an invention.** Reagent 2.0.1's
  `functional-render-memo-fn` is a `React.memo` `areEqual` doing `=` over
  the whole argv inside a `try` that answers `false` on a throw — the same
  comparison, the same guard and the same polarity. UIx 1.4.4's
  `uix.core/memo` compares `argv` plus `:children`; `rfProps` already
  carries `:children`, so the compared value matches without a special
  case. Reagent's `*always-update*` dynamic escape is declined: it exists
  so `force-update-all` can bypass the comparison for hot reload, and
  re-evaluating a `defview` here re-mints the head and its wrapper — a new
  React element *type*, which HMR replaces outright."
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
  "Give a marked head **one stable internal memo wrapper**, and return the
  head — still the function it was.

  ## Why the wrapper is internal (HD-006 as amended, rf2-2rtt6.52)

  A value-equality bail-out is the boundary DEFAULT: without one, a write
  moving a key the PAGE reads re-rendered the page and then all 300 card
  boundaries beneath it, every card's props and every card's subscription
  values equal. React re-renders the children of a re-rendered parent
  unless the element is referentially identical (a `for` builds fresh
  ones) or the component bails out itself, and a plain function component
  cannot bail out itself.

  But `React.memo` returns a memo **object**, not a function, and a minted
  head is required to BE a function — so the wrapper may not become the
  public representation. It is attached to the head instead, minted once
  at definition, and [[boundary-element]] creates elements from it. The
  head a `defview` hands back is unchanged, `boundary-head?` still asks
  `fn?`, and no memo object escapes.

  **Stability is the whole contract.** One wrapper per head, minted here
  and never per element: a fresh wrapper per render would be a fresh React
  element *type* every time, and React would unmount and remount the
  entire subtree rather than bail out of it.

  Opt-in at the mint site rather than applied to every marked head, so
  heads that are not reactive boundaries — `h/boundary`'s error-boundary
  class, presence — keep the semantics they were written with."
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

;; A map entry is TWO reachable positions, not one. Keys were skipped
;; here until rf2-2rtt6.32 on the argument that hashing a seq realises
;; it, so nothing unrealised can already be a key — and that argument is
;; wrong twice. A `delay` hashes by object identity (cljs.core extends
;; `IHash` on `default` to `goog/getUid`), so hashing never forces one;
;; and a small map literal is a `PersistentArrayMap`, which compares keys
;; with `=` against the entries already accumulated and hashes nothing at
;; all, so the first key of a one-entry map is never even compared. Both
;; positions therefore go through the same walk.
;;
;; The `keyword?` short-circuit is not a special case, it is the same
;; allocation-and-predicate accounting as the two named vars above, and
;; it is worth as much. A `Keyword` is neither a collection nor a
;; `Delay`, so [[realize-deep]] on one is a provable no-op — but proving
;; it costs `coll?`, which for anything without the `ICollection` marker
;; falls through to `native-satisfies?` and is the dearest predicate on
;; the path. `keyword?` is one `instanceof`. Prop-map keys are keywords
;; essentially always, and skipping the no-op for them is the difference
;; between the key half costing +51–67% of the walk and costing almost
;; nothing (rf2-2rtt6.32, table in [[realize-deep]]).
(defn- realize-entry [_ k x]
  (when-not (keyword? k) (realize-deep k))
  (realize-deep x)
  nil)
(defn- realize-item  [_ x]   (realize-deep x) nil)

(defn- refuse-deferred!
  "The one thing the crossing walk **refuses** rather than repairs.

  A `delay` is an author's explicit statement that a computation happens
  *later*, and the codec may not overrule it: forcing it at the hand-off
  would be the opposite of what a `delay` is for, and would change the
  meaning of the author's program to protect a property the author has
  not been told about. Refusing costs nothing and states the problem.

  The refusal is raised **inside the render of the body that wrote the
  `delay`**, because [[realize-deep]] runs at the crossing — so the stack
  lands on the author's own call site rather than on the child that would
  otherwise have been blamed. That is the attribution a query name would
  have bought, obtained without forcing anything to learn the name.

  Only an **unforced** delay is refused. One the author already deref'd
  in their own body carries a computed value, derefs to it without
  calling anything, and is harmless wherever it goes."
  [v]
  (fail! :rf.error/hicasso-deferred-read-at-boundary
         'front.codec/boundary-element
         (str "An unforced `delay` reached a boundary's props. It would be "
              "forced inside the CHILD's render, so any subscription it reads "
              "becomes the child's edge, is cached by the delay, and is then "
              "dropped the next time the child renders — a value correct on "
              "screen, frozen thereafter, and attributable to nothing. Hicasso "
              "will not force it for you: that would change what your `delay` "
              "means. Hand a FUNCTION instead — the child calls it on every "
              "render, so its reads are the child's edges and are kept — or "
              "deref the delay in the body that wrote it.")
         :hand-a-function-or-deref-it-in-this-body
         {:value v}))

(defn realize-deep
  "Force every lazy sequence reachable from `v`, refuse any unforced
  `delay` reachable from it, and return **`v` itself, by identity**.

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
  the walk allocates no `MapEntry`; **both** halves of an entry are
  descended into, because a map entry is two reachable positions and the
  invariant above is about reach, not about position. `coll?` is tested
  before `map?` so a scalar — the overwhelming case — costs exactly one
  predicate.

  ### What the key half costs (rf2-2rtt6.32)

  Not nothing, and it was measured rather than assumed. Three walks
  A/B/C'd in one process on an otherwise idle box, rounds interleaved,
  best of seven per round, four whole repetitions. A is the value-only
  walk this replaced, B walks keys unconditionally, C is B with the
  `keyword?` short-circuit above and is what ships.

  **All three arms are written in the measuring namespace, including the
  one that ships**, and that is not fussiness. Timing two local arms
  against `realize-deep` itself compares an inline `(throw (ex-info …))`
  with a call to [[refuse-deferred!]] as much as it compares anything
  about keys, and it reported the shipping arm 9–20% *faster* than a
  walk doing strictly less work — an impossible result, and the only
  reason the confound was caught.

  | shape | B vs A | C vs A |
  |---|---|---|
  | the dogfood row's props | +59%, +57%, +67%, +51% | +4%, +2%, +1%, +18% |
  | the same plus two hiccup children | +28%, +20%, +20%, +24% | +7%, +3%, +0%, −2% |
  | a 100-row collection prop | +56%, +40%, +47%, +49% | +7%, +1%, +2%, +3% |

  Against the whole boundary element build, measured in the same process:
  **B adds 7.6–9.9% of it, C adds 0.2–2.8%.** The unconditional walk is a
  real cost at the shape that matters most, and one predicate removes it
  — the dear part was never the traversal, it was proving that a keyword
  is not a collection.

  Two instruments, one denominator: the element build reads 1.08–1.16 µs
  here, within a few percent of the 1,089 ns in the table above, while
  the walk itself reads 148–177 ns against that table's 69 ns. They agree
  on what an element costs and not on what the walk inside it costs, so
  the rows above are quoted as ratios and the absolute figures are not
  carried forward.

  The 100-row collection prop is the honest ceiling, every element of it
  a map and so every element two positions rather than one; it is still
  the position whose eagerness costs far less than the NATIVE prop
  position's `clj->js` beside it.

  A seq of unbounded length at a boundary prop position now diverges here
  rather than in the child. That is the same thing `clj->js` already does
  to one at a native prop position, and it must be: a deferred read
  cannot be both unbounded and attributable.

  ## What it forces, and the one thing it refuses

  A lazy sequence is **structure**, and structure is what a codec walk is
  entitled to force: forcing it changes nothing an author could observe,
  because the seq was going to be walked one boundary later regardless.
  A `delay` is not structure. It is an explicit deferral, and the whole
  of its meaning is *not now* — so the walk may not force it, and
  [[refuse-deferred!]] says so instead. That is the entire difference
  between the two carriers, and the reason one is repaired silently and
  the other cannot be.

  The check costs one `instanceof` per non-collection node, on the branch
  that already exists for scalars, and it is never reached for anything
  the walk descends into. `realized?` narrows it further: a `delay` the
  author already deref'd in their own body is a computed value and passes
  through.

  **Its reach is the walk's reach, and no further.** The walk descends
  into data structures; a *mutable reference* is not one, and is not
  descended into. A deferral an author parks in an atom — or in a
  module-level var the codec never sees — is outside what any structural
  pass can reach, and is a declared limit rather than a repair this
  function withheld."
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

(defn- native-element
  "One native tag as a React element.

  [[re-frame.bench.hicasso.front.controlled/install!]] is the only thing
  here that is not a translation of what the author wrote. It runs on
  the converted props, because the condition it tests is about the
  EMITTED element — a controlled `value`, a change handler, a type with
  a caret — and those are canonical slots rather than spellings. It is
  one JS `switch` on the tag for every element that is not an `:input`
  or a `:textarea`, which is nearly all of them. rf2-fki5d."
  [argv]
  (let [parsed      (cached-parse (nth argv 0))
        has-props?  (props-map? argv 1)
        props       (if has-props? (nth argv 1) nil)
        ;; nil, not `(or props {})` — the absent attribute map is
        ;; [[convert-props]]'s first lane, and wrapping it in an empty
        ;; map was the whole cost of telling it so (rf2-y1jkm).
        js-props    (convert-props props parsed)]
    (controlled/install! (.-tag parsed) js-props)
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
        ;;
        ;; The same pass refuses the one carrier it may not repair — an
        ;; unforced `delay`, whose meaning is precisely that it is not
        ;; forced here. The refusal fires inside THIS body's render, so
        ;; the author who wrote the crossing is the one who sees it.
        ;; rf2-2rtt6.32.
        body-props (realize-deep (cond-> (dissoc props :key)
                                   children (assoc :children children)))
        js-props   #js {"rfProps" body-props}]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (react/createElement (element-type (nth argv 0)) js-props)))

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
  (unchecked-set prop-cache "class" (->PropSlot "className" false false false))
  (unchecked-set prop-cache "for" (->PropSlot "htmlFor" false false false))
  (unchecked-set prop-cache "charset" (->PropSlot "charSet" false false false))
  nil)
