(ns re-frame.ui.compiler.analyze
  "Template-grammar analyzer: one normalized, closed-node-set template AST
  from the blessed hiccup grammar (Spec 004 rewrite §Template grammar).
  The AST is PRIVATE — the public contract is the JVM tree + the
  conversion table; both emitters consume ONLY these nodes:

    :text :nothing :expr :element :fragment :view :foreign
    :if :let :letfn :case :for :raw :html

  Control forms (let/letfn/if/if-not/when/when-not/cond/case/pure-do/for)
  normalize INTO the AST; every analyzer and both emitters see through
  branches. Rejected forms throw compile errors with
  {:rf.ui.compile/error <id>} ex-data (the S1e roster keys off the ids)."
  (:require [clojure.string :as str]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.fingerprint :as fingerprint]
            [re-frame.ui.rules :as rules]))

(def node-ops
  "The CLOSED op set — the AST-shape gate's vocabulary. `:frame-root` (S1c,
  rf2-vxgfnd.3) appears only inside the static top region of a ROOT form
  (`ui/mount` / `ui/render!` / `ui/hydrate-root`) — the analyzer rejects it
  everywhere else, so defview-template ASTs never carry it.
  `:frame-provider` (S2c, rf2-vxgfnd.9) is the SCOPE form — legal anywhere,
  scoping a subtree to an already-live frame."
  #{:text :nothing :expr :element :fragment :view :foreign
    :if :let :letfn :case :for :raw :html :frame-root :frame-provider :slot})

(defn literal-scalar? [x]
  (or (string? x) (number? x) (keyword? x) (boolean? x) (nil? x)))

(def ^:private ui-raw-fqns    #{'re-frame.ui/raw})
(def ^:private ui-html-fqns   #{'re-frame.ui/html})
(def ^:private ui-raw-fn-fqns #{'re-frame.ui/raw-fn})
(def ^:private ui-spread-fqns #{'re-frame.ui/spread})
(def ^:private ui-spread-safe-fqns #{'re-frame.ui/spread-safe})
(def ^:private ui-event-fqns  #{'re-frame.ui/event})
(def ^:private ui-render-fn-fqns #{'re-frame.ui/render-fn})
(def ^:private ui-slot-fqns   #{'re-frame.ui/slot})
(def ^:private sub-fqns       #{'re-frame.ui/sub})
(def ^:private lease-fqns     #{'re-frame.ui/lease})
(def ^:private frame-fqns     #{'re-frame.ui/frame})
(def ^:private frame-root-fqns #{'re-frame.ui/frame-root})
(def ^:private frame-provider-fqns #{'re-frame.ui/frame-provider})

(def markup-map-fqns
  "The map family — heads whose (f render-fn coll) idiom generates markup
  rows lazily. Rejected in child position (:rf.ui.compile/markup-returning-map)
  with the keyed-(for ...) escape. CLOSED vocabulary: additions are S1e
  roster changes."
  #{'clojure.core/map          'cljs.core/map
    'clojure.core/map-indexed  'cljs.core/map-indexed
    'clojure.core/mapcat       'cljs.core/mapcat
    'clojure.core/keep         'cljs.core/keep
    'clojure.core/keep-indexed 'cljs.core/keep-indexed})

(def lazy-seq-fqns
  "Core seq producers whose value in child position is a RAW SEQ — the
  grammar rejects raw lazy seqs (Spec 004 rewrite §Template grammar:
  they hide keys and laziness). Rejected in child position
  (:rf.ui.compile/lazy-seq-child). CLOSED vocabulary: additions are S1e
  roster changes. Note: these heads stay legal everywhere expressions
  are opaque — prop values, for-collections, if-tests — only RENDERED
  CONTENT positions reject them."
  (into #{}
        (mapcat (fn [s] [(symbol "clojure.core" (name s))
                         (symbol "cljs.core" (name s))]))
        '[filter remove take take-while take-nth take-last drop drop-while
          drop-last concat interpose interleave sequence repeat repeatedly
          iterate cycle range distinct dedupe flatten partition
          partition-all partition-by sort sort-by reverse rest next butlast
          keys vals seq re-seq]))

(def ^:private control-heads
  #{'if 'if-not 'when 'when-not 'cond 'case 'let 'letfn 'do 'for})

(defn- fn-form? [f]
  (and (seq? f) (contains? #{'fn 'fn* 'clojure.core/fn 'cljs.core/fn} (first f))))

(defn- raw-form? [e f]  (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-raw-fqns)))
(defn- html-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-html-fqns)))
(defn- raw-fn-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-raw-fn-fqns)))
(defn- spread-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-spread-fqns)))
(defn- spread-safe-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-spread-safe-fqns)))
(defn- ui-event-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-event-fqns)))
(defn- render-fn-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-render-fn-fqns)))
(defn- slot-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-slot-fqns)))

;; ---------------------------------------------------------------------------
;; Expression rewriting — lexical site indexing + loop finiteness
;; ---------------------------------------------------------------------------

(def ^:private runtime-sub-fqn 're-frame.ui.reactive/sub-read)
(def ^:private runtime-frame-ops-fqn 're-frame.ui.frames/frame-ops)
(def ^:private deferred-scope ::deferred-scope)
(def ^:private transparent-macro-fqns
  "Closed macro set whose arguments are host-independent expression slots,
  with no user-authored binders. Preserve their spelling while recursively
  lowering explicit sub/lease calls."
  (into #{}
        (mapcat (fn [s] [(symbol "clojure.core" (name s))
                         (symbol "cljs.core" (name s))]))
        '[or and when when-not cond -> ->> some-> some->> cond-> cond->>]))

(defn- deferred-expr-root?
  "Expression roots whose value is evaluated by a later host callback, not by
  the view's render capture. `sub`/`lease` below these roots would be
  phase-divergent between the JVM data host and React."
  [expr-root]
  (or (and (= :handler (first expr-root))
           (= :event-arg (nth expr-root 2 nil)))
      (some #{:raw-fn} expr-root)))

(defn runtime-sub-form?
  "True for the compiler's serialized internal lowering of a lexical sub.
  Public only within the compiler family (fingerprint/emitter tests use it)."
  [x]
  (and (seq? x) (= runtime-sub-fqn (first x)) (= 3 (count x))))

(defn template-fingerprint-projection
  "Remove compiler-owned lexical site ids from an analyzed AST while retaining
  the semantic sub query. This makes source-anchor/path movement irrelevant to
  template/build identity without making the query invisible."
  [ast]
  (letfn [(project [x]
            (cond
              (and (seq? x) (= 'quote (first x))) x

              (runtime-sub-form? x)
              (with-meta (list 're-frame.ui/sub (project (nth x 2))) (meta x))

              (map? x)
              (with-meta (into (empty x) (map (fn [[k v]] [(project k) (project v)])) x)
                         (meta x))

              (vector? x) (with-meta (mapv project x) (meta x))
              (set? x)    (with-meta (into (empty x) (map project) x) (meta x))
              (seq? x)    (with-meta (apply list (map project x)) (meta x))
              :else x))]
    (project ast)))

(defn- relative-source-anchor
  [e form]
  (let [m    (meta form)
        base (:source e)
        line (:line m)
        col  (:column m)]
    (if (and (integer? line) (integer? (:line base)))
      ;; Reader end coordinates are not host-common (and have changed across
      ;; reader versions). Start coordinates are the portable lexical anchor.
      [:relative (- line (:line base)) (or col 0)]
      ;; No reader anchor means no attempt to history-match sites. The whole
      ;; template anchor changes all such ids on an edit: safe reacquisition,
      ;; never ordinal transfer to a different lexical site.
      [:template (or (:template-anchor e)
                     (fingerprint/digest "sta1-" form))])))

(defn- lexical-site-id
  [e kind form expr-path]
  (fingerprint/digest
   "sid1-"
   [1 (:self-id e) kind (relative-source-anchor e form)
    (:path e) (vec expr-path)]))

(declare rewrite-expr)

(defn lease-declaration-form?
  "True when `form` is a direct, unshadowed call resolving to public
  `re-frame.ui/lease`. Only such forms may occupy defview's leading lease
  declaration prefix; helper calls and macro-produced calls are never granted
  an invisible ownership site."
  [e form]
  (and (seq? form)
       (symbol? (first form))
       (not (contains? (:locals e) (first form)))
       (env/resolves-to? e (first form) lease-fqns)))

(defn- compile-time-lease-descriptor!
  "Validate the statically knowable portion of one descriptor. Dynamic
  expressions defer to the host-neutral runtime validator; literal map keys
  and literal resource ids fail at compile time."
  [e descriptor]
  (cond
    (nil? descriptor) nil

    (map? descriptor)
    (let [allowed  #{:resource :scope :params}
          unknown  (seq (sort-by pr-str (remove allowed (keys descriptor))))
          resource (:resource descriptor)
          dynamic-resource? (or (symbol? resource) (seq? resource))]
      (when unknown
        (env/fail! e :rf.ui.compile/unsupported-form
                   (str "a lease descriptor contains unknown key"
                        (when (next unknown) "s") " " (pr-str (vec unknown))
                        " — the v1 map is closed to :resource, :scope, and :params")
                   {:form descriptor :unknown (vec unknown)}))
      (when-not (contains? descriptor :resource)
        (env/fail! e :rf.ui.compile/unsupported-form
                   "a lease descriptor requires :resource"
                   {:form descriptor}))
      (when (and (not dynamic-resource?)
                 (not (and (keyword? resource) (namespace resource))))
        (env/fail! e :rf.ui.compile/unsupported-form
                   (str "a lease descriptor's :resource must be a qualified "
                        "keyword; got " (pr-str resource))
                   {:form descriptor :resource resource})))

    ;; A symbol/call may evaluate to nil or a descriptor at render time.
    (or (symbol? descriptor) (seq? descriptor)) nil

    :else
    (env/fail! e :rf.ui.compile/unsupported-form
               (str "a lease descriptor must evaluate to nil or a closed map; "
                    "the literal " (pr-str descriptor) " cannot")
               {:form descriptor}))
  descriptor)

(defn analyze-lease-declaration
  "Analyze one direct leading `(lease descriptor)` declaration. Returns the
  compact emitted site record; also appends the tool-rich manifest row.
  Descriptor expression rewriting is evaluation-order/shadowing aware, so a
  finite nested `sub` receives its own independent sid while any nested lease
  is rejected by the ordinary expression rewriter."
  [e index form]
  (when-not (= 2 (count form))
    (env/fail! e :rf.ui.compile/unsupported-form
               "(lease descriptor) takes exactly one descriptor argument"
               {:form form}))
  (let [descriptor (compile-time-lease-descriptor! e (second form))
        expr-path  [:lease-declarations index]
        sid        (lexical-site-id e :lease form expr-path)
        descriptor* (rewrite-expr e (conj expr-path :descriptor) descriptor)]
    (env/add-site! e :leases {:sid sid
                              :descriptor descriptor
                              :path (:path e)
                              :expr-path expr-path})
    {:sid sid :descriptor descriptor*}))

(defn- map-path-token [x]
  (fingerprint/digest "ep1-" x))

(declare reactive-macro-reference-kind)

(defn- reactive-call-kind
  "Return :sub/:lease/:frame when `x` contains an unshadowed resolved
  reactive CALL. Quoted data is never executable. This scanner is
  deliberately about calls, not bare symbols, so a binding pattern may
  still introduce a local named `sub`, `lease`, or `frame`."
  [e x locals]
  (let [e* (update e :locals into locals)]
    (cond
      (and (seq? x) (= 'quote (first x))) nil
      (seq? x)
      (or (let [h (first x)]
            (when (and (symbol? h) (not (contains? locals h)))
              (let [info (env/resolve-sym e* h)]
                (cond
                  (contains? sub-fqns (:fqn info)) :sub
                  (contains? lease-fqns (:fqn info)) :lease
                  (contains? frame-fqns (:fqn info)) :frame
                  ;; Binding/default forms never pass through rewriting later.
                  ;; An unaudited macro can inject a reactive call even when its
                  ;; invocation carries no visible sub token, so reject it.
                  (and (true? (-> info :meta :macro))
                       (not (contains? transparent-macro-fqns (:fqn info))))
                  :opaque-macro
                  (true? (-> info :meta :macro))
                  (reactive-macro-reference-kind e (rest x) locals)))))
          (some #(reactive-call-kind e % locals) (rest x)))
      (map? x) (or (some #(reactive-call-kind e % locals) (keys x))
                   (some #(reactive-call-kind e % locals) (vals x)))
      (coll? x) (some #(reactive-call-kind e % locals) x)
      :else nil)))

(defn- reactive-macro-reference-kind
  "Like `reactive-call-kind`, plus unquoted bare references. Opaque macros may
  turn a bare `sub` argument into a call (`->` is the canonical example), so
  only macro expansion could prove such a reference harmless."
  [e x locals]
  (let [e* (update e :locals into locals)]
    (cond
      (and (seq? x) (= 'quote (first x))) nil
      (and (symbol? x) (not (contains? locals x)))
      (cond
        (env/resolves-to? e* x sub-fqns) :sub
        (env/resolves-to? e* x lease-fqns) :lease
        (env/resolves-to? e* x frame-fqns) :frame)
      (map? x) (or (some #(reactive-macro-reference-kind e % locals) (keys x))
                   (some #(reactive-macro-reference-kind e % locals) (vals x)))
      (coll? x) (some #(reactive-macro-reference-kind e % locals) x)
      :else nil)))

(defn- bare-reactive-reference-kind
  "Return :sub/:lease/:frame for an unquoted BARE reactive var reference. A
  direct `(sub ...)`/`(lease ...)`/`(frame)` head is not bare—the rewriter
  owns it—but a threading step such as `(-> query sub)` would become an
  unindexed call only after macro expansion and must fail loudly."
  [e x locals]
  (let [e* (update e :locals into locals)]
    (cond
      (and (seq? x) (= 'quote (first x))) nil
      (and (symbol? x) (not (contains? locals x)))
      (cond
        (env/resolves-to? e* x sub-fqns) :sub
        (env/resolves-to? e* x lease-fqns) :lease
        (env/resolves-to? e* x frame-fqns) :frame)
      (seq? x)
      (let [h (first x)
            direct? (and (symbol? h) (not (contains? locals h))
                         (or (env/resolves-to? e* h sub-fqns)
                             (env/resolves-to? e* h lease-fqns)
                             (env/resolves-to? e* h frame-fqns)))]
        (some #(bare-reactive-reference-kind e % locals)
              (if direct? (rest x) x)))
      (map? x) (or (some #(bare-reactive-reference-kind e % locals) (keys x))
                   (some #(bare-reactive-reference-kind e % locals) (vals x)))
      (coll? x) (some #(bare-reactive-reference-kind e % locals) x)
      :else nil)))

(defn- reactive-direct-form
  "The kind-correct compiler-owned direct form a reactive authoring verb must
  take in evaluated code — the ONLY shape in which the public var is legal, and
  what every escape diagnostic must recommend. `sub` reads a query, `lease`
  DECLARES a descriptor (the leading prefix, never a second-form read), `frame`
  takes no argument. Kept as one function so no diagnostic can drift into
  recommending an invalid form (e.g. a lease `query` second element)."
  [kind]
  (case kind
    :sub   "(sub query)"
    :lease "(lease descriptor)"
    :frame "(frame)"))

(defn- reactive-authoring-var-kind
  "Return :sub/:lease/:frame when `sym` is an unquoted, unshadowed reference to
  a public reactive authoring var. Such a var is sound ONLY as a compiler-owned
  DIRECT CALL HEAD — the rewriter consumes those heads (and lowers them to an
  indexed runtime site) before this leaf check runs — so a bare reference that
  reaches any other, value-flow, position has escaped the manifest. `env` must
  already carry the ambient lexical locals so a local shadow resolves to nil."
  [env sym]
  (let [{:keys [fqn]} (env/resolve-sym env sym)]
    (cond
      (contains? sub-fqns fqn)   :sub
      (contains? lease-fqns fqn) :lease
      (contains? frame-fqns fqn) :frame)))

(defn- key-group-kw?
  "True for a `:keys`/`:strs`/`:syms` map-destructuring group directive (any
  namespace: `:person/keys` too). Its map-entry VALUE is a vector of symbols;
  its keys are keyword/string/symbol LITERALS, never evaluated expressions."
  [k]
  (and (keyword? k) (contains? #{"keys" "strs" "syms"} (name k))))

(defn- key-group-directive-fn
  "The lookup-key transform host `destructure` applies to a `:keys`/`:strs`/
  `:syms` group directive `mk` (any namespace). This is the SHARED CLJ/CLJS
  transform — both hosts run `destructure` on the JVM at macro-expansion time —
  reproduced verbatim so the produced key (and, through it, the map's iteration
  order) matches the host exactly."
  [mk]
  (let [mkns (namespace mk)]
    (case (name mk)
      "keys" #(keyword (or mkns (namespace %)) (name %))
      "syms" #(list 'quote (symbol (or mkns (namespace %)) (name %)))
      "strs" str)))

(defn- assoc-binding-units
  "The associative-destructuring binding units of map `pattern`, in the EXACT
  host `destructure` `bes` order — the ONE canonical, host-faithful binding
  plan (`reject-reactive-binding!` scope checking and the CLJS header emitter
  both consume it, so neither can drift from the other or from the host).

  It reproduces `destructure`'s remaining-bindings transformation rather than
  invoking a general macroexpander: start from `(dissoc pattern :as :or)`, then
  for each group directive — in the order it appears in the pattern's keys —
  `dissoc` the directive and `assoc` each expanded local exactly as the host
  does, and read the units off `(seq bes)` in the resulting map-iteration order.
  Small patterns stay a `PersistentArrayMap` (insertion order); at nine or more
  remaining bindings the host promotes to a `PersistentHashMap` and the order
  becomes hash-driven. Because every host computes this on the JVM the 8→9
  threshold and the hash order are identical on CLJ and CLJS, so reproducing the
  transform here is faithful to both.

  `:as` is NOT included — it binds first, before `bes`, so the caller seeds it
  into scope. Each unit is `{:local-pattern p :key-expr e}`: for an explicit
  `{p key-expr}` entry `p` is the local pattern and `key-expr` its EVALUATED
  lookup-key expression; for a group local `p` is the simple symbol and the key
  is the produced literal (a keyword/string/quoted symbol — never a reactive
  escape). The units are consumed in order so each binding's default/lookup-key
  is judged against the scope established BEFORE it — never against a symbol the
  same pattern binds later, and never in an order the host would not use."
  [pattern]
  (let [start      (dissoc pattern :as :or)
        transforms (reduce (fn [t k] (if (key-group-kw? k) (assoc t k true) t))
                           {} (keys pattern))
        explicit   (set (keys (reduce dissoc start (keys transforms))))
        bes        (reduce
                    (fn [bes [mk _]]
                      (let [f (key-group-directive-fn mk)]
                        (reduce (fn [b s] (assoc b s (f s)))
                                (dissoc bes mk)
                                (get bes mk))))
                    start
                    transforms)]
    (for [[bb bk] bes]
      (if (contains? explicit bb)
        {:local-pattern bb :key-expr bk}
        {:local-pattern (symbol (name bb)) :key-expr nil}))))

(defn- check-portable-map-shape!
  "Reject the nonportable associative-destructuring shapes the host tolerates
  but that bind ambiguously across analysis and emission (or across CLJ/CLJS):
  a keyword or namespace-qualified EXPLICIT local, and a composite (non-simple-
  symbol) `:or` key. The host strips the namespace off a qualified local and
  binds a keyword local name-only, while the analyzer's scope walk keeps the
  written form — a divergence that can mis-lower a reactive read. A composite
  `:or` key never matches a bound local, so its default is silently dead. We
  close the portable grammar to simple-symbol (and nested) locals with the
  shared typed unsupported-form error rather than reproducing those shapes."
  [e pattern]
  (doseq [[k _] pattern]
    (cond
      (or (= k :as) (= k :or) (key-group-kw? k)) nil

      (keyword? k)
      (env/fail! e :rf.ui.compile/unsupported-form
                 (str "keyword destructuring local " k " is not portable — an "
                      "explicit binding local must be a simple symbol (or a "
                      "nested destructuring pattern). Bind {a-symbol " k "}")
                 {:form pattern :key k})

      (and (symbol? k) (namespace k))
      (env/fail! e :rf.ui.compile/unsupported-form
                 (str "namespace-qualified destructuring local " k " is not "
                      "portable — the host binds it name-only while analysis "
                      "keeps the namespace, so a reactive read can mis-lower. "
                      "Use the simple symbol " (symbol (name k)))
                 {:form pattern :key k})))
  (doseq [k (keys (:or pattern))]
    (when-not (and (symbol? k) (nil? (namespace k)))
      (env/fail! e :rf.ui.compile/unsupported-form
                 (str ":or default key " (pr-str k) " is not a simple symbol — "
                      "the host only defaults simple-symbol locals, so this "
                      "default is dead. Default a bound symbol")
                 {:form pattern :or-key k}))))

(defn- reject-binding-escape!
  "Reject a reactive authoring escape reaching ONE evaluated destructuring
  expression `expr`, judged against `scope` — the locals live at this
  expression's host evaluation point (ambient lexical locals PLUS the
  same-pattern bindings established before it). Both the executable-call scan
  and the bare-authoring-var scan use this one point-in-time scope, so an
  earlier-bound local shadows the authoring var while a later-bound or self
  binding does not. `slot` names the slot for the diagnostic."
  [e scope slot pattern expr]
  (when-let [kind (reactive-call-kind e expr scope)]
    (env/fail! e :rf.ui.compile/unsupported-form
               (if (= :opaque-macro kind)
                 (str "an unaudited macro cannot appear in a binding pattern "
                      "or " slot " — macro expansion could inject a reactive "
                      "call after lexical site analysis. Compute it in the view "
                      "body instead")
                 (str "(" (name kind) " ...) cannot appear in a binding pattern "
                      "or " slot " — that position cannot own a lexical render "
                      "site. Hoist the read into the view body and bind/"
                      "destructure its value there"))
               {:form pattern :reactive-kind kind}))
  (when-let [kind (bare-reactive-reference-kind e expr scope)]
    (env/fail! e :rf.ui.compile/unsupported-form
               (str "bare " (name kind) " reference as a " slot
                    " — re-frame.ui/" (name kind) " is a reactive authoring "
                    "var, sound ONLY as a compiler-owned direct call head "
                    (reactive-direct-form kind)
                    ". A " slot " is never rewritten, so the bare var flows as "
                    "a VALUE where the compiler cannot own a lexical render "
                    "site — the manifest under-declares and the optimized build "
                    "elides the read. Hoist the read into the view body and "
                    "destructure its committed value there")
               {:form pattern :reactive-kind kind})))

(defn- check-binding-scope!
  "Thread the incremental local scope through binding `pattern` in host
  evaluation order, rejecting a reactive authoring escape in every EVALUATED
  binding expression against the scope live at THAT point. Returns `scope`
  extended with every symbol `pattern` binds, so a caller can carry the roster
  across sibling patterns. `scope` already carries the ambient locals."
  [e scope pattern]
  (cond
    (symbol? pattern) (conj scope pattern)

    (vector? pattern)
    ;; Sequential destructuring `[p0 p1 … & prest :as asym]` carries no
    ;; evaluated expression of its own; its element/rest/`:as` sub-patterns
    ;; establish bindings left-to-right, so a later nested default sees an
    ;; earlier sibling's local.
    (loop [scope scope, xs (seq pattern)]
      (if (nil? xs)
        scope
        (let [x (first xs)]
          (cond
            (= '& x)  (recur scope (next xs))
            (= :as x) (recur (check-binding-scope! e scope (second xs)) (nnext xs))
            :else     (recur (check-binding-scope! e scope x) (next xs))))))

    (map? pattern)
    ;; Associative destructuring. Close the portable grammar first, then thread
    ;; scope in host `destructure` order. `:as` binds the whole map FIRST (host
    ;; order), so it is in scope for every key default. Then the key bindings are
    ;; established sequentially (`assoc-binding-units` = the host `bes` order):
    ;; each key's default AND each explicit lookup-key expression is evaluated
    ;; against the scope BEFORE that key's own local is bound — the local is
    ;; added only after, and never in an order the host would not use.
    (do
      (check-portable-map-shape! e pattern)
      (let [defaults (:or pattern)
            scope    (cond-> scope (:as pattern) (conj (:as pattern)))]
        (reduce
         (fn [scope {:keys [local-pattern key-expr]}]
           (when key-expr
             (reject-binding-escape! e scope "destructuring lookup-key expression"
                                     pattern key-expr))
           (when (and (symbol? local-pattern) (contains? defaults local-pattern))
             (reject-binding-escape! e scope "destructuring :or default"
                                     pattern (get defaults local-pattern)))
           (check-binding-scope! e scope local-pattern))
         scope
         (assoc-binding-units pattern))))

    :else scope))

(defn reject-reactive-binding!
  "Reject a reactive authoring escape reaching an EVALUATED destructuring
  expression — an executable `(sub …)`/`(lease …)`/`(frame)` call OR a bare
  reactive authoring var — anywhere in a binding pattern. Binding patterns are
  consumed by the host compiler, not expression rewriting, so such a position
  can never own a lexical render site: the manifest would under-declare and the
  optimized build elide the read.

  CLJ/CLJS destructuring binds SEQUENTIALLY, so scope is modelled in host
  evaluation order (`check-binding-scope!`): each `:or` default and each
  explicit map lookup-key expression is judged against the locals live at ITS
  binding point — ambient lexical locals plus the same-pattern bindings
  established BEFORE it. A bare var reaching a default from OUTSIDE those locals
  (a later-bound or self shadow — `{:keys [f sub] :or {f sub}}`,
  `{:keys [sub] :or {sub sub}}`) still resolves to the public authoring var and
  escapes; a genuine EARLIER-bound local shadows it and is legal
  (`{:keys [sub a] :or {a sub}}`, `{:keys [sub f] :or {f (sub :fallback)}}`).
  The call scan and the bare-reference scan share this one point-in-time scope,
  so neither over-shadows (whole-pattern) nor under-shadows (ambient-only)."
  [e binding-form]
  (check-binding-scope! e (:locals e) binding-form)
  binding-form)

(defn header-binding-order
  "The explicit/group local-patterns of a defview header map `binding-form`, in
  host `destructure` `bes` order (`:as` excluded — it binds first). The CLJS
  header emitter consumes this so its property-read bindings land in the same
  order the JVM native destructuring would use; a dependent `:or` default then
  resolves to the same symbol on both hosts and no public authoring var can
  survive through a reordered default. One plan (`assoc-binding-units`), both
  emitters. Returns nil for a non-map header (`[sym]` ≡ `{:as sym}`)."
  [binding-form]
  (when (map? binding-form)
    (mapv :local-pattern (assoc-binding-units binding-form))))

(defn- impure-slot-fail!
  "The didactic rejection for a reactive read inside a `ui/render-fn` slot
  body — a slot body is a pure render fragment; the reactive/dispatch surface
  belongs to the owning view or a mounted defview."
  [e verb form]
  (env/fail! e :rf.ui.compile/impure-slot-body
             (str "(" verb " …) inside a ui/render-fn slot body — a slot body is "
                  "a PURE render fragment; sub / lease / frame (and dispatch / "
                  "hooks) are not permitted. Read the value in the OWNING view and "
                  "pass its committed value into the slot's arguments; a stateful "
                  "part MOUNTS a defview that owns its own state")
             {:form form}))

(defn rewrite-expr
  "Rewrite an opaque expression, lowering resolved unshadowed `(sub q)` calls
  to the serialized two-argument runtime shape and recording stable lexical
  sub/lease ids. The returned form MUST be threaded into the AST.

  `expr-root` names the containing AST slot; recursive paths then distinguish
  every nested expression without a fragile global/preorder ordinal. Binding
  scopes follow evaluation order for fn/let/loop/letfn and all metadata and
  collection kinds are preserved."
  ([e form] (rewrite-expr e [:expr] form))
  ([e expr-root form]
   (letfn [(with-same-meta [old x]
              (with-meta x (meta old)))
           (rw-fn-arity [arity locals p]
             (let [[argv & body] arity
                   _ (reject-reactive-binding! (update e :locals into locals) argv)
                   body-locals (-> locals
                                   (into (env/binding-syms argv))
                                   (conj deferred-scope))]
               (with-same-meta
                 arity
                 (apply list argv
                        (map-indexed #(rw %2 body-locals (conj p :body %1)) body)))))
           (rw-fn [f locals p]
             (let [head (first f)
                   tail (rest f)
                   named? (symbol? (first tail))
                   fname (when named? (first tail))
                   tail (if named? (rest tail) tail)
                   locals* (cond-> locals fname (conj fname))
                   rewritten
                   (if (vector? (first tail))
                     (let [[argv & body] tail
                            _ (reject-reactive-binding! (update e :locals into locals*) argv)
                            body-locals (-> locals*
                                            (into (env/binding-syms argv))
                                            (conj deferred-scope))]
                       (concat [head] (when fname [fname]) [argv]
                               (map-indexed #(rw %2 body-locals (conj p :body %1)) body)))
                     (concat [head] (when fname [fname])
                             (map-indexed #(rw-fn-arity %2 locals* (conj p :arity %1))
                                          tail)))]
               (with-same-meta f (apply list rewritten))))
           (rw-let [f locals p]
             (let [head (first f)
                   bindings (second f)]
               (if-not (vector? bindings)
                 (with-same-meta f
                   (apply list (map-indexed #(rw %2 locals (conj p %1)) f)))
                 (let [[bindings* locals*]
                       (loop [pairs (partition 2 bindings)
                              i 0
                              out []
                              scope locals]
                         (if-let [[pat init] (first pairs)]
                           (do
                             (reject-reactive-binding! (update e :locals into scope) pat)
                             (recur (rest pairs) (inc i)
                                  (conj out pat (rw init scope (conj p :binding i)))
                                  (into scope (env/binding-syms pat))))
                           [(with-same-meta bindings (vec out)) scope]))]
                   (with-same-meta
                     f
                     (apply list head bindings*
                             (map-indexed #(rw %2 (cond-> locals*
                                                   (contains? #{'loop 'loop*} head)
                                                   (conj deferred-scope))
                                               (conj p :body %1))
                                          (drop 2 f))))))))
           (rw-letfn [f locals p]
             (let [specs (second f)]
               (if-not (vector? specs)
                 (with-same-meta f
                   (apply list (map-indexed #(rw %2 locals (conj p %1)) f)))
                 (let [names (into #{} (keep #(when (seq? %) (first %))) specs)
                       scope (into locals names)
                       specs* (with-same-meta
                                specs
                                (mapv (fn [i spec]
                                        (if (and (seq? spec) (symbol? (first spec)))
                                          (let [[name & arities] spec
                                                fake (with-meta (apply list 'fn name arities)
                                                                (meta spec))
                                                [_ _ & rewritten] (rw-fn fake scope
                                                                          (conj p :spec i))]
                                            (with-same-meta spec (apply list name rewritten)))
                                          (rw spec scope (conj p :spec i))))
                                      (range) specs))]
                   (with-same-meta
                     f
                     (apply list (first f) specs*
                            (map-indexed #(rw %2 scope (conj p :body %1))
                                         (drop 2 f))))))))
           (rw-letfn* [f locals p]
             ;; The HOST special form `letfn*` (what the `letfn` macro expands
             ;; to) has FLAT name/initializer bindings `[n0 init0 n1 init1 …]`,
             ;; NOT source `letfn`'s paired fnspec-LIST grammar. Its lexical
             ;; grammar: every name is in scope for every initializer AND the
             ;; body (mutual recursion), and each initializer is a `fn*` whose
             ;; body is deferred — so routing an initializer through `rw`
             ;; (→ `rw-fn`) makes a render-time `sub`/`lease` inside it illegal,
             ;; while a visible `sub` in the OUTER body still lowers to a site.
             (let [bindings (second f)]
               (if-not (and (vector? bindings) (even? (count bindings)))
                 (env/fail! e :rf.ui.compile/bad-let
                            (str "letfn* needs a vector of an even number of flat "
                                 "name/initializer bindings [name init …]")
                            {:form f})
                 (let [pairs (partition 2 bindings)
                       names (map first pairs)]
                   (when-not (every? symbol? names)
                     (env/fail! e :rf.ui.compile/bad-let
                                "letfn* binding names must be symbols"
                                {:form f}))
                   (let [scope     (into locals names)
                         bindings* (with-same-meta
                                     bindings
                                     (into []
                                           (comp (map-indexed
                                                  (fn [i [nm init]]
                                                    [nm (rw init scope (conj p :binding i))]))
                                                 cat)
                                           pairs))]
                     (with-same-meta
                       f
                       (apply list (first f) bindings*
                              (map-indexed #(rw %2 scope (conj p :body %1))
                                           (drop 2 f)))))))))
           (rw-try [f locals p]
             ;; `catch` introduces a local, so it cannot go through generic
             ;; traversal. `finally` does not. Preserve the marker/type slots
             ;; verbatim and rewrite only evaluated bodies.
             (with-same-meta
               f
               (apply list
                      (first f)
                      (map-indexed
                       (fn [i clause]
                         (cond
                           (and (seq? clause) (= 'catch (first clause))
                                (symbol? (nth clause 2 nil)))
                           (let [[marker type binding & body] clause]
                             (with-same-meta
                               clause
                               (apply list marker type binding
                                      (map-indexed
                                       #(rw %2 (conj locals binding)
                                            (conj p :catch i :body %1))
                                       body))))

                           (and (seq? clause) (= 'finally (first clause)))
                           (with-same-meta
                             clause
                             (apply list 'finally
                                    (map-indexed
                                     #(rw %2 locals (conj p :finally :body %1))
                                     (rest clause))))

                           :else (rw clause locals (conj p :body i))))
                       (rest f)))))
           (rw-map [m locals p]
             (with-same-meta
               m
               (reduce-kv (fn [out k v]
                            (let [t (map-path-token k)]
                              (assoc out
                                     (rw k locals (conj p :map-key t))
                                     (rw v locals (conj p :map-value t)))))
                          (empty m)
                          m)))
           (macro-info [e* head locals]
             (when (and (symbol? head) (not (contains? locals head)))
               (let [info (env/resolve-sym e* head)]
                 (when (true? (-> info :meta :macro)) info))))
           (rw [f locals p]
             (cond
               (and (seq? f) (= 'quote (first f))) f

               (seq? f)
               (let [head (first f)
                     e*   (update e :locals into locals)]
                 (cond
                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head ui-render-fn-fqns))
                    (env/fail! e :rf.ui.compile/render-fn-misplaced
                               (str "(ui/render-fn …) is a render-slot callback "
                                    "value — legal ONLY as a component call-site "
                                    "prop value or a ui/slot argument, never as a "
                                    "plain expression. The library invokes it "
                                    "through ui/slot")
                               {:form f})

                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head ui-slot-fqns))
                    (env/fail! e :rf.ui.compile/bad-slot
                               (str "(ui/slot render-fn-value arg…) renders content "
                                    "— it is legal only in a child position, not as "
                                    "a plain expression. Put it where a child goes")
                               {:form f})

                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head sub-fqns))
                    (do
                      (when-not (= 2 (count f))
                        (env/fail! e :rf.ui.compile/unsupported-form
                                   "(sub query) takes exactly one query argument"
                                   {:form f}))
                      (when (or (nil? (:self-id e))
                                (:in-loop? e)
                                (contains? locals deferred-scope))
                        (if (:in-render-fn? e)
                          (impure-slot-fail! e "sub" f)
                          (env/fail! e :rf.ui.compile/sub-in-loop
                                     (str "(sub ...) must be a finite render-time site in a "
                                          "defview — it cannot run in a loop, deferred "
                                          "callback, raw-fn/ref body, or root expression. "
                                          "Hoist the read into the view body and let the "
                                          "callback capture its committed value; for rows, "
                                          "extract a keyed child view")
                                     {:form f})))
                     (let [sid   (lexical-site-id e :sub f p)
                           query (rw (second f) locals (conj p :query))]
                       (env/add-site! e :subs {:sid sid :query (second f)
                                               :path (:path e) :expr-path (vec p)})
                       (with-same-meta f (list runtime-sub-fqn sid query))))

                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head lease-fqns))
                    (do
                      (when-not (= 2 (count f))
                        (env/fail! e :rf.ui.compile/unsupported-form
                                   "(lease descriptor) takes exactly one descriptor argument"
                                   {:form f}))
                      (if (or (nil? (:self-id e))
                              (:in-loop? e)
                              (contains? locals deferred-scope))
                        (if (:in-render-fn? e)
                          (impure-slot-fail! e "lease" f)
                          (env/fail! e :rf.ui.compile/lease-in-loop
                                     (str "(lease ...) must be a finite render-time site in "
                                          "a defview — it cannot run in a loop, deferred "
                                          "callback, raw-fn/ref body, or root expression. "
                                          "Move it into the leading lease declaration prefix; for rows, "
                                          "extract a keyed child view")
                                     {:form f}))
                        (env/fail! e :rf.ui.compile/unsupported-form
                                   (str "(lease ...) is a declaration, not an expression — "
                                        "put direct lease forms before the view's one final "
                                        "template; conditional liveness is (lease (when p descriptor))")
                                   {:form f})))

                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head frame-fqns))
                    (do
                      (when-not (= 1 (count f))
                        (env/fail! e :rf.ui.compile/unsupported-form
                                   (str "(frame) takes no arguments — it returns the "
                                        "operation bundle locked to the committed frame; "
                                        "to target a different frame, scope the subtree "
                                        "with [frame-provider {:frame f} ...]")
                                   {:form f}))
                      (when (or (nil? (:self-id e))
                                (:in-loop? e)
                                (contains? locals deferred-scope))
                        (if (:in-render-fn? e)
                          (impure-slot-fail! e "frame" f)
                          (env/fail! e :rf.ui.compile/frame-in-loop
                                     (str "(frame) must be a finite render-time site in a "
                                          "defview — it cannot run in a loop, deferred "
                                          "callback, raw-fn/ref body, or root expression. "
                                          "Hoist the read into the view body and let the "
                                          "callback capture its committed ops bundle")
                                     {:form f})))
                      (let [sid (lexical-site-id e :frame f p)]
                        (env/add-site! e :frame-ops {:sid sid
                                                     :path (:path e)
                                                     :expr-path (vec p)})
                        (with-same-meta f (list runtime-frame-ops-fqn))))

                   (fn-form? f) (rw-fn f locals p)
                   (contains? #{'let 'let* 'loop 'loop*} head) (rw-let f locals p)
                   (= 'letfn head)  (rw-letfn f locals p)
                   (= 'letfn* head) (rw-letfn* f locals p)
                   (= 'try head) (rw-try f locals p)

                   (and (macro-info e* head locals)
                        (contains? transparent-macro-fqns
                                   (:fqn (macro-info e* head locals))))
                   (if-let [kind (bare-reactive-reference-kind e (rest f) locals)]
                     (env/fail! e :rf.ui.compile/unsupported-form
                                (str "bare " (name kind) " reference below macro "
                                     head " would become an unindexed reactive "
                                     "call after expansion. Write the explicit "
                                     (reactive-direct-form kind) " call")
                                {:form f :macro head})
                     (with-same-meta
                       f
                       (apply list head
                              (map-indexed #(rw %2 locals (conj p (inc %1)))
                                           (rest f)))))

                   ;; A DEFERRED callback body (fn / ui/event / raw-fn / event-arg)
                   ;; hosts no render-time reactive site — sub/lease/frame are
                   ;; already illegal here (rejected above) — so there is nothing
                   ;; for lexical site analysis to protect. Opaque host macros
                   ;; (`..`, `doto`, `case`, `if-let`, …) are ordinary callback
                   ;; code: pass the form through verbatim. A reactive call
                   ;; smuggled inside one stays illegal in deferred scope and fails
                   ;; loud at its resolution-only var when the callback runs.
                   (and (macro-info e* head locals)
                        (contains? locals deferred-scope))
                   f

                   (macro-info e* head locals)
                   (env/fail! e :rf.ui.compile/unsupported-form
                              (str "macro " head " is outside the compiler's "
                                   "audited expression set and could inject, "
                                   "duplicate, or defer a reactive call after "
                                   "lexical site analysis. Rewrite it with "
                                   "ordinary functions/control forms, or hoist "
                                   "the computation around the view template")
                              {:form f :macro head})

                   :else
                   ;; A generic invocation. Clojure evaluates the CALLEE before
                   ;; its arguments, so the callee position is an ordinary
                   ;; evaluated expression that can host a reactive site:
                   ;;
                   ;;   ((if (sub [:op]) inc dec) 1)   ; sub in a computed callee
                   ;;
                   ;; A simple symbol/keyword head is not itself an evaluated
                   ;; expression (a plain fn/special-form name, a keyword lookup
                   ;; op) and cannot contain a nested site — preserve it verbatim.
                   ;; A COMPUTED callee (seq/vector/map/set) IS evaluated, so
                   ;; rewrite it under a stable `:callee` token — BEFORE the
                   ;; arguments, matching evaluation order — so a visible `(sub …)`
                   ;; there lowers to its indexed site (or an immediately-invoked
                   ;; fn / opaque-macro callee is rejected didactically by the
                   ;; binder/deferred/macro rules `rw` already applies). It must
                   ;; never survive as a public `ui/sub` with an empty manifest.
                   (with-same-meta
                     f
                     (apply list
                            (if (or (symbol? head) (keyword? head))
                              head
                              (rw head locals (conj p :callee)))
                            (map-indexed #(rw %2 locals (conj p (inc %1)))
                                         (rest f))))))

               (map? f) (rw-map f locals p)
               (vector? f)
               (with-same-meta f (mapv #(rw %2 locals (conj p %1)) (range) f))
               (set? f)
               (with-same-meta
                 f
                 (into (empty f)
                       (map #(rw % locals (conj p :set (map-path-token %))))
                       f))

               ;; A BARE reactive authoring var reaching this leaf is a
               ;; value-flow escape. Every SOUND reactive site is consumed above
               ;; as a direct call head (`(sub q)`/`(lease d)`/`(frame)` → an
               ;; indexed runtime site) or rejected pre-expansion under a macro.
               ;; A bare `sub`/`lease`/`frame` symbol here instead flows as a
               ;; VALUE — into a computed callee `((if p sub inc) q)`, a let
               ;; alias `(let [f sub] (f q))`, an argument, or a collection —
               ;; where the analyzer cannot own a lexical render site, so the
               ;; manifest under-declares and the optimized build elides the
               ;; ViewCell, leaving the read to go stale / escape ownership.
               ;; Reject it loudly. Lexical shadows resolve to nil here and pass
               ;; through; quoted data was returned by the `quote` branch above.
               :else
               (if-let [kind (and (symbol? f) (not (contains? locals f))
                                  (reactive-authoring-var-kind
                                   (update e :locals into locals) f))]
                 (env/fail! e :rf.ui.compile/unsupported-form
                            (str "bare " (name kind) " reference — re-frame.ui/"
                                 (name kind) " is a reactive authoring var, sound "
                                 "ONLY as a compiler-owned direct call head "
                                 (reactive-direct-form kind)
                                 ". Here it flows as a VALUE (into a computed "
                                 "callee, let-binding, argument, or collection) "
                                 "where the compiler cannot own a lexical render "
                                 "site, so the manifest would under-declare and "
                                 "the optimized build would elide the read. Keep "
                                 "the read at a visible " (name kind)
                                 " call site, or make the boundary its own defview")
                            {:form f :reactive-kind kind})
                 f)))]
     ;; A `ui/render-fn` slot body is a DEFERRED render: it executes inside a
     ;; DIFFERENT view (the library seam that invokes the slot), so every
     ;; expression it walks is deferred — sub/lease/frame there would be
     ;; phase-divergent and owner-wrong, exactly as in a fn/ui-event callback.
     (rw form (cond-> #{}
                (or (deferred-expr-root? expr-root) (:in-render-fn? e))
                (conj deferred-scope))
         (vec expr-root)))))

;; Transitional internal name used throughout the analyzer. Unlike its former
;; side-effect-only implementation it returns the rewritten expression.
(def walk-expr rewrite-expr)

;; ---------------------------------------------------------------------------
;; Tag sugar — :div.cls#id / :div#id.cls (both orders)
;; ---------------------------------------------------------------------------

(defn parse-tag
  "Parse a keyword element head into {:tag kw :id str|nil :classes [str]}.
  `.class`/`#id` segments compose in any order; two `#id`s are an error."
  [e head]
  (let [s (name head)]
    (when (namespace head)
      (env/fail! e :rf.ui.compile/bad-tag
                 (str "element head " head " must be an unqualified keyword — "
                      "tag keywords carry no namespace (write :" s ")")
                 {:head head}))
    (when (or (str/blank? s) (str/starts-with? s ".") (str/starts-with? s "#"))
      (env/fail! e :rf.ui.compile/bad-tag
                 (str "element head " head " needs a tag name before sugar "
                      "(e.g. :div.card, :div#main)")
                 {:head head}))
    (let [segs (re-seq #"[.#][^.#]+|^[^.#]+" s)
          tag  (first segs)
          sugar (rest segs)]
      (reduce (fn [acc seg]
                (cond
                  (str/starts-with? seg "#")
                  (if (:id acc)
                    (env/fail! e :rf.ui.compile/duplicate-id-sugar
                               (str "two #id segments on " head " — an element "
                                    "has one id. Keep one #id segment (or move "
                                    "the id to an :id prop)")
                               {:head head})
                    (assoc acc :id (subs seg 1)))
                  (str/starts-with? seg ".")
                  (update acc :classes conj (subs seg 1))
                  :else acc))
              {:tag (keyword tag) :id nil :classes []}
              sugar))))

;; ---------------------------------------------------------------------------
;; Handlers (DOM/custom-element :on-* positions)
;; ---------------------------------------------------------------------------

(defn- check-loop-capture! [e kind form]
  (when (:in-loop? e)
    (let [captured (env/captured-loop-syms e form)]
      (when (seq captured)
        (env/fail! e :rf.ui.compile/loop-capturing-handler
                   (str kind " captures loop binding" (when (next captured) "s")
                        " " (str/join ", " (sort (map str captured)))
                        " — per-row committed slots need per-row instances. "
                        "Extract a keyed child view and pass "
                        (first (sort (map str captured))) " as a prop")
                   {:form form :captured captured})))))

(defn- check-placeholders! [e vec-form]
  ;; top-level placeholders splice; NESTED placeholder keywords are almost
  ;; certainly a bug -> warning (they dispatch as ordinary keywords)
  (doseq [[i x] (map-indexed vector vec-form)]
    (when (and (coll? x) (seq (filter rules/placeholders (env/kws-in-form x))))
      (env/warn! e {:id :rf.ui.compile/placeholder-not-top-level
                    :msg (str "placeholder keyword nested inside event-vector "
                              "argument " i " — placeholders splice only at "
                              "top-level positions; nested ones dispatch as "
                              "ordinary keywords")
                    :form vec-form}))))

(defn- event-source-coord
  "Best available authored coordinate for an event site. Reader metadata on
  the handler wins; macro-generated forms fall back to the defview anchor.
  The template path remains a separate, deterministic occurrence coordinate."
  [e form]
  (let [m    (meta form)
        base (:source e)]
    (cond-> (select-keys base [:file :line :column])
      (:line m)   (assoc :line (:line m))
      (:column m) (assoc :column (:column m)))))

(defn- event-site-identity
  [e k form]
  {:sid          (lexical-site-id e :event form [:handler k])
   :site-index   (count (:events @(:sites e)))
   :view-id      (:self-id e)
   :source-coord (event-source-coord e form)
   :path         (:path e)})

(defn- add-event-site!
  [e identity site]
  (env/add-site! e :events (merge identity site))
  identity)

(defn- controlled-event-sync?
  "The deliberately narrow controlled-input sync door.  It is a property of
  the whole native element, not of the handler in isolation, so it is applied
  after every prop has been classified.  The SITE proof is static: a literal
  vector handler, or a `ui/event` handler whose runtime result the invocation
  classifies as an event vector.  Both ride the one synchronous drain; the
  prefix/payload stay runtime values."
  [controlled? handler]
  (boolean
   (and controlled?
        (contains? #{"on-input" "on-change" "on-before-input"}
                   (:name handler))
        (contains? #{:vector :ui-event} (:classification handler)))))

(defn- record-event-sync!
  [e sid sync?]
  (swap! (:sites e)
         update :events
         (fn [sites]
           (mapv #(if (= sid (:sid %)) (assoc % :sync? sync?) %) sites))))

(defn- analyze-event-vector! [e k form]
  (when-not (keyword? (first form))
    (env/fail! e :rf.ui.compile/bad-event-vector
               (str k " event vector must start with a literal event-id "
                    "keyword: [:domain/event args...]")
               {:prop k :form form}))
  (check-loop-capture! e (str "event vector " (pr-str form) " at " k) form)
  (check-placeholders! e form)
  (with-meta
    (into [(first form)]
          (map-indexed (fn [i x]
                         (walk-expr e [:handler k :event-arg i] x)))
          (rest form))
    (meta form)))

(defn analyze-handler
  "Classify one :on-* entry. -> {:k kw :name str :classification
  :vector|:options|:fn|:dynamic :form form :capture? bool
  :hoistable? bool :serializable? bool}"
  [e k form]
  (when (:in-render-fn? e)
    (env/fail! e :rf.ui.compile/impure-slot-body
               (str "an event handler (" k ") inside a ui/render-fn slot body — a "
                    "slot body is a PURE render fragment; a committed handler "
                    "DISPATCHES, which a slot body may not. Own the interactivity "
                    "in the library view, or mount a defview that owns its handlers "
                    "(a stateful part is a pure slot body mounting a defview)")
               {:prop k :form form}))
  (let [nm       (name k)
        identity (event-site-identity e k form)]
    (cond
      (vector? form)
      (let [form* (analyze-event-vector! e k form)]
        (add-event-site! e identity
                         {:prop k :handler form*
                          :classification :vector :serializable? true})
        (merge identity
               {:k k :name nm :classification :vector :form form* :capture? false
                :hoistable? (every? #(or (literal-scalar? %)
                                         (contains? rules/placeholders %))
                                    form*)
                :serializable? true}))

      (map? form)
      (let [unknown (remove rules/handler-option-keys (keys form))]
        (when (seq unknown)
          (env/fail! e :rf.ui.compile/bad-handler-options
                     (str "unknown handler option" (when (next unknown) "s") " "
                          (str/join ", " (map pr-str unknown)) " at " k
                          " — the closed listener vocabulary is "
                          "{:event :prevent-default :stop-propagation :capture "
                          ":passive :once}")
                     {:prop k :form form}))
        (when (and (:passive form) (:prevent-default form))
          (env/fail! e :rf.ui.compile/contradictory-handler-options
                     (str ":passive true with :prevent-default true at " k
                          " — a passive listener promises the browser it will "
                          "NEVER call preventDefault, so the combination is a "
                          "contradiction (in any stage). Drop one of them")
                     {:prop k :form form}))
        (when-not (vector? (:event form))
          (env/fail! e :rf.ui.compile/bad-handler-options
                     (str "handler options map at " k " needs a literal "
                          ":event vector — {:event [:domain/event args...] "
                          ":prevent-default true ...}")
                     {:prop k :form form}))
        (let [event* (analyze-event-vector! e k (:event form))
              form*  (assoc form :event event*)]
          (add-event-site! e identity
                           {:prop k :handler form*
                            :classification :options :serializable? true})
          (merge identity
                 {:k k :name nm :classification :options :form form*
                  :capture? (boolean (:capture form))
                  :passive? (boolean (:passive form))
                  :hoistable? (every? #(or (literal-scalar? %)
                                           (contains? rules/placeholders %))
                                      event*)
                  :serializable? true})))

      (ui-event-form? e form)
      (let [[_ bindings & body] form]
        (when-not (and (vector? bindings) (= 1 (count bindings)))
          (env/fail! e :rf.ui.compile/bad-ui-event
                     (str "(ui/event [e] body…) at " k " binds exactly the "
                          "native event and returns an event vector (or nil to "
                          "dispatch nothing); got " (pr-str bindings)
                          ". For imperative work with no dispatch, S3 adds "
                          "ui/handler")
                     {:prop k :form form}))
        ;; A ui/event handler is a SITE, like a literal vector: capturing a loop
        ;; binding needs per-row committed slots (its own bindings shadow, so they
        ;; are excluded from the capture check). Its body is a deferred callback,
        ;; so render-time sub/lease/frame inside it are rejected by the fn walk.
        (let [binders (set (env/binding-syms bindings))
              form*   (walk-expr e [:handler k :ui-event]
                                 (with-meta (apply list 'fn bindings body)
                                   (meta form)))]
          (check-loop-capture! (update e :loop-syms #(reduce disj % binders))
                               (str "ui/event handler at " k) form)
          (add-event-site! e identity
                           {:prop k :handler :opaque
                            :classification :ui-event :serializable? false})
          (merge identity
                 {:k k :name nm :classification :ui-event :form form*
                  :capture? false :hoistable? false :serializable? false})))

      (fn-form? form)
      (do (when (:in-loop? e)
            (env/warn! e {:id :rf.ui.compile/bare-fn-in-loop
                          :msg (str "bare fn handler at " k " inside a loop — "
                                    "works, at per-row closure cost, and defeats "
                                    "the data idiom; prefer an event vector or a "
                                    "keyed child view")
                          :form form}))
          (let [form* (walk-expr e [:handler k :fn] form)]
            (add-event-site! e identity
                             {:prop k :handler :opaque
                              :classification :fn :serializable? false})
            (merge identity
                   {:k k :name nm :classification :fn :form form* :capture? false
                    :hoistable? false :serializable? false})))

      :else
      (let [form* (walk-expr e [:handler k :dynamic] form)]
        (check-loop-capture! e (str "dynamic handler at " k) form)
        (add-event-site! e identity
                         {:prop k :handler :opaque
                          :classification :dynamic :serializable? false})
        (merge identity
               {:k k :name nm :classification :dynamic :form form* :capture? false
                :hoistable? false :serializable? false})))))

;; ---------------------------------------------------------------------------
;; :class / :style
;; ---------------------------------------------------------------------------

(defn analyze-class
  "-> {:base-str str :flags [[name expr]...] :dyn form|nil :static? bool}
  Sugar classes first (source order), then the explicit :class form;
  flag-map entries in lexicographic name order; no de-duplication."
  [e sugar form]
  (let [base (vec sugar)]
    (cond
      (nil? form)
      {:base-str (str/join " " base) :flags [] :dyn nil
       :static? true}

      (or (string? form) (keyword? form))
      {:base-str (str/join " " (conj base (if (keyword? form) (name form) form)))
       :flags [] :dyn nil :static? true}

      (and (vector? form) (every? #(or (string? %) (keyword? %) (nil? %)) form))
      {:base-str (str/join " " (into base (keep #(when % (if (keyword? %) (name %) %))) form))
       :flags [] :dyn nil :static? true}

      (map? form)
      (do
        (when-not (every? #(or (keyword? %) (string? %)) (keys form))
          (env/fail! e :rf.ui.compile/bad-class
                     ":class flag-map keys must be literal names (string/keyword)"
                     {:form form}))
        (let [{consts true exprs false}
              (group-by (fn [[_ v]] (literal-scalar? v)) form)
              const-names (into base
                                (->> consts
                                     (keep (fn [[k v]] (when v (if (keyword? k) (name k) k))))
                                     sort))
               flags (->> exprs
                          (map (fn [[k v]]
                                 [(if (keyword? k) (name k) k)
                                  (walk-expr e [:class :flag k] v)]))
                          (sort-by first)
                          vec)]
          {:base-str (str/join " " const-names) :flags flags :dyn nil
           :static? (empty? flags)}))

      (vector? form) ; mixed literal/expr vector — runtime join in vector order
      (let [form* (with-meta
                    (mapv (fn [i x]
                            (if (literal-scalar? x)
                              x
                              (walk-expr e [:class :vector i] x)))
                          (range) form)
                    (meta form))]
        {:base-str (str/join " " base) :flags [] :dyn form* :static? false})

      :else
      {:base-str (str/join " " base) :flags []
       :dyn (walk-expr e [:class :dynamic] form) :static? false})))

(defn analyze-style
  "-> {:entries [{:css-name str :value form :literal? bool}] :static? bool}
  or {:dyn form} for a wholly-dynamic :style expression."
  [e form]
  (cond
    (map? form)
    (let [entries (mapv (fn [[k v]]
                          (when-not (keyword? k)
                            (env/fail! e :rf.ui.compile/bad-style
                                       (str ":style keys must be literal "
                                            "keywords — for computed names, "
                                            "pass the whole :style value as "
                                            "one dynamic expression")
                                       {:key k :form form}))
                           {:css-name (name k)
                            :value (if (literal-scalar? v)
                                     v
                                     (walk-expr e [:style k] v))
                            :literal? (literal-scalar? v)})
                         form)]
      {:entries entries :static? (every? :literal? entries)})

    :else
    {:dyn (walk-expr e [:style :dynamic] form) :static? false}))

;; ---------------------------------------------------------------------------
;; Element props
;; ---------------------------------------------------------------------------

(defn- check-rejected-spelling! [e k]
  (when-let [replacement (get rules/rejected-prop-spellings k)]
    (env/fail! e :rf.ui.compile/rejected-prop-spelling
               (str k " is not a prop — one spelling per name, ambiguities "
                    "removed. Use " replacement)
               {:prop k})))

(defn- analyze-ref [e form context]
  (when (:in-render-fn? e)
    (env/fail! e :rf.ui.compile/impure-slot-body
               (str ":ref inside a ui/render-fn slot body — a slot body is a PURE "
                    "render fragment; a ref is a commit-phase host hook, which a "
                    "slot body may not own. Mount a defview that owns the ref")
               {:form form}))
  (case context
    :element
    (cond
      (fn-form? form)
      (env/fail! e :rf.ui.compile/bare-fn-ref
                 (str "bare fn in :ref — the bare-fn shorthand applies only to "
                      "native event properties, never refs. A callback ref must "
                      "be explicit: (ui/raw-fn f); object refs are preferred")
                 {:form form})
      (raw-fn-form? e form)
      {:form (walk-expr e [:ref :raw-fn] (second form)) :raw-fn? true}
      :else
      {:form (walk-expr e [:ref :element] form) :raw-fn? false})
    :view
    (env/fail! e :rf.ui.compile/ref-on-view-s1
               (str ":ref at an internal-view call site — internal views "
                    "forward :ref only by declaring it, and declared ref "
                    "forwarding lands S3. (Conservative S1 pin.)")
               {:form form})
    :foreign
    {:form (walk-expr e [:ref :foreign] form) :raw-fn? false}))

(defn- analyze-literal-props
  "Analyze a DOM/custom element's LITERAL props map `m` (`properties` is the
  build's custom-element property set for the tag, nil for plain DOM). The
  owned map of a `(ui/spread-safe owned caller)` form rides this same path, so
  a controlled owned site keeps the sync door. -> the props AST."
  [e tag-info properties m]
  (let [tag (:tag tag-info)]
    (doseq [k (keys m)]
      (when-not (keyword? k)
        (env/fail! e :rf.ui.compile/non-keyword-prop
                   (str "prop keys must be literal keywords; got " (pr-str k))
                   {:key k}))
      (check-rejected-spelling! e k))
    (when (and (:id tag-info) (contains? m :id))
      (env/fail! e :rf.ui.compile/id-sugar-conflict
                 (str "#" (:id tag-info) " sugar AND an :id prop on " tag
                      " — two id spellings on one element is an ambiguity, "
                      "and this grammar removes ambiguities. Keep one")
                 {:tag tag}))
    (let [key-form   (get m :key)
              m*         (dissoc m :key :class :style :ref)
              ref-form   (get m :ref)
              on?        (fn [k] (str/starts-with? (name k) "on-"))
              handler-ks (filter on? (keys m*))
              attr-ks    (remove on? (keys m*))
              controlled? (or (contains? m :value) (contains? m :checked))
              events0    (mapv #(analyze-handler e % (get m* %)) handler-ks)
              events     (mapv (fn [handler]
                                 (let [sync? (controlled-event-sync?
                                              controlled? handler)]
                                   (record-event-sync! e (:sid handler) sync?)
                                   (when (and controlled?
                                              (contains? #{"on-input" "on-change"
                                                           "on-before-input"}
                                                         (:name handler))
                                              (not sync?))
                                     (env/warn!
                                      e
                                      {:id :rf.ui.compile/controlled-input-async-handler
                                       :msg (str (:k handler)
                                                 " is paired with a controlled "
                                                 ":value/:checked prop, but its "
                                                 "handler is not a literal data "
                                                 "event. It stays on the ordinary "
                                                 "batched path; use a literal event "
                                                 "vector to open the controlled-"
                                                 "input sync door")
                                       :form (:form handler)}))
                                   (assoc handler :sync? sync?)))
                               events0)
              attrs      (mapv (fn [k]
                                  (let [v (get m* k)
                                        n (name k)
                                        property? (boolean (and properties (properties k)))
                                        v* (if (literal-scalar? v)
                                             v
                                             (walk-expr e [:prop k] v))]
                                   ;; literal collection VALUES only — seq forms
                                   ;; are dynamic expressions (runtime-normalized)
                                   (when (and (or (vector? v) (map? v) (set? v))
                                              (not property?))
                                     (env/fail! e :rf.ui.compile/collection-attr-value
                                                (str "collection value for attribute " k
                                                     " — collections are only meaningful "
                                                     "for :class/:style (React renders "
                                                     "\"[object Object]\" garbage). Pass "
                                                     "a string, e.g. (str/join \" \" xs)")
                                                {:prop k :value v}))
                                   (when (fn-form? v)
                                     (env/fail! e :rf.ui.compile/bare-fn-prop
                                                (str "bare fn at non-event prop " k " — "
                                                     "bare fns are legal only in known "
                                                     "native event properties (:on-* on "
                                                     "DOM/custom elements). Use ui/raw-fn "
                                                     "for identity-as-protocol callbacks")
                                                {:prop k}))
                                   {:k k
                                    :react-name (if property?
                                                  (rules/custom-element-property-name n)
                                                  (rules/react-prop-name n))
                                    :kind (if property? :property :attr)
                                     :value v*
                                     :literal? (literal-scalar? v)}))
                               attr-ks)
              sugar-id   (:id tag-info)
              attrs      (into (if sugar-id
                                 [{:k :id :react-name "id" :kind :attr
                                   :value sugar-id :literal? true}]
                                 [])
                               attrs)
              class-a    (when (or (seq (:classes tag-info)) (contains? m :class))
                           (analyze-class e (:classes tag-info) (get m :class)))
              style-a    (when (contains? m :style)
                           (analyze-style e (get m :style)))
               ref-a      (when (contains? m :ref) (analyze-ref e ref-form :element))
               key-form*  (if (and (contains? m :key)
                                    (not (literal-scalar? key-form)))
                            (walk-expr e [:key] key-form)
                            key-form)]
          {:key   {:present? (contains? m :key) :expr key-form*
                   :literal? (literal-scalar? key-form)}
           :class class-a
           :style style-a
           :attrs attrs
           :events events
           :spread nil
           :ref ref-a
           :property-props (into #{} (comp (filter #(= :property (:kind %))) (map :k)) attrs)
           :static? (and (not (contains? m :key))
                         (nil? ref-a)
                         (empty? events)
                         (every? :literal? attrs)
                         (or (nil? class-a) (:static? class-a))
                         (or (nil? style-a) (:static? style-a)))})))

(defn- analyze-spread-safe-props
  "Analyze `(ui/spread-safe owned caller)` in an element's props position (the
  LITERAL safe-spread policy). `owned` is a LITERAL props map, analysed exactly
  like an element's props (so a controlled owned site RETAINS the sync door);
  `caller` is the forwarded runtime attr map, guarded by the every-build
  owned-key deny law (`re-frame.ui.rules/assert-safe-caller!` at runtime; a
  LITERAL offender is caught here at compile time). -> the owned props AST plus
  a `:safe-spread` slot carrying the walked caller form + owned-handler keys."
  [e tag-info properties props-form]
  (let [[_ owned caller & extra] props-form]
    (when (or (not (map? owned)) (not (= 3 (count props-form))))
      (env/fail! e :rf.ui.compile/bad-spread-safe
                 (str "(ui/spread-safe owned caller) — `owned` must be a LITERAL "
                      "props map (the component's own props; the compiler proves "
                      "the controlled site and keeps the sync door) and `caller` "
                      "the forwarded runtime attr map")
                 {:form props-form}))
    (let [owned-props        (analyze-literal-props e tag-info properties owned)
          owned-handler-keys (into #{}
                                    (filter #(and (keyword? %)
                                                  (str/starts-with? (name %) "on-")))
                                    (keys owned))]
      ;; A LITERAL caller map is compile-checked against the deny law now — a
      ;; runtime map is guarded in every build by assert-safe-caller!.
      (when (map? caller)
        (doseq [k (keys caller)]
          (when (rules/spread-safe-denied-key? k owned-handler-keys)
            (env/fail! e :rf.ui.compile/spread-safe-owned-key
                       (str "(ui/spread-safe owned caller) — the caller map may "
                            "not carry the owned/structural key " (pr-str k)
                            "; it is denied in every build so it can never clobber "
                            "an owned prop. Forward it through the visible-cost "
                            "(ui/spread base overrides) instead, or drop it")
                       {:prop k :form props-form}))))
      (let [caller*  (walk-expr e [:spread-safe :caller] caller)
            identity (event-site-identity e :spread-safe props-form)]
        ;; ONE opaque runtime site classifies the caller's allowed :on-* handlers
        ;; (vector/fn/dynamic via the handler decision table) — exactly like a
        ;; general spread's site, so allowed caller handlers batch. The owned
        ;; handlers keep their own compiled per-site events (sync door intact).
        (add-event-site! e identity
                         {:prop :spread-safe :handler :opaque
                          :classification :spread :serializable? false
                          :sync? false})
        (assoc owned-props
               :safe-spread (merge identity {:base caller*
                                             :owned-handler-keys owned-handler-keys})
               :static? false)))))

(defn analyze-element-props
  "Analyze a DOM/custom element's props position — a literal map,
  `(ui/spread base overrides)`, or `(ui/spread-safe owned caller)`.
  -> {:key {..} :class {..} :style {..}|nil :attrs [..] :events [..]
  :spread form|nil :safe-spread form|nil :ref {..}|nil :property-props #{kw}
  :static? bool}"
  [e tag-info custom? props-form]
  (let [tag        (:tag tag-info)
        ;; Per-build compile-time property classification, read from the
        ;; ambient build's `elements` slice (rf2-vxgfnd.91). This analysis
        ;; runs under `cljs.env/*compiler*`, so `build/element-properties`
        ;; resolves the CURRENT build's declarations — never a process-global
        ;; last-writer-wins mirror that a sibling build could clobber between
        ;; this tag's declaration and this classification read.
        properties (when custom? (build/element-properties tag))
        spread?    (spread-form? e props-form)
        spread-safe? (spread-safe-form? e props-form)]
    (when (and (some? props-form) (not (map? props-form))
               (not spread?) (not spread-safe?))
      (env/fail! e :rf.ui.compile/dynamic-props-map
                 (str "props of " tag " must be a literal map, "
                      "(ui/spread base overrides), or (ui/spread-safe owned caller)")
                 {:form props-form}))
    (cond
      spread?
      (let [[_ base overrides & extra] props-form]
        (when (or (nil? base) (seq extra))
          (env/fail! e :rf.ui.compile/bad-spread
                     "(ui/spread base) or (ui/spread base overrides)"
                     {:form props-form}))
        (let [base*      (walk-expr e [:spread :base] base)
              overrides* (when overrides
                           (walk-expr e [:spread :overrides] overrides))
              identity   (event-site-identity e :spread props-form)]
          (add-event-site! e identity
                           {:prop :spread :handler :opaque
                            :classification :spread :serializable? false
                            :sync? false})
          {:key {:present? false} :class (analyze-class e (:classes tag-info) nil)
           :style nil :attrs [] :events []
           :spread (merge identity {:base base* :overrides overrides*})
           :safe-spread nil
           :ref nil :property-props #{} :static? false}))

      spread-safe?
      (analyze-spread-safe-props e tag-info properties props-form)

      :else
      (analyze-literal-props e tag-info properties (or props-form {})))))

;; ---------------------------------------------------------------------------
;; Nodes
;; ---------------------------------------------------------------------------

(declare analyze)

(defn- node-static? [n] (boolean (:static? n)))

(defn- analyze-children [e forms]
  (into []
        (map-indexed (fn [i f] (analyze (update e :path conj i) f)))
        forms))

(defn- analyze-element [e form]
  (let [head      (nth form 0)
        tag-info  (parse-tag e head)
        tag       (:tag tag-info)
        custom?   (str/includes? (name tag) "-")
        second*   (nth form 1 nil)
        has-props (or (map? second*) (spread-form? e second*)
                      (spread-safe-form? e second*))
        props     (analyze-element-props e tag-info custom? (when has-props second*))
        child-fs  (vec (if has-props (drop 2 form) (drop 1 form)))
        html-kid? (and (= 1 (count child-fs)) (html-form? e (first child-fs)))]
    (when (and (contains? rules/children-rejected-tags tag) (seq child-fs))
      (env/fail! e :rf.ui.compile/void-children
                 (str "<" (name tag) "> cannot have children (React throws at "
                      "render; this grammar rejects earlier)")
                 {:tag tag :form form}))
    (doseq [f child-fs]
      (when (and (html-form? e f) (not html-kid?))
        (env/fail! e :rf.ui.compile/html-not-sole-child
                   (str "(ui/html ...) must be the SOLE child of a DOM element "
                        "— the React host owns trusted markup through the "
                        "parent element. Wrap it: [:div (ui/html s)] "
                        "(conservative S1 pin)")
                   {:form form})))
    (let [children (if html-kid? [] (analyze-children e child-fs))
          html-ast (when html-kid?
                     (let [[_ s & extra] (first child-fs)]
                       (when (or (nil? s) (seq extra))
                         (env/fail! e :rf.ui.compile/bad-html
                                    "(ui/html string) takes exactly one argument"
                                    {:form (first child-fs)}))
                       (when-not (or (string? s) (not (literal-scalar? s)))
                         (env/fail! e :rf.ui.compile/bad-html
                                    "(ui/html x) requires a string"
                                    {:form (first child-fs)}))
                       (let [s* (if (string? s)
                                  s
                                  (walk-expr e [:html] s))]
                       ;; Record the trusted-markup site in the compiler
                       ;; manifest (profile row `ui/html` — "manifest site
                       ;; recording"): the visible bypass carries source/
                       ;; template path so tools can list every place escaping
                       ;; is bypassed. `:serializable?` is false for a dynamic
                       ;; string expression, true for a literal.
                       (env/add-site! e :htmls {:form s*
                                                :static? (string? s)
                                                :serializable? (string? s)
                                                :path (:path e)})
                       {:op :html :form s* :static? (string? s)})))]
      {:op :element
       :tag tag
       :custom? custom?
       :void? (contains? rules/void-tags tag)
       :props props
       :html html-ast
       :children children
       :static? (and (:static? props)
                     (nil? (:spread props))
                     (nil? (:safe-spread props))
                     (if html-kid? (:static? html-ast) (every? node-static? children))
                     true)
       :path (:path e)})))

;; ---------------------------------------------------------------------------
;; Compiled render slots — `ui/render-fn` callback + `ui/slot` invocation (S3)
;; ---------------------------------------------------------------------------
;;
;; `ui/render-fn` is a compiler-owned PURE render callback authored at the
;; consumer call site (a component prop value, or an inline ui/slot argument),
;; so its body is COMPILED — the closed template grammar, no runtime hiccup.
;; The body is a DEFERRED render (the library seam invokes it later, in a
;; different view), so `:in-render-fn?` seeds the deferred scope: sub/lease/
;; frame reads and the dispatch/hook surface (event handlers, refs) inside are
;; didactic `impure-slot-body` errors. Statically-referenced internal view
;; heads REMAIN legal — a stateful part is a pure slot body mounting a static
;; defview that owns its own state (the wave-2 registered-`ui/view` coverage
;; argument depends on exactly this).

(defn analyze-render-fn
  "Analyze `(ui/render-fn [args…] template)` → {:params <vec> :body <ast>}.
  The params bind as locals over a pure deferred-render template body."
  [e form]
  (let [[_ params & body] form]
    (when-not (vector? params)
      (env/fail! e :rf.ui.compile/bad-render-fn
                 (str "(ui/render-fn [args…] template) needs a literal parameter "
                      "binding vector before its one template body; got "
                      (pr-str params))
                 {:form form}))
    (when (some #{'&} params)
      (env/fail! e :rf.ui.compile/bad-render-fn
                 (str "ui/render-fn parameters are a FIXED arg list — variadic & "
                      "is not permitted (a ui/slot passes a fixed number of args)")
                 {:form form}))
    (when (not= 1 (count body))
      (env/fail! e :rf.ui.compile/bad-render-fn
                 (str "(ui/render-fn [args…] template) has exactly ONE template "
                      "body form — computation goes in (let …); siblings wrap in "
                      "[:<> …]. Got " (count body) " body forms")
                 {:form form}))
    (reject-reactive-binding! e params)
    (let [binders (env/binding-syms params)
          e*      (-> e
                      (env/with-locals binders)
                      (assoc :in-render-fn? true :in-loop? false :loop-syms #{})
                      (dissoc :top-region?)
                      (update :path conj :render-fn))]
      {:params params
       :body   (analyze e* (first body))})))

(defn- analyze-component-props
  "View/foreign call-site props (Q2/Q3/Q4): literal map required; :key
  extracted (never a prop); :children as an explicit key rejected
  (children are positional); literal fn values rejected (the narrow
  bare-fn law: not arbitrary fn-valued props)."
  [e head-info props-form]
  (when (and (some? props-form) (not (map? props-form)))
    (env/fail! e :rf.ui.compile/dynamic-props-map
               (str "component call sites take a LITERAL props map — a wholly-"
                    "dynamic props expression is not v1 grammar (conservative "
                    "S1 pin; ui/spread converts dynamic maps for DOM elements "
                    "only)")
               {:head (:sym head-info) :form props-form}))
  (let [m (or props-form {})]
    (doseq [k (keys m)]
      (when-not (keyword? k)
        (env/fail! e :rf.ui.compile/non-keyword-prop
                   (str "prop keys must be literal keywords; got " (pr-str k))
                   {:key k}))
      (when (= k :children)
        (env/fail! e :rf.ui.compile/children-prop
                   (str ":children as an explicit prop at a call site — "
                        "children are positional ([view {...} child1 child2]) "
                        "and arrive as :children on the definition side. One "
                        "spelling per concept")
                   {:head (:sym head-info)})))
    (let [key-form (get m :key)
          ref-a    (when (contains? m :ref)
                     (analyze-ref e (get m :ref)
                                  (if (= :view (:kind head-info)) :view :foreign)))
          m*       (dissoc m :key :ref)
          entries  (mapv (fn [[k v]]
                           (if (render-fn-form? e v)
                             ;; A compiled render slot: the body is a lexically
                             ;; visible pure template compiled HERE (both emitters)
                             ;; into a callback value the seam invokes via ui/slot.
                             {:k k
                              :slot (if-let [ns* (namespace k)] (str ns* "/" (name k)) (name k))
                              :render-fn (analyze-render-fn
                                          (update e :path into [:component-prop k]) v)
                              :marker :render-fn
                              :literal? false}
                             (do
                               (when (fn-form? v)
                                 (env/fail! e :rf.ui.compile/bare-fn-prop
                                            (str "bare fn prop " k " at a "
                                                 (if (= :view (:kind head-info))
                                                   "view" "foreign-component")
                                                 " boundary — invoker and phase are "
                                                 "unknown there. Choose ui/raw-fn "
                                                 "(identity-as-protocol), ui/event (a "
                                                 "committed :on-* handler), or "
                                                 "ui/render-fn (a compiled render "
                                                 "slot) — never a bare fn")
                                            {:prop k :head (:sym head-info)}))
                               ;; NOTE: vector/data props MAY capture loop bindings —
                               ;; passing row data into a keyed child view is exactly
                               ;; the extract-a-keyed-child-view fix; only HANDLER
                               ;; sites are capture-checked.
                               (let [raw?    (raw-form? e v)
                                     raw-fn? (raw-fn-form? e v)
                                     v*      (cond
                                               raw? (walk-expr e [:component-prop k :raw]
                                                               (second v))
                                               raw-fn? (walk-expr e [:component-prop k :raw-fn]
                                                                  (second v))
                                               (literal-scalar? v) v
                                               :else (walk-expr e [:component-prop k] v))]
                                 {:k k
                                  :slot (if-let [ns* (namespace k)] (str ns* "/" (name k)) (name k))
                                  :value v*
                                  :marker (cond raw? :foreign raw-fn? :ui/raw-fn :else nil)
                                  :literal? (literal-scalar? v)}))))
                          m*)]
      (let [key-form* (if (and (contains? m :key) (not (literal-scalar? key-form)))
                        (walk-expr e [:component-key] key-form)
                        key-form)]
      {:key {:present? (contains? m :key) :expr key-form*
             :literal? (literal-scalar? key-form)}
       :entries entries
       :ref ref-a}))))

(defn- analyze-component [e form]
  (let [head      (nth form 0)
        info      (env/classify-head e head)
        second*   (nth form 1 nil)
        has-props (map? second*)
        props     (analyze-component-props e info (when has-props second*))
        child-fs  (vec (if has-props (drop 2 form) (drop 1 form)))
        ;; a view/foreign boundary ENDS the static top region (S1c):
        ;; frame-root below it is a compile error
        children  (analyze-children (dissoc e :top-region?) child-fs)]
    (when (and (= :view (:kind info)) (seq children))
      (let [meta*      (:meta (env/resolve-sym e head))
            self?      (and (:self e) (= head (:self e)))
            children-ok? (if self?
                           (:self-children? e)
                           (:rf.ui/children? meta*))]
        (when-not children-ok?
          (env/fail! e :rf.ui.compile/children-not-accepted
                     (str "view " (:view-id info) " does not accept children — "
                          "it declares none (:children in the header "
                          "destructuring, an :as binding, or a [:children ...] "
                          "schema entry would declare them). Q4 pin")
                     {:head head}))))
    (when (= :view (:kind info))
      ;; Q2 closed-map: :props present => closed — literal call-site keys
      ;; must be declared
      (let [meta*  (:meta (env/resolve-sym e head))
            self?  (and (:self e) (= head (:self e)))
            closed (if self? (:self-closed-keys e) (:rf.ui/closed-prop-keys meta*))]
        (when closed
          (let [allowed (set closed)
                bad     (remove #(or (allowed %) (= :key %)) (map :k (:entries props)))]
            (when (seq bad)
              (env/fail! e :rf.ui.compile/undeclared-prop
                         (str "undeclared prop" (when (next bad) "s") " "
                              (str/join ", " (map pr-str bad)) " passed to "
                              (:view-id info) " — its :props schema closes the "
                              "map (declared: " (str/join ", " (map pr-str closed))
                              "). Q2 pin: absent :props = open, present :props "
                              "= closed")
                         {:head head :undeclared (vec bad)}))))))
    {:op (if (= :view (:kind info)) :view :foreign)
     :sym head
     :fqn (:fqn info)
     :view-id (:view-id info)
     :props props
     :children children
     :static? false
     :path (:path e)}))

(defn- analyze-slot
  "Analyze `(ui/slot render-fn-value arg…)` — the compiler-owned invocation of
  a compiled render slot in child position. The first argument is an inline
  `(ui/render-fn …)` (compiled here) or an ordinary expression evaluating to a
  render-fn value or nil (validated at the seam by `slot-ready?`); the
  remaining args are the library's runtime values, walked in the ambient
  (library-view) scope — a slot ARGUMENT is not deferred, so a `(sub …)` there
  is the library's own render-time read. The slot's output participates in the
  surrounding children exactly like any other child (child-like memo cost)."
  [e form]
  (when (< (count form) 2)
    (env/fail! e :rf.ui.compile/bad-slot
               (str "(ui/slot render-fn-value arg…) needs a render-fn value (or "
                    "nil) as its first argument; got " (pr-str form))
               {:form form}))
  (let [slotval (nth form 1)
        args    (drop 2 form)
        sid     (lexical-site-id e :slot form (:path e))
        args*   (into []
                      (map-indexed (fn [i a] (walk-expr e [:slot :arg i] a)))
                      args)
        node    (cond-> {:op :slot
                         :args args*
                         :sid sid
                         :static? false
                         :path (:path e)}
                  (render-fn-form? e slotval)
                  (assoc :render-fn (analyze-render-fn
                                     (update e :path conj :slot-fn) slotval))
                  (not (render-fn-form? e slotval))
                  (assoc :slot-value (if (literal-scalar? slotval)
                                       slotval
                                       (walk-expr e [:slot :value] slotval))))]
    (env/add-site! e :slots {:sid sid
                             :path (:path e)
                             :source-coord (event-source-coord e form)
                             :inline? (contains? node :render-fn)})
    node))

;; ---------------------------------------------------------------------------
;; Reactive authoring verbs in head position (rf2-vxgfnd.266)
;; ---------------------------------------------------------------------------
;;
;; sub/lease/frame are reactive authoring verbs, sound in evaluated code ONLY as
;; their compiler-owned DIRECT forms — (sub query), the leading (lease
;; descriptor) declaration, (frame) — which the expression rewriter lowers to
;; indexed runtime sites. A Hiccup component HEAD is not such a form:
;; env/classify-head resolves any resolved non-:rf.ui/view var to a plain
;; :foreign React component, so [sub {…}] / [lease {…}] / [frame] would
;; otherwise compile as a foreign component with an EMPTY reactive manifest,
;; leaving the public authoring var to survive to runtime unindexed and
;; bypassing reactive-site indexing entirely. These verbs are therefore
;; RESERVED BEFORE generic component classification — `analyze`'s dispatch
;; checks this head predicate ahead of `analyze-component`/`env/classify-head`
;; — so a reactive verb can never be reclassified as foreign. A lexical shadow
;; resolves to nil (`resolve-sym` skips locals) and falls through to the
;; ordinary local-head (`:dynamic-head`) rule; a genuine foreign component still
;; classifies as :foreign.

(defn- reactive-authoring-head-kind
  "Return :sub/:lease/:frame when `head` is an unshadowed symbol resolving to a
  public reactive authoring var — the reserved-head discriminator, mirroring
  frame-root-head?/frame-provider-head?."
  [e head]
  (when (and (symbol? head) (not (contains? (:locals e) head)))
    (reactive-authoring-var-kind e head)))

(defn- analyze-reactive-authoring-head! [e form head]
  (let [kind (reactive-authoring-head-kind e head)]
    (env/fail! e :rf.ui.compile/unsupported-form
               (str "reactive authoring verb " (name kind) " in component-head "
                    "position [" (name kind) " …] — re-frame.ui/" (name kind)
                    " is sound ONLY as its compiler-owned direct call head "
                    (reactive-direct-form kind)
                    ", which the compiler lowers to an indexed render site. As a "
                    "Hiccup head it would classify as a plain foreign React "
                    "component with an empty reactive manifest, leaving the "
                    "public authoring var to survive to runtime unindexed. Keep "
                    "the read at a visible " (reactive-direct-form kind) " site")
               {:form form :reactive-kind kind})))

;; ---------------------------------------------------------------------------
;; frame-root (S1c, rf2-vxgfnd.3) — the static ENSURE-plan position
;; ---------------------------------------------------------------------------
;;
;; The STATIC TOP REGION of a root form (root-identity contract §6) is every
;; node reachable from the root without crossing a control form, a dynamic
;; expression, an internal-view boundary, or a foreign component. The walk
;; carries `:top-region? true` (set only by the root-form entry points —
;; `ui/mount` / `ui/render!` / `ui/hydrate-root`); DOM elements, fragments
;; and frame-root itself PRESERVE the flag for their children, and every
;; other position clears it. `frame-root` is legal exactly while the flag
;; holds — plans are static identity, extracted at compile time.

(defn- frame-root-head? [e head]
  (and (symbol? head)
       (not (contains? (:locals e) head))
       (env/resolves-to? e head frame-root-fqns)))

(defn- analyze-frame-root [e form]
  (when-not (:top-region? e)
    (env/fail! e :rf.ui.compile/frame-root-misplaced
               (str "frame-root sits outside the static top region of a root "
                    "form — ENSURE plans are static identity, extracted at "
                    "compile time. Legal positions: the root form handed to "
                    "ui/mount / ui/render! / ui/hydrate-root, nested only "
                    "under unconditional wrappers (DOM elements, fragments, "
                    "frame-root) — never under a control form, inside a "
                    "view, or in a defview template (frames are ambient "
                    "inside views)")
               {:form form}))
  (let [props (nth form 1 nil)]
    (when-not (map? props)
      (env/fail! e :rf.ui.compile/bad-frame-root
                 (str "frame-root takes a literal props map: "
                      "[frame-root {:id :frame-id ...} children...]")
                 {:form form}))
    (when (contains? props :frame)
      (env/fail! e :rf.ui.compile/bad-frame-root
                 (str "frame-root ENSURES a frame by :id — :frame is "
                      "frame-provider's scope key (providers scope, roots "
                      "ensure; the rf2-nyea0r split)")
                 {:form form}))
    (let [id (:id props)]
      (when-not (keyword? id)
        (env/fail! e :rf.ui.compile/bad-frame-root
                   (str "frame-root :id must be a compile-time literal "
                        "keyword — plans are static identity; got "
                        (pr-str id))
                   {:form form :id id}))
      (let [config (not-empty (dissoc props :id))
            config* (when config
                      (with-meta
                        (into (empty config)
                              (map (fn [[k v]]
                                     [k (walk-expr e [:frame-root :config k] v)]))
                              config)
                        (meta config)))]
        ;; config values are opaque runtime expressions (evaluated at
        ;; preflight) — walk them for sub/lease site indexing only
        {:op :frame-root
         :frame-id id
         :config config*
         :children (analyze-children e (vec (drop 2 form)))
         :static? false
         :path (:path e)}))))

;; ---------------------------------------------------------------------------
;; frame-provider (S2c, rf2-vxgfnd.9) — the SCOPE form
;; ---------------------------------------------------------------------------
;;
;; frame-provider scopes a subtree to an ALREADY-LIVE frame through the
;; shared React context — it is legal ANYWHERE a template form is (defview
;; templates and root forms), unlike the static-top-region-only `frame-root`.
;; Its `:frame` is a RUNTIME frame TARGET (a frame-id keyword OR a live frame
;; value), so provider-scoped frames are not statically extractable and
;; contribute NO plan to the Root Descriptor (root-identity contract §6). In
;; a root form's top region the walk descends THROUGH a frame-provider (it
;; preserves `:top-region?` for its children), so a nested `frame-root` plan
;; is still extracted; the provider itself just scopes.

(defn- frame-provider-head? [e head]
  (and (symbol? head)
       (not (contains? (:locals e) head))
       (env/resolves-to? e head frame-provider-fqns)))

(defn- analyze-frame-provider [e form]
  (let [props (nth form 1 nil)]
    (when-not (map? props)
      (env/fail! e :rf.ui.compile/bad-frame-provider
                 (str "frame-provider takes a literal props map: "
                      "[frame-provider {:frame f} children...]")
                 {:form form}))
    (when (contains? props :id)
      (env/fail! e :rf.ui.compile/bad-frame-provider
                 (str "frame-provider SCOPES an already-live frame by :frame — "
                      ":id is frame-root's ENSURE key (roots ensure, providers "
                      "scope; the rf2-nyea0r split). Use frame-root {:id ...} to "
                      "create-if-absent")
                 {:form form}))
    (when-not (contains? props :frame)
      (env/fail! e :rf.ui.compile/bad-frame-provider
                 (str "frame-provider requires :frame — the already-live frame "
                      "target (a frame-id keyword or a live frame value) to "
                      "scope the subtree to")
                 {:form form}))
    (let [extra (dissoc props :frame)]
      (when (seq extra)
        (env/fail! e :rf.ui.compile/bad-frame-provider
                   (str "frame-provider takes only {:frame f}; got extra key"
                        (when (next extra) "s") " "
                        (str/join ", " (map pr-str (keys extra)))
                        " — providers only scope (roots ensure)")
                   {:form form})))
    (let [target (:frame props)
          target* (if (literal-scalar? target)
                    target
                    (walk-expr e [:frame-provider :frame] target))]
      ;; the :frame target is a runtime expression — walk it for sub/lease
      ;; site indexing (a sub in the target expression is still a site)
      {:op :frame-provider
       :frame target*
       ;; children keep whatever region flag `e` carries: preserved in a
       ;; root form's top region (nested frame-root plans still extract),
       ;; absent in a defview template
       :children (analyze-children e (vec (drop 2 form)))
       :static? false
       :path (:path e)})))

(defn- analyze-fragment [e form]
  (let [second*   (nth form 1 nil)
        has-props (map? second*)
        _         (when has-props
                    (let [extra (dissoc second* :key)]
                      (when (seq extra)
                        (env/fail! e :rf.ui.compile/bad-fragment-props
                                   (str "fragments take only {:key ...}; got "
                                        (str/join ", " (map pr-str (keys extra))))
                                   {:form form}))))
        key-form  (when has-props (get second* :key))
        key-form* (if (and has-props (not (literal-scalar? key-form)))
                    (walk-expr e [:fragment :key] key-form)
                    key-form)
        children  (analyze-children e (vec (if has-props (drop 2 form) (drop 1 form))))]
    {:op :fragment
     :key {:present? has-props :expr key-form* :literal? (literal-scalar? key-form)}
     :children children
     :static? (and (not has-props) (every? node-static? children))
     :path (:path e)}))

;; ---------------------------------------------------------------------------
;; Control forms
;; ---------------------------------------------------------------------------

(defn- single-body! [e head body form]
  (when (not= 1 (count body))
    (env/fail! e :rf.ui.compile/multi-form-body
               (str head " in template position takes exactly ONE body form — "
                    "side effects don't belong in templates (statically-pure "
                    "rule); siblings wrap in [:<> ...]")
               {:form form}))
  (first body))

(defn- analyze-if [e test then else form]
  {:op :if :test (walk-expr e [:if :test] test)
   :then (analyze (update e :path conj :then) then)
   :else (if (some? else)
           (analyze (update e :path conj :else) else)
           {:op :nothing :static? true})
   :static? false :path (:path e)})

(defn- analyze-cond [e clauses form]
  (cond
    (empty? clauses) {:op :nothing :static? true}
    (odd? (count clauses))
    (env/fail! e :rf.ui.compile/bad-cond
               "cond needs test/branch pairs: (cond test1 branch1 ... :else fallback)"
               {:form form})
    :else
    (let [[t b & more] clauses]
      (if (= :else t)
        (analyze e b)
        (analyze-if e t b (when (seq more) (cons 'cond more)) form)))))
  ;; note: the recursive else is re-entered through analyze-list as (cond ...)

(defn- analyze-case [e form]
  (let [[_ expr & clauses] form]
    (let [default? (odd? (count clauses))
          pairs    (partition 2 (if default? (butlast clauses) clauses))
          default  (when default? (last clauses))]
      {:op :case
       :expr (walk-expr e [:case :expr] expr)
       :clauses (into []
                      (map-indexed (fn [i [test branch]]
                                     [test (analyze (update e :path conj [:case i]) branch)]))
                      pairs)
       :default (if default?
                  (analyze (update e :path conj :case-default) default)
                  ::none)
       :static? false
       :path (:path e)})))

(defn- analyze-let [e form]
  (let [[head bindings & body] form]
    (when-not (and (vector? bindings) (even? (count bindings)))
      (env/fail! e :rf.ui.compile/bad-let
                 (str head " needs an even bindings vector") {:form form}))
    (let [pairs (partition 2 bindings)
          [e* rewritten]
          (reduce (fn [[acc out] [i [pat init]]]
                    (reject-reactive-binding! acc pat)
                    [(env/with-locals acc (env/binding-syms pat))
                     (conj out pat
                           (walk-expr acc [:let :binding i] init))])
                  [e []]
                  (map-indexed vector pairs))
          bindings* (with-meta (vec rewritten) (meta bindings))
          body-form (single-body! e (str head) body form)]
      {:op :let :bindings bindings*
       :body (analyze (update e* :path conj :body) body-form)
       :static? false :path (:path e)})))

(defn- analyze-letfn [e form]
  (let [[_ fnspecs & body] form]
    (when-not (vector? fnspecs)
      (env/fail! e :rf.ui.compile/bad-let "letfn needs a fnspecs vector" {:form form}))
    (let [names (map first fnspecs)
          e*    (env/with-locals e names)
          ;; Reuse the opaque rewriter's exact letfn scoping (all names in
          ;; scope for every body; each argv shadows vars within its arity).
          fnspecs* (second
                     (walk-expr e [:letfn :bindings]
                                (list 'letfn fnspecs nil)))]
      (let [body-form (single-body! e "letfn" body form)]
        {:op :letfn :fnspecs fnspecs*
         :body (analyze (update e* :path conj :body) body-form)
         :static? false :path (:path e)}))))

(defn- analyze-for [e form]
  (let [[_ seq-exprs & body] form]
    (when-not (and (vector? seq-exprs) (even? (count seq-exprs)) (seq seq-exprs))
      (env/fail! e :rf.ui.compile/bad-for
                 "(for [pattern coll ...modifiers] body) needs a non-empty, even seq-exprs vector"
                 {:form form}))
    (let [pairs (partition 2 seq-exprs)]
      (when (keyword? (ffirst pairs))
        (env/fail! e :rf.ui.compile/bad-for
                   "for needs a binding pair before modifiers" {:form form}))
      ;; Rewrite in evaluation order. The first collection evaluates once per
      ;; view render; after its binding every later collection/modifier is
      ;; row-scoped and therefore rejects sub/lease sites.
      (let [[e-final rewritten]
            (loop [scope e
                   first-coll? true
                   ps (seq (map-indexed vector pairs))
                   out []]
              (if-let [[i [l r]] (first ps)]
                (cond
                  (= :let l)
                  (do
                    (when-not (and (vector? r) (even? (count r)))
                      (env/fail! e :rf.ui.compile/bad-for
                                 ":let modifier needs an even bindings vector"
                                 {:form form}))
                    (let [[scope* binds*]
                          (reduce (fn [[s xs] [j [pat init]]]
                                    (reject-reactive-binding! s pat)
                                    [(env/with-loop s (env/binding-syms pat))
                                     (conj xs pat
                                           (walk-expr (assoc s :in-loop? true)
                                                      [:for i :let j] init))])
                                  [scope []]
                                  (map-indexed vector (partition 2 r)))]
                      (recur scope* false (next ps)
                             (conj out l (with-meta (vec binds*) (meta r))))))

                  (contains? #{:when :while} l)
                  (recur scope false (next ps)
                         (conj out l
                               (walk-expr (assoc scope :in-loop? true)
                                          [:for i l] r)))

                  (keyword? l)
                  (env/fail! e :rf.ui.compile/bad-for
                             (str "unknown for modifier " l " — the subgrammar is "
                                  ":let / :when / :while (Q6 pin)")
                             {:form form})

                  :else
                  (let [_  (reject-reactive-binding! scope l)
                        r* (walk-expr (if first-coll?
                                        scope
                                        (assoc scope :in-loop? true))
                                      [:for i :collection] r)]
                    (recur (env/with-loop scope (env/binding-syms l))
                           false (next ps) (conj out l r*))))
                [scope out]))
            seq-exprs* (with-meta (vec rewritten) (meta seq-exprs))
            e-body    (update e-final :path conj :for)
            body-form (single-body! e "for" (vec body) form)
            _         (when (and (seq? body-form) (= 'for (first body-form)))
                        (env/fail! e :rf.ui.compile/nested-for-body
                                   (str "a for directly inside a for body — express "
                                        "nested iteration as multiple binding pairs in "
                                        "ONE for: (for [x xs, y (f x)] ...) — one keyed "
                                        "list site (Q6 pin)")
                                   {:form form}))
            body-ast  (analyze e-body body-form)]
        (when-not (contains? #{:element :view :foreign :fragment} (:op body-ast))
          (env/fail! e :rf.ui.compile/unkeyed-list-item
                     (str "for body must be a keyed element/view/fragment — got "
                          (name (:op body-ast)) ". Keyed lists compile to direct "
                          "JS arrays; missing key = build failure")
                     {:form form}))
        (let [k (get-in body-ast [:props :key] (get body-ast :key))]
          (when-not (:present? k)
            (env/fail! e :rf.ui.compile/unkeyed-list-item
                       (str "missing :key on the for body — every list row needs "
                            "a literal :key prop (unkeyed list items are a build "
                            "failure)")
                       {:form form}))
          (when (:literal? k)
            (env/fail! e :rf.ui.compile/constant-list-key
                       (str "constant :key " (pr-str (:expr k)) " in a list — a "
                            "key must vary per row (a constant key guarantees "
                            "duplicates). Key on row data: {:key (:id x)}")
                       {:form form})))
        {:op :for
         :seq-exprs seq-exprs*
         :body body-ast
         :static? false
         :path (:path e)}))))

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn- analyze-list [e form]
  (let [head (first form)]
    (if (and (symbol? head)
             (contains? control-heads head)
             (not (contains? (:locals e) head)))
      (case head
        if       (let [[_ t a b & extra] form]
                   (when (seq extra)
                     (env/fail! e :rf.ui.compile/bad-if "if takes test then else?" {:form form}))
                   (analyze-if e t a b form))
        if-not   (let [[_ t a b & extra] form]
                   (when (seq extra)
                     (env/fail! e :rf.ui.compile/bad-if "if-not takes test then else?" {:form form}))
                   (analyze-if e (list 'not t) a b form))
        when     (let [[_ t & body] form]
                   (analyze-if e t (single-body! e "when" (vec body) form) nil form))
        when-not (let [[_ t & body] form]
                   (analyze-if e (list 'not t) (single-body! e "when-not" (vec body) form) nil form))
        cond     (analyze-cond e (rest form) form)
        case     (analyze-case e form)
        let      (analyze-let e form)
        letfn    (analyze-letfn e form)
        do       (analyze e (single-body! e "do" (vec (rest form)) form))
        for      (analyze-for e form))
      (cond
        (raw-form? e form)
        (let [[_ x & extra] form]
          (when (or (nil? x) (seq extra))
            (env/fail! e :rf.ui.compile/bad-raw "(ui/raw react-element) takes one argument"
                       {:form form}))
          {:op :raw :form (walk-expr e [:raw] x)
           :static? false :path (:path e)})

        (html-form? e form)
        (env/fail! e :rf.ui.compile/html-not-sole-child
                   (str "(ui/html ...) must be the sole child of a DOM element "
                        "— here it has no host element to own the markup. Wrap "
                        "it: [:div (ui/html s)] (conservative S1 pin)")
                   {:form form})

        (raw-fn-form? e form)
        (env/fail! e :rf.ui.compile/raw-fn-child
                   "(ui/raw-fn f) is a callback marker for prop positions, not renderable content"
                   {:form form})

        (slot-form? e form)
        (analyze-slot e form)

        (render-fn-form? e form)
        (env/fail! e :rf.ui.compile/render-fn-misplaced
                   (str "(ui/render-fn …) is a render-slot callback value, not "
                        "renderable content — invoke it with (ui/slot render-fn "
                        "arg…), or pass it as a component prop value")
                   {:form form})

        (spread-form? e form)
        (env/fail! e :rf.ui.compile/bad-spread
                   "(ui/spread ...) belongs in a DOM element's props position: [:div (ui/spread base overrides)]"
                   {:form form})

        (spread-safe-form? e form)
        (env/fail! e :rf.ui.compile/bad-spread-safe
                   "(ui/spread-safe ...) belongs in a DOM element's props position: [:input (ui/spread-safe {:value v :on-change …} caller)]"
                   {:form form})

        (and (symbol? head) (not (contains? (:locals e) head))
             (env/resolves-to? e head markup-map-fqns))
        (env/fail! e :rf.ui.compile/markup-returning-map
                   (str "(" head " f coll) in child position — markup-returning "
                        "map is rejected: it hides keys and laziness. Use "
                        "(for [x coll] [row {:key (:id x)}])")
                   {:form form})

        (and (symbol? head) (not (contains? (:locals e) head))
             (env/resolves-to? e head lazy-seq-fqns))
        (env/fail! e :rf.ui.compile/lazy-seq-child
                   (str "(" head " ...) in child position produces a raw seq — "
                        "raw lazy seqs are rejected: they hide keys and "
                        "laziness. Render rows with (for [x coll] "
                        "[row {:key (:id x)}]); render text with "
                        "(str/join ...)")
                   {:form form})

        :else
        {:op :expr :form (walk-expr e [:expr] form)
         :static? false :path (:path e)}))))

;; ---------------------------------------------------------------------------
;; Vector-head precedence (rf2-vxgfnd.274)
;; ---------------------------------------------------------------------------
;;
;; A symbol head classifies by RESOLUTION at expansion time (the Q5 rule,
;; env/classify-head), but two reservations run in `analyze`'s dispatch
;; AHEAD of `env/classify-head`: the frame-boundary heads (frame-root /
;; frame-provider) and the reactive-authoring verbs (sub / lease / frame,
;; rf2-vxgfnd.266). Both key on Var resolution — so a head equal to the view
;; being `defview`d right now (`:self`) that ALSO resolves to a referred
;; public authoring Var (`(defview sub [] [sub …])` in a namespace that
;; refers `re-frame.ui/sub`) was reserved-then-rejected BEFORE the Q5
;; self-recursion rule could select it as an internal view — even though the
;; view Var may not exist yet, so classification here cannot depend on Var
;; resolution at all (Q5 rule 1). The one true precedence ordering is:
;;
;;   1. a local/dynamic binding of the spelling (checked inside every head
;;      predicate and env/classify-head) — a lexical shadow outranks self;
;;   2. the exact, unshadowed `:self` — an internal-view head, resolution-free;
;;   3. the reserved frame-boundary / reactive-authoring heads (Var-resolved);
;;   4. ordinary internal/foreign classification (env/classify-head).
;;
;; `self-head?` is tier 2 — it fires before every reserved-head predicate so a
;; genuine self-recursive head is never reclassified as a reserved verb.

(defn- self-head?
  "True when `head` is the exact, unshadowed symbol of the view currently
  being compiled (`:self`). Self-recursion outranks every reserved-head
  reservation because the view Var may not exist yet — classification cannot
  depend on Var resolution here (Q5 rule 1). A local binding of the same
  spelling (checked first) still outranks self and yields a dynamic head."
  [e head]
  (and (:self e)
       (symbol? head)
       (= head (:self e))
       (not (contains? (:locals e) head))))

(defn analyze
  "Template form -> AST node."
  [e form]
  (cond
    (string? form)  {:op :text :value form :static? true}
    (number? form)  {:op :text :value (rules/js-number-str form) :static? true}
    (nil? form)     {:op :nothing :static? true}
    (boolean? form) {:op :nothing :static? true}
    (keyword? form) (env/fail! e :rf.ui.compile/keyword-child
                               (str "keyword " form " in child position — keywords are "
                                    "element heads, not content. String content wants "
                                    (pr-str (name form)))
                               {:form form})
    (vector? form)
    (let [head (nth form 0 nil)]
      (cond
        (= :<> head)     (analyze-fragment e form)
        (keyword? head)  (analyze-element e form)
        ;; The exact unshadowed self outranks every reserved-head reservation
        ;; below (rf2-vxgfnd.274): the view Var may not exist yet, so a
        ;; self-recursive head classifies as an internal view up front —
        ;; resolution-free — before any Var-resolved reservation (frame
        ;; boundary / reactive authoring) can reclassify it.
        (self-head? e head) (analyze-component e form)
        (frame-root-head? e head) (analyze-frame-root e form)
        (frame-provider-head? e head) (analyze-frame-provider e form)
        ;; Reserve sub/lease/frame BEFORE generic component classification
        ;; (rf2-vxgfnd.266): a reactive authoring verb can never be
        ;; reclassified as a :foreign component with an empty manifest.
        (reactive-authoring-head-kind e head)
        (analyze-reactive-authoring-head! e form head)
        (symbol? head)   (analyze-component e form)
        :else
        (env/fail! e :rf.ui.compile/dynamic-head
                   (str "dynamic element head " (pr-str head) " — heads must be "
                        "literal (a keyword or a component var). Runtime-chosen "
                        "components are ui/view / ui/element [WAVE-2]; ui/raw "
                        "covers a runtime React element meanwhile")
                   {:form form})))
    ;; a control form / opaque expression ends the static top region (S1c)
    (seq? form)     (analyze-list (dissoc e :top-region?) form)
    (symbol? form)  {:op :expr :form (walk-expr e [:expr] form)
                     :static? false :path (:path e)}
    :else
    (env/fail! e :rf.ui.compile/unsupported-form
               (str "unsupported template form " (pr-str form) " of type "
                    (type form) " — template content is strings/numbers/"
                    "nil/false, [:tag ...] vectors, control forms, and "
                    "expressions. Render a value as text with (str ...)")
               {:form form})))
