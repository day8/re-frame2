(ns re-frame.freehand.compiler.analyze
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
            [re-frame.freehand.compiler.a11y :as a11y]
            [re-frame.freehand.compiler.binding-plan :as bp]
            [re-frame.freehand.compiler.build :as build]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.controlled :as controlled]
            [re-frame.freehand.events :as events]
            [re-frame.freehand.fingerprint :as fingerprint]
            [re-frame.freehand.props-schema :as props-schema]
            [re-frame.freehand.rules :as rules]
            #?@(:clj [[re-frame.freehand.compiler.harvest :as harvest]])))

(def node-ops
  "The CLOSED op set — the AST-shape gate's vocabulary. `:frame-root` (S1c,
  rf2-vxgfnd.3) appears only inside the static top region of a ROOT form
  (`v/mount` / `v/render!` / `v/hydrate-root`) — the analyzer rejects it
  everywhere else, so defview-template ASTs never carry it.
  `:frame-provider` (S2c, rf2-vxgfnd.9) is the SCOPE form — legal anywhere,
  scoping a subtree to an already-live frame."
  #{:text :nothing :expr :element :fragment :view :foreign
    :if :let :letfn :case :for :raw :html :frame-root :frame-provider :slot
    :error-boundary :client-only :presence
    :hook-prefix})

(defn literal-scalar? [x]
  (or (string? x) (number? x) (keyword? x) (boolean? x) (nil? x)))

(def ^:private ui-raw-fqns    #{'re-frame.freehand/raw})
(def ^:private ui-html-fqns   #{'re-frame.freehand/html})
(def ^:private ui-raw-fn-fqns #{'re-frame.freehand/raw-fn})
(def ^:private ui-spread-fqns #{'re-frame.freehand/spread})
(def ^:private ui-spread-safe-fqns #{'re-frame.freehand/spread-safe})
(def ^:private ui-event-fqns  #{'re-frame.freehand/event})
(def ^:private ui-handler-fqns #{'re-frame.freehand/handler})
(def ^:private ui-render-fn-fqns #{'re-frame.freehand/render-fn})
(def ^:private ui-slot-fqns   #{'re-frame.freehand/slot})
(def ^:private error-boundary-fqns #{'re-frame.freehand/error-boundary})
(def ^:private client-only-fqns #{'re-frame.freehand/client-only})
(def ^:private presence-fqns #{'re-frame.freehand/presence})
(def ^:private sub-fqns       #{'re-frame.freehand/sub})
(def ^:private frame-fqns     #{'re-frame.freehand/frame})
(def ^:private local-fqns     #{'re-frame.freehand/local})
(def ^:private effect-fqns    #{'re-frame.freehand/effect})
(def ^:private dispatch-fn-fqns #{'re-frame.freehand/dispatch-fn})

;; The frozen re-frame.freehand.react interop tier (Spec 004 §The React interop tier)
;; plus the promoted substrate host hook `re-frame.freehand/ref` (rf2-u53yy.9). All are
;; recognised in expression (let-binding value) position by the same finite-site
;; machinery as `local`, position-checked, and lowered to `re-frame.freehand.hooks/*`
;; (ref's lowering target stays the `use-ref` runtime fn). `lazy` is def-level
;; only — recognised in a view body solely to reject it.
(def ^:private ui-ref-fqns                 #{'re-frame.freehand/ref})
(def ^:private react-use-effect-fqns       #{'re-frame.freehand.react/use-effect})
(def ^:private react-use-layout-effect-fqns #{'re-frame.freehand.react/use-layout-effect})
(def ^:private react-use-effect-event-fqns #{'re-frame.freehand.react/use-effect-event})
(def ^:private react-use-context-fqns      #{'re-frame.freehand.react/use-context})
(def ^:private react-use-id-fqns           #{'re-frame.freehand.react/use-id})
(def ^:private react-lazy-fqns             #{'re-frame.freehand.react/lazy})
;; :authored = the full authored head spelling used in diagnostics; :kind keyword
;; + runtime lowering target + call arity, keyed by fqn set.
(def ^:private react-hook-specs
  [{:fqns ui-ref-fqns                 :kind :ref          :runtime 're-frame.freehand.hooks/use-ref
    :min-args 0 :max-args 1 :authored "v/ref"}
   {:fqns react-use-effect-fqns       :kind :effect       :runtime 're-frame.freehand.hooks/use-effect
    :min-args 1 :max-args 2 :authored "react/use-effect" :deferred-cb 0 :deps-arg 1}
   {:fqns react-use-layout-effect-fqns :kind :layout-effect :runtime 're-frame.freehand.hooks/use-layout-effect
    :min-args 1 :max-args 2 :authored "react/use-layout-effect" :deferred-cb 0 :deps-arg 1}
   {:fqns react-use-effect-event-fqns :kind :effect-event :runtime 're-frame.freehand.hooks/use-effect-event
    :min-args 1 :max-args 1 :authored "react/use-effect-event" :deferred-cb 0}
   {:fqns react-use-context-fqns      :kind :context      :runtime 're-frame.freehand.hooks/use-context
    :min-args 1 :max-args 1 :authored "react/use-context"}
   {:fqns react-use-id-fqns           :kind :id           :runtime 're-frame.freehand.hooks/use-id
    :min-args 0 :max-args 0 :authored "react/use-id"}])

(defn- react-hook-spec-for
  "The react-hook spec `head` (an unshadowed symbol) resolves to, or nil."
  [e* head]
  (some (fn [spec] (when (env/resolves-to? e* head (:fqns spec)) spec))
        react-hook-specs))
(def ^:private frame-root-fqns #{'re-frame.freehand/frame-root})
(def ^:private frame-provider-fqns #{'re-frame.freehand/frame-provider})

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

(def ^:private if-let-family
  "The admitted conditional-binder family (rf2-u53yy.4): if-let / when-let /
  if-some / when-some, keyed by the UNQUALIFIED core name. `:some?` selects the
  `some?`-based nil test over truthiness; `:else?` selects the two-arm
  if-let/if-some shape over the single-body when-let/when-some shape. This map is
  the source of truth for `if-let-family-fqns`; admission is RESOLVER-confirmed
  (see `if-let-family-config`), never keyed on the raw spelling. The set is
  CLOSED — case in expression position, condp, doto and the rest stay outside the
  grammar (Spec 004 §Expression positions); additions are S1e roster changes."
  {'if-let    {:some? false :else? true}
   'if-some   {:some? true  :else? true}
   'when-let  {:some? false :else? false}
   'when-some {:some? true  :else? false}})

(def ^:private if-let-family-fqns
  "The four admitted binders' fully-qualified `clojure.core` / `cljs.core` names
  mapped to their desugar config. Admission is RESOLVER-confirmed against this
  set (rf2-u53yy.4 audit repair): a same-spelled user macro/var resolves to a
  different fqn and is NOT admitted (it fails loudly as an unaudited macro), a
  qualified core binder (`clojure.core/if-let`) IS admitted, and a local shadow
  yields no resolution at all — the spelling alone never decides admission."
  (into {}
        (mapcat (fn [[s cfg]]
                  [[(symbol "clojure.core" (name s)) cfg]
                   [(symbol "cljs.core" (name s)) cfg]]))
        if-let-family))

(defn- if-let-family-config
  "The desugar config {:some? :else?} for `head` when it is the core
  if-let-family binder, else nil (rf2-u53yy.4 audit repair). Admission is
  RESOLVER-confirmed, never keyed on the raw spelling alone:

    - a LOCAL shadow (`head` in `(:locals e)`) is never admitted — it falls
      through to an ordinary call;
    - a spelling whose unqualified name is in the family and that resolves to a
      NON-core var (a `:refer`d look-alike user macro, `my.ns/if-let`) is NOT
      admitted — it fails loudly as an unaudited macro;
    - a spelling that resolves to a core binder var (bare `if-let` or qualified
      `clojure.core/if-let`) IS admitted;
    - a BARE core name whose host offers no resolution (a data-only emit env)
      falls back to the name — the plain core spelling with no resolver is
      overwhelmingly the core macro, matching how the other control heads are
      admitted in that path.

  The caller passes an env whose `:locals` already carry the lexical scope."
  [e head]
  (when (and (symbol? head) (not (contains? (:locals e) head)))
    (when-let [cfg (get if-let-family (symbol (name head)))]
      (let [r (env/resolve-sym e head)]
        (cond
          (contains? if-let-family-fqns (:fqn r))        cfg
          (and (nil? r) (nil? (namespace head)))         cfg)))))

(defn- core-sym
  "A host-qualified `clojure.core` / `cljs.core` symbol. A namespace-qualified
  symbol cannot be shadowed by a user local, so a compiler-GENERATED core call —
  the some?-binder's nil test `(core/not= temp nil)` — keeps core semantics even
  under a hostile `(let [not= (constantly true)] (if-some …))`. `not=` is a plain
  core function on both hosts (unlike `some?`/`nil?`, which are cljs.core macros
  and would be rejected by the expression grammar on re-analysis)."
  [e nm]
  (symbol (if (= :cljs (:host e)) "cljs.core" "clojure.core") nm))

(defn- binder-temp
  "A deterministic, reserved temp symbol for the if-let-family desugar. Stable
  per lexical `anchor` (so template identity is stable across compiles) and
  never a user local (the reserved `rf-ui-` prefix). The temp holds the raw
  init value so the truthiness / some? test examines it BEFORE the pattern
  destructures — the exact hygiene clojure.core's own if-let uses (a
  destructuring pattern must never be tested for truthiness)."
  [anchor]
  (symbol (str "rf-ui-binder-" (fingerprint/digest "bnd1-" anchor))))

(defn- desugar-if-let
  "Desugar one if-let/when-let/if-some/when-some form into the analyzer's own
  let + if over a reserved temp — mirroring clojure.core's expansions, but using
  ONLY host-safe generated semantics (rf2-u53yy.4 audit repair): the conditional
  is `if` (a special form, so a user local named `when`/`if` cannot capture it),
  the some?-variants' nil test is a host-qualified core `(not= temp nil)`
  (un-shadowable by a user local, and — unlike `some?`/`nil?`, which are cljs.core
  macros — a plain function the expression grammar accepts on re-analysis), and
  the single-body when-* shape lowers to `(if test body nil)` rather than a
  generated `when`. `config` is the resolver-confirmed
  {:some? :else?} for `head`. The init is an ordinary evaluated expression (it
  may own a finite reactive site); the pattern binds into the then/body branch
  ONLY; the else branch never sees it. Both grammar tiers then reuse their
  EXISTING let/if machinery on the result, so the family inherits the SAME scope
  threading, destructuring, and reactive-escape rejection as a hand-written let +
  if — no runtime interpreter, no dynamic site, no open macro system. Fails with
  the shared bad-let/bad-if ids on a malformed binding vector or branch arity."
  [e head config temp form]
  (let [{:keys [some? else?]} config
        [_ bindings & body] form]
    (when-not (and (vector? bindings) (= 2 (count bindings)))
      (env/fail! e :rf.ui.compile/bad-let
                 (str head " needs a single binding pair: (" head
                      " [pattern init] " (if else? "then else?" "body …") ")")
                 {:form form}))
    (let [[pattern init] bindings
          test  (if some? (list (core-sym e "not=") temp nil) temp)
          inner (fn [& tail] (apply list 'let [pattern temp] tail))]
      (if else?
        (do
          (when-not (<= 1 (count body) 2)
            (env/fail! e :rf.ui.compile/bad-if
                       (str head " takes a then and an optional else after the "
                            "binding: (" head " [pattern init] then else?)")
                       {:form form}))
          (list 'let [temp init]
                (list 'if test (inner (first body)) (second body))))
        (list 'let [temp init]
              (list 'if test (apply inner body) nil))))))

(defn- fn-form? [f]
  (and (seq? f) (contains? #{'fn 'fn* 'clojure.core/fn 'cljs.core/fn} (first f))))

(defn- host-portable-argv?
  "The raw `fn*` special form binds only DISTINCT simple (unqualified) symbols,
  with an optional single trailing `& rest`. Applied ONLY to a raw `fn*`
  initializer: its parameters are what the host compiler binds directly, so
  destructuring, non-symbol/qualified parameters, and malformed `&` forms are
  host-illegal (and a map argv otherwise reaches binding-plan as a raw
  IllegalArgumentException). Macro `fn` / source `letfn` legally destructure —
  their expansion owns that grammar — so they keep the looser argv shape.

  Distinctness (`[x x]`, `[x & x]`, `[x y x]`) is the rf2-wnhbm seam. Both
  hosts *compile* a duplicate parameter, but by different mechanisms — the JVM
  shadows the earlier binding, ClojureScript gensym-renames the later one to
  `x__$1` — and this analyzer's own lexical model cannot represent either: it
  threads scope as a SET (`env/binding-syms`), so `[x x]` collapses to one
  local and a reactive-escape check on the second `x` silently reads the
  first's scope. A duplicate parameter is never intentional, so the honest
  bounded rule is to reject it rather than analyze a form we cannot model."
  [argv]
  (and (vector? argv)
       (let [[fixed tail] (split-with #(not= '& %) argv)]
         (and (every? simple-symbol? fixed)
              (case (count tail)
                0 true                            ; no variadic marker
                2 (simple-symbol? (second tail))  ; `& rest`: exactly one rest sym
                false)                            ; bare `&`, or `& a b`
              (let [bound (cond-> (vec fixed) (seq tail) (conj (second tail)))]
                (= (count bound) (count (distinct bound))))))))

(defn- host-arities-compatible?
  "Bounded arity-overload check for a raw `fn*`: the host rejects two overloads
  of the same fixed arity, more than one variadic overload, or a fixed overload
  that reaches into the variadic overload's arity range. (This is the narrow
  host-portability seam, NOT a general Clojure arity grammar.)

  The fixed-vs-variadic boundary (rf2-wnhbm) is where the two hosts stop
  agreeing, so it cannot be left to \"the host compiler\" — there are two of
  them. Writing `v` for the variadic overload's REQUIRED count (its parameters
  before `&`, so its emitted parameter count is `v + 1`) and `f` for a fixed
  overload's arity:

    f <= v      both hosts compile it, identically — legal.
    f == v + 1  the JVM rejects (`Can't have fixed arity function with more
                params than variadic function`); ClojureScript compiles it with
                BOTH a :variadic-max-arity and an :overload-arity
                (\"Can't have 2 overloads with same arity\") warning, emitting a
                dispatch that silently drops the fixed overload.
    f >  v + 1  the JVM rejects; ClojureScript warns :variadic-max-arity and
                emits the same broken dispatch.

  So every fixed arity must be strictly less than the variadic overload's
  parameter count — equivalently `f <= v`. That single rule is exactly where
  the JVM's hard error and ClojureScript's warnings both begin, which is what
  makes the accept/reject verdict host-portable."
  [argvs]
  (let [variadic?   (fn [argv] (boolean (some #(= '& %) argv)))
        required    (fn [argv] (count (take-while #(not= '& %) argv)))
        variadics   (filter variadic? argvs)
        fixed       (map required (remove variadic? argvs))]
    (and (<= (count variadics) 1)
         (= (count fixed) (count (set fixed)))
         (or (empty? variadics)
             (let [v (required (first variadics))]
               (every? #(<= % v) fixed))))))

(defn- fn-init-shape?
  "True when `init` is a structurally well-formed fn/fn* form — the only legal
  shape for a HOST letfn* binding initializer. Bounded grammar check, not a full
  semantic one: it guards `rw-fn` against a malformed body (a non-vector arity
  otherwise throws a raw host `Don't know how to create ISeq` exception) and
  rejects a non-fn initializer such as a bare `42` up front. Accepted shapes are
  `(fn <name>? [argv] body*)` (single arity) and `(fn <name>? ([argv] body*)+)`
  (one or more arity lists); every argv MUST be a vector.

  A RAW `fn*` (the host special form — not the `fn` macro) additionally binds
  only host-portable parameters (`host-portable-argv?`) with host-compatible
  arities (`host-arities-compatible?`), and its optional internal name must be
  a SIMPLE symbol: the host compiler requires it, and without these a
  destructuring/qualified/malformed argv is either silently accepted here only
  to crash the later host compiler, or reaches binding-plan as a raw
  IllegalArgumentException. A qualified internal name is the sharpest of these
  (rf2-wnhbm): the JVM accepts `(fn* foo/bar ([x] x))`, while ClojureScript
  compiles it to `function cljs$user$foo.bar(x){…}` — not valid JavaScript, a
  parse-time SyntaxError far downstream of this analyzer. Macro `fn` / source
  `letfn` legally destructure, so their argv shape is left to their own
  expansion."
  [init]
  (and (fn-form? init)
       (let [raw?    (= 'fn* (first init))
             fname   (when (symbol? (second init)) (second init))
             tail    (cond-> (rest init) fname rest)
             arities (cond
                       (vector? (first tail)) (list tail)   ; single arity
                       (and (seq tail)
                            (every? #(and (seq? %) (vector? (first %))) tail))
                       tail                                  ; arity lists
                       :else nil)]
         (boolean
          (and arities
               (or (not raw?)
                   (let [argvs (map first arities)]
                     (and (or (nil? fname) (simple-symbol? fname))
                          (every? host-portable-argv? argvs)
                          (host-arities-compatible? argvs)))))))))

(defn- raw-form? [e f]  (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-raw-fqns)))
(defn- html-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-html-fqns)))
(defn- raw-fn-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-raw-fn-fqns)))
(defn- spread-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-spread-fqns)))
(defn- spread-safe-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-spread-safe-fqns)))
(defn- ui-event-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-event-fqns)))
(defn- ui-handler-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-handler-fqns)))
(defn- render-fn-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-render-fn-fqns)))
(defn- slot-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-slot-fqns)))
(defn- error-boundary-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) error-boundary-fqns)))
(defn- client-only-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) client-only-fqns)))
(defn- presence-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) presence-fqns)))

;; ---------------------------------------------------------------------------
;; Expression rewriting — lexical site indexing + loop finiteness
;; ---------------------------------------------------------------------------

(def ^:private runtime-sub-fqn 're-frame.freehand.reactive/sub-read)
(def ^:private runtime-frame-ops-fqn 're-frame.freehand.frames/frame-ops)
(def ^:private runtime-local-fqn 're-frame.freehand.hooks/local-state)
(def ^:private runtime-effect-value-fqn 're-frame.freehand.hooks/effect-value)
(def ^:private runtime-effect-connect-fqn 're-frame.freehand.hooks/effect-connect)
(def ^:private runtime-dispatch-fn-fqn 're-frame.freehand.hooks/dispatch-fn)
(def ^:private deferred-scope ::deferred-scope)
(def ^:private transparent-macro-fqns
  "Closed macro set whose arguments are host-independent expression slots,
  with no user-authored binders. Preserve their spelling while recursively
  lowering explicit sub/frame calls."
  (into #{}
        (mapcat (fn [s] [(symbol "clojure.core" (name s))
                         (symbol "cljs.core" (name s))]))
        '[or and when when-not cond -> ->> some-> some->> cond-> cond->>]))

(defn- deferred-expr-root?
  "Expression roots whose value is evaluated by a later host callback, not by
  the view's render capture. `sub`/`frame` below these roots would be
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
              (with-meta (list 're-frame.freehand/sub (project (nth x 2))) (meta x))

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

(defn- site-source-coord
  "The authored `{:file :line :column}` for a manifest site whose form is
  `form` — its OWN reader position when the reader anchored it, else the
  declaration's.

  TOTAL and never invented. Every roster entry carries a coordinate, so a
  manifest fact and a build-log line can name the same lexical position;
  and the coordinate is always one a reader really produced. The reader
  anchors lists on both hosts and macro-generated templates carry no
  metadata at all, so a site whose form was not anchored inherits its
  enclosing DECLARATION's position — the widest true statement available —
  rather than a fabricated line the source would not agree with.

  It is the AUTHORED coordinate and nothing else. The template `:path`
  beside it is the deterministic occurrence coordinate and answers a
  different question; neither is derivable from the other."
  [e form]
  (let [m    (meta form)
        base (:source e)]
    (cond-> (select-keys base [:file :line :column])
      (:line m)   (assoc :line (:line m))
      (:column m) (assoc :column (:column m)))))

(declare rewrite-expr)

(defn- map-path-token [x]
  (fingerprint/digest "ep1-" x))

(declare reactive-macro-reference-kind)

(defn- reactive-call-kind
  "Return :sub/:frame when `x` contains an unshadowed resolved
  reactive CALL. Quoted data is never executable. This scanner is
  deliberately about calls, not bare symbols, so a binding pattern may
  still introduce a local named `sub` or `frame`."
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
        (env/resolves-to? e* x frame-fqns) :frame)
      (map? x) (or (some #(reactive-macro-reference-kind e % locals) (keys x))
                   (some #(reactive-macro-reference-kind e % locals) (vals x)))
      (coll? x) (some #(reactive-macro-reference-kind e % locals) x)
      :else nil)))

(defn- bare-reactive-reference-kind
  "Return :sub/:frame for an unquoted BARE reactive var reference. A
  direct `(sub ...)`/`(frame)` head is not bare—the rewriter
  owns it—but a threading step such as `(-> query sub)` would become an
  unindexed call only after macro expansion and must fail loudly."
  [e x locals]
  (let [e* (update e :locals into locals)]
    (cond
      (and (seq? x) (= 'quote (first x))) nil
      (and (symbol? x) (not (contains? locals x)))
      (cond
        (env/resolves-to? e* x sub-fqns) :sub
        (env/resolves-to? e* x frame-fqns) :frame)
      (seq? x)
      (let [h (first x)
            direct? (and (symbol? h) (not (contains? locals h))
                         (or (env/resolves-to? e* h sub-fqns)
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
  what every escape diagnostic must recommend. `sub` reads a query, `frame`
  takes no argument. Kept as one function so no diagnostic can drift into
  recommending an invalid form."
  [kind]
  (case kind
    :sub   "(sub query)"
    :frame "(frame)"))

(defn- reactive-authoring-var-kind
  "Return :sub/:frame when `sym` is an unquoted, unshadowed reference to
  a public reactive authoring var. Such a var is sound ONLY as a compiler-owned
  DIRECT CALL HEAD — the rewriter consumes those heads (and lowers them to an
  indexed runtime site) before this leaf check runs — so a bare reference that
  reaches any other, value-flow, position has escaped the manifest. `env` must
  already carry the ambient lexical locals so a local shadow resolves to nil."
  [env sym]
  (let [{:keys [fqn]} (env/resolve-sym env sym)]
    (cond
      (contains? sub-fqns fqn)   :sub
      (contains? frame-fqns fqn) :frame)))

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
      (or (= k :as) (= k :or) (bp/key-group-kw? k)) nil

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
                    " — re-frame.freehand/" (name kind) " is a reactive authoring "
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
    ;; established sequentially (`bp/assoc-binding-units` = the host `bes` order):
    ;; each key's default AND each explicit lookup-key expression is evaluated
    ;; against the scope BEFORE that key's own local is bound — the local is
    ;; added only after, and never in an order the host would not use. Only an
    ;; `:explicit?` unit's `:key` is a user-written expression that can hide a
    ;; reactive-authoring escape; a group local's key is a produced literal.
    (do
      (check-portable-map-shape! e pattern)
      (let [defaults (:or pattern)
            scope    (cond-> scope (:as pattern) (conj (:as pattern)))]
        (reduce
         (fn [scope {:keys [local-pattern key explicit?]}]
           (when explicit?
             (reject-binding-escape! e scope "destructuring lookup-key expression"
                                     pattern key))
           (when (and (symbol? local-pattern) (contains? defaults local-pattern))
             (reject-binding-escape! e scope "destructuring :or default"
                                     pattern (get defaults local-pattern)))
           (check-binding-scope! e scope local-pattern))
         scope
         (bp/assoc-binding-units pattern))))

    :else scope))

(defn reject-reactive-binding!
  "Reject a reactive authoring escape reaching an EVALUATED destructuring
  expression — an executable `(sub …)`/`(frame)` call OR a bare
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
  host `destructure` `bes` order (`:as` excluded — it binds first). One plan
  (`bp/assoc-binding-units`), so a dependent `:or` default resolves to the same
  symbol on both hosts and no public authoring var can survive through a
  reordered default. Returns nil for a non-map header (`[sym]` ≡ `{:as sym}`)."
  [binding-form]
  (when (map? binding-form)
    (bp/binding-order binding-form)))

(defn- impure-slot-fail!
  "The didactic rejection for a reactive read inside a `v/render-fn` slot
  body — a slot body is a pure render fragment; the reactive/dispatch surface
  belongs to the owning view or a mounted defview."
  [e verb form]
  (env/fail! e :rf.ui.compile/impure-slot-body
             (str "(" verb " …) inside a v/render-fn slot body — a slot body is "
                  "a PURE render fragment; sub / frame (and dispatch / "
                  "hooks) are not permitted. Read the value in the OWNING view and "
                  "pass its committed value into the slot's arguments; a stateful "
                  "part MOUNTS a defview that owns its own state")
             {:form form}))

(defn rewrite-expr
  "Rewrite an opaque expression, lowering resolved unshadowed `(sub q)` calls
  to the serialized two-argument runtime shape and recording stable lexical
  sub ids. The returned form MUST be threaded into the AST.

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
             ;; (→ `rw-fn`) makes a render-time `sub`/`frame` inside it illegal,
             ;; while a visible `sub` in the OUTER body still lowers to a site.
             (let [bindings (second f)]
               (if-not (and (vector? bindings) (even? (count bindings)))
                 (env/fail! e :rf.ui.compile/bad-let
                            (str "letfn* needs a vector of an even number of flat "
                                 "name/initializer bindings [name init …]")
                            {:form f})
                 (let [pairs (partition 2 bindings)
                       names (map first pairs)]
                   ;; Bound the flat grammar BEFORE blindly rewriting each
                   ;; initializer: a name must be a simple (unqualified) symbol
                   ;; — the host only defaults simple-symbol locals — and each
                   ;; initializer must be a well-formed fn/fn* form, else a bare
                   ;; value (e.g. `42`) slips through to the host compiler and a
                   ;; malformed arity throws a raw ISeq exception inside rw-fn.
                   (doseq [[nm init] pairs]
                     (when-not (simple-symbol? nm)
                       (env/fail! e :rf.ui.compile/bad-let
                                  (str "letfn* binding names must be simple "
                                       "(unqualified) symbols; got " (pr-str nm))
                                  {:form f}))
                     (when-not (fn-init-shape? init)
                       (env/fail! e :rf.ui.compile/bad-let
                                  (str "each letfn* initializer must be a fn/fn* "
                                       "form with a [argv] body or ([argv] body …) "
                                       "arity lists; got " (pr-str init))
                                  {:form f})))
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
                               (str "(v/render-fn …) is a render-slot callback "
                                    "value — legal ONLY as a component call-site "
                                    "prop value or a v/slot argument, never as a "
                                    "plain expression. The library invokes it "
                                    "through v/slot")
                               {:form f})

                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head ui-slot-fqns))
                    (env/fail! e :rf.ui.compile/bad-slot
                               (str "(v/slot render-fn-value arg…) renders content "
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
                                               :source-coord (site-source-coord e f)
                                               :path (:path e) :expr-path (vec p)})
                       (with-same-meta f (list runtime-sub-fqn sid query))))

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
                                                     :source-coord (site-source-coord e f)
                                                     :path (:path e)
                                                     :expr-path (vec p)})
                        (with-same-meta f (list runtime-frame-ops-fqn))))

                    ;; (local init) — view-local ephemera, a React useState hook.
                    ;; Legal ONLY as a binding value in a defview's UNCONDITIONAL
                    ;; top region (React hooks run once per render in fixed order):
                    ;; not in a loop / branch / deferred callback / render-fn slot.
                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head local-fqns))
                    (do
                      (when-not (= 2 (count f))
                        (env/fail! e :rf.ui.compile/unsupported-form
                                   "(local init) takes exactly one initial-value argument"
                                   {:form f}))
                      (when (or (nil? (:self-id e))
                                (:in-loop? e)
                                (contains? locals deferred-scope)
                                (not (:hooks-region? e)))
                        (if (:in-render-fn? e)
                          (impure-slot-fail! e "local" f)
                          (env/fail! e :rf.ui.compile/hook-misplaced
                                     (str "(local init) is a host hook — legal ONLY as a "
                                          "binding value in a defview's UNCONDITIONAL top "
                                          "region: (let [[value set! update!] (local init)] "
                                          "…). It cannot run in a loop, branch, deferred "
                                          "callback, render-fn slot, or root expression "
                                          "(host hooks run once per render in fixed order)")
                                     {:form f})))
                      (let [sid (lexical-site-id e :local f p)]
                        (env/next-hook-ordinal! e) ; a local advances the shared body-hook ordinal
                        (env/add-site! e :locals {:sid sid :path (:path e)
                                                  :expr-path (vec p)})
                        (with-same-meta
                          f
                          (list runtime-local-fqn
                                (rw (second f) locals (conj p 1))))))

                    ;; Host hooks — the substrate `v/ref` and the re-frame.freehand.react
                    ;; interop hooks (use-effect / use-layout-effect /
                    ;; use-effect-event / use-context / use-id) — value-position
                    ;; host hooks obeying the SAME position law as `local`: legal
                    ;; only where they evaluate unconditionally, once per render
                    ;; (the straight-line top region — an outer let binding).
                    ;; Lowered to hooks/*.
                    (and (symbol? head) (not (contains? locals head))
                         (react-hook-spec-for e* head))
                    (let [{:keys [kind runtime min-args max-args authored deps-arg]}
                          (react-hook-spec-for e* head)
                          call-args (rest f)
                          argc      (count call-args)]
                      (when (or (< argc min-args) (> argc max-args))
                        (env/fail! e :rf.ui.compile/unsupported-form
                                   (str "(" authored " …) takes "
                                        (if (= min-args max-args)
                                          (str min-args)
                                          (str min-args "–" max-args))
                                        " argument(s); got " argc)
                                   {:form f}))
                      (when (or (nil? (:self-id e))
                                (:in-loop? e)
                                (contains? locals deferred-scope)
                                (not (:hooks-region? e)))
                        (if (:in-render-fn? e)
                          (impure-slot-fail! e authored f)
                          (env/fail! e :rf.ui.compile/react-hook-misplaced
                                     (str "(" authored " …) is a host hook — legal ONLY "
                                          "where it evaluates unconditionally, once per "
                                          "render: the straight-line top region of a "
                                          "defview body (an outer let binding). It cannot "
                                          "run in a loop, branch, deferred callback, "
                                          "render-fn slot, or root expression — React's "
                                          "hook order must be static. Hoist it to the top "
                                          "of the view body, or extract a keyed child view")
                                     {:form f})))
                      (when (and deps-arg (> argc deps-arg)
                                 (not (vector? (nth call-args deps-arg))))
                        (env/fail! e :rf.ui.compile/react-hook-bad-deps
                                   (str "(" authored " setup deps): deps must be a "
                                        "literal vector (compared by rf= value equality); "
                                        "got " (pr-str (nth call-args deps-arg)))
                                   {:form f}))
                      (let [sid   (lexical-site-id e kind f p)
                            order (env/next-hook-ordinal! e)
                            args* (map-indexed
                                   (fn [i a]
                                     (if (and deps-arg (= i deps-arg))
                                       ;; deps vector: walk each slot in ambient
                                       ;; scope (render-time values).
                                       (with-same-meta a
                                         (mapv (fn [j d] (rw d locals (conj p (inc i) j)))
                                               (range) a))
                                       ;; setup fn / ctx / initial: ordinary
                                       ;; expressions (a setup fn is a deferred
                                       ;; callback — rw-fn rejects reactive sites).
                                       (rw a locals (conj p (inc i)))))
                                   call-args)]
                        (env/add-site! e :react {:sid sid :kind kind :order order
                                                 :path (:path e) :expr-path (vec p)})
                        (with-same-meta f (apply list runtime args*))))

                    ;; (react/lazy …) is DEF-LEVEL only — recognised in a body
                    ;; solely to reject it (per-render construction remount-loops).
                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head react-lazy-fqns))
                    (env/fail! e :rf.ui.compile/react-lazy-misplaced
                               (str "(react/lazy …) is DEF-LEVEL only — bind it at the top "
                                    "level: (def HeavyChart (react/lazy load-thunk "
                                    "{:fallback tpl})), then use the component as a foreign "
                                    "head [HeavyChart {…}]. Calling it inside a view body "
                                    "mints a new component type per render and remount-loops")
                               {:form f})

                    ;; (v/dispatch-fn) — the stable committed-frame dispatcher.
                    ;; A render-time site like (frame): finite, not in a loop or
                    ;; deferred callback. Its VALUE (a stable fn) is captured in
                    ;; the body and used from effect/foreign callbacks later.
                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head dispatch-fn-fqns))
                    (do
                      (when-not (= 1 (count f))
                        (env/fail! e :rf.ui.compile/unsupported-form
                                   (str "(v/dispatch-fn) takes no arguments — it returns "
                                        "the stable committed-frame dispatcher")
                                   {:form f}))
                      (when (or (nil? (:self-id e))
                                (:in-loop? e)
                                (contains? locals deferred-scope))
                        (if (:in-render-fn? e)
                          (impure-slot-fail! e "dispatch-fn" f)
                          (env/fail! e :rf.ui.compile/frame-in-loop
                                     (str "(v/dispatch-fn) must be a finite render-time "
                                          "site in a defview — it cannot run in a loop, "
                                          "deferred callback, raw-fn/ref body, or root "
                                          "expression. Capture it in the view body and use "
                                          "it from an (effect …) or foreign callback")
                                     {:form f})))
                      (let [sid (lexical-site-id e :dispatch-fn f p)]
                        (env/add-site! e :dispatch-fns {:sid sid :path (:path e)
                                                        :expr-path (vec p)})
                        (with-same-meta f (list runtime-dispatch-fn-fqn))))

                    ;; (effect …) is a leading STATEMENT, spliced before the
                    ;; final template by analyze-hooks-body. Reaching rw means it
                    ;; appeared as an ordinary expression (a binding value, an
                    ;; argument, a branch) — misplaced.
                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head effect-fqns))
                    (if (:in-render-fn? e)
                      (impure-slot-fail! e "effect" f)
                      (env/fail! e :rf.ui.compile/hook-misplaced
                                 (str "(effect …) is a host-effect STATEMENT — place it in a "
                                      "defview's UNCONDITIONAL top region (or a top-region "
                                      "let/do body) BEFORE the final template, never as an "
                                      "expression, branch, or deferred callback")
                                 {:form f}))

                   (fn-form? f) (rw-fn f locals p)
                   (contains? #{'let 'let* 'loop 'loop*} head) (rw-let f locals p)
                   (= 'letfn head)  (rw-letfn f locals p)
                   (= 'letfn* head) (rw-letfn* f locals p)
                   (= 'try head) (rw-try f locals p)

                   ;; if-let / when-let / if-some / when-some (rf2-u53yy.4):
                   ;; admitted conditional binders, RESOLVER-confirmed against the
                   ;; core binder vars (a same-spelled user macro is NOT admitted;
                   ;; a local shadow yields no resolution and falls through to an
                   ;; ordinary call). Desugar into the analyzer's own let + if —
                   ;; the position-aware binder machinery it already controls — so
                   ;; the init lowers a reactive site, the pattern's
                   ;; reactive-escape is rejected, and the deferred / loop position
                   ;; law all fall out of the existing rw-let / if recursion.
                   (if-let-family-config e* head)
                   (rw (desugar-if-let e head (if-let-family-config e* head)
                                       (binder-temp p) f)
                       locals p)

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

                   ;; A DEFERRED callback body (fn / v/event / raw-fn / event-arg)
                   ;; hosts no render-time reactive site — sub/frame are
                   ;; already illegal here (rejected above) — so there is nothing
                   ;; for lexical site analysis to protect. Opaque host macros
                   ;; (`..`, `doto`, `case` in expression position, …) are
                   ;; ordinary callback code: pass the form through verbatim
                   ;; (the admitted if-let family is handled above, never here).
                   ;; A reactive call
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
                   ;; never survive as a public `v/sub` with an empty manifest.
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
               ;; as a direct call head (`(sub q)`/`(frame)` → an
               ;; indexed runtime site) or rejected pre-expansion under a macro.
               ;; A bare `sub`/`frame` symbol here instead flows as a
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
                            (str "bare " (name kind) " reference — re-frame.freehand/"
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
     ;; A `v/render-fn` slot body is a DEFERRED render: it executes inside a
     ;; DIFFERENT view (the library seam that invokes the slot), so every
     ;; expression it walks is deferred — sub/frame there would be
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
  (let [s   (name head)
        ns* (namespace head)]
    ;; A head in the framework-reserved `:rf/*` scheme gets its OWN reject
    ;; (rf2-01zvu — the client half of the rf2-j81hs SS4 ruling). re-frame.freehand
    ;; classifies heads at COMPILE time, so it never painted the phantom the
    ;; runtime substrates did — but the generic arm below advises "write
    ;; :<name>", which for a reserved head tells the author to strip the
    ;; namespace and paint exactly that phantom. Same closed-vocabulary id;
    ;; the message is what distinguishes the arm.
    (when (and ns* (or (= "rf" ns*) (str/starts-with? ns* "rf.")))
      (env/fail! e :rf.ui.compile/bad-tag
                 (str "element head " head " is in the framework-reserved "
                      ":rf/* namespace, which is framework-owned (Conventions "
                      "§Reserved namespaces) — it cannot be an author element. "
                      "No :rf/* head has a client meaning "
                      "(:rf/suspense-boundary is a streaming-SSR marker, "
                      "server-only). Check the spelling, or use an unreserved "
                      "keyword if you meant a custom element.")
                 {:head head}))
    (when ns*
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

(defn- event-site-identity
  [e k form]
  {:sid          (lexical-site-id e :event form [:handler k])
   :site-index   (count (:events @(:sites e)))
   :view-id      (:self-id e)
   :source-coord (site-source-coord e form)
   :path         (:path e)})

(defn- add-event-site!
  [e identity site]
  (env/add-site! e :events (merge identity site))
  identity)

(defn- door-slot
  "This handler's FINAL-NORMALIZED prop slot — the form the door reads, so
  every spelling of one emitted handler prop is judged alike."
  [handler]
  (rules/caller-key-slot (:k handler)))

(defn- controlled-event-sync?
  "Is this site inside the controlled-input synchronous door?

  ASKED, never restated: the rule is
  [[re-frame.freehand.controlled/door?]], the ONE predicate the
  interpreted walk also asks at render time. That is what makes promotion
  parity a structural fact rather than a pair of lists someone has to keep
  in step — a field cannot silently change from synchronous to batched by
  gaining `{:compiled true}`, because there is nothing to diverge.

  It is a property of the whole native element, not of the handler in
  isolation, so it is applied after every prop has been classified. The
  SITE proof is static: the compiled classification names the callback's
  roster role, and the door takes the roles whose outcome is
  synchronously known to be one event vector or `nil` — a literal vector,
  an options map carrying one, or a `v/event` body. The prefix and
  payload stay runtime values."
  [tag controlled? handler]
  (controlled/door? {:tag         tag
                     :controlled? controlled?
                     :slot        (door-slot handler)
                     :role        (controlled/compiled-role (:classification handler))
                     :capture?    (:capture? handler)
                     :passive?    (:passive? handler)}))

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

(defn- roster-callback
  "The lowered value for one of the body-taking roster forms — the EXACT
  constructor call the interpreted `v/event` / `v/handler` macro expands
  to.

  A compiled body must lower a roster form to the ROSTER VALUE and not to
  the bare function it carries, because the runtime classifies the value
  PRESENT: [[re-frame.freehand.events/event-plan]] reads a bare fn as
  `:bare-fn`, whose firing arm applies the function and DISCARDS what it
  answered. A `v/event` lowered bare therefore dispatched nothing at all
  while the compiled site's own door verdict said `:event` — a silent
  no-op the build could not see and no diagnostic named (rf2-berc2). The
  constructor makes the two halves agree by construction: one value, one
  classification, one firing arm, whichever front end wrote it."
  [role f arity]
  (list 're-frame.freehand.events/callback role f arity))

(defn- analyze-ui-event-fn
  "The `(v/event [e] body…)` seq form, lowered to the `(fn [e] body…)` the
  runtime callback carries. One reader for the two positions a `v/event` is
  legal in — the whole handler, and one branch of a key-condition map — so the
  arity refusal, the walk and the loop-capture check cannot differ between
  them."
  [e k form]
  (let [[_ bindings & body] form]
    (when-not (and (vector? bindings) (= 1 (count bindings)))
      (env/fail! e :rf.ui.compile/bad-ui-event
                 (str "(v/event [e] body…) at " k " binds exactly the "
                      "native event and returns an event vector (or nil to "
                      "dispatch nothing); got " (pr-str bindings)
                      ". For imperative work with no dispatch, S3 adds "
                      "v/handler")
                 {:prop k :form form}))
    ;; A v/event handler is a SITE, like a literal vector: capturing a loop
    ;; binding needs per-row committed slots (its own bindings shadow, so they
    ;; are excluded from the capture check). Its body is a deferred callback,
    ;; so render-time sub/frame inside it are rejected by the fn walk.
    (let [binders (set (env/binding-syms bindings))
          form*   (walk-expr e [:handler k :ui-event]
                             (with-meta (apply list 'fn bindings body)
                               (meta form)))]
      (check-loop-capture! (update e :loop-syms #(reduce disj % binders))
                           (str "v/event handler at " k) form)
      form*)))

;; ---------------------------------------------------------------------------
;; The compiled key-condition event map
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Key-condition event maps, ruled by D007. The compiled tier reads
;; the SAME closed form the runtime normalizer reads — string keys, one level,
;; legal only on a key listener, each branch an existing dispatching value —
;; because a declaration that means one thing interpreted and another with
;; `{:compiled true}` is exactly the promotion break the compiled tier exists to
;; make impossible. The two rosters this rests on are ASKED rather than
;; restated: `events/key-slots` names the legal slots and `events/branch-options`
;; the legal branch keys, so there is one grammar with two front ends.

(defn- key-condition-map?
  "Is this literal map at an event position the exact-key CONDITION form rather
  than the listener OPTIONS map? Decided by the presence of a string key, the
  way `events/map-plan` decides it at render."
  [form]
  (boolean (some string? (keys form))))

(defn- analyze-key-branch
  "One branch of a compiled key-condition map, lowered to the value the
  emitters write into the map.

  A branch names exactly ONE intent, so the roster is the dispatching one: an
  event vector, an options map carrying `:event` and at most the two
  pre-dispatch mechanics, a `v/event`, or `nil`. A literal branch lowers to
  data; a `v/event` branch lowers to the roster callback its interpreted
  spelling expands to, so the runtime classifies one branch grammar whichever
  front end wrote it."
  [e k key-str form]
  (cond
    (nil? form) nil

    (vector? form) (analyze-event-vector! e k form)

    (and (map? form) (or (empty? form) (key-condition-map? form)))
    (env/fail! e :rf.ui.compile/bad-handler-options
               (str "the " (pr-str key-str) " branch at " k " is "
                    (if (empty? form) "an empty map" "another key-condition map")
                    " — the exact-key form is ONE level deep and every branch names "
                    "one intent. Nesting selects nothing: a keystroke carries a single "
                    "key. Write {\"Enter\" [:accept] \"Escape\" [:close]}, and let "
                    "(v/event [e] …) answer anything richer")
               {:prop k :key key-str :form form})

    (map? form)
    (let [unknown (remove events/branch-options (keys form))]
      (when (seq unknown)
        (env/fail! e :rf.ui.compile/bad-handler-options
                   (str "the " (pr-str key-str) " branch at " k " carries "
                        (str/join ", " (map pr-str (sort unknown)))
                        " — a branch is SELECTED after the keystroke arrives, so "
                        (pr-str (vec (sort events/branch-options)))
                        " is all it can still honour. :capture and :passive are decided "
                        "when the listener is attached to the node and :once retires the "
                        "whole site, every one of them before a key has been read. Drop "
                        "the option rather than have it silently do nothing")
                   {:prop k :key key-str :form form}))
      (when-not (vector? (:event form))
        (env/fail! e :rf.ui.compile/bad-handler-options
                   (str "the " (pr-str key-str) " branch at " k " is an options map and "
                        "needs a literal :event vector — {\"Enter\" {:event "
                        "[:domain/event args...] :prevent-default true}}")
                   {:prop k :key key-str :form form}))
      (assoc form :event (analyze-event-vector! e k (:event form))))

    (ui-event-form? e form)
    ;; The interpreted `v/event` macro expands to this exact constructor, so the
    ;; branch the runtime classifies is the same roster value in both modes.
    (roster-callback :event (analyze-ui-event-fn e k form) 1)

    :else
    (env/fail! e :rf.ui.compile/bad-handler-options
               (str "the " (pr-str key-str) " branch at " k " is not an intent — a key "
                    "branch is an event vector, an options map carrying :event, a "
                    "(v/event [e] …), or nil (dispatch nothing). A callback that is not "
                    "itself an intent, and anything the compiler cannot see, are outside "
                    "the one-level exact-key form")
               {:prop k :key key-str :form form})))

(defn- key-branch-data?
  "Is this LOWERED branch still DATA — an event vector, an options map, or
  `nil` — rather than the `v/event` constructor call only evaluation turns into
  a callback? The site's `:serializable?` fact is the conjunction over the
  branches, so one `v/event` branch makes the whole site opaque to the manifest
  exactly as a whole-handler `v/event` does."
  [branch]
  (not (seq? branch)))

(defn- analyze-key-map
  "The whole compiled key-condition map: its slot legality, then every branch.

  Legality is the runtime rule — a map that selects by `KeyboardEvent.key` is
  legal only where a key IS carried — raised HERE, at build, rather than at the
  first keystroke of a site that could never have fired."
  [e k form]
  (when-not (contains? events/key-slots (rules/caller-key-slot k))
    (env/fail! e :rf.ui.compile/bad-handler-options
               (str "a key-condition map at " k " — the exact-key form selects an intent "
                    "by KeyboardEvent.key, so it is legal only on :on-key-down and "
                    ":on-key-up. Put an ordinary click or input intent in an event vector "
                    "or an options map")
               {:prop k :form form}))
  (reduce-kv (fn [m key-str branch]
               (assoc m key-str (analyze-key-branch e k key-str branch)))
             {}
             form))

(defn analyze-handler
  "Classify one :on-* entry. -> {:k kw :name str :classification
  :vector|:options|:key-map|:fn|:dynamic :form form :capture? bool
  :hoistable? bool :serializable? bool}"
  [e k form]
  (when (:in-render-fn? e)
    (env/fail! e :rf.ui.compile/impure-slot-body
               (str "an event handler (" k ") inside a v/render-fn slot body — a "
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
      ;; A MAP is the one shape two closed forms share, and it is split HERE
      ;; exactly as `events/map-plan` splits it at render: options first, then
      ;; the string-keyed exact-key form, with the empty map and the mixed map
      ;; named as the authoring errors they are. Same order, same three
      ;; outcomes, so `{:compiled true}` cannot change which form a map IS.
      (cond
        (empty? form)
        (env/fail! e :rf.ui.compile/bad-handler-options
                   (str "an empty map at " k " is neither an options map — which "
                        "states its intent under :event — nor a key-condition map, "
                        "which names at least one exact KeyboardEvent.key branch. "
                        "Write {:event [:domain/event args...]} or "
                        "{\"Enter\" [:accept] \"Escape\" [:close]}")
                   {:prop k :form form})

        (not (key-condition-map? form))
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

        (every? string? (keys form))
        (let [form* (analyze-key-map e k form)
              data? (every? key-branch-data? (vals form*))]
          ;; A key-condition map carries no whole-listener option — the roster
          ;; that would name one is refused per branch — so the attachment lane
          ;; is the ordinary one and there is nothing for the door to weigh. It
          ;; is never hoisted: one committed site owns the whole selection, and
          ;; a hoisted constant would be a second one.
          (add-event-site! e identity
                           {:prop k :handler (if data? form* :opaque)
                            :classification :key-map :serializable? data?})
          (merge identity
                 {:k k :name nm :classification :key-map :form form*
                  :capture? false :passive? false :hoistable? false
                  :serializable? data?}))

        :else
        (env/fail! e :rf.ui.compile/bad-handler-options
                   (str "the map at " k " carries exact-key branch"
                        (when (next (filter string? (keys form))) "es") " "
                        (str/join ", " (map pr-str (sort (filter string? (keys form)))))
                        " beside the listener option"
                        (when (next (remove string? (keys form))) "s") " "
                        (str/join ", " (map pr-str (sort (remove string? (keys form)))))
                        " — the exact-key form and the options map are SEPARATE "
                        "closed forms. State per-key intents as {\"Enter\" [:accept] …} "
                        "and whole-listener options in an options map, never both in "
                        "one map")
                   {:prop k :form form}))

      (ui-event-form? e form)
      ;; The WHOLE-handler `v/event`, lowered to the same roster constructor a
      ;; key-condition BRANCH lowers to, and to the same one the interpreted
      ;; `v/event` macro expands to. Nothing between here and the DOM wraps it,
      ;; so the value the analyzer writes IS the value `events/event-plan`
      ;; classifies — and a bare fn there is the `:bare-fn` arm, which applies
      ;; the body and throws its event vector away (rf2-berc2).
      (let [form* (roster-callback :event (analyze-ui-event-fn e k form) 1)]
        (add-event-site! e identity
                         {:prop k :handler :opaque
                          :classification :ui-event :serializable? false})
        (merge identity
               {:k k :name nm :classification :ui-event :form form*
                :capture? false :hoistable? false :serializable? false}))

      (ui-handler-form? e form)
      ;; The explicit imperative committed callback (`v/handler`) at a DOM
      ;; :on-* site — the visible spelling of the bare-fn shorthand. Its body
      ;; runs after commit; the native event binds its parameter and its return
      ;; is IGNORED (no dispatch of a returned vector — that is `v/event`).
      (let [[_ bindings & body] form]
        (when-not (and (vector? bindings) (not (some #{'&} bindings))
                       (= 1 (count bindings)))
          (env/fail! e :rf.ui.compile/bad-ui-handler
                     (str "(v/handler [x] body…) at " k " binds exactly the "
                          "invoker's argument (the native event at a DOM site) "
                          "and does imperative work; got " (pr-str bindings)
                          ". To DISPATCH a vector, use v/event")
                     {:prop k :form form}))
        (let [binders (set (env/binding-syms bindings))
              ;; The roster constructor, for the reason the `v/event` arm above
              ;; gives. `v/handler` and a bare fn happen to FIRE alike, so the
              ;; drop here is not a lost dispatch — it is the recorded fact: a
              ;; bare fn records `{:rf.ui/opaque :fn}` on the structural tree
              ;; where the interpreted twin records `{:rf.ui/opaque :v/handler}`.
              ;; One spelling, one roster value, one recorded marker.
              form*   (roster-callback
                       :handler
                       (walk-expr e [:handler k :ui-handler]
                                  (with-meta (apply list 'fn bindings body)
                                    (meta form)))
                       1)]
          (check-loop-capture! (update e :loop-syms #(reduce disj % binders))
                               (str "v/handler at " k) form)
          (add-event-site! e identity
                           {:prop k :handler :opaque
                            :classification :handler :serializable? false})
          (merge identity
                 {:k k :name nm :classification :handler :form form*
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
  "-> {:base-str str :flags [[name expr]...] :dyn form|nil :static? bool
       :sugar [str…] :runtime form|nil}
  Sugar classes first (source order), then the explicit :class form;
  flag-map entries in lexicographic name order; no de-duplication.

  `:base-str` / `:flags` / `:dyn` are the SPLIT plan — literal names folded
  at compile time, the rest joined at render — which the React emitter
  lowers. `:sugar` / `:runtime` are the WHOLE plan: the sugar names, and
  one form evaluating to the authored `:class` value with its expressions
  rewritten. The structural emitter takes the whole plan because the
  structural tier's class rule (`node/class-string`) is the same rule the
  interpreted walk applies, so a promoted declaration composes classes
  through one implementation rather than two agreeing ones. It matters for
  exactly the shape the split plan orders differently: a flag map mixing
  literal and computed entries sorts ALL its truthy names together, not
  literals-then-computed. `:runtime` is nil when the value is wholly
  static, where the two plans cannot differ."
  [e sugar form]
  (let [base (vec sugar)]
    (cond
      (nil? form)
      {:base-str (str/join " " base) :flags [] :dyn nil
       :sugar base :runtime nil :static? true}

      (or (string? form) (keyword? form))
      {:base-str (str/join " " (conj base (if (keyword? form) (name form) form)))
       :flags [] :dyn nil :sugar base :runtime nil :static? true}

      (and (vector? form) (every? #(or (string? %) (keyword? %) (nil? %)) form))
      {:base-str (str/join " " (into base (keep #(when % (if (keyword? %) (name %) %))) form))
       :flags [] :dyn nil :sugar base :runtime nil :static? true}

      (map? form)
      (do
        (when-not (every? #(or (keyword? %) (string? %)) (keys form))
          (env/fail! e :rf.ui.compile/bad-class
                     ":class flag-map keys must be literal names (string/keyword)"
                     {:form form}))
        ;; Every non-literal entry is walked EXACTLY ONCE — the walk records
        ;; lexical reactive sites, so a second pass over the same expression
        ;; would double-count them. Both plans are built from that one result.
        (let [walked (into {} (map (fn [[k v]]
                                     [k (if (literal-scalar? v)
                                          v
                                          (walk-expr e [:class :flag k] v))]))
                           form)
              {consts true exprs false}
              (group-by (fn [[k _]] (literal-scalar? (get form k))) walked)
              const-names (into base
                                (->> consts
                                     (keep (fn [[k v]] (when v (if (keyword? k) (name k) k))))
                                     sort))
               flags (->> exprs
                          (map (fn [[k v]] [(if (keyword? k) (name k) k) v]))
                          (sort-by first)
                          vec)
               static? (empty? flags)]
          {:base-str (str/join " " const-names) :flags flags :dyn nil
           :sugar base
           :runtime (when-not static? walked)
           :static? static?}))

      (vector? form) ; mixed literal/expr vector — runtime join in vector order
      (let [form* (with-meta
                    (mapv (fn [i x]
                            (if (literal-scalar? x)
                              x
                              (walk-expr e [:class :vector i] x)))
                          (range) form)
                    (meta form))]
        {:base-str (str/join " " base) :flags [] :dyn form*
         :sugar base :runtime form* :static? false})

      :else
      (let [dyn (walk-expr e [:class :dynamic] form)]
        {:base-str (str/join " " base) :flags [] :dyn dyn
         :sugar base :runtime dyn :static? false}))))

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

(defn- check-rejected-spelling!
  "Refuse a literal prop key whose EMITTED prop name is spoken for.

  The key is read the way the emitter below will read it — through
  `rules/caller-key-slot`, which is `(rules/react-prop-name (name k))` for an
  attribute, the very projection `:react-name` is built from. Checking the raw
  keyword instead left every other representation of one emitted prop outside
  the check: `:x/children` compiled happily and landed in React's reserved
  `children` slot, so a compiled declaration rendered content its structural
  twin did not carry (rf2-2bg4t). One canonical slot, and the refusal cannot
  drift from the emission.

  `:ref` is the one spelling of the reserved ref slot the grammar accepts —
  the analyzer routes it through `analyze-ref` and its host contract. An alias
  is not a second spelling of it: it would slip past that contract entirely and
  reach React's ref slot as an ordinary attribute, so it is refused here on the
  same 'one spelling per name' law as the roster.

  `:key` is refused on that law for a sharper reason. React's key is not a
  prop — the reconciler consumes it and it never reaches the DOM — so an alias
  routed into that slot would not misspell an attribute, it would change which
  element React considers the SAME element across renders. The failure mode is
  wrong element reuse: preserved DOM state landing on the wrong row, or a
  remount where none was intended. That is `:children`'s hazard class, not a
  misspelled attribute's, so `:key` keeps its one spelling (rf2-drpa3.93).
  The two remaining structural slots go the OTHER way — `:class` and `:style`
  are ordinary props, and their aliases are canonicalized and routed by
  `analyze-literal-props` below."
  [e k]
  (let [slot (rules/caller-key-slot k)]
    (cond
      (and (= rules/reserved-key-slot slot) (not= :key k))
      (env/fail! e :rf.ui.compile/rejected-prop-spelling
                 (str k " projects onto React's key, and :key is its one spelling — "
                      "a key is not a prop React writes to the DOM, it is the "
                      "identity the reconciler matches elements on, so an alias "
                      "would silently change which element React reuses. Use :key")
                 {:prop k})

      (and (= rules/reserved-ref-slot slot) (not= :ref k))
      (env/fail! e :rf.ui.compile/rejected-prop-spelling
                 (str k " projects onto React's reserved ref prop, and :ref is its "
                      "one spelling — an alias reaches the ref slot around the ref "
                      "contract entirely. Use :ref")
                 {:prop k})

      :else
      (when-let [replacement (rules/rejected-slot-replacement slot)]
        (env/fail! e :rf.ui.compile/rejected-prop-spelling
                   (str k " is not a prop — one spelling per name, ambiguities "
                        "removed. Use " replacement)
                   {:prop k})))))

(defn- analyze-ref [e form context]
  (when (:in-render-fn? e)
    (env/fail! e :rf.ui.compile/impure-slot-body
               (str ":ref inside a v/render-fn slot body — a slot body is a PURE "
                    "render fragment; a ref is a commit-phase host hook, which a "
                    "slot body may not own. Mount a defview that owns the ref")
               {:form form}))
  ;; :element, :view and :foreign share ONE ref contract: object refs preferred,
  ;; a callback ref MUST be explicit `(v/raw-fn f)` (React invokes it during
  ;; commit before the owning view's layout publication, so no committed-slot
  ;; promise), a bare fn is rejected on every tier — Spec 004 and the guide say
  ;; every callback ref is explicit, so the foreign seam is not an exception
  ;; (rf2-u53yy.3). An internal view accepts a forwarded ref only by declaring
  ;; `:ref` in its header; React 19 passes `:ref` as an ordinary prop, so the
  ;; call site just carries it into the props object.
  (case context
    (:element :view)
    (cond
      (fn-form? form)
      (env/fail! e :rf.ui.compile/bare-fn-ref
                 (str "bare fn in :ref — the bare-fn shorthand applies only to "
                      "native event properties, never refs. A callback ref must "
                      "be explicit: (v/raw-fn f); object refs are preferred")
                 {:form form})
      (raw-fn-form? e form)
      {:form (walk-expr e [:ref :raw-fn] (second form)) :raw-fn? true}
      :else
      {:form (walk-expr e [:ref context] form) :raw-fn? false})
    :foreign
    (if (fn-form? form)
      (env/fail! e :rf.ui.compile/bare-fn-ref
                 (str "bare fn in :ref at a foreign component — the bare-fn "
                      "shorthand applies only to native event properties, never "
                      "refs. A callback ref must be explicit: (v/raw-fn f); "
                      "object refs are preferred")
                 {:form form})
      {:form (walk-expr e [:ref :foreign] form) :raw-fn? false})))

(defn- analyze-literal-props
  "Analyze a DOM/custom element's LITERAL props map `m` (`properties` is the
  build's custom-element property set for the tag, nil for plain DOM). The
  owned map of a `(v/spread-safe owned caller)` form rides this same path, so
  a controlled owned site keeps the sync door. -> the props AST.

  The map's keys are read in their canonical author spelling first
  (`rules/canonical-attr-key`), so an ALIAS of a slot-owning key is analysed
  as that key: `:x/class` walks `analyze-class` and composes with the
  `.class#id` sugar, `:style` spelled under a namespace walks
  `analyze-style`. Rewriting here rather than at each use is what keeps the
  compiled tier's answer identical to the interpreted walk's, which applies
  the same canonicalization to a render-time key
  ([[re-frame.freehand.node/element]]'s `:dyn` fold) — one rule, two moments
  (rf2-drpa3.93). An ordinary key is untouched, namespace and all."
  [e tag-info properties m]
  (let [tag (:tag tag-info)]
    (doseq [k (keys m)]
      (when-not (keyword? k)
        (env/fail! e :rf.ui.compile/non-keyword-prop
                   (str "prop keys must be literal keywords; got " (pr-str k))
                   {:key k}))
      (check-rejected-spelling! e k))
    ;; The `#id` conflict, judged by the emitted SLOT the key projects onto
    ;; rather than by the raw `:id`. `:x/id` compiles to the same React
    ;; property the sugar does, and the compiled emitter writes the sugar
    ;; pair first, so the authored one won — while the structural lowering
    ;; carried both. Same projection `check-rejected-spelling!` above reads
    ;; (rf2-drpa3.101).
    (when-some [dup (and (:id tag-info)
                         (first (filter #(= rules/sugar-id-slot (rules/caller-key-slot %))
                                        (keys m))))]
      (env/fail! e :rf.ui.compile/id-sugar-conflict
                 (str "#" (:id tag-info) " sugar AND " dup " on " tag
                      " — two id spellings on one element is an ambiguity, "
                      "and this grammar removes ambiguities. Keep one"
                      (when (not= :id dup)
                        (str " (" dup " is :id written differently — a namespace is "
                             "dropped on the way to the DOM, so both compile to the "
                             "same prop)")))
                 {:tag tag :prop dup}))
    (let [m          (reduce-kv #(assoc %1 (rules/canonical-attr-key %2) %3) {} m)
              key-form   (get m :key)
              m*         (dissoc m :key :class :style :ref)
              ref-form   (get m :ref)
              on?        (fn [k] (str/starts-with? (name k) "on-"))
              handler-ks (filter on? (keys m*))
              attr-ks    (remove on? (keys m*))
              ;; The element half of the door, ASKED not restated — the same
              ;; normalized-slot rule the interpreted emitter asks over the
              ;; props it just wrote. A raw `:value`/`:checked` comparison
              ;; judged a SPELLING while the emitter judged a SLOT, so
              ;; `:x/value` reached React's `value` prop and made the node
              ;; controlled while the compiled site stayed batched — the one
              ;; promotion-parity break D009 names (rf2-drpa3.119).
              controlled? (controlled/controlled-props? (keys m))
              events0    (mapv #(analyze-handler e % (get m* %)) handler-ks)
              events     (mapv (fn [handler]
                                 (let [sync? (controlled-event-sync?
                                              tag controlled? handler)]
                                   (record-event-sync! e (:sid handler) sync?)
                                   ;; The advisory fires only where the door
                                   ;; COULD have opened — a controlled native
                                   ;; input, on a door attribute — so the one
                                   ;; thing left to fix is the handler's own
                                   ;; shape. A site the door can never admit
                                   ;; (`:on-before-input`, a `:div`) gets no
                                   ;; unactionable nag.
                                   ;;
                                   ;; What it reports is the loss of STATIC
                                   ;; EVIDENCE, not a change of dispatch lane.
                                   ;; The emitted element facts (tag,
                                   ;; controlled?, slot) are constants and
                                   ;; `controlled/door?` decides at COMMIT from
                                   ;; the runtime handler value, so a forwarded
                                   ;; vector reaches the door in both modes.
                                   ;; What it cannot do is prove anything
                                   ;; beforehand.
                                   (when (and controlled?
                                              (contains? controlled/controlled-tags tag)
                                              (controlled/door-slot? (door-slot handler))
                                              (not sync?))
                                     (env/warn!
                                      e
                                      {:id :rf.ui.compile/controlled-input-async-handler
                                       :msg (str (:k handler)
                                                 " is paired with a controlled "
                                                 ":value/:checked prop, but nothing "
                                                 "static pins this handler's class, so "
                                                 "the site is OPAQUE: its intent is "
                                                 "absent from the compiled manifest, and "
                                                 "no structural test or tool can say what "
                                                 "this control dispatches before it "
                                                 "fires. The door itself still opens — it "
                                                 "is decided at commit, from the runtime "
                                                 "handler value, in both modes; what is "
                                                 "lost is the proof. Keep the site "
                                                 "readable with a literal event vector, "
                                                 "an options map carrying one, or a "
                                                 "(v/event …) handler when the native "
                                                 "payload must be converted — and not "
                                                 "behind a :capture/:passive listener, "
                                                 "which is a different native attachment "
                                                 "lane")
                                       :form (:form handler)}))
                                   (assoc handler :sync? sync?)))
                               events0)
              attrs      (mapv (fn [k]
                                  (let [v (get m* k)
                                        n (name k)
                                        property? (boolean (and properties (properties k)))
                                        ;; The ONE attribute whose authored
                                        ;; value may be collection-shaped — a
                                        ;; native <select>'s value IS the list
                                        ;; of chosen option values. Asked of
                                        ;; the tag and the emitted slot, never
                                        ;; of `multiple`, which may be a
                                        ;; runtime value this tier cannot see.
                                        select-value? (controlled/select-value-slot? tag k)
                                        v* (if (literal-scalar? v)
                                             v
                                             (walk-expr e [:prop k] v))]
                                   ;; literal collection VALUES only — seq forms
                                   ;; are dynamic expressions (runtime-normalized)
                                   (when (and (or (vector? v) (map? v) (set? v))
                                              (not property?)
                                              ;; A LITERAL selection was the one
                                              ;; corner this guard still refused
                                              ;; while the interpreted twin
                                              ;; rendered it, and while a
                                              ;; COMPUTED selection compiled
                                              ;; (rf2-b6poy). It rides the same
                                              ;; runtime path a computed one
                                              ;; does, so nothing downstream
                                              ;; has to learn a new shape.
                                              (not (and select-value? (sequential? v))))
                                     (env/fail! e :rf.ui.compile/collection-attr-value
                                                (if select-value?
                                                  (str "collection value for " k " on "
                                                       "<select> — a select's value is the "
                                                       "SEQUENTIAL list of chosen option "
                                                       "values, and a " (cond (set? v) "set"
                                                                              (map? v) "map"
                                                                              :else "value")
                                                       " is not one. A set is refused "
                                                       "deliberately: its order is not a "
                                                       "value the two hosts agree on, so "
                                                       "one declaration would answer two "
                                                       "trees. Write a vector, e.g. "
                                                       "[\"a\" \"b\"]")
                                                  (str "collection value for attribute " k
                                                       " — collections are only meaningful "
                                                       "for :class/:style and a <select>'s "
                                                       ":value (React renders "
                                                       "\"[object Object]\" garbage "
                                                       "elsewhere). Pass a string, e.g. "
                                                       "(str/join \" \" xs)"))
                                                {:prop k :value v}))
                                   (when (fn-form? v)
                                     (env/fail! e :rf.ui.compile/bare-fn-prop
                                                (str "bare fn at non-event prop " k " — "
                                                     "bare fns are legal only in known "
                                                     "native event properties (:on-* on "
                                                     "DOM/custom elements). Use v/raw-fn "
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
  "Analyze `(v/spread-safe owned caller)` in an element's props position (the
  LITERAL safe-spread policy). `owned` is a LITERAL props map, analysed exactly
  like an element's props (so a controlled owned site RETAINS the sync door);
  `caller` is the forwarded runtime attr map, guarded by the every-build
  owned-key deny law (`re-frame.freehand.rules/assert-safe-caller!` at runtime; a
  LITERAL offender is caught here at compile time). -> the owned props AST plus
  a `:safe-spread` slot carrying the walked caller form + owned-handler keys."
  [e tag-info properties props-form]
  (let [[_ owned caller & extra] props-form]
    (when (or (not (map? owned)) (not (= 3 (count props-form))))
      (env/fail! e :rf.ui.compile/bad-spread-safe
                 (str "(v/spread-safe owned caller) — `owned` must be a LITERAL "
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
                       (str "(v/spread-safe owned caller) — the caller map may "
                            "not carry the owned/structural key " (pr-str k)
                            "; it is denied in every build so it can never clobber "
                            "an owned prop. Forward it through the visible-cost "
                            "(v/spread base overrides) instead, or drop it")
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
  `(v/spread base overrides)`, or `(v/spread-safe owned caller)`.
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
        ;;
        ;; On the plain-JVM / SSR path there is no `:compile-prepare` hook to
        ;; pre-seed the slice, and macros expand top-to-bottom, so a view
        ;; expanded before a declaration below it would read an empty slice and
        ;; lower a declared property to an attribute. Harvest this namespace's
        ;; OWN literal declarations once, here, before classifying — making the
        ;; verdict order-independent (rf2-vxgfnd.141, dimension 2). Under a real
        ;; Shadow compile the build hook already seeded the slice, so this is a
        ;; no-op there (`shadow-compile?`).
        properties (when custom?
                     #?(:clj (when-not (build/shadow-compile?)
                               (harvest/ensure-namespace-harvested! (:ns e))))
                     (build/element-properties tag))
        spread?    (spread-form? e props-form)
        spread-safe? (spread-safe-form? e props-form)]
    (when (and (some? props-form) (not (map? props-form))
               (not spread?) (not spread-safe?))
      (env/fail! e :rf.ui.compile/dynamic-props-map
                 (str "props of " tag " must be a literal map, "
                      "(v/spread base overrides), or (v/spread-safe owned caller)")
                 {:form props-form}))
    (cond
      spread?
      ;; The shape is judged by ARITY, exactly as `analyze-foreign-spread`
      ;; judges its own. Testing `(nil? base)` instead read a present
      ;; literal nil as an ABSENT first argument, so `(v/spread nil)` —
      ;; which the public function answers `{}` and which §Props
      ;; forwarding defines as an element with no attributes — failed to
      ;; compile, while a binding whose runtime value is nil compiled and
      ;; rendered. Adding an otherwise unnecessary local changed whether
      ;; the same value was accepted.
      (let [args             (rest props-form)
            [base overrides] args]
        (when-not (<= 1 (count args) 2)
          (env/fail! e :rf.ui.compile/bad-spread
                     "(v/spread base) or (v/spread base overrides)"
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

(defn- raw-text-structural-child?
  "A source child form beneath a static single-text-body host element — a
  `<script>`/`<style>` raw-text element, or a `<textarea>` — that VISIBLY
  denotes host STRUCTURE rather than text: a hiccup element/fragment vector, or
  a keyed-list (`for`) markup form. React drops or stringifies such a child
  (these elements take one text string) and the JVM serialiser rejects it, so it
  is a compile error here (rf2-ib4fd). A runtime-dynamic expression — a symbol,
  a `(str …)`/other call, a branch — is NOT visibly structural and stays
  programmer-trusted: it produces the text string at render time."
  [e f]
  (or (vector? f)
      (and (seq? f)
           (let [h (first f)]
             (and (symbol? h)
                  (= h 'for)
                  (not (contains? (:locals e) h)))))))

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
                   (str "(v/html ...) must be the SOLE child of a DOM element "
                        "— the React host owns trusted markup through the "
                        "parent element. Wrap it: [:div (v/html s)] "
                        "(conservative S1 pin)")
                   {:form form})))
    ;; rf2-ib4fd — (v/html …) beneath a static <textarea> lowers to React
    ;; `dangerouslySetInnerHTML`, which react-dom/server 19.2 REJECTS on a
    ;; textarea (its content is `:value`/`defaultValue`, or an ordinary text
    ;; child). Reject at compile — a clear fail-fast beats a silent wrong render
    ;; (React throws / the JVM tree emits divergent trusted markup).
    (when (and (= tag :textarea) html-kid?)
      (env/fail! e :rf.ui.compile/html-in-textarea
                 (str "(v/html …) is not valid inside <textarea> — React sets a "
                      "textarea's content through :value (or an ordinary text "
                      "child), never trusted markup (dangerouslySetInnerHTML), "
                      "which React 19 rejects on a <textarea>. Use :value \"…\" "
                      "or an ordinary text child.")
                 {:tag tag :form form}))
    ;; rf2-ib4fd (residual) — a static <textarea>'s CHILD contract, the sibling
    ;; of the html-in-textarea rule above. React 19.2 renders a textarea's
    ;; content from ONE channel: its :value/:default-value, OR a single ordinary
    ;; text child — never both, never several children, never structural markup.
    ;; `[:textarea "a" "b"]` throws "<textarea> can only have at most one child";
    ;; :value/:default-value + an authored child throws; a structural child
    ;; renders "[object Object]" (and the JVM serialiser emits a divergent
    ;; <span>…</span> body). Reject the host-divergent shapes at compile — a
    ;; sole text child (literal or runtime-dynamic) and :value alone stay valid.
    (when (= tag :textarea)
      (let [literal-value? (and (map? second*)
                                (or (contains? second* :value)
                                    (contains? second* :default-value)))]
        (cond
          (and literal-value? (seq child-fs))
          (env/fail! e :rf.ui.compile/textarea-children
                     (str "<textarea> takes its content from EITHER "
                          ":value / :default-value OR a single text child, never "
                          "both — React rejects a textarea given a value AND a "
                          "child. Drop the child, or drop :value.")
                     {:tag tag :form form})

          (> (count child-fs) 1)
          (env/fail! e :rf.ui.compile/textarea-children
                     (str "<textarea> takes at most ONE child, but "
                          (count child-fs) " were given — React rejects a "
                          "textarea with more than one child. Supply a single "
                          "text child, or set the content through :value.")
                     {:tag tag :form form})

          (and (= 1 (count child-fs))
               (raw-text-structural-child? e (first child-fs)))
          (env/fail! e :rf.ui.compile/textarea-children
                     (str "<textarea> takes a single TEXT child, not structural "
                          "markup — React renders an element child as "
                          "\"[object Object]\". Supply text (a string, or a "
                          "(str …)), or set the content through :value.")
                     {:tag tag :form form}))))
    ;; rf2-ib4fd — a static <script>/<style> is an HTML RAW-TEXT element: React
    ;; takes a SINGLE text body (one string). Multiple source children reach
    ;; React as an array that warns and loses the body; a visibly structural
    ;; sole child (a hiccup element/fragment, or a `for` list) is dropped or
    ;; stringified by React and rejected by the JVM serialiser. Reject both at
    ;; compile. A sole (v/html …) is the sanctioned trusted-markup body (the
    ;; html-kid? path below); a runtime-dynamic expression stays trusted.
    (when (and (contains? rules/raw-text-tags tag) (not html-kid?))
      (cond
        (> (count child-fs) 1)
        (env/fail! e :rf.ui.compile/raw-text-children
                   (str "<" (name tag) "> is an HTML raw-text element and takes a "
                        "SINGLE text child, but " (count child-fs) " children were "
                        "given — React joins them into an array that warns and "
                        "loses the body. Construct ONE string, e.g. (str a b …), "
                        "or use (v/html s) for trusted markup.")
                   {:tag tag :form form})
        (and (= 1 (count child-fs))
             (raw-text-structural-child? e (first child-fs)))
        (env/fail! e :rf.ui.compile/raw-text-children
                   (str "<" (name tag) "> is an HTML raw-text element and takes a "
                        "SINGLE text child, not structural markup — React drops or "
                        "stringifies an element child here. Construct ONE string, "
                        "e.g. (str …), or use (v/html s) for trusted markup.")
                   {:tag tag :form form})))
    (let [children (if html-kid? [] (analyze-children e child-fs))
          html-ast (when html-kid?
                     (let [[_ s & extra] (first child-fs)]
                       (when (or (nil? s) (seq extra))
                         (env/fail! e :rf.ui.compile/bad-html
                                    "(v/html string) takes exactly one argument"
                                    {:form (first child-fs)}))
                       (when-not (or (string? s) (not (literal-scalar? s)))
                         (env/fail! e :rf.ui.compile/bad-html
                                    "(v/html x) requires a string"
                                    {:form (first child-fs)}))
                       (let [s* (if (string? s)
                                  s
                                  (walk-expr e [:html] s))]
                       ;; Record the trusted-markup site in the compiler
                       ;; manifest (profile row `v/html` — "manifest site
                       ;; recording"): the visible bypass carries source/
                       ;; template path so tools can list every place escaping
                       ;; is bypassed. `:serializable?` is false for a dynamic
                       ;; string expression, true for a literal.
                       (env/add-site! e :htmls {:form s*
                                                :static? (string? s)
                                                :serializable? (string? s)
                                                :source-coord (site-source-coord
                                                               e (first child-fs))
                                                :path (:path e)})
                       {:op :html :form s* :static? (string? s)})))
          node     {:op :element
                    :tag tag
                    :custom? custom?
                    :void? (contains? rules/void-tags tag)
                    :props props
                    :html html-ast
                    :children children
                    :static? (and (:static? props)
                                  (nil? (:spread props))
                                  (nil? (:safe-spread props))
                                  (if html-kid?
                                    (:static? html-ast)
                                    (every? node-static? children))
                                  true)
                    :path (:path e)}]
      ;; The compile-tier a11y roster (S4-C) reads the ANALYZED node — literal
      ;; facts only — and validates the element's suppression metadata. It mints
      ;; findings, never rejections: the node is returned unchanged.
      (a11y/check-element! e form node
                           (fn [expr-path] (lexical-site-id e :a11y form expr-path)))
      node)))

;; ---------------------------------------------------------------------------
;; Compiled render slots — `v/render-fn` callback + `v/slot` invocation (S3)
;; ---------------------------------------------------------------------------
;;
;; `v/render-fn` is a compiler-owned PURE render callback authored at the
;; consumer call site (a component prop value, or an inline v/slot argument),
;; so its body is COMPILED — the closed template grammar, no runtime hiccup.
;; The body is a DEFERRED render (the library seam invokes it later, in a
;; different view), so `:in-render-fn?` seeds the deferred scope: sub/frame
;; reads and the dispatch/hook surface (event handlers, refs) inside are
;; didactic `impure-slot-body` errors. Statically-referenced internal view
;; heads REMAIN legal — a stateful part is a pure slot body mounting a static
;; defview that owns its own state (the wave-2 registered-`v/view` coverage
;; argument depends on exactly this).

(defn analyze-render-fn
  "Analyze `(v/render-fn [args…] template)` → {:params <vec> :body <ast>}.
  The params bind as locals over a pure deferred-render template body."
  [e form]
  (let [[_ params & body] form]
    (when-not (vector? params)
      (env/fail! e :rf.ui.compile/bad-render-fn
                 (str "(v/render-fn [args…] template) needs a literal parameter "
                      "binding vector before its one template body; got "
                      (pr-str params))
                 {:form form}))
    (when (some #{'&} params)
      (env/fail! e :rf.ui.compile/bad-render-fn
                 (str "v/render-fn parameters are a FIXED arg list — variadic & "
                      "is not permitted (a v/slot passes a fixed number of args)")
                 {:form form}))
    (when (not= 1 (count body))
      (env/fail! e :rf.ui.compile/bad-render-fn
                 (str "(v/render-fn [args…] template) has exactly ONE template "
                      "body form — computation goes in (let …); siblings wrap in "
                      "[:<> …]. Got " (count body) " body forms")
                 {:form form}))
    (reject-reactive-binding! e params)
    (let [binders (env/binding-syms params)
          e*      (-> e
                      (env/with-locals binders)
                      (assoc :in-render-fn? true :in-loop? false :loop-syms #{})
                      ;; a render-fn body is DEFERRED — it runs wherever the
                      ;; matching `v/slot` is invoked, so it is not inline
                      ;; parent-render markup any more.
                      (dissoc :top-region? :presence-inline?)
                      (update :path conj :render-fn))]
      {:params params
       :body   (analyze e* (first body))})))

(defn- prop-slot-name [k]
  (if-let [ns* (namespace k)] (str ns* "/" (name k)) (name k)))

(defn- analyze-component-callback
  "A `v/event` or `v/handler` at a foreign/internal-view component prop: a
  per-site-stable committed callback that reads the winning commit (the
  stale-closure boundary law — one closed boundary contract for both invokers).
  `kind` is :ui-event (its body returns the event vector to dispatch, or nil) or
  :handler (imperative — its return is dropped). Records an event SITE
  (loop-capture-checked, manifest-carried) and produces the compiled body fn the
  emitter lowers to the stable callback."
  [e head-info k kind form]
  (let [[_ bindings & body] form
        verb (if (= kind :ui-event) "v/event" "v/handler")]
    ;; A committed callback authored as a component prop inside a render-fn slot
    ;; body is the correctness-critical escape: a repeated slot reuses ONE lexical
    ;; event-site key, so later rows overwrite the descriptor and every row's
    ;; callback reads the last row's closure. The purity fence rejects it — the
    ;; callback belongs to the owning view or a mounted defview (rf2-vtfzn).
    (when (:in-render-fn? e)
      (env/fail! e :rf.ui.compile/impure-slot-body
                 (str "(" verb " …) at prop " k " inside a v/render-fn slot body "
                      "— a slot body is a PURE render fragment; a committed callback "
                      "DISPATCHES / runs imperative work, which a slot body may not "
                      "own (a repeated slot shares ONE lexical callback site, so "
                      "every row would alias the last row's closure). Own the "
                      "callback in the OWNING view, or MOUNT a defview that owns its "
                      "handlers")
                 {:prop k :form form}))
    (when-not (and (vector? bindings) (not (some #{'&} bindings))
                   (if (= kind :ui-event)
                     (= 1 (count bindings))
                     (pos? (count bindings))))
      (env/fail! e :rf.ui.compile/bad-ui-callback
                 (str "(" verb " " (pr-str bindings) " …) at prop " k " on "
                      (if (= :view (:kind head-info)) "view " "foreign-component ")
                      (:sym head-info) " — " verb
                      (if (= kind :ui-event)
                        (str " binds exactly the invoker's event argument and "
                             "returns an event vector (or nil to dispatch nothing)")
                        (str " binds the invoker's arguments (a fixed-arity "
                             "vector, no &) and does imperative work")))
                 {:prop k :form form}))
    (let [identity (event-site-identity e k form)
          binders  (set (env/binding-syms bindings))
          ;; The body is a DEFERRED callback (the invoker calls it later), so the
          ;; fn walk rejects render-time sub/frame inside it. Its own
          ;; bindings shadow, so they are excluded from the loop-capture check.
          form*    (walk-expr e [:component-prop k kind]
                              (with-meta (apply list 'fn bindings body) (meta form)))]
      (check-loop-capture! (update e :loop-syms #(reduce disj % binders))
                           (str verb " at prop " k) form)
      (add-event-site! e identity
                       {:prop k :handler :opaque
                        :classification kind :serializable? false})
      (merge identity
             {:k k
              :slot (prop-slot-name k)
              :marker kind          ; :ui-event | :handler
              :callback-fn form*
              ;; The lowered VALUE, under the slot every other entry carries
              ;; its value in. A `v/event` / `v/handler` prop is analysed
              ;; content, so the entry used to carry the body under
              ;; `:callback-fn` and NOTHING under `:value` — and both v1
              ;; emitters read `:value`. The crossing therefore handed the
              ;; boundary `{:on-pick nil}`: every declaration compiled, every
              ;; mount resolved, and the callback the author wrote was simply
              ;; not there. It is the roster constructor for the same reason a
              ;; DOM-site one is — the interpreted macro expands to exactly
              ;; this, so the boundary records one marker whichever mode wrote
              ;; the prop.
              :value (roster-callback (if (= kind :ui-event) :event :handler)
                                      form*
                                      (count bindings))
              :classification kind
              :literal? false}))))

(defn- analyze-foreign-spread
  "Parse `(v/spread …)` in a FOREIGN component's props position (rf2-u53yy.5):
  an OPTIONAL leading LITERAL map — the component's own props, analysed exactly
  like a literal call-site props map (compiled `v/handler`/`v/event`/
  `v/render-fn`, prop-key checks, `:key`/`:ref` extraction) — plus an opaque
  forwarded runtime map. The forwarded map is the visible foreign-boundary
  opt-in: a foreign head's props are open and pass through UNCONVERTED (there is
  no per-slot memo comparator or slot ABI to defend, so nothing is converted),
  and the compiled LITERAL props WIN any key collision — mirroring
  `v/spread-safe`'s owned-wins layering, minus the deny law. The forwarded map
  marks the site `:dynamic` in the manifest exactly as a dynamic handler
  expression does. -> `[literal-map spread-node|nil]`.

  Shapes: `(v/spread runtime-map)` — the plain forwarded map, no literal part;
  `(v/spread literal-map runtime-map)` — literal part plus the forwarded map. A
  leading literal map is the literal part; a leading non-map expression is the
  forwarded map."
  [e head-info props-form]
  (let [args (rest props-form)
        n    (count args)]
    (when-not (<= 1 n 2)
      (env/fail! e :rf.ui.compile/bad-spread
                 (str "(v/spread runtime-map) or (v/spread literal-part "
                      "runtime-map) at the foreign component " (:sym head-info))
                 {:form props-form}))
    (let [[a b]   args
          literal (if (map? a)
                    a
                    (when (= n 2)
                      (env/fail! e :rf.ui.compile/bad-spread
                                 (str "(v/spread literal-part runtime-map) — the "
                                      "first argument must be a LITERAL props map "
                                      "(analysed for the component's compiled "
                                      "handlers and props); the second is the "
                                      "opaque forwarded runtime map")
                                 {:form props-form})))
          runtime (cond
                    (= n 2)  b
                    (map? a) nil
                    :else    a)]
      [(or literal {})
       (when (some? runtime)
         (let [identity (event-site-identity e :spread props-form)]
           (add-event-site! e identity
                            {:prop :spread :handler :opaque
                             :classification :spread :serializable? false
                             :sync? false})
           (merge identity {:base (walk-expr e [:spread :base] runtime)})))])))

(defn- analyze-component-props
  "View/foreign call-site props (Q2/Q3/Q4): a literal map — or, at a FOREIGN
  head only, `(v/spread …)` (rf2-u53yy.5, the foreign wrapper idiom). :key
  extracted (never a prop); :children as an explicit key rejected (children are
  positional). Bare fn values: rejected at a FOREIGN boundary (the narrow
  bare-fn law — invoker/phase unknown), but LEGAL as an opaque identity-compared
  value at an INTERNAL-view boundary (C-13a) — the framework never invokes it
  and promises no phase; v/handler/v/render-fn opt a phase in. `v/event`/
  `v/handler` are the explicit committed callbacks at either. `v/spread` at an
  INTERNAL view is rejected — an internal view requires a literal props map (its
  generated per-slot memo comparator and slot ABI need the literal keys)."
  [e head-info props-form]
  (let [spread? (spread-form? e props-form)
        view?   (= :view (:kind head-info))]
   (cond
    (and spread? view?)
    (env/fail! e :rf.ui.compile/spread-internal-view
               (str "(v/spread …) at the internal view " (:view-id head-info)
                    " — an internal view requires a LITERAL props map: its "
                    "generated per-slot memo comparator and slot ABI need the "
                    "literal keys. v/spread is admitted at a FOREIGN component "
                    "call site (its props are open and pass through unconverted) "
                    "and in a DOM/custom element's props position")
               {:head (:sym head-info)})

    (and (some? props-form) (not (map? props-form)) (not spread?))
    (env/fail! e :rf.ui.compile/dynamic-props-map
               (str "component call sites take a LITERAL props map — a wholly-"
                    "dynamic props expression is not v1 grammar (conservative "
                    "S1 pin). At a FOREIGN component forward a runtime map with "
                    "(v/spread literal-part runtime-map); a DOM/custom element "
                    "also takes (v/spread base overrides)")
               {:head (:sym head-info) :form props-form})

    :else
    (let [[m spread-node] (if spread?
                            (analyze-foreign-spread e head-info props-form)
                            [(or props-form {}) nil])]
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
                           (cond
                             (render-fn-form? e v)
                             ;; A compiled render slot: the body is a lexically
                             ;; visible pure template compiled HERE (both emitters)
                             ;; into a callback value the seam invokes via v/slot.
                             {:k k
                              :slot (prop-slot-name k)
                              :render-fn (analyze-render-fn
                                          (update e :path into [:component-prop k]) v)
                              :marker :render-fn
                              :literal? false}

                             ;; The explicit committed callbacks — a per-site
                             ;; stable identity reading the winning commit at a
                             ;; foreign or internal-view seam.
                             (ui-event-form? e v)
                             (analyze-component-callback e head-info k :ui-event v)

                             (ui-handler-form? e v)
                             (analyze-component-callback e head-info k :handler v)

                             (fn-form? v)
                             (if (= :view (:kind head-info))
                               ;; C-13a: a bare fn between INTERNAL views is a
                               ;; legal OPAQUE value — identity-compared, NEVER
                               ;; invoked by the framework, no implicit invocation
                               ;; phase. A fresh closure repaints via the memo
                               ;; identity-compare; opt a phase in with v/handler
                               ;; (per-site stable) or v/render-fn.
                               {:k k :slot (prop-slot-name k)
                                :value (walk-expr e [:component-prop k] v)
                                :marker nil :literal? false}
                               (env/fail! e :rf.ui.compile/bare-fn-prop
                                          (str "bare fn prop " k " at a "
                                               "foreign-component boundary — invoker "
                                               "and phase are unknown there. Choose "
                                               "v/raw-fn (identity-as-protocol), "
                                               "v/event (returns the event vector to "
                                               "dispatch), v/handler (imperative "
                                               "work), or v/render-fn (a compiled "
                                               "render slot) — never a bare fn")
                                          {:prop k :head (:sym head-info)}))

                             :else
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
                                :slot (prop-slot-name k)
                                :value v*
                                :marker (cond raw? :foreign raw-fn? :v/raw-fn :else nil)
                                :literal? (literal-scalar? v)})))
                          m*)]
      (let [key-form* (if (and (contains? m :key) (not (literal-scalar? key-form)))
                        (walk-expr e [:component-key] key-form)
                        key-form)]
      (cond-> {:key {:present? (contains? m :key) :expr key-form*
                     :literal? (literal-scalar? key-form)}
               :entries entries
               :ref ref-a}
        spread-node (assoc :spread spread-node))))))))

(defn- analyze-component [e form]
  (let [head      (nth form 0)
        info      (env/classify-head e head)
        second*   (nth form 1 nil)
        ;; a `(v/spread …)` second form is PROPS at a component head, not a
        ;; child — admitted at a FOREIGN head (rf2-u53yy.5), rejected at an
        ;; internal view by analyze-component-props (literal props required).
        has-props (or (map? second*) (spread-form? e second*))
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
                           (env/accepts-children? meta*))]
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
            closed (if self? (:self-closed-keys e) (env/closed-prop-keys meta*))]
        (when closed
          ;; The SAME roster and the SAME sentence the boundary uses. This
          ;; arm reports at BUILD time because a compiled call site's keys
          ;; are literal and therefore knowable then — static knowledge moves
          ;; when a breach surfaces, never which props are legal (D011).
          (let [bad (props-schema/undeclared closed (map :k (:entries props)))]
            (when (seq bad)
              (env/fail! e :rf.ui.compile/undeclared-prop
                         (props-schema/violation-message (:view-id info) closed bad)
                         {:head head :undeclared (vec bad)}))))))
    ;; The CROSSING index (D010). An internal boundary is where a compiled
    ;; body hands rendering to another declaration, and the child's mode is
    ;; the child's business — so the site records which mode it crosses into
    ;; rather than the parent pretending to own it. It rides the SITE index,
    ;; not the AST node, deliberately: a child's promotion is not an edit to
    ;; this template and must not move this template's fingerprint.
    (when (= :view (:kind info))
      (env/add-site! e :views {:view-id      (:view-id info)
                               :lowering     (:lowering info)
                               :source-coord (site-source-coord e form)
                               :path         (:path e)}))
    (cond-> {:op (if (= :view (:kind info)) :view :foreign)
             :sym head
             :fqn (:fqn info)
             :view-id (:view-id info)
             :props props
             :children children
             :static? false
             :path (:path e)}
      ;; a re-frame.freehand.react/lazy component is a foreign head that IS callable on
      ;; the JVM structural render (renders its fallback / nothing).
      (:lazy? info) (assoc :lazy? true))))

(defn- analyze-slot
  "Analyze `(v/slot render-fn-value arg…)` — the compiler-owned invocation of
  a compiled render slot in child position. The first argument is an inline
  `(v/render-fn …)` (compiled here) or an ordinary expression evaluating to a
  render-fn value or nil (validated at the seam by `slot-ready?`); the
  remaining args are the library's runtime values, walked in the ambient
  (library-view) scope — a slot ARGUMENT is not deferred, so a `(sub …)` there
  is the library's own render-time read. The slot's output participates in the
  surrounding children exactly like any other child (child-like memo cost).

  A render-fn is a FIXED-arity callback (Spec 004 §render slots), so the slot
  boundary owns ONE host-independent arity contract: an INLINE render-fn's
  parameter count is compared with the slot's argument count HERE and a mismatch
  is a compile error; a PROP-carried render-fn carries its arity into the runtime
  carrier and `check-slot-arity!` enforces it before invocation on both hosts —
  neither leans on a native fixed/variadic call quirk (rf2-ckviw)."
  [e form]
  (when (< (count form) 2)
    (env/fail! e :rf.ui.compile/bad-slot
               (str "(v/slot render-fn-value arg…) needs a render-fn value (or "
                    "nil) as its first argument; got " (pr-str form))
               {:form form}))
  ;; The deliberate mode ASYMMETRY, refused where it is visible. An interpreted
  ;; `v/slot` accepts an ordinary pure fn as parameterized content — it has
  ;; nothing to prove about what it invokes. A compiled one does not, and a fn
  ;; written lexically HERE is the one case the compiler can say so about at
  ;; build time rather than at render.
  (when (fn-form? (nth form 1 nil))
    (env/fail! e :rf.ui.compile/bad-slot
               (str "(v/slot (fn …) arg…) — a bare fn is not compiled render "
                    "content. The compiled tier lowers what it can SEE, and a "
                    "function value is exactly what it cannot: write the content "
                    "as (v/render-fn [args…] template) so its body compiles here, "
                    "or keep this view interpreted, where an ordinary pure fn is "
                    "accepted")
               {:form form}))
  (let [slotval (nth form 1)
        args    (drop 2 form)
        argc    (count args)
        inline? (render-fn-form? e slotval)
        sid     (lexical-site-id e :slot form (:path e))
        args*   (into []
                      (map-indexed (fn [i a] (walk-expr e [:slot :arg i] a)))
                      args)
        ;; Analyze the inline body FIRST (so an impure body throws
        ;; impure-slot-body before the arity check), then compare arity.
        rf-node (when inline?
                  (analyze-render-fn (update e :path conj :slot-fn) slotval))]
    (when inline?
      (let [pc (count (:params rf-node))]
        (when (not= pc argc)
          (env/fail! e :rf.ui.compile/slot-arity
                     (str "(v/slot render-fn arg…) passes " argc " argument"
                          (when (not= 1 argc) "s") " to an inline (v/render-fn "
                          (pr-str (:params rf-node)) " …) that declares " pc
                          " parameter" (when (not= 1 pc) "s")
                          " — a render-fn is a FIXED-arity callback, so the slot must "
                          "pass exactly its declared parameters (host-identically, "
                          "before invocation). "
                          (if (< argc pc)
                            "Pass the missing argument(s), or drop the unused parameter(s)"
                            "Drop the surplus argument(s), or declare the extra parameter(s)"))
                     {:form form :expected pc :actual argc}))))
    (let [node (cond-> {:op :slot
                        :args args*
                        :sid sid
                        :static? false
                        :path (:path e)}
                 inline?       (assoc :render-fn rf-node)
                 (not inline?) (assoc :slot-value (if (literal-scalar? slotval)
                                                    slotval
                                                    (walk-expr e [:slot :value] slotval))))]
      (env/add-site! e :slots {:sid sid
                               :path (:path e)
                               :source-coord (site-source-coord e form)
                               :inline? inline?})
      node)))

;; ---------------------------------------------------------------------------
;; v/error-boundary + v/client-only (S3) — the interop recovery/boundary forms
;; ---------------------------------------------------------------------------

(def ^:private error-boundary-opt-keys #{:fallback :reset-key :on-error})

(defn- analyze-error-boundary
  "(v/error-boundary {:fallback view :reset-key val :on-error [:ev …]} child)
  — the explicit error component. Catches render/lifecycle throws below it
  (React does not catch event-handler or async errors — those keep their typed
  paths); the fallback VIEW renders with :error on catch; :on-error dispatches
  AFTER the failing commit through the owning view's captured frame (never
  during render, I-1); changing :reset-key clears the caught error (retry). One
  guarded child."
  [e form]
  (let [opts  (nth form 1 nil)
        rest* (drop 2 form)]
    (when-not (map? opts)
      (env/fail! e :rf.ui.compile/bad-error-boundary
                 (str "(v/error-boundary {:fallback view …} child) needs a "
                      "literal opts map with a :fallback view")
                 {:form form}))
    (let [bad (remove error-boundary-opt-keys (keys opts))]
      (when (seq bad)
        (env/fail! e :rf.ui.compile/bad-error-boundary
                   (str "unknown error-boundary option" (when (next bad) "s") " "
                        (str/join ", " (map pr-str bad))
                        " — the closed set is {:fallback :reset-key :on-error}")
                   {:form form})))
    (when-not (contains? opts :fallback)
      (env/fail! e :rf.ui.compile/bad-error-boundary
                 (str ":fallback is REQUIRED — the view that renders with :error "
                      "when a throw is caught below the boundary")
                 {:form form}))
    (let [fallback (:fallback opts)
          fb-info  (when (and (symbol? fallback) (not (contains? (:locals e) fallback)))
                     (env/classify-head e fallback))]
      (when-not (and fb-info (= :view (:kind fb-info)))
        (env/fail! e :rf.ui.compile/bad-error-boundary
                   (str ":fallback must be a defview — it renders with :error + "
                        "declared props and cannot recursively dispatch; got "
                        (pr-str fallback))
                   {:form form}))
      (let [on-error (:on-error opts)]
        ;; :on-error DISPATCHES after the failing commit — a committed-callback
        ;; dispatch surface. Inside a render-fn slot body it escapes the purity
        ;; fence exactly like a component-prop callback, so reject it (the
        ;; error-boundary and its :on-error belong to the owning view or a mounted
        ;; defview; rf2-vtfzn). A fallback-only boundary stays legal in a slot.
        (when (and (some? on-error) (:in-render-fn? e))
          (env/fail! e :rf.ui.compile/impure-slot-body
                     (str ":on-error inside a v/render-fn slot body — a slot body "
                          "is a PURE render fragment, and :on-error DISPATCHES after "
                          "the failing commit, which a slot body may not own. Place "
                          "the error-boundary (with its :on-error) in the OWNING "
                          "view, or MOUNT a defview that owns the boundary")
                     {:form form}))
        (when (and (some? on-error)
                   (not (and (vector? on-error) (keyword? (first on-error)))))
          (env/fail! e :rf.ui.compile/bad-error-boundary
                     (str ":on-error must be a literal event vector "
                          "[:domain/event …] dispatched after the failing commit; "
                          "got " (pr-str on-error))
                     {:form form}))
        (when (not= 1 (count rest*))
          (env/fail! e :rf.ui.compile/bad-error-boundary
                     (str "(v/error-boundary {…} child) takes exactly ONE guarded "
                          "child; wrap siblings in [:<> …]")
                     {:form form}))
        {:op :error-boundary
         :fallback fb-info
         :has-reset-key? (contains? opts :reset-key)
         :reset-key (when (contains? opts :reset-key)
                      (walk-expr e [:error-boundary :reset-key] (:reset-key opts)))
         ;; :on-error args evaluate when the after-commit dispatch closure builds
         ;; at render — walk them for site indexing (the head stays the event id).
         :on-error (when (some? on-error)
                     (with-meta
                       (into [(first on-error)]
                             (map-indexed
                              (fn [i x] (walk-expr e [:error-boundary :on-error i] x)))
                             (rest on-error))
                       (meta on-error)))
         ;; The child is CONDITIONALLY rendered (child vs fallback) → host hooks
         ;; below it are illegal; frame-plan extraction DESCENDS through the
         ;; boundary (004C §6), so :top-region? is preserved.
         :child (analyze (assoc e :hooks-region? false) (first rest*))
         :static? false
         :path (:path e)}))))

(defn- analyze-capability-free
  "Analyze `form` as a CAPABILITY-FREE template (a client-only :fallback):
  deterministic structural markup only — the JVM/SSR and first-hydration render
  must match, so reactive reads (sub/frame), host state/effects (local/
  effect/dispatch-fn), and committed event handlers are compile errors. Returns
  the fallback AST. `context` names the position for the diagnostic."
  [e context form]
  (let [sub-sites (atom {:events [] :subs [] :htmls [] :frame-ops []
                         :slots [] :locals [] :effects [] :dispatch-fns []})
        ast       (analyze (assoc e :sites sub-sites :hooks-region? false) form)
        found     (->> [:events :subs :frame-ops :locals :effects :dispatch-fns]
                       (filter #(seq (get @sub-sites %)))
                       (map name))]
    (when (seq found)
      (env/fail! e :rf.ui.compile/capability-in-fallback
                 (str context " must be CAPABILITY-FREE — the JVM/SSR and first "
                      "hydration render it deterministically, then the client "
                      "swaps in the live subtree, so a capability here would tear "
                      "on hydration. Found " (str/join ", " (sort found))
                      ". A fallback is static markup (structure, props, branches, "
                      "v/html); move reactive reads, host state/effects, and "
                      "event handlers into the client subtree")
                 {:form form :capabilities (vec (sort found))}))
    ast))

(defn analyze-capability-free-template
  "Analyze `form` as a standalone CAPABILITY-FREE template (deterministic
  structural markup: no reactive reads, host state/effects, or committed event
  handlers) — the def-level entry for re-frame.freehand.react/lazy's `:fallback`.
  Returns the AST or fails loud. `context` names the position for diagnostics."
  [e context form]
  (analyze-capability-free e context form))

(defn- analyze-client-only
  "(v/client-only {:fallback tpl} client-tpl) — a browser-only subtree with a
  mandatory, capability-free fallback (compiler-checked). The JVM/SSR renders
  the fallback (a deterministic `:rf.ui/boundary :client-only` node); the CLJS
  emitter lowers the site to a PHASE-CONDITIONAL runtime boundary
  (`re-frame.freehand.runtime/client-only`) that renders the fallback in `:server`
  phase and the client subtree in `:client` phase — the S5 phase flip a
  hydrating root drives `:server` -> `:client` after its hydration commit (Spec
  011 §Phase flip, rf2-3omxp). A non-hydrating mount reads the `:client` phase
  default and renders the client subtree on the first render (the S3
  activation)."
  [e form]
  (let [opts  (nth form 1 nil)
        rest* (drop 2 form)]
    (when-not (map? opts)
      (env/fail! e :rf.ui.compile/bad-client-only
                 (str "(v/client-only {:fallback tpl} client-tpl) needs a literal "
                      "{:fallback tpl} map")
                 {:form form}))
    (let [bad (remove #{:fallback} (keys opts))]
      (when (seq bad)
        (env/fail! e :rf.ui.compile/bad-client-only
                   (str "unknown client-only option" (when (next bad) "s") " "
                        (str/join ", " (map pr-str bad))
                        " — the only option is :fallback")
                   {:form form})))
    (when-not (contains? opts :fallback)
      (env/fail! e :rf.ui.compile/bad-client-only
                 (str ":fallback is REQUIRED and must be capability-free — the "
                      "deterministic JVM/SSR + first-hydration render")
                 {:form form}))
    (when (not= 1 (count rest*))
      (env/fail! e :rf.ui.compile/bad-client-only
                 (str "(v/client-only {:fallback tpl} client-tpl) takes exactly "
                      "ONE client child; wrap siblings in [:<> …]")
                 {:form form}))
    {:op :client-only
     :fallback (analyze-capability-free e "a v/client-only :fallback" (:fallback opts))
     ;; the client subtree is browser-only — it ends the static top region and
     ;; the hooks region (host content never appears on the JVM/SSR path).
     :child (analyze (-> e (dissoc :top-region?) (assoc :hooks-region? false))
                     (first rest*))
     :static? false
     :path (:path e)}))

;; ---------------------------------------------------------------------------
;; v/presence (S4, rf2-uckeg) — declarative enter/exit retention
;; ---------------------------------------------------------------------------

(def ^:private presence-opt-keys #{:timeout-ms})

(defn- presence-keyed-child?
  "A presence child must be STATICALLY keyed — the identity the runtime tracks
  across renders. A `(for …)` is keyed by construction (analyze-for enforces
  every row's `:key`); a literal element/view/foreign/fragment must carry a
  `:key`. A branch (if/let/case) is keyed when each rendered arm is.

  The key's location differs by node, and this predicate reads each node's
  ACTUAL analyzed shape: a `:fragment` carries its key at the node
  (`analyze-fragment`), while `:element` (`analyze-element-props`) and
  `:view`/`:foreign` (`analyze-component-props`) carry it inside their analyzed
  props map. Reading `[:key :present?]` off an `:element` finds nil and rejects
  every keyed literal host child (rf2-vxgfnd.96.1).

  Both locations now answer the SAME question — does this node carry a `:key`?
  A `:fragment` used to report its PROPS MAP's presence there, so `[:<> {} …]`
  passed as keyed while offering the boundary no identity to retain; the probe
  is truthful at its source now (rf2-xoz1s), which is why this predicate needs
  no fragment-specific translation."
  [ast]
  (case (:op ast)
    :for true
    :fragment (boolean (get-in ast [:key :present?]))
    (:element :view :foreign) (boolean (get-in ast [:props :key :present?]))
    :if (and (presence-keyed-child? (:then ast)) (presence-keyed-child? (:else ast)))
    :let (presence-keyed-child? (:body ast))
    :letfn (presence-keyed-child? (:body ast))
    :case (and (every? presence-keyed-child? (map second (:clauses ast)))
               (or (= ::none (:default ast)) (presence-keyed-child? (:default ast))))
    :nothing true                       ; a statically-absent arm is fine
    false))

(defn- analyze-presence
  "(v/presence {:timeout-ms n} keyed-children) — the three-phase enter/exit
  retention boundary. `:timeout-ms` is MANDATORY (the terminal safety bound —
  unit suffixed on the key) and a positive number literal. Children must be
  statically KEYED (a `(for …)` with `:key`, or keyed element/view rows) —
  unkeyed presence children are a build failure (§Presence)."
  [e form]
  (let [opts  (nth form 1 nil)
        body  (drop 2 form)]
    (when-not (map? opts)
      (env/fail! e :rf.ui.compile/bad-presence
                 (str "(v/presence {:timeout-ms n} children) needs a literal opts "
                      "map with a mandatory :timeout-ms (the terminal safety bound)")
                 {:form form}))
    (let [bad (remove presence-opt-keys (keys opts))]
      (when (seq bad)
        (env/fail! e :rf.ui.compile/bad-presence
                   (str "unknown presence option" (when (next bad) "s") " "
                        (str/join ", " (map pr-str bad))
                        " — the only option is :timeout-ms")
                   {:form form})))
    (when-not (contains? opts :timeout-ms)
      (env/fail! e :rf.ui.compile/bad-presence
                 (str ":timeout-ms is MANDATORY on (v/presence …) — the terminal "
                      "safety bound AND the exit retention duration: a retained "
                      "exiting child is removed when :timeout-ms fires")
                 {:form form}))
    (let [t (:timeout-ms opts)]
      (when-not (and (number? t) (pos? t))
        (env/fail! e :rf.ui.compile/bad-presence
                   (str ":timeout-ms must be a positive number of milliseconds; got "
                        (pr-str t))
                   {:form form})))
    (when (empty? body)
      (env/fail! e :rf.ui.compile/bad-presence
                 "(v/presence {:timeout-ms n} children) needs at least one keyed child"
                 {:form form}))
    (let [child-asts (into []
                           (map-indexed
                            (fn [i c]
                              (analyze (-> e (update :path conj :presence i)
                                           ;; INLINE literal markup under a
                                           ;; presence boundary is evaluated in
                                           ;; the parent's render — outside the
                                           ;; per-child phase Provider — so it
                                           ;; provably cannot read its own
                                           ;; (v/presence-phase). S4-C check 4
                                           ;; keys off exactly this fact.
                                           (assoc :hooks-region? false
                                                  :presence-inline? true))
                                       c)))
                           body)]
      (doseq [ast child-asts]
        (when-not (presence-keyed-child? ast)
          (env/fail! e :rf.ui.compile/presence-unkeyed-child
                     (str "children under (v/presence …) must be KEYED — a "
                          "(for [x xs] [row {:key (:id x)} …]) or keyed element/view "
                          "rows. Presence tracks children by key across renders, so "
                          "unkeyed presence children are a build failure")
                     {:form form})))
      {:op :presence
       :timeout-ms (:timeout-ms opts)
       :children child-asts
       :static? false
       :path (:path e)})))

;; ---------------------------------------------------------------------------
;; Reactive authoring verbs in head position (rf2-vxgfnd.266)
;; ---------------------------------------------------------------------------
;;
;; sub/frame are reactive authoring verbs, sound in evaluated code ONLY as
;; their compiler-owned DIRECT forms — (sub query), (frame) — which the
;; expression rewriter lowers to
;; indexed runtime sites. A Hiccup component HEAD is not such a form:
;; env/classify-head resolves any resolved non-:rf.ui/view var to a plain
;; :foreign React component, so [sub {…}] / [frame] would
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
  "Return :sub/:frame when `head` is an unshadowed symbol resolving to a
  public reactive authoring var — the reserved-head discriminator, mirroring
  frame-root-head?/frame-provider-head?."
  [e head]
  (when (and (symbol? head) (not (contains? (:locals e) head)))
    (reactive-authoring-var-kind e head)))

(defn- analyze-reactive-authoring-head! [e form head]
  (let [kind (reactive-authoring-head-kind e head)]
    (env/fail! e :rf.ui.compile/unsupported-form
               (str "reactive authoring verb " (name kind) " in component-head "
                    "position [" (name kind) " …] — re-frame.freehand/" (name kind)
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
;; `v/mount` / `v/render!` / `v/hydrate-root`); DOM elements, fragments
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
                    "v/mount / v/render! / v/hydrate-root, nested only "
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
        ;; preflight) — walk them for sub site indexing only
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
      ;; the :frame target is a runtime expression — walk it for sub
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
        ;; Key PRESENCE is `:key`'s own presence, never the props map's
        ;; (rf2-xoz1s). `[:<> {} …]` carries a props map and no key; reporting
        ;; `:present? true` for it claims an identity the fragment does not
        ;; have, and every downstream key consumer believes the claim — the
        ;; presence boundary admits it as keyed and then has nothing to track,
        ;; `analyze-for` mis-reports the empty map as a CONSTANT key, and the
        ;; JVM structural tree gains a `:key nil` entry. `contains?` is exactly
        ;; how `:element`/`:view` probe their own props (`analyze-element-props`
        ;; / `analyze-component-props`), so the three node kinds now answer the
        ;; same question the same way and `presence-keyed-child?` reads a true
        ;; `[:key :present?]` off a `:fragment` without further translation.
        key?      (and has-props (contains? second* :key))
        key-form  (when key? (get second* :key))
        key-form* (if (and key? (not (literal-scalar? key-form)))
                    (walk-expr e [:fragment :key] key-form)
                    key-form)
        children  (analyze-children e (vec (if has-props (drop 2 form) (drop 1 form))))]
    {:op :fragment
     :key {:present? key? :expr key-form* :literal? (literal-scalar? key-form)}
     :children children
     ;; Deliberately still `has-props`, not `key?`: hoisting is a separate
     ;; question from identity, and `[:<> {} …]` staying un-hoisted is a missed
     ;; optimisation rather than a wrong answer. Narrowing it would change
     ;; emitted code for a shape this bead is not about.
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

;; ---------------------------------------------------------------------------
;; Host hooks — `effect` statements + the `local`/`effect` hooks region
;; ---------------------------------------------------------------------------

(defn effect-statement-form?
  "True when `form` is a direct, unshadowed call resolving to public
  `re-frame.freehand/effect` — the only form allowed to occupy the leading statement
  prefix of a hooks region (a defview's top body or a top-region let/do body)."
  [e form]
  (and (seq? form)
       (symbol? (first form))
       (not (contains? (:locals e) (first form)))
       (env/resolves-to? e (first form) effect-fqns)))

(defn- analyze-effect-statement
  "Analyze one leading `(effect …)` statement -> the lowered runtime call form.
  `(effect [deps…] body…)` compares deps by rf= (value deps); `(effect :connect
  body…)` runs at each connect. The body is a DEFERRED callback (sub/frame
  inside are rejected); it is spliced before the template by `analyze-hooks-body`."
  [e index form]
  ;; The purity fence is TRANSITIVE: a render-fn slot body inherits the enclosing
  ;; hooks region, so a leading (effect …) would otherwise reach this seam and be
  ;; emitted as a React lifecycle hook INSIDE the deferred callback. A slot body
  ;; is a PURE render fragment — its effects belong to the owning view or a
  ;; mounted defview (rf2-vtfzn).
  (when (:in-render-fn? e)
    (env/fail! e :rf.ui.compile/impure-slot-body
               (str "(effect …) inside a v/render-fn slot body — a slot body is a "
                    "PURE render fragment; a host effect is a lifecycle hook, which "
                    "a slot body may not own. Run the effect in the OWNING view, or "
                    "MOUNT a defview that owns its own effects")
               {:form form}))
  (when (or (nil? (:self-id e)) (not (:hooks-region? e)))
    (env/fail! e :rf.ui.compile/hook-misplaced
               (str "(effect …) is a host-effect statement — legal ONLY in a "
                    "defview's UNCONDITIONAL top region (or a top-region let/do "
                    "body), before the final template")
               {:form form}))
  (let [[_ deps-or-kw & body] form
        sid (lexical-site-id e :effect form [:effect index])]
    (when (empty? body)
      (env/fail! e :rf.ui.compile/bad-effect
                 (str "(effect [deps…] body…) / (effect :connect body…) needs a "
                      "body form after the deps")
                 {:form form}))
    (letfn [(body-fn [] ; the deferred callback fn — the fn walk rejects sub/frame
              (walk-expr e [:effect index :body]
                         (with-meta (apply list 'fn [] body) (meta form))))]
      (cond
        (= :connect deps-or-kw)
        (do
          (env/add-site! e :effects {:sid sid :kind :connect :index index
                                     :path (:path e) :expr-path [:effect index]})
          (with-meta (list runtime-effect-connect-fqn (body-fn)) (meta form)))

        (vector? deps-or-kw)
        (let [deps* (mapv (fn [i d] (walk-expr e [:effect index :dep i] d))
                          (range) deps-or-kw)]
          (env/add-site! e :effects {:sid sid :kind :deps :index index
                                     :path (:path e) :expr-path [:effect index]})
          (with-meta (list runtime-effect-value-fqn (body-fn) (vec deps*))
            (meta form)))

        :else
        (env/fail! e :rf.ui.compile/bad-effect
                   (str "(effect …) first argument must be a literal deps VECTOR "
                        "(value deps compared by rf=) or the keyword :connect; got "
                        (pr-str deps-or-kw))
                   {:form form})))))

(defn- analyze-hooks-body
  "Analyze a hooks-region body: zero or more leading `(effect …)` STATEMENTS,
  then exactly ONE final template. Returns the template AST, wrapped in a
  `:hook-prefix` node carrying the lowered effect statement forms when any are
  present (the emitters splice them before the template as a `do` sequence)."
  [e head body form]
  (loop [effects [] forms (seq body) index 0]
    (cond
      (nil? forms)
      (env/fail! e :rf.ui.compile/multi-form-body
                 (str head " needs exactly ONE final template after any leading "
                      "(effect …) statements")
                 {:form form})

      (effect-statement-form? e (first forms))
      (recur (conj effects (analyze-effect-statement e index (first forms)))
             (next forms) (inc index))

      (next forms)
      (env/fail! e :rf.ui.compile/multi-form-body
                 (str head " in template position takes leading (effect …) "
                      "statements then exactly ONE template form — side effects "
                      "don't belong in templates (statically-pure rule); siblings "
                      "wrap in [:<> ...]")
                 {:form form})

      :else
      (let [tmpl (analyze e (first forms))]
        (if (seq effects)
          {:op :hook-prefix :statements effects :body tmpl
           :static? false :path (:path e)}
          tmpl)))))

(defn- analyze-if [e test then else form]
  ;; Branches are CONDITIONAL — host hooks (local/effect) are illegal below them
  ;; (React hooks run unconditionally, once per render, in fixed order).
  (let [eb (assoc e :hooks-region? false)]
    {:op :if :test (walk-expr e [:if :test] test)
     :then (analyze (update eb :path conj :then) then)
     :else (if (some? else)
             (analyze (update eb :path conj :else) else)
             {:op :nothing :static? true})
     :static? false :path (:path e)}))

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
          default  (when default? (last clauses))
          eb       (assoc e :hooks-region? false)]
      {:op :case
       :expr (walk-expr e [:case :expr] expr)
       :clauses (into []
                      (map-indexed (fn [i [test branch]]
                                     [test (analyze (update eb :path conj [:case i]) branch)]))
                      pairs)
       :default (if default?
                  (analyze (update eb :path conj :case-default) default)
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
          eb        (update e* :path conj :body)]
      {:op :let :bindings bindings*
       ;; A top-region let keeps its body in the hooks region, so leading
       ;; (effect …) statements are legal there; a let below a branch/loop is
       ;; not, so it is the strict single-template body.
       :body (if (:hooks-region? eb)
               (analyze-hooks-body eb (str head) body form)
               (analyze eb (single-body! eb (str head) body form)))
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

(def ^:private wrapper-body-ops
  "Analyzed node kinds that CAN wrap markup rather than being it — the shapes
  a `for` body takes when a row is present one form deeper.

  A body of one of these kinds is a candidate for the `indirect-list-body`
  sentence rather than the missing-`:key` one, but only once
  [[wraps-markup-node?]] confirms a markup row is actually in there: the
  mistake that sentence names is a keyed row with something standing between
  it and the `for`, and a wrapper bottoming out at an `:expr`/`:text`/`:slot`
  has no row to stand in front of and keeps the general message."
  #{:let :letfn :if :case})

(defn- wraps-markup-node?
  "Does this analyzed `for`-body wrapper reach a MARKUP ROW — an element,
  view, foreign or fragment — in a rendered position?

  That is the fact the `indirect-list-body` diagnostic asserts (\"the keyed
  row is present, one form deeper\"), so it is established BEFORE the
  diagnostic claims it. A `:let`/`:letfn`/`:if`/`:case` bottoming out at an
  `:expr`/`:text`/`:slot` carries no row at all, and reporting it as a
  wrapper around a keyed node would state a fact the analyzer has not proved
  (rf2-drpa3.164). The walk mirrors [[presence-keyed-child?]]'s node shapes:
  a branch reaches markup when ANY rendered arm does — one wrapped row is
  enough to make the wrapper the thing between the `for` and it."
  [ast]
  (case (:op ast)
    (:element :view :foreign :fragment) true
    (:let :letfn)                       (wraps-markup-node? (:body ast))
    :if   (or (wraps-markup-node? (:then ast)) (wraps-markup-node? (:else ast)))
    :case (boolean (or (some wraps-markup-node? (map second (:clauses ast)))
                       (and (not= ::none (:default ast))
                            (wraps-markup-node? (:default ast)))))
    false))

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
      ;; row-scoped and therefore rejects sub sites.
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
            e-body    (-> e-final (update :path conj :for)
                          (assoc :hooks-region? false))
            body-form (single-body! e "for" (vec body) form)
            _         (when (and (seq? body-form) (= 'for (first body-form)))
                        (env/fail! e :rf.ui.compile/nested-for-body
                                   (str "a for directly inside a for body — express "
                                        "nested iteration as multiple binding pairs in "
                                        "ONE for: (for [x xs, y (f x)] ...) — one keyed "
                                        "list site (Q6 pin)")
                                   {:form form}))
            body-ast  (analyze e-body body-form)]
        ;; The WRAPPED body is its own rejection, because it is its own
        ;; mistake (rf2-drpa3.164). `(for [i is] (let [r (nth rows i)] [:div
        ;; {:key (:id r)} …]))` is the natural Clojure spelling, and the row
        ;; inside it IS keyed — reporting a missing key sends the author to a
        ;; line that has one. The rule they need is that the body must BE the
        ;; node, and the fix is `for`'s own `:let` modifier, which this
        ;; grammar fully supports. But the sentence claims a row is present
        ;; one form deeper, so it is raised only once `wraps-markup-node?`
        ;; proves one is: `(for [x xs] (let [y x] (str y)))` wraps a scalar,
        ;; not a keyed node, and keeps the general unkeyed-list-item answer
        ;; rather than a sentence about a row that isn't there.
        (when (and (contains? wrapper-body-ops (:op body-ast))
                   (wraps-markup-node? body-ast))
          (env/fail! e :rf.ui.compile/indirect-list-body
                     (str "a for body must BE the keyed node — an element, a "
                          "view or a fragment, with nothing standing between "
                          "the for and it — and this body is a "
                          (name (:op body-ast)) " wrapping one. A keyed list "
                          "lowers to a direct JS array of rows, so the row has "
                          "to be what the body evaluates to."
                          (if (= :let (:op body-ast))
                            (str " Bind the row's values with for's OWN :let "
                                 "modifier: (for [i (range start end) "
                                 ":let [r (nth rows i)]] [:div {:key (:id r)} …])")
                            (str " Extract a declared child view that does the "
                                 "wrapping, and key it at the call site")))
                     {:form form :op (:op body-ast)}))
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

(defn analyze-view-body
  "Analyze a defview body remainder: zero or
  more leading `(effect …)` statements, then exactly ONE final template. The
  env must carry `:hooks-region? true` and `:self-id`. Returns the body AST
  (a `:hook-prefix` node when effect statements are present)."
  [e forms]
  (analyze-hooks-body e "defview" forms (cons 'defview-body forms)))

(defn- analyze-list [e form]
  (let [head (first form)
        fam  (if-let-family-config e head)]
    (cond
      ;; if-let / when-let / if-some / when-some (rf2-u53yy.4): admitted
      ;; conditional binders, RESOLVER-confirmed against the core binder vars (a
      ;; same-spelled user macro is NOT admitted; a local shadow yields no
      ;; resolution and falls through to an ordinary call). Desugar into the
      ;; analyzer's own let + if and re-analyze — the branch is a conditional
      ;; (its then/else leave the hooks region, exactly like if/when), the init
      ;; lowers a finite reactive site, and the pattern's reactive-escape is
      ;; rejected, all through the existing template machinery.
      fam
      (analyze e (desugar-if-let e head fam (binder-temp (:path e)) form))

      (and (symbol? head)
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
        do       (if (:hooks-region? e)
                   (analyze-hooks-body e "do" (vec (rest form)) form)
                   (analyze e (single-body! e "do" (vec (rest form)) form)))
        for      (analyze-for e form))

      :else
      (cond
        (raw-form? e form)
        (let [[_ x & extra] form]
          (when (or (nil? x) (seq extra))
            (env/fail! e :rf.ui.compile/bad-raw "(v/raw react-element) takes one argument"
                       {:form form}))
          {:op :raw :form (walk-expr e [:raw] x)
           :static? false :path (:path e)})

        (html-form? e form)
        (env/fail! e :rf.ui.compile/html-not-sole-child
                   (str "(v/html ...) must be the sole child of a DOM element "
                        "— here it has no host element to own the markup. Wrap "
                        "it: [:div (v/html s)] (conservative S1 pin)")
                   {:form form})

        (raw-fn-form? e form)
        (env/fail! e :rf.ui.compile/raw-fn-child
                   "(v/raw-fn f) is a callback marker for prop positions, not renderable content"
                   {:form form})

        (slot-form? e form)
        (analyze-slot e form)

        (error-boundary-form? e form)
        (analyze-error-boundary e form)

        (client-only-form? e form)
        (analyze-client-only e form)

        (presence-form? e form)
        (analyze-presence e form)

        (render-fn-form? e form)
        (env/fail! e :rf.ui.compile/render-fn-misplaced
                   (str "(v/render-fn …) is a render-slot callback value, not "
                        "renderable content — invoke it with (v/slot render-fn "
                        "arg…), or pass it as a component prop value")
                   {:form form})

        (spread-form? e form)
        (env/fail! e :rf.ui.compile/bad-spread
                   "(v/spread ...) belongs in a DOM element's props position: [:div (v/spread base overrides)]"
                   {:form form})

        (spread-safe-form? e form)
        (env/fail! e :rf.ui.compile/bad-spread-safe
                   "(v/spread-safe ...) belongs in a DOM element's props position: [:input (v/spread-safe {:value v :on-change …} caller)]"
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
;; frame-provider) and the reactive-authoring verbs (sub / frame,
;; rf2-vxgfnd.266). Both key on Var resolution — so a head equal to the view
;; being `defview`d right now (`:self`) that ALSO resolves to a referred
;; public authoring Var (`(defview sub [] [sub …])` in a namespace that
;; refers `re-frame.freehand/sub`) was reserved-then-rejected BEFORE the Q5
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
      ;; `^{:key …}` is the Reagent spelling, and it is refused HERE — at
      ;; the form that carries it — rather than where its absence is later
      ;; noticed (rf2-drpa3.163). A `for` body written this way used to be
      ;; reported as MISSING a key while the author was looking at one, and
      ;; the same metadata on an ordinary child was ignored outright while
      ;; the interpreted walk silently dropped it. One rule, one sentence,
      ;; every position.
      (when (contains? (meta form) :key)
        (env/fail! e :rf.ui.compile/metadata-key
                   (str "a :key in METADATA — ^{:key …} — on " (pr-str form)
                        ". Freehand reads no metadata-carried contract, so "
                        "this key reaches neither the tree nor React. A key "
                        "is a PROPS slot here, in both modes and on both "
                        "hosts: write [:li {:key k} …], or [:<> {:key k} …] "
                        "around content with no props map of its own. "
                        "(Reagent honours the metadata spelling; this "
                        "substrate has one spelling for a key, and the "
                        "interpreted walk refuses the other rather than "
                        "dropping it.)")
                   {:form form}))
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
        ;; Reserve sub/frame BEFORE generic component classification
        ;; (rf2-vxgfnd.266): a reactive authoring verb can never be
        ;; reclassified as a :foreign component with an empty manifest.
        (reactive-authoring-head-kind e head)
        (analyze-reactive-authoring-head! e form head)
        (symbol? head)   (analyze-component e form)
        :else
        (env/fail! e :rf.ui.compile/dynamic-head
                   (str "dynamic element head " (pr-str head) " — heads must be "
                        "literal (a keyword or a component var). Runtime-chosen "
                        "components are v/view / v/element [WAVE-2]; v/raw "
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
