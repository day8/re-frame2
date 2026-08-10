(ns re-frame.migration.hicasso.rewrite
  "The six rewrites (§4) and the refusal set (§5), as pure functions over
  rewrite-clj NODES.

  ## Two laws, and one corollary that does most of the work

  **Law 1 — decidability.** A rewrite fires only where the donor's answer
  and the destination's answer are both computable from the source text,
  and they differ.

  **Law 2 — no blanking.** No rewrite introduces a function whose return
  value differs from the value the donor's conversion produced.

  **Corollary.** A prop's spelling may only ever make the tool do LESS.
  Where a name-shaped test says \"yes\", the answer is always *skip this
  rewrite* or *refuse this site* — never *apply this rewrite*. See
  [[re-frame.migration.hicasso.dest]], where the vocabulary is enumerated
  with that enforcement in its last column.

  **Silent behaviour change is the only fatal class.** `[:>]` is legal in
  Hicasso, so \"leave it as `[:>]`\" is a valid output for any site the
  rewrite cannot make behaviour-preserving. A refused site still works;
  the report is what turns a refusal from silence into an instruction.

  ## How a site is planned, and why it is planned exactly once

  [[plan-site]] is a pure function from a site node (plus a context) to
  `{:entries [...] :edit (fn [node] node')}`. The report pass and the
  rewrite pass each call it, on the same node with the same context, and
  therefore reach the same decision — there is no separate \"apply\"
  implementation to drift from the analysis. `:edit` is written to operate
  STRUCTURALLY on whatever node it is handed, so the rewrite pass may hand
  it a node whose nested sites have already been rewritten.

  ## The three amendments (rf2-2rtt6.143), which CORRECT the landed design

  **(A)** W4 captures at prop-evaluation time — [[w4-plan]].
  **(B)** W2 refuses normalized-key collisions — [[injective-keys]].
  **(C)** The key slot gets a dedicated report entry — [[w3-entry]]."
  (:require [clojure.string :as str]
            [re-frame.migration.hicasso.dest :as dest]
            [re-frame.migration.hicasso.donor :as donor]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

;; ---------------------------------------------------------------------------
;; Node helpers
;; ---------------------------------------------------------------------------

(defn sexpr-safe
  "The node's value, or `::unreadable` for a node no reader can answer —
  a reader conditional, an unresolved auto-resolved keyword, a tagged
  literal. `::unreadable` is never equal to anything the tool tests for,
  so an unreadable node simply fails every literalness test, which is the
  conservative direction."
  [node]
  (try (n/sexpr node) (catch Exception _ ::unreadable)))

(defn elements
  "The node's semantic children — whitespace, newlines and comments
  removed. Indices into this vector are what [[replace-element]] and
  [[insert-element]] address, so formatting survives every edit."
  [node]
  (if (n/inner? node)
    (vec (remove n/whitespace-or-comment? (n/children node)))
    []))

(defn element-at [node i] (get (elements node) i))

(defn- replace-element
  "Replace the `i`-th semantic child of `node`, leaving every whitespace
  and comment node exactly where the author put it."
  [node i new-child]
  (loop [out [], ks (n/children node), j 0]
    (if (empty? ks)
      (n/replace-children node out)
      (let [k (first ks)]
        (if (n/whitespace-or-comment? k)
          (recur (conj out k) (rest ks) j)
          (recur (conj out (if (= j i) new-child k)) (rest ks) (inc j)))))))

(defn- insert-element
  "Insert `new-child` so that it becomes the `i`-th semantic child,
  separated by one space. Appended at the end when `i` is past the last
  semantic child — which is the `[:> C]` case W1 mints into."
  [node i new-child]
  (let [total (count (elements node))]
    (if (>= i total)
      (n/replace-children node (concat (n/children node)
                                       (when (pos? total) [(n/spaces 1)])
                                       [new-child]))
      (loop [out [], ks (n/children node), j 0]
        (if (empty? ks)
          (n/replace-children node out)
          (let [k (first ks)]
            (if (n/whitespace-or-comment? k)
              (recur (conj out k) (rest ks) j)
              (if (= j i)
                (n/replace-children node (concat out [new-child (n/spaces 1)] ks))
                (recur (conj out k) (rest ks) (inc j))))))))))

(defn- remove-element
  "Remove the `i`-th semantic child of `node` along with ONE adjacent
  whitespace node, so a removal leaves neither a double space nor a
  leading one. The preceding space goes when there is one; for the first
  child — which has nothing before it but the opening delimiter — the
  FOLLOWING space goes instead."
  [node i]
  (let [kept (loop [out [], ks (n/children node), j 0]
               (if (empty? ks)
                 out
                 (let [k (first ks)]
                   (if (n/whitespace-or-comment? k)
                     (recur (conj out k) (rest ks) j)
                     (if (= j i)
                       (if (and (seq out) (n/whitespace-or-comment? (peek out)))
                         (recur (pop out) (rest ks) (inc j))
                         ;; nothing to drop behind us: drop the space ahead
                         (let [ks' (rest ks)]
                           (recur out
                                  (if (and (seq ks') (n/whitespace-or-comment? (first ks')))
                                    (rest ks')
                                    ks')
                                  (inc j))))
                       (recur (conj out k) (rest ks) (inc j)))))))]
    (n/replace-children node kept)))

(defn- tag= [node t] (and node (= t (n/tag node))))

(defn- map-node? [node] (tag= node :map))
(defn- vector-node? [node] (tag= node :vector))
(defn list-node? [node] (tag= node :list))

(defn pairs
  "A literal map node as `[[key-node value-node index] ...]`, where index
  is the KEY's position among the node's semantic children. A map with an
  odd number of semantic children is malformed source; it yields the
  pairs it can and drops the dangling key."
  [node]
  (let [els (elements node)]
    (vec (for [i (range 0 (dec (count els)) 2)]
           [(nth els i) (nth els (inc i)) i]))))

(defn- self-evaluating?
  "Is this form's value fixed by its own text, with no evaluation to
  observe? Keywords, strings, numbers, booleans, `nil`, characters and
  regexes. Deliberately NOT symbols: re-reading a var on each callback
  invocation is observable under redefinition, which is the whole subject
  of amendment (A)."
  [node]
  (and (tag= node :token)
       (let [v (sexpr-safe node)]
         (or (keyword? v) (string? v) (number? v) (boolean? v)
             (nil? v) (char? v) (instance? java.util.regex.Pattern v)))))

(defn- literal-named-value
  "The keyword or symbol this prop value **is**, or `nil`.

  A bare symbol token is a VARIABLE REFERENCE, not a symbol value:
  `handler` names whatever `handler` is bound to, and at a prop that is
  overwhelmingly a function. Only a keyword literal and a QUOTED symbol
  are named values the donor's `(name x)` arm ever saw.

  This distinction is the difference between W3 and a catastrophe.
  Reading a bare symbol as a symbol value rewrites `{:on-click handler}`
  to `{:on-click \"handler\"}` — a live handler replaced by a string,
  silently, which is precisely the fatal class this whole tool exists to
  delete. Everything reached through a symbol is invisible to a source
  reader and falls to `:computed-value` (§4.4's \"scope, stated
  honestly\")."
  [v]
  (cond
    (and (tag= v :token) (keyword? (sexpr-safe v)))
    (sexpr-safe v)

    (tag= v :quote)
    (let [inner (element-at v 0)]
      (when (and inner (tag= inner :token) (symbol? (sexpr-safe inner)))
        (sexpr-safe inner)))

    :else nil))

(defn- fn-literal?
  "Is this form SYNTACTICALLY a function — `(fn …)`, `(fn* …)`, `#(…)`,
  or a `let` whose body ends in one (which is W4's own output shape)?

  Used only to keep a value OUT of the `:computed-value` report: the three
  things a computed value may silently be are a keyword, a nested map and
  a non-fn `IFn`, and a function literal is provably none of them. A tool
  that flags the code it just wrote as undecidable is crying wolf."
  [node]
  (boolean
   (or (tag= node :fn)
       (and (list-node? node)
            (let [h (element-at node 0)
                  s (and h (sexpr-safe h))]
              (or (contains? '#{fn fn* clojure.core/fn} s)
                  (and (= 'let s)
                       (some-> (last (elements node)) fn-literal?))))))))

(defn- read-off-the-text?
  "Can this prop value's crossing be decided from the text alone? True for
  a non-symbol token, a literal collection, a quoted form, a reader
  literal such as `#js {…}`, and a function literal. False for a bare
  symbol and for any call — those are `:computed-value`, which is exactly
  what §4.4 says of \"anything arriving through a symbol\"."
  [v]
  (boolean
   (or (and (tag= v :token) (not (symbol? (sexpr-safe v))))
       (map-node? v) (vector-node? v) (tag= v :set)
       (tag= v :quote) (tag= v :reader-macro)
       (fn-literal? v))))

;; ---------------------------------------------------------------------------
;; The `^{:key k}` wrapper (W1's other half)
;; ---------------------------------------------------------------------------
;;
;; W1's edit spans two nodes: the key moves OUT of the metadata map and INTO
;; the props map. The props half is [[plan-site]]'s `:edit`; this half is the
;; caller's, because a metadata map is the site vector's PARENT and a plan
;; cannot reach upward. Both halves fire on the one condition — `plan-site`'s
;; `:w1?` — so they cannot disagree.

(defn meta-node? [nd] (tag= nd :meta))

(defn unwrap-metas
  "Walk down a chain of `:meta` nodes. Returns `[meta-nodes innermost]`.

  A chain rather than a single node because `^:foo ^{:key k} [:> …]` is
  legal and Reagent's `(meta argv)` reads the merge of all of them."
  [nd]
  (loop [ms [], cur nd]
    (if (meta-node? cur)
      (recur (conj ms cur) (element-at cur 1))
      [ms cur])))

(defn meta-key-node
  "The `:key` VALUE node inside one meta node's map, if it carries one.
  Non-map metadata — `^:foo`, `^String` — never carries a key."
  [mnode]
  (let [mv (element-at mnode 0)]
    (when (map-node? mv)
      (some (fn [[k v _]] (when (= :key (sexpr-safe k)) v)) (pairs mv)))))

(defn meta-key-nodes
  "Every `:key` value node across a meta chain. More than one is a
  `:key-conflict`; [[plan-site]] decides that, not this."
  [metas]
  (vec (keep meta-key-node metas)))

(defn- drop-key-entry
  "Remove the `:key` entry from one meta node's map. When the map is left
  empty the whole `^{}` wrapper goes with it, because an empty metadata
  map is noise the author never wrote."
  [mnode]
  (let [mv (element-at mnode 0)
        i  (some (fn [[k _ idx]] (when (= :key (sexpr-safe k)) idx)) (pairs mv))]
    (if (nil? i)
      mnode
      (let [mv' (-> mv (remove-element (inc i)) (remove-element i))]
        (if (empty? (elements mv'))
          (element-at mnode 1)
          (replace-element mnode 0 mv'))))))

(defn rebuild-meta-chain
  "Rebuild a meta chain around `inner'`, dropping the `:key` entry from
  whichever meta carries it when `strip?`."
  [nd inner' strip?]
  (if (meta-node? nd)
    (let [child (rebuild-meta-chain (element-at nd 1) inner' strip?)
          nd'   (replace-element nd 1 child)]
      (if strip? (drop-key-entry nd') nd'))
    inner'))

;; ---------------------------------------------------------------------------
;; Reagent alias resolution
;; ---------------------------------------------------------------------------

(def ^:private reagent-namespaces
  '#{reagent.core reagent.dom reagent.dom.client reagent.ratom})

(defn ns-context
  "Read a file's `ns` form and answer which symbols name Reagent's API.

  Returns `{:aliases #{r reagent} :referred #{partial …}}`. Alias
  resolution is what makes W4 and W5 legal at all: the only non-fn `IFn`
  and the only `adapt-react-class` a source reader can RECOGNISE is a
  literal call through a symbol this map resolves. A bare `partial` is
  `clojure.core/partial` — which IS a function, so the donor never wrapped
  it — unless the `ns` form referred Reagent's, so the referred set is
  read rather than assumed."
  [root-node]
  (let [ns-form (->> (elements root-node)
                     (filter #(and (list-node? %)
                                   (= 'ns (sexpr-safe (element-at % 0)))))
                     first)
        form    (some-> ns-form sexpr-safe)
        reqs    (when (seq? form)
                  (->> form
                       (filter #(and (seq? %) (contains? #{:require :require-macros} (first %))))
                       (mapcat rest)))]
    (reduce
     (fn [acc spec]
       (let [spec (if (symbol? spec) [spec] spec)]
         (if (and (sequential? spec) (contains? reagent-namespaces (first spec)))
           (let [opts (apply hash-map (rest spec))]
             (cond-> (update acc :aliases conj (first spec))
               (:as opts)    (update :aliases conj (:as opts))
               (:refer opts) (update :referred into (:refer opts))))
           acc)))
     {:aliases #{} :referred #{}}
     reqs)))

(defn reagent-call?
  "Is `node` a literal call to Reagent's `sym`, through an alias this
  file's `ns` form actually binds?"
  [node sym {:keys [aliases referred]}]
  (and (list-node? node)
       (let [h (sexpr-safe (element-at node 0))]
         (and (symbol? h)
              (= (name h) (name sym))
              (if-let [q (namespace h)]
                (contains? aliases (symbol q))
                (contains? referred sym))))))

(defn- subtree-has-reagent-call?
  [node sym ctx]
  (boolean
   (or (reagent-call? node sym ctx)
       (and (n/inner? node)
            (some #(subtree-has-reagent-call? % sym ctx) (n/children node))))))

;; ---------------------------------------------------------------------------
;; Site detection
;; ---------------------------------------------------------------------------

(defn- head-keyword [node]
  (when (vector-node? node)
    (let [h (element-at node 0)]
      (when (tag= h :token) (sexpr-safe h)))))

(defn site-kind
  "Which kind of site is this node, if any?

  `:raw`   — `[:> C …]`, the tool's whole subject.
  `:adapt` — `[(r/adapt-react-class X) …]`, W5's inline head.
  `:r>`    — `[:r> C …]`, ported by hand (§5.3).
  `:f>`    — `[:f> C …]`, ported by hand (§5.3)."
  [node ctx]
  (when (vector-node? node)
    (case (head-keyword node)
      :> :raw
      :r> :r>
      :f> :f>
      (when (reagent-call? (element-at node 0) 'adapt-react-class ctx) :adapt))))

(defn- head-text
  "The component name for the report — the one thing in the system that
  can carry it. A `[:>]` refusal at RUNTIME prints `raw-crossing`'s
  `displayName`, which is the constant `\"[:>]\"`, so the runtime cannot
  say which component refused. The report can, because it read the head."
  [node kind]
  (let [h (case kind
            :adapt (element-at (element-at node 0) 1)
            (element-at node 1))]
    (some-> h n/string str/trim)))

(defn- props-index [kind] (if (= :adapt kind) 1 2))

(defn- child-literal?
  "Is this form OBVIOUSLY a hiccup child rather than a props map? A
  string, a number, a boolean, `nil` or a nested hiccup vector. Anything
  else that is not a literal map is `:computed-props` — the tool could not
  tell props from a child, and says so."
  [node]
  (or (vector-node? node)
      (and (tag= node :token)
           (let [v (sexpr-safe node)]
             (or (string? v) (number? v) (boolean? v) (nil? v))))))

;; ---------------------------------------------------------------------------
;; The report entry
;; ---------------------------------------------------------------------------

(defn- entry [class action detail note]
  {:class class :action action :detail detail :note note})

;; ---------------------------------------------------------------------------
;; W2 — nested map keys (§4.2), amended by (B)
;; ---------------------------------------------------------------------------

(defn injective-keys
  "**AMENDMENT (B).** Are this literal map's keys injective under the
  DONOR's key function?

  Reagent's `kv-conv` writes each nested-map key under
  `cached-prop-name`, so `:foo-bar`/`:fooBar`, `:class`/`:className` and
  `:foo-bar`/`\"fooBar\"` siblings each collapse onto ONE JS property with
  an iteration-order winner. (The donor suite pins exactly this at the top
  level: `codemod-contract-donor-the-whole-corpus` asserts a sixteen-prop
  corpus emits FIFTEEN slots, and that `\"which of the two survives is
  reduce-kv order … so the pin is that exactly one does\"`.)

  Respelling both sources is therefore not available. Writing both
  normalized names mints a DUPLICATE literal map key — which is not even
  readable Clojure — and writing one picks a winner the rewrite cannot
  know. So the whole map is refused and every colliding source key is
  named.

  Returns `nil` when injective, else a sorted vector of the colliding
  source keys' texts, grouped by the normalized name they collapse onto.

  CSS custom properties are excluded from the check because [[donor/key-name]]
  answers `nil` for them: they are refused individually and never
  respelled, so they cannot be half of a minted duplicate."
  [map-node]
  (let [named (->> (pairs map-node)
                   (keep (fn [[k _ _]]
                           (let [kv (sexpr-safe k)
                                 dn (donor/key-name kv)]
                             (when dn [dn (str/trim (n/string k))])))))
        clashes (->> (group-by first named)
                     (filter (fn [[_ v]] (> (count v) 1)))
                     (sort-by first))]
    (when (seq clashes)
      (vec (for [[slot ks] clashes]
             {:slot slot :keys (vec (sort (map second ks)))})))))

(defn- respell-key
  "The key node W2 writes, or nil when the key is already a fixpoint or is
  one the tool refuses. Preserves the KIND the author wrote — a keyword
  stays a keyword, a symbol stays a symbol — because `clj->js` reads
  keywords by `name` and symbols by `str`, and only the same kind
  reproduces the donor's emitted name."
  [key-node]
  (let [kv (sexpr-safe key-node)
        dn (donor/key-name kv)]
    (when (and dn (not= dn (dest/nested-key-name kv)))
      (cond
        (keyword? kv) (n/token-node (keyword dn))
        (symbol? kv)  (n/token-node (symbol dn))
        :else         nil))))

(declare w2-map-plan)

(defn- w2-map-plan
  "Plan the rewrite of ONE literal map value, recursively.

  Returns `{:edit fn :entries [...] :changed? bool}`. Recursion walks map
  values THROUGH MAPS ONLY and stops at the first non-map collection,
  because that is exactly where the donor stopped: Reagent recurses via
  the `map?` arm, and a map inside a vector, list or set is reached by the
  `coll?` arm, which is `clj->js` and does not camelCase. It never
  descends into a `#js {…}` node, which both runtimes pass through
  untouched."
  [map-node path]
  (let [collisions (injective-keys map-node)]
    (if collisions
      ;; (B): refuse the WHOLE map, naming every colliding source key.
      {:edit identity
       :changed? false
       :entries [(entry :normalized-key-collision :refused
                        {:path path :collisions collisions}
                        (str "Reagent wrote each of these keys under `cached-prop-name`, so the "
                             "siblings named here COLLAPSED onto one JS property and an "
                             "iteration-order winner decided which value the library saw. "
                             "Respelling them would either mint a duplicate map key or silently "
                             "pick a winner this tool cannot know was the one that won. The map "
                             "is left exactly as written: decide which key you meant, delete the "
                             "other, and re-run."))]}
      (let [ps (pairs map-node)]
        (reduce
         (fn [acc [k v i]]
           (let [kv    (sexpr-safe k)
                 kpath (conj path (str/trim (n/string k)))
                 ;; the key
                 acc   (cond
                         (and (or (keyword? kv) (symbol? kv)) (donor/css-var-name? (name kv)))
                         (update acc :entries conj
                                 (entry :css-var-repair :refused
                                        {:path kpath}
                                        (str "Reagent's `dash-to-prop-name` mangled this CSS custom "
                                             "property into a style key nothing reads, so the "
                                             "declaration never took effect. Hicasso preserves it and "
                                             "React routes a `--`-prefixed key through `setProperty`. "
                                             "The key is left alone and THE SITE STARTS WORKING — "
                                             "check that the style it now applies is the one you "
                                             "want.")))

                         (not (or (keyword? kv) (symbol? kv) (string? kv)))
                         (update acc :entries conj
                                 (entry :computed-nested-key :refused
                                        {:path kpath}
                                        (str "This map key is not a literal keyword, symbol or "
                                             "string, so the tool cannot compute what Reagent spelled "
                                             "it as. It keeps the spelling you wrote, and the "
                                             "collision check above could not see it either.")))

                         :else
                         (if-let [nk (respell-key k)]
                           (-> acc
                               (update :edit (fn [f] (fn [nd] (replace-element (f nd) i nk))))
                               (assoc :changed? true)
                               (update :respelled conj [(str/trim (n/string k))
                                                        (str/trim (n/string nk))]))
                           acc))
                 ;; the value, when it is itself a literal map
                 acc   (if (map-node? v)
                         (let [sub (w2-map-plan v kpath)]
                           (cond-> (update acc :entries into (:entries sub))
                             (:changed? sub)
                             (-> (update :edit (fn [f] (fn [nd]
                                                         (let [nd (f nd)]
                                                           (replace-element nd (inc i)
                                                                            ((:edit sub) (element-at nd (inc i))))))))
                                 (assoc :changed? true))
                             (seq (:respelled sub))
                             (update :respelled into (:respelled sub))))
                         acc)]
             acc))
         {:edit identity :entries [] :changed? false :respelled []}
         ps)))))

(defn- w2-entry
  "W2's own report line — **the loudest the tool emits** (§9.1), and the
  reason it must be is that W2 moves a latent conversion into visible
  source text and hands the author a rake."
  [path respelled]
  (entry :nested-map-keys :rewrote
         {:path path :respelled (vec (sort respelled))}
         (str "Reagent camelCased these nested keys on the way across; Hicasso does not, so the "
              "spelling is now written into your source where the library will actually read it. "
              "IF A KEY HERE IS DATA RATHER THAN A LIBRARY OPTION, THIS REWRITE IS THE MOMENT TO "
              "NOTICE — tidying it back to the spelling you originally wrote will silently change "
              "what the library receives.")))

;; ---------------------------------------------------------------------------
;; W3 — named prop values (§4.3), amended by (C)
;; ---------------------------------------------------------------------------

(defn- w3-entry
  "The report line for a literal keyword or symbol prop value.

  **AMENDMENT (C)** is the `key`-slot arm. The exclusion itself is
  ratified — `:foo` and `\"foo\"` sibling keys COLLIDED under Reagent and
  are DISTINCT under Hicasso, so rewriting would restore the defect
  rf2-vrvv9 removed — but §5.4 priced it as \"one remount\", and a remount
  is not free: React reconciles a changed key by UNMOUNTING the old
  subtree and mounting a new one, which discards its state."
  [kind kw prop]
  (case kind
    :key-slot
    (entry :key-slot-named-value :skipped
           {:prop prop :value (str kw)}
           (str "NOT REWRITTEN, DELIBERATELY, AND IT COSTS YOU SOMETHING. Reagent converted this "
                "key like any prop and React received \"" (name kw) "\"; Hicasso lifts it raw and "
                "React coerces it to \"" (str kw) "\". Writing \"" (name kw) "\" here would restore a "
                "defect: two sibling elements keyed `" (str kw) "` and `\"" (name kw) "\"` collided "
                "under Reagent and are distinct under Hicasso. The cost of leaving it is that the "
                "key CHANGES at the migration, so on the first render after it React treats this "
                "element as a new one — it unmounts the existing subtree and mounts a fresh one, "
                "DISCARDING ITS STATE (uncontrolled input values, scroll position, component "
                "state). It is a one-time cost and the key is stable thereafter."))

    :namespaced
    (entry :namespaced-named-value :rewrote
           {:prop prop :was (str kw) :now (name kw)}
           (str "Preserved exactly, and the preservation bakes in a collision. Reagent answered "
                "`(name " (str kw) ")`, so `" (str kw) "` and any other namespace's `" (name kw)
                "` reached the library as ONE string. Hicasso keeps the namespace, which is what "
                "rf2-vrvv9 changed it to do. Writing the string preserves your current behaviour; "
                "if the namespace was carrying meaning, this is where it was already being lost."))

    (entry :named-value :rewrote
           {:prop prop :was (str kw) :now (name kw)}
           (str "Reagent named every keyword and symbol prop value; Hicasso keeps them whole "
                "except at HTML-attribute slots. The string is a fixpoint on both walks."))))

;; ---------------------------------------------------------------------------
;; W4 — the non-fn IFn literal (§4.4), amended by (A)
;; ---------------------------------------------------------------------------

(defn- site-symbols
  "Every symbol occurring anywhere in this node's subtree.

  W4's reserved set. It has to be the WHOLE subtree rather than the
  top-level argument forms, because a `let` binding shadows its name for
  every initializer that FOLLOWS it — including one nested three levels
  down inside a later argument. Quoted symbols are swept in with the rest:
  a quoted symbol cannot actually be captured, but excluding it would buy
  nothing except a subtler rule."
  [node]
  (cond
    (n/inner? node) (into #{} (mapcat site-symbols) (n/children node))
    (tag= node :token) (let [v (sexpr-safe node)] (if (symbol? v) #{v} #{}))
    :else #{}))

(defn- w4-names
  "Deterministic generated names for W4's `let`, FRESH against `reserved`.

  The `__rf2` suffix is a convention, not hygiene. A consumer may already
  have a local spelled `f__rf2`, and because `let` binds sequentially, a
  generated binding that shadows one silently rebinds every later
  initializer that referred to it: `(r/partial vector :first f__rf2)`
  inside an outer `f__rf2` emits `(let [f__rf2 vector a1__rf2 f__rf2] …)`,
  whose second initializer now reads `vector` instead of the outer
  binding. That is the fatal silent-behaviour-change class this tool
  exists to delete, so the names are checked against the site.

  Generation 0 is the bare `f__rf2` / `aN__rf2` / `args__rf2`, which is
  what every site free of the suffix gets — the overwhelming majority, and
  the reason the golden corpus can still assert output byte-for-byte. A
  collision on ANY of the three bumps the whole family together
  (`f__rf2__1`, `a0__rf2__1`, `args__rf2__1`) so the emitted form reads as
  one generation rather than a mix. `reserved` is finite, so the search
  terminates.

  The rest name is checked even though `apply`'s argument list holds only
  generated names and self-evaluating literals, so a shadow there cannot
  currently be observed: emitting a binding the site already spells is a
  latent trap for the next change to this shape, and it costs one `not-any?`
  to not do it."
  [reserved arg-indices]
  (first
   (for [g    (range)
         :let [sfx    (if (zero? g) "" (str "__" g))
               callee (symbol (str "f__rf2" sfx))
               rst    (symbol (str "args__rf2" sfx))
               args   (into {} (map (fn [i] [i (symbol (str "a" i "__rf2" sfx))])) arg-indices)]
         :when (not-any? reserved (list* callee rst (vals args)))]
     {:callee callee :rest rst :args args})))

(defn w4-plan
  "**AMENDMENT (A).** Rewrite a literal `(r/partial f a …)` prop value.

  The landed design's stated shape is `(fn [& args] (apply f a … args))`,
  and it is wrong in a way that only shows up at runtime: it re-evaluates
  the callee and every argument form on EACH callback invocation, where
  Reagent's `make-partial-fn` evaluated them ONCE, at construction. So
  `(r/partial f @snapshot)` stopped being a snapshot, and
  `(r/partial f (next-id!))` started minting a new id per click.

  The shape written here is therefore a hygienic `let` that captures the
  callee and every non-literal argument **once, left to right, before the
  wrapper is minted**:

      (r/partial handler @cart (log! :x))
        =>
      (let [f__rf2 handler, a0__rf2 @cart, a1__rf2 (log! :x)]
        (fn [& args__rf2] (apply f__rf2 a0__rf2 a1__rf2 args__rf2)))

  Self-evaluating arguments — keywords, strings, numbers, booleans, `nil`
  — are inlined at their original positions rather than bound, because
  their evaluation has nothing to observe and a binding for one is pure
  noise. Symbols ARE bound: re-reading a var on each invocation picks up a
  redefinition the donor's captured value would not have seen.

  The names are DETERMINISTIC rather than gensym'd, so the golden corpus
  can assert output byte-for-byte — but they are checked against the
  site's own symbols and bumped on a collision, because deterministic and
  fixed are not the same thing and only the first of the two is required.
  See [[w4-names]].

  Law 2 holds by transcription: the wrapper is `apply`, so whatever `f`
  returned before it returns now. That is why W4 cannot blank a render
  prop even at a prop that IS a render prop."
  [call-node]
  (let [els    (elements call-node)
        callee (second els)
        args   (drop 2 els)]
    (when callee
      (let [numbered (map-indexed (fn [i a] [i a (self-evaluating? a)]) args)
            bound    (remove (fn [[_ _ lit?]] lit?) numbered)
            nm       (w4-names (site-symbols call-node) (map first bound))
            f-sym    (:callee nm)
            rest-sym (:rest nm)
            arg-sym  (:args nm)
            binds    (str/join " " (cons (str f-sym " " (str/trim (n/string callee)))
                                         (for [[i a _] bound]
                                           (str (arg-sym i) " " (str/trim (n/string a))))))
            applied  (str/join " " (for [[i a lit?] numbered]
                                     (if lit? (str/trim (n/string a)) (str (arg-sym i)))))
            body     (str "(fn [& " rest-sym "] (apply " f-sym
                          (when (seq applied) (str " " applied))
                          " " rest-sym "))")]
        {:node    (p/parse-string (str "(let [" binds "] " body ")"))
         :captured (vec (for [[_ a _] bound] (str/trim (n/string a))))}))))

;; ---------------------------------------------------------------------------
;; The props walk
;; ---------------------------------------------------------------------------

(defn- meta-key-note []
  (str "Reagent read a `^{:key …}` on the vector AFTER converting the props, so a metadata key "
       "BEAT a props `:key`. Hicasso's `raw-element` reads `(:key props)` and contains no `(meta …)` "
       "read at all, so this key would have gone dead at the migration with no diagnostic from "
       "either Hicasso or React beyond React's own parent-deduped warning. Moved into the props "
       "map, where the destination reads it."))

(defn- plan-props
  "Walk the props map: W2, W3, W4 and every prop-level refusal. Returns
  `{:edit fn :entries [...] :suggest {...}}`, where `:edit` maps the props
  map node to its rewritten self."
  [props-node {:keys [native? w6-candidate?] :as ctx}]
  (reduce
   (fn [acc [k v i]]
     (let [kv    (sexpr-safe k)
           ktext (str/trim (n/string k))
           slot  (when (or (keyword? kv) (symbol? kv) (string? kv)) (dest/canonical-slot kv))
           event? (dest/event-prop? kv)
           vi    (inc i)
           add   (fn [a e] (update a :entries conj e))
           edit  (fn [a new-v]
                   (update a :edit (fn [f] (fn [nd] (replace-element (f nd) vi new-v)))))]
       (cond
         ;; ---- §5.2 `:amp-key` -------------------------------------------------
         (= :& kv)
         (add acc (entry :amp-key :refused {:prop ktext}
                         (str "Reagent emitted a prop LITERALLY NAMED \"&\"; Hicasso reads `:&` as "
                              "its one attribute merge, folding the map into this element's own "
                              "attributes under the owned-literal law. Two unrelated meanings for "
                              "one literal and your intent is unrecoverable from the text — decide "
                              "which you meant.")))

         ;; ---- §5.3 `:dangerous-html` -----------------------------------------
         (dest/dangerous-html-key? kv)
         (add acc (entry :dangerous-html :refused {:prop ktext}
                         (str "MIGRATION BLOCKER. Reagent's `convert-props` DELETED this prop "
                              "unless its value was an `UnsafeHTML` instance, so under Reagent it "
                              "very likely did nothing at all. Hicasso passes it through, so the "
                              "migration RESURRECTS it and the HTML starts being injected. Decide "
                              "deliberately whether you want that.")))

         ;; ---- §5.2 `:named-ref` ----------------------------------------------
         ;; A bare symbol at `:ref` is a CALLBACK ref and is fine; only a
         ;; keyword or quoted symbol made the donor produce a string ref.
         (and (= dest/ref-slot slot) (literal-named-value v))
         (add acc (entry :named-ref :refused {:prop ktext :value (str/trim (n/string v))}
                         (str "Reagent's `(name …)` here produced a STRING REF, which React 19 "
                              "removed and now throws on — so preserving Reagent would restore a "
                              "crash. Replace it with a callback ref (a function taking the node) "
                              "or a `useRef`-style handle.")))

         ;; ---- carriers at event-spelled slots (§5.2 / §5.3) -------------------
         (and event? (or (vector-node? v) (map-node? v)))
         (add acc
              (if w6-candidate?
                (entry :event-carrier-goes-live :refused
                       {:prop ktext :value (str/trim (n/string v))}
                       (str "REWRITE REFUSED, AND THIS IS THE DESIGN'S ONE GENUINELY FATAL CLASS. "
                            "Under Reagent this was INERT — `convert-prop-value` asks `coll?` "
                            "before `ifn?`, so it crossed as an array and the handler never fired. "
                            "At a native Hicasso tag the same form is LOWERED into a live "
                            "dispatch, so rewriting the head to a native tag would turn a handler "
                            "that never fired into one that does. The head is left as `[:>]`. "
                            "Decide whether this handler was ever meant to run."))
                (entry :intent-needs-a-declaration :refused
                       {:prop ktext :value (str/trim (n/string v))}
                       (str "MIGRATION BLOCKER, and the one place Hicasso refuses something "
                            "Reagent accepted. `host-entry` refuses an intent vector or key-map at "
                            "an event-spelled prop the declaration does not name, and a `[:>]` "
                            "crossing declares nothing — so this site will THROW at render. Know "
                            "first that it never fired under Reagent either: it crossed as an "
                            "inert array. Either declare the crossing with `defhost` and name "
                            "this prop in `:callbacks`, or hand it a plain function."))))

         ;; ---- W4 (§4.4), amended by (A) ---------------------------------------
         (reagent-call? v 'partial ctx)
         (if-let [{:keys [node captured]} (w4-plan v)]
           (-> (edit acc node)
               (add (entry :partial-wrapper :rewrote
                           {:prop ktext
                            :was (str/trim (n/string v))
                            :now (str/trim (n/string node))
                            :captured captured}
                           (str "Reagent's `convert-prop-value` ends with an `ifn?` arm that "
                                "wrapped a `PartialFn` in `(fn [& args] (apply x args))`. Hicasso "
                                "has no such arm, so the object would have crossed opaque and this "
                                "handler would have stopped firing with nothing thrown. The "
                                "wrapper is Reagent's own, transcribed — return-transparent, so "
                                "whatever it returned before it returns now. The `let` is not "
                                "decoration: `r/partial` evaluated the callee and every argument "
                                "ONCE when the prop was built, and the `let` is what keeps that "
                                "true instead of re-evaluating them on every invocation."))))
           acc)

         ;; ---- W3 (§4.3), amended by (C) ---------------------------------------
         (literal-named-value v)
         (let [vv (literal-named-value v)]
           (cond
             ;; W6 landed this site on a NATIVE tag, whose Hicasso walk carries
             ;; the `(name v)` arm itself — so W3 is a fixpoint here and firing
             ;; it would only grow the diff (§4.6).
             native? acc
             ;; both runtimes `name` here — a fixpoint, and skipping keeps the diff small
             (dest/html-attr-slot? slot) acc
             ;; the class slot: `class-names` names it on the Hicasso side, in every spelling
             (= dest/class-slot slot) acc
             ;; (C) — ratified exclusion, now with its own report entry
             (= dest/key-slot slot) (add acc (w3-entry :key-slot vv ktext))
             :else
             (-> (edit acc (n/string-node (name vv)))
                 (add (w3-entry (if (namespace vv) :namespaced :plain) vv ktext)))))

         :else acc)))

   {:edit identity :entries []}
   (pairs props-node)))

;; ---------------------------------------------------------------------------
;; The site plan
;; ---------------------------------------------------------------------------

(defn- computed-value-entry
  "ONE entry per site naming every prop whose value the tool could not
  read, rather than one entry per prop — a per-prop line for a class this
  common is noise that teaches migrators to skim the report."
  [props-node ctx]
  (let [unread (->> (pairs props-node)
                    (remove (fn [[_ v _]]
                              (or (read-off-the-text? v)
                                  ;; W4 read this one and rewrote it; a tool that
                                  ;; reports its own rewrite as undecidable is
                                  ;; crying wolf.
                                  (reagent-call? v 'partial ctx))))
                    (mapv (fn [[k _ _]] (str/trim (n/string k)))))]
    (when (seq unread)
      (entry :computed-value :refused {:props unread}
             (str "These prop values are computed, so the tool cannot see what crosses. Three "
                  "things a computed value may silently be, each of which diverges: a KEYWORD or "
                  "symbol (Reagent named it, Hicasso keeps it whole), a NESTED MAP (Reagent "
                  "camelCased its keys, Hicasso does not), or a non-fn `IFn` such as an "
                  "`r/partial` reached through a symbol (Reagent wrapped it, Hicasso passes it "
                  "opaque and the handler stops firing). Check each by hand.")))))

(defn- suggestions
  "The per-component suggestion block (§7.6), LABELLED AS GUESSES. The
  tool suggests a declaration and never writes one: `:callbacks` synthesis
  is never mechanical.

  Only a FOREIGN component earns one. A string head is a native tag —
  W6 rewrites `[:> \"input\" …]` to `[:input …]`, and the two string heads
  W6 declines (the broken `\"div#id\"` shorthand, and a carrier at
  `\"button\"`) are native too — so there is nothing to declare and the
  caller gates on that."
  [head props-node]
  (when props-node
    (let [ps (pairs props-node)
          ev (->> ps (keep (fn [[k _ _]] (let [kv (sexpr-safe k)]
                                           (when (dest/event-prop? kv) (str/trim (n/string k))))))
                  sort vec)
          fns (->> ps (keep (fn [[k v _]] (when (fn-literal? v) (str/trim (n/string k)))))
                   sort vec)]
      (when (or (seq ev) (seq fns))
        {:head head :event-slots ev :fn-slots fns}))))

(defn plan-site
  "Plan one site. Pure, and called by both passes — see this namespace's
  docstring."
  [node ctx]
  (let [kind (site-kind node ctx)
        head (head-text node kind)
        base {:head head :entries [] :edit identity :suggest nil}]
    (case kind
      (:r> :f>)
      (assoc base :entries
             [(entry (if (= :r> kind) :r>-site :f>-site) :refused {}
                     (str "Port this by hand. Hicasso reads `" (name (head-keyword node))
                          "` as an ordinary tag keyword and renders an unknown element — nothing "
                          "throws, nothing warns, and the component never mounts."))])

      (:raw :adapt)
      (let [pidx     (props-index kind)
            slot     (element-at node pidx)
            props    (when (map-node? slot) slot)
            computed? (and slot (not props) (not (child-literal? slot)))
            head-node (element-at node 1)
            head-str  (when (and (= :raw kind) (tag= head-node :token)) (sexpr-safe head-node))
            plain-tag? (and (string? head-str)
                            (re-matches #"[A-Za-z][A-Za-z0-9-]*" head-str))
            ;; W6 fires only when the head is a plain tag string AND no prop
            ;; would go live at a native destination.
            carrier?  (and props
                           (some (fn [[k v _]]
                                   (and (dest/event-prop? (sexpr-safe k))
                                        (or (vector-node? v) (map-node? v))))
                                 (pairs props)))
            w6-cand?  (and (= :raw kind) plain-tag?)
            w6?       (and w6-cand? (not carrier?))
            ctx       (assoc ctx :w6-candidate? w6-cand? :native? w6?)

            ;; ---- the props walk -------------------------------------------
            ;; Always walked, even at a W6 site: the head repair must not
            ;; suppress a refusal. `:native?` is what suppresses the two
            ;; rewrites that are fixpoints at a native tag; W1, W4 and every
            ;; refusal class still fire there (§4.6).
            pp        (when props (plan-props props ctx))
            ;; W2 runs per nested literal map value, under the props walk
            w2        (when (and props (not w6?))
                        (reduce (fn [acc [k v i]]
                                  (if (map-node? v)
                                    (let [sub (w2-map-plan v [(str/trim (n/string k))])]
                                      (cond-> (update acc :entries into (:entries sub))
                                        (:changed? sub)
                                        (-> (update :edit (fn [f] (fn [nd]
                                                                    (let [nd (f nd)]
                                                                      (replace-element nd (inc i)
                                                                                       ((:edit sub) (element-at nd (inc i))))))))
                                            (update :entries conj (w2-entry (str/trim (n/string k))
                                                                            (:respelled sub))))))
                                    acc))
                                {:edit identity :entries []}
                                (pairs props)))

            ;; ---- W1 ---------------------------------------------------------
            meta-keys (:meta-keys ctx)
            props-key (when props
                        (some (fn [[k _ _]] (when (= :key (sexpr-safe k)) k)) (pairs props)))
            w1?       (and (= 1 (count meta-keys)) (not props-key) (or props (not computed?)))
            w1-clash? (and (seq meta-keys) (or (> (count meta-keys) 1) props-key))
            w1-blocked? (and (= 1 (count meta-keys)) (not props-key) computed?)

            entries (cond-> []
                      computed?
                      (conj (entry :computed-props :refused
                                   {:slot (str/trim (n/string slot))}
                                   (str "The slot after the head is neither a literal map nor "
                                        "obviously a child, so the tool could not tell props from "
                                        "a child and left the whole site alone. Every prop-level "
                                        "repair is unavailable here — check this site by hand.")))

                      w1-clash?
                      (conj (entry :key-conflict :refused {}
                                   (str "Both a `^{:key …}` and a props `:key` are present. "
                                        "Reagent's metadata key WON, because `native-element` sets "
                                        "it after `convert-props` has run — so preserving your "
                                        "current behaviour would mean OVERWRITING the `:key` you "
                                        "wrote in the map, and an overwrite is a decision. Delete "
                                        "whichever one is dead and re-run.")))

                      w1-blocked?
                      (conj (entry :computed-props :refused {}
                                   (str "A `^{:key …}` needs a props map to move into and the "
                                        "props slot is computed, so the key cannot be relocated. "
                                        "It will go DEAD at the migration — move it by hand.")))

                      (:cljc? ctx)
                      (conj (entry :cljc-site :refused {}
                                   (str "This crossing is in a `.cljc` file. `[:>]` is a "
                                        "ClojureScript-only node — the JVM side of this file has "
                                        "no React to cross into — so check that this branch is "
                                        "reader-conditioned to `:cljs`.")))

                      (and props (subtree-has-reagent-call? props 'as-element ctx))
                      (conj (entry :as-element-island :refused {}
                                   (str "An `r/as-element` inside a callback body at this site. "
                                        "Port it by hand: the closure runs when the library calls "
                                        "it, OUTSIDE the owner's render window, so swapping "
                                        "Reagent's lowering for Hicasso's is not a text "
                                        "substitution.")))

                      (and props (some #(subtree-has-reagent-call? props % ctx)
                                       '[atom cursor track reaction create-class with-let]))
                      (conj (entry :reagent-api-residue :refused {}
                                   (str "Reagent API — `r/atom`, `r/cursor`, `r/track`, a form-2 or "
                                        "form-3 shape — inside this site. That is outside this "
                                        "tool's fence entirely; it is named here so you are not "
                                        "surprised by it later."))))

            cv      (when props (computed-value-entry props ctx))
            entries (cond-> entries cv (conj cv))

            ;; ---- heads ------------------------------------------------------
            head-entries
            (cond
              (= :adapt kind)
              [(entry :adapt-react-class-head :rewrote {:head head}
                      (str "`vec-to-elem` sent a `NativeWrapper` head and a `[:> C …]` head to the "
                           "SAME `native-element`, with the props slot at a different index — two "
                           "spellings of one path. Respelled as `[:> …]`, which is the form "
                           "Hicasso accepts; left alone it is a head Hicasso answers with "
                           "`:rf.error/hicasso-bad-head`."))]

              w6?
              [(entry :string-tag-head :rewrote {:head head-str}
                      (str "Under Reagent `[:> \"" head-str "\" …]` took the controlled-input "
                           "wrapper when the tag was `input` or `textarea`, because "
                           "`input-component?` matches on exactly this path. Under Hicasso a "
                           "string head is REFUSED at the crossing. Rewritten to `[:" head-str
                           " …]`, which lands the site on Hicasso's own controlled door — the "
                           "taught form. Reagent's deep camelCasing and its `(name …)` arm are "
                           "both present at a native tag, so the prop rewrites are fixpoints here "
                           "and are deliberately not applied."))]

              (and (= :raw kind) (string? head-str) (not plain-tag?))
              [(entry :string-tag-unparseable :refused {:head head-str}
                      (str "This string head was ALREADY BROKEN under Reagent: the `:>` path never "
                           "parsed the `#`/`.` shorthand, so `createElement(\"" head-str "\")` "
                           "asked React for an element that does not exist. Rewriting it to a "
                           "native tag would PARSE the shorthand and the site would start "
                           "behaving differently, so it is left for you."))]

              (and (= :raw kind) (string? head-str) plain-tag? carrier?)
              []                       ; the carrier entry above already explains the refusal

              :else [])

            entries (into entries head-entries)
            entries (into entries (:entries pp))
            entries (into entries (:entries w2))

            ;; ---- the edit ---------------------------------------------------
            key-node  (first meta-keys)
            props-edit (apply comp (keep :edit [w2 pp]))
            edit
            (fn [nd]
              (let [;; 1. The head repair, which is the only edit that moves
                    ;;    the props slot. `pidx*` is where the props map sits
                    ;;    once the head is settled.
                    [nd pidx*]
                    (cond
                      ;; W5 — `[(r/adapt-react-class X) props …]` → `[:> X props …]`.
                      ;; `nd` inside the second form is still the ORIGINAL node,
                      ;; so element 0 is the adapt call the component is read out of.
                      (= :adapt kind)
                      [(-> nd
                           (replace-element 0 (n/token-node :>))
                           (insert-element 1 (element-at (element-at nd 0) 1)))
                       2]

                      ;; W6 — `[:> "input" props …]` → `[:input props …]`.
                      ;; The `:>` head becomes the tag and the string goes away,
                      ;; so the props slot moves down one.
                      w6?
                      [(-> nd
                           (replace-element 0 (n/token-node (keyword head-str)))
                           (remove-element 1))
                       1]

                      :else [nd 2])

                    ;; 2. The props rewrites, applied in place.
                    nd (if props (replace-element nd pidx* (props-edit (element-at nd pidx*))) nd)]

                ;; 3. W1 — mint the props map, or extend the one that is there.
                (if w1?
                  (if props
                    (replace-element nd pidx*
                                     (let [m (element-at nd pidx*)]
                                       (n/replace-children
                                        m (concat (n/children m)
                                                  (when (seq (elements m)) [(n/spaces 1)])
                                                  [(n/token-node :key) (n/spaces 1) key-node]))))
                    (insert-element nd pidx*
                                    (n/map-node [(n/token-node :key) (n/spaces 1) key-node])))
                  nd)))]

        (assoc base
               :kind kind
               :w1? w1?
               :entries (cond-> entries
                          w1? (conj (entry :metadata-key :rewrote
                                           {:key (str/trim (n/string key-node))}
                                           (meta-key-note))))
               ;; `head-str` is a string only when the head is a string
               ;; LITERAL, which is a native tag and never a host.
               :suggest (when-not (string? head-str) (suggestions head props))
               :edit edit))

      base)))
