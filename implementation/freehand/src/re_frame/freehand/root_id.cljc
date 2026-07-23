(ns re-frame.freehand.root-id
  "The root-id grammar — one implementation, read by both tiers.

  A root's identity is a `:root-id`, and the same three questions are
  asked about it in two very different places: the compiler asks them at
  a mount site's expansion, and the live-root registry asks them again in
  the browser, of a root nobody compiled. The questions are

    - is this authored id a LEGAL id?
    - what id does a mount DERIVE when none is authored?
    - what `identifierPrefix` does that id imply?

  and the answers must be the same answer, not two answers that agree
  today. So the grammar lives here, host-neutral and dependency-free, and
  [[re-frame.freehand.compiler.root]] and
  [[re-frame.freehand.root]] both read it.

  ## The slug is injective, and that is load-bearing

  [[root-id-slug]] is the deterministic function the default
  `identifierPrefix` is built from. It must be **injective**: distinct
  valid root-ids ALWAYS yield distinct slugs, or two roots on one page
  would derive one prefix and collide React's `use-id` output — the
  collision the client-tier prefix check exists to catch, silently
  manufactured by the framework itself.

  It is therefore a DECODABLE canonical form over the DOM-safe alphabet
  `[A-Za-z0-9_-]`. `_` is the sole metacharacter: every character outside
  `[A-Za-z0-9-]` (including `_`) is reversibly escaped `_<hex>_`, and
  each structural boundary carries an uppercase `_`-tag the escape never
  emits (`_S` keyword namespace/name separator; `_V` vector lead-in;
  `_K`/`_T`/`_I` a vector element's keyword/string/integer type). Because
  every boundary is MARKED rather than inferred from a data character the
  mapping is losslessly decodable, and a decodable map is injective. A
  lossy transform (normalise every disallowed character to `-`) is not:
  `:a/b-c` and `:a-b/c` would both flatten to `a-b-c`.

  Normative owner:
  [`spec/004C-Roots-and-Mount.md`](../../../../../spec/004C-Roots-and-Mount.md)
  §1.")

;; ---------------------------------------------------------------------------
;; The shapes
;; ---------------------------------------------------------------------------

(defn qualified-keyword-id?
  "True for a namespaced keyword — the canonical root-id spelling
  (`:page/shop`)."
  [x]
  (and (keyword? x) (some? (namespace x))))

(defn scalar-disambiguator?
  "The disambiguator grammar (Spec 004C §1.2): a keyword, a string, or an
  integer. Scalars only — a disambiguator is identity, and identity that
  can be structurally equal without being printably distinct is identity
  a diagnostic cannot name."
  [x]
  (or (keyword? x) (string? x) (integer? x)))

(defn authored-root-id?
  "True for a legal AUTHORED `:root-id` (Spec 004C §1.1): a qualified
  keyword, or a vector of a qualified keyword plus one or more scalar
  disambiguators."
  [x]
  (or (qualified-keyword-id? x)
      (and (vector? x)
           (<= 2 (count x))
           (qualified-keyword-id? (first x))
           (every? scalar-disambiguator? (rest x)))))

(defn derive-root-id
  "The DERIVED root-id for a mount whose opts author none (Spec 004C
  §1.2): the mounted view's registered id, or `[view-id disambiguator]`
  when the site supplies one.

  The disambiguator is what lets ONE view mount twice on one page without
  either site authoring an id — the two roots differ by exactly the fact
  the author actually stated."
  [view-id disambiguator]
  (if (some? disambiguator)
    [view-id disambiguator]
    view-id))

;; ---------------------------------------------------------------------------
;; The slug
;; ---------------------------------------------------------------------------

(defn- code-unit
  "The UTF-16 code unit at index `i` — host-neutral (a CLJS string is a JS
  string; a JVM `char` is a 16-bit code unit), so a slug computed at
  compile time on the JVM is character-for-character the slug the browser
  computes for the same id."
  [s i]
  #?(:clj  (int (.charAt ^String s i))
     :cljs (.charCodeAt s i)))

(defn- hex [cc]
  #?(:clj  (Integer/toHexString (int cc))
     :cljs (.toString cc 16)))

(defn- safe-code? [cc]
  (or (<= 48 cc 57)     ; 0-9
      (<= 65 cc 90)     ; A-Z
      (<= 97 cc 122)    ; a-z
      (= cc 45)))       ; -

(defn- enc
  "Reversibly escape a raw string into the DOM-safe alphabet: a character
  in `[A-Za-z0-9-]` passes through; anything else — INCLUDING `_` — becomes
  `_<lowercase-hex-code-unit>_`. The output therefore carries a bare `_`
  only inside an escape, so the uppercase structural markers below can
  never be confused with escaped data."
  [s]
  (let [n (count s)]
    (loop [i 0, out ""]
      (if (< i n)
        (let [cc (code-unit s i)]
          (recur (inc i)
                 (str out (if (safe-code? cc)
                            (subs s i (inc i))
                            (str "_" (hex cc) "_")))))
        out))))

(defn- enc-kw
  "A keyword → `enc(namespace) _S enc(name)` when qualified, else
  `enc(name)`. The `_S` marker fixes the namespace/name boundary, so
  `:a/b-c` and `:a-b/c` cannot alias and a qualified keyword can never
  collide with an unqualified one."
  [k]
  (if-let [ns* (namespace k)]
    (str (enc ns*) "_S" (enc (name k)))
    (enc (name k))))

(defn- enc-elem
  "A vector element, type-tagged so a keyword, a string and an integer
  built from the same characters cannot alias — and so the tag also
  delimits the element: `_K` keyword, `_T` string, `_I` integer."
  [x]
  (cond
    (keyword? x) (str "_K" (enc-kw x))
    (integer? x) (str "_I" (enc (str x)))
    :else        (str "_T" (enc x))))

(defn root-id-slug
  "The ONE deterministic, INJECTIVE slug over the closed root-id grammar
  (Spec 004C §1).

      :page/shop        → \"page_Sshop\"
      [:shop/app :left] → \"_V_Kshop_Sapp_Kleft\"

  A keyword root encodes to `enc-kw`; a vector root to `_V` followed by
  its type-tagged, escaped elements. Keyword and vector slugs can never
  collide: a vector slug starts with the literal `_V`, and a keyword slug
  starts with either a safe character or `_<lowercase-hex>`."
  [root-id]
  (if (vector? root-id)
    (str "_V" (apply str (map enc-elem root-id)))
    (enc-kw root-id)))

(defn default-identifier-prefix
  "`\"rf2-\" + root-id-slug + \"-\"` (Spec 004C §3) — the `identifierPrefix`
  a root feeds React when the site authors none. Injective over root-id
  because the slug is, so the DERIVED prefix never collides on its own;
  the client-tier uniqueness check backstops AUTHORED prefixes, which
  still can."
  [root-id]
  (str "rf2-" (root-id-slug root-id) "-"))
