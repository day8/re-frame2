(ns hooks.re-frame.hicasso
  "clj-kondo hooks for the Hicasso authoring surface (rf2-hic-022).

  Two jobs, and they are worth separating because only the first is
  ordinary.

  **Shape.** `defview` and `hfn` are `defn`- and `fn`-shaped macros, so the
  hooks rewrite them into those forms and let kondo's own analysis do the
  work — arglists, arity, lexical bindings, unused bindings. Without this a
  view's name and every destructured prop read as `Unresolved symbol`.
  This is the same shape as the repo-root `hooks.re-frame.core` hook for
  `reg-view`, and is deliberately no cleverer.

  **The checks.** Six findings, all of them SYNTACTIC FACTS about the form
  in hand. A clj-kondo hook sees one top-level call and nothing else: it
  does not know what a symbol resolves to at runtime, whether a
  subscription is registered, or what a value will be. Every check here is
  therefore written to be ALWAYS RIGHT about a narrow thing rather than
  usually right about a broad one, and each declines — silently — the
  moment the form stops being decidable. `README.md` beside this file lists
  what each one refuses to know, and why the obvious wider version is not
  here.

  The rule the whole file is written to: a check that fires on correct code
  is worse than no check, because it teaches people to ignore the linter."
  (:require [clj-kondo.hooks-api :as api]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Resolution — what a symbol actually names
;; ---------------------------------------------------------------------------

(defn- token-sexpr
  "The symbol a token node names, or nil for any other node."
  [node]
  (when (api/token-node? node)
    (let [s (api/sexpr node)]
      (when (symbol? s) s))))

(defn- hicasso-var
  "The `re-frame.hicasso` var this node names, or nil.

  Resolution rather than spelling: an author may alias the namespace as
  `h`, refer `sub` in directly, or write it fully qualified, and all three
  are the same var. `api/resolve` reads the file's own ns form, so the
  answer is a fact about this file rather than a guess about a convention."
  [node]
  (when-let [s (token-sexpr node)]
    (let [{:keys [ns name]} (api/resolve {:name s})]
      (when (= 're-frame.hicasso ns) name))))

(defn- core-var
  "The `clojure.core` / `cljs.core` var this node names, or nil.

  A node that resolves to some OTHER namespace answers nil — a local
  `my.util/map` is not `clojure.core/map`. A node that resolves to nothing
  at all falls back to its bare symbol, which is what an unanalysable
  `:require` or a `.cljs` file linted without its classpath leaves behind."
  [node]
  (when-let [s (token-sexpr node)]
    (let [{:keys [ns name]} (api/resolve {:name s})]
      (cond
        (contains? #{'clojure.core 'cljs.core} ns) name
        (nil? ns)                                  (symbol (clojure.core/name s))
        :else                                      nil))))

(defn- reads-hicasso-state?
  "Does this node name one of the two reading doors?"
  [node]
  (contains? #{'sub 'use-subs} (hicasso-var node)))

;; ---------------------------------------------------------------------------
;; Walking — every node under `node`, quoted data excepted
;; ---------------------------------------------------------------------------

(defn- subforms
  "Depth-first seq of `node` and its descendants, NOT descending into
  quoted forms: `'[:div {:& 1}]` is data an author wrote down, not hiccup
  the runtime will interpret, and judging it would be judging a quotation."
  [node]
  (when (and node (not (api/quote-node? node)))
    (cons node (mapcat subforms (:children node)))))

;; ---------------------------------------------------------------------------
;; Hiccup shapes
;; ---------------------------------------------------------------------------

(defn- scalar
  "`[value]` when this node is a literal SCALAR — a keyword, string, number,
  boolean, character or `nil` — and nil when it is anything else, including
  a symbol.

  The one-element vector is not decoration: a literal `nil` and \"not a
  literal at all\" are different answers, and every caller below turns on
  which one it got. Keywords are their own node type in the hook API rather
  than token nodes, and reading them as tokens is how the first draft of
  this file made every hiccup-shaped check silently blind."
  [node]
  (when (or (api/keyword-node? node)
            (api/string-node? node)
            (api/token-node? node))
    (let [s (api/sexpr node)]
      (when-not (symbol? s) [s]))))

(defn- tag-keyword
  "The keyword a node names when it is a literal keyword, else nil."
  [node]
  (let [v (first (scalar node))]
    (when (keyword? v) v)))

(defn- hiccup-vector?
  "Is this node a vector whose head is a LITERAL keyword — `[:div …]`,
  `[:button.icon …]`, `[:<> …]`, `[:> Foo …]`?

  A keyword head is what makes a vector unambiguously hiccup. A SYMBOL
  head — a view, a host, or an ordinary two-element data vector — is not
  decidable here, so nothing below judges one.

  The head must be UNQUALIFIED. A hiccup tag never carries a namespace, and
  a qualified keyword at the head of a vector is ordinary tagged data —
  `[:app/button 1]` is not a `<button>`, and `base-tag` would happily read
  one out of it."
  [node]
  (boolean (and (api/vector-node? node)
                (when-let [kw (tag-keyword (first (:children node)))]
                  (nil? (namespace kw))))))

(defn- base-tag
  "`:button.icon#close` -> \"button\". The selector shorthand is sugar for
  attributes and never changes which element is being written."
  [kw]
  (first (str/split (name kw) #"[.#]")))

(defn- props-node
  "The props map a hiccup vector writes, or nil when it writes none.

  Only a MAP LITERAL answers. A symbol or a call at that position may
  evaluate to a props map or to a child — the codec decides at runtime with
  `map?`, and this cannot."
  [node]
  (let [second-child (second (:children node))]
    (when (api/map-node? second-child) second-child)))

(defn- definitely-not-props?
  "Is the node at position 1 a literal that CANNOT be a props map: a string,
  a number, a keyword, a vector? Then the element writes no attributes at
  all, which is a fact rather than a guess. A symbol or a call answers
  false, because either may evaluate to a map."
  [node]
  (cond
    (nil? node)             true
    (api/map-node? node)    false
    (api/vector-node? node) true
    (api/set-node? node)    true
    :else                   (some? (scalar node))))

(defn- map-keys
  "The literal keywords a map node writes as keys."
  [node]
  (into #{}
        (keep tag-keyword)
        (take-nth 2 (:children node))))

(defn- child-nodes
  "The CHILDREN of a hiccup vector — everything after the head, less the
  props map when one is written."
  [node]
  (let [[_head & more] (:children node)]
    (if (props-node node) (rest more) more)))

(defn- element-subforms
  "`subforms`, minus the PROPS MAPS of hiccup vectors.

  A props map's values are attributes, not children, and an event vector
  written at one is indistinguishable from a hiccup element by SHAPE:
  `{:on-click [:a]}` and the anchor `[:a]` are the same three characters.
  Position is the only honest discriminator, so the element checks never
  look inside a props map. This is not hypothetical — it is the false
  positive the negative fixtures caught in the first draft, where every
  `:on-click [:a]` in the corpus read as an unnamed anchor.

  The `:&` check deliberately uses the FULL walk instead: a merge key lives
  in a props map by definition."
  [node]
  (when (and node (not (api/quote-node? node)))
    (let [skip (when (hiccup-vector? node) (props-node node))]
      (cons node (mapcat element-subforms
                         (remove #(identical? % skip) (:children node)))))))

;; ---------------------------------------------------------------------------
;; The findings
;; ---------------------------------------------------------------------------

(defn- finding!
  [node type message]
  (api/reg-finding! (assoc (meta node) :type type :message message)))

;; --- `:&` carries a caller's attribute map and nothing else ----------------

(defn- merge-value-problem
  "A one-word description of what `:&` was literally given, when that is a
  literal of a type it can never accept; nil otherwise.

  A symbol or a call answers nil on purpose: `{:& attrs}` is the ordinary
  spelling and its value is unknowable here. `nil` answers nil too — the
  runtime folds it away."
  [node]
  (cond
    (api/vector-node? node) "a vector"
    (api/set-node? node)    "a set"
    :else
    (when-let [[v] (scalar node)]
      (cond
        (nil? v)     nil
        (string? v)  "a string"
        (keyword? v) "a keyword"
        (number? v)  "a number"
        (boolean? v) "a boolean"
        (char? v)    "a character"
        :else        nil))))

(defn- check-merge-key!
  "`:&` carrying a literal the runtime will refuse
  (`:rf.error/hicasso-merge-not-a-map`)."
  [node]
  (when (api/map-node? node)
    (let [children (:children node)]
      (doseq [[k v] (partition 2 children)
              :when (= :& (tag-keyword k))
              :let  [problem (merge-value-problem v)]
              :when problem]
        (finding! v :re-frame.hicasso/merge-not-a-map
                  (str ":& carries a caller's attribute map and nothing else. "
                       "It was given " problem ". Forward a map, or drop the key."))))))

;; --- a read in the one position that is deferred by construction ----------

(defn- check-read-in-callback!
  "`h/sub` / `h/use-subs` inside an `hfn` body.

  `hfn` IS the callback form — its whole contract is that it runs later —
  so a read written inside one is deferred by construction rather than by
  circumstance, and the runtime refuses it with
  `:rf.error/hicasso-sub-outside-render`. This is the ONLY deferred-read
  shape that is a syntactic fact; see README."
  [body]
  (doseq [node (mapcat subforms body)
          :when (api/list-node? node)
          :let  [head (first (:children node))]
          :when (reads-hicasso-state? head)]
    (finding! node :re-frame.hicasso/deferred-read
              (str "A subscription is read inside `hfn`, which runs AFTER the body "
                   "that wrote it, so the read has no boundary to belong to and "
                   "is refused at runtime. Read during the body and close over "
                   "the value, or dispatch an event that reads it."))))

;; --- a read parked in a mutable reference (rf2-djxr) -----------------------

(defn- thunk-body
  "The forms a LITERAL thunk defers: `(delay …)`, `(fn … )`, `#(…)`. nil for
  anything else, including a symbol naming a thunk built elsewhere.

  `#(…)` is its own node type rather than a list, which is why it is asked
  about by tag: reading it as a list would miss the shortest spelling of
  the very thing this looks for."
  [node]
  (when node
    (cond
      (= :fn (api/tag node)) (:children node)
      (api/list-node? node)  (when (contains? #{'delay 'fn 'fn*}
                                              (core-var (first (:children node))))
                               (rest (:children node)))
      :else                  nil)))

(defn- parked-thunk?
  "Does this node defer a Hicasso read, written out in full at this
  position?"
  [node]
  (boolean
    (when-let [body (thunk-body node)]
      (some #(and (api/list-node? %)
                  (reads-hicasso-state? (first (:children %))))
            (mapcat subforms body)))))

(defn- check-parked-read!
  "`(reset! r (delay … (h/sub …)))` / `(vreset! r (fn [] … (h/sub …)))`.

  Per the rf2-djxr ruling the runtime does NOT chase deferred reads through
  mutable references — `realize-deep` walks the structure a body returns,
  and a reference is not in it. Forcing such a thunk inside another body is
  undefined conduct rather than an error, so this is a WARNING and nothing
  here enforces anything. It is syntactic in the strictest sense: the thunk
  must be written literally as the argument of the `reset!`."
  [body]
  (doseq [node (mapcat subforms body)
          :when (api/list-node? node)
          :let  [[head _target value] (:children node)]
          :when (contains? #{'reset! 'vreset!} (core-var head))
          :when (parked-thunk? value)]
    (finding! value :re-frame.hicasso/parked-read
              (str "A subscription read is parked in a mutable reference. The "
                   "read-extent law covers the structure a body RETURNS, so a "
                   "deferral held in a reference is outside it. Forcing this in "
                   "another body is undefined: the edge is attributed to "
                   "whichever body forces it, and a `delay` caches, so it is "
                   "then dropped. Read during the body and park the VALUE."))))

;; --- mapped children without a key ----------------------------------------

(def ^:private mapping-forms
  "The core forms whose element expression is written at a fixed position.
  `for` takes its element last; the rest take a function first."
  '#{for map mapv keep map-indexed})

(defn- element-expression
  "The expression a mapping form produces one child from, when that
  position is written literally enough to read."
  [node]
  (let [[head & more] (:children node)
        nm            (core-var head)]
    (case (some-> nm str)
      "for" (last more)
      ("map" "mapv" "keep" "map-indexed")
      (let [f (first more)]
        (when (and (api/list-node? f)
                   (contains? #{'fn 'fn*} (core-var (first (:children f)))))
          (last (:children f))))
      nil)))

(defn- check-unkeyed-children!
  "A mapping form in a CHILDREN position of a literal hiccup vector, whose
  element is a literal hiccup vector that writes no `:key`.

  Both ends have to be literal for this to be a fact rather than a hunch:
  the mapping form must sit directly in children position (not behind a
  `let`), and the element must be a keyword-headed vector whose props are a
  map literal — or provably absent. Warning level, as the bead specifies."
  [node]
  (when (hiccup-vector? node)
    (doseq [child (child-nodes node)
            :when (api/list-node? child)
            :when (contains? mapping-forms (core-var (first (:children child))))
            :let  [element (element-expression child)]
            :when (and element (hiccup-vector? element))
            :let  [props (props-node element)
                   at-1  (second (:children element))]
            :when (if props
                    (not (contains? (map-keys props) :key))
                    (definitely-not-props? at-1))]
      (finding! element :re-frame.hicasso/unkeyed-mapped-child
                (str "A mapped child writes no `:key`, so React cannot keep its "
                     "identity across a commit, so state, focus and animation "
                     "follow position instead of data. Add `:key` to the props "
                     "map. A `:&` remainder cannot supply one: `key` is a "
                     "structural slot no merge may reach.")))))

;; --- an interactive element with nothing to name it -----------------------

(def ^:private interactive-tags
  "Tags whose whole purpose is to be operated, and which are inoperable
  from a screen reader without an accessible name. Deliberately two: an
  `:input`'s name usually comes from a sibling `<label for=…>`, which is a
  fact about the TREE rather than about the element, and belongs to the
  real a11y pass (hic-043)."
  #{"button" "a"})

(def ^:private naming-attributes
  "The attributes that give an element an accessible name on their own."
  #{:aria-label :aria-labelledby :title})

(defn- check-nameless-interactive!
  "`[:button {…}]` / `[:a {…}]` with NO children and no naming attribute.

  Zero children is what makes this a fact: an element with any child at all
  may well render text, and this cannot know. Dynamic props answer the same
  way — the name may be in there."
  [node]
  (when (hiccup-vector? node)
    (let [tag      (tag-keyword (first (:children node)))
          children (child-nodes node)
          props    (props-node node)
          at-1     (second (:children node))]
      (when (and (contains? interactive-tags (base-tag tag))
                 (empty? children)
                 (or props (definitely-not-props? at-1))
                 (empty? (into #{} (filter naming-attributes)
                               (some-> props map-keys))))
        (finding! node :re-frame.hicasso/nameless-interactive-element
                  (str "This <" (base-tag tag) "> has no children and no "
                       ":aria-label, :aria-labelledby or :title, so it has no "
                       "accessible name; a screen reader announces it as an "
                       "unlabelled control. Give it text, or name it."))))))

;; --- a function literal where a head belongs ------------------------------

(defn- function-literal?
  "Is this node a literal `(fn …)`, `#(…)` or `(hfn …)`?"
  [node]
  (boolean
    (when node
      (or (= :fn (api/tag node))
          (and (api/list-node? node)
               (let [head (first (:children node))]
                 (or (contains? #{'fn 'fn*} (core-var head))
                     (= 'hfn (hicasso-var head)))))))))

(defn- check-function-head!
  "A function literal in the head of a vector sitting in a CHILDREN
  position of a literal hiccup vector.

  The children-position requirement is what makes this always right rather
  than usually right: `[(fn [] :a) (fn [] :b)]` bound in a `let` is an
  ordinary vector of functions and is none of this check's business. The
  same vector written as a child of `[:div …]` is a head, and a function is
  never a legal one (`:rf.error/hicasso-bad-head`)."
  [node]
  (when (hiccup-vector? node)
    (doseq [child (child-nodes node)
            :when (api/vector-node? child)
            :let  [head (first (:children child))]
            :when (function-literal? head)]
      (finding! head :re-frame.hicasso/function-in-head-position
                (str "A function in head position is never a silent embedding. "
                     "Call it, or make it a view with `defview`: a view is a "
                     "real component and is a legal head; a function is not.")))))

;; ---------------------------------------------------------------------------
;; The hooks
;; ---------------------------------------------------------------------------

(defn- check-body!
  "Every hiccup-shaped check, over one body."
  [body]
  (run! check-merge-key! (mapcat subforms body))
  (let [elements (mapcat element-subforms body)]
    (run! check-unkeyed-children! elements)
    (run! check-nameless-interactive! elements)
    (run! check-function-head! elements))
  (check-parked-read! body))

(defn defview
  "`(defview sym doc? [props] body+)` -> `(defn sym doc? [props] body+)`,
  plus the body checks."
  [{:keys [node]}]
  (let [[_defview sym & more] (:children node)
        [doc more]            (if (api/string-node? (first more))
                                [(first more) (rest more)]
                                [nil more])
        [argv & body]         more]
    (check-body! body)
    {:node (api/list-node
             (concat [(api/token-node 'clojure.core/defn) sym]
                     (when doc [doc])
                     [argv]
                     body))}))

(defn hfn
  "`(hfn [args] body+)` -> `(fn [args] body+)`, plus the one deferred-read
  check the callback form makes decidable."
  [{:keys [node]}]
  (let [[_hfn argv & body] (:children node)]
    (check-read-in-callback! body)
    {:node (api/list-node
             (list* (api/token-node 'clojure.core/fn) argv body))}))

(defn defhost
  "`(defhost sym doc? component opts?)` -> a `def` of the minted head.

  Not `defn`: a host declares no argument vector, because the props it
  accepts are the foreign component's business rather than the
  declaration's. `opts` is analysed as an ordinary expression so a
  reference inside `:ssr` / `:callbacks` is neither unresolved nor unused."
  [{:keys [node]}]
  (let [[_defhost sym & more] (:children node)
        [doc more]            (if (api/string-node? (first more))
                                [(first more) (rest more)]
                                [nil more])
        [component opts]      more
        ;; `do` and `def` are SPECIAL FORMS, so they are spelled bare: a
        ;; qualified `clojure.core/do` reads as a var that does not exist and
        ;; kondo says so, at the call site, in the consumer's file.
        value                 (if opts
                                (api/list-node
                                  [(api/token-node 'do) opts component])
                                component)]
    {:node (api/list-node
             (concat [(api/token-node 'def) sym]
                     (when doc [doc])
                     [value]))}))
