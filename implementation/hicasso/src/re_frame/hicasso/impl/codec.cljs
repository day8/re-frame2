(ns re-frame.hicasso.impl.codec
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
  `__rfArgv` crossing, and the adapters' reserved-head and keyword-prop
  diagnostics (public-boundary policy for a shipped adapter; this codec
  has no public boundary, and the pre-alpha stance is to trust the
  programmer). `defhost` — HD-011's taught door — is NOT absent any
  more: [[mint-host!]] is the declaration and the host head is the
  fourth element class, §Host heads below (rf2-2rtt6.65). Nor is the
  `[:>]` raw escape, which HD-011 keeps explicitly secondary and which
  is now built as [[raw-element]] — the same crossing with the
  declaration erased (rf2-2rtt6.103).
  Neither is HD-011's SSR placeholder, which HD-020(d) left inert until
  the operator ruled SSR into scope: `:ssr` is a declaration option
  with three values (rf2-2rtt6.85, rf2-l0wfx). For the two gated ones
  [[mint-host-gate!]] is the one mechanism that serves the server
  render, hydration's first client pass and a fresh `createRoot` mount
  alike; `:ssr :render` mints no gate and renders the component itself
  server-side, which is the only policy under which a crossing's
  CHILDREN reach the server response at all.

  ## Codec-work caching only (HD-004)

  Two caches, both keyed by the author's literal and both JS objects
  with **no prototype** ([[empty-cache]]), so a hiccup tag or prop
  literally named `toString` cannot be served an inherited value and one
  named `__proto__` cannot poison a write — the first structurally, the
  second by a refusal on the miss branch:

    tag-cache    \"div#main.wide\" -> ParsedTag
    prop-cache   \"on-click\"      -> PropSlot

  A [[PropSlot]] is the React name the cache always held plus the four
  classifications that are pure functions of the same literal — reserved
  slot, event position, ref slot, class slot (rf2-y1jkm, rf2-2rtt6.36).
  Same keys, same lifetime, same guard; one lookup now answers everything
  the per-prop walk used to re-derive per element per render.

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
  [[re-frame.hicasso.impl.controlled]], which owns the mechanism,
  its guards and its one dependency.

  ## The component ABI (HD-016)

  | Head | Props | Children | `:key` |
  |---|---|---|---|
  | Native tag | attr map | trailing forms; seqs realized once and flattened one level; `nil`/`false` render nothing, `true` errors | `:key` in the attr map |
  | Boundary (a marked `defview` product) | one props map, every lazy sequence in it realized and every unforced `delay` in it refused ([[realize-deep]]) | trailing forms as `(:children props)`, a realized vector | `:key` in the props map, extracted before the body sees props |
  | Host (a `defhost` declaration — HD-011) | attr map: declared `:callbacks` slots lowered by their DECLARED contract, `:ref` a callback ref (HD-022's vector refusal holds here), the class slot coerced and composed by [[class-names]] exactly as at a native tag (rf2-2rtt6.119), an `h/fn` at any slot none of those claimed REFUSED (rf2-2rtt6.116), everything else converted shallowly ([[host-prop-value]]) | trailing forms converted hiccup→element, handed to the foreign component as React children | `:key` in the attr map |
  | Fragment `[:<> …]` | optional attr map | trailing forms | on the fragment's props map |

  A React element is a legal child anywhere. No metadata keys, no second
  calling convention, and a plain function in head position is a loud
  error.

  ## Three reserved attribute keys, and nothing else

  | Key | Meaning |
  |---|---|
  | `:&` | the caller's remainder map. One merge, one law: **the literal keys written in the map always win** (HD-023, [[merge-caller]]) |
  | `:ref` | a callback ref in v0; a **vector** is the reserved data spelling and is refused loudly (HD-022, [[check-ref!]]) |
  | `:re-frame.hicasso/revision` | authoring.md's `::h/revision` — a controlled text element's reset trigger. Read off the author's own PRE-MERGE map in [[native-element]], never emitted as an attribute, never reachable from a `:&` remainder ([[revision-key]]) |

  *Two columns; three body rows; hand-counted.*

  All three are attribute *keys*, not forms — so the merge survives into a
  structural test and into tooling, and none adds a public concept in the
  K5 sense. (K5 — the ergonomics kill criterion — was removed by operator
  ruling on 2026-08-04; this records the reason the shape was chosen, not
  a live gate.)

  ## One canonical slot, and every rule asks it

  This codec accepts a prop key written as a keyword, a string, a symbol
  or a namespaced keyword, in kebab or in camel, and emits them all under
  **one** React name. So every rule about *which* attribute a value is —
  the owned-literal deny, the two structural exclusions, the `:ref`
  reservation, and the ref position's exclusion from intent lowering —
  is asked of [[canonical-slot]], the slot the value will actually be
  emitted into, and never of the key it happened to be written as. A rule
  written against the raw key is a rule that `\"key\"`, `:x/ref` and
  `:onInput` walk straight past.

  **The rule itself is not in this file** (rf2-ani6y). It is
  [[re-frame.hicasso.impl.slot/prop-name]], in `.cljc`, because
  the `[:>]` migration codemod decides the same slots on the JVM and a
  reimplementation there would be the codemod's own defect class turned
  inward: a divergence nothing pins, failing silently. What stays here is
  the CACHING of its answers — [[cached-prop-name]] and [[PropSlot]] —
  which is emission work and has no JVM consumer.

  The tag's `#id`/`.class` shorthand answers the same question one step
  further on: it is folded onto the **emitted object**, where the slot is
  not resolved at all because it already *is* the slot. An explicit id
  therefore beats `#tag` in every spelling, and a declared class composes
  with `.foo` in every spelling — including a spelling a `:&` remainder
  forwarded, which is the one door where the author of the element never
  sees the key at all.

  ### The two exceptions, argued rather than assumed

  `:key` and [[revision-key]] are matched as the EXACT keyword and are
  never slot-claimed, which is the doctrine's own inverse. Both take the
  exception for the same reason and it is not convenience: they are
  **triggers rather than attributes**. Claiming the slot `key` for
  React's identity contract would be harmless, but claiming the slot
  `revision` for a reset would make bare `:revision`, `\"revision\"` and
  `:x/revision` all mean *reset this field* in some positions and an
  ordinary DOM attribute in others — a worse outcome than the exception,
  and the reason the collision argument settles the spelling.

  The reasoning is recorded here because the doctrine has since been
  reinforced by name twice — rf2-vrvv9 (\"a rule written against the
  spelling is a rule the other spellings walk past\") and rf2-2rtt6.119
  (the class slot is a position at the crossing too) — so the next reader
  arrives with that fresh and should find the exception argued. Note also
  that `:key` has BOTH halves, an exact match in the walk *and* a
  canonical-slot denial in [[structural-slots]]; the revision takes only
  the first, and closes the same gap from the other end — its read is
  PRE-merge, so a remainder cannot reach it, and [[convert-props]]
  refuses a remainder that carries it rather than letting it pass
  silently. **If a third exception ever arrives, restate the doctrine
  with its exceptions enumerated rather than accumulating them one commit
  at a time.**

  One consequence of leaving the slot unclaimed is worth stating because
  it is a surprise if undocumented: an element that writes a literal
  `:re-frame.hicasso/revision` thereby claims the canonical slot
  `revision` as an OWNED literal, so a `:&` remainder's ordinary bare
  `revision` attribute is denied by [[merge-caller]]'s owned-literal law.
  That is correct conduct — the law is on the slot — but the element's
  author never wrote an attribute named `revision`, so nothing on the
  page explains the denial except this paragraph."
  (:require [clojure.string :as str]
            [re-frame.hicasso.impl.controlled :as controlled]
            [re-frame.hicasso.impl.intent :as intent]
            [re-frame.hicasso.impl.slot :as slot]
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
  three names that must never be written to a JS object, or emitted
  through one? A hiccup literal named `__proto__`, `prototype` or
  `constructor` would otherwise reach an object's prototype. That roster
  is the whole predicate.

  Three `===` string compares rather than the set lookup this began as
  (rf2-y1jkm): a set lookup pays a string hash for a roster of three —
  36.9 ns against 9.6 on the census page's own literals, measured by
  `walk_profile_app`'s micro table.

  **It is asked on the cache MISS path only** (rf2-2rtt6.63). The caches
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
  "A codec cache: a JS object with **no prototype at all**
  (rf2-2rtt6.63).

  Both caches are keyed by the author's literal, so both had to answer
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
  the classes, as in the donor and in stock Reagent."
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
  prototype to serve a wrong one. The three poisoning names still never
  reach the cache — the refusal moved to the miss branch, which is the
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
;; the silent divergence the codemod exists to delete (rf2-ani6y). Only
;; the CACHING of its answers is codec work, and that is what follows.

(deftype PropSlot [js-name reserved? event? ref? class?]
  ;; What the prop cache holds for one prop literal (rf2-y1jkm): the React
  ;; name the codec always cached, PLUS the four classifications the
  ;; per-prop walk used to re-derive per element per render — is the
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
  rather than written out again here (rf2-ani6y). A hand-spelled seed is
  the one place this file could still answer a slot the shared rule
  would not, which is this bead's own defect class one level in; and the
  seed was written out TWICE, here and in [[reset-caches!]], so a drift
  had two chances and the suites' `:each` fixture made the second copy
  the live one. There is one copy now, and it cannot disagree with the
  rule because it does not restate it.

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
  is minted on every sight rather than cached, exactly as before: the
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

  **Only a keyword or a symbol is cached**, which is the donor's shape and
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
  [[re-frame.hicasso.impl.presence]] asks THIS, and never the key
  it was written as."
  cached-prop-name)

(def structural-slots
  "The two React slots that address the ELEMENT rather than its
  attributes. `key` is React's own identity contract and `ref` is
  HD-016's node handle; neither is an attribute, and neither is ever
  taken from a map that is *about* attributes — a `:&` remainder
  ([[merge-caller]]) or a presence phase override
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

(defn- fold-shorthand!
  "Fold the tag's `#id`/`.class` shorthand into the object the walk just
  emitted, and return it.

  **On the emitted object, and that is the whole repair.** The rule has
  always been *an explicit id wins over the shorthand, and the shorthand
  class is prepended to a declared one*; stated over the props MAP it read
  `:id`, `:class` and `:className` and saw exactly three of the spellings
  this codec accepts. `[:div#tag.foo {:& {\"id\" \"caller\" \"className\"
  \"bar\"}}]` walked straight past it: neither key was seen, the shorthand
  was added as a second entry landing on the same React slot, and which
  one survived was decided by the order the props map happened to iterate
  in — the explicit id could lose to `#tag`, and the caller's class could
  replace `.foo` instead of composing with it.

  Asked of the emitted object there is nothing left to resolve. Every
  spelling has already been through [[canonical-slot]] on its way into
  this object, so `id` present means *the author or their caller wrote an
  id*, however they spelled it, and `className` holds the composed class
  ([[convert-entry]]) whatever it was written as. One `undefined?` test
  and one `class-names` answer both halves for every spelling at once.

  It also deletes the map surgery the walk profile priced at most of
  [[convert-props]]'s cost — the `dissoc`/`assoc` pair that rebuilt the
  attribute map of every element carrying a shorthand — and with it the
  fast lane that existed to dodge it."
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
;; `::h/revision` — the controlled element's reset trigger (rf2-zq8kh)
;; ---------------------------------------------------------------------------

(def revision-key
  "`:re-frame.hicasso/revision` — authoring.md's `::h/revision`, the third
  and last reserved attribute key.

  **A trigger, not an attribute.** A change to its value (CLJS `=`)
  re-baselines a controlled text field to the model WITHOUT remounting
  it: the node is kept, the focus is kept, and the caret lands at
  end-of-model on the commit that carries the reset. Resets are by
  explicit caller revision and NEVER by value equality — the reset law
  HD-019 keeps from D016 — so a `:value` that changes under an unchanged
  revision continues the draft, and a revision that changes while the
  value stays equal still resets.

  ## Matched as the EXACT keyword, and never slot-claimed

  The `:key` precedent, and the codec docstring argues why the doctrine
  exception is right here rather than assumed. Every other spelling —
  bare `:revision`, `\"revision\"`, `:x/revision` — flows on as an
  ordinary DOM attribute, which is the honest loss the guide's
  troubleshooting line describes: post-rf2-vrvv9 the NATIVE walk still
  answers `(name v)` for a keyword ([[convert-prop-value]]), so a
  misspelled bare `:revision` carrying the most natural revision value
  there is — a namespaced keyword — emits `revision=\"rev-3\"` with the
  namespace deleted, collapsing two distinct revisions into one attribute
  value. Silent, and lossy.

  ## Zero new machinery, and no third `flushSync` site

  There is no transport to build. The codec mints a fresh props object
  per element per render (HD-004 refuses prop-object caching), React
  marks a host update on props IDENTITY rather than value, and the commit
  runs `updateInput` unconditionally, which assigns only when the DOM
  disagrees. So the whole delivery is: the revision is a value the body
  reads, its change re-runs the body, the re-run re-commits the element,
  and the commit re-asserts the model against the DOM. No hook, no ref,
  no comparison record, no keyed re-render.

  **Those three React behaviours are not public contracts** — the same
  class as the `defaultValue` mirror
  [[re-frame.hicasso.impl.controlled/last-rendered]] depends on —
  and this promotes HD-004's no-caching posture from a
  measurement-honesty stance to a CORRECTNESS DEPENDENCY of the reset
  transport. Any future prop-object memoization must exclude controlled
  text elements or re-design this delivery.

  The authored-data rule is the instance key's, transplanted: *if it
  would be a good instance key, it is a good revision value* — a domain
  fact written by events, never a render-order index, never a counter
  minted in render, never `random-uuid`."
  :re-frame.hicasso/revision)

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
  through, exactly as it survived the `dissoc`. The literal
  [[revision-key]] is skipped beside it, for the same reason and at the
  same price: [[native-element]] has already read it off the author's own
  PRE-MERGE map, so by the time the walk sees it there is nothing left to
  do but keep it out of the emitted object — a reset trigger is not a DOM
  attribute. The skip is unconditional because the walk cannot tell a
  literal from a remainder's copy; the provenance question is asked once,
  in [[convert-props]], where the answer is still available. A keyword or symbol key
  is answered by its [[prop-slot]] — name and classification in one
  lookup, `event?` gated on `keyword?` because a symbol is never an
  event position — and a value that nothing claims (not a ref, not a
  lowerable value at an event position, not a marked callback) goes
  straight to [[convert-prop-value]] without entering the lowering at
  all, which is the branch nearly every attribute on a census page
  takes. The paths [[intent/lower-prop]] IS entered on reproduce its
  answers by construction: it re-asks `event-prop?` and re-takes the
  same branch this slot was minted from. A string key keeps the donor's
  uncached path, byte for byte.

  **The class slot is a position, like the ref slot beside it.** Its
  value is coerced by [[class-names]] — a string, a keyword, a symbol or
  a collection of those, nils dropped — rather than by
  [[convert-prop-value]], which would hand React the `clj->js` array of
  `{:class [\"a\" nil :b]}`; that coercion used to live in the map surgery
  this walk replaced, and it is on the emitted slot now, so it holds for
  `\"class\"` and `:x/class` as well as for `:class`. And it COMPOSES
  with whatever is already in the slot: two spellings of the class of one
  element are two map keys and one React slot, so letting the last write
  win would drop a class silently, which is the failure class HD-023
  exists to delete. Composing drops nothing, and it is what the slot
  means."
  [o k v]
  (if (or (keyword-identical? :key k) (keyword-identical? revision-key k))
    o
    (if (or (keyword? k) (symbol? k))
      (let [^PropSlot s (prop-slot k (name k))]
        (when-not (.-reserved? s)
          (unchecked-set o (.-js-name s)
                         (convert-prop-value
                           (cond
                             (.-ref? s)
                             (check-ref! k v)

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
      (let [n (cached-prop-name k)]
        (when-not (reserved-name? n)
          (unchecked-set o n (convert-prop-value
                              (cond
                                (identical? ref-slot n)   (check-ref! k v)
                                (identical? class-slot n) (class-names (unchecked-get o class-slot) v)
                                :else                     (intent/lower-prop k v)))))
        o))))

(defn convert-props
  "One pass over the attribute map: fold a `:&` remainder under the
  owned-literal law, refuse a reserved `:ref` value, fold the tag
  shorthand, drop `:key` (React's own contract — it is not an attribute),
  lower every intent, and set each converted value under its React prop
  name.

  Two things about the order. Intent lowering happens *inside* the single
  walk, so the codec does not traverse the props map a second time to
  find the event positions. And [[merge-caller]] runs FIRST — before any
  prop-name conversion — which is what lets one rule cover the class the
  predecessor needs a third form for. A forwarded `:className` is merged
  as the key it was written as and then converted by *this position's*
  grammar; nothing canonicalises it into `:class` on the way through and
  hands the wrong name onward.

  The tag shorthand is folded LAST, onto the emitted object
  ([[fold-shorthand!]]) — so `[:input.form-control {:& caller}]` composes
  `\"form-control\"` with the caller's classes instead of one silently
  replacing the other, and does so however the caller spelled them.

  The `:ref` reservation, the ref position's exclusion from intent
  lowering, the class coercion and the shorthand fold are all taken on
  the CANONICAL SLOT rather than on the key — which is what makes them
  hold for `\"ref\"`, `:x/class` and `\"id\"` as well as for `:ref`,
  `:class` and `:id`, and costs the walk one comparison it already had
  the value for.

  ## The two lanes (rf2-y1jkm, narrowed by rf2-2rtt6.36)

  The walk-cost profile (`walk_profile_app`, census page: 1,202
  elements, 567 of them with no attribute map, 924 with a `.class`
  shorthand, 71 with a declared `:class`) priced this function at 67.5%
  of the whole interpreter walk, most of it the map surgery the
  shorthand merge performed on elements whose only class IS the
  shorthand. That surgery is **gone**: the shorthand is folded onto the
  object the walk emits rather than into the map the walk reads, so
  there is no `dissoc`/`assoc` pair on any path and no shape to peel a
  lane off for. What is left is one lane and one short-circuit:

  1. **No attribute map at all** (`props` nil): the emitted object is
     exactly the shorthand's `id`/`className`, so it is built directly —
     no merge, no map iteration, no fold.
  2. **Everything else**: convert the map (a `:&` remainder merged in
     first, by identity when there is none), then fold the shorthand onto
     the result.

  React's own `key` contract: the LITERAL `:key` is dropped in-loop (one
  keyword-identity test) rather than by a `dissoc` that copies the map;
  any other spelling lands in the emitted props exactly as it always
  did, and [[native-element]] still reads the literal `:key` off the
  original map.

  Per prop, one [[prop-slot]] lookup answers the React name AND the
  classifications the walk used to re-derive per element — reserved slot,
  event position, ref slot, class slot — so a non-event prop no longer
  pays [[re-frame.hicasso.impl.intent/event-prop?]]'s regex, and
  only a lowerable value at an event position (a vector, a map, a
  function) enters
  [[re-frame.hicasso.impl.intent/lower-prop]] at all. The slot's
  `event?` flag is gated on `keyword?` at the call site — a symbol shares
  the cache entry but is not an event position — and a string-keyed prop
  takes the donor's uncached path unchanged.

  ## The `:&` door is shut on the revision, loudly

  [[revision-key]] is read PRE-merge, in [[native-element]], so a
  remainder's copy arms nothing by construction. Left there it would be
  merely inert, and inert is the wrong posture for this lane: a
  remainder that writes a reset trigger the element's author never wrote
  is asking for a behaviour it cannot be given, and it should learn that
  from a diagnostic rather than from a field that never resets. So the
  provenance question is asked exactly once, here, where both maps are
  still in hand — present after the merge and absent from the author's
  own literals means it came through `:&`, and that is refused.

  **When the element writes the literal too, the literal simply wins and
  nothing is refused**: [[merge-caller]]'s owned-literal law has already
  denied the remainder's copy on the canonical slot `revision`, so the
  key that survives to be tested is the author's own. Order matters, and
  this is downstream of the merge for exactly that reason.

  The cost on the ordinary path is one `identical?`. [[merge-caller]]
  returns its argument by identity when there is no `:&` at all, so the
  two `contains?` calls are reached only by an element that actually
  merged a remainder."
  [props ^ParsedTag parsed]
  (if (nil? props)
    (let [o #js {}]
      (when-some [id (.-id parsed)] (unchecked-set o id-slot id))
      (when-some [c (.-className parsed)] (unchecked-set o class-slot c))
      o)
    (let [merged (merge-caller props)]
      (when-not (identical? merged props)
        (when (and (contains? merged revision-key)
                   (not (contains? props revision-key)))
          (fail! :rf.error/hicasso-revision-from-remainder
                 'front.codec/convert-props
                 (str (pr-str revision-key) " is a RESET TRIGGER the element's own "
                      "author writes, and a `:&` remainder may not arm one. It "
                      "re-baselines a controlled field to the model, discarding "
                      "the draft in it — conduct a caller forwarding attributes "
                      "cannot be given. Write the revision as a literal on the "
                      "element, driving it from whatever the caller sent through "
                      "an ordinary prop.")
                 :write-the-revision-as-a-literal-on-the-element
                 {:revision (get merged revision-key)})))
      (fold-shorthand! (reduce-kv convert-entry #js {} merged) parsed))))

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

(def ^:private frame-prop-marker "hicassoFrameProp")

(defn mark-frame-prop!
  "Record that `f` — an already-marked boundary head — takes its frame as
  an ordinary ELEMENT PROP rather than from React context, and return it
  (rf2-2rtt6.39).

  ## Why the codec can supply it at all

  The frame is ordinary data that flows down the tree, and every boundary
  element below the root is created by an ancestor BODY — which runs
  inside [[re-frame.hicasso.impl.intent/with-frame]], so the frame
  is already bound at the exact moment [[boundary-element]] mints the
  element. Baking it in costs one dynamic-var read and one property
  write per element, and buys the shell its `useContext` back.

  A foreign component sitting between two Hicasso boundaries is harmless:
  its children were created by the Hicasso body ABOVE it, with the prop
  already in them, so passing `props.children` through preserves it. The
  one creator with no ancestor body is an outward bridge — the root, or a
  React component that mounts Hicasso itself — and that creator names the
  frame explicitly ([[root-element]]).

  **This is a MEASUREMENT variant, not the default** (rf2-2rtt6.39 is a
  hypothesis to price, not a ruling). Both variants live here so the
  comparison is like-for-like: an unmarked head pays exactly what it
  always paid, because the marker is read where the head's memo wrapper
  is already read and the prop is written only when it is set."
  [f]
  (unchecked-set f frame-prop-marker true)
  f)

(defn frame-prop-head?
  "Does this head take the frame as a prop? One own-property read, on a
  path that already reads one ([[element-type]])."
  [f]
  (true? (unchecked-get f frame-prop-marker)))

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
  React element *type*, which HMR replaces outright.

  ## `rfFrame` is compared too, and it has to be (rf2-2rtt6.39)

  A context-fed boundary is safe from this comparator by construction:
  React propagates a context change to its consumers directly, ahead of
  the comparator and through a memo, so a subtree that changed frames
  re-renders whatever its props say. A frame-fed boundary
  ([[mark-frame-prop!]]) has no such channel — the frame IS a prop, and a
  comparator that ignored it would bail a re-parented subtree out and
  leave every body below reading the frame it left. One `identical?` on a
  keyword closes it, ahead of the `=` that can throw.

  The incumbent pays that one comparison on two `undefined`s, which is
  the honest price of keeping ONE comparator rather than two: a second
  memo path would be a second place for the fail-open ruling to rot."
  [^js prev ^js next]
  (try
    (and (identical? (unchecked-get prev "rfFrame") (unchecked-get next "rfFrame"))
         (= (unchecked-get prev "rfProps") (unchecked-get next "rfProps")))
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
;; Host heads (HD-011) — the declared door for a foreign React component
;; ---------------------------------------------------------------------------
;;
;; `defhost` is the door, and the only form taught. The declaration is a
;; VALUE — the foreign component, the finite `:callbacks` contract map,
;; and a name for the crossing — minted once and legal as a hiccup head
;; anywhere. The `[:>]` raw escape below is HD-011's explicitly
;; secondary form, for the cases a static declaration cannot express;
;; it is the SAME crossing with the declaration erased, and what erasing
;; the declaration costs is exactly what the declaration carried.
;;
;; The declaration's `:callbacks` is a finite map from EXACT prop names
;; to `:event`, `:handler` or `:render` — NEVER inferred from an `on*`
;; spelling. "Exact" is enforced on the CANONICAL SLOT, like every other
;; rule in this codec: `{:on-pick :event}` and a call site writing
;; `:onPick` name the same slot, so the declaration binds however the
;; author spells the prop — while an undeclared `onFoo` never becomes an
;; event position no matter how event-shaped its name is.
;;
;; ## The `:ssr` policy — HD-011's placeholder, activated (rf2-2rtt6.85)
;;
;; HD-011 listed "SSR placeholder" among `defhost`'s strong defaults and
;; HD-020(d) left it inert; the operator's 2026-08-04 ruling makes SSR
;; required scope, so the placeholder is now real. THREE VALUES, and the
;; author writes at most one of them:
;;
;;     :ssr :client-only        ; THE DEFAULT — omit :ssr and this is it
;;     :ssr {:fallback <hiccup>}
;;     :ssr :render             ; "this component is safe on the server"
;;
;; `:client-only` renders NOTHING where the host sits until the client
;; has adopted the markup; `{:fallback …}` renders that hiccup there
;; instead; `:render` runs the component itself, server-side, with its
;; real props and its real children. Anything else is refused at the
;; declaration. The DEFAULT is the conservative one because a foreign
;; React component is exactly the node whose render may reach for
;; `window` — the door cannot know, so it does not guess. `:render` is
;; the AUTHOR saying, which is a different thing from the door guessing.
;;
;; **The third value is rf2-l0wfx's ruling** (2026-08-05), and the case
;; that filed it is a context PROVIDER: a transparent wrapper that
;; contributes no markup of its own and exists solely to carry a
;; subtree. Under either of the first two policies the unadopted arm
;; returns something that is not the component, so the crossing's
;; CHILDREN are dropped and the provider deletes the whole application
;; from the server response — silently, because the server snapshot and
;; hydration's first client pass agree by construction. `:render` is the
;; one policy under which the children reach the server at all.
;;
;; ## Two shapes, and which one a declaration mints
;;
;; For `:client-only` and `{:fallback …}` **ONE mechanism serves the
;; server, the hydration pass and the fresh mount**, and it is
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
;; why `:ssr :children` — "render `props.children` in place of the
;; component" — was refused rather than adopted: it restores the markup
;; without the provider above it, so every consumer below reads the
;; context DEFAULT server-side (silent-absent becomes silent-wrong), and
;; then remounts the whole just-hydrated subtree at adoption.
;;
;; **The price, stated because it changed** (rf2-2rtt6.85): the door used
;; to mint no wrapper, no fiber and no hook — the foreign component was
;; the element's own type. A gated declaration mints ONE gate, so a
;; crossing under the first two policies costs one fiber and one hook; a
;; `:render` crossing costs neither. HD-020(b)'s ≤2 budget is a statement
;; about Hicasso's BOUNDARY shells and is untouched either way: the gate
;; is not a boundary, holds no subscription, and reads no frame.

(def ^:private host-marker "hicassoHost")

;; [[host-head?]] is the predicate over that marker and reads naturally
;; beside [[host-ssr]], at the end of this section. The fallback walk
;; below needs it three definitions earlier.
(declare host-head?)

(def ^:private callback-contracts
  "The three contracts a declaration may name — the position table's
  roster in `front.intent`, verbatim."
  #{:event :handler :render})

(def ^:private host-options
  "Every key a declaration may carry. [[mint-host!]] read `:callbacks`
  and SILENTLY IGNORED everything else until rf2-2rtt6.85 — so a
  misspelled `:ssr`, or a policy invented by an author reading the
  wrong docstring, was a no-op that looked like a setting. That is the
  same defect class as an intent crossing as inert data, and it gets
  the same treatment: refused, at the declaration."
  #{:callbacks :ssr})

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
  "A DECLARED FALLBACK IS INERT MARKUP, ENFORCED (rf2-nv07k). Walks
  `form` structurally and refuses a `defview` or `defhost` head at any
  position, naming the host, the head and where it sits.

  Structural rather than evaluating, and that is the whole repair. The
  fallback's other refusals are what [[as-element]] happens to evaluate
  on its way to an element — an intent vector raises
  `:rf.error/hicasso-intent-outside-boundary` because there is no
  frame-locked dispatch to lower it against, a `sub` call in the form
  raises `:rf.error/hicasso-sub-outside-render` because it is evaluated
  where the declaration is. A boundary head is neither: it is an element
  whose body runs LATER, so that walk never looks inside it and every
  refusal it carries is deferred past the declaration, which is the one
  thing a mint-time walk exists to prevent. What was enforced was
  therefore never a rule about content; it was a property of the walk.

  Two facts made the absence a defect rather than a narrow rule
  (measured, `arm1/fallback_contents_cljs_test`):

  1. **The declared placeholder is not a value.** [[mint-host-gate!]]
     walks once and reuses the element everywhere, and its stated reason
     is *\"a placeholder that differs per site is not a placeholder\"*.
     One declaration carrying a boundary head renders `ALPHA` in one
     frame, `BRAVO` in another and `ALPHA-TWO` after a write — the
     justification falsified by what it permitted.
  2. **It did not survive the arm's other boundary variant.** A
     frame-fed head ([[mark-frame-prop!]]) reads `intent/*frame*` at
     ELEMENT-creation time, which in a fallback is mint time, where the
     var is `nil` — so it baked `nil` in, minted happily, and threw
     `:rf.error/no-frame-prop` one render into the server response.
     Whether a boundary head in a fallback worked at all was a property
     of which mint it came from, which is not a rule an author can hold.

  The refusal is walk-scoped, so it catches that frame-fed variant for
  free: a frame-fed head is a boundary head, and the walk asks the
  marker rather than the mint.

  **The workaround it deletes is superseded, not merely removed.**
  Writing a provider's subtree a second time as the declaration's
  fallback was `rf2-l0wfx`'s only recovery; `:ssr :render` is now the
  honest one, and it renders the real subtree with the real context
  value and no duplication.

  `path` is the index route into the declared form — `[]` is the
  fallback itself, `[0]` its head position, `[2 0]` the head of its
  third element."
  [host-name path form]
  (if-some [kind (deferring-head-kind form)]
    (fail! :rf.error/hicasso-host-fallback-boundary-head
           'front.codec/mint-host!
           (str "defhost " host-name " declares an :ssr fallback carrying the "
                kind " head " (head-name form) " at position " (pr-str path)
                ". A fallback is INERT MARKUP: it is walked into ONE element "
                "at the declaration, outside any frame, and that element is "
                "reused at every site of the host — so a head whose body runs "
                "later makes one declared placeholder render a different "
                "document per frame and per write, which is not a "
                "placeholder. Write plain hiccup there, or declare "
                ":ssr :render and render the real subtree on the server.")
           :write-inert-hiccup-or-declare-ssr-render
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
  behind its `:ssr` policy. `:client-only` and `{:fallback …}` mint one
  of these; `:ssr :render` mints none, and the head's `gate` slot
  carries the foreign component itself.

  `fallback` is walked into an element HERE, at the declaration —
  which is where every other host refusal fires, so a fallback that is
  not hiccup fails with the author's own stack rather than one render
  into a server response. It is walked ONCE and the element is reused
  at every site of the host: React elements are immutable values, and a
  placeholder that differs per site is not a placeholder.

  ## And that is now ENFORCED rather than merely stated (rf2-nv07k)

  This docstring used to draw the corollary the guide teaches — *\"a
  fallback is inert markup\"* — while only half of it held: the walk
  refused what it could EVALUATE (an intent vector, a `sub` call in the
  form, hiccup that is not hiccup) and never looked inside a head whose
  body runs later. [[refuse-deferring-heads-in-fallback!]] closes that,
  structurally and ahead of the walk, so the sentence is true as
  written. Its docstring carries the two measurements that decided it;
  `arm1/fallback_contents_cljs_test` is the contract.

  The gate hands its own props straight through to the foreign
  component, so the crossing's props object is exactly the one
  [[host-element]] built — `ref` included, which React 19 carries as an
  ordinary prop — and `:key` never reaches here, because
  `createElement` took it off the gate's own element."
  [host-name component fallback]
  (when (some? fallback)
    (refuse-deferring-heads-in-fallback! host-name [] fallback))
  (let [placeholder (when (some? fallback) (as-element fallback))
        gate        (fn [props]
                      (if (react/useSyncExternalStore
                            gate-no-subscribe gate-adopted gate-unadopted)
                        (react/createElement component props)
                        placeholder))]
    (unchecked-set gate "displayName" host-name)
    gate))

(defn- declared-ssr
  "The `:ssr` policy this declaration carries, validated. Absent means
  `:client-only` — the ruled default, so an author who writes nothing
  gets the conservative answer and an author who writes the default
  explicitly gets the same one.

  `:render` is the third value (rf2-l0wfx, 2026-08-05) and it is an
  ASSERTION: *this component is safe to render on the server*. The two
  spellings an author reaches for instead — `:children` and
  `:transparent` — stay refused, and so do `:passthrough` and `:server`.
  They assert a structural property nobody can check and deliver the
  subtree under the WRONG context value; `:render` names both the
  conduct (the component renders) and the claim (it is safe to)."
  [host-name opts]
  (let [policy (get opts :ssr :client-only)]
    (if (or (keyword-identical? :client-only policy)
            (keyword-identical? :render policy)
            (and (map? policy)
                 (= 1 (count policy))
                 (some? (:fallback policy))))
      policy
      (fail! :rf.error/hicasso-host-bad-ssr-policy
             'front.codec/mint-host!
             (str "defhost " host-name " declares :ssr " (pr-str policy)
                  ". The policy is :client-only — the default, meaning the "
                  "host region renders nothing until the client adopts it — "
                  "or {:fallback <hiccup>}, meaning that markup renders "
                  "there instead, or :render, meaning the component itself "
                  "is safe to run on the server and does. There is no "
                  "fourth value.")
             :declare-client-only-a-fallback-or-render
             {:host host-name :ssr policy}))))

(defn mint-host!
  "THE ONE-LINE DECLARATION (HD-011). Give the crossing to `component` a
  name, a policy, and a place: returns the host HEAD — a marked carrier
  legal in hiccup head position, rendered by [[vec->element]]'s fourth
  branch.

  `component` is anything React accepts as an element type — a function
  component, a class, a `memo`/`lazy` product, a context provider. `nil`
  is refused HERE, at the declaration, because it is the classic broken
  interop symptom (`:default` against a library that has no default
  export) and the render-time alternative is React's own error naming
  nothing the author wrote.

  `opts` carries `:callbacks` — the finite contract map above — and
  `:ssr`, the server policy (`:client-only` by default,
  `{:fallback <hiccup>}`, or `:render`). Each callback key is normalized
  to its canonical slot at MINT time, so the lookup the crossing
  performs per prop is one `get`; a contract outside the roster, a
  declaration on a structural slot (`key`/`ref` are React's, not
  positions), two spellings landing on one slot, an `:ssr` value outside
  the three, a `defview` or `defhost` head written into a fallback, and
  an option key outside `#{:callbacks :ssr}` are all refused at the
  declaration, where the author's stack is the declaration site.

  ## What the `gate` slot holds, and why it is not always a gate

  The head's `gate` slot is the React TYPE [[host-element]] creates
  every crossing from, and the `:ssr` policy is expressed by choosing
  it. Under `:client-only` and `{:fallback …}` it is
  [[mint-host-gate!]]'s product — one fiber, one hook, and the foreign
  component behind it. Under `:render` it is the foreign component
  ITSELF: HD-011's original zero-wrapper, zero-fiber, zero-hook shape,
  and the reason that policy has no adoption event, no snapshot pair and
  no remount. `displayName` is not stamped onto the foreign object in
  that case — it belongs to somebody else's component, and the head map
  already carries the crossing's name."
  ([host-name component] (mint-host! host-name component {}))
  ([host-name component opts]
   (when (nil? component)
     (fail! :rf.error/hicasso-host-no-component
            'front.codec/mint-host!
            (str "defhost " host-name " was given nil as its component. The "
                 "usual cause is a JS import that resolved nothing — e.g. "
                 "`:default` against a library with no default export.")
            :hand-the-declaration-a-real-component
            {:host host-name}))
   (doseq [k (keys opts)]
     (when-not (contains? host-options k)
       (fail! :rf.error/hicasso-host-unknown-option
              'front.codec/mint-host!
              (str "defhost " host-name " was declared with " (pr-str k)
                   ", which is not an option. A declaration carries "
                   ":callbacks and :ssr. Reading past an option it does not "
                   "know is how a policy comes to be set and never applied.")
              :declare-callbacks-or-ssr
              {:host host-name :option k :options host-options})))
   (let [ssr (declared-ssr host-name opts)
         declared
         (reduce-kv
           (fn [m k contract]
             (let [slot (cached-prop-name k)]
               (when-not (contains? callback-contracts contract)
                 (fail! :rf.error/hicasso-unknown-callback-contract
                        'front.codec/mint-host!
                        (str "defhost " host-name " declares " (pr-str k) " with "
                             "the callback contract " (pr-str contract)
                             ". The contracts are :event, :handler and :render.")
                        :declare-event-handler-or-render
                        {:host host-name :position k :contract contract}))
               (when (structural-slot? k)
                 (fail! :rf.error/hicasso-host-structural-callback
                        'front.codec/mint-host!
                        (str "defhost " host-name " declares a callback contract on "
                             (pr-str k) ", whose canonical slot is structural. `key` "
                             "is React's identity contract and `ref` is HD-016's "
                             "node handle; neither is a callback position.")
                        :declare-contracts-on-ordinary-props-only
                        {:host host-name :position k}))
               (when (contains? m slot)
                 (fail! :rf.error/hicasso-host-callback-slot-collision
                        'front.codec/mint-host!
                        (str "defhost " host-name " declares " (pr-str k) ", but the "
                             "slot " (pr-str slot) " already carries a contract from "
                             "another spelling. Two spellings of one prop are one "
                             "position; declare it once.")
                        :declare-each-slot-once
                        {:host host-name :position k :slot slot}))
               (assoc m slot contract)))
           {}
           (or (:callbacks opts) {}))
         ;; The policy IS the type. `:render` mints no gate at all —
         ;; the foreign component is the element's own type, so the
         ;; server render, hydration's first pass and a fresh mount are
         ;; one tree and there is nothing to swap at adoption.
         ^js head #js {"component"   component
                       "gate"        (if (keyword-identical? :render ssr)
                                       component
                                       (mint-host-gate! host-name component
                                                        (:fallback ssr)))
                       "callbacks"   declared
                       "ssr"         ssr
                       "displayName" host-name}]
     (unchecked-set head host-marker true)
     head)))

(defn host-head?
  "Is `v` a minted host head? One own-property read behind a nil guard;
  no registry, no map — the same shape as [[boundary-head?]]."
  [v]
  (and (some? v) (true? (unchecked-get v host-marker))))

(defn host-ssr
  "The `:ssr` policy `head` was declared with — `:client-only`,
  `{:fallback <hiccup>}` or `:render`. The declaration read back as
  data, for a server walk that wants to state the policy it is
  honouring (rf2-2rtt6.86) and for the witnesses that assert on it.
  Nothing on the render path reads it: the policy is enforced by WHICH
  TYPE the declaration mints — a gate for the first two, the foreign
  component itself for `:render`."
  [^js head]
  (unchecked-get head "ssr"))

;; ---------------------------------------------------------------------------
;; Hiccup shape
;; ---------------------------------------------------------------------------

(defn- props-map?
  "Slot `i` of a hiccup vector is the props map when it is a map. A seq
  there is a child, as is a string, as is another hiccup vector."
  [argv i]
  (map? (nth argv i nil)))

;; ---------------------------------------------------------------------------
;; The minted key warnings — DEVELOPMENT ONLY (rf2-2rtt6.104)
;; ---------------------------------------------------------------------------
;;
;; React already warns about an unkeyed list, and this does not replace it
;; or suppress it. What React cannot say is the AUTHORING fact: its message
;; names a component stack and its dedupe is keyed on the parent TAG name
;; (`ownerHasKeyUseWarning` in react-dom-client's development build), so
;; after the first unkeyed list under any `:ul` on the page, every later
;; `:ul` site is silent for the life of that page. At those later sites this
;; warning is the only signal on the console — which is why it names the
;; enclosing view rather than leaning on React's owner clause.

(def ^:private keywarn
  "The warning's whole state: who is lowering, and which sites have
  already spoken. `nil` in production — every reader below sits behind
  `goog.DEBUG`, so under `:advanced` the object, the tables and every
  message string fold away with the branches that reach them.

  Plain `def` rather than `defonce`: an app-code edit never re-mints it
  (the codec requires no app code, so shadow-cljs's reload does not reach
  it), while a framework-dev edit of THIS file does — which is the
  behaviour a codec author wants and an app author cannot observe. A full
  page reload resets it either way, which is React's own semantics for
  the same dedupe.

  `warned` is a `Map` of owner-name -> `Map` of member head -> the kinds
  that head has already reported there, rather than the flat `Set` of
  joined site strings the design proposed. The reason is a clocked one:
  an ALREADY-WARNED site is re-encountered on every render of a list the
  author has not fixed yet, and building `(str owner \"|\" member \"|\"
  kind)` to look it up allocated a string per member per render — 420
  ns/member, a third of dev lowering, on precisely the list the author is
  sitting in front of. These lookups key on the owner string and the head
  object as they already are, so the repeat path allocates nothing."
  (when ^boolean js/goog.DEBUG
    #js {"owner" nil "warned" (js/Map.)}))

(defn set-lowering-owner!
  "Dev-only seam: name the view whose body is being lowered, or `nil` to
  clear. A no-op in production builds.

  **For the arm's runtime shell and for the tests, never for authors** —
  the same class as [[reset-caches!]]. The arm's `run-once` sets it before
  a body's hiccup is lowered and clears it in the `finally` it already
  has, so an `as-element` reached outside a body run — the root, an
  outward bridge, a deferred lowering inside `presence` or an error
  boundary, a direct call from a test — observes `nil` and the warnings
  below drop their owner clause rather than naming a stale view.

  ## Two halves, one pinnable

  *Forgetting to CLEAR* would mis-attribute, and is pinned: setting a
  non-nil owner over a non-nil one is an unbalanced pair and says so on
  the console. Any future lowering entry point that skips its `finally`
  is caught deterministically at the next boundary render in dev.

  *Forgetting to SET* is *not* pinnable, and this docstring is the
  declaration the design owes: a test cannot cover an entry point that
  does not exist yet. A future lowering entry point that adds no set/clear
  pair degrades the warnings to their ownerless wording — never to a wrong
  name, because the clear-in-`finally` guarantees `nil` rather than
  staleness. The obligation is on the entry point: add the pair."
  [view-name]
  (when ^boolean js/goog.DEBUG
    (let [prior (unchecked-get keywarn "owner")]
      (when (and (some? view-name) (some? prior) (exists? js/console))
        (.warn js/console
               (str "[hicasso] A boundary body began lowering while `" prior
                    "` was still recorded as the enclosing view. The "
                    "set/clear pair around a body run is unbalanced, so a "
                    "key warning may name the wrong view. The lowering entry "
                    "point that set the owner must clear it in a `finally`.")))
      (unchecked-set keywarn "owner" view-name)))
  nil)

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
  guard everyone routes around is worse than the silence this repair
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

  TOTAL over everything [[check-member-key!]] rejects, which is the
  repair rf2-2rtt6.104 asked for. The strings below are the ONLY text
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

(defn- warn-member-key!
  "One console line per site, where a site is *(enclosing view, member
  head, which hazard)*. Built only on detection, never on the render
  path."
  [head kind i]
  (let [owner (unchecked-get keywarn "owner")
        heads (let [by-owner (unchecked-get keywarn "warned")]
                (or (.get by-owner owner)
                    (let [m (js/Map.)] (.set by-owner owner m) m)))
        kinds (or (.get heads head)
                  (let [o #js {}] (.set heads head o) o))]
    (when-not (unchecked-get kinds kind)
      (unchecked-set kinds kind true)
      (when (exists? js/console)
        (let [member (head-name head)]
          (.warn js/console
                 (if (= kind "missing")
                   (str "[hicasso] Unkeyed boundary children"
                        (if owner (str " in the body of " owner) "")
                        ": a seq of " member " members has no :key (absent or "
                        "nil; first at index " i ")."
                        " Give each one a :key in its props map —"
                        " [child {:key id, …}] — so the list keeps identity"
                        " across reorder and removal; a key written as Reagent"
                        " metadata is not read here. React's own warning names"
                        " the component stack and fires once per parent tag"
                        " name; this one names the authoring site and fires"
                        " once per site, in development builds only."
                        " [:rf.warning/hicasso-missing-key]")
                   (str "[hicasso] Entity-valued :key on boundary children"
                        (if owner (str " in the body of " owner) "")
                        ": a seq of " member " members carries " kind
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
                        " [:rf.warning/hicasso-entity-key]")))))))
  nil)

(defn- check-member-key!
  "One member of a lowered child seq, at index `i`. Warns when it is a
  boundary-headed vector React will reconcile by position — no `:key`, or
  a `:key` whose value is not one React can coerce to a stable identity.

  ## The classification is TOTAL (rf2-2rtt6.104)

  This `cond` shipped with two arms and no `:else`, so every non-nil
  `:key` that was neither primitive nor a CLJS collection fell out of the
  check in silence. The shape that made that a bug rather than a gap is
  the FOREIGN JS ENTITY OBJECT: `createElement` does `key = '' + key`, so
  every plain object reaches `Object.prototype.toString` and every member
  of the list is keyed `[object Object]`. Distinct rows, one key. The
  codec was already careful never to PRINT such a value ([[key-shape]]);
  it simply never reached the printing.

  Every non-nil value [[plain-key?]] rejects now goes exactly one of two
  ways — classified safe by [[stable-object-key?]], or named by
  [[key-shape]], which is total. Nothing falls through.

  ## What React says, and where it is genuinely silent

  Verified against the vendored React 19.2 rather than assumed. React's
  duplicate-key warning (`react-dom-client.development.js`,
  `warnOnInvalidKey`) bails on `if (\"string\" !== typeof key) break` —
  but the coercion above has already happened, so the key IS a string and
  the check DOES apply. React therefore does emit its own *Encountered
  two children with the same key, `[object Object]`* whenever two or more
  foreign-object keys collide.

  That is worth stating plainly because it means this warning is NOT the
  only signal in the collision case. Where React is genuinely silent is
  the larger half of the same hole: a key whose coercion is
  CONTENT-DERIVED and therefore distinct per member — a `js/Date`, a JS
  array, a CLJS map — collides with nothing, so React never warns, and
  the row silently remounts the moment the author edits the entity. That
  is the same hazard the `:rf.warning/hicasso-entity-key` row already
  existed for, and the foreign object is simply its unhandled case.

  The reason to warn on BOTH rather than defer the collision half to
  React is cost, and here it is zero: see the ordering note below. The
  cost argument that keeps this lane quiet where React already speaks
  (rf2-2rtt6.134, the missing key on a host or `[:>]` child) is an
  argument about a ~150 ns/member charge on the hot walk. Nothing here
  touches the hot walk.

  ## What it costs, clocked rather than asserted

  `keywarn_clock_run.cjs`, dev build, 300 boundary members, 200 walks per
  round, 11 interleaved rounds, median-of-rounds, ns per member:

  | population | shipping | check ablated | the check | share of dev lowering |
  |---|---|---|---|---|
  | keyed (the steady state) | 1523.7 | 1367.2 | **156.5** | 10.3% |
  | unkeyed, site already warned | 936.7 | 789.0 | **147.8** | 15.8% |

  ~150 ns per member on both populations; the share differs only because
  an unkeyed boundary element is cheaper to mint than a keyed one, so the
  same absolute cost is a larger fraction of a smaller number.

  That is **4x the 15-40 ns/member the design derived analytically**, and
  the reason is that this is a DEV build: `vector?`, `nth` and the `:key`
  lookup are protocol dispatches through real function calls here, where
  the analytic estimate priced them as the inlined shapes `:advanced`
  produces — and under `:advanced` the check does not exist at all. The
  figure is recorded rather than argued with; rf2-2rtt6.32 is this lane's
  standing reminder of what an unclocked micro-claim is worth.

  Two shapes were measured and rejected on the way to this one:

  - **A pre-pass over the seq before the expansion loop** (the design's
    proposal, chosen there to leave the shipping loop untouched):
    316 ns/member, 18% of dev lowering. It TRAVERSES THE SPINE TWICE, and
    `first`/`next` over the chunked seq a `for` produces allocates per
    step. Riding the loop that is already walking costs the predicates
    and nothing else — and costs production nothing either, because the
    call site is gated and `(.-length a)` supplies the index without a
    loop variable (see [[expand-seq]]).
  - **A flat `Set` of joined site strings** for the dedupe (also the
    design's): 420 ns/member on an already-warned list, because looking a
    site up meant building its string on every member of every render of
    the list the author had not fixed yet. The nested tables in
    [[keywarn]] key on the owner string and the head object as they
    already are.

  The predicate order is the rest of the cost: `vector?` first, then the
  props-map `:key` read, then [[plain-key?]] — which is where a keyed
  member leaves, on a `typeof`. [[boundary-head?]]'s own-property read is
  asked LAST, so an unkeyed `[:li …]` costs one `fn?` and no more.

  THE TOTALITY REPAIR IS FREE, and the ordering is why. Both new arms sit
  INSIDE the `cond`, which is reached only by a member already known to
  be boundary-headed AND already known to carry a non-plain `:key`. The
  keyed steady state never arrives — it left at [[plain-key?]], three
  `typeof`s up. The unkeyed fast path never arrives at the new arms
  either: `nil?` is still the FIRST arm, so an unkeyed boundary member
  short-circuits exactly where it did before. Neither of the two
  populations in the table above executes one added instruction, so the
  numbers stand as clocked.

  The one population whose cost MOVED is a list keyed by `uuid`, and it
  got cheaper. `coll?` — the dearest predicate on this path, because
  anything without the `ICollection` marker falls through to
  `native-satisfies?` — used to be asked on the walk, so a legitimate
  UUID-keyed member paid it on every member of every render only to fall
  out of the `cond` unhandled. It now lives in [[key-shape]], which runs
  on detection, and a UUID leaves at [[stable-object-key?]] on two
  `instanceof`-class tests instead.

  Every member is checked every time, uniform with the keyed steady
  state. Stopping at the first offender would save work only on the
  broken list the author is about to fix, and would hide a second unkeyed
  head until the first was repaired. The dedupe is on the SITE, so
  console volume is one line per site per page load either way. Nested
  seqs need no code: a seq member is not a vector, and its own expansion
  checks its own members — HD-016's one level at a time."
  [m i]
  (when (vector? m)
    (let [p (nth m 1 nil)
          k (when (map? p) (:key p))]
      (when-not (plain-key? k)
        (let [h (nth m 0 nil)]
          (when (boundary-head? h)
            (cond
              (nil? k)               (warn-member-key! h "missing" i)
              (stable-object-key? k) nil
              :else                  (warn-member-key! h (key-shape k) i)))))))
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
  therefore never warns about. This branch, where the seq is still in hand
  and the enclosing body's owner slot is still set, is the only chance
  anything has to say so (rf2-2rtt6.104)."
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
  truncate the list.

  The dev-only [[check-member-key!]] call RIDES this loop rather than
  pre-scanning the seq, which costs the predicates and no second spine
  traversal (rf2-2rtt6.104 clocked the difference; the fn's docstring
  carries the numbers).

  **The index it reports is `(.-length a)`, not a loop variable**, and
  that is the reason the loop still has exactly the shape it had: one
  element is pushed per member, so the array's length IS the index of the
  member about to be pushed. Threading an `i` would have put an increment
  per child on the production path for a dev message's benefit. As
  written, `goog.DEBUG` folds to `false` under `:advanced`, the whole line
  goes, and what is left is character for character the loop that was here
  before the warning existed. `(first items)` is read twice in a dev build
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

  [[re-frame.hicasso.impl.controlled/install!]] is the only thing
  here that is not a translation of what the author wrote. It runs on
  the converted props, because the condition it tests is about the
  EMITTED element — a controlled `value`, a change handler, a type with
  a caret — and those are canonical slots rather than spellings. It is
  one JS `switch` on the tag for every element that is not an `:input`
  or a `:textarea`, which is nearly all of them. rf2-fki5d.

  It also **answers what to render the props as**, which since rf2-digtt
  is the tag for everything except a controlled `input`/`textarea` —
  those get the composition shadow's component, and the tag it renders
  is the tag parsed here. The codec asks one question and takes one
  answer; which of the two it is belongs entirely to that namespace.

  ## The revision is read here, and it is read PRE-MERGE

  [[revision-key]] is taken off `props` — the author's OWN attribute map,
  before [[convert-props]] folds any `:&` remainder into it — with the
  same expression shape the `:key` read below uses, and for the same
  reason. `key` and the revision are the two positions a remainder must
  never reach: [[merge-caller]] denies a remainder only the structural
  slots plus the slots owned by literals the element wrote, so
  `{:& {:re-frame.hicasso/revision r}}` on an element that writes no
  literal survives the merge. Reading pre-merge is what makes arming it
  from that door impossible rather than merely unlikely — the hostile
  remainder cannot force a field re-baseline the element's author never
  wrote. [[convert-props]] refuses such a remainder in the same pass, so
  the door is shut loudly as well as shut.

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
        ;; map was the whole cost of telling it so (rf2-y1jkm).
        js-props    (convert-props props parsed)
        _           (when-some [r (get props revision-key)]
                      (unchecked-set js-props controlled/revision-slot r))
        component   (controlled/install! (.-tag parsed) js-props)]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    (make-element component js-props argv (if has-props? 2 1))))

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
        head       (nth argv 0)
        js-props   #js {"rfProps" body-props}]
    (when-some [k (:key props)] (unchecked-set js-props "key" k))
    ;; THE FRAME AS DATA (rf2-2rtt6.39). Only for a head that asked for
    ;; it, so the context-fed incumbent's element carries exactly what it
    ;; always carried and the two variants are comparable. `intent/*frame*`
    ;; is bound by the ancestor body this element is being created inside;
    ;; at the root there is no ancestor body and [[root-element]] binds it.
    (when (frame-prop-head? head)
      (unchecked-set js-props "rfFrame" intent/*frame*))
    (react/createElement (element-type head) js-props)))

(def ^:private html-attr-slots
  "The emitted slots at a FOREIGN crossing whose value is bound for an
  HTML attribute wherever the component passes it on, and which therefore
  has no representation but a string. `className`, `id` and `role` named
  as SLOTS rather than as keys — `:class`, `:className` and `\"class\"`
  are one position ([[canonical-slot]]), and a rule written against the
  spelling is a rule the other spellings walk past. The `data-*` /
  `aria-*` families join them by prefix;
  [[re-frame.hicasso.impl.slot/prop-name]] leaves those two
  uncamelCased, so the slot still carries the prefix to test.

  The roster is this repo's own, not a guess: the `reagent-slim` adapter
  narrowed exactly this seam after an audit and shipped this same set
  (`adapters/reagent-slim/IMPL-SPEC.md` §7.2, `DESIGN-RATIONALE.md` §5)."
  #{"className" "id" "role"})

(defn- ^boolean html-attr-slot?
  "Is `slot` one of the HTML-attribute positions? A non-string slot — the
  key was neither keyword, symbol nor string, so [[cached-prop-name]]
  answered it verbatim — is never one."
  [slot]
  (and (string? slot)
       (or (contains? html-attr-slots slot)
           (str/starts-with? slot "data-")
           (str/starts-with? slot "aria-"))))

(defn host-prop-value
  "A host prop value, converted SHALLOWLY — HD-011's default — for the
  emitted `slot` it is bound for. The top-level KEY is camelCased (that
  is [[cached-prop-name]], applied by [[host-element]]'s walk); the VALUE
  crosses with no renaming inside it: functions by identity (so
  `React.memo` and every downstream bail-out that compares handler
  identity keep working), collections through `clj->js` — whose nested
  map keys keep the spelling the author wrote — and **a keyword or symbol
  by identity, not by name**. A library expecting camelCase inside a
  nested option map is handed exactly what the author typed, and the
  guide's answer is to convert that one map yourself: deep conversion
  guessing at which nested maps are options and which are data is the
  documented support burden the shallow default deletes.

  ## The named value crosses whole (rf2-vrvv9)

  This branch read `(name v)` for every named value at every host prop,
  which is stock Reagent's rule — and it silently deleted half of a
  namespaced keyword's identity at the one crossing where that identity
  is most often the point. `[provider {:value :theme/dark}]` handed the
  provider `\"dark\"`, `:other/dark` handed it `\"dark\"` too, and every
  consumer below read a plausible string that two distinct values now
  share. Nothing threw. A crossing that answers two inputs with one
  output is not a conversion, it is a collision.

  **The rule is the shallow default's own rule, applied one level up.**
  The paragraph above already refuses to guess that a nested map was
  meant as options; `(name v)` is the same guess about a keyword — that
  the author meant a string — made silently, on the value the author
  most often meant literally. The honest answer is the one the nested map
  already gets: hand the library exactly what was typed. An author who
  wants `\"contained\"` writes `\"contained\"`, or `(name :contained)`,
  at the call site where the intent is legible.

  **[[html-attr-slots]] is the one exception, and it is not a roster of
  taste.** At `className`, `id`, `role`, `data-*` and `aria-*` the value
  is bound for an HTML attribute, whose only representation is a string —
  there is nothing to preserve it AS. So those keep `(name v)`, which is
  also the answer the NATIVE walk gives at the same names
  ([[convert-prop-value]]) and the answer the server serializer gives, so
  the two crossings agree on every attribute both can carry.

  **`className` no longer arrives here at all** (rf2-2rtt6.119).
  [[host-entry]] takes the class slot ahead of this function and hands it
  to [[class-names]], which is the coercion the native walk takes and the
  only one that answers a COLLECTION correctly — the arm the named-value
  rule above could never reach, since a collection is not a named value.
  The slot stays on the roster because the roster states which SLOTS are
  bound for HTML attributes, which is still true of `className` and is
  what keeps this function's answer right if it is ever asked directly.

  **No dev warning accompanies this**, and the omission is deliberate.
  `reagent-slim` warns once per non-HTML keyword prop because it narrowed
  the rule underneath an installed Reagent codebase and the warning is
  that migration's safety-net (DESIGN-RATIONALE §5: \"the warning exists
  for the case we did not audit\"). Hicasso has no such codebase to
  protect, and after this change a keyword at a host prop is the CORRECT
  and taught spelling of HD-011's flagship case — warning on the happy
  path is a nag, not a diagnostic. The guide teaches the rule instead."
  [slot v]
  (cond
    (fn? v)                       v
    (or (keyword? v) (symbol? v)) (if (html-attr-slot? slot) (name v) v)
    (coll? v)                     (clj->js v)
    :else                         v))

(defn- refuse-undeclared-host-event!
  "An intent vector or key-map at an event-SPELLED prop the declaration
  does not name. The contract rule is 'never inferred from an `on*`
  name', and this refusal is the loud half of it: no contract is guessed
  — but the alternative to refusing is `clj->js` shipping the author's
  intent to the library as an inert array, which is the silently dead
  handler class every loud error in this codec exists to delete. An
  ORDINARY function at an undeclared prop is different and legal: it is
  a value handed to a foreign API — not a position — so it crosses by
  identity and simply runs (the position table's deletion row). The
  MARKED form is not, since rf2-2rtt6.116:
  [[refuse-unclaimed-host-callback!]] takes an `h/fn` at the same
  position, because that one asked for a contract."
  [^js head k v]
  (fail! :rf.error/hicasso-host-undeclared-callback
         'front.codec/host-element
         (str "The host " (unchecked-get head "displayName") " was handed "
              (pr-str v) " at " (pr-str k) ", which its declaration does not "
              "name. A host's callback contracts are a finite map from exact "
              "prop names to :event, :handler or :render — never inferred "
              "from an on* spelling — so an undeclared intent would cross as "
              "inert data. Declare " (pr-str k) " in :callbacks, or hand a "
              "plain function.")
         :declare-the-callback-contract
         {:host     (unchecked-get head "displayName")
          :position k
          :value    v
          :declared (into #{} (keys (unchecked-get head "callbacks")))}))

(defn- refuse-unclaimed-host-callback!
  "An `h/fn` at a host prop slot NOTHING CLAIMED — not the `:ref` slot,
  not a slot the declaration named, not the class slot. The marked form
  is a REQUEST that the position impose a contract, and at an unclaimed
  slot no position selected one, so the mark reads nothing and the
  function crosses to the foreign library as an ordinary function. It is
  callable, so nothing throws.

  **The trap is the `:event` contract's convenience.**
  `[my-host {:on-pick (h/fn [x] [:row/pick x])}]`, with `:on-pick` absent
  from `:callbacks`, crosses; the library calls it; it returns an intent
  vector; the library discards the return; nothing dispatches. The user's
  click does nothing, in production, with no diagnostic anywhere — the
  silently dead handler class the sibling refusal above names as the one
  every loud error in this codec exists to delete. An `h/fn` returning
  that same vector is that defect one level of indirection down.

  **This is derived, not new policy** (`rf2-2rtt6.116`). `mint-host!`
  already refuses an option it does not know, on the reasoning HD-011's
  addendum records: a policy could be written and never applied, and the
  silent-ignore was its own defect. An `h/fn` whose contract is never
  selected IS a policy written and never applied.

  **A PLAIN function at the same slot stays legal and untouched.** It is
  a value handed to a foreign API rather than a position, and it never
  asked for anything, so there is nothing to leave unanswered. This
  refuses the unanswered REQUEST — never functions at the crossing, and
  never the form itself, which is legal at every slot a declaration
  claims."
  [^js head k]
  (fail! :rf.error/hicasso-host-unclaimed-callback
         'front.codec/host-element
         (str "The host " (unchecked-get head "displayName") " was handed an "
              "h/fn at " (pr-str k) ", which no position claims — its "
              "declaration names " (pr-str (into #{} (keys (unchecked-get head "callbacks"))))
              ". An h/fn asks the POSITION for a contract, and an unclaimed "
              "slot has none to give: it would cross as an ordinary function "
              "whose return the library discards, so an intent it returned "
              "would never dispatch. Declare " (pr-str k) " in :callbacks, or "
              "hand a plain function.")
         :declare-the-slot-or-hand-a-plain-function
         {:host     (unchecked-get head "displayName")
          :position k
          :declared (into #{} (keys (unchecked-get head "callbacks")))}))

(defn- host-entry
  "ONE host prop into the emitted object — [[host-element]]'s reducing
  function, and the position table's second row where
  [[convert-entry]] is its first.

  Same discipline as its native sibling, and deliberately the same
  lookups: the literal `:key` is skipped in-loop (React's contract, not
  an attribute), a keyword or symbol key is answered by its cached
  [[prop-slot]] — React name plus `reserved?`/`event?`/`ref?` in one
  read — and a reserved emitted slot is never written, because the props
  object handed to React does have a prototype even though the caches no
  longer do.

  The one difference from [[convert-entry]] is the whole of HD-011: what
  a position MEANS here comes from the DECLARATION rather than from the
  key's spelling. A declared slot takes its declared contract; an
  event-spelled slot the declaration does not name is refused rather
  than inferred; an `h/fn` at any slot nothing claimed is refused too,
  because the mark is a request for a contract and no position selected
  one ([[refuse-unclaimed-host-callback!]], rf2-2rtt6.116); everything
  else crosses shallowly. `event?` is gated on
  `keyword?` for the same reason the native walk gates it — a symbol
  spelled `on-click` shares the cache entry and is not an event
  position.

  ## The class slot is a POSITION here too (rf2-2rtt6.119)

  `className` is the one slot whose value has a coercion of its own —
  [[class-names]] — rather than the position's ordinary conversion, and
  that coercion belongs to the SLOT rather than to either walk. It was
  taken at the native position only, so the two crossings answered the
  same authored shape differently: `{:class [\"a\" nil :b]}` reached a
  native tag as `\"a b\"` and reached a declared foreign component as the
  JS array `[\"a\", null, \"b\"]` — `clj->js`'s answer, and not a class
  string at all. React writes that array to the DOM as `\"a,,b\"`
  wherever the component passes it on, so nothing threw and the styling
  was simply wrong.

  rf2-vrvv9 already settled the principle and applied half of it: at
  `className`, `id`, `role` and the `data-*`/`aria-*` families the value
  is bound for an HTML attribute, so a named value keeps `(name v)` there
  — *\"which is also the answer the NATIVE walk gives at the same
  names\"* ([[host-prop-value]]). That sentence is the law; the
  collection arm was the half of it still unwritten, because a collection
  never reached the named-value branch. Asking `class?` here — the flag
  the [[PropSlot]] already carries, so a declared-slot lookup pays one
  property read and a string key one compare — makes the two crossings
  agree at the class slot for **every** value shape.

  It composes, for the reason [[convert-entry]] composes: two spellings
  of one element's class are two map keys and one React slot, and letting
  the last write win drops a class silently."
  [^js head declared o k v]
  (if (keyword-identical? :key k)
    o
    (let [keyword-ish? (or (keyword? k) (symbol? k))
          ^PropSlot s  (when keyword-ish? (prop-slot k (name k)))
          slot         (if keyword-ish? (.-js-name s) (cached-prop-name k))
          reserved?    (if keyword-ish? (.-reserved? s) (reserved-name? slot))
          ref?         (if keyword-ish? (.-ref? s) (= ref-slot slot))
          class?       (if keyword-ish? (.-class? s) (identical? class-slot slot))
          event?       (if keyword-ish?
                         (and (.-event? s) (keyword? k))
                         (intent/event-prop? k))]
      (when-not reserved?
        (unchecked-set o slot
          (cond
            ref?
            (check-ref! k v)

            (contains? declared slot)
            (intent/lower-declared-prop k v (get declared slot))

            ;; The slot's own coercion, and the slot's own composition —
            ;; the same law [[convert-entry]] takes at the native
            ;; position, taken here so the two crossings agree
            ;; (rf2-2rtt6.119). Below the declaration, because HD-011's
            ;; whole point is that a DECLARED position means what the
            ;; declaration says it means.
            class?
            (class-names (unchecked-get o class-slot) v)

            :else
            (do (when (and event? (or (vector? v) (map? v)))
                  (refuse-undeclared-host-event! head k v))
                ;; Beside its sibling, and for the sibling's own stated
                ;; reason one indirection down (rf2-2rtt6.116). It is
                ;; last of the two because `event?` is a flag already
                ;; read, so that test costs a boolean, while this one
                ;; costs a `fn?` — and it is ahead of
                ;; [[host-prop-value]] because that function is where
                ;; the marked form would silently become an ordinary
                ;; one. Both live in the `:else` arm only: no claimed
                ;; slot pays either, and the NATIVE walk
                ;; ([[convert-entry]]) is not on this path at all.
                (when (intent/callback? v)
                  (refuse-unclaimed-host-callback! head k))
                ;; The SLOT, never the key: `:class` and `:className` are
                ;; one position, and the named-value rule is written
                ;; against where the value lands (rf2-vrvv9).
                (host-prop-value slot v)))))
      o)))

(defn- host-element
  "One declared foreign component as a React element — THE CROSSING
  (HD-011). Runs inside the render window of the boundary that wrote it,
  like every other lowering in this walk, so a declared `:event`
  contract closes over that boundary's frame-locked dispatch and a
  crossing written outside any boundary is the same loud
  `:rf.error/hicasso-intent-outside-boundary` an intent vector raises.

  One pass over the attr map, mirroring [[convert-props]] with the
  position table's second row swapped in for the first: fold a `:&`
  remainder under the owned-literal law (the same one merge, at a third
  position), extract `:key` (React's contract, not an attribute), refuse
  a reserved `:ref` vector and pass a callback ref through untouched,
  lower every DECLARED slot by its declared contract
  ([[re-frame.hicasso.impl.intent/lower-declared-prop]] — an
  `h/fn` takes the contract's wrapper, an intent vector or key-map
  lowers as at a native position), refuse an event-spelled intent at an
  UNDECLARED slot and an `h/fn` at any UNCLAIMED one, and convert
  everything else shallowly ([[host-prop-value]]) under its camelCased
  name.

  Children are trailing forms converted hiccup→element and handed to the
  foreign component as ordinary React children — HD-011's third default.

  The element's TYPE is whatever the declaration put in its `gate`
  slot, and that is where the `:ssr` policy lives: under `:client-only`
  and `{:fallback …}` it is the gate ([[mint-host-gate!]]), one fiber
  and one hook, with the foreign component behind it once the markup is
  adopted; under `:ssr :render` it is the foreign component itself and
  the crossing costs neither. Everything else is unchanged by that
  choice: the props object built here is the object the foreign
  component receives either way, `:key` is React's on the crossing's
  element where it always was, and the component's hooks, state and
  refs stay React's affair under React's rules — which is the whole
  point of the door, and what keeps HD-020's ≤2-hook budget a statement
  about Hicasso's BOUNDARIES. A gate is not one: no frame, no
  subscription, no body."
  [argv]
  (let [^js head   (nth argv 0)
        declared   (unchecked-get head "callbacks")
        has-props? (props-map? argv 1)
        props      (merge-caller (if has-props? (nth argv 1) {}))
        js-props   (reduce-kv (partial host-entry head declared) #js {} props)]
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
;; `:ssr` policy, so the crossing is hard `:client-only` and that is
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
;;    migration codemod (rf2-2rtt6.106). A refusal that is right in
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
  a `displayName` for its refusal messages and an EMPTY roster. The
  escape has no `mint-host!` and mints nothing per site — this is one
  module-level value, and `callbacks` is a ClojureScript map because
  that is what the refusals project into their `:declared` ex-data."
  #js {"displayName" "[:>]"
       "callbacks"   {}})

(def ^:private raw-gate
  "THE ONE GATE, shared by every `[:>]` crossing on every page.

  A one-hook component whose `useSyncExternalStore` answers `false` from
  its SERVER snapshot and `true` from its client one — the same triple
  [[mint-host-gate!]] uses, with the placeholder fixed at `nil`, which
  is what `:client-only` already compiles to at the door.

  **Shared rather than minted per component**, and that is a ruling
  rather than a saving. A component-keyed cache is the identity-keyed
  auto-hosting HD-011 rejected, wearing a different hat; it also cannot
  be built, because React's built-in wrapper types (`Fragment`,
  `Suspense`, `StrictMode`, `Profiler`) are `Symbol.for` values and
  ES2024 excludes REGISTERED symbols as `WeakMap` keys by design. One
  constant type is stable for every component including those, and a
  runtime component swap at one site keeps the gate's fiber while
  remounting the inner subtree — which is the correct grain for the
  escape's first named use case.

  **The carrier is two slots on the gate's own props**, `c` and `p` —
  the shape [[boundary-element]] already uses for `rfProps` — so the
  object the foreign component receives is exactly the one
  [[raw-element]] built, with nothing of ours in it. Carrying the
  component BESIDE the converted props and stripping it inside would
  cost a shallow copy per crossing per render and leave an internal key
  one bug away from reaching React; two slots make that leak
  unrepresentable instead. Children ride on the OUTER element via
  [[make-element]]'s existing arms and are forwarded here as
  `createElement`'s third argument — which is why the childless case is
  its own branch: passing `undefined` as a third argument WRITES
  `children` onto the inner props, and conversion parity with the door
  is a claim about the object the component receives."
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

(defn- ^boolean react-exotic?
  "Is `x` one of React's built-in wrapper TYPES — `Fragment`,
  `Suspense`, `StrictMode`, `Profiler`, and whatever it ships next?

  They are all `Symbol.for(…)` values, so the test is the JS type and
  not a roster: enumerating them would false-refuse the next one React
  adds, and a hatch that refuses a legal component is worse than one
  that passes an illegal value through to React's own error."
  [x]
  (identical? "symbol" (goog/typeOf x)))

(defn- ^boolean react-wrapper-object?
  "Is `x` one of React's wrapper OBJECTS — a `memo` or `lazy` product, a
  context, a consumer, a `forwardRef`? All of them are plain objects
  carrying a `$$typeof` brand, and asking for the brand is what keeps
  this open to the ones React has not shipped yet. A ClojureScript map,
  vector, set, record or keyword is not a plain object and never reaches
  the second test."
  [x]
  (and (object? x) (some? (unchecked-get x "$$typeof"))))

(defn- raw-component-shape
  "What sits in the Component position, NAMED rather than printed —
  [[key-shape]]'s discipline, applied at the one crossing in this codec
  most likely to be handed a foreign or cyclic value. TOTAL, so no arm
  falls through to the value itself."
  [c]
  (cond
    (string? c)              "a string"
    (keyword? c)             "a keyword"
    (symbol? c)              "a symbol"
    (boundary-head? c)       "a defview product"
    (host-head? c)           "a defhost product"
    (react/isValidElement c) "a React element"
    (map? c)                 "a map"
    (vector? c)              "a vector"
    (set? c)                 "a set"
    (coll? c)                "a collection"
    (ifn? c)                 "callable, but not a function"
    :else                    "a foreign object"))

(defn- raw-component-fix
  "The recovery sentence for one confusion — the discriminating half of
  the refusal, which is what makes a single error id enough."
  [c]
  (cond
    (or (string? c) (keyword? c) (symbol? c))
    (str "A tag belongs to the grammar, not to this position: write the tag "
         "— [:div …] — or, for a tag chosen at runtime, a computed KEYWORD "
         "head, which keeps the parse and the controlled-input door that "
         "[:> \"input\" …] would silently drop.")

    (boundary-head? c)
    (str "A Hicasso view is a head in its own right: write [my-view …]. "
         "Mounted raw it would read rfProps, get undefined, and receive nil "
         "props — silently.")

    (host-head? c)
    (str "A declared host is a head in its own right: write [my-host …]. "
         "The escape is for what a declaration cannot express, and this "
         "one already is a declaration.")

    (react/isValidElement c)
    (str "An element is a legal CHILD, never a type: put it in child "
         "position, or hand [:>] the component it was built from.")

    (ifn? c)
    (str "It is callable, but React takes a real function: wrap it — "
         "(fn [props] (f props)) — or declare the crossing with defhost.")

    :else
    (str "[:>] takes what React accepts as an element type: a function or "
         "class component, one of React's built-in wrappers (Fragment, "
         "Suspense, …), or a memo / lazy / forwardRef / context value.")))

(defn- fail-raw-component!
  [c]
  (let [shape (raw-component-shape c)]
    (fail! :rf.error/hicasso-raw-not-a-component
           'front.codec/raw-element
           (str "[:>] was handed " shape " in the Component position. "
                (raw-component-fix c))
           :hand-the-escape-a-component-react-accepts
           {:shape shape})))

(defn- raw-component
  "The Component slot of a `[:>]` vector, or a loud refusal.

  The accepted set is what React 19's reconciler mints a fiber for —
  functions (function and class components alike), the built-in
  `Symbol.for` exotics, and objects branded with a `$$typeof` — MINUS
  three deliberate narrowings:

  - **`nil`**, which is the classic broken-interop symptom (`:default`
    against a library with no default export) and gets the door's own
    diagnosis. `[:>]` with nothing after it lands here too.
  - **strings and keywords**, because the GRAMMAR owns tags. Reagent's
    `[:> \"input\" …]` took its controlled-input wrapper on exactly this
    path, so accepting a string here would silently drop caret and IME
    protection at a site a migrator ports verbatim. A dynamic tag is a
    computed KEYWORD head, which keeps the parse and the controlled
    door.
  - **`defview` and `defhost` heads**, which React would accept and
    which are silent breakage: a `defview` product is `fn?`-true, so a
    bare \"is it a function\" test mounts the shell raw, the body reads
    `rfProps`, gets `undefined`, and receives nil props. That is the
    shape a migration produces.

  Refused HERE, in the owner's render window and on the server too,
  rather than delegated to React: React's refusal is minted at fiber
  creation, which behind [[raw-gate]] is post-adoption and client-only,
  and its message names `typeof type` — so a keyword, map, vector, set
  or record all read *\"got: object\"*."
  [argv]
  (let [c (nth argv 1 nil)]
    (cond
      (nil? c)
      (fail! :rf.error/hicasso-raw-no-component
             'front.codec/raw-element
             (str "[:>] was given " (if (< (count argv) 2) "no component at all" "nil")
                  " in the Component position. The usual cause is a JS import "
                  "that resolved nothing — e.g. `:default` against a library "
                  "with no default export. Write [:> Component props & "
                  "children], or declare the crossing with defhost.")
             :hand-the-escape-a-real-component
             {:argv-count (count argv)})

      ;; Ahead of `fn?`, because both marked heads ARE functions or carry
      ;; one and React would accept them.
      (boundary-head? c)   (fail-raw-component! c)
      (host-head? c)       (fail-raw-component! c)

      (fn? c)              c
      (react-exotic? c)    c

      ;; Ahead of the `$$typeof` accept, because a React ELEMENT is an
      ;; object carrying one — and an element is a legal CHILD, never a
      ;; type.
      (react/isValidElement c) (fail-raw-component! c)

      (react-wrapper-object? c) c
      :else                     (fail-raw-component! c))))

(defn- raw-element
  "One `[:> Component props & children]` vector as a React element.

  The indices are the door's, shifted by one: the component is at 1, the
  attribute map at 2 when there is one, children from 3 or from 2.
  `[:> Component]` with neither is legal.

  Everything after the component is [[host-element]]'s own body: fold a
  `:&` remainder under the owned-literal law BEFORE conversion (HD-023
  clause (d) — the conversion that follows is the position's own),
  extract `:key` onto the crossing's outer element, and walk every prop
  through [[host-entry]] against an EMPTY declared roster. Children lower
  eagerly, here, inside the render window of the boundary that wrote the
  crossing — so an intent closure in a `[:>]` child captures that
  boundary's frame-locked dispatch and fires into the right frame however
  much later the foreign component renders it.

  The element's TYPE is always [[raw-gate]]; the component rides in the
  carrier's `c` slot."
  [argv]
  (let [component  (raw-component argv)
        has-props? (props-map? argv 2)
        props      (merge-caller (if has-props? (nth argv 2) {}))
        js-props   (reduce-kv (partial host-entry raw-crossing {}) #js {} props)
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
  [[fragment-head?]]'s own reason, and it is sharper here: `:>` was NOT
  an error before rf2-2rtt6.103 — [[hiccup-tag?]] accepted any keyword
  that is not `:<>`, so `[:> Foo {}]` asked React for an element
  literally named `<>`. An `identical?` test would work under
  `:advanced`, where the build interns keyword literals, and silently
  route every escape back into that native path everywhere else."
  [head]
  (= :> head))

(defn- hiccup-tag?
  "Is this head a native tag? Asked AFTER the fragment and raw arms in
  [[vec->element]]'s `cond`, which is its only caller — so the
  `(not (fragment-head? head))` this body used to re-ask was provably
  dead, and every native tag on every page paid the fragment `=` twice
  for it. Dropping it is what pays for [[raw-head?]] exactly: a keyword
  tag paid two `=` and one type predicate before, and pays two `=` and
  one type predicate now. This codec has costed a `keyword?`
  short-circuit at ±51–67% of a walk, so a new head test is not free by
  assertion on this surface — it is free by accounting."
  [head]
  (or (keyword? head) (symbol? head) (string? head)))

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
      (raw-head? head)        (raw-element argv)
      (hiccup-tag? head)      (native-element argv)
      (boundary-head? head)   (boundary-element argv)
      (host-head? head)       (host-element argv)
      :else
      (throw (ex-info (str "Hiccup head " (pr-str head) " is not a valid element head; "
                           "use a tag keyword, :<>, :>, a view minted by defview, or a "
                           "host minted by defhost. "
                           "A plain function in head position is never a silent "
                           "embedding — call it, or make it a view. A raw JS "
                           "component is never a silent embedding either — "
                           "declare it with defhost, or write the escape "
                           "[:> Component …] (HD-011). "
                           "[:rf.error/hicasso-bad-head]")
                      {:rf.error/id :rf.error/hicasso-bad-head
                       :where       'front.codec/vec->element
                       :reason      (if (fn? head)
                                      "A plain function in head position is a loud error (HD-016)."
                                      "Hiccup head must be a tag keyword, :<>, :>, a defview product, or a defhost product.")
                       :recovery    (if (fn? head) :call-it-or-make-it-a-view :supply-a-valid-hiccup-head)})))))

(defn as-element
  "Interpret any hiccup form. `nil` and `false` render nothing; `true` is
  an error (HD-016); an existing React element passes through.

  ## Why `string?` is asked before `vector?` (rf2-2rtt6.63)

  The branches are MUTUALLY EXCLUSIVE — a value satisfies at most one of
  `nil?`, `false?`, `string?`, `vector?`, `number?`, `seq?`, `true?` —
  so their order cannot change an answer, only what each population
  pays. And `vector?` is the dear one: it is `IVector` satisfaction,
  which for anything without the marker falls through to
  `native-satisfies?`, so every string, number and lazy seq on a page
  used to prove itself not-a-vector the expensive way before reaching
  its own branch.

  Costed over the census page's whole child roster before it was changed
  (`walk_vs_reagent_app` candidate table: 1,908 children, of which 567
  strings, 1,201 vectors, 69 numbers, 71 seqs): the shipping order 22.5
  ns/child, this order **8.9** — and on the strings alone 31.7 -> 5.3.
  The vectors pay one extra `typeof` and the whole population still
  reads 2.5x cheaper. This is the stage on which stock Reagent was
  furthest ahead of us: its `as-element` asks one `js-val?`
  (`goog/typeOf x !== \"object\"`) and returns a string on the first
  branch, and it read 8.8 ns/string against our 33.5."
  [x]
  (cond
    (nil? x)         nil
    (false? x)       nil
    (string? x)      x
    (vector? x)      (vec->element x)
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

(defn root-element
  "[[as-element]] for a hiccup form written OUTSIDE any boundary body —
  the root, or an outward React bridge that mounts Hicasso from foreign
  code (rf2-2rtt6.39).

  Every other element in the tree is created by an ancestor body, which
  is already running inside
  [[re-frame.hicasso.impl.intent/with-frame]]. This is the one
  creator that is not, so it is the one creator that has to NAME the
  frame — which an outward bridge takes explicitly anyway. Binding it
  here rather than in the arm's mount keeps the reason next to the
  mechanism.

  `*dispatch*` is deliberately NOT bound: the frame is an identity the
  root genuinely has, while a frame-locked dispatch is what makes an
  intent vector legal, and an intent written outside a boundary stays the
  loud `:rf.error/hicasso-intent-outside-boundary` it was."
  [frame-kw hiccup]
  (binding [intent/*frame* frame-kw]
    (as-element hiccup)))

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
  spelling from a cold build's (rf2-ani6y)."
  []
  (doseq [k (js/Object.keys tag-cache)] (js-delete tag-cache k))
  (doseq [k (js/Object.keys prop-cache)] (js-delete prop-cache k))
  (seed-prop-cache! prop-cache)
  nil)
