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

(defn- native-tag
  "The tag a node names when it is a LITERAL native head, else nil.

  Taken from the runtime rather than guessed: `codec/hiccup-tag?` is
  `(or (keyword? head) (symbol? head) (string? head))` and `parse-tag`
  reads it through `name`. So a STRING head is a tag, and a QUALIFIED
  keyword is one too — `[:app/button …]` renders a `<button>`, because
  `(name :app/button)` is \"button\". An earlier draft of this file claimed
  the opposite and was simply wrong about shipped behaviour.

  Only a literal answers. A symbol head is a runtime VALUE — a view or a
  host — and is not decidable here."
  [node]
  (let [v (first (scalar node))]
    (cond
      (keyword? v) (name v)
      (string? v)  v
      :else        nil)))

(defn- hiccup-vector?
  "Is this node a vector whose head is a literal native tag — `[:div …]`,
  `[:button.icon …]`, `[\"button\" …]`, `[:app/button …]`, `[:<> …]`?

  A literal head is what makes a vector decidably hiccup. A SYMBOL head (a
  view or a host) is a value this cannot resolve, so nothing below judges
  one."
  [node]
  (boolean (and (api/vector-node? node)
                (native-tag (first (:children node))))))

(defn- base-tag
  "`\"button.icon#close\"` -> `\"button\"`. The selector shorthand is sugar
  for attributes and never changes which element is being written."
  [tag]
  (first (str/split tag #"[.#]")))

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
          :let  [[head _target & args] (:children node)]
          :when (contains? #{'reset! 'vreset! 'swap!} (core-var head))
          value (filter parked-thunk? args)]
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

(def ^:private naming-attributes
  "The attributes that give an element an accessible name on their own.
  The same three the compiled substrates' a11y pass names
  (`re-frame.ui.compiler.a11y`, `:rf.ui.compile/a11y-missing-accessible-name`)."
  #{:aria-label :aria-labelledby :title})

(defn- needs-an-accessible-name?
  "Is this an element that is named by its CONTENT, and so unusable without
  one when it has none?

  The tag set and the `:href` condition are taken from the compiled
  substrates' a11y pass rather than invented here, so the two agree about
  what a nameless control is: a `<button>` always, and an `<a>` only when it
  is a real link. An `<a>` with no `href` is not focusable and not a link,
  so demanding a name for one would be a false positive.

  `:input`, `:select` and `:textarea` are in that pass's set and NOT here:
  their name usually comes from a sibling `<label for=…>`, which is a fact
  about the TREE rather than about the form in hand, and belongs to the real
  a11y pass (hic-043)."
  [tag props]
  (let [base (base-tag tag)]
    (or (= "button" base)
        (and (= "a" base)
             (contains? (some-> props map-keys) :href)))))

(defn- check-nameless-interactive!
  "`[:button {…}]` / `[:a {…}]` with NO children and no naming attribute.

  Zero children is what makes this a fact: an element with any child at all
  may well render text, and this cannot know. Dynamic props answer the same
  way — the name may be in there."
  [node]
  (when (hiccup-vector? node)
    (let [tag      (native-tag (first (:children node)))
          children (child-nodes node)
          props    (props-node node)
          at-1     (second (:children node))]
      (when (and (needs-an-accessible-name? tag props)
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
  (doseq [child (if (hiccup-vector? node) (child-nodes node) nil)
          :when (api/vector-node? child)
          :let  [head (first (:children child))]
          :when (function-literal? head)]
    (finding! head :re-frame.hicasso/function-in-head-position
              (str "A function in head position is never a silent embedding. "
                   "Call it, or make it a view with `defview`: a view is a "
                   "real component and is a legal head; a function is not."))))

(defn- check-root-head!
  "The same law at the ROOT of a body.

  `(defview root [_] [(fn [] …)])` is the identical mistake, and the
  children-position rule above cannot see it: the offending vector is what
  the body RETURNS rather than a child of anything. A body's return value
  is a hiccup position by construction — that is what a boundary is — so
  the head of a literal vector there is decidable without guessing.

  Applied to the last form of the body, and through the tails of the
  ordinary wrappers a body's return threads out of."
  [node]
  (when node
    (cond
      (api/vector-node? node)
      (let [head (first (:children node))]
        (when (function-literal? head)
          (finding! head :re-frame.hicasso/function-in-head-position
                    (str "A function in head position is never a silent "
                         "embedding, and this one is what the body RETURNS. "
                         "Call it, or make it a view with `defview`."))))

      ;; `let`, `when`, `if`, `do`, `when-let`… — a body's return threads
      ;; out through the tail of each, so the same position is still a
      ;; hiccup position one level in. Only the TAIL: a non-tail form is an
      ;; ordinary expression and none of this check's business.
      (api/list-node? node)
      (let [nm (some-> (core-var (first (:children node))) str)]
        (when (contains? #{"let" "when" "when-let" "when-some" "if-let"
                           "if-some" "do" "when-not" "with-redefs" "binding"}
                         nm)
          (check-root-head! (last (:children node))))
        (when (= "if" nm)
          (let [[_if _test then else] (:children node)]
            (check-root-head! then)
            (check-root-head! else)))))))

;; ---------------------------------------------------------------------------
;; The hooks
;; ---------------------------------------------------------------------------

(def ^:private synchronous-iteration
  "Core forms that call their function DURING the body. A `sub` inside a fn
  literal handed to one of these is an ordinary read of the boundary that
  wrote it, not a deferred one, and the codec's eager walk is what makes it
  so. Naming them is what keeps the check below off correct code."
  '#{map mapv keep keep-indexed map-indexed mapcat filter remove
     reduce reduce-kv some every? sort-by group-by run! doseq for})

(defn- check-deferred-read-in-fn!
  "`h/sub` inside a plain `(fn …)` / `#(…)` written in a body, unless that
  function is handed straight to a synchronous iteration form.

  Roster item 3 (operator ruling): the obviously-deferred read, at WARNING.
  A warning rather than an error because the judgement is a HEURISTIC —
  `(some-helper (fn [] (h/sub …)))` may well call its argument during the
  body, and this cannot know. What it can see is that nothing standard is
  about to call it synchronously, which is where the mistake actually
  lives: a callback, a timer, a promise, a thunk stashed for later.

  Read extent in general remains the RUNTIME's law (I7 / hic-011); this
  never pretends otherwise, which is why it does not block a build."
  [body]
  (doseq [node (mapcat subforms body)
          :when (api/list-node? node)
          :let  [callee (core-var (first (:children node)))]
          ;; `parked-read` owns the mutable-reference shape and says
          ;; something sharper about it. Two warnings on one site is
          ;; nagging, so this one yields.
          :when (not (contains? synchronous-iteration callee))
          :when (not (contains? #{'reset! 'vreset! 'swap!} callee))
          arg   (rest (:children node))
          :when (and (function-literal? arg)
                     (not= 'hfn (hicasso-var (first (:children arg)))))
          :when (some #(and (api/list-node? %)
                            (reads-hicasso-state? (first (:children %))))
                      (mapcat subforms (or (thunk-body arg) [])))]
    (finding! arg :re-frame.hicasso/deferred-read
              (str "A subscription is read inside a function that nothing here "
                   "calls during this body, so the read is very likely deferred "
                   "— and a deferred read has no boundary to belong to. Read "
                   "during the body and close over the value. If this function "
                   "IS called synchronously, ignore this: read extent is the "
                   "runtime's law, not lint's."))))

(defn- check-direct-view-call!
  "A view called like a function, where that is decidable.

  `defview` mints a React component and it is used as a HEAD:
  `[todo-row {…}]`, never `(todo-row {…})`. The general check needs to know
  that a symbol resolves to a var minted by `defview`, usually in another
  namespace, and a hook sees one form — so the decidable slice is the view
  calling ITSELF, whose name this hook is holding. Narrow, but always right
  and editor-reliable, which the wider version is not (see README)."
  [sym body]
  (when-let [nm (token-sexpr sym)]
    (doseq [node (mapcat subforms body)
            :when (api/list-node? node)
            :when (= nm (token-sexpr (first (:children node))))]
      (finding! node :re-frame.hicasso/direct-view-call
                (str "`" nm "` is a view, so it is a hiccup HEAD rather than a "
                     "function: write [" nm " {…}]. Called directly it returns "
                     "the runtime's component object instead of rendering, and "
                     "no boundary is minted, so its reads belong to whichever "
                     "body called it.")))))

(defn- check-body!
  "Every hiccup-shaped check, over one body."
  [body]
  (let [elements (mapcat element-subforms body)]
    (run! check-unkeyed-children! elements)
    (run! check-nameless-interactive! elements)
    (run! check-function-head! elements))
  (check-root-head! (last body))
  (check-deferred-read-in-fn! body)
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
    (check-direct-view-call! sym body)
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
