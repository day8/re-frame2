(ns re-frame.migration.hicasso.dest
  "**What Hicasso does** — the DESTINATION column of the design's §3 table,
  and the whole of the design's §6 static-analysis vocabulary.

  ## The vocabulary, and the one term that may authorise a rewrite

  `rf2-2rtt6.103` rejected migration's E2 as fatal: Fluent's `onRender*`
  family and Ant's `onRow` are event-*spelled* render props, so a design
  that reads an `on*` name as \"this is an event position\" and wraps the
  value in a nil-returning event wrapper blanks them silently. The ruling
  kept the position table alive as **the codemod's static-analysis
  vocabulary** and nothing more.

  That licence is enforced structurally here rather than promised. Read
  the last column downward:

  | term | what it is asked | can a \"yes\" ADD a rewrite? |
  |---|---|---|
  | [[canonical-slot]] | which React slot does this key emit into? | No — it selects which §3 row applies, and both runtimes compute it identically from the key alone |
  | [[html-attr-slot?]] | is this slot bound for an HTML attribute? | No — a yes means both runtimes `name` here, so W3 SKIPS |
  | [[event-prop?]] | will `host-entry` refuse a carrier at this slot? | No — a yes is always a REFUSAL, never a wrap |
  | [[callback-contracts]] | what may a declaration name at a slot? | No — it is not asked of the source at all; the report PRINTS it |
  | literalness (`rewrite`'s own syntactic tests) | can this form's value be read off the text? | Yes — and it is the only term that can |

  Exactly one term can authorise a rewrite and it is the one that asks
  about syntax rather than about names. Every name-shaped term can only
  subtract. That is the corollary of the design's Law 1, and it is why
  this tool cannot reproduce E2's failure even by accident: `onRenderCell`
  is [[event-prop?]]-true, and an [[event-prop?]]-true answer here does
  nothing but make the tool MORE conservative at that prop.

  ## What is mirrored, and what is shared

  [[canonical-slot]] is the shared `.cljc` rule itself (rf2-ani6y) — not a
  mirror, the actual function the codec calls. [[html-attr-slots]],
  [[re-event-prop]] and [[callback-contracts]] ARE mirrors, of
  `codec/html-attr-slots`, `intent/event-prop?` and
  `codec/callback-contracts`, because all three live in `.cljs` this JVM
  tool cannot load. Each is one small pure form carrying the symbol it
  mirrors, which is the design's §9.5 partial mitigation — a convention,
  and the page is candid that a convention is not a pin.

  [[callback-contracts]] is the one mirror the tool does not merely
  consult but REPRINTS, so `shared-rule-test` reads the door's own file
  and asserts the two rosters equal rather than leaving this one to the
  convention (rf2-vi11)."
  (:require [clojure.string :as str]
            [re-frame.bench.hicasso.front.slot :as slot]))

(def canonical-slot
  "**The one slot resolver**, and it is the codec's own. The React prop
  slot a hiccup attribute key emits into.

  `front.codec/canonical-slot` is `cached-prop-name`, which is a cache
  over `slot/prop-name`; a cache changes when a lookup is computed and
  nothing about what it answers, so this alias and the codec's are the
  same function of the same key."
  slot/prop-name)

(def key-slot
  "`raw-element` lifts `(:key props)` — the LITERAL keyword, read off the
  author's own map before conversion — onto the crossing's outer element.
  Held as the canonical slot for the report's benefit; W1 mints and reads
  the literal `:key`, because that is what the codec reads."
  "key")

(def ref-slot "ref")
(def class-slot "className")

(def ^:private html-attr-slots
  "MIRRORS `front.codec/html-attr-slots`. The emitted slots at a foreign
  crossing whose value is bound for an HTML attribute and therefore has no
  representation but a string — so the codec keeps `(name v)` there, which
  is also the donor's answer, which is why W3 skips them.

  Named as SLOTS, not as keys: `:class`, `:className` and `\"class\"` are
  one position."
  #{"className" "id" "role"})

(defn html-attr-slot?
  "Is `slot` one of the HTML-attribute positions? MIRRORS
  `front.codec/html-attr-slot?`, prefix families included —
  `slot/prop-name` leaves `aria-*` and `data-*` uncamelCased, so the slot
  still carries the prefix to test."
  [slot]
  (boolean
   (and (string? slot)
        (or (contains? html-attr-slots slot)
            (str/starts-with? slot "data-")
            (str/starts-with? slot "aria-")))))

(def ^:private re-event-prop
  "MIRRORS `front.intent/re-event-prop`. `:on-click` (the taught kebab
  spelling) or `:onClick` (the camel one a migrating author writes). Two
  shapes, one rule, no roster of DOM event names to maintain."
  #"^on-[a-z]|^on[A-Z]")

(defn event-prop?
  "Is `k` an event position? MIRRORS `front.intent/event-prop?`, including
  its type gate: the function answers false for a symbol, so a prop key
  spelled `on-click` as a SYMBOL is not an event position and this tool
  must not refuse there.

  Asked of what `host-entry` will do with our own landed code, not of what
  a prop means in a library. Where the model says \"this will throw\", the
  answer is a report line naming `defhost` and `:callbacks` — never a
  wrap, never a rewrite."
  [k]
  (boolean (and (or (keyword? k) (string? k))
                (re-find re-event-prop (name k)))))

(def callback-contracts
  "MIRRORS `front.codec/callback-contracts` — the three contracts a
  `defhost` declaration may name at a slot, and the whole of what the door
  will accept there.

  A VECTOR where the door holds a set, because this is the one piece of
  destination vocabulary the report PRINTS rather than merely consults,
  and printed prose needs an order. The order is the door's own literal
  and the guide's: `:event`, `:handler`, `:render`.

  The tool never chooses one. It cannot: `onRenderCell` is
  [[event-prop?]]-true and is a RENDER prop, so a contract read off a name
  is a guess that fails silently. The roster is carried here so the report
  can tell a migrator what the three answers ARE, which is the part of the
  decision the tool genuinely knows."
  [:event :handler :render])

(defn nested-key-name
  "What `clj->js` emits for a key inside a NESTED map — which is the
  destination's whole answer there, because `host-prop-value` sends a
  collection value straight to `clj->js` and its keys keep the spelling
  the author wrote.

  This is the function W2 has to make agree with [[donor/key-name]]."
  [k]
  (cond
    (keyword? k) (name k)
    (symbol? k)  (str k)
    (string? k)  k
    :else        (pr-str k)))

(defn dangerous-html-key?
  "Does this key name the `dangerouslySetInnerHTML` slot in any spelling?
  Compared with the dashes stripped and case folded, because the kebab
  spelling never reached React's exact name under EITHER runtime — the
  camel rule answers `dangerouslySetInnerHtml`, with a lowercase `tml` —
  and the migrator needs telling about the site regardless of which
  spelling they used.

  The donor DELETED this prop unless it was `UnsafeHTML`-wrapped and
  Hicasso passes it through, so the migration resurrects it. A decision
  for a person; never a rewrite (`:dangerous-html`, §5.3)."
  [k]
  (and (or (keyword? k) (string? k) (symbol? k))
       (= "dangerouslysetinnerhtml"
          (-> (name k) (str/replace "-" "") (str/lower-case)))))
