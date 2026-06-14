(ns re-frame.migration.reg-event-codemod
  "EP-0018 Slice E — v1 -> v2 `reg-event` scanner + conservative codemod.

  EP-0018 collapses re-frame2's three public event-registration forms to one:

    reg-event-fx   ->  reg-event   (pure rename; same semantics)
    reg-event-db   ->  reg-event   with the handler body wrapped `{:db BODY}`
                                   and the `db` arg destructured `{:keys [db]}`
    reg-event-ctx  ->  (removed from the public surface; never auto-rewritten)

  This namespace is the SCANNER + the CONSERVATIVE CODEMOD. It operates purely
  on source TEXT via rewrite-clj (a zipper over the node tree), so formatting
  and comments survive the rewrite and re-frame2 itself is never loaded.

  ---------------------------------------------------------------------------
  WHAT IT REWRITES (and what it deliberately leaves for a human)
  ---------------------------------------------------------------------------

  * reg-event-fx  -> reg-event
      A pure symbol rename. `reg-event` IS today's `reg-event-fx`, so the body
      is byte-for-byte unchanged. Always safe; always applied.

  * reg-event-db  -> reg-event, when the form is the SIMPLE, FAITHFUL shape:
      (reg-event-db ID (fn [DB EV] BODY))            ;; 2-arg handler fn
        =>
      (reg-event    ID (fn [{:keys [db]} EV] {:db BODY}))

      BODY always evaluates to the new app-db (that IS the reg-event-db
      contract), so wrapping it in `{:db BODY}` is mechanical regardless of how
      complex BODY is. The first handler param (`DB`) is rebound so the renamed
      handler reads the db out of the coeffects map. Path-interceptor metadata in
      the optional middle slot (`{:interceptors [(rf/path ...)]}`) is PRESERVED
      untouched.

      FIRST-PARAM REBIND: when the first param is literally `db` (or an `_`/`_x`
      binding that is genuinely never read in the body), it becomes `{:keys [db]}`.
      When it is a plain symbol bound under a DIFFERENT name (a path-scoped slice
      such as `c`), it becomes `{c :db}` — binding the db value back under its
      original name so every body reference to `c` stays resolved WITHOUT the
      codemod rewriting the body. (Rebinding such a param to `{:keys [db]}` would
      orphan every body reference to `c` -> unresolvable-symbol compile error;
      rf2-xhfxcs.15.) An `_`-prefixed name that IS referenced (e.g. `_state` used
      in the body) is treated like any other named slice — it binds back
      `{_state :db}` rather than `{:keys [db]}`, since `_`-prefix is a convention,
      not a guarantee the name is unused (rf2-u6m0o9). The free-occurrence check
      respects inner shadowing, so an inner `let`/`fn` that rebinds the name does
      NOT count as a reference.

      D7 NIL-FLAG: a reg-event-db handler whose BODY can evaluate to `nil` is
      NOT silently rewritten. Under the new model a bare `nil` return is a
      no-op and `{:db nil}` coerces to `{:db {}}` (rf2-ekq28v), so the author
      may now prefer the no-op reading. The codemod FLAGS these (`:flag/nil`)
      for the author to choose the intended reading; it leaves the source
      unchanged.

  * reg-event-db  -> FLAGGED for manual review (not rewritten) when the form is
      COMPLEX: a handler that is not a literal `(fn ...)` (a var, a
      higher-order construction, `comp`, etc.), a multi-arity fn, an `fn` with a
      handler arg that is itself destructured (so the `db` rebinding is unsafe),
      or anything the conservative analysis cannot prove is the simple shape.

  * reg-event-ctx -> ALWAYS FLAGGED, never rewritten. The full-context form is
      removed from the public surface; the replacement is an interceptor
      (`->interceptor`), which is a human-judgment rewrite.

  ---------------------------------------------------------------------------
  PROGRAMMATIC API (the atomic-flip slice calls these corpus-wide)
  ---------------------------------------------------------------------------

    (scan-string s {:keys [file]})  -> [finding ...]
    (scan-file   path)              -> [finding ...]
    (scan-paths  paths)             -> [finding ...]    ;; files + dirs (recursive)
    (rewrite-string s)              -> {:source out :findings [...]}
    (rewrite-file!  path opts)      -> {:path .. :changed? .. :findings [...]}
    (rewrite-paths! paths opts)     -> [{...} ...]

  A `finding` is a map:
    {:file       \"path or nil\"
     :line       42
     :col        3
     :form       :reg-event-db | :reg-event-fx | :reg-event-ctx
     :action     :rewrite | :rename | :flag
     :flag       nil | :nil-capable | :complex | :ctx          ;; when :action :flag
     :target     :reg-event | nil                              ;; suggested target form
     :note       \"human-readable explanation\"}"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Symbol recognition
;; ---------------------------------------------------------------------------

(def ^:private registrar-forms
  "Maps a recognised registrar (by its *simple* name) to the v1 form keyword."
  {"reg-event-db"  :reg-event-db
   "reg-event-fx"  :reg-event-fx
   "reg-event-ctx" :reg-event-ctx})

(defn- registrar-of
  "If `sym` (a clojure symbol like `rf/reg-event-db`, `re-frame.core/reg-event-fx`,
  or bare `reg-event-db`) names one of the three retired registrars, return its
  form keyword; else nil. Matches on the symbol's *name* (post-`/`) so any
  namespace alias is recognised."
  [sym]
  (when (symbol? sym)
    (get registrar-forms (name sym))))

(defn- replace-registrar-name
  "Given a registrar symbol like `rf/reg-event-db`, return the same symbol with
  its name swapped to `reg-event` (alias/namespace preserved)."
  [sym]
  (if-let [ns* (namespace sym)]
    (symbol ns* "reg-event")
    (symbol "reg-event")))

;; ---------------------------------------------------------------------------
;; nil-capability analysis (D7)
;; ---------------------------------------------------------------------------
;;
;; Conservative: we ask "can this BODY (the final form of the handler) evaluate
;; to nil under SOME input?". We answer YES (=> flag) for the shapes a v1
;; reg-event-db body commonly takes that can yield nil, and NO only when the
;; body is provably non-nil (a literal map/vector/etc, or a thread/assoc/update
;; whose head is a guaranteed-non-nil db-builder). When unsure, we say YES
;; (flag) — the safe direction for D7.

(defn- sig-children
  "Non-whitespace, non-comment child nodes of a node."
  [node]
  (->> (n/children node)
       (filter (complement n/whitespace-or-comment?))))

(def ^:private nil-safe-heads
  "Head symbols whose return is a guaranteed-non-nil value when given a
  guaranteed-non-nil first arg (the db). These are the pervasive db-builders; a
  body headed by one of these cannot return nil for a map db."
  #{"assoc" "assoc-in" "update" "update-in" "dissoc" "merge" "merge-with"
    "conj" "into" "concat" "select-keys" "rename-keys" "vary-meta"
    "with-meta" "assoc!" "update-keys" "update-vals"})

(def ^:private nil-capable-heads
  "Head symbols that branch and may yield a falsey/absent value: the classic
  nil-capable reg-event-db shapes."
  #{"if" "if-let" "if-some" "if-not" "when" "when-let" "when-some" "when-not"
    "when-first" "cond" "condp" "case" "and" "or"
    "get" "get-in" "find" "some" "seek" "first" "peek" "next"
    "filter" "remove" "keep"})

(defn- classify-head
  "Classify a head-name string for nil-capability. Returns one of
  :safe / :nil-capable / :unknown."
  [head-name]
  (cond
    (contains? nil-safe-heads head-name)     :safe
    (contains? nil-capable-heads head-name)  :nil-capable
    :else                                    :unknown))

(declare node-nil-capable?)

(defn- list-nil-capable?
  "nil-capability of a list/seq node — its head symbol decides."
  [node]
  (let [kids (sig-children node)
        hd   (first kids)]
    (if (and hd (= :token (n/tag hd)))
      (let [hv (try (n/sexpr hd) (catch Exception _ nil))]
        (if (symbol? hv)
          (let [head-name (name hv)]
            (cond
              ;; some-> / some->> short-circuit to nil by construction.
              (#{"some->" "some->>"} head-name) true

              ;; -> / ->> thread: the value flows to the LAST stage's head.
              (#{"->" "->>"} head-name)
              (let [last-stage (last (rest kids))]
                (cond
                  (nil? last-stage) true
                  ;; (-> db (assoc ...)) — last stage is a list; its head decides
                  (= :list (n/tag last-stage))
                  (list-nil-capable? last-stage)
                  ;; (-> db some-fn) — bare-symbol stage; classify by its name
                  (= :token (n/tag last-stage))
                  (let [v (try (n/sexpr last-stage) (catch Exception _ nil))]
                    (if (symbol? v)
                      (not= :safe (classify-head (name v)))
                      true))
                  :else true))

              ;; when-family: a falsey test makes the form evaluate to nil.
              (#{"when" "when-let" "when-some" "when-not" "when-first"} head-name)
              true

              ;; do / let / letfn / binding: the value is the LAST body form.
              (#{"do" "let" "letfn" "binding"} head-name)
              (let [forms (rest kids)
                    tail  (if (#{"let" "letfn" "binding"} head-name)
                            (rest forms)  ;; skip the binding vector
                            forms)]
                (boolean (node-nil-capable? (last tail))))

              :else
              (case (classify-head head-name)
                :safe        false
                :nil-capable true
                :unknown     true)))
          true))
      true)))

(defn- node-nil-capable?
  "Conservative nil-capability of a single value node (the handler BODY).
  Returns true if the node can evaluate to nil under some input. When unsure we
  answer YES (flag) — the safe direction for D7."
  [node]
  (cond
    (nil? node) false
    (n/whitespace-or-comment? node) false
    :else
    (case (n/tag node)
      ;; Literal collections are never nil.
      (:map :vector :set) false
      :token (let [v (try (n/sexpr node) (catch Exception _ ::err))]
               (cond
                 (= v ::err) true        ;; unreadable -> be safe, flag
                 (nil? v)    true         ;; literal nil
                 (symbol? v) true         ;; a bare local/var — unknown value, flag
                 :else       false))      ;; literal number/string/kw/bool/char — non-nil
      :list  (list-nil-capable? node)
      ;; Anything else (reader macros, quotes, syntax-quote, ...) -> be safe.
      true)))

(defn- body-nil-capable? [node] (node-nil-capable? node))

;; ---------------------------------------------------------------------------
;; reg-event-db form shape analysis
;; ---------------------------------------------------------------------------

(defn- simple-handler-fn?
  "Is `node` a literal single-arity `(fn [DB EV] BODY...)` or `(fn name [DB EV] BODY...)`?
  Returns a map describing it, or nil if it is not the simple shape:
    {:fn-node node
     :name?   bool       ;; carries an optional fn name
     :params  param-vec-node
     :body    [body-node ...]}"
  [node]
  (when (and node (= :list (n/tag node)))
    (let [kids (sig-children node)
          hd   (first kids)]
      (when (and hd (= :token (n/tag hd))
                 (= 'fn (try (n/sexpr hd) (catch Exception _ nil))))
        (let [after-fn (rest kids)
              named?   (and (seq after-fn) (= :token (n/tag (first after-fn))))
              after    (if named? (rest after-fn) after-fn)
              params   (first after)
              body     (rest after)]
          (when (and params (= :vector (n/tag params)) (seq body))
            {:fn-node node :name? named? :params params :body (vec body)}))))))

(defn- params-simple?
  "Is the param vector the simple `[DB EV]` / `[DB _]` / `[DB]` shape with the
  first param a plain symbol (so we can safely rebind it to `{:keys [db]}`)?
  Returns the first-param node when simple, nil otherwise. A first param that is
  itself a destructure (`{:keys [...]}` / `[...]`) is NOT simple — flag it."
  [params-node]
  (let [ps (sig-children params-node)
        p0 (first ps)]
    (when (and p0 (= :token (n/tag p0))
               (symbol? (try (n/sexpr p0) (catch Exception _ nil))))
      p0)))

;; ---------------------------------------------------------------------------
;; Finding construction
;; ---------------------------------------------------------------------------

(defn- pos-of [zloc]
  (let [p (z/position zloc)]
    {:line (first p) :col (second p)}))

(defn- finding
  [base extra]
  (merge {:file nil :line nil :col nil :form nil :action nil
          :flag nil :target nil :note nil}
         base extra))

;; ---------------------------------------------------------------------------
;; Per-call-site analysis + rewrite
;; ---------------------------------------------------------------------------

(defn- analyse-call
  "Analyse a registrar call-site list zipper `zloc` (head already known to be a
  retired registrar `form-kw`). Returns {:finding f :rewrite-fn (fn [zloc] zloc')}.
  `rewrite-fn` mutates the zipper at this node when the action is a structural
  rewrite; for :flag / :rename-symbol-only it may be nil (handled by caller)."
  [zloc form-kw {:keys [file force-db-wrap?]}]
  (let [{:keys [line col]} (pos-of zloc)
        base {:file file :line line :col col :form form-kw}]
    (case form-kw
      :reg-event-fx
      {:finding (finding base {:action :rename :target :reg-event
                               :note "reg-event-fx -> reg-event (pure rename; same semantics)"})
       :kind :rename}

      :reg-event-ctx
      {:finding (finding base {:action :flag :flag :ctx :target nil
                               :note "reg-event-ctx removed from the public surface; rewrite to an interceptor (->interceptor) by hand"})
       :kind :flag}

      :reg-event-db
      (let [kids        (sig-children (z/node zloc))
            ;; kids: (reg-event-db ID ?meta HANDLER)
            handler-node (last kids)
            simple-fn    (simple-handler-fn? handler-node)]
        (cond
          (nil? simple-fn)
          {:finding (finding base {:action :flag :flag :complex :target nil
                                   :note "reg-event-db handler is not a literal single-arity (fn [db ev] ...) — review by hand (var/higher-order/multi-arity/anon-shorthand)"})
           :kind :flag}

          ;; first param not a plain symbol => unsafe to rebind to {:keys [db]}
          (nil? (params-simple? (:params simple-fn)))
          {:finding (finding base {:action :flag :flag :complex :target nil
                                   :note "reg-event-db handler's first param is destructured (or absent) — db-rebinding is unsafe; review by hand"})
           :kind :flag}

          ;; D7: body can evaluate to nil => flag, do not rewrite — UNLESS the
          ;; caller opts into `:force-db-wrap?` (the EP-0018 Slice-Z framework
          ;; corpus sweep; `{:db BODY}` is the faithful reading for our own test
          ;; handlers — the D7 author-choice gate is a v1-MIGRATION default, not
          ;; relevant to in-repo test code we control).
          (and (not force-db-wrap?) (body-nil-capable? (last (:body simple-fn))))
          {:finding (finding base {:action :flag :flag :nil-capable :target :reg-event
                                   :note "reg-event-db body can evaluate to nil; under v2 a bare nil is a no-op and {:db nil} coerces to {:db {}} -- choose the intended reading by hand (D7)"})
           :kind :flag}

          :else
          {:finding (finding base {:action :rewrite :target :reg-event
                                   :note "reg-event-db -> reg-event: db -> {:keys [db]}, body wrapped {:db BODY}"})
           :kind :rewrite-db
           :simple-fn simple-fn})))))

(defn- underscore-prefixed-symbol?
  "Is `sym` an `_` or an `_`-prefixed name (the conventional \"ignore me\"
  spelling)? Such a name is USUALLY an unused binding — but it is a perfectly
  legal referenceable local, so whether it actually needs a name-preserving
  rebind depends on whether it occurs free in the body (`symbol-free-in-body?`)."
  [sym]
  (and (symbol? sym)
       (let [nm (name sym)]
         (or (= nm "_") (str/starts-with? nm "_")))))

;; ---------------------------------------------------------------------------
;; Free-occurrence analysis (shadowing-aware)
;; ---------------------------------------------------------------------------
;;
;; The first-param rebind decision (`{:keys [db]}` vs `{S :db}`) only matters
;; for `_`-prefixed names: a plain non-`db` symbol always rebinds (the
;; rf2-xhfxcs.15 transform), and a literal `db` always keeps `{:keys [db]}`.
;; For an `_`-prefixed name we must distinguish:
;;
;;   * REFERENCED in the body  -> bind back `{_state :db}` (else the body's
;;                                `_state` refs become unbound — rf2-u6m0o9).
;;   * NOT referenced          -> `{:keys [db]}` (nothing to rebind).
;;
;; "Referenced" means: the symbol occurs FREE somewhere in the body, i.e. not
;; captured by an inner binding form that shadows it (a `let`/`fn`/`loop`/… that
;; rebinds the same name). We walk the body's sexpr, tracking the set of names a
;; binding form has shadowed; a bare occurrence of the target name that is NOT in
;; the shadowed set counts as a free reference.

(def ^:private binding-vector-forms
  "Head symbols whose SECOND child is a binding vector of name/value pairs
  (`let`-shape): the LHS names shadow within the rest of the form."
  '#{let let* loop loop* binding if-let when-let if-some when-some
     with-open with-local-vars with-redefs})

(def ^:private seq-binding-forms
  "Head symbols whose SECOND child is a seq-binding vector (`for`/`doseq`-shape):
  `[x coll, y coll2, :let [...] ...]`. We treat every even-index non-keyword
  entry as a bound LHS (conservative: over-binding only makes us MORE likely to
  call a name unreferenced, but we additionally honour `:let` LHS names)."
  '#{for doseq})

(defn- collect-binding-names
  "Collect every symbol that a destructuring LHS form `lhs` binds. Handles plain
  symbols, vector/seq destructuring (incl. `:as`/`& rest`), and map destructuring
  (`:keys`/`:strs`/`:syms`, `:as`, and `{local key}` pairs). Conservative: when
  unsure we still add any contained symbol, which only widens shadowing."
  [lhs]
  (cond
    (symbol? lhs) #{lhs}

    (vector? lhs)
    (into #{} (mapcat collect-binding-names) lhs)

    (map? lhs)
    (reduce-kv
      (fn [acc k v]
        (cond
          ;; {:keys [a b]} / {:strs [..]} / {:syms [..]}
          (and (keyword? k) (#{:keys :strs :syms} k) (vector? v))
          (into acc (map (fn [s] (symbol (name s)))) v)
          ;; {:as whole}
          (= :as k) (conj acc v)
          ;; {:or {...}} / {:keys ...} handled above; skip other keyword keys
          (keyword? k) acc
          ;; {local :some/key} — the LHS (k) is the binding
          :else (into acc (collect-binding-names k))))
      #{}
      lhs)

    :else #{}))

(declare free-in-form?)

(defn- free-in-children?
  "Does `target` occur free in any of `forms` under the current `bound` set?"
  [target forms bound]
  (boolean (some #(free-in-form? target % bound) forms)))

(defn- free-in-binding-vector-form?
  "Free-occurrence within a `let`-shaped form `(head [b0 v0 b1 v1 ...] body...)`.
  Each value `vi` is evaluated under the names bound by `b0..b(i-1)`; the body is
  evaluated under all LHS names. Returns true iff `target` occurs free anywhere."
  [target form bound]
  (let [bvec  (second form)
        body  (drop 2 form)]
    (if (vector? bvec)
      (loop [pairs (partition-all 2 bvec)
             bnd   bound]
        (if-let [[lhs v] (first pairs)]
          (cond
            ;; value occurs free under the names bound so far
            (free-in-form? target v bnd) true
            :else (recur (rest pairs)
                         (into bnd (collect-binding-names lhs))))
          ;; binding vector exhausted: test the body under all bound names
          (free-in-children? target body bnd)))
      ;; malformed binding vector — treat children conservatively
      (free-in-children? target (rest form) bound))))

(defn- free-in-seq-binding-form?
  "Free-occurrence within a `for`/`doseq`-shaped form. The seq-binding vector
  alternates LHS / expr (with `:let`/`:when`/`:while` modifier clauses). We bind
  every LHS (and `:let` LHS) name; exprs/guards and the body are tested for the
  target as free."
  [target form bound]
  (let [bvec (second form)
        body (drop 2 form)]
    (if (vector? bvec)
      (loop [pairs (partition-all 2 bvec)
             bnd   bound]
        (if-let [[lhs v] (first pairs)]
          (cond
            (= :let lhs)
            ;; v is a let-style binding vector
            (let [lv (if (vector? v) v [])]
              (if (loop [ps (partition-all 2 lv) b bnd]
                    (if-let [[l e] (first ps)]
                      (if (free-in-form? target e b)
                        :free
                        (recur (rest ps) (into b (collect-binding-names l))))
                      false))
                true
                (recur (rest pairs)
                       (into bnd (mapcat (fn [[l _]] (collect-binding-names l)))
                             (partition-all 2 lv)))))

            (#{:when :while} lhs)
            (if (free-in-form? target v bnd) true (recur (rest pairs) bnd))

            :else
            (if (free-in-form? target v bnd)
              true
              (recur (rest pairs) (into bnd (collect-binding-names lhs)))))
          (free-in-children? target body bnd)))
      (free-in-children? target (rest form) bound))))

(defn- fn-arity-binds
  "Given a single fn arity `(params body...)` (params a vector), return the set
  of names its param vector binds."
  [arity]
  (let [params (first arity)]
    (if (vector? params) (collect-binding-names params) #{})))

(defn- free-in-fn-form?
  "Free-occurrence within an `(fn name? [params] body...)` / multi-arity fn.
  Each arity's params shadow within that arity's body; an optional self-name also
  shadows."
  [target form bound]
  (let [after (rest form)
        [self after] (if (symbol? (first after))
                       [(first after) (rest after)]
                       [nil after])
        bound (cond-> bound self (conj self))]
    (if (vector? (first after))
      ;; single arity: (fn name? [params] body...)
      (free-in-children? target (rest after)
                         (into bound (collect-binding-names (first after))))
      ;; multi-arity: (fn name? ([p] b) ([p q] b) ...)
      (boolean
        (some (fn [arity]
                (when (seq? arity)
                  (free-in-children? target (rest arity)
                                     (into bound (fn-arity-binds arity)))))
              after)))))

(defn- free-in-letfn-form?
  "Free-occurrence within `(letfn [(f [..] ..) (g [..] ..)] body...)`. The fn
  NAMES are mutually in scope across all fnspecs and the body; each fnspec's
  params shadow within its own body."
  [target form bound]
  (let [specs (second form)
        body  (drop 2 form)]
    (if (vector? specs)
      (let [names (into #{} (keep #(when (seq? %) (first %))) specs)
            bnd   (into bound names)]
        (or (boolean
              (some (fn [spec]
                      (when (seq? spec)
                        (free-in-children? target (drop 2 spec)
                                           (into bnd (fn-arity-binds (rest spec))))))
                    specs))
            (free-in-children? target body bnd)))
      (free-in-children? target (rest form) bound))))

(defn- free-in-form?
  "Does symbol `target` occur FREE in `form`, given the set `bound` of names
  already shadowed by an enclosing binding form? Walks the sexpr, descending into
  binding forms (`let`/`loop`/`fn`/`for`/`letfn`/…) with their LHS names added to
  `bound`, so a shadowed inner occurrence does NOT count as a reference."
  ([target form] (free-in-form? target form #{}))
  ([target form bound]
   (cond
     (symbol? form) (and (= form target) (not (contains? bound target)))

     (or (vector? form) (set? form))
     (free-in-children? target (seq form) bound)

     (map? form)
     (or (free-in-children? target (keys form) bound)
         (free-in-children? target (vals form) bound))

     (seq? form)
     (let [hd (first form)]
       (cond
         (and (symbol? hd) (contains? binding-vector-forms hd))
         (free-in-binding-vector-form? target form bound)

         (and (symbol? hd) (contains? seq-binding-forms hd))
         (free-in-seq-binding-form? target form bound)

         (and (symbol? hd) (#{'fn 'fn*} hd))
         (free-in-fn-form? target form bound)

         (and (symbol? hd) (= 'letfn hd))
         (free-in-letfn-form? target form bound)

         ;; quote: nothing inside a (quote ...) is a runtime reference
         (and (symbol? hd) (= 'quote hd)) false

         :else (free-in-children? target form bound)))

     :else false)))

(defn- symbol-referenced-in-body?
  "Does the first-param symbol (`p0-node`) occur FREE — i.e. as a live reference,
  not captured by an inner shadowing binding — anywhere in the handler `body`
  (a vector of value nodes)? Conservative on unreadable forms: a body node whose
  sexpr cannot be read is treated as POSSIBLY referencing the symbol (so we keep
  the safe name-preserving rebind)."
  [sym body-nodes]
  (boolean
    (some (fn [node]
            (let [form (try (n/sexpr node) (catch Exception _ ::err))]
              (if (= form ::err)
                true                                  ;; unreadable -> assume referenced
                (free-in-form? sym form))))
          body-nodes)))

(defn- db-coeffect-destructure-node
  "The `{:keys [db]}` coeffects destructure node — reads the db out of the
  coeffects map under the conventional local name `db`."
  []
  (n/coerce '{:keys [db]}))

(defn- renamed-db-destructure-node
  "An associative-destructure node `{S :db}` that binds the ORIGINAL first-param
  symbol `S` to the `:db` coeffect. Used when the v1 handler bound its db value
  under a non-`db` name (a path-scoped slice such as `c`): rebinding to
  `{:keys [db]}` would orphan every body reference to `S`, so we instead bind the
  db value back under `S` and leave the body byte-for-byte unchanged. This is the
  hygienic, faithful transform — no body walking, so shadowing can't be misread."
  [sym-node]
  (n/map-node
    [(n/token-node (n/sexpr sym-node)) (n/spaces 1) (n/keyword-node :db)]))

(defn- first-param-replacement
  "Choose the replacement node for the handler's first param, given the param
  node `p0` and the handler `body` (a vector of value nodes).

    * literal `db`                        -> `{:keys [db]}` (canonical)
    * `_`-prefixed name NOT free in body  -> `{:keys [db]}` (nothing to rebind)
    * `_`-prefixed name FREE in the body  -> `{S :db}` (rf2-u6m0o9): the `_`-name
        is actually referenced, so it must bind back under its original name or
        every body reference becomes unbound.
    * any other plain symbol `S`          -> `{S :db}` (rf2-xhfxcs.15): the db
        value was bound under a non-`db` name; rebind it back so the body's `S`
        references stay resolved without touching the body.

  Only the `_`-prefixed case consults the body (via `symbol-referenced-in-body?`,
  which is shadowing-aware); the other branches are name-only decisions."
  [p0 body]
  (let [sym (try (n/sexpr p0) (catch Exception _ nil))]
    (cond
      (= sym 'db)
      (db-coeffect-destructure-node)

      (underscore-prefixed-symbol? sym)
      (if (symbol-referenced-in-body? sym body)
        (renamed-db-destructure-node p0)   ;; referenced -> bind back {_x :db}
        (db-coeffect-destructure-node))    ;; truly unused -> {:keys [db]}

      :else
      (renamed-db-destructure-node p0))))

(defn- rewrite-db-handler
  "Given the handler `(fn [DB EV] BODY)` node and its analysis, return the new
  handler node `(fn [{:keys [db]} EV] {:db BODY})`, preserving fn name, the
  rest of the params, and any intervening whitespace in the params/body as much
  as rewrite-clj allows.

  When the first param is a plain symbol other than `db` (a v1 slice bound under
  a different name, e.g. `c`), the param is rebound `{c :db}` — binding the db
  value back under its original name — so every body reference resolves without
  the codemod rewriting the body (which would have to reason about shadowing)."
  [handler-node {:keys [params body]}]
  (let [p0        (params-simple? params)
        ;; Replace the first param token with the db destructure node. The
        ;; choice may consult the body (an `_`-prefixed name that is actually
        ;; referenced must bind back under its name — rf2-u6m0o9).
        keys-node (first-param-replacement p0 body)
        new-params (n/replace-children
                     params
                     (map (fn [c] (if (identical? c p0) keys-node c))
                          (n/children params)))
        ;; Wrap only the LAST body form in {:db ...}; earlier body forms (side
        ;; effects / lets at the top of an implicit do) are preserved verbatim.
        ;; The inner body node is reused unchanged, so its formatting/comments
        ;; survive the wrap.
        last-form  (last body)
        db-map     (n/map-node
                     [(n/keyword-node :db) (n/spaces 1) last-form])]
    (n/replace-children
      handler-node
      (map (fn [c]
             (cond
               (identical? c params)    new-params
               (identical? c last-form) db-map
               :else                    c))
           (n/children handler-node)))))

(defn- apply-rewrite-at
  "At the registrar call-site zipper `zloc`, apply the structural rewrite for
  `analysis`. Returns the (possibly mutated) zipper positioned at the call-site."
  [zloc form-kw analysis]
  (case (:kind analysis)
    :flag zloc                          ;; leave source unchanged
    :rename
    ;; rename the head symbol reg-event-fx -> reg-event
    (let [head-z (z/down zloc)
          sym    (z/sexpr head-z)]
      (z/up (z/replace head-z (n/token-node (replace-registrar-name sym)))))
    :rewrite-db
    (let [head-z   (z/down zloc)
          sym      (z/sexpr head-z)
          ;; rename head
          zloc1    (z/up (z/replace head-z (n/token-node (replace-registrar-name sym))))
          ;; rewrite handler node (the last child)
          node     (z/node zloc1)
          kids     (n/children node)
          simple   (:simple-fn analysis)
          old-h    (:fn-node simple)
          new-h    (rewrite-db-handler old-h simple)
          new-kids (map (fn [c] (if (identical? c old-h) new-h c)) kids)]
      (z/replace zloc1 (n/replace-children node new-kids)))
    zloc))

;; ---------------------------------------------------------------------------
;; Traversal
;; ---------------------------------------------------------------------------

(defn- registrar-call?
  "If zloc is positioned at a list whose head token names a retired registrar,
  return its form keyword; else nil."
  [zloc]
  (when (and (= :list (z/tag zloc)))
    (let [head (z/down zloc)]
      (when (and head (= :token (z/tag head)))
        (registrar-of (try (z/sexpr head) (catch Exception _ nil)))))))

(defn- walk
  "Walk every node of the zipper rooted at `zroot` (the value-stripping zipper
  positioned at its first form), collecting findings and — when `rewrite?` —
  applying the codemod in place. Returns {:zip <last-zloc> :findings v}."
  [zroot {:keys [rewrite?] :as opts}]
  (loop [zloc     zroot
         findings (transient [])]
    (let [form-kw (registrar-call? zloc)
          [zloc' fs] (if form-kw
                       (let [{:keys [finding] :as analysis} (analyse-call zloc form-kw opts)
                             z* (if rewrite? (apply-rewrite-at zloc form-kw analysis) zloc)]
                         [z* (conj! findings finding)])
                       [zloc findings])
          nxt (z/next zloc')]
      (if (z/end? nxt)
        {:zip nxt :findings (persistent! fs)}
        (recur nxt fs)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn scan-string
  "Scan a source string; return a vector of findings (no rewrite)."
  ([s] (scan-string s {}))
  ([s opts]
   (let [zroot (z/of-string s {:track-position? true})]
     (:findings (walk zroot (assoc opts :rewrite? false))))))

(defn rewrite-string
  "Apply the conservative codemod to a source string. Returns
  {:source <new-source> :findings [...]}. The source is unchanged for any site
  whose action is :flag."
  ([s] (rewrite-string s {}))
  ([s opts]
   (let [zroot  (z/of-string s {:track-position? true})
         {:keys [zip findings]} (walk zroot (assoc opts :rewrite? true))]
     {:source   (z/root-string (or zip zroot))
      :findings findings})))

(defn scan-file
  "Scan a single file path; return findings stamped with :file."
  [path]
  (let [s (slurp path)]
    (scan-string s {:file (str path)})))

(defn- clj-source-file? [^java.io.File f]
  (and (.isFile f)
       (let [n (.getName f)]
         (or (str/ends-with? n ".clj")
             (str/ends-with? n ".cljc")
             (str/ends-with? n ".cljs")))))

(defn- expand-paths
  "Expand a seq of paths (files or dirs) into a seq of source files."
  [paths]
  (->> paths
       (mapcat (fn [p]
                 (let [f (io/file p)]
                   (cond
                     (.isDirectory f) (filter clj-source-file? (file-seq f))
                     (.isFile f)      [f]
                     :else            []))))
       distinct))

(defn scan-paths
  "Scan a seq of file/dir paths (dirs walked recursively for .clj/.cljc/.cljs);
  return all findings."
  [paths]
  (vec (mapcat (fn [f] (scan-string (slurp f) {:file (str f)}))
               (expand-paths paths))))

(defn rewrite-file!
  "Apply the codemod to a file. With `{:write? true}` the file is overwritten
  in place when it changes; otherwise it is a dry run. Returns
  {:path .. :changed? .. :findings [...]}."
  ([path] (rewrite-file! path {}))
  ([path {:keys [write? force-db-wrap?]}]
   (let [orig (slurp path)
         {:keys [source findings]} (rewrite-string orig (cond-> {:file (str path)}
                                                          force-db-wrap? (assoc :force-db-wrap? true)))
         changed? (not= orig source)]
     (when (and write? changed?)
       (spit path source))
     {:path (str path) :changed? changed? :findings findings :source source})))

(defn rewrite-paths!
  "Apply the codemod across a seq of file/dir paths. See `rewrite-file!`."
  ([paths] (rewrite-paths! paths {}))
  ([paths opts]
   (mapv #(rewrite-file! % opts) (expand-paths paths))))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(defn- print-findings [findings]
  (doseq [{:keys [file line col form action flag note]} findings]
    (println (format "%s:%s:%s  %-14s %-8s %s"
                     (or file "-") (or line "-") (or col "-")
                     (name form) (name action)
                     (str (when flag (str "[" (name flag) "] ")) note)))))

(defn- summary [findings]
  (let [by (group-by :action findings)]
    {:total   (count findings)
     :rewrite (count (:rewrite by))
     :rename  (count (:rename by))
     :flag    (count (:flag by))}))

(defn -main
  "CLI: scan (default) or --rewrite a file set.

    clojure -M:run PATH ...               ;; scan + print findings
    clojure -M:run --rewrite PATH ...     ;; dry-run rewrite + print findings
    clojure -M:run --rewrite --write PATH ;; rewrite IN PLACE"
  [& args]
  (let [rewrite?       (some #{"--rewrite"} args)
        write?         (some #{"--write"} args)
        force-db-wrap? (some #{"--force-db-wrap"} args)
        paths          (remove #{"--rewrite" "--write" "--force-db-wrap"} args)]
    (when (empty? paths)
      (println "usage: clojure -M:run [--rewrite] [--write] [--force-db-wrap] PATH ...")
      (System/exit 2))
    (if rewrite?
      (let [results (rewrite-paths! paths {:write? write? :force-db-wrap? force-db-wrap?})
            findings (mapcat :findings results)
            changed (filter :changed? results)]
        (print-findings findings)
        (println)
        (println (format "%d file(s) %s; summary: %s"
                         (count changed)
                         (if write? "rewritten" "would change (dry run)")
                         (summary findings))))
      (let [findings (scan-paths paths)]
        (print-findings findings)
        (println)
        (println (format "summary: %s" (summary findings)))))))
