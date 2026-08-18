(ns re-frame.hicasso.impl.slot
  "THE CANONICAL SLOT RULE — one implementation, two hosts (rf2-ani6y).

  A hiccup prop key is written in one of four spellings — a keyword, a
  string, a symbol or a namespaced keyword — in kebab or in camel, and
  every one of them is emitted under ONE React name. [[prop-name]] is
  the function that decides which. It is the whole of the rule and it is
  the whole of this namespace.

  ## Why it is `.cljc` rather than a `defn` inside the codec

  [[re-frame.hicasso.impl.codec]] is `.cljs`, because emission is
  `React.createElement`. The slot rule is not: it is a pure function from
  a name to a name, and it has a **second consumer that cannot run in
  CLJS at all** — the `[:>]` migration codemod, which reads a Reagent
  namespace on the JVM and must decide, statically, which slot each prop
  it rewrites will land in.

  A tool that reimplements the rule is the exact defect the codemod
  exists to delete, reproduced inside the tool: a corpus pins the tool, a
  DOM suite pins the runtime, and **nothing pins them equal**. When they
  drift the failure is silent — the tool rewrites a prop one way, the
  runtime reads it another, and the author's source now says something
  the framework does not do. No comment, checklist or restating test
  inside the tool's own tree closes that; a convention is not a pin.

  So the rule moved to the one file both hosts can load, and the codec
  calls it like anybody else. There is no second copy to keep honest.

  ## What pins the two hosts equal

  `re-frame.bench.hicasso.front.slot-cljs-test` is a **`.cljc` suite**,
  and this repo's lane bijection (`scripts/check_test_lane_bijection.py`
  rule B2) requires such a file to be selected by a CLJS lane as well as
  the JVM one. So the same corpus of authored keys, with the same
  expected slots, is asserted by `npm run test:cljs` AND by
  `clojure -M:test` in `implementation/freehand`. One table, one
  implementation, two runtimes — and a divergence has nowhere left to
  hide, because there is no second implementation to hold a second
  answer.

  That matters more than it looks, because the primitives below are not
  host-identical for free. `str/upper-case` is `.toUpperCase()` on both,
  but the JVM's takes the platform's DEFAULT LOCALE — on a Turkish-locale
  JVM `\"i\"` upper-cases to `\"İ\"` and `:aria-index` would slot
  differently from the browser's answer. `str/split`'s trailing-empty
  handling is a second such seam. Neither is guarded here by a
  reader conditional, and deliberately: a guard would be a guess about
  which host is right. The cross-host suite makes the disagreement LOUD
  instead, on the host that has it.

  ## What is NOT here

  The caches, the [[re-frame.hicasso.impl.codec/PropSlot]]
  classifications, the prototype-poisoning guard and every walk are
  emission concerns and stay in the codec. This namespace holds the pure
  rule and its two vocabularies, and requires nothing but
  `clojure.string`."
  (:require [clojure.string :as str]))

(def ^:private dont-camel-case
  "`aria-*` and `data-*` are HTML attribute names in React too, and
  camelCasing them would break them."
  #{"aria" "data"})

(defn- capitalize
  "Upper-case the first character and leave the rest ALONE.

  Deliberately not `clojure.string/capitalize`, which lower-cases the
  tail: `:on-Click` would become `onClick` under either, but an author
  who wrote `:view-Box` means `viewBox` and `str/capitalize` would hand
  React `Viewbox`."
  [s]
  (if (< (count s) 2)
    (str/upper-case s)
    (str (str/upper-case (subs s 0 1)) (subs s 1))))

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

  **A pure function of the key**, which is what
  [[re-frame.hicasso.impl.codec/canonical-slot]] rests on. A slot
  that depended on what the build happened to have converted earlier
  would make the owned-literal law depend on render order.

  It is also the function the `[:>]` migration codemod asks on the JVM,
  which is why it lives here rather than in the codec — see this
  namespace's docstring."
  [k]
  (let [n (name k)]
    (or (react-renames n)
        (if (or (string? k) (str/starts-with? n "--"))
          n
          (let [[start & parts] (str/split n #"-")]
            (if (dont-camel-case start)
              n
              (apply str start (map capitalize parts))))))))
