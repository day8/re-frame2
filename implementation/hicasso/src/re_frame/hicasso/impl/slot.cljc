(ns re-frame.hicasso.impl.slot
  "THE CANONICAL SLOT RULE — one implementation, two hosts.

  A hiccup prop key is written in one of four spellings — a keyword, a
  string, a symbol or a namespaced keyword — in kebab or in camel, and
  every one of them is emitted under ONE React name. [[prop-name]]
  decides which: the three React renames (`class`, `for`, `charset`) in
  every spelling; kebab → camel with an existing hump preserved;
  `aria-*`, `data-*` and `--custom-property` passed through; a string
  taken verbatim apart from the renames. It is the whole of this
  namespace, it holds no state, and it requires nothing but
  `clojure.string`.

  `.cljc` rather than a `defn` in the codec because the rule has a second
  consumer that cannot run in CLJS at all — the Reagent-to-Hicasso codemod,
  which decides on the JVM which slot each prop it rewrites will land in —
  and one shared definition is the only thing that pins the tool and the
  runtime equal: `test/re_frame/hicasso/slot_cljs_test.cljc` asserts one
  corpus twice, in Node and on the JVM, and the codemod's `shared_rule_test`
  holds its resolver `identical?` to this one. The caches and the
  prototype-poisoning guard are emission concerns and stay in the codec.

  Design record: docs/design/hicasso/studio/reagent-codemod-against-the-landed-escape.md"
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
