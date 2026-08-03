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
            [reagent2.impl.diag :as diag]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Tag parsing — :div.cls#id shorthand
;;
;; Per IMPL-SPEC §7.3: same regex stock Reagent has used for years. We
;; lift the pattern byte-for-byte and cache parses on first sight.
;; ---------------------------------------------------------------------------

(def ^:private re-tag
  "Regex for parsing CSS-style id and class from a Reagent tag keyword.
   Captures `tag`, an optional `#id`, then optional `.class.class…`
   shorthand — in THAT order. `#id` MUST precede the `.class` segments
   (the `:div#id.a.b` form); the class-before-id form (`:div.a#id`) does
   NOT match and yields a nil tag. This mirrors stock Reagent's own
   id-before-class regex (a documented constraint, not a regression)."
  #"([^\s\.#]+)(?:#([^\s\.#]+))?(?:\.([^\s#]+))?")

;; Note on field name `className` (rather than `class`): in JS-target
;; CLJS, `class` is a reserved word and the compiler munges it to
;; `class$` on the deftype's instance fields, while `(.-class obj)`
;; reads from the unmangled `.class` slot — so the read silently
;; returns undefined. We use `className` to match React's prop name
;; and to dodge the munging entirely.
(deftype ^:private HiccupTag [tag id className])

;; ---------------------------------------------------------------------------
;; Reserved `:rf/*` heads fail loud (rf2-01zvu — the CLIENT half of the
;; rf2-j81hs SS4 ruling; the two JVM emitters already carry the server half).
;;
;; rf2-j81hs made keyword heads HTML elements EVERYWHERE. That leaves a
;; head in the framework-reserved `:rf/*` scheme sailing straight through
;; the DOM-tag grammar: `:rf/suspense-boundry` (typo) has a `name` that
;; matches `re-tag`, so the renderer would paint a phantom
;; `<suspense-boundry>` and say nothing — the exact silent mis-render that
;; bead exists to kill, displaced by one keystroke.
;;
;; The guard is TOTAL — every `:rf/*` / `:rf.<area>/*` head is rejected,
;; with no allow-list carve-out. The `:rf/*` root is framework-owned
;; (Conventions §Reserved namespaces) so no legitimate author element lives
;; there, and NO `:rf/*` head has a client render-tree meaning: the one
;; reserved hiccup head that exists, `:rf/suspense-boundary`, is a
;; streaming-SSR-only marker consumed by the JVM shell walker. The
;; recognised interop heads (`:<>`, `:>`, `:r>`, `:f>`) are unnamespaced
;; and are consumed by `vec-to-elem` before the tag grammar is reached.
;;
;; COST: the guard runs in `parse-tag` (the cache-MISS path, and the
;; `reagent2.dom.server` direct-call path) AND once more at the top of
;; `cached-parse`, BEFORE the cache lookup (rf2-sgbna). A `parse-tag`-only
;; guard was reached only on a cache MISS, but the reserved keyword `:rf/x`
;; and a valid STRING head "rf/x" share the same cache key "rf/x", so an
;; app that first rendered the string form let the later reserved keyword
;; ride the cache-HIT path and skip the reject entirely — see the note on
;; `cached-parse`. The pre-lookup guard is a single cheap `reserved-rf-head?`
;; predicate (keyword? + namespace, false-fast for the common unnamespaced
;; head), so a legitimate head still resolves through the cache at
;; essentially the same cost.
;;
;; ALWAYS-ON: no `goog.DEBUG` gate. This is a correctness reject on a
;; runtime DATA branch, so it survives `:advanced` + `goog.DEBUG=false`
;; exactly as the sibling `:rf.error/template-empty-vector` throw does.
;; rf2-2hkfy is why that is PINNED rather than asserted in a comment: a
;; rejection described as "always-on" there was in fact goog.DEBUG-gated
;; and so was absent from the build users ship. The pin is
;; `reagent2.impl.template-reserved-head-elision-prod-test`, which runs
;; under the `:browser-test-prod-elision` build (`:advanced` +
;; `goog.DEBUG=false`) and asserts the OBSERVABLE OUTCOME — that no
;; phantom element is painted — not merely that something threw.
;; ---------------------------------------------------------------------------

(defn- ^boolean reserved-rf-head?
  "True when `head` is a keyword in the framework-reserved `:rf/*` scheme —
  the bare `rf` namespace (`:rf/suspense-boundary`) or a dotted subsystem
  segment under it (`:rf.ssr/…`). Mirrors `re-frame.ssr.emit/reserved-rf-head?`;
  duplicated rather than shared because reagent-slim is bundle-isolated and
  MUST NOT `:require` re-frame.*."
  [head]
  (if-let [ns* (and (keyword? head) (namespace head))]
    (or (= "rf" ns*)
        (str/starts-with? ns* "rf."))
    false))

(defn- reject-reserved-rf-head!
  "Throw `:rf.error/invalid-hiccup-head` for an unrecognised `:rf/*` head.

  Canonical thrown-error shape per Spec 009 §The thrown-error shape
  (rf2-vvixub), replicated INLINE — the central `re-frame.error` builder is
  not reachable from this bundle-isolated adapter (see the sibling
  `:rf.error/template-empty-vector` throw in `vec-to-elem`). The id and the
  `:recovery` token are the ones the JVM emitters' reserved-head arm already
  carries, so server and client teach one grammar.

  `element` is the WHOLE offending hiccup vector and is REQUIRED (rf2-vzno0).
  Spec 009's `:rf.error/invalid-hiccup-head` row promises the payload pair
  `:head`, `:element` on BOTH arms, and the JVM arm
  (`re-frame.ssr.emit/reject-reserved-rf-hiccup-head!`) supplies both. This
  arm stamped `:head` alone, so a diagnostic consumer reading the documented
  payload got the head server-side and nil client-side for the same
  category — exactly the cross-host friction the shared id exists to avoid.
  Required rather than optional because every live caller has the vector in
  hand: an optional arity would only re-open a path that stamps `:element
  nil`, which is a claim the catalogue does not permit."
  [head element]
  (when (reserved-rf-head? head)
    (let [reason (str "hiccup vector head " head " is in the framework-reserved "
                      ":rf/* namespace but is not a head this renderer "
                      "recognises. The :rf/* scheme is framework-owned "
                      "(Conventions §Reserved namespaces), so this cannot be an "
                      "author DOM element — rendering it would paint a phantom <"
                      (name head) "> element silently. No :rf/* head has a "
                      "client meaning (:rf/suspense-boundary is a streaming-SSR "
                      "marker, server-only). Check the spelling, or use an "
                      "unreserved keyword if you meant a custom element.")]
      (throw (ex-info (str reason " [:rf.error/invalid-hiccup-head]")
                      {:rf.error/id :rf.error/invalid-hiccup-head
                       :where       'reagent2.template/parse-tag
                       :reason      reason
                       :recovery    :use-a-recognised-reserved-head-or-an-unreserved-keyword
                       :head        head
                       :element     element})))))

(defn parse-tag
  "Parse a hiccup tag keyword into its `:tag` / `:id` / `:class` parts.

  Returns a `HiccupTag` record. The `class` field carries whitespace-
  joined classes when the tag has `.foo.bar` shorthand; nil when there
  are no class shorthand parts. The `id` field is non-nil only when the
  tag has a `#id` shorthand part. `tag` is the bare element name string.

  CONSTRAINT (matches stock Reagent): `#id` must precede the `.class`
  segments. The id-before-class form is supported; the class-before-id
  form (`:div.a#id`) is NOT — `re-tag` returns nil for it and the result
  carries a nil tag. Use `:div#id.a.b`, never `:div.a.b#id`.

  Examples:

    (parse-tag :div        [:div])         → HiccupTag{tag \"div\" id nil  class nil}
    (parse-tag :div.cls    [:div.cls])     → HiccupTag{tag \"div\" id nil  class \"cls\"}
    (parse-tag :div#id     [:div#id])      → HiccupTag{tag \"div\" id \"id\" class nil}
    (parse-tag :div#id.a.b [:div#id.a.b])  → HiccupTag{tag \"div\" id \"id\" class \"a b\"}
    (parse-tag :div.a.b#id [:div.a.b#id])  → HiccupTag{tag nil   id nil  class nil}  ; NOT supported

  Rejects an UNRECOGNISED head in the framework-reserved `:rf/*` scheme —
  see `reject-reserved-rf-head!` (rf2-01zvu). `element` is the whole hiccup
  vector `hiccup-tag` heads; it is carried ONLY so the reject can stamp the
  `:element` payload slot Spec 009 promises (rf2-vzno0) and takes no part in
  parsing. Every call site has it in hand, so it is required, not optional."
  [hiccup-tag element]
  (reject-reserved-rf-head! hiccup-tag element)
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
;;   - `cached-prop-name` — never caches a reserved name. (Belt: keeps the
;;     shared cache clean.)
;;   - `kv-conv` / `top-prop-conv` — drop reserved camelCased names before
;;     writing to the per-render JS props object. (Braces: the actual
;;     prototype-pollution chokepoint, because the props object is what
;;     flows into `React.createElement`.)
;;
;; PROPS OBJECTS STAY ON THE DEFAULT PROTOTYPE, and that is not negotiable:
;; React's renderer calls `styles.hasOwnProperty(...)` on nested objects like
;; `:style` when diffing inline styles (`react-dom` ReactDOMHostConfig), and a
;; null-prototype object throws `TypeError: styles.hasOwnProperty is not a
;; function`. So for anything reaching React the reserved-key filter is the
;; sole defence — sufficient on its own because every pollution path runs
;; through the filtered `aset` chokepoints.
;;
;; THE CACHES ARE NOT PROPS OBJECTS (rf2-lhdp0). `tag-name-cache` and
;; `prop-name-cache` are module-private lookup tables; nothing in them is ever
;; handed to React, so the constraint above does not reach them and they take
;; `Object.create(null)` instead. That is what lets their HIT path — the path
;; a mount takes once per element and once per prop — drop BOTH guards it used
;; to carry. See `own-key?`'s epitaph below.
;; ---------------------------------------------------------------------------

(defn- ^boolean reserved-prop-key?
  "True for the three JS property names that must NEVER be `aset` from
  user-controlled input: writing to one of them mutates the target's
  prototype instead of creating an own property, leaking inherited slots
  across every subsequent prop-map conversion. Per rf2-dwds9 MEDIUM.

  THE ROSTER IS THE FUNCTION BODY (rf2-lhdp0). This was a
  `PersistentHashSet` and a `contains?` — a string hash plus a hash-map
  probe, for a roster of three, asked once per prop occurrence on every
  element of every mount. Costed side by side on one run over the census
  page's own prop names: set 53.7 ns/op, three `===` compares 10.4, a
  null-prototype index derived from the set 15.8. The chain wins on the
  clock, and it is the only one of the three that needs no data structure
  at all — so the roster stops being a thing to look up and becomes
  something to read. `front/codec`'s `reserved-name?` is the same shape
  for the same reason."
  [n]
  (or (identical? "__proto__" n)
      (identical? "prototype" n)
      (identical? "constructor" n)))

;; WHY THERE IS NO `own-key?` ANY MORE (rf2-tsuk6, closed structurally by
;; rf2-lhdp0).
;;
;; The caches key entries by user-controlled names: `tag-name-cache` on tag
;; heads (a string head like "hasOwnProperty" is accepted) and
;; `prop-name-cache` on prop-key names (`{:hasOwnProperty x}` is accepted).
;; When the caches were `#js {}` this raised two hostile questions on EVERY
;; lookup. Testing a hit with `(.hasOwnProperty cache n)` read the method OFF
;; the cache object, so caching an entry literally named "hasOwnProperty"
;; shadowed the method and the next lookup invoked a string as a function
;; (rf2-tsuk6); and an inherited name like `toString` could falsely hit a
;; value nobody cached. The answer was `Object.prototype.hasOwnProperty.call`
;; plus a reserved-name check, both on the HIT path.
;;
;; `Object.create(null)` answers both questions in the cache's CONSTRUCTION.
;; There is no prototype chain, so a lookup can only ever return an own
;; property: no inherited name can hit, and no entry can shadow a method the
;; lookup does not use. The hit path is now one `unchecked-get` and an
;; `undefined?` test. The write guard survives on the MISS branch — the only
;; branch that writes — where it runs once per distinct literal for the life
;; of the build instead of once per element per mount.
;;
;; This is strictly safer than the guard it replaces, not a trade: a cache
;; that CANNOT serve an inherited value beats one that promises to notice.
;; It is also where the bead's named term went: `cached-prop-name` read 2.0x
;; stock Reagent's over the census page's 1,489 prop occurrences before this
;; and 0.53x after, measured on the real function in the same instrument
;; either side of the change (rf2-lhdp0).

(def ^:private tag-name-cache (js/Object.create nil))

;; The cache key is the head's FULLY-QUALIFIED name, not its bare `name`
;; (rf2-01zvu). Keyed on `name` alone, `:button` and `:rf/button` collide on
;; the entry `"button"`: an app rendering ordinary `[:button …]` markup would
;; seed it, and a later `[:rf/button …]` would HIT the cache, never reach
;; `parse-tag`, and paint a phantom — the app's own markup silently
;; disarming the reserved-head guard. Namespaced heads are rare and
;; erroneous, so the `str` runs essentially never; the common unnamespaced
;; head keeps `name`'s exact cost. (Parsing itself is unaffected: `parse-tag`
;; reads `(name …)`, so the two spellings always produced the same
;; `HiccupTag` — the collision was harmless until this guard made the head's
;; namespace load-bearing.)
;;
;; NOTE (rf2-sgbna): the reserved-head phantom is now prevented at its
;; source — `cached-parse` rejects a reserved keyword head BEFORE this key
;; is even computed — so no cache key can serve a reserved head. The
;; fully-qualified key remains to keep distinct keyword spellings distinct;
;; it is no longer the reserved-head guard. (This very qualification is also
;; what made `:rf/x` alias the string head "rf/x", the collision rf2-sgbna
;; closes.)
(defn- cache-key [k]
  (let [n (name k)]
    (if-let [ns* (and (keyword? k) (namespace k))]
      (str ns* "/" n)
      n)))

(defn- cached-parse [k element]
  ;; Reject a reserved `:rf/*` keyword head BEFORE consulting the cache
  ;; (rf2-sgbna). `reject-reserved-rf-head!` also runs inside `parse-tag`,
  ;; but `parse-tag` is reached only on a cache MISS. The fully-qualified
  ;; `cache-key` for the reserved keyword `:rf/x` is the string "rf/x" —
  ;; IDENTICAL to the key a valid STRING head "rf/x" seeds (a string's
  ;; `cache-key` is its own name). So an app that first renders the string
  ;; form seeds "rf/x", and the later reserved keyword `:rf/x` takes the
  ;; cache-HIT path, never reaches `parse-tag`, and paints a phantom
  ;; <rf/x> — the type-aliased cache silently disarming the fail-loud
  ;; guard. Rejecting here, before the lookup, closes that path. The reject
  ;; is keyword-only (`reserved-rf-head?` is false-fast for the common
  ;; unnamespaced head and for any string head), so a legitimate head pays
  ;; a single cheap predicate and still resolves through the cache.
  (reject-reserved-rf-head! k element)
  (let [n (cache-key k)
        v (unchecked-get tag-name-cache n)]
    ;; The cache has no prototype, so a non-undefined answer is necessarily
    ;; an own entry somebody cached — the hit test IS the lookup (rf2-lhdp0).
    (if (undefined? v)
      (let [v' (parse-tag k element)]
        (when-not (reserved-prop-key? n)
          (unchecked-set tag-name-cache n v'))
        v')
      v)))

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
    (let [name-str (name dashed)]
      ;; CSS custom properties (`--gap`) are case-sensitive and must NOT
      ;; be camelCased — `--gap` split on `-` would yield `"Gap"`,
      ;; silently dropping the variable. React's style handling preserves
      ;; `--`-prefixed names verbatim; the pure server serializer already
      ;; does too, so preserving here closes the live-vs-server style
      ;; parity gap (rf2-ygknv finding 2). Guarded on the `--` prefix so
      ;; only custom properties are exempted from kebab→camel.
      (if (str/starts-with? name-str "--")
        name-str
        (let [[start & parts] (str/split name-str #"-")]
          (if (dont-camel-case start)
            name-str
            (apply str start (map capitalize parts))))))))

(def ^:private prop-name-cache
  (doto (js/Object.create nil)
    (unchecked-set "class" "className")
    (unchecked-set "for" "htmlFor")
    (unchecked-set "charset" "charSet")))

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
  `constructor`) are never cached. The downstream `convert-props` writes
  drop these too (see `kv-conv`), so a malicious key cannot reach the
  React props object.

  The cache has no prototype (rf2-lhdp0), so the HIT path is one property
  load and an `undefined?` test — no own-property guard, no reserved-name
  probe. A reserved name simply never occupies an entry, so it misses every
  time and takes the conversion path, which answers it verbatim:
  `dash-to-prop-name` of each of the three returns the name unchanged (no
  `-`, no `--` prefix, no `data`/`aria` start), exactly what the previous
  early-return produced."
  [k]
  (if (or (keyword? k) (symbol? k))
    (let [n (name k)
          cached (unchecked-get prop-name-cache n)]
      (if (undefined? cached)
        (let [v (dash-to-prop-name k)]
          (when-not (reserved-prop-key? n)
            (unchecked-set prop-name-cache n v))
          v)
        cached))
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

(defn clear-warned-keyword-prop!
  "Reset the keyword-prop warn-once cache to empty; returns nil.

  The user-facing contract is `warn once per [k name-of-v] pair` for the
  process lifetime, so this cache is a `defonce`. Tests, however, must
  re-arm it between cases — otherwise a sibling test that already warned
  for a given pair silently swallows a later test's same-pair warning
  (the rf2-4edk test-isolation hazard, applied here per rf2-qy6cl). The
  reagent-slim adapter ns wires this into the chained
  `:adapter/clear-warn-once-caches!` late-bind hook (via
  `spine/install-clear-warn-once-step!`) so `make-reset-runtime-fixture`
  clears it alongside every other adapter's warn-once cache. Reset-only;
  no production behaviour changes (the hook is a test-fixture surface)."
  []
  (reset! warned-keyword-prop #{})
  nil)

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

(defn- ^boolean named? [x]
  (or (keyword? x) (symbol? x)))

(defn- ^boolean js-val? [x]
  (not (identical? "object" (goog/typeOf x))))

(declare convert-prop-value)

(defn- kv-conv
  "Reduce-kv step: convert one [k v] pair into the JS props object `o`.

  Used for NESTED maps (style objects, custom-component prop sub-maps)
  reached via `convert-prop-value`'s `map?` branch. At this depth there
  is no outer prop-name context — a `:style` sub-map's `:cursor` is the
  CSS property name, not an HTML attribute — so the per-key VALUE
  conversion uses the 1-arg `convert-prop-value`, which stringifies
  EVERY named value (`{:cursor :pointer}` → `cursor \"pointer\"`). This
  matches stock Reagent AND the pure server serializer
  (`dom/server.cljs`, whose `named->str` stringifies every style value),
  so the slim LIVE and SSR style paths agree — no live-vs-SSR hydration
  mismatch (rf2-fdm4rm). Routing nested values through the 2-arg
  (interop) form instead left keyword style values (`:cursor :pointer`)
  reaching React as RAW keywords, silently dropped by the CSSOM. The KEY
  is still camelCased via `cached-prop-name`. The TOP-LEVEL prop map is
  converted by `convert-props` with the native-aware `top-prop-conv`
  step instead, the seam that makes native-DOM keyword ATTRIBUTES
  stringify (per rf2-ygknv finding 1).

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
      ;; 1-arg form: nested-map values carry no outer prop-name context,
      ;; so stringify every named value (rf2-fdm4rm). See the docstring.
      (let [v' (convert-prop-value v)]
        (aset o k' v')
        o))))

(defn- top-prop-conv
  "Reduce-kv step for the TOP-LEVEL prop map of a hiccup element. `o`
  is the JS props object; `[k v]` the prop pair; `native?` whether the
  element is a native DOM/string tag (vs a `:>` interop / custom React
  component).

  For native DOM tags, keyword/symbol values stringify for ANY prop
  name — every prop on a real DOM element is an HTML attribute that
  takes a string value, and the pure server serializer already
  stringifies them unconditionally; so `[:button {:type :button}]`
  must reach React with `props.type === \"button\"` (rf2-ygknv finding
  1). For custom/interop components, the narrowed (interop) rule
  applies via the 2-arg `convert-prop-value` — a keyword like
  `:rf/foo` on a React-context Provider's `:value` is preserved.

  Reserved-key dropping (rf2-dwds9 MEDIUM) is unchanged."
  [native? o k v]
  (let [k' (cached-prop-name k)]
    (if (and (string? k') (reserved-prop-key? k'))
      o
      (let [v' (convert-prop-value k v native?)]
        (aset o k' v')
        o))))

(defn convert-prop-value
  "Convert a hiccup prop-map value `v` for prop-name `k` to a React-
  shaped JS value.

  Per IMPL-SPEC §7.2 (DECISION-2) + rf2-ygknv finding 1: keyword/symbol
  stringification is TARGET-AWARE.

    - NATIVE DOM/string tags (the 3-arg form with `native?` true):
      keyword/symbol values stringify for ANY prop name — every prop on
      a real DOM element is an HTML attribute taking a string value, and
      the pure server serializer stringifies them unconditionally, so
      the live React path must agree (`[:button {:type :button}]` →
      `props.type \"button\"`).

    - CUSTOM/INTEROP components (the 2-arg form, or `native?` false):
      keyword/symbol values stringify only for documented HTML-attribute
      prop names (`:class`, `:id`, `:role`, `:data-*`, `:aria-*`); other
      named values pass through unchanged (with a one-shot dev warning),
      so a keyword like `:rf/foo` on a React-context Provider's `:value`
      is preserved.

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
     (named? v)  (name v)         ; nested map values: stringify (CSS values)
     (map? v)     (reduce-kv kv-conv #js {} v)
     (coll? v)    (clj->js v)
     (fn? v)      v                ; pass through — preserves identity
     (ifn? v)     (fn [& args] (apply v args))
     :else        v))
  ([k v]
   ;; 2-arg form: CUSTOM/INTEROP semantics (the `:>` path + the public
   ;; Var's documented contract). Narrowed stringification per §7.2.
   (cond
     (js-val? v)  v
     (named? v)  (if (html-attr-name? k)
                    (name v)
                    (do
                      (when ^boolean js/goog.DEBUG
                        (warn-once-keyword-prop! k v))
                      v))
     (map? v)     (reduce-kv kv-conv #js {} v)
     (coll? v)    (clj->js v)
     (fn? v)      v                ; pass through — preserves identity
     (ifn? v)     (fn [& args] (apply v args))
     :else        v))
  ([k v native?]
   ;; 3-arg form: TARGET-AWARE. For a native DOM tag every prop is an
   ;; HTML attribute, so keyword/symbol values stringify regardless of
   ;; the prop name — matching the pure server serializer and React DOM.
   ;; For non-native targets we defer to the 2-arg interop semantics.
   (if (and native? (named? v) (not (js-val? v)))
     (name v)
     (convert-prop-value k v))))

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
                             (if (named? c) (name c) c)))
                         class)]
       (when (seq classes)
         (str/join " " classes)))
     (if (named? class)
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

(defn- collapse-class-keys
  "Fold a co-occurring `:className` into `:class` and drop `:className`,
  leaving a single canonical `:class` key.

  `:class` and `:className` both map to React's `className` prop via
  `cached-prop-name`, so leaving both keys in the map sends two writes
  to the same JS slot — the survivor is iteration-order dependent
  (PersistentArrayMap vs PersistentHashMap differ), silently dropping
  one class string. Mirrors the server path's `merge-shorthand`
  (which already `dissoc`'s `:className`) so React and SSR agree.

  Per stock Reagent, the prop-map `:class` is the value; a stray
  `:className` is treated as an additional class and merged with a
  space. When only `:className` is present it is renamed to `:class`
  so `set-id-class`'s shorthand merge has a single key to read. A no-op
  when neither `:className` nor `:class` is present.

  `:className` IS THE DISCRIMINATOR, so it is probed first (rf2-lhdp0).
  Every branch that does anything requires it; when it is absent — which is
  every element of idiomatic hiccup, where the spelling is `:class` — the
  answer is `props` unchanged and one probe has settled it. Asking
  `:class` first cost two probes on that path and three when `:class` was
  present, the extra one re-asking a question already answered."
  [props]
  (if (contains? props :className)
    (if (contains? props :class)
      (-> props
          (assoc :class (class-names (:class props) (:className props)))
          (dissoc :className))
      (-> props
          (assoc :class (:className props))
          (dissoc :className)))
    props))

(defn- convert-props
  "Convert a hiccup prop map `props` to a React-shape JS props object.
  `parsed` is the HiccupTag with id/class shorthand merged in.

  Target-awareness (rf2-ygknv finding 1): when `parsed`'s tag is a
  string the element is a NATIVE DOM tag — keyword/symbol prop values
  stringify for every attribute (HTML attributes are string-valued,
  matching the pure server serializer + React DOM). When the tag is a
  fn/class (`:>` interop / custom component) keyword values follow the
  narrowed interop rule and pass through unchanged. The discriminator
  is `(string? (.-tag parsed))`: `cached-parse` yields a string tag for
  DOM elements, while `interop-element`'s synthetic HiccupTag carries
  the component (fn/class) in the tag slot.

  Returns nil for empty input."
  [props ^HiccupTag parsed]
  (let [native?     (string? (.-tag parsed))
        collapsed   (collapse-class-keys props)
        class       (:class collapsed)
        normalised  (cond-> collapsed
                      class (assoc :class (class-names class)))
        with-shorthand (set-id-class normalised parsed)
        ^js js-props (when (seq with-shorthand)
                       (reduce-kv (fn [o k v] (top-prop-conv native? o k v))
                                  #js {} with-shorthand))]
    js-props))

;; ---------------------------------------------------------------------------
;; React-key extraction (per stock Reagent)
;;
;; A user can attach a key via `^{:key "k"}` meta on the hiccup vector,
;; OR via `:key` in the props map. We honour the meta first, falling
;; back to the props map.
;;
;; ONE rule, TWO entry points. `react-key` is the rule and takes the props
;; slot; `get-react-key` finds the slot first, for a caller that holds only
;; a vector. The constructors that have already shaped their argv
;; (`native-element`, `fragment-element`) call the rule; `expand-seq`'s
;; per-child missing-key DEBUG warning — which runs on the RAW list children
;; of EVERY head shape — and the cold component constructors call the finder.
;;
;; That warning path is why each interop head keeps a
;; case here — including `:r>`: `raw-element` owns `:r>` key stamping during
;; construction, but a raw `:r>` child (e.g.
;; `(for [x xs] [:r> C #js {:key x}])`) still flows through the missing-key
;; check, and React honours `props.key` on a `:r>` element, so the case
;; reads the js-props `.key`. Dropping it would spuriously warn on a
;; properly-keyed `:r>` list child.
;; ---------------------------------------------------------------------------

(defn- react-key
  "THE RULE: `^{:key …}` meta on the hiccup vector wins; failing that, the
  props map's `:key`. `props` is the vector's props slot, nil when it has
  none — `(:key nil)` is nil, so the absence needs no branch.

  This is what an element constructor calls. Locating the props slot is
  precisely what `hiccup-shape` has just done for it, so re-deriving the
  slot from the head — `get-react-key`'s `nth`/`case` ladder below — is
  work the hot path does not owe: 133.9 → 62.8 ns per element, the two
  costed side by side on one run over the census page (rf2-lhdp0)."
  [argv props]
  (or (some-> (meta argv) :key)
      (:key props)))

(defn- get-react-key
  "[[react-key]] for a caller holding only a hiccup vector, which must
  find the props slot before it can read it: the slot is at index 2 under
  an interop head and index 1 otherwise."
  [v]
  (when (vector? v)
    (case (nth v 0 nil)
      (:> :f>) (react-key v (let [h (nth v 2 nil)] (when (map? h) h)))
      ;; `:r>` reaches here only via expand-seq's missing-key warning (see
      ;; the header note); its props slot is a JS object, so the rule's
      ;; `:key` lookup does not apply — read the `.key` React honours.
      :r>      (or (some-> (meta v) :key) (some-> (nth v 2 nil) (.-key)))
      (react-key v (let [h (nth v 1 nil)] (when (map? h) h))))))

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

;; A null-prototype index DERIVED from `void-tags`, so the roster above stays
;; the single source of truth and there is no second list to drift
;; (rf2-lhdp0). The probe is asked once per DOM element of every mount, and
;; costed side by side on one run over the census page's own tag strings,
;; `contains?` on this 14-entry PersistentHashSet was 102.3 ns/op — a string
;; hash plus a hash-map probe — against 17.5 for a property load on a
;; prototype-less object. Fourteen `===` compares are not the readable answer
;; the three-name reserved roster's are, so here the index earns its five
;; lines; a `case` was costed too (25.4) and declined because it would be a
;; SECOND copy of the roster.
(def ^:private void-tag-index
  (reduce (fn [o t] (unchecked-set o t true) o)
          (js/Object.create nil)
          void-tags))

(defn- ^boolean void-tag? [tag-str]
  (true? (unchecked-get void-tag-index tag-str)))

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
      (do
        ;; HTML5 void tags (br/hr/img/input/…) take no children — React
        ;; RAISES "<tag> is a void element tag and must neither have
        ;; children…" if any are passed. The slim renderer stays LENIENT
        ;; (it drops the children rather than crashing the render), but
        ;; that leniency is now INTENTIONAL and NON-SILENT: a DEBUG-gated
        ;; warning surfaces the app bug instead of masking it
        ;; (rf2-mdgt8t (c)). Elided under :advanced + goog.DEBUG=false.
        (when ^boolean js/goog.DEBUG
          (when (and (pos? n-children) (exists? js/console))
            (.warn js/console
                   (str "[reagent-slim] void element <" component
                        "> was given " n-children
                        " child element(s); HTML5 void tags cannot have"
                        " children — the children are dropped. Remove them"
                        " from the hiccup (rf2-mdgt8t)."))))
        (react/createElement component js-props))

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
    ;; Drive the loop off seq-exhaustion (`seq`), NOT the truthiness of
    ;; the bound element. A `when-let`/`first`-truthiness loop cannot
    ;; distinguish "seq exhausted" from a `nil`/`false` element in the
    ;; middle of the seq, so it terminates early and silently drops that
    ;; element AND every subsequent child. The idiomatic conditional-list
    ;; shape — `(for [x xs] (when (pred? x) [:li ...]))` — yields exactly
    ;; such interior nils and would truncate at the first filtered row.
    ;; Stock Reagent maps `as-element` over the WHOLE seq (nil → React
    ;; null, renders nothing); we match that. (rf2-8u8tx.1)
    (loop [items (seq s)]
      (when items
        (let [el (first items)]
          (when ^boolean js/goog.DEBUG
            (when (and (vector? el)
                       (not (get-react-key el))
                       (exists? js/console))
              ;; EP-0015 (rf2-uwqale): summarise the offending child, never
              ;; `pr-str` it whole — a hiccup child can carry app-owned
              ;; sensitive/large values and the warning lands verbatim in
              ;; the browser console (an off-box observation surface).
              (.warn js/console
                     (str "[reagent-slim] each child in a list should have a unique"
                          " :key prop; saw " (pr-str (diag/value-summary el))))))
          (.push arr (as-element el))
          (recur (next items)))))
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
  prop-conversion so the attr name flows through cached-prop-name.

  native-element is the emit path for BOTH real DOM tags AND `:>` interop
  elements (interop-element builds a synthetic HiccupTag whose tag slot
  holds the foreign COMPONENT, then calls native-element). Stamping is
  gated on the tag being a string DOM tag — `(string? component)`, the
  same discriminator convert-props uses for native? and the React-hook
  spine uses via dom-element? — so a `:>`-rooted view does NOT stamp the
  attr as a foreign prop on the component (React would drop it, and the
  real DOM root would go unannotated). When the root is `:>`/interop the
  *source-coord* binding is left UNCONSUMED so it flows to the first real
  DOM element downstream (§5.4's 'first hiccup vector with a DOM-tag
  head')."
  [^HiccupTag parsed argv first-pos]
  (let [component (.-tag parsed)
        [head has-props first-child] (hiccup-shape argv first-pos)
        props     (cond-> (when has-props head)
                    (and *source-coord* (string? component)) merge-source-coord-attr)
        js-props  (or (convert-props props parsed) #js {})]
    ;; `props` IS the props slot `get-react-key` would go and re-derive, on
    ;; both routes here: a DOM tag enters at `first-pos` 1 and `:>` at 2,
    ;; which are exactly the two indices that ladder reads. (The source-coord
    ;; merge only ever adds `:data-rf2-source-coord`, never `:key`.)
    (when-some [key (react-key argv props)]
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
        supplied  (nth argv 2 nil)]
    (if-some [key (-> (meta argv) :key)]
      ;; Copy the caller's js-props before stamping :key (rf2-mdgt8t (d)).
      ;; js-props here is the caller-supplied object (nth argv 2); mutating
      ;; it with (set! (.-key …)) is a user-visible mutation of an input.
      ;; native-element / react-component-element mint fresh props objects,
      ;; so only :r> was affected. Shallow-copy so the stamp is ours alone.
      (let [js-props (js/Object.assign #js {} (or supplied #js {}))]
        (set! (.-key js-props) key)
        (make-element argv component js-props 3))
      (make-element argv component (or supplied #js {}) 3))))

(defn- as-fn-component
  "Wrap a plain CLJS render fn `f` as a REAL React FUNCTION component so
  React hooks called inside `f` (`useState`, `useEffect`, `useRef`, …)
  run in a valid hooks context — the DEFINING purpose of the `:f>` head,
  and the whole reason it exists distinct from the class-wrapping user-fn
  path.

  The reagent argv travels through React props as `__rfArgv`; the wrapper
  calls `f` with the user args (the argv tail) and converts the returned
  hiccup (or an already-built React element, which passes through) via
  `as-element`. The wrapper is a plain JS function, so React treats it as
  a function component and the hooks dispatcher is installed for `f`'s
  body.

  Cached on `f` as `.-cljsFnComponent` so repeated renders reuse ONE
  component type — a fresh wrapper per render would give React a new type
  identity every time and force a remount (losing hook + DOM state).

  Reactivity boundary (deliberate, per IMPL-SPEC §4.7 + §7.1): unlike the
  class path (Form-1/2/3, which runs render inside a Reaction that captures
  bare RAtom derefs), a function component does NOT get bare-deref capture.
  Function components source reactive state through `useSyncExternalStore`-
  shaped subscription hooks — `:f>` is the escape hatch TO React hooks, so
  use a subscription hook for reactivity inside it. This mirrors how the
  UIx substrates consume subscriptions."
  [f]
  (or (.-cljsFnComponent ^js f)
      (let [wrapper (fn [^js props]
                      (as-element (apply f (rest (.-__rfArgv props)))))]
        (set! (.-displayName wrapper)
              (or (some-> ^js f .-displayName)
                  (some-> ^js f .-name)
                  "reagent-fn-component"))
        (set! (.-cljsFnComponent ^js f) wrapper)
        wrapper)))

(defn- function-element
  "Emit `:f>` — function-component dispatch.
  `[:f> some-fn args...]` renders `some-fn` as a REAL React FUNCTION
  component (via `as-fn-component`), NOT a class — so React hooks work
  inside it (rf2-bf4uw2). The prior `fn-to-class` lowering produced a
  React class, and calling a hook during class render throws
  'Invalid hook call', silently defeating the head's defining purpose.

  The user args (`[:f> f a b]` → `[a b]`) travel through React props as
  `__rfArgv` (head-included, matching the class path's convention). The
  original vector's `:key` meta / props-map `:key` is honoured via
  `get-react-key` so React keys flow."
  [argv]
  (let [f          (nth argv 1 nil)
        component  (as-fn-component f)
        ;; [head & user-args] as [f & user-args] — as-fn-component's
        ;; wrapper drops the head with (rest __rfArgv) before applying f.
        synth-argv (with-meta (into [f] (subvec argv 2)) (meta argv))
        js-props   #js {:__rfArgv synth-argv}]
    (when-some [key (get-react-key argv)]
      (set! (.-key js-props) key))
    (react/createElement component js-props)))

(defn- fragment-element
  "Emit `:<>` — React.Fragment.
  `[:<> & children]` or `[:<> {:key k} & children]`. Props map (if
  present) is JS-converted; only `:key` is meaningful on Fragments."
  [argv]
  (let [[head has-props first-child] (hiccup-shape argv 1)
        js-props  (or (when (and has-props (some? head))
                        (convert-prop-value head))
                      #js {})]
    ;; The slot is in hand, so the rule is read directly — see `react-key`.
    (when-some [key (react-key argv (when has-props head))]
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
    ;; Canonical thrown-error shape per Spec 009 §The thrown-error shape
    ;; (rf2-vvixub). The central `re-frame.error` builder is NOT used here:
    ;; reagent-slim is bundle-isolated and MUST NOT `:require` re-frame.*
    ;; (the slim bundle-isolation gate). The shape is replicated inline —
    ;; human message + trailing [:rf.error/<id>] token, `:rf.error/id` the
    ;; sole machine discriminator.
    (throw (ex-info (str "Hiccup vector cannot be empty; give the vector a "
                         "head tag (e.g. [:div …]). [:rf.error/template-empty-vector]")
                    {:rf.error/id :rf.error/template-empty-vector
                     :where       'reagent2.template/as-element
                     :reason      (str "Hiccup vector cannot be empty; give the "
                                       "vector a head tag (e.g. [:div …]).")
                     :recovery    :supply-a-head-tag})))
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
      (native-element (cached-parse tag argv) argv 1)

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
      ;; Canonical shape replicated inline (bundle isolation — see the
      ;; empty-vector throw above for the rationale).
      ;; EP-0015 (rf2-uwqale): the head + argv summarise into shape-only
      ;; diagnostics — never the raw head/children. A bad-tag throw is
      ;; captured by error boundaries / host logs before the projector can
      ;; classify it, and the argv carries app-owned hiccup children that
      ;; can hold sensitive/large values.
      (throw (ex-info (str "Hiccup head " (pr-str (diag/value-summary tag))
                           " is not a valid element head; use a keyword "
                           "(DOM tag or :>/:<>/:r>/:f>), a Reagent component "
                           "class, a React component class, or a fn. "
                           "[:rf.error/template-bad-tag]")
                      {:rf.error/id   :rf.error/template-bad-tag
                       :where         'reagent2.template/as-element
                       :reason        (str "Hiccup head must be a keyword (DOM tag "
                                           "or :>/:<>/:r>/:f>), a Reagent component "
                                           "class, a React component class, or a fn.")
                       :recovery      :supply-a-valid-hiccup-head
                       :tag/summary   (diag/value-summary tag)
                       :argv/summary  (diag/value-summary argv)})))))

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
    (named? x)     (name x)
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
